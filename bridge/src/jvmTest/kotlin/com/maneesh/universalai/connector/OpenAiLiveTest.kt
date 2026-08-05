package com.maneesh.universalai.connector

import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiGenerationParameters
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Explicit P4-B provider smoke tests.
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
    fun cancellingPendingResponseRemainsCallerCancellation(): Unit = runBlocking {
        var credentialResolved = false
        configuredConnector {
            credentialResolved = true
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

            assertTrue(credentialResolved)
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

    private fun liveRequest(prompt: String): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = OPENAI_PROVIDER_ID,
                    modelId = ModelId.of(requiredEnvironment("OPENAI_LIVE_MODEL")),
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
