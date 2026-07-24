package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.extension.ExtensionNamespace
import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.contract.extension.Extensions
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UniversalAiRequestContractsTest {
    private val json = Json

    @Test
    fun identifiersValidateWithoutNormalizingTheirRawValues() {
        assertEquals("provider_name-v1", ProviderId.of("provider_name-v1").rawValue)
        assertEquals("a".repeat(64), ProviderId.of("a".repeat(64)).rawValue)
        assertFailsWith<IllegalArgumentException> { ProviderId.of("OpenAI") }
        assertFailsWith<IllegalArgumentException> { ProviderId.of("a".repeat(65)) }

        val model = "プロバイダー/Model:v1"
        assertEquals(model, ModelId.of(model).rawValue)
        assertFailsWith<IllegalArgumentException> { ModelId.of("") }
        assertFailsWith<IllegalArgumentException> { ModelId.of("model with spaces") }
        assertFailsWith<IllegalArgumentException> { ModelId.of("model\nname") }
        assertFailsWith<IllegalArgumentException> {
            ModelId.of(charArrayOf('\uD800').concatToString())
        }
    }

    @Test
    fun rolesAreRawBackedAndKnownValuesStayDistinguishable() {
        assertTrue(UniversalAiInputRole.System.isKnown)
        assertTrue(UniversalAiInputRole.Developer.isKnown)
        assertTrue(UniversalAiInputRole.User.isKnown)
        assertTrue(UniversalAiInputRole.Assistant.isKnown)

        val future = UniversalAiInputRole.of("critic_v2")
        assertFalse(future.isKnown)
        assertEquals("critic_v2", future.rawValue)
        assertFailsWith<IllegalArgumentException> {
            UniversalAiInputRole.of("Critic")
        }
    }

    @Test
    fun minimalRequestAlwaysEncodesVersionAndOmitsEmptyDefaults() {
        val request = minimalRequest(content = "  Hello\n")

        val encoded = json.parseToJsonElement(request.toJson()).jsonObject

        assertEquals(
            setOf("contractVersion", "target", "input"),
            encoded.keys,
        )
        assertEquals("1", encoded.getValue("contractVersion").jsonPrimitive.content)
        val content =
            encoded.getValue("input")
                .let { it as JsonArray }
                .single()
                .jsonObject
                .getValue("content")
                .jsonPrimitive
                .content
        assertEquals("  Hello\n", content)
        assertEquals(request, UniversalAiRequest.fromJson(request.toJson()))
    }

    @Test
    fun completeRequestRoundTripsOrderedInputSchemaGenerationAndExtensions() {
        val schema =
            StructuredOutputSchema.parse(
                """
                {
                  "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "properties": {
                    "answer": {
                      "type": "string"
                    }
                  },
                  "required": ["answer"],
                  "additionalProperties": false
                }
                """.trimIndent(),
            )
        val sourceInput =
            mutableListOf(
                UniversalAiTextInput(UniversalAiInputRole.System, "System"),
                UniversalAiTextInput(UniversalAiInputRole.Developer, "Developer"),
                UniversalAiTextInput(UniversalAiInputRole.User, "User"),
            )
        val sourceStops = mutableListOf("END", "\nSTOP")
        val extensions =
            Extensions.of(
                ExtensionNamespace.of("com.example.connector") to
                    ExtensionValue.objectValue(
                        "enabled" to ExtensionValue.boolean(true),
                        "nested" to
                            ExtensionValue.objectValue(
                                "value" to ExtensionValue.Null,
                            ),
                    ),
            )
        val request =
            UniversalAiRequest(
                target =
                    UniversalAiTarget(
                        providerId = ProviderId.of("openai"),
                        modelId = ModelId.of("gpt-4.1-mini"),
                    ),
                input = sourceInput,
                responseFormat = UniversalAiResponseFormat.jsonSchema(schema),
                generation =
                    UniversalAiGenerationParameters(
                        maxOutputTokens = 512,
                        temperature = 0.2,
                        topP = 0.95,
                        stopSequences = sourceStops,
                    ),
                extensions = extensions,
            )

        sourceInput.clear()
        sourceStops.clear()

        assertEquals(listOf("System", "Developer", "User"), request.input.map { it.content })
        assertEquals(listOf("END", "\nSTOP"), request.generation.stopSequences)
        assertEquals(request, UniversalAiRequest.fromJson(request.toJson()))
    }

    @Test
    fun futureValuesSurviveWhileUnknownOrdinaryMembersAreDropped() {
        val decoded =
            UniversalAiRequest.fromJson(
                """
                {
                  "contractVersion": "1",
                  "target": {
                    "providerId": "future-provider",
                    "modelId": "future/model:v2",
                    "futureTargetMember": true
                  },
                  "input": [
                    {
                      "role": "critic_v2",
                      "content": "Preserve me.",
                      "futureInputMember": 1
                    }
                  ],
                  "responseFormat": {
                    "kind": "future_binary",
                    "futureFormatMember": {
                      "encoding": "future"
                    }
                  },
                  "generation": {
                    "futureGenerationMember": true
                  },
                  "extensions": {
                    "com.example.future": {
                      "preserved": null
                    }
                  },
                  "futureRequestMember": true
                }
                """.trimIndent(),
            )

        assertEquals("critic_v2", decoded.input.single().role.rawValue)
        assertEquals("future_binary", decoded.responseFormat.kind.rawValue)
        assertFalse(decoded.responseFormat.isKnown)
        assertTrue(decoded.generation.isEmpty)

        val reencoded = json.parseToJsonElement(decoded.toJson()).jsonObject
        assertFalse("futureRequestMember" in reencoded)
        assertFalse(
            "futureTargetMember" in reencoded.getValue("target").jsonObject,
        )
        assertFalse(
            "futureInputMember" in
                (reencoded.getValue("input") as JsonArray).single().jsonObject,
        )
        assertFalse(
            "futureFormatMember" in reencoded.getValue("responseFormat").jsonObject,
        )
        assertFalse("generation" in reencoded)
        assertTrue("extensions" in reencoded)
    }

    @Test
    fun canonicalNullsAndInvalidVersionsFailInsteadOfDefaulting() {
        val base =
            """
            {
              "contractVersion": "1",
              "target": {
                "providerId": "openai",
                "modelId": "gpt-4.1-mini"
              },
              "input": [
                {
                  "role": "user",
                  "content": "Hello"
                }
              ]
            }
            """.trimIndent()
        listOf("responseFormat", "generation", "extensions").forEach { member ->
            val withNull = base.dropLast(2) + ",\n  \"$member\": null\n}"
            assertFailsWith<SerializationException>(member) {
                UniversalAiRequest.fromJson(withNull)
            }
        }

        val unsupportedVersion =
            assertFailsWith<SerializationException> {
                UniversalAiRequest.fromJson(base.replace("\"1\"", "\"2\""))
            }
        assertEquals(
            "unsupported_contract_version",
            unsupportedVersion.contractSemanticExceptionOrNull()?.code,
        )
        assertEquals(
            "/contractVersion",
            unsupportedVersion.contractSemanticExceptionOrNull()?.path,
        )
        assertFailsWith<SerializationException> {
            UniversalAiRequest.fromJson(base.replace("\"1\"", "null"))
        }
        assertFailsWith<SerializationException> {
            UniversalAiRequest.fromJson(base.replace("\"contractVersion\": \"1\",\n", ""))
        }
    }

    @Test
    fun duplicateJsonMembersAreRejectedBeforeMaterialization() {
        assertFailsWith<IllegalArgumentException> {
            UniversalAiRequest.fromJson(
                """
                {
                  "contractVersion": "1",
                  "contractVersion": "1",
                  "target": {
                    "providerId": "openai",
                    "modelId": "gpt-4.1-mini"
                  },
                  "input": [
                    {
                      "role": "user",
                      "content": "Hello"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun responseFormatEnforcesKnownCouplingAndPreservesFutureKinds() {
        val missingSchema =
            requestJson(
                responseFormat = """{"kind":"json_schema"}""",
            )
        val unexpectedSchema =
            requestJson(
                responseFormat = """{"kind":"plain_text","schema":{"type":"string"}}""",
            )
        val futureSchema =
            requestJson(
                responseFormat = """{"kind":"future_binary","schema":{"type":"string"}}""",
            )

        assertFailsWith<SerializationException> {
            UniversalAiRequest.fromJson(missingSchema)
        }
        assertFailsWith<SerializationException> {
            UniversalAiRequest.fromJson(unexpectedSchema)
        }
        val futureFailure =
            assertFailsWith<SerializationException> {
                UniversalAiRequest.fromJson(futureSchema)
            }
        assertEquals(
            "unexpected_response_schema",
            futureFailure.contractSemanticExceptionOrNull()?.code,
        )
        assertEquals(
            "/responseFormat/schema",
            futureFailure.contractSemanticExceptionOrNull()?.path,
        )

        val future =
            UniversalAiResponseFormat.future(
                UniversalAiResponseFormatKind.of("future_format"),
            )
        assertEquals("future_format", future.kind.rawValue)
        assertNull(future.schema)
    }

    @Test
    fun governedSchemasRejectUnsupportedExternalAndRecursiveFeatures() {
        listOf(
            """{"type":"string","pattern":"[a-z]+"}""",
            """{"${'$'}ref":"https://example.com/schema"}""",
            """
            {
              "${'$'}defs": {
                "node": {
                  "${'$'}ref": "#/${'$'}defs/node"
                }
              },
              "${'$'}ref": "#/${'$'}defs/node"
            }
            """.trimIndent(),
        ).forEach { rawSchema ->
            assertFailsWith<IllegalArgumentException>(rawSchema) {
                StructuredOutputSchema.parse(rawSchema)
            }
        }

        assertEquals("true", StructuredOutputSchema.parse("true").toJson())
    }

    @Test
    fun governedSchemasUseSemanticEnumAndIntegerRulesAndStrictLocalReferences() {
        StructuredOutputSchema.parse("""{"type":"string","minLength":1.0}""")
        StructuredOutputSchema.parse("""{"type":"array","minItems":1e0}""")

        listOf(
            """{"enum":[1,1.0]}""",
            """{"${'$'}defs":{"bad~2":true},"${'$'}ref":"#/${'$'}defs/bad~2"}""",
            """{"${'$'}defs":{"encoded":true},"${'$'}ref":"#/${'$'}defs/%65ncoded"}""",
        ).forEach { rawSchema ->
            assertFailsWith<ContractSemanticException>(rawSchema) {
                StructuredOutputSchema.parse(rawSchema)
            }
        }
    }

    @Test
    fun standaloneAndEmbeddedSchemasUseTheSameCompactSizePolicy() {
        val whitespace = " ".repeat(70_000)
        val rawSchema = "{$whitespace\"type\":\"string\"}"
        val standalone = StructuredOutputSchema.parse(rawSchema)
        val embedded =
            UniversalAiRequest.fromJson(
                requestJson(
                    responseFormat =
                        """
                        {
                          "kind": "json_schema",
                          "schema": $rawSchema
                        }
                        """.trimIndent(),
                ),
            )

        assertEquals(standalone, embedded.responseFormat.schema)
    }

    @Test
    fun governedSchemaEqualityUsesJsonValueSemantics() {
        val first =
            StructuredOutputSchema.parse(
                """
                {
                  "type": "number",
                  "const": 1.2300e+4
                }
                """.trimIndent(),
            )
        val second =
            StructuredOutputSchema.parse(
                """
                {
                  "const": 12300,
                  "type": "number"
                }
                """.trimIndent(),
            )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun generationParametersEnforceEveryBoundaryAndDefensiveStops() {
        assertTrue(
            UniversalAiGenerationParameters(
                maxOutputTokens = 1,
                temperature = 0.0,
                topP = 1.0,
            ).let { !it.isEmpty },
        )
        assertEquals(
            UniversalAiGenerationParameters(temperature = 0.0),
            UniversalAiGenerationParameters(temperature = -0.0),
        )
        UniversalAiGenerationParameters(maxOutputTokens = 1_048_576)
        assertFailsWith<IllegalArgumentException> {
            UniversalAiGenerationParameters(maxOutputTokens = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            UniversalAiGenerationParameters(maxOutputTokens = 1_048_577)
        }
        assertFailsWith<IllegalArgumentException> {
            UniversalAiGenerationParameters(temperature = -0.01)
        }
        assertFailsWith<IllegalArgumentException> {
            UniversalAiGenerationParameters(temperature = 1.01)
        }
        assertFailsWith<IllegalArgumentException> {
            UniversalAiGenerationParameters(temperature = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            UniversalAiGenerationParameters(topP = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            UniversalAiGenerationParameters(topP = 1.01)
        }
        assertFailsWith<IllegalArgumentException> {
            UniversalAiGenerationParameters(stopSequences = listOf(""))
        }
        assertFailsWith<IllegalArgumentException> {
            UniversalAiGenerationParameters(stopSequences = listOf("END", "END"))
        }
        assertFailsWith<IllegalArgumentException> {
            UniversalAiGenerationParameters(
                stopSequences = List(5) { "x".repeat(1_000) + it },
            )
        }
    }

    @Test
    fun generationNumberDecodingAcceptsMathematicalIntegersWithoutChangingNumbers() {
        val decoded =
            UniversalAiRequest.fromJson(
                requestJsonWithGeneration(
                    """
                    {
                      "maxOutputTokens": 1e0,
                      "temperature": 0.10,
                      "topP": 1e0
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(1, decoded.generation.maxOutputTokens)
        assertEquals(0.1, decoded.generation.temperature)
        assertEquals(1.0, decoded.generation.topP)
        assertEquals(
            decoded,
            UniversalAiRequest.fromJson(decoded.toJson()),
        )
    }

    @Test
    fun generationNumberDecodingRejectsUnderflowAndPrecisionLossWithStableLocations() {
        listOf(
            Triple("topP", "1e-400", "/generation/topP"),
            Triple(
                "temperature",
                "0.100000000000000005",
                "/generation/temperature",
            ),
        ).forEach { (name, value, expectedPath) ->
            val failure =
                assertFailsWith<SerializationException> {
                    UniversalAiRequest.fromJson(
                        requestJsonWithGeneration("""{"$name":$value}"""),
                    )
                }
            val issue = failure.contractSemanticExceptionOrNull()
            assertEquals("generation_number_not_round_trippable", issue?.code)
            assertEquals(expectedPath, issue?.path)
        }
    }

    @Test
    fun embeddedExtensionFailuresRetainTheFullRequestJsonPointer() {
        val oversizedNumber = "1".repeat(129)
        val failure =
            assertFailsWith<SerializationException> {
                UniversalAiRequest.fromJson(
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "extensions": {
                        "com.example.connector": {
                          "numberValue": $oversizedNumber
                        }
                      }
                    }
                    """.trimIndent(),
                )
            }

        val issue = failure.contractSemanticExceptionOrNull()
        assertEquals("extension_number_token_too_long", issue?.code)
        assertEquals(
            "/extensions/com.example.connector/numberValue",
            issue?.path,
        )
    }

    @Test
    fun inputLimitsPreserveExactContentWithoutRoleSequenceRules() {
        val byteBoundaryRequest =
            UniversalAiRequest(
                target = target(),
                input =
                    listOf(
                        UniversalAiTextInput(
                            UniversalAiInputRole.Assistant,
                            "a".repeat(262_144),
                        ),
                        UniversalAiTextInput(
                            UniversalAiInputRole.System,
                            "b".repeat(262_144),
                        ),
                    ),
            )
        assertTrue(byteBoundaryRequest.toJson().isNotEmpty())

        val escapeExpandedRequest =
            UniversalAiRequest(
                target = target(),
                input =
                    listOf(
                        UniversalAiTextInput(
                            UniversalAiInputRole.User,
                            "a" + "\n".repeat(262_143),
                        ),
                        UniversalAiTextInput(
                            UniversalAiInputRole.User,
                            "b" + "\n".repeat(262_143),
                        ),
                    ),
            )
        assertFailsWith<IllegalArgumentException> {
            escapeExpandedRequest.toJson()
        }

        assertFailsWith<IllegalArgumentException> {
            UniversalAiTextInput(
                UniversalAiInputRole.User,
                "a".repeat(262_145),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            UniversalAiTextInput(UniversalAiInputRole.User, " \n\t ")
        }
        assertFailsWith<IllegalArgumentException> {
            UniversalAiTextInput(
                UniversalAiInputRole.User,
                charArrayOf('\uD800').concatToString(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            UniversalAiRequest(
                target = target(),
                input =
                    listOf(
                        UniversalAiTextInput(
                            UniversalAiInputRole.User,
                            "a".repeat(262_144),
                        ),
                        UniversalAiTextInput(
                            UniversalAiInputRole.User,
                            "b".repeat(262_144),
                        ),
                        UniversalAiTextInput(
                            UniversalAiInputRole.User,
                            "c",
                        ),
                    ),
            )
        }
    }

    private fun minimalRequest(content: String = "Hello"): UniversalAiRequest =
        UniversalAiRequest(
            target = target(),
            input =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = content,
                    ),
                ),
        )

    private fun target(): UniversalAiTarget =
        UniversalAiTarget(
            providerId = ProviderId.of("openai"),
            modelId = ModelId.of("gpt-4.1-mini"),
        )

    private fun requestJson(responseFormat: String): String =
        """
        {
          "contractVersion": "1",
          "target": {
            "providerId": "openai",
            "modelId": "gpt-4.1-mini"
          },
          "input": [
            {
              "role": "user",
              "content": "Hello"
            }
          ],
          "responseFormat": $responseFormat
        }
        """.trimIndent()

    private fun requestJsonWithGeneration(generation: String): String =
        """
        {
          "contractVersion": "1",
          "target": {
            "providerId": "openai",
            "modelId": "gpt-4.1-mini"
          },
          "input": [
            {
              "role": "user",
              "content": "Hello"
            }
          ],
          "generation": $generation
        }
        """.trimIndent()
}
