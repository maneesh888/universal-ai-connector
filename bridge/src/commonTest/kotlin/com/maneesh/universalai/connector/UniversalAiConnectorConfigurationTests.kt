package com.maneesh.universalai.connector

import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.contract.UniversalAiException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UniversalAiConnectorConfigurationTests {
    @Test
    fun configurationIsValidatedSortedAndDefensivelyCopied() {
        val providers =
            mutableListOf(
                provider("z-provider"),
                provider("a-provider"),
            )
        val configuration = UniversalAiConnectorConfiguration(providers)
        providers.clear()

        assertEquals(
            listOf("a-provider", "z-provider"),
            configuration.providers.map { provider -> provider.providerId.rawValue },
        )
        assertEquals(
            "https://example.invalid/base/",
            configuration.providers.first().baseUrl,
        )
    }

    @Test
    fun duplicateProviderConfigurationFailsBeforeCredentialUse() {
        var credentialCalls = 0
        val failure =
            assertFailsWith<IllegalArgumentException> {
                UniversalAiConnectorConfiguration(
                    listOf(
                        provider("duplicate") { credentialCalls += 1 },
                        provider("duplicate") { credentialCalls += 1 },
                    ),
                )
            }

        assertEquals("Provider 'duplicate' is configured more than once.", failure.message)
        assertEquals(0, credentialCalls)
    }

    @Test
    fun invalidBaseUrlFailsWithoutCredentialUse() {
        var credentialCalls = 0

        assertFailsWith<UniversalAiException> {
            UniversalAiProviderConfiguration(
                providerId = ProviderId.of("openai"),
                baseUrl = "https://user:password@example.invalid/v1",
                credentialSupplier = {
                    credentialCalls += 1
                    "unused"
                },
            )
        }

        assertEquals(0, credentialCalls)
    }

    @Test
    fun providerCredentialIsNotResolvedDuringConstructionOrDeterministicUse() = runTest {
        var credentialCalls = 0
        val engine =
            MockEngine {
                respond(
                    content = "{}",
                    status = HttpStatusCode.InternalServerError,
                )
            }
        val connector =
            UniversalAiConnector(
                configuration =
                    UniversalAiConnectorConfiguration(
                        listOf(
                            provider("openai") {
                                credentialCalls += 1
                            },
                        ),
                    ),
                httpEngine = engine,
            )

        try {
            assertEquals(0, credentialCalls)
            connector.respond(
                UniversalAiRequest(
                    target =
                        UniversalAiTarget(
                            providerId = ProviderId.of("deterministic"),
                            modelId = ModelId.of("echo-v1"),
                        ),
                    input =
                        listOf(
                            UniversalAiTextInput(
                                role = UniversalAiInputRole.User,
                                content = "configuration remains lazy",
                            ),
                        ),
                ),
            )

            assertEquals(0, credentialCalls)
            assertEquals(0, engine.requestHistory.size)
        } finally {
            connector.close()
            engine.close()
        }
    }

    private fun provider(
        providerId: String,
        onCredential: () -> Unit = {},
    ): UniversalAiProviderConfiguration =
        UniversalAiProviderConfiguration(
            providerId = ProviderId.of(providerId),
            baseUrl = "https://example.invalid/base",
            credentialSupplier = {
                onCredential()
                "credential"
            },
        )
}
