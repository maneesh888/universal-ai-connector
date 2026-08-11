package com.maneesh.universalai.connector

import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.StructuredOutputSchema
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiGenerationParameters
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormat
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.internal.provider.chatcompletions.hasChatCompletionsEventStreamContentType
import com.maneesh.universalai.connector.internal.provider.openaicompatible.OpenAiCompatibleChatCompletionsAdapter
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportRequest
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportResponse
import com.maneesh.universalai.connector.internal.transport.createDefaultKtorTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Explicit P7-B live compatibility proof against one host-selected LLM Gateway deployment.
 *
 * Ordinary jvmTest excludes this class. scripts/check-live.sh supplies only the Gateway base URL,
 * credential, model, structured-output capability, and exact-head binding through the process
 * environment.
 */
class GatewayLiveTest {
    @Test
    fun minimalNonStreamingResponseAcceptsOptionalUsage(): Unit = runBlocking {
        connector().use { connector ->
            val response =
                withTimeout(LIVE_TIMEOUT_MILLIS) {
                    connector.respond(liveRequest("Reply with exactly one short word: ready."))
                }

            assertTrue(response.outputs.isNotEmpty())
            assertTrue(response.outputs.all { output -> output.text?.isNotBlank() == true })
            assertEquals(OPENAI_COMPATIBLE_PROVIDER_ID, response.target.providerId)
            response.usage?.let { usage ->
                assertTrue(usage.inputTokens >= 0)
                assertTrue(usage.outputTokens >= 0)
                assertTrue(usage.totalTokens >= 0)
            }
        }
    }

    @Test
    fun selectedGatewayModelReturnsRevalidatedStructuredOutput(): Unit = runBlocking {
        if (!requiredStructuredOutputSupport()) {
            return@runBlocking
        }
        connector().use { connector ->
            val response =
                withTimeout(LIVE_TIMEOUT_MILLIS) {
                    connector.respond(structuredLiveRequest())
                }
            val output = response.outputs.single()

            assertNull(output.text)
            val structured = assertNotNull(output.structuredJson)
            assertTrue(structured.toJson().contains("\"answer\":\"ready\""))
        }
    }

    @Test
    fun selectedGatewayModelStreamsWithOneCanonicalTerminal(): Unit = runBlocking {
        connector().use { connector ->
            val events =
                withTimeout(LIVE_TIMEOUT_MILLIS) {
                    connector
                        .stream(liveRequest("Reply with exactly one short word: ready."))
                        .toList()
                }

            assertTrue(events.isNotEmpty())
            assertEquals(UniversalAiStreamEventType.ResponseStarted, events.first().type)
            assertEquals(
                (1L..events.size.toLong()).toList(),
                events.map(UniversalAiStreamEvent::sequence),
            )
            val deltas =
                events
                    .filter { event -> event.type == UniversalAiStreamEventType.OutputDelta }
                    .mapNotNull(UniversalAiStreamEvent::delta)
            assertTrue(deltas.isNotEmpty())
            val completedOutput =
                assertNotNull(
                    events
                        .single { event ->
                            event.type == UniversalAiStreamEventType.OutputCompleted
                        }.output,
                )
            assertEquals(deltas.joinToString(""), completedOutput.text)
            assertNotNull(
                events.single { event -> event.type == UniversalAiStreamEventType.UsageUpdated }.usage,
            )
            assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
            assertEquals(UniversalAiStreamEventType.ResponseCompleted, events.last().type)
            assertEquals(OPENAI_COMPATIBLE_PROVIDER_ID, events.last().response?.target?.providerId)
        }
    }

    @Test
    fun invalidGatewayCredentialReturnsOnlyTheFixedCanonicalError(): Unit = runBlocking {
        val credential = requiredEnvironment("GATEWAY_API_KEY")
        connector(credential = "$credential-invalid").use { connector ->
            val failure =
                assertFailsWith<UniversalAiException> {
                    withTimeout(LIVE_TIMEOUT_MILLIS) {
                        connector.respond(liveRequest("Reply with ready."))
                    }
                }

            assertEquals(UniversalAiErrorCategory.Authentication, failure.error.category)
            assertEquals("provider_authentication_failed", failure.error.code.rawValue)
            assertEquals(
                401L,
                failure.error.metadata?.number("statusCode")?.toLongOrNull(),
            )
            assertFalse(failure.error.toJson().contains(credential))
            assertFalse(failure.stackTraceToString().contains(credential))
        }
    }

