package com.maneesh.universalai.connector.internal.provider.openaicompatible

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiConnectorConfiguration
import com.maneesh.universalai.connector.UniversalAiProviderConfiguration
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.StructuredOutputSchema
import com.maneesh.universalai.connector.contract.StructuredOutputValue
import com.maneesh.universalai.connector.contract.UniversalAiCapabilityName
import com.maneesh.universalai.connector.contract.UniversalAiCapabilitySupport
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
import com.maneesh.universalai.connector.contract.extension.ExtensionNamespace
import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.contract.extension.Extensions
import com.maneesh.universalai.connector.internal.provider.OPENAI_COMPATIBLE_PROVIDER_ID
import com.maneesh.universalai.connector.internal.provider.OPENAI_COMPATIBLE_PROVIDER_CAPABILITY_PROFILE
import com.maneesh.universalai.connector.internal.provider.ProviderRegistry
import com.maneesh.universalai.connector.internal.provider.builtInProviderRegistration
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportRequest
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportResponse
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
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiCompatibleChatCompletionsAdapterTests {
    @Test
    fun constructsGenericRequestAndTranslatesCompatibleResponse() = runTest {
        val credential = "synthetic-compatible-credential"
        var credentialCalls = 0
        val engine =
            MockEngine {
                respond(
                    content = ByteReadChannel(successResponse()),
                    status = HttpStatusCode.OK,
                    headers =
                        Headers.build {
                            append("X-Request-Id", "req_compatible")
                            append("Retry-After", "3")
                        },
                )
            }
        val connector =
            connector(engine = engine) {
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
                                temperature = 0.25,
                                topP = 0.8,
                                stopSequences = listOf("END", "STOP"),
                            ),
                    ),
                )

            assertEquals(1, credentialCalls)
            assertEquals("chatcmpl_compatible", response.id.rawValue)
            assertEquals("req_compatible", response.requestId?.rawValue)
            assertEquals("openai-compatible", response.target.providerId.rawValue)
            assertEquals("resolved-compatible-model", response.target.modelId.rawValue)
            assertEquals(UniversalAiCompletionReason.Stop, response.completionReason)
            with(response.outputs.single()) {
                assertEquals("chatcmpl_compatible", id.rawValue)
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

            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals(
                "https://compatible.example.invalid/v1/chat/completions",
                sent.url.toString(),
            )
            assertEquals("Bearer $credential", sent.headers["Authorization"])
            assertEquals("application/json", sent.body.contentType?.toString())
            assertEquals("application/json", sent.headers["Accept"])
            assertNull(sent.headers["HTTP-Referer"])
            assertNull(sent.headers["X-OpenRouter-Title"])
            val encoded = sent.body.bodyBytes().decodeToString()
            assertFalse(encoded.contains(credential))
            val document = JSON.parseToJsonElement(encoded) as JsonObject
            assertEquals("requested-compatible-model", document.string("model"))
            assertFalse("stream" in document)
            assertFalse("provider" in document)
            assertEquals(64, document.int("max_tokens"))
            assertEquals(0.25, document.double("temperature"))
            assertEquals(0.8, document.double("top_p"))
            assertEquals(
                listOf("END", "STOP"),
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
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun preservesValidatedBaseUrlPrefixesAndExplicitPorts() = runTest {
        val cases =
            listOf(
                "https://compatible.example.invalid" to
                    "https://compatible.example.invalid/chat/completions",
                "https://compatible.example.invalid/api/v1/" to
                    "https://compatible.example.invalid/api/v1/chat/completions",
                "http://127.0.0.1:11434/v1" to
                    "http://127.0.0.1:11434/v1/chat/completions",
            )

        cases.forEach { (baseUrl, expectedUrl) ->
            val engine = MockEngine { respond(successResponse()) }
            val connector = connector(engine = engine, baseUrl = baseUrl) { "credential" }
            try {
                connector.respond(request())
                assertEquals(expectedUrl, engine.requestHistory.single().url.toString())
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun credentialsAndUnsupportedFeaturesCannotOverrideProtectedHeaders() = runTest {
        val extensions =
            Extensions.of(
                ExtensionNamespace.of("com.example.headers") to
                    ExtensionValue.objectValue(
                        "authorization" to ExtensionValue.string("caller-value"),
                    ),
            )
        val requests =
            listOf(
                request(providerId = "openrouter"),
                request(
                    input =
                        listOf(
                            UniversalAiTextInput(
                                role = UniversalAiInputRole.Developer,
                                content = "unsupported",
                            ),
                        ),
                ),
                request(extensions = extensions),
            )
        var credentialCalls = 0
        val engine = MockEngine { error("Unsupported requests must not dispatch.") }
        val connector =
            connector(engine) {
                credentialCalls += 1
                "credential"
            }

        try {
            requests.forEach { unsupported ->
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
            assertEquals(OPENAI_COMPATIBLE_STREAMING_MESSAGE, streamFailure.message)
            assertEquals(0, credentialCalls)
            assertTrue(engine.requestHistory.isEmpty())
        } finally {
            connector.close()
            engine.close()
        }

        listOf("", "   ", "line\nbreak", "x".repeat(8_193)).forEach { credential ->
            val invalidEngine = MockEngine { error("Invalid credentials must not dispatch.") }
            val invalidConnector = connector(invalidEngine) { credential }
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        invalidConnector.respond(request())
                    }
                assertEquals(UniversalAiErrorCategory.Authentication, failure.error.category)
                assertEquals("missing_credential", failure.error.code.rawValue)
                assertEquals(OPENAI_COMPATIBLE_CREDENTIAL_MESSAGE, failure.message)
                assertTrue(invalidEngine.requestHistory.isEmpty())
            } finally {
                invalidConnector.close()
                invalidEngine.close()
            }
        }
    }

    @Test
    fun structuredOutputUsesTheStrictCompatibleShapeAndRevalidatesTheResult() = runTest {
        val schema = supportedSchema()
        val structuredJson = """{"answer":"ready","score":1}"""
        val engine =
            MockEngine { request ->
                val document =
                    JSON.parseToJsonElement(request.body.bodyBytes().decodeToString()) as JsonObject
                assertFalse("provider" in document)
                val responseFormat = document["response_format"] as JsonObject
                assertEquals("json_schema", responseFormat.string("type"))
                val jsonSchema = responseFormat["json_schema"] as JsonObject
                assertEquals("universal_ai_response", jsonSchema.string("name"))
                assertEquals(JSON.parseToJsonElement(schema.toJson()), jsonSchema["schema"])
                respond(successResponse(text = structuredJson))
            }
        val connector = connector(engine) { "credential" }

        try {
            val output =
                connector
                    .respond(request(responseFormat = UniversalAiResponseFormat.jsonSchema(schema)))
                    .outputs
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
    fun unsupportedSchemasAndInvalidStructuredValuesFailSafely() = runTest {
        val unsupported =
            StructuredOutputSchema.parse(
                """
                {
                  "type":"object",
                  "properties":{"answer":{"type":"string","minLength":1}},
                  "required":["answer"],
                  "additionalProperties":false
                }
                """.trimIndent(),
            )
        var credentialCalls = 0
        val noDispatchEngine = MockEngine { error("Unsupported schemas must not dispatch.") }
        val noDispatchConnector = connector(noDispatchEngine) { credentialCalls += 1; "credential" }
        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    noDispatchConnector.respond(
                        request(responseFormat = UniversalAiResponseFormat.jsonSchema(unsupported)),
                    )
                }
            assertEquals(UniversalAiErrorCategory.Validation, failure.error.category)
            assertEquals(OPENAI_COMPATIBLE_STRUCTURED_SCHEMA_MESSAGE, failure.message)
            assertEquals(0, credentialCalls)
            assertTrue(noDispatchEngine.requestHistory.isEmpty())
        } finally {
            noDispatchConnector.close()
            noDispatchEngine.close()
        }

        val sensitive = "generic-structured-sensitive-fragment"
        val invalidEngine =
            MockEngine {
                respond(
                    successResponse(
                        text = """{"answer":"ready","score":1,"extra":"$sensitive"}""",
                    ),
                )
            }
        val invalidConnector = connector(invalidEngine) { "credential" }
        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    invalidConnector.respond(
                        request(
                            responseFormat = UniversalAiResponseFormat.jsonSchema(supportedSchema()),
                        ),
                    )
                }
            assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
            assertEquals("invalid_structured_provider_response", failure.error.code.rawValue)
            assertEquals(OPENAI_COMPATIBLE_INVALID_STRUCTURED_RESPONSE_MESSAGE, failure.message)
            assertFalse(failure.stackTraceToString().contains(sensitive))
        } finally {
            invalidConnector.close()
            invalidEngine.close()
        }
    }

    @Test
    fun harmlessUnknownResponseFieldsAreIgnoredButSemanticIntrusionsFail() = runTest {
        val acceptedEngine = MockEngine { respond(successResponse()) }
        val acceptedConnector = connector(acceptedEngine) { "credential" }
        try {
            assertEquals("ready", acceptedConnector.respond(request()).outputs.single().text)
        } finally {
            acceptedConnector.close()
            acceptedEngine.close()
        }

        listOf(
            successResponse(objectType = "response"),
            successResponse(extraMessageMembers = ",\"tool_calls\":[{\"id\":\"call_1\"}]"),
            successResponse(extraMessageMembers = ",\"refusal\":\"blocked\""),
            successResponse(extraMessageMembers = ",\"function_call\":{\"name\":\"tool\"}"),
            successResponse(extraMessageMembers = ",\"reasoning_content\":\"hidden\""),
            successResponse(extraChoiceMembers = ",\"delta\":{\"content\":\"streamed\"}"),
            successResponse(finishReason = "tool_calls"),
            successResponse(text = ""),
        ).forEach { payload ->
            val engine = MockEngine { respond(payload) }
            val connector = connector(engine) { "credential" }
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(request())
                    }
                assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
                assertEquals("malformed_provider_response", failure.error.code.rawValue)
                assertEquals(OPENAI_COMPATIBLE_MALFORMED_RESPONSE_MESSAGE, failure.message)
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun choiceLevelErrorInNominalSuccessUsesFixedSafeProviderFailure() = runTest {
        val sensitive = "embedded-provider-sensitive-detail"
        val engine =
            MockEngine {
                respond(
                    successResponse(
                        extraChoiceMembers =
                            ",\"error\":{\"message\":\"$sensitive\",\"code\":500}",
                    ),
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
            assertEquals(OPENAI_COMPATIBLE_PROVIDER_FAILURE_MESSAGE, failure.message)
            assertFalse(failure.stackTraceToString().contains(sensitive))
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun mapsGenericHttpStatusesWithoutTrustingEndpointErrorBodies() = runTest {
        data class Case(
            val status: Int,
            val category: UniversalAiErrorCategory,
            val code: String,
            val message: String,
        )

        val cases =
            listOf(
                Case(400, UniversalAiErrorCategory.Validation, "provider_invalid_request", OPENAI_COMPATIBLE_INVALID_REQUEST_MESSAGE),
                Case(401, UniversalAiErrorCategory.Authentication, "provider_authentication_failed", OPENAI_COMPATIBLE_AUTHENTICATION_MESSAGE),
                Case(403, UniversalAiErrorCategory.Authorization, "provider_permission_denied", OPENAI_COMPATIBLE_PERMISSION_MESSAGE),
                Case(404, UniversalAiErrorCategory.NotFound, "provider_resource_not_found", OPENAI_COMPATIBLE_NOT_FOUND_MESSAGE),
                Case(408, UniversalAiErrorCategory.Provider, "provider_request_timeout", OPENAI_COMPATIBLE_TIMEOUT_MESSAGE),
                Case(429, UniversalAiErrorCategory.RateLimit, "provider_rate_limited", OPENAI_COMPATIBLE_RATE_LIMIT_MESSAGE),
                Case(500, UniversalAiErrorCategory.Provider, "provider_server_error", OPENAI_COMPATIBLE_SERVER_ERROR_MESSAGE),
                Case(503, UniversalAiErrorCategory.Provider, "provider_unavailable", OPENAI_COMPATIBLE_UNAVAILABLE_MESSAGE),
                Case(599, UniversalAiErrorCategory.Provider, "provider_server_error", OPENAI_COMPATIBLE_SERVER_ERROR_MESSAGE),
            )
        val sensitive = "generic-status-sensitive-fragment"
        cases.forEach { case ->
            val engine =
                MockEngine {
                    respond(
                        content =
                            """{"error":{"type":"server-specific","message":"$sensitive"}}""",
                        status = HttpStatusCode.fromValue(case.status),
                    )
                }
            val connector = connector(engine) { "credential" }
            try {
                val failure =
                    assertFailsWith<UniversalAiException> { connector.respond(request()) }
                assertEquals(case.category, failure.error.category)
                assertEquals(case.code, failure.error.code.rawValue)
                assertEquals(case.message, failure.message)
                assertFalse(failure.stackTraceToString().contains(sensitive))
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun reportsUnknownStructuredSupportAndUnsupportedStreamingConservatively() {
        val configuration =
            UniversalAiProviderConfiguration(
                providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                baseUrl = "https://compatible.example.invalid/v1",
                credentialSupplier = { error("Capability lookup must not resolve credentials.") },
            )
        val registration = builtInProviderRegistration(configuration)
        assertEquals(
            OPENAI_COMPATIBLE_PROVIDER_CAPABILITY_PROFILE,
            registration.capabilityProfile,
        )
        assertEquals(
            UniversalAiCapabilitySupport.Unknown,
            assertNotNull(
                registration.capabilityProfile.capabilities[
                    UniversalAiCapabilityName.StructuredOutput
                ],
            ).support,
        )
        assertEquals(
            UniversalAiCapabilitySupport.Unsupported,
            assertNotNull(
                registration.capabilityProfile.capabilities[
                    UniversalAiCapabilityName.Streaming
                ],
            ).support,
        )
        val registry = ProviderRegistry(listOf(registration), noDispatchTransport())
        val modelCapabilities =
            assertNotNull(
                registry.capabilitiesOrNull(
                    UniversalAiTarget(
                        providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                        modelId = ModelId.of("unverified-compatible-model"),
                    ),
                ),
            )
        assertEquals(
            UniversalAiCapabilitySupport.Unknown,
            assertNotNull(modelCapabilities[UniversalAiCapabilityName.StructuredOutput]).support,
        )
        assertEquals(
            UniversalAiCapabilitySupport.Unsupported,
            assertNotNull(modelCapabilities[UniversalAiCapabilityName.Streaming]).support,
        )
    }

    @Test
    fun errorsUseFixedGenericMappingWithoutCredentialOrProviderBody() = runTest {
        val credential = "adversarial-compatible-credential"
        val providerDetail = "provider-sensitive-detail"
        val engine =
            MockEngine {
                respond(
                    content = """{"error":{"message":"$credential $providerDetail"}}""",
                    status = HttpStatusCode.TooManyRequests,
                    headers =
                        Headers.build {
                            append("X-Request-Id", "req_generic_error")
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
            assertEquals(OPENAI_COMPATIBLE_RATE_LIMIT_MESSAGE, failure.message)
            with(assertNotNull(failure.error.metadata)) {
                assertEquals(429L, number("statusCode")?.toLongOrNull())
                assertEquals("req_generic_error", string("requestId"))
                assertEquals(7_000L, number("retryAfterMillis")?.toLongOrNull())
            }
            val diagnostic = failure.stackTraceToString()
            assertFalse(diagnostic.contains(credential))
            assertFalse(diagnostic.contains(providerDetail))
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun credentialAndPendingRequestCancellationRemainCallerCancellation() = runTest {
        val supplierCancellation = CancellationException("credential lookup cancelled")
        val supplierEngine = MockEngine { error("Cancellation must prevent dispatch.") }
        val supplierConnector = connector(supplierEngine) { throw supplierCancellation }
        try {
            assertFailsWith<CancellationException> {
                supplierConnector.respond(request())
            }
            assertTrue(supplierEngine.requestHistory.isEmpty())
        } finally {
            supplierConnector.close()
            supplierEngine.close()
        }

        val requestStarted = CompletableDeferred<Unit>()
        val pendingEngine =
            MockEngine {
                requestStarted.complete(Unit)
                awaitCancellation()
            }
        val pendingConnector = connector(pendingEngine) { "credential" }
        try {
            val operation =
                async(start = CoroutineStart.UNDISPATCHED) {
                    pendingConnector.respond(request())
                }
            requestStarted.await()
            operation.cancel()
            assertFailsWith<CancellationException> {
                operation.await()
            }
        } finally {
            pendingConnector.close()
            pendingEngine.close()
        }
    }

    private fun connector(
        engine: MockEngine,
        baseUrl: String = "https://compatible.example.invalid/v1",
        credentialSupplier: () -> String,
    ): UniversalAiConnector =
        UniversalAiConnector(
            configuration =
                UniversalAiConnectorConfiguration(
                    listOf(
                        UniversalAiProviderConfiguration(
                            providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                            baseUrl = baseUrl,
                            credentialSupplier = credentialSupplier,
                        ),
                    ),
                ),
            httpEngine = engine,
        )

    private fun request(
        providerId: String = "openai-compatible",
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
        extensions: Extensions = Extensions.Empty,
    ): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = ProviderId.of(providerId),
                    modelId = ModelId.of("requested-compatible-model"),
                ),
            input = input,
            responseFormat = responseFormat,
            generation = generation,
            extensions = extensions,
        )

    private fun successResponse(
        id: String = "chatcmpl_compatible",
        objectType: String = "chat.completion",
        model: String = "resolved-compatible-model",
        text: String = "ready",
        finishReason: String = "stop",
        extraMessageMembers: String = "",
        extraChoiceMembers: String = "",
    ): String =
        """
        {
          "id":"$id",
          "object":"$objectType",
          "model":"$model",
          "choices":[{
            "index":0,
            "message":{
              "role":"assistant",
              "content":${JsonPrimitive(text)}
              $extraMessageMembers
            },
            "finish_reason":"$finishReason",
            "future_choice_field":true
            $extraChoiceMembers
          }],
          "usage":{
            "prompt_tokens":12,
            "completion_tokens":5,
            "total_tokens":17,
            "prompt_tokens_details":{"cached_tokens":2},
            "completion_tokens_details":{"reasoning_tokens":1},
            "future_usage_field":true
          },
          "future_response_field":true
        }
        """.trimIndent()

    private fun supportedSchema(): StructuredOutputSchema =
        StructuredOutputSchema.parse(
            """
            {
              "type":"object",
              "properties":{
                "answer":{"type":"string","enum":["ready"]},
                "score":{"type":"integer","minimum":1,"maximum":2}
              },
              "required":["answer","score"],
              "additionalProperties":false
            }
            """.trimIndent(),
        )

    private fun noDispatchTransport(): ConnectorTransport =
        object : ConnectorTransport {
            override suspend fun <Result> execute(
                request: ConnectorTransportRequest,
                consumeResponse: suspend (ConnectorTransportResponse) -> Result,
            ): Result = error("Capability lookup must not dispatch.")

            override fun close() = Unit
        }
}

private fun OutgoingContent.bodyBytes(): ByteArray =
    (this as OutgoingContent.ByteArrayContent).bytes()

private fun JsonObject.string(name: String): String =
    (this[name] as JsonPrimitive).content

private fun JsonObject.int(name: String): Int =
    (this[name] as JsonPrimitive).int

private fun JsonObject.double(name: String): Double =
    (this[name] as JsonPrimitive).double

private val JSON = Json
