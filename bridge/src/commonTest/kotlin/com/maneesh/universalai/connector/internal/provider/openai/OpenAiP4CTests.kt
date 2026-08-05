package com.maneesh.universalai.connector.internal.provider.openai

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
import com.maneesh.universalai.connector.internal.provider.OPENAI_PROVIDER_CAPABILITY_PROFILE
import com.maneesh.universalai.connector.internal.provider.OPENAI_PROVIDER_ID
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
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiP4CTests {
    @Test
    fun translatesGovernedSchemaAndValidatesCanonicalStructuredOutput() = runTest {
        val schema =
            StructuredOutputSchema.parse(
                """
                {
                  "type": "object",
                  "properties": {
                    "answer": {"type": "string", "enum": ["ready"]},
                    "score": {"type": "integer", "minimum": 1, "maximum": 2},
                    "tags": {
                      "type": "array",
                      "items": {"type": "string"},
                      "minItems": 1,
                      "maxItems": 2
                    }
                  },
                  "required": ["answer", "score", "tags"],
                  "additionalProperties": false
                }
                """.trimIndent(),
            )
        val structuredJson = """{"answer":"ready","score":1.0,"tags":["stable"]}"""
        val engine =
            MockEngine { request ->
                val document =
                    JSON.parseToJsonElement(request.body.p4cBodyBytes().decodeToString()) as JsonObject
                val text = document["text"] as JsonObject
                val format = text["format"] as JsonObject
                assertEquals("json_schema", format.string("type"))
                assertEquals("universal_ai_response", format.string("name"))
                assertTrue(format.boolean("strict"))
                assertEquals(
                    JSON.parseToJsonElement(schema.toJson()),
                    format["schema"],
                )
                respond(
                    content =
                        ByteReadChannel(
                            completedResponse(
                                outputText = structuredJson,
                                extra = ""","future_optional":{"safe":true}""",
                            ),
                        ),
                )
            }
        val connector = connector(engine)

        try {
            val response = connector.respond(request(schema))

            val output = response.outputs.single()
            assertEquals(UniversalAiOutputKind.StructuredJson, output.kind)
            assertNull(output.text)
            assertEquals(
                StructuredOutputValue.parse(structuredJson),
                output.structuredJson,
            )
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun supportsDefinitionsAndNestedAnyOfWithinTheGovernedProviderSubset() = runTest {
        val schema =
            StructuredOutputSchema.parse(
                """
                {
                  "type":"object",
                  "properties":{"payload":{"${'$'}ref":"#/${'$'}defs/payload"}},
                  "required":["payload"],
                  "additionalProperties":false,
                  "${'$'}defs":{
                    "payload":{
                      "type":"object",
                      "properties":{
                        "state":{"type":"string","const":"ready"},
                        "note":{"anyOf":[{"type":"string"},{"type":"null"}]}
                      },
                      "required":["state","note"],
                      "additionalProperties":false
                    }
                  }
                }
                """.trimIndent(),
            )
        val structuredJson = """{"payload":{"state":"ready","note":null}}"""
        val engine = MockEngine { respond(completedResponse(structuredJson)) }
        val connector = connector(engine)

        try {
            val response = connector.respond(request(schema))
            assertEquals(
                StructuredOutputValue.parse(structuredJson),
                assertNotNull(response.outputs.single().structuredJson),
            )
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun acceptsNullForNullableNumberWithNumericBounds() = runTest {
        val schema =
            StructuredOutputSchema.parse(
                """
                {
                  "type":"object",
                  "properties":{
                    "score":{
                      "type":["number","null"],
                      "minimum":0,
                      "maximum":1
                    }
                  },
                  "required":["score"],
                  "additionalProperties":false
                }
                """.trimIndent(),
            )
        val structuredJson = """{"score":null}"""
        val engine = MockEngine { respond(completedResponse(structuredJson)) }
        val connector = connector(engine)

        try {
            val response = connector.respond(request(schema))

            assertEquals(
                StructuredOutputValue.parse(structuredJson),
                assertNotNull(response.outputs.single().structuredJson),
            )
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun enforcesMathematicalIntegerArrayBounds() = runTest {
        val cases =
            listOf(
                """{"minItems":2.0}""" to """{"tags":["one"]}""",
                """{"maxItems":1e0}""" to """{"tags":["one","two"]}""",
            )

        cases.forEach { (bound, structuredJson) ->
            val schema =
                StructuredOutputSchema.parse(
                    """
                    {
                      "type":"object",
                      "properties":{
                        "tags":{
                          "type":"array",
                          "items":{"type":"string"},
                          ${bound.removePrefix("{").removeSuffix("}")}
                        }
                      },
                      "required":["tags"],
                      "additionalProperties":false
                    }
                    """.trimIndent(),
                )
            val engine = MockEngine { respond(completedResponse(structuredJson)) }
            val connector = connector(engine)

            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(request(schema))
                    }

                assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
                assertEquals("invalid_structured_provider_response", failure.error.code.rawValue)
                assertEquals(OPENAI_INVALID_STRUCTURED_RESPONSE_MESSAGE, failure.message)
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun rejectsProviderIncompatibleGovernedSchemasBeforeCredentialOrDispatch() = runTest {
        val schemas =
            (
                listOf(
                """{"type":"array","items":{"type":"string"}}""",
                """
                {
                  "type":"object",
                  "properties":{"value":{"type":"string"}},
                  "required":[],
                  "additionalProperties":false
                }
                """.trimIndent(),
                """
                {
                  "type":"object",
                  "properties":{},
                  "required":[],
                  "additionalProperties":true
                }
                """.trimIndent(),
                """
                {
                  "type":"object",
                  "properties":{},
                  "required":[],
                  "additionalProperties":false,
                  "allOf":[{"type":"object","additionalProperties":false}]
                }
                """.trimIndent(),
                """
                {
                  "type":"object",
                  "properties":{"value":{"type":"string","minLength":1}},
                  "required":["value"],
                  "additionalProperties":false
                }
                """.trimIndent(),
                """
                {
                  "${'$'}schema":"https://json-schema.org/draft/2020-12/schema",
                  "type":"object",
                  "properties":{},
                  "required":[],
                  "additionalProperties":false
                }
                """.trimIndent(),
                ) +
                    listOf(
                        nestedObjectSchema(levels = 11),
                        nestedArraySchema(levels = 11),
                        nestedAnyOfSchema(levels = 11),
                    )
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
                        connector.respond(request(schema))
                    }

                assertEquals(UniversalAiErrorCategory.Validation, failure.error.category)
                assertEquals("invalid_request", failure.error.code.rawValue)
                assertEquals(OPENAI_STRUCTURED_SCHEMA_MESSAGE, failure.message)
                assertEquals(0, credentialCalls)
                assertTrue(engine.requestHistory.isEmpty())
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun boundsRepeatedReferenceDagAtTheExactProviderDepthLimit() {
        assertTrue(
            OpenAiStructuredOutput.isSupported(
                StructuredOutputSchema.parse(
                    repeatedReferenceDagSchema(levels = 8, branches = 32),
                ),
            ),
        )
        assertFalse(
            OpenAiStructuredOutput.isSupported(
                StructuredOutputSchema.parse(
                    repeatedReferenceDagSchema(levels = 9, branches = 32),
                ),
            ),
        )
    }

    @Test
    fun invalidStructuredValuesFailWithOneFixedSafeProtocolError() = runTest {
        val schema =
            StructuredOutputSchema.parse(
                """
                {
                  "type":"object",
                  "properties":{
                    "state":{"type":"string","enum":["ready"]},
                    "count":{"type":"integer","minimum":1,"maximum":2}
                  },
                  "required":["state","count"],
                  "additionalProperties":false
                }
                """.trimIndent(),
            )
        val sensitive = "provider-structured-sensitive-fragment"
        val invalidValues =
            listOf(
                """{"state":"ready"}""",
                """{"state":"ready","count":1,"extra":"$sensitive"}""",
                """{"state":"other","count":1}""",
                """{"state":"ready","count":"1"}""",
                """{"state":"ready","count":3}""",
                """{"state":"ready","count":1""",
            )

        invalidValues.forEach { invalidValue ->
            val engine = MockEngine { respond(completedResponse(invalidValue)) }
            val connector = connector(engine)
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(request(schema))
                    }

                assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
                assertEquals("invalid_structured_provider_response", failure.error.code.rawValue)
                assertEquals(OPENAI_INVALID_STRUCTURED_RESPONSE_MESSAGE, failure.message)
                assertFalse(failure.stackTraceToString().contains(sensitive))
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun mapsCompleteHttpStatusSetWithFixedSafeMessagesAndMetadata() = runTest {
        data class Case(
            val status: Int,
            val category: UniversalAiErrorCategory,
            val code: String,
            val message: String,
        )

        val cases =
            listOf(
                Case(400, UniversalAiErrorCategory.Validation, "provider_invalid_request", OPENAI_INVALID_REQUEST_MESSAGE),
                Case(401, UniversalAiErrorCategory.Authentication, "provider_authentication_failed", OPENAI_AUTHENTICATION_MESSAGE),
                Case(403, UniversalAiErrorCategory.Authorization, "provider_permission_denied", OPENAI_PERMISSION_MESSAGE),
                Case(404, UniversalAiErrorCategory.NotFound, "provider_resource_not_found", OPENAI_NOT_FOUND_MESSAGE),
                Case(408, UniversalAiErrorCategory.Provider, "provider_request_timeout", OPENAI_TIMEOUT_MESSAGE),
                Case(409, UniversalAiErrorCategory.Validation, "provider_invalid_request", OPENAI_INVALID_REQUEST_MESSAGE),
                Case(422, UniversalAiErrorCategory.Validation, "provider_invalid_request", OPENAI_INVALID_REQUEST_MESSAGE),
                Case(429, UniversalAiErrorCategory.RateLimit, "provider_rate_limited", OPENAI_RATE_LIMIT_MESSAGE),
                Case(500, UniversalAiErrorCategory.Provider, "provider_server_error", OPENAI_SERVER_ERROR_MESSAGE),
                Case(502, UniversalAiErrorCategory.Provider, "provider_unavailable", OPENAI_UNAVAILABLE_MESSAGE),
                Case(503, UniversalAiErrorCategory.Provider, "provider_unavailable", OPENAI_UNAVAILABLE_MESSAGE),
                Case(504, UniversalAiErrorCategory.Provider, "provider_unavailable", OPENAI_UNAVAILABLE_MESSAGE),
            )
        val sensitive = "provider-error-sensitive-fragment"

        cases.forEach { case ->
            val engine =
                MockEngine {
                    respond(
                        content =
                            """{"error":{"type":"rate_limit_exceeded","message":"$sensitive"}}""",
                        status = HttpStatusCode.fromValue(case.status),
                        headers =
                            Headers.build {
                                append("X-Request-Id", "req_${case.status}")
                                append("Retry-After", "2")
                            },
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
                    assertEquals("req_${case.status}", string("requestId"))
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
    fun defensivelyMapsErrorEnvelopesFailedResponsesAndUnusableBodies() = runTest {
        val sensitive = "unretained-provider-error-fragment"
        val cases =
            listOf(
                Triple(
                    599,
                    """{"error":{"type":"rate_limit_exceeded","message":"$sensitive","future":true}}""",
                    "provider_rate_limited",
                ),
                Triple(599, "<html>$sensitive</html>", "provider_request_failed"),
                Triple(500, """{"error":{"message":"${"x".repeat(300_000)}"}}""", "provider_server_error"),
            )

        cases.forEach { (status, body, expectedCode) ->
            val engine =
                MockEngine {
                    respond(
                        content = body,
                        status = HttpStatusCode.fromValue(status),
                    )
                }
            val connector = connector(engine)
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(plainRequest())
                    }

                assertEquals(expectedCode, failure.error.code.rawValue)
                assertFalse(failure.stackTraceToString().contains(sensitive))
                assertFalse(failure.stackTraceToString().contains("x".repeat(256)))
            } finally {
                connector.close()
                engine.close()
            }
        }

        val failedEngine =
            MockEngine {
                respond(
                    """
                    {
                      "object":"response",
                      "status":"failed",
                      "error":{
                        "code":"server_error",
                        "message":"$sensitive",
                        "future_optional":true
                      }
                    }
                    """.trimIndent(),
                    headers = Headers.build { append("X-Request-Id", "req_failed") },
                )
            }
        val connector = connector(failedEngine)
        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.respond(plainRequest())
                }

            assertEquals(UniversalAiErrorCategory.Provider, failure.error.category)
            assertEquals("provider_server_error", failure.error.code.rawValue)
            assertEquals(OPENAI_SERVER_ERROR_MESSAGE, failure.message)
            assertEquals(
                "req_failed",
                assertNotNull(failure.error.metadata).string("requestId"),
            )
            assertFalse(failure.stackTraceToString().contains(sensitive))
        } finally {
            connector.close()
            failedEngine.close()
        }
    }

    @Test
    fun mapsIncompleteReasonsAndRefusalsWithoutProviderPayloadLeakage() = runTest {
        data class IncompleteCase(
            val reason: String?,
            val code: String,
            val message: String,
        )

        val cases =
            listOf(
                IncompleteCase("max_output_tokens", "provider_output_limit_reached", OPENAI_OUTPUT_LIMIT_MESSAGE),
                IncompleteCase("content_filter", "provider_response_filtered", OPENAI_FILTERED_RESPONSE_MESSAGE),
                IncompleteCase("future_reason", "provider_incomplete_response", OPENAI_INCOMPLETE_RESPONSE_MESSAGE),
                IncompleteCase(null, "provider_incomplete_response", OPENAI_INCOMPLETE_RESPONSE_MESSAGE),
            )
        cases.forEach { case ->
            val reasonMember =
                case.reason?.let { reason -> """"reason":"$reason","future":true""" }
                    ?: """"future":true"""
            val engine =
                MockEngine {
                    respond(
                        """
                        {
                          "object":"response",
                          "status":"incomplete",
                          "incomplete_details":{$reasonMember}
                        }
                        """.trimIndent(),
                    )
                }
            val connector = connector(engine)
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(plainRequest())
                    }
                assertEquals(UniversalAiErrorCategory.Provider, failure.error.category)
                assertEquals(case.code, failure.error.code.rawValue)
                assertEquals(case.message, failure.message)
            } finally {
                connector.close()
                engine.close()
            }
        }

        val sensitive = "provider-refusal-sensitive-fragment"
        val refusalEngine =
            MockEngine {
                respond(
                    """
                    {
                      "id":"resp_refusal",
                      "object":"response",
                      "status":"completed",
                      "model":"resolved-model",
                      "output":[{
                        "id":"message_0",
                        "type":"message",
                        "status":"completed",
                        "role":"assistant",
                        "content":[{"type":"refusal","refusal":"$sensitive"}]
                      }],
                      "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}
                    }
                    """.trimIndent(),
                )
            }
        val refusalConnector = connector(refusalEngine)
        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    refusalConnector.respond(plainRequest())
                }
            assertEquals("provider_refused_response", failure.error.code.rawValue)
            assertEquals(OPENAI_REFUSAL_MESSAGE, failure.message)
            assertFalse(failure.stackTraceToString().contains(sensitive))
        } finally {
            refusalConnector.close()
            refusalEngine.close()
        }
    }

    @Test
    fun reportsAdapterSupportAndUnknownModelBehaviorConservatively() {
        val credentialCalls = mutableListOf<Unit>()
        val configuration =
            UniversalAiProviderConfiguration(
                providerId = OPENAI_PROVIDER_ID,
                baseUrl = "https://api.example.invalid/v1",
                credentialSupplier = {
                    credentialCalls += Unit
                    "unused"
                },
            )
        val registration = builtInProviderRegistration(configuration)
        assertEquals(OPENAI_PROVIDER_CAPABILITY_PROFILE, registration.capabilityProfile)

        val structured =
            assertNotNull(
                registration.capabilityProfile.capabilities[
                    UniversalAiCapabilityName.StructuredOutput
                ],
            )
        assertEquals(UniversalAiCapabilitySupport.Supported, structured.support)
        assertEquals(
            65_536L,
            structured.limits[UniversalAiCapabilityLimitName.MaxSchemaBytes],
        )
        assertEquals(
            10L,
            structured.limits[UniversalAiCapabilityLimitName.MaxSchemaDepth],
        )
        assertEquals(
            UniversalAiCapabilitySupport.Unsupported,
            assertNotNull(
                registration.capabilityProfile.capabilities[
                    UniversalAiCapabilityName.Streaming
                ],
            ).support,
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
                providerId = OPENAI_PROVIDER_ID,
                modelId = ModelId.of("undocumented-model"),
            )
        val modelCapabilities = assertNotNull(registry.capabilitiesOrNull(target))
        assertEquals(
            UniversalAiCapabilitySupport.Unknown,
            assertNotNull(modelCapabilities[UniversalAiCapabilityName.StructuredOutput]).support,
        )
        assertTrue(
            assertNotNull(
                modelCapabilities[UniversalAiCapabilityName.StructuredOutput],
            ).limits.isEmpty(),
        )
        assertEquals(
            UniversalAiCapabilitySupport.Unsupported,
            assertNotNull(modelCapabilities[UniversalAiCapabilityName.Streaming]).support,
        )
        assertEquals(
            registration.capabilityProfile,
            registry.capabilityProfileOrNull(OPENAI_PROVIDER_ID),
        )
        assertNull(registry.capabilityProfileOrNull(ProviderId.of("unknown")))
        assertTrue(credentialCalls.isEmpty())
    }

    private fun connector(
        engine: MockEngine,
        credentialSupplier: () -> String = { "p4c-test-credential" },
    ): UniversalAiConnector =
        UniversalAiConnector(
            configuration =
                UniversalAiConnectorConfiguration(
                    listOf(
                        UniversalAiProviderConfiguration(
                            providerId = OPENAI_PROVIDER_ID,
                            baseUrl = "https://api.example.invalid/v1",
                            credentialSupplier = credentialSupplier,
                        ),
                    ),
                ),
            httpEngine = engine,
        )

    private fun request(schema: StructuredOutputSchema): UniversalAiRequest =
        baseRequest(UniversalAiResponseFormat.jsonSchema(schema))

    private fun plainRequest(): UniversalAiRequest =
        baseRequest(UniversalAiResponseFormat.PlainText)

    private fun baseRequest(responseFormat: UniversalAiResponseFormat): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = OPENAI_PROVIDER_ID,
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
        extra: String = "",
    ): String =
        """
        {
          "id":"resp_structured",
          "object":"response",
          "status":"completed",
          "model":"resolved-model",
          "output":[{
            "id":"message_0",
            "type":"message",
            "status":"completed",
            "role":"assistant",
            "content":[{
              "type":"output_text",
              "text":${JsonPrimitive(outputText)}
            }]
          }],
          "usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5}
          $extra
        }
        """.trimIndent()

    private fun nestedObjectSchema(levels: Int): String {
        var child = """{"type":"string"}"""
        repeat(levels) { index ->
            val name = "level_$index"
            child =
                """
                {
                  "type":"object",
                  "properties":{"$name":$child},
                  "required":["$name"],
                  "additionalProperties":false
                }
                """.trimIndent()
        }
        return child
    }

    private fun nestedArraySchema(levels: Int): String {
        var child = """{"type":"string"}"""
        repeat(levels) {
            child = """{"type":"array","items":$child}"""
        }
        return rootObjectSchema(child)
    }

    private fun nestedAnyOfSchema(levels: Int): String {
        var child = """{"type":"string"}"""
        repeat(levels) {
            child = """{"anyOf":[$child,{"type":"null"}]}"""
        }
        return rootObjectSchema(child)
    }

    private fun rootObjectSchema(child: String): String =
        """
        {
          "type":"object",
          "properties":{"payload":$child},
          "required":["payload"],
          "additionalProperties":false
        }
        """.trimIndent()

    private fun repeatedReferenceDagSchema(
        levels: Int,
        branches: Int,
    ): String {
        val definitions = mutableListOf(""""leaf":{"type":"string"}""")
        var target = "leaf"
        repeat(levels) { index ->
            val name = "level_$index"
            val references =
                List(branches) {
                    """{"${'$'}ref":"#/${'$'}defs/$target"}"""
                }.joinToString(",")
            definitions += """"$name":{"anyOf":[$references]}"""
            target = name
        }
        return """
            {
              "type":"object",
              "properties":{
                "payload":{"${'$'}ref":"#/${'$'}defs/$target"}
              },
              "required":["payload"],
              "additionalProperties":false,
              "${'$'}defs":{${definitions.joinToString(",")}}
            }
            """.trimIndent()
    }
}

private fun OutgoingContent.p4cBodyBytes(): ByteArray =
    (this as OutgoingContent.ByteArrayContent).bytes()

private fun JsonObject.string(name: String): String =
    (this[name] as JsonPrimitive).content

private fun JsonObject.boolean(name: String): Boolean =
    (this[name] as JsonPrimitive).boolean

private val JSON = Json