    @Test
    fun cancellingActiveGatewayResponseRemainsCallerCancellation(): Unit = runBlocking {
        val gatewayResponseStarted = CompletableDeferred<Unit>()
        val delegate = createDefaultKtorTransport()
        val trackingTransport =
            object : ConnectorTransport {
                override suspend fun <Result> execute(
                    request: ConnectorTransportRequest,
                    consumeResponse: suspend (ConnectorTransportResponse) -> Result,
                ): Result =
                    delegate.execute(request) { response ->
                        check(response.statusCode in 200..299)
                        check(response.hasChatCompletionsEventStreamContentType())
                        gatewayResponseStarted.complete(Unit)
                        // Hold the real response open so caller cancellation is the terminal path.
                        awaitCancellation()
                    }

                override fun close() {
                    delegate.close()
                }
            }
        val connector =
            UniversalAiConnector(
                OpenAiCompatibleChatCompletionsAdapter(
                    configuration = providerConfiguration(requiredEnvironment("GATEWAY_API_KEY")),
                    transport = trackingTransport,
                ),
            )

        try {
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) {
                    connector.stream(liveRequest("Reply with exactly one short word: ready.")).collect()
                }

            withTimeout(LIVE_TIMEOUT_MILLIS) {
                gatewayResponseStarted.await()
            }
            pending.cancel()
            assertFailsWith<CancellationException> {
                pending.await()
            }
            assertTrue(gatewayResponseStarted.isCompleted)
        } finally {
            connector.close()
            trackingTransport.close()
        }
    }

    private fun connector(
        credential: String = requiredEnvironment("GATEWAY_API_KEY"),
    ): UniversalAiConnector =
        UniversalAiConnector(
            UniversalAiConnectorConfiguration(
                providers = listOf(providerConfiguration(credential)),
            ),
        )

    private fun providerConfiguration(credential: String): UniversalAiProviderConfiguration =
        UniversalAiProviderConfiguration(
            providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
            baseUrl = requiredEnvironment("GATEWAY_LIVE_BASE_URL"),
            credentialSupplier = { credential },
        )

    private fun liveRequest(prompt: String): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                    modelId = ModelId.of(requiredEnvironment("GATEWAY_LIVE_MODEL")),
                ),
            input =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = prompt,
                    ),
                ),
            generation =
                UniversalAiGenerationParameters(
                    maxOutputTokens = 96,
                    temperature = 0.0,
                ),
        )

    private fun structuredLiveRequest(): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                    modelId = ModelId.of(requiredEnvironment("GATEWAY_LIVE_MODEL")),
                ),
            input =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = "Return answer ready in the requested JSON schema.",
                    ),
                ),
            responseFormat =
                UniversalAiResponseFormat.jsonSchema(
                    StructuredOutputSchema.parse(
                        """
                        {
                          "type":"object",
                          "properties":{"answer":{"type":"string","enum":["ready"]}},
                          "required":["answer"],
                          "additionalProperties":false
                        }
                        """.trimIndent(),
                    ),
                ),
            generation =
                UniversalAiGenerationParameters(
                    maxOutputTokens = 96,
                    temperature = 0.0,
                ),
        )

    private companion object {
        val OPENAI_COMPATIBLE_PROVIDER_ID: ProviderId = ProviderId.of("openai-compatible")
        const val LIVE_TIMEOUT_MILLIS: Long = 60_000

        fun requiredEnvironment(name: String): String =
            checkNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) {
                "Gateway live verification is not configured; see .env.live.example."
            }

        fun requiredStructuredOutputSupport(): Boolean =
            when (requiredEnvironment("GATEWAY_LIVE_STRUCTURED_OUTPUT")) {
                "true" -> true
                "false" -> false
                else -> error("GATEWAY_LIVE_STRUCTURED_OUTPUT must be true or false.")
            }
    }
}
