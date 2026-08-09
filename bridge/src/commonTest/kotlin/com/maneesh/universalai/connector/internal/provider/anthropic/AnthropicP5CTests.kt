package com.maneesh.universalai.connector.internal.provider.anthropic

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiConnectorConfiguration
import com.maneesh.universalai.connector.UniversalAiProviderConfiguration
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.StructuredOutputSchema
import com.maneesh.universalai.connector.contract.StructuredOutputValue
import com.maneesh.universalai.connector.contract.UniversalAiCapabilityLimitName
import com.maneesh.universalai.connector.contract.UniversalAiCapabilityName
import com.maneesh.universalai.connector.contract.UniversalAiCapabilitySupport
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiGenerationParameters
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiOutputKind
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormat
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.internal.provider.ANTHROPIC_PROVIDER_CAPABILITY_PROFILE
import com.maneesh.universalai.connector.internal.provider.ANTHROPIC_PROVIDER_ID
import com.maneesh.universalai.connector.internal.provider.ProviderRegistry
import com.maneesh.universalai.connector.internal.provider.builtInProviderRegistration
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportRequest
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicP5CTests {
    @Test
    fun translatesGovernedSchemaAndValidatesCanonicalStructuredOutput(): Unit = runTest {
        val schema =
            StructuredOutputSchema.parse(
                """
                {
                  "type":"object",
                  "properties":{
                    "answer":{"${'$'}ref":"#/${'$'}defs/answer"},
                    "note":{"anyOf":[{"type":"string"},{"type":"null"}]}
                  },
                  "required":["answer"],
                  "additionalProperties":false,
                  "${'$'}defs":{"answer":{"type":"string","const":"ready"}}
                }
                """.trimIndent(),
            )
        val structuredJson = """{"answer":"ready"}"""
        val engine =
            MockEngine { request ->
                val body =
                    JSON.parseToJsonElement(request.body.bodyBytes().decodeToString()) as JsonObject
                val outputConfig = body["output_config"] as JsonObject
                val format = outputConfig["format"] as JsonObject
                assertEquals("json_schema", format.string("type"))
                assertEquals(JSON.parseToJsonElement(schema.toJson()), format["schema"])
                respond(completedResponse(structuredJson))
            }
        val connector = connector(engine)

        try {
            val response = connector.respond(structuredRequest(schema))
            val output = response.outputs.single()
            assertEquals(UniversalAiOutputKind.StructuredJson, output.kind)
            assertNull(output.text)
            assertEquals(StructuredOutputValue.parse(structuredJson), output.structuredJson)
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun acceptsSupportedFormatsAllOfAndMinItemsOne() {
        val schema =
            StructuredOutputSchema.parse(
                """
                {
                  "type":"object",
                  "properties":{
                    "created":{"type":"string","format":"date-time"},
                    "tags":{"type":"array","items":{"type":"string"},"minItems":1},
                    "state":{"allOf":[{"type":"string"},{"const":"ready"}]}
                  },
                  "required":["created","tags","state"],
                  "additionalProperties":false
                }
                """.trimIndent(),
            )

        assertTrue(AnthropicStructuredOutput.isSupported(schema))
        assertNotNull(
            AnthropicStructuredOutput.parseAndValidate(
                """{"created":"2026-08-08T00:00:00Z","tags":["stable"],"state":"ready"}""",
                schema,
            ),
        )
    }

    @Test
    fun rejectsProviderIncompatibleSchemasBeforeCredentialOrDispatch(): Unit = runTest {
        val schemas =
            listOf(
                """{"type":"number","minimum":0}""",
                """{"type":"string","minLength":1}""",
                """{"type":"array","items":{"type":"string"},"minItems":2}""",
                """{"type":"array","items":{"type":"string"},"maxItems":2}""",
                """{"oneOf":[{"type":"string"},{"type":"null"}]}""",
                """{"type":"array","prefixItems":[{"type":"string"}]}""",
                """{"type":"array","enum":[[]]}""",
                """{"type":"string","format":"uri-template"}""",
                """{"type":"object","properties":{},"required":[],"additionalProperties":true}""",
                """
                {
                  "allOf":[{"${'$'}ref":"#/${'$'}defs/value"}],
                  "${'$'}defs":{"value":{"type":"string"}}
                }
                """.trimIndent(),
                optionalPropertiesSchema(AnthropicStructuredOutput.MAX_OPTIONAL_PROPERTIES + 1),
                unionParametersSchema(AnthropicStructuredOutput.MAX_UNION_PARAMETERS + 1),
            ).map(StructuredOutputSchema::parse)

        schemas.forEach { schema ->
            var credentialCalls = 0
            val engine = MockEngine { error("Unsupported schema must not dispatch.") }
            val connector =
                connector(engine) {
                    credentialCalls += 1
                    "unused-credential"
                }
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(structuredRequest(schema))
                    }

                assertEquals(UniversalAiErrorCategory.Validation, failure.error.category)
                assertEquals("invalid_request", failure.error.code.rawValue)
                assertEquals(ANTHROPIC_STRUCTURED_SCHEMA_MESSAGE, failure.message)
                assertEquals(0, credentialCalls)
                assertTrue(engine.requestHistory.isEmpty())
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun invalidStructuredValuesFailWithOneFixedSafeProtocolError(): Unit = runTest {
        val schema =
            StructuredOutputSchema.parse(
                """
                {
                  "type":"object",
                  "properties":{"answer":{"type":"string","enum":["ready"]}},
                  "required":["answer"],
                  "additionalProperties":false
                }
                """.trimIndent(),
            )
        val sensitive = "anthropic-structured-sensitive-fragment"
        val invalidBodies =
            listOf(
                """{"answer":"Ready"}""",
                """{"answer":"other"}""",
                """{"answer":"ready","extra":"$sensitive"}""",
                "{\"answer\":\"ready\"",
            )

        invalidBodies.forEach { invalidBody ->
            val engine = MockEngine { respond(completedResponse(invalidBody)) }
            val connector = connector(engine)
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(structuredRequest(schema))
                    }

                assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
                assertEquals("invalid_structured_provider_response", failure.error.code.rawValue)
                assertEquals(ANTHROPIC_INVALID_STRUCTURED_RESPONSE_MESSAGE, failure.message)
                assertFalse(failure.stackTraceToString().contains(sensitive))
            } finally {
                connector.close()
                engine.close()
            }
        }

        val multipleBlocksEngine =
            MockEngine {
                respond(
                    completedResponse(
                        """{"answer":"ready"}""",
                        extraContent = """,{"type":"text","text":"unexpected"}""",
                    ),
                )
            }
        val multipleBlocksConnector = connector(multipleBlocksEngine)
        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    multipleBlocksConnector.respond(structuredRequest(schema))
                }
            assertEquals("invalid_structured_provider_response", failure.error.code.rawValue)
        } finally {
            multipleBlocksConnector.close()
            multipleBlocksEngine.close()
        }
    }

    @Test
    fun mapsIncompleteStopReasonsWithoutProviderPayloadLeakage(): Unit = runTest {
        data class Case(
            val stopReason: String,
            val code: String,
            val message: String,
        )

        val cases =
            listOf(
                Case("max_tokens", "provider_output_limit_reached", ANTHROPIC_OUTPUT_LIMIT_MESSAGE),
                Case("refusal", "provider_refused_response", ANTHROPIC_REFUSAL_MESSAGE),
                Case("model_context_window_exceeded", "provider_incomplete_response", ANTHROPIC_INCOMPLETE_RESPONSE_MESSAGE),
                Case("tool_use", "provider_incomplete_response", ANTHROPIC_INCOMPLETE_RESPONSE_MESSAGE),
                Case("pause_turn", "provider_incomplete_response", ANTHROPIC_INCOMPLETE_RESPONSE_MESSAGE),
            )
        val sensitive = "anthropic-incomplete-sensitive-fragment"

        cases.forEach { case ->
            val engine = MockEngine { respond(completedResponse(sensitive, case.stopReason)) }
            val connector = connector(engine)
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(plainRequest())
                    }
                assertEquals(UniversalAiErrorCategory.Provider, failure.error.category)
                assertEquals(case.code, failure.error.code.rawValue)
                assertEquals(case.message, failure.message)
                assertEquals(200L, assertNotNull(failure.error.metadata).number("statusCode")?.toLongOrNull())
                assertFalse(failure.stackTraceToString().contains(sensitive))
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun mapsCompleteHttpStatusSetWithFixedSafeMessagesAndMetadata(): Unit = runTest {
        data class Case(
            val status: Int,
            val category: UniversalAiErrorCategory,
            val code: String,
            val message: String,
        )

        val cases =
            listOf(
                Case(400, UniversalAiErrorCategory.Validation, "provider_invalid_request", ANTHROPIC_INVALID_REQUEST_MESSAGE),
                Case(401, UniversalAiErrorCategory.Authentication, "provider_authentication_failed", ANTHROPIC_AUTHENTICATION_MESSAGE),
                Case(402, UniversalAiErrorCategory.Provider, "provider_billing_error", ANTHROPIC_BILLING_MESSAGE),
                Case(403, UniversalAiErrorCategory.Authorization, "provider_permission_denied", ANTHROPIC_PERMISSION_MESSAGE),
                Case(404, UniversalAiErrorCategory.NotFound, "provider_resource_not_found", ANTHROPIC_NOT_FOUND_MESSAGE),
                Case(409, UniversalAiErrorCategory.Validation, "provider_invalid_request", ANTHROPIC_INVALID_REQUEST_MESSAGE),
                Case(413, UniversalAiErrorCategory.Validation, "provider_invalid_request", ANTHROPIC_INVALID_REQUEST_MESSAGE),
                Case(422, UniversalAiErrorCategory.Validation, "provider_invalid_request", ANTHROPIC_INVALID_REQUEST_MESSAGE),
                Case(429, UniversalAiErrorCategory.RateLimit, "provider_rate_limited", ANTHROPIC_RATE_LIMIT_MESSAGE),
                Case(500, UniversalAiErrorCategory.Provider, "provider_server_error", ANTHROPIC_SERVER_ERROR_MESSAGE),
                Case(504, UniversalAiErrorCategory.Provider, "provider_request_timeout", ANTHROPIC_TIMEOUT_MESSAGE),
                Case(529, UniversalAiErrorCategory.Provider, "provider_unavailable", ANTHROPIC_UNAVAILABLE_MESSAGE),
            )
        val sensitive = "anthropic-error-sensitive-fragment"

        cases.forEach { case ->
            val engine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "type":"error",
                              "error":{"type":"rate_limit_error","message":"$sensitive"},
                              "request_id":"req_body_${case.status}",
                              "future":true
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.fromValue(case.status),
                        headers = Headers.build { append("Retry-After", "2") },
                    )
                }
            val connector = connector(engine)
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(plainRequest())
                    }

                assertEquals(case.category, failure.error.category)
                assertEquals(case.code, failure.error.code.rawValue)
                assertEquals(case.message, failure.message)
                with(assertNotNull(failure.error.metadata)) {
                    assertEquals(case.status.toLong(), number("statusCode")?.toLongOrNull())
                    assertEquals("req_body_${case.status}", string("requestId"))
                    assertEquals(2_000L, number("retryAfterMillis")?.toLongOrNull())
                }
                assertFalse(failure.stackTraceToString().contains(sensitive))
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun mapsDocumentedErrorEnvelopeTypesWhenStatusHasNoDedicatedMapping(): Unit = runTest {
        data class Case(
            val type: String,
            val category: UniversalAiErrorCategory,
            val code: String,
        )

        val cases =
            listOf(
                Case("invalid_request_error", UniversalAiErrorCategory.Validation, "provider_invalid_request"),
                Case("conflict_error", UniversalAiErrorCategory.Validation, "provider_invalid_request"),
                Case("request_too_large", UniversalAiErrorCategory.Validation, "provider_invalid_request"),
                Case("authentication_error", UniversalAiErrorCategory.Authentication, "provider_authentication_failed"),
                Case("billing_error", UniversalAiErrorCategory.Provider, "provider_billing_error"),
                Case("permission_error", UniversalAiErrorCategory.Authorization, "provider_permission_denied"),
                Case("not_found_error", UniversalAiErrorCategory.NotFound, "provider_resource_not_found"),
                Case("rate_limit_error", UniversalAiErrorCategory.RateLimit, "provider_rate_limited"),
                Case("api_error", UniversalAiErrorCategory.Provider, "provider_server_error"),
                Case("timeout_error", UniversalAiErrorCategory.Provider, "provider_request_timeout"),
                Case("overloaded_error", UniversalAiErrorCategory.Provider, "provider_unavailable"),
            )

        cases.forEach { case ->
            val engine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "type":"error",
                              "error":{"type":"${case.type}","message":"unretained"},
                              "request_id":"req_${case.type}"
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.fromValue(599),
                    )
                }
            val connector = connector(engine)
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(plainRequest())
                    }
                assertEquals(case.category, failure.error.category)
                assertEquals(case.code, failure.error.code.rawValue)
                assertEquals(
                    "req_${case.type}",
                    assertNotNull(failure.error.metadata).string("requestId"),
                )
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun mapsUnknownStatusFromSafeEnvelopeAndDiscardsUnusableBodies(): Unit = runTest {
        val sensitive = "anthropic-unretained-envelope-fragment"
        val envelopeEngine =
            MockEngine {
                respond(
                    content =
                        """
                        {
                          "type":"error",
                          "error":{"type":"permission_error","message":"$sensitive","future":true},
                          "request_id":"req_body_fallback"
                        }
                        """.trimIndent(),
                    status = HttpStatusCode.fromValue(599),
                )
            }
        val envelopeConnector = connector(envelopeEngine)
        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    envelopeConnector.respond(plainRequest())
                }
            assertEquals(UniversalAiErrorCategory.Authorization, failure.error.category)
            assertEquals("provider_permission_denied", failure.error.code.rawValue)
            assertEquals("req_body_fallback", assertNotNull(failure.error.metadata).string("requestId"))
            assertFalse(failure.stackTraceToString().contains(sensitive))
        } finally {
            envelopeConnector.close()
            envelopeEngine.close()
        }

        listOf(
            "<html>$sensitive</html>",
            """{"type":"error","request_id":"bad\nvalue"}""",
            """{"type":"message","request_id":"req_not_error"}""",
            """{"type":"error","error":{"type":"future_error"}}""",
        ).forEach { body ->
            val engine =
                MockEngine {
                    respond(content = body, status = HttpStatusCode.fromValue(599))
                }
            val connector = connector(engine)
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(plainRequest())
                    }
                assertEquals("provider_request_failed", failure.error.code.rawValue)
                assertNull(assertNotNull(failure.error.metadata).string("requestId"))
                assertFalse(failure.stackTraceToString().contains(sensitive))
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun reportsAdapterSupportAndUnknownModelBehaviorConservatively() {
        val credentialCalls = mutableListOf<Unit>()
        val registration =
            builtInProviderRegistration(
                UniversalAiProviderConfiguration(
                    providerId = ANTHROPIC_PROVIDER_ID,
                    baseUrl = "https://api.example.invalid/v1",
                    credentialSupplier = {
                        credentialCalls += Unit
                        "unused"
                    },
                ),
            )
        assertEquals(ANTHROPIC_PROVIDER_CAPABILITY_PROFILE, registration.capabilityProfile)
        val profileCapabilities = registration.capabilityProfile.capabilities
        val structured =
            assertNotNull(profileCapabilities[UniversalAiCapabilityName.StructuredOutput])
        assertEquals(UniversalAiCapabilitySupport.Supported, structured.support)
        assertEquals(
            65_536L,
            structured.limits[UniversalAiCapabilityLimitName.MaxSchemaBytes],
        )
        assertNull(structured.limits[UniversalAiCapabilityLimitName.MaxSchemaDepth])
        assertEquals(
            UniversalAiCapabilitySupport.Supported,
            assertNotNull(profileCapabilities[UniversalAiCapabilityName.Streaming]).support,
        )

        val transport =
            object : ConnectorTransport {
                override suspend fun <Result> execute(
                    request: ConnectorTransportRequest,
                    consumeResponse: suspend (ConnectorTransportResponse) -> Result,
                ): Result = error("Capability lookup must not dispatch.")

                override fun close() = Unit
            }
        val registry = ProviderRegistry(listOf(registration), transport)
        val target =
            UniversalAiTarget(
                providerId = ANTHROPIC_PROVIDER_ID,
                modelId = ModelId.of("undocumented-model"),
            )
        val modelCapabilities = assertNotNull(registry.capabilitiesOrNull(target))
        assertEquals(
            UniversalAiCapabilitySupport.Unknown,
            assertNotNull(modelCapabilities[UniversalAiCapabilityName.StructuredOutput]).support,
        )
        assertTrue(
            assertNotNull(modelCapabilities[UniversalAiCapabilityName.StructuredOutput]).limits.isEmpty(),
        )
        assertEquals(
            UniversalAiCapabilitySupport.Supported,
            assertNotNull(modelCapabilities[UniversalAiCapabilityName.Streaming]).support,
        )
        assertTrue(credentialCalls.isEmpty())
    }

    private fun connector(
        engine: MockEngine,
        credentialSupplier: () -> String = { "p5c-test-credential" },
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

    private fun structuredRequest(schema: StructuredOutputSchema): UniversalAiRequest =
        baseRequest(UniversalAiResponseFormat.jsonSchema(schema))

    private fun plainRequest(): UniversalAiRequest =
        baseRequest(UniversalAiResponseFormat.PlainText)

    private fun baseRequest(responseFormat: UniversalAiResponseFormat): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = ANTHROPIC_PROVIDER_ID,
                    modelId = ModelId.of("requested-model"),
                ),
            input =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = "return the requested value",
                    ),
                ),
            responseFormat = responseFormat,
            generation = UniversalAiGenerationParameters(maxOutputTokens = 64),
        )

    private fun completedResponse(
        outputText: String,
        stopReason: String = "end_turn",
        extraContent: String = "",
    ): String =
        """
        {
          "id":"msg_structured",
          "type":"message",
          "role":"assistant",
          "content":[{"type":"text","text":${JsonPrimitive(outputText)}}$extraContent],
          "model":"resolved-model",
          "stop_reason":"$stopReason",
          "stop_sequence":null,
          "usage":{"input_tokens":2,"output_tokens":3}
        }
        """.trimIndent()

    private fun optionalPropertiesSchema(count: Int): String {
        val properties =
            (0 until count).joinToString(",") { index ->
                "\"optional_$index\":{\"type\":\"string\"}"
            }
        return """{"type":"object","properties":{$properties},"required":[],"additionalProperties":false}"""
    }

    private fun unionParametersSchema(count: Int): String {
        val properties =
            (0 until count).joinToString(",") { index ->
                "\"union_$index\":{\"type\":[\"string\",\"null\"]}"
            }
        val required = (0 until count).joinToString(",") { index -> "\"union_$index\"" }
        return """{"type":"object","properties":{$properties},"required":[$required],"additionalProperties":false}"""
    }

    private companion object {
        val JSON = Json
    }
}

private fun OutgoingContent.bodyBytes(): ByteArray =
    (this as OutgoingContent.ByteArrayContent).bytes()

private fun JsonObject.string(name: String): String =
    (this[name] as JsonPrimitive).content
