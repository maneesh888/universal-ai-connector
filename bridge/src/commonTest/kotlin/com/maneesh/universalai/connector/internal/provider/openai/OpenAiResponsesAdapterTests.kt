package com.maneesh.universalai.connector.internal.provider.openai

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiConnectorConfiguration
import com.maneesh.universalai.connector.UniversalAiProviderConfiguration
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiCompletionReason
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiGenerationParameters
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiOutputKind
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormat
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.contract.extension.ExtensionNamespace
import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.contract.extension.Extensions
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
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

class OpenAiResponsesAdapterTests {
    @Test
    fun translatesOrderedRequestHeadersResponseOutputsUsageAndMetadata() = runTest {
        val credential = "synthetic-credential-material"
        var credentialCalls = 0
        val engine =
            MockEngine {
                respond(
                    content =
                        ByteReadChannel(
                            """
                            {
                              "id": "resp_test",
                              "object": "response",
                              "status": "completed",
                              "model": "resolved-model-snapshot",
                              "output": [
                                {
                                  "id": "reasoning_0",
                                  "type": "reasoning",
                                  "summary": []
                                },
                                {
                                  "id": "message_0",
                                  "type": "message",
                                  "status": "completed",
                                  "role": "assistant",
                                  "content": [
                                    {"type": "output_text", "text": "first"},
                                    {"type": "output_text", "text": " response"}
                                  ]
                                },
                                {
                                  "id": "message_1",
                                  "type": "message",
                                  "role": "assistant",
                                  "content": [
                                    {"type": "output_text", "text": "second response"}
                                  ]
                                }
                              ],
                              "usage": {
                                "input_tokens": 12,
                                "input_tokens_details": {
                                  "cached_tokens": 2,
                                  "cache_write_tokens": 1
                                },
                                "output_tokens": 8,
                                "output_tokens_details": {
                                  "reasoning_tokens": 3
                                },
                                "total_tokens": 20
                              },
                              "future_optional_field": true
                            }
                            """.trimIndent(),
                        ),
                    status = HttpStatusCode.OK,
                    headers =
                        Headers.build {
                            append("X-Request-Id", "req_test")
                            append("Retry-After", "4")
                        },
                )
            }
        val connector =
            connector(
                engine = engine,
                credentialSupplier = {
                    credentialCalls += 1
                    credential
                },
            )

        try {
            val response =
                connector.respond(
                    request(
                        generation =
                            UniversalAiGenerationParameters(
                                maxOutputTokens = 32,
                                temperature = 0.25,
                                topP = 0.8,
                            ),
                    ),
                )

            assertEquals(1, credentialCalls)
            assertEquals("resp_test", response.id.rawValue)
            assertEquals("req_test", response.requestId?.rawValue)
            assertEquals("openai", response.target.providerId.rawValue)
            assertEquals("resolved-model-snapshot", response.target.modelId.rawValue)
            assertEquals(listOf(0, 1), response.outputs.map { output -> output.index })
            assertEquals(
                listOf("first response", "second response"),
                response.outputs.map { output -> output.text },
            )
            assertTrue(response.outputs.all { output -> output.kind == UniversalAiOutputKind.Text })
            assertEquals(UniversalAiCompletionReason.Stop, response.completionReason)
            with(assertNotNull(response.usage)) {
                assertEquals(12, inputTokens)
                assertEquals(8, outputTokens)
                assertEquals(20, totalTokens)
                assertEquals(mapOf("cached_tokens" to 2L, "cache_write_tokens" to 1L), inputDetails)
                assertEquals(mapOf("reasoning_tokens" to 3L), outputDetails)
            }
            val sentRequest = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sentRequest.method)
            assertEquals("https://api.example.invalid/v1/responses", sentRequest.url.toString())
            assertTrue(
                sentRequest.headers["Authorization"] == "Bearer $credential",
                "Authorization header did not use the supplied credential.",
            )
            assertEquals("application/json", sentRequest.body.contentType?.toString())
            assertEquals("application/json", sentRequest.headers["Accept"])
            val requestBody = sentRequest.body.bodyBytes().decodeToString()
            assertFalse(requestBody.contains(credential))
            val document = JSON.parseToJsonElement(requestBody) as JsonObject
            assertEquals("requested-model", document.string("model"))
            assertFalse(document.boolean("store"))
            assertEquals(32, document.int("max_output_tokens"))
            assertEquals(0.25, document.double("temperature"))
            assertEquals(0.8, document.double("top_p"))
            val input = document["input"] as JsonArray
            assertEquals(
                    listOf("system", "developer", "user", "assistant"),
                input.map { element -> (element as JsonObject).string("role") },
            )
            assertEquals(
                    listOf("system rules", "rules", "question", "earlier answer"),
                input.map { element -> (element as JsonObject).string("content") },
            )
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun omitsAbsentOptionalGenerationFieldsAndResolvesCredentialOncePerRequest() = runTest {
        var credentialCalls = 0
        val engine =
            MockEngine { request ->
                val document =
                    JSON.parseToJsonElement(request.body.bodyBytes().decodeToString()) as JsonObject
                assertFalse("max_output_tokens" in document)
                assertFalse("temperature" in document)
                assertFalse("top_p" in document)
                respond(successResponse("response-${credentialCalls}"))
            }
        val connector =
            connector(
                engine = engine,
                credentialSupplier = {
                    credentialCalls += 1
                    "request-scoped-credential"
                },
            )

        try {
            connector.respond(request())
            connector.respond(request())

            assertEquals(2, credentialCalls)
            assertEquals(2, engine.requestHistory.size)
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun missingBlankAndThrowingCredentialsFailSafelyBeforeDispatch() = runTest {
        val sensitiveSupplierDetail = "supplier-sensitive-detail"
        val suppliers =
            listOf<() -> String>(
                { "" },
                { "   " },
                { "line\nbreak" },
                { "x".repeat(8_193) },
                { throw IllegalStateException(sensitiveSupplierDetail) },
            )

        suppliers.forEach { supplier ->
            var calls = 0
            val engine = MockEngine { error("Credential failure must prevent dispatch.") }
            val connector =
                connector(
                    engine = engine,
                    credentialSupplier = {
                        calls += 1
                        supplier()
                    },
                )
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(request())
                    }

                assertEquals(1, calls)
                assertEquals(0, engine.requestHistory.size)
                assertEquals(UniversalAiErrorCategory.Authentication, failure.error.category)
                assertEquals("missing_credential", failure.error.code.rawValue)
                assertEquals(OPENAI_CREDENTIAL_MESSAGE, failure.message)
                assertNull(failure.cause)
                assertFalse(failure.stackTraceToString().contains(sensitiveSupplierDetail))
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun credentialSupplierCancellationPropagatesBeforeDispatch() = runTest {
        val cancellation = CancellationException("host credential lookup cancelled")
        val engine = MockEngine { error("Credential cancellation must prevent dispatch.") }
        val connector =
            connector(
                engine = engine,
                credentialSupplier = { throw cancellation },
            )

        try {
            assertFailsWith<CancellationException> {
                connector.respond(request())
            }
            assertEquals(0, engine.requestHistory.size)
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun providerFailureMapsStatusRequestIdAndRetryMetadataWithoutResponseBodyLeakage() = runTest {
        val credential = "adversarial-credential-fragment"
        val providerFragment = "provider-body-sensitive-fragment"
        val engine =
            MockEngine {
                respond(
                    content =
                        """{"error":{"message":"$credential $providerFragment"}}""",
                    status = HttpStatusCode.TooManyRequests,
                    headers =
                        Headers.build {
                            append("X-Request-Id", "req_rate_limit")
                            append("Retry-After", "7")
                        },
                )
            }
        val connector = connector(engine) { credential }

        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.respond(request())
                }

            assertEquals(UniversalAiErrorCategory.RateLimit, failure.error.category)
            assertEquals("provider_rate_limited", failure.error.code.rawValue)
            assertEquals(OPENAI_RATE_LIMIT_MESSAGE, failure.message)
            with(assertNotNull(failure.error.metadata)) {
                assertEquals(429L, number("statusCode")?.toLongOrNull())
                assertEquals("req_rate_limit", string("requestId"))
                assertEquals(7_000L, number("retryAfterMillis")?.toLongOrNull())
            }
            val diagnostic = failure.stackTraceToString()
            assertFalse(diagnostic.contains(credential))
            assertFalse(diagnostic.contains(providerFragment))
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun malformedSuccessfulPayloadsFailWithOneFixedSafeError() = runTest {
        val sensitive = "malformed-sensitive-fragment"
        val malformedPayloads =
            listOf(
                """{"not_json":""",
                """
                {
                  "id":"resp_0",
                  "object":"response",
                  "status":"incomplete",
                  "model":"model",
                  "output":[],
                  "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2},
                  "incomplete_details":{"reason":"$sensitive"}
                }
                """.trimIndent(),
                """
                {
                  "id":"resp_0",
                  "object":"response",
                  "status":"completed",
                  "model":"model",
                  "output":[],
                  "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}
                }
                """.trimIndent(),
                """
                {
                  "id":"resp_0",
                  "object":"response",
                  "status":"completed",
                  "model":"model",
                  "output":[{"id":"call_0","type":"function_call","arguments":"$sensitive"}],
                  "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}
                }
                """.trimIndent(),
                """
                {
                  "id":"resp_0",
                  "object":"response",
                  "status":"completed",
                  "model":"model",
                  "output":[{
                    "id":"message_0",
                    "type":"message",
                    "role":"assistant",
                    "content":[{"type":"refusal","text":"$sensitive"}]
                  }],
                  "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}
                }
                """.trimIndent(),
                """
                {
                  "id":"resp_0",
                  "object":"response",
                  "status":"completed",
                  "model":"model",
                  "output":[{
                    "id":"message_0",
                    "type":"message",
                    "role":"assistant",
                    "content":[{"type":"output_text","text":"ok"}]
                  }],
                  "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":99}
                }
                """.trimIndent(),
            )

        malformedPayloads.forEach { payload ->
            val engine = MockEngine { respond(payload) }
            val connector = connector(engine) { "malformed-test-credential" }
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(request())
                    }
                assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
                assertEquals("malformed_provider_response", failure.error.code.rawValue)
                assertEquals(OPENAI_MALFORMED_RESPONSE_MESSAGE, failure.message)
                assertFalse(failure.stackTraceToString().contains(sensitive))
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun invalidUtf8SuccessfulPayloadFailsWithFixedSafeError() = runTest {
        val sensitive = "invalid-utf8-sensitive-fragment"
        val bytes =
            """
            {
              "id":"resp_0",
              "object":"response",
              "status":"completed",
              "model":"model",
              "output":[{
                "id":"message_0",
                "type":"message",
                "role":"assistant",
                "content":[{"type":"output_text","text":"$sensitive
            """.trimIndent().encodeToByteArray() +
                byteArrayOf(0xc3.toByte()) +
                """
                "}]
              }],
              "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}
            }
            """.trimIndent().encodeToByteArray()
        val engine =
            MockEngine {
                respond(ByteReadChannel(bytes))
            }
        val connector = connector(engine) { "invalid-utf8-test-credential" }

        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.respond(request())
                }

            assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
            assertEquals("malformed_provider_response", failure.error.code.rawValue)
            assertEquals(OPENAI_MALFORMED_RESPONSE_MESSAGE, failure.message)
            assertFalse(failure.stackTraceToString().contains(sensitive))
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun oversizedSuccessfulPayloadFailsWithFixedSafeError() = runTest {
        val engine =
            MockEngine {
                respond("x".repeat(8 * 1_024 * 1_024 + 1))
            }
        val connector = connector(engine) { "oversized-response-credential" }

        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.respond(request())
                }

            assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
            assertEquals("malformed_provider_response", failure.error.code.rawValue)
            assertEquals(OPENAI_MALFORMED_RESPONSE_MESSAGE, failure.message)
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun unexpectedTransportFailureCannotRetainCredentialMaterial() = runTest {
        val credential = "transport-failure-credential-fragment"
        val engine =
            MockEngine {
                throw IllegalStateException("unexpected $credential")
            }
        val connector = connector(engine) { credential }

        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.respond(request())
                }

            assertEquals(UniversalAiErrorCategory.Internal, failure.error.category)
            assertEquals(UniversalAiErrorCode.ConnectorFailure, failure.error.code)
            assertNull(failure.cause)
            assertFalse(failure.stackTraceToString().contains(credential))
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun unsupportedRequestFeaturesFailBeforeCredentialOrTransportUse() = runTest {
        val requests =
            listOf(
                request(responseFormat = structuredFormat()),
                request(
                    generation =
                        UniversalAiGenerationParameters(
                            stopSequences = listOf("stop"),
                        ),
                ),
                request(
                    extensions =
                        Extensions.of(
                            ExtensionNamespace.of("com.example.test") to
                                ExtensionValue.objectValue("enabled" to ExtensionValue.boolean(true)),
                        ),
                ),
                request(
                    input =
                        listOf(
                            UniversalAiTextInput(
                                role = UniversalAiInputRole.of("future-role"),
                                content = "unsupported role",
                            ),
                        ),
                ),
            )

        requests.forEach { unsupportedRequest ->
            var credentialCalls = 0
            val engine = MockEngine { error("Unsupported requests must not dispatch.") }
            val connector =
                connector(engine) {
                    credentialCalls += 1
                    "unused"
                }
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(unsupportedRequest)
                    }
                assertEquals(UniversalAiErrorCategory.Validation, failure.error.category)
                assertEquals(UniversalAiErrorCode.InvalidRequest, failure.error.code)
                assertEquals(0, credentialCalls)
                assertEquals(0, engine.requestHistory.size)
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun callerCancellationCancelsPendingProviderResponseWithoutCanonicalFailure() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        var credentialCalls = 0
        val engine =
            MockEngine {
                requestStarted.complete(Unit)
                awaitCancellation()
            }
        val connector =
            connector(engine) {
                credentialCalls += 1
                "cancellation-test-credential"
            }

        try {
            val operation =
                async {
                    connector.respond(request())
                }
            requestStarted.await()
            operation.cancel()

            assertFailsWith<CancellationException> {
                operation.await()
            }
            assertEquals(1, credentialCalls)
        } finally {
            connector.close()
            engine.close()
        }
    }

    private fun connector(
        engine: MockEngine,
        credentialSupplier: () -> String,
    ): UniversalAiConnector =
        UniversalAiConnector(
            configuration =
                UniversalAiConnectorConfiguration(
                    listOf(
                        UniversalAiProviderConfiguration(
                            providerId = ProviderId.of("openai"),
                            baseUrl = "https://api.example.invalid/v1",
                            credentialSupplier = credentialSupplier,
                        ),
                    ),
                ),
            httpEngine = engine,
        )

    private fun request(
        generation: UniversalAiGenerationParameters = UniversalAiGenerationParameters.Default,
        responseFormat: UniversalAiResponseFormat = UniversalAiResponseFormat.PlainText,
        extensions: Extensions = Extensions.Empty,
        input: List<UniversalAiTextInput> =
            listOf(
                UniversalAiTextInput(
                    role = UniversalAiInputRole.System,
                    content = "system rules",
                ),
                UniversalAiTextInput(
                    role = UniversalAiInputRole.Developer,
                    content = "rules",
                ),
                UniversalAiTextInput(
                    role = UniversalAiInputRole.User,
                    content = "question",
                ),
                UniversalAiTextInput(
                    role = UniversalAiInputRole.Assistant,
                    content = "earlier answer",
                ),
            ),
    ): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = ProviderId.of("openai"),
                    modelId = ModelId.of("requested-model"),
                ),
            input = input,
            responseFormat = responseFormat,
            generation = generation,
            extensions = extensions,
        )

    private fun structuredFormat(): UniversalAiResponseFormat =
        UniversalAiResponseFormat.jsonSchema(
            com.maneesh.universalai.connector.contract.StructuredOutputSchema.parse(
                """{"type":"object","additionalProperties":false}""",
            ),
        )

    private fun successResponse(responseId: String): String =
        """
        {
          "id":"$responseId",
          "object":"response",
          "status":"completed",
          "model":"resolved-model",
          "output":[{
            "id":"message_0",
            "type":"message",
            "role":"assistant",
            "content":[{"type":"output_text","text":"ok"}]
          }],
          "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}
        }
        """.trimIndent()
}

private fun OutgoingContent.bodyBytes(): ByteArray =
    (this as OutgoingContent.ByteArrayContent).bytes()

private fun JsonObject.string(name: String): String =
    (this[name] as JsonPrimitive).content

private fun JsonObject.int(name: String): Int =
    (this[name] as JsonPrimitive).int

private fun JsonObject.double(name: String): Double =
    (this[name] as JsonPrimitive).double

private fun JsonObject.boolean(name: String): Boolean =
    (this[name] as JsonPrimitive).boolean

private val JSON = Json
