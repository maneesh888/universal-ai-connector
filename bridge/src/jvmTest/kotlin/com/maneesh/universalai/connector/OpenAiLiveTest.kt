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
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.internal.provider.openai.OPENAI_INVALID_REQUEST_MESSAGE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Explicit P4-C provider smoke tests.
 *
 * The ordinary jvmTest task excludes this class. scripts/check-live.sh supplies and exact-head
 * binds the required process environment without retaining any credential in test state or output.
 */
class OpenAiLiveTest {
    @Test
    fun minimalNonStreamingResponseTranslatesToCanonicalOutput(): Unit = runBlocking {
        connector().use { connector ->
            val response = connector.respond(liveRequest("Reply with one short word: ready."))

            assertTrue(response.outputs.isNotEmpty())
            assertTrue(response.outputs.all { output -> output.text?.isNotBlank() == true })
            assertTrue(response.target.providerId == OPENAI_PROVIDER_ID)
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
                        prompt = "Return the word ready in the answer field.",
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
    fun intentionalUnknownModelErrorMapsToSafeCanonicalValidationFailure(): Unit = runBlocking {
        connector().use { connector ->
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.respond(
                        liveRequest(
                            prompt = "This request intentionally selects an unavailable model.",
                            modelId = "uac-p4c-intentional-unknown-model",
                        ),
                    )
                }

            assertEquals(UniversalAiErrorCategory.Validation, failure.error.category)
            assertEquals("provider_invalid_request", failure.error.code.rawValue)
            assertEquals(OPENAI_INVALID_REQUEST_MESSAGE, failure.message)
        }
    }

    @Test
    fun cancellingPendingResponseRemainsCallerCancellation(): Unit = runBlocking {
        val credentialResolved = CompletableDeferred<Unit>()
        configuredConnector {
            credentialResolved.complete(Unit)
            requiredEnvironment("OPENAI_API_KEY")
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

    private fun connector(): UniversalAiConnector =
        configuredConnector {
            requiredEnvironment("OPENAI_API_KEY")
        }

    private fun configuredConnector(
        credentialSupplier: () -> String,
    ): UniversalAiConnector =
        UniversalAiConnector(
            UniversalAiConnectorConfiguration(
                listOf(
                    UniversalAiProviderConfiguration(
                        providerId = OPENAI_PROVIDER_ID,
                        baseUrl = "https://api.openai.com/v1",
                        credentialSupplier = credentialSupplier,
                    ),
                ),
            ),
        )

    private fun liveRequest(
        prompt: String,
        responseFormat: UniversalAiResponseFormat = UniversalAiResponseFormat.PlainText,
        modelId: String = requiredEnvironment("OPENAI_LIVE_MODEL"),
    ): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = OPENAI_PROVIDER_ID,
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
        val OPENAI_PROVIDER_ID: ProviderId = ProviderId.of("openai")

        fun requiredEnvironment(name: String): String =
            checkNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) {
                "OpenAI live verification is not configured; see .env.live.example."
            }
    }
}
