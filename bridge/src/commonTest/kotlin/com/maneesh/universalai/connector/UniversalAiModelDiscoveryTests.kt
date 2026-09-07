package com.maneesh.universalai.connector

import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiCapabilityName
import com.maneesh.universalai.connector.contract.UniversalAiCapabilitySupportState
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UniversalAiModelDiscoveryTests {
    @Test
    fun openAiListsBoundedModelsWithProviderHeadersAndStableDeduplication() = runTest {
        val engine =
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("https://api.example.invalid/v1/models", request.url.toString())
                assertEquals("Bearer credential", request.headers[HttpHeaders.Authorization])
                assertEquals("application/json", request.headers[HttpHeaders.Accept])
                respond(
                    """
                    {
                      "object":"list",
                      "data":[
                        {"id":"z-model","object":"model"},
                        {"id":"a-model","object":"model"},
                        {"id":"a-model","object":"model"}
                      ]
                    }
                    """.trimIndent(),
                )
            }
        configuredConnector("openai", engine).use { connector ->
            val result = assertIs<UniversalAiModelListResult.Supported>(
                connector.listModels(ProviderId.of("openai")),
            )

            assertEquals(listOf("a-model", "z-model"), result.modelIds())
            assertTrue(
                result.models.all { model ->
                    model.capabilities.supportState(UniversalAiCapabilityName.Streaming) ==
                        UniversalAiCapabilitySupportState.SUPPORTED
                },
            )
            assertTrue(
                result.models.all { model ->
                    model.capabilities.supportState(UniversalAiCapabilityName.StructuredOutput) ==
                        UniversalAiCapabilitySupportState.UNKNOWN
                },
            )
        }
        engine.close()
    }

    @Test
    fun anthropicOwnsCursorPaginationAndDeduplicatesFirstOccurrence() = runTest {
        var calls = 0
        var credentialCalls = 0
        val engine =
            MockEngine { request ->
                calls += 1
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("credential", request.headers["x-api-key"])
                assertEquals("2023-06-01", request.headers["anthropic-version"])
                assertEquals("1000", request.url.parameters["limit"])
                when (calls) {
                    1 -> {
                        assertNull(request.url.parameters["after_id"])
                        respond(
                            """
                            {
                              "data":[
                                {"id":"z-model","type":"model","display_name":"Zed"},
                                {"id":"cursor/model","type":"model","display_name":"First"}
                              ],
                              "first_id":"z-model",
                              "has_more":true,
                              "last_id":"cursor/model"
                            }
                            """.trimIndent(),
                        )
                    }
                    2 -> {
                        assertEquals("cursor/model", request.url.parameters["after_id"])
                        assertTrue(request.url.toString().contains("after_id=cursor%2Fmodel"))
                        respond(
                            """
                            {
                              "data":[
                                {"id":"cursor/model","type":"model","display_name":"Duplicate"},
                                {"id":"a-model","type":"model","display_name":"Alpha"}
                              ],
                              "first_id":"cursor/model",
                              "has_more":false,
                              "last_id":"a-model"
                            }
                            """.trimIndent(),
                        )
                    }
                    else -> error("Unexpected Anthropic model page.")
                }
            }
        configuredConnector(
            providerId = "anthropic",
            engine = engine,
            credentialSupplier = {
                credentialCalls += 1
                "credential"
            },
        ).use { connector ->
            val result = assertIs<UniversalAiModelListResult.Supported>(
                connector.listModels(ProviderId.of("anthropic")),
            )

            assertEquals(listOf("a-model", "cursor/model", "z-model"), result.modelIds())
            assertEquals("First", result.models[1].displayName)
            assertEquals(2, calls)
            assertEquals(2, credentialCalls)
        }
        engine.close()
    }

    @Test
    fun openRouterTranslatesNamesLimitsAndPerModelStructuredSupport() = runTest {
        val engine =
            MockEngine { request ->
                assertEquals("https://openrouter.example.invalid/api/v1/models", request.url.toString())
                assertEquals("Bearer credential", request.headers[HttpHeaders.Authorization])
                respond(
                    """
                    {
                      "data":[
                        {
                          "id":"z/model",
                          "name":"Zed",
                          "context_length":8192,
                          "supported_parameters":["temperature"],
                          "top_provider":{"max_completion_tokens":2048}
                        },
                        {
                          "id":"a/model",
                          "name":"Alpha",
                          "context_length":16384,
                          "supported_parameters":["structured_outputs","response_format"],
                          "top_provider":{"max_completion_tokens":4096}
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            }
        configuredConnector(
            providerId = "openrouter",
            engine = engine,
            baseUrl = "https://openrouter.example.invalid/api/v1",
        ).use { connector ->
            val result = assertIs<UniversalAiModelListResult.Supported>(
                connector.listModels(ProviderId.of("openrouter")),
            )

            assertEquals(listOf("a/model", "z/model"), result.modelIds())
            assertEquals("Alpha", result.models[0].displayName)
            assertEquals(16_384, result.models[0].limits?.contextWindowTokens)
            assertEquals(4_096, result.models[0].limits?.maxOutputTokens)
            assertEquals(
                UniversalAiCapabilitySupportState.SUPPORTED,
                result.models[0].capabilities.supportState(
                    UniversalAiCapabilityName.StructuredOutput,
                ),
            )
            assertEquals(
                UniversalAiCapabilitySupportState.UNSUPPORTED,
                result.models[1].capabilities.supportState(
                    UniversalAiCapabilityName.StructuredOutput,
                ),
            )
        }
        engine.close()
    }

    @Test
    fun openRouterKeepsModelsWhoseOptionalOutputLimitExceedsTheCanonicalCeiling() = runTest {
        val engine =
            MockEngine {
                respond(
                    """
                    {
                      "data":[
                        {
                          "id":"provider/large-output-model",
                          "name":"Large output model",
                          "context_length":2097152,
                          "supported_parameters":["temperature"],
                          "top_provider":{"max_completion_tokens":1048577}
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            }
        configuredConnector(
            providerId = "openrouter",
            engine = engine,
            baseUrl = "https://openrouter.example.invalid/api/v1",
        ).use { connector ->
            val result =
                assertIs<UniversalAiModelListResult.Supported>(
                    connector.listModels(ProviderId.of("openrouter")),
                )

            assertEquals(listOf("provider/large-output-model"), result.modelIds())
            assertEquals(2_097_152, result.models.single().limits?.contextWindowTokens)
            assertNull(result.models.single().limits?.maxOutputTokens)
        }
        engine.close()
    }

    @Test
    fun openAiCompatibleGatewaySupportsConservativeListShapeOrExplicitUnsupported() = runTest {
        listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed, HttpStatusCode.NotImplemented)
            .forEach { status ->
                val engine = MockEngine { respond("", status = status) }
                configuredConnector("openai-compatible", engine).use { connector ->
                    val result = connector.listModels(ProviderId.of("openai-compatible"))
                    assertIs<UniversalAiModelListResult.Unsupported>(result)
                }
                engine.close()
            }

        val engine =
            MockEngine { request ->
                assertEquals("https://api.example.invalid/v1/models", request.url.toString())
                respond(
                    """
                    {
                      "object":"list",
                      "data":[
                        {"id":"gateway-b","object":"model"},
                        {"id":"gateway-a","object":"model"}
                      ]
                    }
                    """.trimIndent(),
                )
            }
        configuredConnector("openai-compatible", engine).use { connector ->
            val result = assertIs<UniversalAiModelListResult.Supported>(
                connector.listModels(ProviderId.of("openai-compatible")),
            )
            assertEquals(listOf("gateway-a", "gateway-b"), result.modelIds())
        }
        engine.close()
    }

    @Test
    fun discoveryPreservesTypedFailuresMalformedResponsesCancellationAndLifecycle() = runTest {
        val authenticationEngine =
            MockEngine { respond("{}", status = HttpStatusCode.Unauthorized) }
        configuredConnector("openai-compatible", authenticationEngine).use { connector ->
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.listModels(ProviderId.of("openai-compatible"))
                }
            assertEquals(UniversalAiErrorCategory.Authentication, failure.error.category)
            assertEquals("provider_authentication_failed", failure.error.code.rawValue)
        }
        authenticationEngine.close()

        val malformedEngine = MockEngine { respond("{\"object\":\"list\",\"data\":[{\"id\":\"   \",\"object\":\"model\"}]}") }
        configuredConnector("openai", malformedEngine).use { connector ->
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.listModels(ProviderId.of("openai"))
                }
            assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
            assertEquals("malformed_provider_response", failure.error.code.rawValue)
        }
        malformedEngine.close()

        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancellationEngine =
            MockEngine {
                started.complete(Unit)
                awaitCancellation()
            }
        val connector = configuredConnector("openai", cancellationEngine)
        val pending = backgroundScope.async {
            connector.listModels(ProviderId.of("openai"))
        }
        started.await()
        connector.close()
        assertFailsWith<CancellationException> { pending.await() }
        cancellationEngine.close()

        val deterministic = UniversalAiConnector()
        assertIs<UniversalAiModelListResult.Unsupported>(
            deterministic.listModels(ProviderId.of("deterministic")),
        )
        val unregistered =
            assertFailsWith<UniversalAiException> {
                deterministic.listModels(ProviderId.of("future-provider"))
            }
        assertEquals(UniversalAiErrorCode.InvalidRequest, unregistered.error.code)
        deterministic.close()
    }

    @Test
    fun everyNonPaginatedAdapterRejectsAnExcessModelList() = runTest {
        listOf("openai", "openai-compatible", "openrouter").forEach { providerId ->
            val engine =
                MockEngine {
                    respond(excessModelList(providerId))
                }
            configuredConnector(providerId, engine).use { connector ->
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.listModels(ProviderId.of(providerId))
                    }
                assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
                assertEquals("malformed_provider_response", failure.error.code.rawValue)
            }
            engine.close()
        }
    }

    @Test
    fun anthropicRejectsCursorLoopsBeforeAnotherNetworkRequest() = runTest {
        var calls = 0
        val engine =
            MockEngine {
                calls += 1
                respond(
                    """
                    {
                      "data":[{"id":"loop","type":"model","display_name":"Loop"}],
                      "first_id":"loop",
                      "has_more":true,
                      "last_id":"loop"
                    }
                    """.trimIndent(),
                )
            }
        configuredConnector("anthropic", engine).use { connector ->
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.listModels(ProviderId.of("anthropic"))
                }
            assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
            assertEquals("malformed_provider_response", failure.error.code.rawValue)
            assertEquals(2, calls)
        }
        engine.close()
    }

    private fun configuredConnector(
        providerId: String,
        engine: MockEngine,
        baseUrl: String = "https://api.example.invalid/v1",
        credentialSupplier: () -> String = { "credential" },
    ): UniversalAiConnector =
        UniversalAiConnector(
            configuration =
                UniversalAiConnectorConfiguration(
                    providers =
                        listOf(
                            UniversalAiProviderConfiguration(
                                providerId = ProviderId.of(providerId),
                                baseUrl = baseUrl,
                                credentialSupplier = credentialSupplier,
                            ),
                        ),
                ),
            httpEngine = engine,
        )

    private fun UniversalAiModelListResult.Supported.modelIds(): List<String> =
        models.map { descriptor -> descriptor.target.modelId.rawValue }

    private fun excessModelList(providerId: String): String {
        val entries =
            (0..2_048).joinToString(",") { index ->
                when (providerId) {
                    "openrouter" ->
                        """{"id":"model-$index","name":"Model $index","supported_parameters":[]}"""
                    else -> """{"id":"model-$index","object":"model"}"""
                }
            }
        return when (providerId) {
            "openrouter" -> """{"data":[$entries]}"""
            else -> """{"object":"list","data":[$entries]}"""
        }
    }
}
