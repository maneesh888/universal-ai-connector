package com.maneesh.universalai.connector.internal.provider.openaicompatible

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiConnectorConfiguration
import com.maneesh.universalai.connector.UniversalAiProviderConfiguration
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.StructuredOutputSchema
import com.maneesh.universalai.connector.contract.StructuredOutputValue
import com.maneesh.universalai.connector.contract.UniversalAiCompletionReason
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
import com.maneesh.universalai.connector.internal.provider.OPENAI_COMPATIBLE_PROVIDER_ID
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-A fixtures for the standard client-facing intersection pinned in the Gateway plan.
 *
 * These fixtures intentionally exercise the existing generic adapter. They are not Gateway DTOs
 * and do not model the Gateway's administration or OpenKeyboard-specific operation extensions.
 */
class OpenAiCompatibleGatewayP7ATests {
    @Test
    fun gatewayFixtureUsesStandardBearerChatCompletionsContract() = runTest {
        val credential = "synthetic-p7-gateway-credential"
        val engine =
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(P7_GATEWAY_CHAT_COMPLETIONS_URL, request.url.toString())
                assertEquals("Bearer $credential", request.headers[HttpHeaders.Authorization])
                assertEquals("application/json", request.headers[HttpHeaders.Accept])
                assertEquals("application/json", request.body.contentType?.toString())

                val encoded = request.body.p7aBodyBytes().decodeToString()
                assertFalse(encoded.contains(credential))
                val document = P7_GATEWAY_JSON.parseToJsonElement(encoded) as JsonObject
                assertEquals(P7_GATEWAY_MODEL, document.string("model"))
                assertFalse("stream" in document)
                assertEquals(48, document.int("max_tokens"))
                assertEquals(0.2, document.double("temperature"))
                assertEquals(0.9, document.double("top_p"))
                assertEquals(
                    listOf("END"),
                    (document["stop"] as JsonArray).map { element ->
                        (element as JsonPrimitive).content
                    },
                )
                assertEquals(
                    listOf("system", "user", "assistant"),
                    (document["messages"] as JsonArray).map { element ->
                        (element as JsonObject).string("role")
                    },
                )

                respond(
                    content = ByteReadChannel(P7GatewayFixtures.textResponse),
                    status = HttpStatusCode.OK,
                    headers = Headers.build { append(HttpHeaders.ContentType, "application/json") },
                )
            }
        val connector = gatewayConnector(engine) { credential }

