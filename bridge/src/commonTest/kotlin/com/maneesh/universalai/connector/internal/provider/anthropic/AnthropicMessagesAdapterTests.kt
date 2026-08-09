package com.maneesh.universalai.connector.internal.provider.anthropic

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
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicMessagesAdapterTests {
    @Test
    fun translatesAcceptedRequestHeadersResponseUsageAndMetadata(): Unit = runTest {
        val credential = "synthetic-anthropic-credential-material"
        var credentialCalls = 0
        val engine =
            MockEngine {
                respond(
                    content =
                        ByteReadChannel(
                            """
                            {
                              "id": "msg_test",
                              "type": "message",
                              "role": "assistant",
                              "content": [
                                {"type": "text", "text": "first"},
                                {"type": "text", "text": " response"}
                              ],
                              "model": "resolved-claude-model",
                              "stop_reason": "stop_sequence",
                              "stop_sequence": "DONE",
                              "usage": {
                                "input_tokens": 12,
                                "output_tokens": 8,
                                "cache_creation_input_tokens": 2,
                                "cache_read_input_tokens": 3
                              },
                              "future_optional_field": true
                            }
                            """.trimIndent(),
                        ),
                    status = HttpStatusCode.OK,
                    headers =
                        Headers.build {
                            append("request-id", "req_anthropic_test")
                            append("retry-after", "4")
                        },
                )
            }
        val connector =
            connector(engine) {
                credentialCalls += 1
                credential
            }

        try {
            val response =
                connector.respond(
                    request(
                        generation =
                            UniversalAiGenerationParameters(
                                maxOutputTokens = 64,
                                stopSequences = listOf("DONE"),
                            ),
                    ),
                )

            assertEquals(1, credentialCalls)
            assertEquals("msg_test", response.id.rawValue)
            assertEquals("req_anthropic_test", response.requestId?.rawValue)
            assertEquals("anthropic", response.target.providerId.rawValue)
            assertEquals("resolved-claude-model", response.target.modelId.rawValue)
            assertEquals(UniversalAiCompletionReason.Stop, response.completionReason)
            val output = response.outputs.single()
            assertEquals("msg_test", output.id.rawValue)
            assertEquals(UniversalAiOutputKind.Text, output.kind)
            assertEquals("first response", output.text)
            with(assertNotNull(response.usage)) {
                assertEquals(17, inputTokens)
                assertEquals(8, outputTokens)
                assertEquals(25, totalTokens)
                assertEquals(
                    mapOf(
                        "cache_write_tokens" to 2L,
                        "cached_tokens" to 3L,
                    ),
                    inputDetails,
                )
            }

            val sentRequest = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sentRequest.method)
            assertEquals(
                "https://api.example.invalid/v1/messages",
                sentRequest.url.toString(),
            )
            assertEquals(credential, sentRequest.headers["x-api-key"])
            assertEquals("2023-06-01", sentRequest.headers["anthropic-version"])
            assertEquals("application/json", sentRequest.headers["accept"])
            assertEquals("application/json", sentRequest.body.contentType?.toString())
            val requestBody = sentRequest.body.bodyBytes().decodeToString()
            assertFalse(requestBody.contains(credential))
            val document = JSON.parseToJsonElement(requestBody) as JsonObject
            assertEquals("requested-claude-model", document.string("model"))
            assertEquals(64, document.int("max_tokens"))
            assertEquals(listOf("DONE"), document.stringArray("stop_sequences"))
            val system = document["system"] as JsonArray
            assertEquals(
                listOf("first system rule", "second system rule"),
                system.map { element -> (element as JsonObject).string("text") },
            )
            assertTrue(system.all { element -> (element as JsonObject).string("type") == "text" })
            val messages = document["messages"] as JsonArray
            assertEquals(
                listOf("user", "assistant", "user"),
                messages.map { element -> (element as JsonObject).string("role") },
            )
            assertEquals(
                listOf("first question", "earlier answer", "follow-up question"),
                messages.map { element ->
                    val content = (element as JsonObject)["content"] as JsonArray
                    (content.single() as JsonObject).string("text")
                },
            )
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun omitsOptionalSystemAndStopFieldsAndResolvesCredentialOncePerRequest(): Unit = runTest {
        var credentialCalls = 0
        val engine =
            MockEngine { request ->
                val document =
                    JSON.parseToJsonElement(request.body.bodyBytes().decodeToString()) as JsonObject
                assertFalse("system" in document)
                assertFalse("stop_sequences" in document)
                respond(successResponse("msg_${credentialCalls}"))
            }
        val connector =
            connector(engine) {
                credentialCalls += 1
                "request-scoped-anthropic-credential"
            }

        try {
            val userOnlyInput =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = "question",
                    ),
                )
            connector.respond(request(input = userOnlyInput))
            connector.respond(request(input = userOnlyInput))

            assertEquals(2, credentialCalls)
            assertEquals(2, engine.requestHistory.size)
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun missingBlankMalformedAndThrowingCredentialsFailSafelyBeforeDispatch(): Unit = runTest {
        val sensitiveSupplierDetail = "anthropic-supplier-sensitive-detail"
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
                connector(engine) {
                    calls += 1
                    supplier()
                }
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(request())
                    }

                assertEquals(1, calls)
                assertEquals(0, engine.requestHistory.size)
                assertEquals(UniversalAiErrorCategory.Authentication, failure.error.category)
                assertEquals("missing_credential", failure.error.code.rawValue)
                assertEquals(ANTHROPIC_CREDENTIAL_MESSAGE, failure.message)
                assertNull(failure.cause)
                assertFalse(failure.stackTraceToString().contains(sensitiveSupplierDetail))
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun credentialSupplierCancellationPropagatesBeforeDispatch(): Unit = runTest {
        val cancellation = CancellationException("host credential lookup cancelled")
        val engine = MockEngine { error("Credential cancellation must prevent dispatch.") }
        val connector = connector(engine) { throw cancellation }

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
    fun unsupportedFeaturesAndRoleSequencesFailBeforeCredentialResolution(): Unit = runTest {
        val unsupportedRequests =
            listOf(
                request(
                    providerId = "openai",
                ),
                request(
                    responseFormat =
                        UniversalAiResponseFormat.jsonSchema(
                            com.maneesh.universalai.connector.contract.StructuredOutputSchema.parse(
                                """{"type":"string","minLength":1}""",
                            ),
                        ),
                ),
                request(
                    generation = UniversalAiGenerationParameters(),
                ),
                request(
                    generation =
                        UniversalAiGenerationParameters(
                            maxOutputTokens = 64,
                            temperature = 0.5,
                        ),
                ),
                request(
                    generation =
                        UniversalAiGenerationParameters(
                            maxOutputTokens = 64,
                            topP = 0.9,
                        ),
                ),
                request(
                    extensions =
                        Extensions.of(
                            ExtensionNamespace.of("com.example.test") to
                                ExtensionValue.objectValue(
                                    "enabled" to ExtensionValue.boolean(true),
                                ),
                        ),
                ),
                request(
                    input =
                        listOf(
                            text(UniversalAiInputRole.Developer, "unsupported"),
                            text(UniversalAiInputRole.User, "question"),
                        ),
                ),
                request(
                    input =
                        listOf(
                            text(UniversalAiInputRole.User, "first"),
                            text(UniversalAiInputRole.User, "second"),
                        ),
                ),
                request(
                    input =
                        listOf(
                            text(UniversalAiInputRole.User, "question"),
                            text(UniversalAiInputRole.Assistant, "answer"),
                        ),
                ),
                request(
                    input =
                        listOf(
                            text(UniversalAiInputRole.User, "question"),
                            text(UniversalAiInputRole.System, "late system"),
                        ),
                ),
                request(
                    input =
                        listOf(
                            text(UniversalAiInputRole.of("future-role"), "unsupported"),
                        ),
                ),
            )

        unsupportedRequests.forEach { unsupportedRequest ->
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
    fun providerFailureMapsSafeMetadataWithoutCredentialOrBodyLeakage(): Unit = runTest {
        val credential = "adversarial-anthropic-credential-fragment"
        val providerFragment = "anthropic-provider-sensitive-fragment"
        val engine =
            MockEngine {
                respond(
                    content =
                        """
                        {
                          "type":"error",
                          "error":{
                            "type":"rate_limit_error",
                            "message":"$credential $providerFragment"
                          },
                          "request_id":"req_body"
                        }
                        """.trimIndent(),
                    status = HttpStatusCode.TooManyRequests,
                    headers =
                        Headers.build {
                            append("request-id", "req_rate_limit")
                            append("retry-after", "7")
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
            assertEquals(ANTHROPIC_RATE_LIMIT_MESSAGE, failure.message)
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
    fun malformedSuccessfulPayloadsFailWithOneFixedSafeError(): Unit = runTest {
        val sensitive = "malformed-anthropic-sensitive-fragment"
        val malformedPayloads =
            listOf(
                """{"not_json":""",
                """
                {
                  "id":"msg_0",
                  "role":"assistant",
                  "content":[{"type":"text","text":"ok"}],
                  "model":"model",
                  "stop_reason":"end_turn",
                  "usage":{"input_tokens":1,"output_tokens":1}
                }
                """.trimIndent(),
                """
                {
                  "id":"msg_0",
                  "type":"message",
                  "role":"assistant",
                  "content":[{"type":"tool_use","name":"$sensitive"}],
                  "model":"model",
                  "stop_reason":"end_turn",
                  "usage":{"input_tokens":1,"output_tokens":1}
                }
                """.trimIndent(),
                """
                {
                  "id":"msg_0",
                  "type":"message",
                  "role":"assistant",
                  "content":[],
                  "model":"model",
                  "stop_reason":"end_turn",
                  "usage":{"input_tokens":1,"output_tokens":1}
                }
                """.trimIndent(),
                """
                {
                  "id":"msg_0",
                  "type":"message",
                  "role":"assistant",
                  "content":[{"type":"text","text":"ok"}],
                  "model":"model",
                  "stop_reason":"end_turn",
                  "usage":{"input_tokens":-1,"output_tokens":1}
                }
                """.trimIndent(),
                """
                {
                  "id":"msg_0",
                  "type":"message",
                  "role":"assistant",
                  "content":[{"type":"text","text":"ok"}],
                  "model":"model",
                  "stop_reason":"end_turn",
                  "usage":{
                    "input_tokens":1,
                    "cache_read_input_tokens":-1,
                    "output_tokens":1
                  }
                }
                """.trimIndent(),
                """
                {
                  "id":"msg_0",
                  "type":"message",
                  "role":"assistant",
                  "content":[{"type":"text","text":"ok"}],
                  "model":"model",
                  "stop_reason":"end_turn",
                  "usage":{
                    "input_tokens":9223372036854775807,
                    "cache_creation_input_tokens":1,
                    "output_tokens":1
                  }
                }
                """.trimIndent(),
            )

        malformedPayloads.forEach { payload ->
            val engine = MockEngine { respond(payload) }
            val connector = connector(engine) { "malformed-anthropic-test-credential" }
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(request())
                    }
                assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
                assertEquals("malformed_provider_response", failure.error.code.rawValue)
                assertEquals(ANTHROPIC_MALFORMED_RESPONSE_MESSAGE, failure.message)
                assertFalse(failure.stackTraceToString().contains(sensitive))
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun oversizedSuccessfulPayloadFailsWithOneFixedSafeError(): Unit = runTest {
        val credential = "oversized-success-anthropic-test-credential"
        val oversizedPayload = ByteArray(TEST_MAX_RESPONSE_BODY_BYTES + 1) { 'x'.code.toByte() }
        val engine =
            MockEngine {
                respond(
                    content = ByteReadChannel(oversizedPayload),
                    status = HttpStatusCode.OK,
                )
            }
        val connector = connector(engine) { credential }

        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.respond(request())
                }

            assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
            assertEquals("malformed_provider_response", failure.error.code.rawValue)
            assertEquals(ANTHROPIC_MALFORMED_RESPONSE_MESSAGE, failure.message)
            assertFalse(failure.stackTraceToString().contains(credential))
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun oversizedProviderErrorBodyIsDiscardedBeforeSafeStatusMapping(): Unit = runTest {
        val credential = "oversized-error-anthropic-test-credential"
        val oversizedPayload = ByteArray(TEST_MAX_ERROR_BODY_BYTES + 1) { 'x'.code.toByte() }
        val engine =
            MockEngine {
                respond(
                    content = ByteReadChannel(oversizedPayload),
                    status = HttpStatusCode.TooManyRequests,
                    headers =
                        Headers.build {
                            append("request-id", "req_oversized_error")
                            append("retry-after", "3")
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
            assertEquals(ANTHROPIC_RATE_LIMIT_MESSAGE, failure.message)
            with(assertNotNull(failure.error.metadata)) {
                assertEquals(429L, number("statusCode")?.toLongOrNull())
                assertEquals("req_oversized_error", string("requestId"))
                assertEquals(3_000L, number("retryAfterMillis")?.toLongOrNull())
            }
            assertFalse(failure.stackTraceToString().contains(credential))
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun callerCancellationCancelsPendingProviderResponseWithoutCanonicalFailure(): Unit = runTest {
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
                "anthropic-cancellation-test-credential"
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
                            providerId = ANTHROPIC_PROVIDER_ID,
                            baseUrl = "https://api.example.invalid/v1",
                            credentialSupplier = credentialSupplier,
                        ),
                    ),
                ),
            httpEngine = engine,
        )

    private fun request(
        providerId: String = "anthropic",
        generation: UniversalAiGenerationParameters =
            UniversalAiGenerationParameters(maxOutputTokens = 64),
        responseFormat: UniversalAiResponseFormat = UniversalAiResponseFormat.PlainText,
        extensions: Extensions = Extensions.Empty,
        input: List<UniversalAiTextInput> =
            listOf(
                text(UniversalAiInputRole.System, "first system rule"),
                text(UniversalAiInputRole.System, "second system rule"),
                text(UniversalAiInputRole.User, "first question"),
                text(UniversalAiInputRole.Assistant, "earlier answer"),
                text(UniversalAiInputRole.User, "follow-up question"),
            ),
    ): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = ProviderId.of(providerId),
                    modelId = ModelId.of("requested-claude-model"),
                ),
            input = input,
            responseFormat = responseFormat,
            generation = generation,
            extensions = extensions,
        )

    private fun text(
        role: UniversalAiInputRole,
        content: String,
    ): UniversalAiTextInput =
        UniversalAiTextInput(
            role = role,
            content = content,
        )

    private fun successResponse(responseId: String): String =
        """
        {
          "id":"$responseId",
          "type":"message",
          "role":"assistant",
          "content":[{"type":"text","text":"ok"}],
          "model":"resolved-claude-model",
          "stop_reason":"end_turn",
          "stop_sequence":null,
          "usage":{"input_tokens":1,"output_tokens":1}
        }
        """.trimIndent()

    private companion object {
        const val TEST_MAX_RESPONSE_BODY_BYTES: Int = 8 * 1_024 * 1_024
        const val TEST_MAX_ERROR_BODY_BYTES: Int = 256 * 1_024
        val ANTHROPIC_PROVIDER_ID: ProviderId = ProviderId.of("anthropic")
        val JSON = Json
    }
}

private fun OutgoingContent.bodyBytes(): ByteArray =
    (this as OutgoingContent.ByteArrayContent).bytes()

private fun JsonObject.string(name: String): String =
    (this[name] as JsonPrimitive).content

private fun JsonObject.int(name: String): Int =
    (this[name] as JsonPrimitive).int

private fun JsonObject.stringArray(name: String): List<String> =
    (this[name] as JsonArray).map { element -> (element as JsonPrimitive).content }
