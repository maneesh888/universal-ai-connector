package com.maneesh.universalai.connector

import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.StructuredOutputSchema
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiGenerationParameters
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiOutputKind
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormat
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.internal.provider.anthropic.ANTHROPIC_NOT_FOUND_MESSAGE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Explicit P5-D provider smoke tests.
 *
 * The ordinary jvmTest task excludes this class. scripts/check-live.sh supplies and exact-head
 * binds the required process environment without retaining any credential or response body.
 */
class AnthropicLiveTest {
    @Test
    fun minimalNonStreamingResponseTranslatesToCanonicalOutput(): Unit = runBlocking {
        connector().use { connector ->
            val response =
                connector.respond(
                    liveRequest("Reply with exactly the single lowercase word ready."),
                )

            assertTrue(response.outputs.isNotEmpty())
            assertTrue(response.outputs.all { output -> output.text?.isNotBlank() == true })
            assertTrue(response.target.providerId == ANTHROPIC_PROVIDER_ID)
            assertNotNull(response.requestId)
            with(assertNotNull(response.usage)) {
                assertTrue(inputTokens >= 0)
                assertTrue(outputTokens >= 0)
                assertTrue(totalTokens > 0)
            }
        }
    }

    @Test
    fun minimalStructuredOutputTranslatesToGovernedCanonicalJson(): Unit = runBlocking {
        val schema =
            StructuredOutputSchema.parse(
                """
                {
                  "type":"object",
                  "properties":{"answer":{"type":"string"}},
                  "required":["answer"],
                  "additionalProperties":false
                }
                """.trimIndent(),
            )
        connector().use { connector ->
            val response =
                connector.respond(
                    liveRequest(
                        prompt = "Return the lowercase word ready in the answer field.",
                        responseFormat = UniversalAiResponseFormat.jsonSchema(schema),
                    ),
                )

            val output = response.outputs.single()
            assertEquals(UniversalAiOutputKind.StructuredJson, output.kind)
            val value = assertNotNull(output.structuredJson)
            assertTrue(value.toJson().contains("\"answer\""))
            assertTrue(value.toJson().contains("ready", ignoreCase = true))
        }
    }

    @Test
    fun minimalStreamingResponseEmitsOrderedContentAndOneValidTerminal(): Unit = runBlocking {
        connector().use { connector ->
            val events =
                connector
                    .stream(liveRequest("Reply with exactly the single lowercase word ready."))
                    .toList()

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
                    events.single { event ->
                        event.type == UniversalAiStreamEventType.OutputCompleted
                    }.output,
                )
            assertEquals(deltas.joinToString(""), completedOutput.text)
            assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
            assertEquals(UniversalAiStreamEventType.ResponseCompleted, events.last().type)
            assertTrue(events.last().terminal)
            assertEquals(completedOutput, events.last().response?.outputs?.single())
        }
    }

    @Test
    fun intentionalUnknownModelErrorMapsToSafeCanonicalNotFoundFailure(): Unit = runBlocking {
        connector().use { connector ->
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.respond(
                        liveRequest(
                            prompt = "This request intentionally selects an unavailable model.",
                            modelId = "uac-p5c-intentional-unknown-model",
                        ),
                    )
                }

            assertEquals(UniversalAiErrorCategory.NotFound, failure.error.category)
            assertEquals("provider_resource_not_found", failure.error.code.rawValue)
            assertEquals(ANTHROPIC_NOT_FOUND_MESSAGE, failure.message)
        }
    }

    @Test
    fun cancellingPendingResponseRemainsCallerCancellation(): Unit = runBlocking {
        val credentialResolved = CompletableDeferred<Unit>()
        configuredConnector {
            credentialResolved.complete(Unit)
            requiredEnvironment("ANTHROPIC_API_KEY")
        }.use { connector ->
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) {
                    connector.respond(
                        liveRequest(
                            "Write a concise explanation of why caller cancellation matters.",
                        ),
                    )
                }

            withTimeout(5_000) {
                credentialResolved.await()
            }
            pending.cancel()
            assertFailsWith<CancellationException> {
                pending.await()
            }
        }
    }

    @Test
    fun cancellingActiveStreamAfterContentEmitsNoLaterConsumerEvent(): Unit = runBlocking {
        connector().use { connector ->
            val events = mutableListOf<UniversalAiStreamEvent>()
            val deltaSeen = CompletableDeferred<Unit>()
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) {
                    connector
                        .stream(
                            liveRequest(
                                "Write several short sentences about cancellation propagation.",
                            ),
                        ).onEach { event ->
                            events += event
                            if (event.type == UniversalAiStreamEventType.OutputDelta) {
                                deltaSeen.complete(Unit)
                                awaitCancellation()
                            }
                        }.collect()
                }

            withTimeout(15_000) {
                deltaSeen.await()
            }
            val eventCountAtCancellation = events.size
            pending.cancel()
            assertFailsWith<CancellationException> {
                pending.await()
            }
            delay(250)
            assertEquals(eventCountAtCancellation, events.size)
            assertTrue(events.none(UniversalAiStreamEvent::terminal))
        }
    }

    private fun connector(): UniversalAiConnector =
        configuredConnector {
            requiredEnvironment("ANTHROPIC_API_KEY")
        }

    private fun configuredConnector(
        credentialSupplier: () -> String,
    ): UniversalAiConnector =
        UniversalAiConnector(
            UniversalAiConnectorConfiguration(
                listOf(
                    UniversalAiProviderConfiguration(
                        providerId = ANTHROPIC_PROVIDER_ID,
                        baseUrl = "https://api.anthropic.com/v1",
                        credentialSupplier = credentialSupplier,
                    ),
                ),
            ),
        )

    private fun liveRequest(
        prompt: String,
        responseFormat: UniversalAiResponseFormat = UniversalAiResponseFormat.PlainText,
        modelId: String = requiredEnvironment("ANTHROPIC_LIVE_MODEL"),
    ): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = ANTHROPIC_PROVIDER_ID,
                    modelId = ModelId.of(modelId),
                ),
            input =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = prompt,
                    ),
                ),
            responseFormat = responseFormat,
            generation =
                UniversalAiGenerationParameters(
                    maxOutputTokens = 128,
                ),
        )

    private companion object {
        val ANTHROPIC_PROVIDER_ID: ProviderId = ProviderId.of("anthropic")

        fun requiredEnvironment(name: String): String =
            checkNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) {
                "Anthropic live verification is not configured; see .env.live.example."
            }
    }
}
