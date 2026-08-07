package com.maneesh.universalai.connector.internal.provider.openrouter

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiConnectorConfiguration
import com.maneesh.universalai.connector.UniversalAiProviderConfiguration
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.StructuredOutputSchema
import com.maneesh.universalai.connector.contract.UniversalAiCompletionReason
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiGenerationParameters
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiOutputKind
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormat
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.internal.provider.OPENROUTER_PROVIDER_ID
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
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

class OpenRouterChatCompletionsAdapterTests {
    @Test
    fun translatesAuthenticationOrderedTextRequestResponseUsageAndMetadata() = runTest {
        val credential = "synthetic-openrouter-credential"
        var credentialCalls = 0
        val engine =
            MockEngine {
                respond(
                    content =
                        ByteReadChannel(
                            successResponse(
                                id = "chatcmpl_test",
                                model = "resolved/provider-model",
                                text = "ready",
                                finishReason = "stop",
                            ),
                        ),
                    status = HttpStatusCode.OK,
                    headers =
                        Headers.build {
                            append("X-Request-Id", "req_openrouter")
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
                                maxOutputTokens = 64,
                                temperature = 0.25,
                                topP = 0.8,
                                stopSequences = listOf("END", "STOP"),
                            ),
                    ),
                )

            assertEquals(1, credentialCalls)
            assertEquals("chatcmpl_test", response.id.rawValue)
            assertEquals("req_openrouter", response.requestId?.rawValue)
            assertEquals("openrouter", response.target.providerId.rawValue)
            assertEquals("resolved/provider-model", response.target.modelId.rawValue)
            assertEquals(UniversalAiCompletionReason.Stop, response.completionReason)
            with(response.outputs.single()) {
                assertEquals("chatcmpl_test", id.rawValue)
                assertEquals(0, index)
                assertEquals(UniversalAiOutputKind.Text, kind)
                assertEquals("ready", text)
            }
            with(assertNotNull(response.usage)) {
                assertEquals(12L, inputTokens)
                assertEquals(5L, outputTokens)
                assertEquals(17L, totalTokens)
                assertEquals(mapOf("cached_tokens" to 2L), inputDetails)
                assertEquals(mapOf("reasoning_tokens" to 1L), outputDetails)
            }

            val sentRequest = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sentRequest.method)
            assertEquals(
                "https://openrouter.example.invalid/api/v1/chat/completions",
                sentRequest.url.toString(),
            )
            assertEquals("Bearer $credential", sentRequest.headers["Authorization"])
            assertEquals("application/json", sentRequest.body.contentType?.toString())
            assertEquals("application/json", sentRequest.headers["Accept"])
            assertNull(sentRequest.headers["HTTP-Referer"])
            assertNull(sentRequest.headers["X-OpenRouter-Title"])
            val requestBody = sentRequest.body.bodyBytes().decodeToString()
            assertFalse(requestBody.contains(credential))
            val document = JSON.parseToJsonElement(requestBody) as JsonObject
            assertEquals("requested/provider-model", document.string("model"))
            assertFalse("stream" in document)
            assertEquals(64, document.int("max_tokens"))
            assertEquals(0.25, document.double("temperature"))
            assertEquals(0.8, document.double("top_p"))
            assertEquals(
                listOf("END", "STOP"),
                (document["stop"] as JsonArray).map { element ->
                    (element as JsonPrimitive).content
                },
            )
            val messages = document["messages"] as JsonArray
            assertEquals(
                listOf("system", "user", "assistant"),
                messages.map { element -> (element as JsonObject).string("role") },
            )
            assertEquals(
                listOf("system rules", "question", "earlier answer"),
                messages.map { element -> (element as JsonObject).string("content") },
            )
            assertTrue((document["provider"] as JsonObject).boolean("require_parameters"))
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun omitsAbsentGenerationFieldsAndResolvesCredentialOncePerRequest() = runTest {
        var credentialCalls = 0
        val engine =
            MockEngine { request ->
                val document =
                    JSON.parseToJsonElement(
                        request.body.bodyBytes().decodeToString(),
                    ) as JsonObject
                assertFalse("max_tokens" in document)
                assertFalse("temperature" in document)
                assertFalse("top_p" in document)
                assertFalse("stop" in document)
                respond(successResponse(id = "response-$credentialCalls"))
            }
        val connector =
            connector(engine) {
                credentialCalls += 1
                "request-scoped-credential"
            }

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
    fun missingBlankMalformedAndThrowingCredentialsFailBeforeDispatchWithoutDetails() = runTest {
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
                assertEquals(OPENROUTER_CREDENTIAL_MESSAGE, failure.message)
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
    fun unsupportedProviderRolesFormatsAndStreamingFailBeforeDispatch() = runTest {
        val engine = MockEngine { error("Unsupported requests must not dispatch.") }
        val connector = connector(engine) { "credential" }
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

        try {
            val wrongProvider =
                request(
                    providerId = "openai",
                )
            val developerInput =
                request(
                    input =
                        listOf(
                            UniversalAiTextInput(
                                role = UniversalAiInputRole.Developer,
                                content = "developer instruction",
                            ),
                        ),
                )
            val structured =
                request(
                    responseFormat = UniversalAiResponseFormat.jsonSchema(schema),
                )

            listOf(wrongProvider, developerInput, structured).forEach { unsupported ->
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(unsupported)
                    }
                assertEquals(UniversalAiErrorCategory.Validation, failure.error.category)
                assertEquals("invalid_request", failure.error.code.rawValue)
            }
            val streamFailure =
                assertFailsWith<UniversalAiException> {
                    connector.stream(request()).collect {}
                }
            assertEquals(OPENROUTER_STREAMING_MESSAGE, streamFailure.message)
            assertEquals(0, engine.requestHistory.size)
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun statusFailuresMapOnlySafeHttpAndTransportMetadata() = runTest {
        val credential = "adversarial-credential-fragment"
        val providerFragment = "provider-body-sensitive-fragment"
        val engine =
            MockEngine {
                respond(
                    content = """{"error":{"message":"$credential $providerFragment"}}""",
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
            assertEquals(OPENROUTER_RATE_LIMIT_MESSAGE, failure.message)
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
    fun successfulEnvelopeErrorMapsToFixedSafeProviderFailure() = runTest {
        val sensitive = "provider-sensitive-message"
        val engine =
            MockEngine {
                respond(
                    content =
                        """
                        {
                          "error":{
                            "code":500,
                            "message":"$sensitive",
                            "metadata":{"provider_name":"sensitive-upstream"}
                          }
                        }
                        """.trimIndent(),
                )
            }
        val connector = connector(engine) { "credential" }

        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.respond(request())
                }

            assertEquals(UniversalAiErrorCategory.Provider, failure.error.category)
            assertEquals("provider_request_failed", failure.error.code.rawValue)
            assertEquals(OPENROUTER_PROVIDER_FAILURE_MESSAGE, failure.message)
            assertFalse(failure.stackTraceToString().contains(sensitive))
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun malformedIncompleteOrUnsupportedSuccessPayloadsUseOneFixedSafeError() = runTest {
        val malformedPayloads =
            listOf(
                """{"not_json":""",
                successResponse(objectType = "response"),
                successResponse(choices = "[]"),
                successResponse(
                    choices =
                        """
                        [
                          {
                            "index":0,
                            "message":{"role":"assistant","content":"one"},
                            "finish_reason":"stop"
                          },
                          {
                            "index":1,
                            "message":{"role":"assistant","content":"two"},
                            "finish_reason":"stop"
                          }
                        ]
                        """.trimIndent(),
                ),
                successResponse(choiceIndex = 1),
                successResponse(role = "tool"),
                successResponse(text = ""),
                successResponse(text = "   "),
                successResponse(finishReason = "tool_calls"),
                successResponse(
                    extraMessageMembers = ""","tool_calls":[{"id":"call_1"}]""",
                ),
                successResponse(
                    extraMessageMembers = ""","reasoning_details":[{"type":"reasoning.text"}]""",
                ),
                successResponse(
                    usage =
                        """
                        {"prompt_tokens":12,"completion_tokens":5,"total_tokens":18}
                        """.trimIndent(),
                ),
            )

        malformedPayloads.forEach { payload ->
            val engine = MockEngine { respond(payload) }
            val connector = connector(engine) { "credential" }
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(request())
                    }
                assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
                assertEquals("malformed_provider_response", failure.error.code.rawValue)
                assertEquals(OPENROUTER_MALFORMED_RESPONSE_MESSAGE, failure.message)
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun supportedFinishReasonsTranslateConservatively() = runTest {
        val cases =
            listOf(
                "stop" to UniversalAiCompletionReason.Stop,
                "length" to UniversalAiCompletionReason.MaxOutputTokens,
                "content_filter" to UniversalAiCompletionReason.ContentFilter,
            )

        cases.forEach { (finishReason, expected) ->
            val engine =
                MockEngine {
                    respond(
                        successResponse(
                            id = "response-$finishReason",
                            finishReason = finishReason,
                        ),
                    )
                }
            val connector = connector(engine) { "credential" }
            try {
                assertEquals(expected, connector.respond(request()).completionReason)
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun cancellingPendingMockEngineRequestRemainsCallerCancellation() = runTest {
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
                "credential"
            }

        try {
            val operation =
                async(start = CoroutineStart.UNDISPATCHED) {
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
                            providerId = OPENROUTER_PROVIDER_ID,
                            baseUrl = "https://openrouter.example.invalid/api/v1",
                            credentialSupplier = credentialSupplier,
                        ),
                    ),
                ),
            httpEngine = engine,
        )

    private fun request(
        providerId: String = "openrouter",
        generation: UniversalAiGenerationParameters = UniversalAiGenerationParameters.Default,
        responseFormat: UniversalAiResponseFormat = UniversalAiResponseFormat.PlainText,
        input: List<UniversalAiTextInput> =
            listOf(
                UniversalAiTextInput(
                    role = UniversalAiInputRole.System,
                    content = "system rules",
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
                    providerId = ProviderId.of(providerId),
                    modelId = ModelId.of("requested/provider-model"),
                ),
            input = input,
            responseFormat = responseFormat,
            generation = generation,
        )

    private fun successResponse(
        id: String = "chatcmpl_test",
        objectType: String = "chat.completion",
        model: String = "resolved/provider-model",
        text: String = "ready",
        role: String = "assistant",
        finishReason: String = "stop",
        choiceIndex: Int = 0,
        choices: String? = null,
        extraMessageMembers: String = "",
        usage: String =
            """
            {
              "prompt_tokens":12,
              "completion_tokens":5,
              "total_tokens":17,
              "prompt_tokens_details":{"cached_tokens":2},
              "completion_tokens_details":{"reasoning_tokens":1}
            }
            """.trimIndent(),
    ): String =
        """
        {
          "id":"$id",
          "object":"$objectType",
          "model":"$model",
          "choices":${choices ?: """
            [{
              "index":$choiceIndex,
              "message":{
                "role":"$role",
                "content":"$text"
                $extraMessageMembers
              },
              "finish_reason":"$finishReason"
            }]
          """.trimIndent()},
          "usage":$usage,
          "future_optional_field":true
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
