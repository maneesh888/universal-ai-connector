package com.maneesh.universalai.connector

import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.StructuredOutputSchema
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiGenerationParameters
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormat
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Explicit P6-B through P6-D OpenRouter and representative generic compatibility smoke tests.
 *
 * The ordinary jvmTest task excludes this class. scripts/check-live.sh supplies and exact-head
 * binds the required process environment without retaining credentials or provider bodies.
 */
class OpenRouterLiveTest {
    @Test
    fun minimalNonStreamingResponseTranslatesToCanonicalOutput(): Unit = runBlocking {
        connector(OPENROUTER_PROVIDER_ID).use { connector ->
            val response =
                connector.respond(
                    liveRequest("Reply with exactly one short word: ready."),
                )

            assertTrue(response.outputs.isNotEmpty())
            assertTrue(response.outputs.all { output -> output.text?.isNotBlank() == true })
            assertTrue(response.target.providerId == OPENROUTER_PROVIDER_ID)
            with(assertNotNull(response.usage)) {
                assertTrue(inputTokens >= 0)
                assertTrue(outputTokens >= 0)
                assertTrue(totalTokens > 0)
            }
        }
    }

    @Test
    fun selectedOpenRouterModelAcceptsTheGenericCompatibleAdapter(): Unit = runBlocking {
        connector(OPENAI_COMPATIBLE_PROVIDER_ID).use { connector ->
            val response =
                connector.respond(
                    liveRequest(
                        prompt = "Reply with exactly one short word: ready.",
                        providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                    ),
                )

            assertTrue(response.outputs.isNotEmpty())
            assertTrue(response.outputs.all { output -> output.text?.isNotBlank() == true })
            assertTrue(response.target.providerId == OPENAI_COMPATIBLE_PROVIDER_ID)
            with(assertNotNull(response.usage)) {
                assertTrue(inputTokens >= 0)
                assertTrue(outputTokens >= 0)
                assertTrue(totalTokens > 0)
            }
        }
    }

    @Test
    fun selectedModelReturnsRevalidatedStructuredOutputThroughBothAdapters(): Unit = runBlocking {
        listOf(OPENROUTER_PROVIDER_ID, OPENAI_COMPATIBLE_PROVIDER_ID).forEach { providerId ->
            connector(providerId).use { connector ->
                val response = connector.respond(structuredLiveRequest(providerId))
                val output = response.outputs.single()
                assertNull(output.text)
                val structured = assertNotNull(output.structuredJson)
                assertTrue(structured.toJson().contains("\"answer\":\"ready\""))
            }
        }
    }

    @Test
    fun invalidModelReturnsOnlyABoundedCanonicalOpenRouterError(): Unit = runBlocking {
        connector(OPENROUTER_PROVIDER_ID).use { connector ->
            val failure =
                assertFailsWith<com.maneesh.universalai.connector.contract.UniversalAiException> {
                    connector.respond(
                        liveRequest(
                            prompt = "Reply with ready.",
                            modelId = "universal-ai-connector/definitely-not-a-real-model",
                        ),
                    )
                }
            assertTrue(
                failure.error.category == UniversalAiErrorCategory.Validation ||
                    failure.error.category == UniversalAiErrorCategory.NotFound,
            )
            assertTrue(
                failure.error.code.rawValue == "provider_invalid_request" ||
                    failure.error.code.rawValue == "provider_resource_not_found",
            )
            assertTrue(failure.message?.startsWith("OpenRouter ") == true)
            assertNotNull(failure.error.metadata)
        }
    }

    @Test
    fun cancellingPendingResponseRemainsCallerCancellation(): Unit = runBlocking {
        val credentialResolved = CompletableDeferred<Unit>()
        configuredConnector {
            credentialResolved.complete(Unit)
            requiredEnvironment("OPENROUTER_API_KEY")
        }.use { connector ->
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) {
                    connector.respond(
                        liveRequest(
                            "Write a concise explanation of why cancellation matters.",
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

    private fun connector(providerId: ProviderId): UniversalAiConnector =
        configuredConnector(providerId) {
            requiredEnvironment("OPENROUTER_API_KEY")
        }

    private fun configuredConnector(
        providerId: ProviderId = OPENROUTER_PROVIDER_ID,
        credentialSupplier: () -> String,
    ): UniversalAiConnector =
        UniversalAiConnector(
            UniversalAiConnectorConfiguration(
                listOf(
                    UniversalAiProviderConfiguration(
                        providerId = providerId,
                        baseUrl = "https://openrouter.ai/api/v1",
                        credentialSupplier = credentialSupplier,
                    ),
                ),
            ),
        )

    private fun liveRequest(
        prompt: String,
        providerId: ProviderId = OPENROUTER_PROVIDER_ID,
        modelId: String = requiredEnvironment("OPENROUTER_LIVE_MODEL"),
    ): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = providerId,
                    modelId = ModelId.of(modelId),
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
                    maxOutputTokens = 64,
                ),
        )

    private fun structuredLiveRequest(providerId: ProviderId): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = providerId,
                    modelId = ModelId.of(requiredEnvironment("OPENROUTER_LIVE_MODEL")),
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
            generation = UniversalAiGenerationParameters(maxOutputTokens = 64),
        )

    private companion object {
        val OPENROUTER_PROVIDER_ID: ProviderId = ProviderId.of("openrouter")
        val OPENAI_COMPATIBLE_PROVIDER_ID: ProviderId = ProviderId.of("openai-compatible")

        fun requiredEnvironment(name: String): String =
            checkNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) {
                "OpenRouter live verification is not configured; see .env.live.example."
            }
    }
}