        try {
            val response = connector.respond(gatewayRequest())

            assertEquals("chatcmpl_gateway_p7", response.id.rawValue)
            assertNull(response.requestId)
            assertEquals(OPENAI_COMPATIBLE_PROVIDER_ID, response.target.providerId)
            assertEquals(P7_GATEWAY_MODEL, response.target.modelId.rawValue)
            assertEquals(UniversalAiCompletionReason.Stop, response.completionReason)
            assertEquals("Gateway ready", response.outputs.single().text)
            with(assertNotNull(response.usage)) {
                assertEquals(9L, inputTokens)
                assertEquals(3L, outputTokens)
                assertEquals(12L, totalTokens)
            }
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun gatewayStructuredFixtureUsesStrictJsonSchemaAndRevalidatesResponse() = runTest {
        val schema = gatewaySchema()
        val structuredJson = """{"answer":"ready","score":1}"""
        val engine =
            MockEngine { request ->
                val document =
                    P7_GATEWAY_JSON.parseToJsonElement(
                        request.body.p7aBodyBytes().decodeToString(),
                    ) as JsonObject
                val responseFormat = document["response_format"] as JsonObject
                assertEquals("json_schema", responseFormat.string("type"))
                val jsonSchema = responseFormat["json_schema"] as JsonObject
                assertEquals("universal_ai_response", jsonSchema.string("name"))
                assertTrue((jsonSchema["strict"] as JsonPrimitive).boolean)
                assertEquals(
                    P7_GATEWAY_JSON.parseToJsonElement(schema.toJson()),
                    jsonSchema["schema"],
                )
                respond(
                    content = P7GatewayFixtures.completionResponse(structuredJson),
                    status = HttpStatusCode.OK,
                    headers = Headers.build { append(HttpHeaders.ContentType, "application/json") },
                )
            }
        val connector = gatewayConnector(engine) { "synthetic-gateway-credential" }

        try {
            val output =
                connector
                    .respond(
                        gatewayRequest(
                            responseFormat = UniversalAiResponseFormat.jsonSchema(schema),
                        ),
                    ).outputs
                    .single()

            assertEquals(UniversalAiOutputKind.StructuredJson, output.kind)
            assertNull(output.text)
            assertEquals(StructuredOutputValue.parse(structuredJson), output.structuredJson)
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun gatewayErrorFixturesUseSafeStatusMappingAndBoundedRetryMetadata() = runTest {
        data class Case(
            val status: HttpStatusCode,
            val body: String,
            val category: UniversalAiErrorCategory,
            val code: String,
            val retryAfterSeconds: Int? = null,
        )

        val sensitive = "gateway-private-upstream-detail"
        val cases =
            listOf(
                Case(
                    status = HttpStatusCode.Unauthorized,
                    body = """{"error":"Invalid or disabled API key","detail":"$sensitive"}""",
                    category = UniversalAiErrorCategory.Authentication,
                    code = "provider_authentication_failed",
                ),
                Case(
                    status = HttpStatusCode.Forbidden,
                    body = """{"error":"Model is not allowed","detail":"$sensitive"}""",
                    category = UniversalAiErrorCategory.Authorization,
                    code = "provider_permission_denied",
                ),
                Case(
                    status = HttpStatusCode.TooManyRequests,
                    body =
                        """{"error":"Rate limit exceeded","retryAfter":5,"limit":1,"remaining":0,"detail":"$sensitive"}""",
                    category = UniversalAiErrorCategory.RateLimit,
                    code = "provider_rate_limited",
                    retryAfterSeconds = 5,
                ),
                Case(
                    status = HttpStatusCode.ServiceUnavailable,
                    body = """{"error":"Upstream model backend is not reachable","detail":"$sensitive"}""",
                    category = UniversalAiErrorCategory.Provider,
                    code = "provider_unavailable",
                ),
                Case(
                    status = HttpStatusCode.GatewayTimeout,
                    body = """{"error":"Ollama request timed out","detail":"$sensitive"}""",
                    category = UniversalAiErrorCategory.Provider,
                    code = "provider_request_timeout",
                ),
            )

        cases.forEach { case ->
            val engine =
                MockEngine {
                    respond(
                        content = case.body,
                        status = case.status,
                        headers =
                            Headers.build {
                                append(HttpHeaders.ContentType, "application/json")
                                case.retryAfterSeconds?.let { seconds ->
                                    append(HttpHeaders.RetryAfter, seconds.toString())
                                }
                            },
                    )
                }
            val connector = gatewayConnector(engine) { "synthetic-gateway-credential" }
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(gatewayRequest())
                    }
                assertEquals(case.category, failure.error.category)
                assertEquals(case.code, failure.error.code.rawValue)
                assertFalse(failure.stackTraceToString().contains(sensitive))
                if (case.retryAfterSeconds != null) {
                    assertEquals(
                        case.retryAfterSeconds * 1_000L,
                        failure.error.metadata?.number("retryAfterMillis")?.toLongOrNull(),
                    )
                }
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun gatewayStreamingFixtureTranslatesSseThroughDone() = runTest {
        val engine =
            MockEngine { request ->
                val document =
                    P7_GATEWAY_JSON.parseToJsonElement(
                        request.body.p7aBodyBytes().decodeToString(),
                    ) as JsonObject
                assertTrue((document["stream"] as JsonPrimitive).boolean)
                assertTrue(
                    ((document["stream_options"] as JsonObject)["include_usage"] as JsonPrimitive)
                        .boolean,
                )
                assertEquals("text/event-stream", request.headers[HttpHeaders.Accept])
                respond(
                    content = ByteReadChannel(P7GatewayFixtures.streamResponse),
                    status = HttpStatusCode.OK,
                    headers =
                        Headers.build {
                            append(HttpHeaders.ContentType, "text/event-stream; charset=utf-8")
                        },
                )
            }
        val connector = gatewayConnector(engine) { "synthetic-gateway-credential" }

        try {
            val events = connector.stream(gatewayRequest()).toList()

            assertEquals(
                listOf(
                    UniversalAiStreamEventType.ResponseStarted,
                    UniversalAiStreamEventType.OutputStarted,
                    UniversalAiStreamEventType.OutputDelta,
                    UniversalAiStreamEventType.OutputDelta,
                    UniversalAiStreamEventType.OutputCompleted,
                    UniversalAiStreamEventType.UsageUpdated,
                    UniversalAiStreamEventType.ResponseCompleted,
                ),
                events.map(UniversalAiStreamEvent::type),
            )
            assertEquals(listOf("Gateway ", "ready"), events.mapNotNull(UniversalAiStreamEvent::delta))
            assertEquals("Gateway ready", events.last().response?.outputs?.single()?.text)
            assertEquals(12L, events.last().response?.usage?.totalTokens)
            assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun cancellingPendingGatewayFixtureRemainsCallerCancellation() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val engine =
            MockEngine {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        val connector = gatewayConnector(engine) { "synthetic-gateway-credential" }

        try {
            val operation =
                async(start = CoroutineStart.UNDISPATCHED) {
                    connector.respond(gatewayRequest())
                }
            started.await()
            operation.cancel()
            assertFailsWith<CancellationException> { operation.await() }
            withTimeout(1_000) { cancelled.await() }
        } finally {
            connector.close()
            engine.close()
        }
    }
}

private fun gatewayConnector(
    engine: MockEngine,
    credentialSupplier: () -> String,
): UniversalAiConnector =
    UniversalAiConnector(
        configuration =
            UniversalAiConnectorConfiguration(
                providers =
                    listOf(
                        UniversalAiProviderConfiguration(
                            providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                            baseUrl = P7_GATEWAY_BASE_URL,
                            credentialSupplier = credentialSupplier,
                        ),
                    ),
            ),
        httpEngine = engine,
    )

private fun gatewayRequest(
    responseFormat: UniversalAiResponseFormat = UniversalAiResponseFormat.PlainText,
): UniversalAiRequest =
    UniversalAiRequest(
        target = UniversalAiTarget(OPENAI_COMPATIBLE_PROVIDER_ID, ModelId.of(P7_GATEWAY_MODEL)),
        input =
            listOf(
                UniversalAiTextInput(UniversalAiInputRole.System, "Answer briefly."),
                UniversalAiTextInput(UniversalAiInputRole.User, "Is the Gateway ready?"),
                UniversalAiTextInput(UniversalAiInputRole.Assistant, "Checking."),
            ),
        responseFormat = responseFormat,
        generation =
            UniversalAiGenerationParameters(
                maxOutputTokens = 48,
                temperature = 0.2,
                topP = 0.9,
                stopSequences = listOf("END"),
            ),
    )

private fun gatewaySchema(): StructuredOutputSchema =
    StructuredOutputSchema.parse(
        """
        {
          "type":"object",
          "properties":{
            "answer":{"type":"string","enum":["ready"]},
            "score":{"type":"integer","minimum":1,"maximum":1}
          },
          "required":["answer","score"],
          "additionalProperties":false
        }
        """.trimIndent(),
    )

private object P7GatewayFixtures {
    val textResponse: String = completionResponse("Gateway ready")

    fun completionResponse(content: String): String =
        """
        {
          "id":"chatcmpl_gateway_p7",
          "object":"chat.completion",
          "created":1786464000,
          "model":"$P7_GATEWAY_MODEL",
          "choices":[{
            "index":0,
            "message":{"role":"assistant","content":${JsonPrimitive(content)}},
            "finish_reason":"stop"
          }],
          "usage":{"prompt_tokens":9,"completion_tokens":3,"total_tokens":12},
          "gateway_future_field":true
        }
        """.trimIndent()

    val streamResponse: String =
        listOf(
            """{"id":"chatcmpl_gateway_stream","object":"chat.completion.chunk","created":1786464000,"model":"$P7_GATEWAY_MODEL","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
            """{"id":"chatcmpl_gateway_stream","object":"chat.completion.chunk","created":1786464000,"model":"$P7_GATEWAY_MODEL","choices":[{"index":0,"delta":{"content":"Gateway "}}]}""",
            """{"id":"chatcmpl_gateway_stream","object":"chat.completion.chunk","created":1786464000,"model":"$P7_GATEWAY_MODEL","choices":[{"index":0,"delta":{"content":"ready"}}]}""",
            """{"id":"chatcmpl_gateway_stream","object":"chat.completion.chunk","created":1786464000,"model":"$P7_GATEWAY_MODEL","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":3,"total_tokens":12}}""",
            "[DONE]",
        ).joinToString(separator = "") { payload -> "data: $payload\n\n" }
}

private fun OutgoingContent.p7aBodyBytes(): ByteArray =
    (this as OutgoingContent.ByteArrayContent).bytes()

private fun JsonObject.string(name: String): String =
    (this[name] as JsonPrimitive).content

private fun JsonObject.int(name: String): Int =
    (this[name] as JsonPrimitive).int

private fun JsonObject.double(name: String): Double =
    (this[name] as JsonPrimitive).double

private const val P7_GATEWAY_BASE_URL: String = "https://gateway.example.invalid/v1"
private const val P7_GATEWAY_CHAT_COMPLETIONS_URL: String =
    "$P7_GATEWAY_BASE_URL/chat/completions"
private const val P7_GATEWAY_MODEL: String = "gateway-test-model"

private val P7_GATEWAY_JSON = Json
