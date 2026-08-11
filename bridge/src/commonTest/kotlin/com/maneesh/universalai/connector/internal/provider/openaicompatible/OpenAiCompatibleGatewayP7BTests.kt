package com.maneesh.universalai.connector.internal.provider.openaicompatible

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiConnectorConfiguration
import com.maneesh.universalai.connector.UniversalAiProviderConfiguration
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.internal.provider.OPENAI_COMPATIBLE_PROVIDER_ID
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** P7-B corrections demonstrated by the frozen Gateway contract and P7-A fixtures. */
class OpenAiCompatibleGatewayP7BTests {
    @Test
    fun gatewaySuccessWithoutUsageRemainsAValidCanonicalResponse() = runTest {
        val connector = connector(successResponse())

        try {
            val response = connector.respond(request())

            assertEquals("Gateway ready", response.outputs.single().text)
            assertNull(response.usage)
        } finally {
            connector.close()
        }
    }

    @Test
    fun presentGatewayUsageRemainsStrictlyValidated() = runTest {
        val invalidUsageValues =
            listOf(
                """{"prompt_tokens":9,"total_tokens":12}""",
                """{"prompt_tokens":9,"completion_tokens":-1,"total_tokens":8}""",
            )

        invalidUsageValues.forEach { usage ->
            val connector = connector(successResponse(usage))
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(request())
                    }
                assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
                assertEquals("malformed_provider_response", failure.error.code.rawValue)
            } finally {
                connector.close()
            }
        }
    }

    @Test
    fun optionalGatewayErrorExtensionsStayUntrustedAndUnretained() = runTest {
        val sensitive = "gateway-upstream-detail-that-must-not-escape"
        val engine =
            MockEngine {
                respond(
                    content =
                        """
                        {
                          "error":"Rate limit exceeded",
                          "detail":"$sensitive",
                          "retryAfter":3,
                          "limit":10,
                          "remaining":0,
                          "future":{"debug":"$sensitive"}
                        }
                        """.trimIndent(),
                    status = HttpStatusCode.TooManyRequests,
                    headers =
                        Headers.build {
                            append(HttpHeaders.ContentType, "application/json")
                            append(HttpHeaders.RetryAfter, "3")
                            append("X-Request-Id", "req_gateway_p7b")
                        },
                )
            }
        val connector = connector(engine)

        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.respond(request())
                }

            assertEquals(UniversalAiErrorCategory.RateLimit, failure.error.category)
            assertEquals("provider_rate_limited", failure.error.code.rawValue)
            assertEquals(
                setOf("statusCode", "requestId", "retryAfterMillis"),
                failure.error.metadata?.members?.keys,
            )
            assertEquals("req_gateway_p7b", failure.error.metadata?.string("requestId"))
            assertEquals(
                3_000L,
                failure.error.metadata?.number("retryAfterMillis")?.toLongOrNull(),
            )
            assertFalse(failure.error.toJson().contains(sensitive))
            assertFalse(failure.stackTraceToString().contains(sensitive))
        } finally {
            connector.close()
        }
    }

    private fun connector(responseBody: String): UniversalAiConnector {
        val engine =
            MockEngine {
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = Headers.build { append(HttpHeaders.ContentType, "application/json") },
                )
            }
        return connector(engine)
    }

    private fun connector(engine: MockEngine): UniversalAiConnector =
        UniversalAiConnector(
            UniversalAiConnectorConfiguration(
                providers =
                    listOf(
                        UniversalAiProviderConfiguration(
                            providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                            baseUrl = "https://gateway.example.invalid/v1",
                            credentialSupplier = { "synthetic-gateway-credential" },
                        ),
                    ),
            ),
            httpEngine = engine,
        )

    private fun request(): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                    modelId = ModelId.of("gateway-test-model"),
                ),
            input =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = "Is the Gateway ready?",
                    ),
                ),
        )

    private fun successResponse(usage: String? = null): String =
        """
        {
          "id":"chatcmpl_gateway_p7b",
          "object":"chat.completion",
          "model":"gateway-test-model",
          "choices":[{
            "index":0,
            "message":{"role":"assistant","content":"Gateway ready"},
            "finish_reason":"stop"
          }]${usage?.let { value -> ",\"usage\":$value" }.orEmpty()}
        }
        """.trimIndent()
}
