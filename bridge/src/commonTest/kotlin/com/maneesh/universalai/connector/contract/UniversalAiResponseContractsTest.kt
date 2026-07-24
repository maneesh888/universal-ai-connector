package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.extension.ExtensionNamespace
import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.contract.extension.Extensions
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UniversalAiResponseContractsTest {
    private val json = Json

    @Test
    fun minimalResponseRoundTripsWithRequiredWireMembersOnly() {
        val response =
            UniversalAiResponse(
                id = ResponseId.of("resp_minimal"),
                target = target(),
                outputs =
                    listOf(
                        UniversalAiOutput.text(
                            id = OutputId.of("out_0"),
                            index = 0,
                            text = "",
                        ),
                    ),
                completionReason = UniversalAiCompletionReason.Stop,
            )

        val encoded = json.parseToJsonElement(response.toJson()).jsonObject

        assertEquals(
            setOf(
                "contractVersion",
                "id",
                "target",
                "outputs",
                "completionReason",
            ),
            encoded.keys,
        )
        assertEquals("1", encoded.getValue("contractVersion").jsonPrimitive.content)
        assertEquals(
            setOf("id", "index", "kind", "text"),
            (encoded.getValue("outputs") as JsonArray).single().jsonObject.keys,
        )
        assertEquals(response, UniversalAiResponse.fromJson(response.toJson()))
    }

    @Test
    fun completeResponseAndErrorRoundTripWithStructuredValuesAndExtensions() {
        val outputExtensions = extensions("com.example.output", "source", "fixture")
        val responseExtensions = extensions("com.example.response", "region", "test")
        val errorExtensions = extensions("com.example.error", "origin", "deterministic")
        val structured =
            StructuredOutputValue.parse(
                """{"answer":"yes","confidence":1.0,"items":[true,null]}""",
            )
        val response =
            UniversalAiResponse(
                id = ResponseId.of("resp_complete"),
                requestId = RequestId.of("req_complete"),
                target = target(),
                outputs =
                    listOf(
                        UniversalAiOutput.text(
                            id = OutputId.of("out_text"),
                            index = 0,
                            text = "Hello",
                            extensions = outputExtensions,
                        ),
                        UniversalAiOutput.structuredJson(
                            id = OutputId.of("out_json"),
                            index = 1,
                            value = structured,
                        ),
                    ),
                usage =
                    UniversalAiUsage(
                        inputTokens = 7,
                        outputTokens = 5,
                        totalTokens = 12,
                        inputDetails = mapOf("cached" to 2, "uncached" to 5),
                        outputDetails = mapOf("reasoning" to 3, "visible" to 2),
                        extensions = extensions("com.example.usage", "estimated", "false"),
                    ),
                completionReason = UniversalAiCompletionReason.MaxOutputTokens,
                extensions = responseExtensions,
            )
        val error =
            UniversalAiError(
                category = UniversalAiErrorCategory.Provider,
                code = UniversalAiErrorCode.SimulatedFailure,
                message = "The deterministic provider failed.",
                metadata =
                    ExtensionValue.objectValue(
                        "field" to ExtensionValue.string("/input/0/content"),
                        "retryable" to ExtensionValue.boolean(false),
                    ),
                extensions = errorExtensions,
            )

        assertEquals(response, UniversalAiResponse.fromJson(response.toJson()))
        assertEquals(error, UniversalAiError.fromJson(error.toJson()))
        assertEquals(
            StructuredOutputValue.parse(
                """{"items":[true,null],"confidence":1,"answer":"yes"}""",
            ),
            structured,
        )
    }

    @Test
    fun futureRawValuesAndExtensionsSurviveWhileUnknownOrdinaryMembersAreIgnored() {
        val response =
            UniversalAiResponse.fromJson(
                """
                {
                  "contractVersion": "1",
                  "id": "resp_future",
                  "requestId": "req_future",
                  "target": {
                    "providerId": "future-provider",
                    "modelId": "future/model",
                    "futureTargetMember": true
                  },
                  "outputs": [
                    {
                      "id": "out_future",
                      "index": 0,
                      "kind": "audio_v2",
                      "futurePayload": {"ignored": true},
                      "extensions": {
                        "com.example.future": {
                          "encoding": "opaque"
                        }
                      }
                    }
                  ],
                  "usage": {
                    "inputTokens": 4,
                    "outputTokens": 2,
                    "totalTokens": 6,
                    "inputDetails": {
                      "future_cache": 1
                    },
                    "futureUsageMember": true
                  },
                  "completionReason": "future_reason",
                  "futureResponseMember": true,
                  "extensions": {
                    "com.example.response": {
                      "preserved": null
                    }
                  }
                }
                """.trimIndent(),
            )
        val error =
            UniversalAiError.fromJson(
                """
                {
                  "category": "future_category",
                  "code": "future_code",
                  "message": "A safe future failure.",
                  "futureErrorMember": true,
                  "extensions": {
                    "com.example.error": {
                      "preserved": true
                    }
                  }
                }
                """.trimIndent(),
            )

        assertEquals("audio_v2", response.outputs.single().kind.rawValue)
        assertFalse(response.outputs.single().kind.isKnown)
        assertNull(response.outputs.single().text)
        assertNull(response.outputs.single().structuredJson)
        assertEquals("future_reason", response.completionReason.rawValue)
        assertFalse(response.completionReason.isKnown)
        assertEquals(1L, response.usage?.inputDetails?.get("future_cache"))
        assertEquals("future_category", error.category.rawValue)
        assertFalse(error.category.isKnown)
        assertEquals("future_code", error.code.rawValue)
        assertFalse(error.code.isKnown)

        val reencodedResponse = json.parseToJsonElement(response.toJson()).jsonObject
        val reencodedOutput =
            (reencodedResponse.getValue("outputs") as JsonArray).single().jsonObject
        assertFalse("futureResponseMember" in reencodedResponse)
        assertFalse("futurePayload" in reencodedOutput)
        assertTrue("extensions" in reencodedOutput)
        assertTrue("extensions" in reencodedResponse)

        val reencodedError = json.parseToJsonElement(error.toJson()).jsonObject
        assertFalse("futureErrorMember" in reencodedError)
        assertTrue("extensions" in reencodedError)
    }

    @Test
    fun responseVersionMarkerIsRequiredNonNullAndRejectsFutureMajors() {
        val current = responseJson()

        assertFailsWith<SerializationException> {
            UniversalAiResponse.fromJson(
                current.replace("\"contractVersion\": \"1\",\n", ""),
            )
        }
        assertFailsWith<SerializationException> {
            UniversalAiResponse.fromJson(
                current.replace("\"contractVersion\": \"1\"", "\"contractVersion\": null"),
            )
        }
        assertSemanticFailure("unsupported_contract_version", "/contractVersion") {
            UniversalAiResponse.fromJson(
                current.replace("\"contractVersion\": \"1\"", "\"contractVersion\": \"2\""),
            )
        }
    }

    @Test
    fun optionalContractMembersRejectExplicitNullButStructuredJsonRetainsJsonNull() {
        val responsePrefix =
            """
            {
              "contractVersion": "1",
              "id": "resp_null",
              "target": {
                "providerId": "openai",
                "modelId": "gpt-4.1-mini"
              },
              "outputs": [
                {
                  "id": "out_0",
                  "index": 0,
                  "kind": "text",
                  "text": "Hello"
                }
              ],
              "completionReason": "stop"
            """.trimIndent()
        listOf("requestId", "usage", "extensions").forEach { member ->
            assertFailsWith<SerializationException>(member) {
                UniversalAiResponse.fromJson("$responsePrefix,\n\"$member\": null\n}")
            }
        }
        assertFailsWith<SerializationException> {
            UniversalAiResponse.fromJson(
                responsePrefix.replace(
                    "\"text\": \"Hello\"",
                    "\"text\": null",
                ) + "\n}",
            )
        }
        assertFailsWith<SerializationException> {
            UniversalAiResponse.fromJson(
                responsePrefix.replace(
                    "\"text\": \"Hello\"",
                    "\"text\": \"Hello\", \"extensions\": null",
                ) + "\n}",
            )
        }

        listOf("metadata", "extensions").forEach { member ->
            assertFailsWith<SerializationException>(member) {
                UniversalAiError.fromJson(
                    """
                    {
                      "category": "validation",
                      "code": "invalid_input",
                      "message": "Invalid input.",
                      "$member": null
                    }
                    """.trimIndent(),
                )
            }
        }

        val structuredNull =
            UniversalAiResponse.fromJson(
                responseJson(
                    output =
                        """{"id":"out_0","index":0,"kind":"structured_json","json":null}""",
                ),
            )
        assertEquals(
            StructuredOutputValue.parse("null"),
            structuredNull.outputs.single().structuredJson,
        )
    }

    @Test
    fun outputKindCouplingIsStrictAndFuturePayloadMustUseExtensions() {
        val missingText =
            responseJson(
                output = """{"id":"out_0","index":0,"kind":"text"}""",
            )
        val unexpectedJson =
            responseJson(
                output =
                    """{"id":"out_0","index":0,"kind":"text","text":"x","json":{"x":1}}""",
            )
        val missingJson =
            responseJson(
                output = """{"id":"out_0","index":0,"kind":"structured_json"}""",
            )
        val unexpectedText =
            responseJson(
                output =
                    """{"id":"out_0","index":0,"kind":"structured_json","json":{},"text":"x"}""",
            )
        val futureText =
            responseJson(
                output = """{"id":"out_0","index":0,"kind":"audio_v2","text":"x"}""",
            )

        assertSemanticFailure("missing_output_text", "/outputs/0/text") {
            UniversalAiResponse.fromJson(missingText)
        }
        assertSemanticFailure("unexpected_output_json", "/outputs/0/json") {
            UniversalAiResponse.fromJson(unexpectedJson)
        }
        assertSemanticFailure("missing_structured_output_json", "/outputs/0/json") {
            UniversalAiResponse.fromJson(missingJson)
        }
        assertSemanticFailure("unexpected_output_text", "/outputs/0/text") {
            UniversalAiResponse.fromJson(unexpectedText)
        }
        assertSemanticFailure("unexpected_output_text", "/outputs/0/text") {
            UniversalAiResponse.fromJson(futureText)
        }
        assertSemanticFailure("known_output_kind", "/kind") {
            UniversalAiOutput.future(
                id = OutputId.of("out_0"),
                index = 0,
                kind = UniversalAiOutputKind.Text,
            )
        }
    }

    @Test
    fun identifiersOutputsAndResponsesEnforceTheirInvariantsWithStablePaths() {
        assertEquals("a".repeat(256), ResponseId.of("a".repeat(256)).rawValue)
        assertEquals("😀".repeat(64), OutputId.of("😀".repeat(64)).rawValue)
        assertSemanticFailure("invalid_request_id", "/requestId") {
            RequestId.of("request id")
        }
        assertSemanticFailure("invalid_response_id", "/id") {
            ResponseId.of("a".repeat(257))
        }
        assertSemanticFailure("invalid_output_id", "/id") {
            OutputId.of(charArrayOf('\uD800').concatToString())
        }
        assertSemanticFailure("output_index_out_of_range", "/index") {
            UniversalAiOutput.text(OutputId.of("out"), -1, "x")
        }
        assertSemanticFailure("output_index_out_of_range", "/index") {
            UniversalAiOutput.text(OutputId.of("out"), 128, "x")
        }
        assertSemanticFailure("output_text_too_large", "/text") {
            UniversalAiOutput.text(
                OutputId.of("out"),
                0,
                "x".repeat(1_048_577),
            )
        }

        val first = UniversalAiOutput.text(OutputId.of("duplicate"), 0, "a")
        val duplicate = UniversalAiOutput.text(OutputId.of("duplicate"), 1, "b")
        assertSemanticFailure("duplicate_output_id", "/outputs/1/id") {
            response(listOf(first, duplicate))
        }
        val misplaced = UniversalAiOutput.text(OutputId.of("out_1"), 1, "b")
        assertSemanticFailure("output_index_mismatch", "/outputs/0/index") {
            response(listOf(misplaced))
        }
        assertSemanticFailure("response_output_limit_exceeded", "/outputs") {
            response(List(129) { first })
        }
        assertSemanticFailure("invalid_output_id", "/outputs/0/id") {
            UniversalAiResponse.fromJson(
                responseJson(
                    output =
                        """{"id":"bad id","index":0,"kind":"text","text":"x"}""",
                ),
            )
        }
    }

    @Test
    fun usageRequiresSafeConsistentCountsAndGovernedBreakdowns() {
        val maximum =
            UniversalAiUsage(
                inputTokens = MAX_JSON_SAFE_INTEGER,
                outputTokens = 0,
                totalTokens = MAX_JSON_SAFE_INTEGER,
            )
        assertEquals(MAX_JSON_SAFE_INTEGER, maximum.totalTokens)

        assertSemanticFailure("usage_token_count_out_of_range", "/inputTokens") {
            UniversalAiUsage(-1, 0, 0)
        }
        assertSemanticFailure("usage_total_mismatch", "/totalTokens") {
            UniversalAiUsage(1, 1, 3)
        }
        assertSemanticFailure("usage_details_exceed_aggregate", "/inputDetails") {
            UniversalAiUsage(
                inputTokens = 2,
                outputTokens = 0,
                totalTokens = 2,
                inputDetails = mapOf("cached" to 1, "uncached" to 2),
            )
        }
        assertSemanticFailure("invalid_usage_detail_key", "/inputDetails/Cached") {
            UniversalAiUsage(
                inputTokens = 1,
                outputTokens = 0,
                totalTokens = 1,
                inputDetails = mapOf("Cached" to 1),
            )
        }
        assertSemanticFailure("usage_detail_limit_exceeded", "/inputDetails") {
            UniversalAiUsage(
                inputTokens = 65,
                outputTokens = 0,
                totalTokens = 65,
                inputDetails =
                    (0 until 65).associate { index ->
                        "detail_$index" to 1L
                    },
            )
        }
        assertSemanticFailure("usage_token_count_out_of_range", "/usage/inputTokens") {
            UniversalAiResponse.fromJson(
                responseJson(
                    usage =
                        """
                        {
                          "inputTokens": 9007199254740992,
                          "outputTokens": 0,
                          "totalTokens": 9007199254740992
                        }
                        """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun mathematicallyEquivalentJsonNumbersDecodeWithoutBinaryCoercion() {
        val decoded =
            UniversalAiResponse.fromJson(
                responseJson(
                    output =
                        """{"id":"out_0","index":0e0,"kind":"structured_json","json":{"n":1.00}}""",
                    usage =
                        """
                        {
                          "inputTokens": 1.0,
                          "outputTokens": 2e0,
                          "totalTokens": 3.00,
                          "inputDetails": {
                            "cached": 1e0
                          }
                        }
                        """.trimIndent(),
                ),
            )

        assertEquals(0, decoded.outputs.single().index)
        assertEquals(1L, decoded.usage?.inputTokens)
        assertEquals(2L, decoded.usage?.outputTokens)
        assertEquals(3L, decoded.usage?.totalTokens)
        assertEquals(1L, decoded.usage?.inputDetails?.get("cached"))
        assertEquals(
            StructuredOutputValue.parse("""{"n":1}"""),
            decoded.outputs.single().structuredJson,
        )

        val reencoded = json.parseToJsonElement(decoded.toJson()).jsonObject
        val usage = reencoded.getValue("usage").jsonObject
        assertEquals("1", usage.getValue("inputTokens").jsonPrimitive.content)
        assertEquals("2", usage.getValue("outputTokens").jsonPrimitive.content)
        assertEquals("3", usage.getValue("totalTokens").jsonPrimitive.content)
    }

    @Test
    fun responseUsageAndStructuredValuesDefensivelyRetainCallerState() {
        val sourceDetails =
            linkedMapOf(
                "cached" to 1L,
                "uncached" to 2L,
            )
        val usage =
            UniversalAiUsage(
                inputTokens = 3,
                outputTokens = 0,
                totalTokens = 3,
                inputDetails = sourceDetails,
            )
        val sourceOutputs =
            mutableListOf(
                UniversalAiOutput.text(OutputId.of("out_0"), 0, "value"),
            )
        val response =
            UniversalAiResponse(
                id = ResponseId.of("resp_defensive"),
                target = target(),
                outputs = sourceOutputs,
                usage = usage,
                completionReason = UniversalAiCompletionReason.Stop,
            )

        sourceDetails.clear()
        sourceOutputs.clear()

        assertEquals(mapOf("cached" to 1L, "uncached" to 2L), usage.inputDetails)
        assertEquals(listOf("out_0"), response.outputs.map { it.id.rawValue })
        assertNotSame(usage.inputDetails, usage.inputDetails)
        assertNotSame(response.outputs, response.outputs)
    }

    @Test
    fun canonicalErrorsEnforceSafeMessagesAndRetainMetadata() {
        val error =
            UniversalAiError(
                category = UniversalAiErrorCategory.Validation,
                code = UniversalAiErrorCode.InvalidRequest,
                message = "Request validation failed.",
                metadata =
                    ExtensionValue.objectValue(
                        "path" to ExtensionValue.string("/target/modelId"),
                    ),
            )

        assertEquals(
            "/target/modelId",
            error.metadata?.string("path"),
        )
        assertEquals(error, UniversalAiError.fromJson(error.toJson()))
        assertSemanticFailure("invalid_error_message", "/message") {
            UniversalAiError(
                UniversalAiErrorCategory.Internal,
                UniversalAiErrorCode.ConnectorFailure,
                " \t ",
            )
        }
        assertSemanticFailure("invalid_error_message", "/message") {
            UniversalAiError(
                UniversalAiErrorCategory.Internal,
                UniversalAiErrorCode.ConnectorFailure,
                "Unsafe\nmessage",
            )
        }
        listOf(
            '\u0085',
            '\u2028',
            '\u2029',
            '\u061C',
            '\u200E',
            '\u200F',
            '\u202A',
            '\u202E',
            '\u2066',
            '\u2069',
        ).forEach { unsafeCharacter ->
            assertSemanticFailure("invalid_error_message", "/message") {
                UniversalAiError(
                    UniversalAiErrorCategory.Internal,
                    UniversalAiErrorCode.ConnectorFailure,
                    "Unsafe${unsafeCharacter}message",
                )
            }
        }
        assertSemanticFailure("invalid_error_message", "/message") {
            UniversalAiError(
                UniversalAiErrorCategory.Internal,
                UniversalAiErrorCode.ConnectorFailure,
                "x".repeat(4_097),
            )
        }
        assertSemanticFailure("invalid_error_category", "/category") {
            UniversalAiErrorCategory.of("Future")
        }
        assertSemanticFailure("invalid_error_code", "/code") {
            UniversalAiErrorCode.of("bad code")
        }
    }

    private fun target(): UniversalAiTarget =
        UniversalAiTarget(
            providerId = ProviderId.of("openai"),
            modelId = ModelId.of("gpt-4.1-mini"),
        )

    private fun response(outputs: List<UniversalAiOutput>): UniversalAiResponse =
        UniversalAiResponse(
            id = ResponseId.of("resp"),
            target = target(),
            outputs = outputs,
            completionReason = UniversalAiCompletionReason.Stop,
        )

    private fun extensions(
        namespace: String,
        name: String,
        value: String,
    ): Extensions =
        Extensions.of(
            ExtensionNamespace.of(namespace) to
                ExtensionValue.objectValue(
                    name to ExtensionValue.string(value),
                ),
        )

    private fun responseJson(
        output: String =
            """{"id":"out_0","index":0,"kind":"text","text":"Hello"}""",
        usage: String? = null,
    ): String {
        val usageMember =
            usage?.let { value ->
                ""","usage":$value"""
            }.orEmpty()
        return """
            {
              "contractVersion": "1",
              "id": "resp",
              "target": {
                "providerId": "openai",
                "modelId": "gpt-4.1-mini"
              },
              "outputs": [$output]
              $usageMember,
              "completionReason": "stop"
            }
        """.trimIndent()
    }

    private fun assertSemanticFailure(
        expectedCode: String,
        expectedPath: String,
        block: () -> Unit,
    ) {
        val failure =
            runCatching(block).exceptionOrNull()
                ?: fail("Expected semantic failure $expectedCode at $expectedPath.")
        val issue =
            failure.contractSemanticExceptionOrNull()
                ?: fail("Expected semantic failure but received ${failure::class.simpleName}.")
        assertEquals(expectedCode, issue.code)
        assertEquals(expectedPath, issue.path)
    }
}
