package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.extension.ExtensionNamespace
import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.contract.extension.Extensions
import com.maneesh.universalai.connector.contract.json.CanonicalJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UniversalAiModelDescriptorContractsTest {
    private val json = Json

    @Test
    fun minimalDescriptorRequiresVersionTargetAndEffectiveCapabilities() {
        val descriptor =
            UniversalAiModelDescriptor(
                target = target(),
            )

        assertEquals(
            """
            {"contractVersion":"1","target":{"providerId":"example","modelId":"model-v1"},"capabilities":{}}
            """.trimIndent(),
            descriptor.toJson(),
        )
        assertEquals(
            descriptor,
            UniversalAiModelDescriptor.fromJson(descriptor.toJson()),
        )
    }

    @Test
    fun completeDescriptorRoundTripsDisplayLimitsCapabilitiesAndExtensions() {
        val descriptor =
            UniversalAiModelDescriptor(
                target = target(),
                displayName = "Example Model",
                limits =
                    UniversalAiModelTokenLimits(
                        contextWindowTokens = 131_072,
                        maxInputTokens = 120_000,
                        maxOutputTokens = 8_192,
                    ),
                capabilities =
                    UniversalAiCapabilitySet.of(
                        UniversalAiCapabilityName.Streaming to
                            UniversalAiCapabilityDeclaration(
                                support = UniversalAiCapabilitySupport.Supported,
                            ),
                        UniversalAiCapabilityName.StructuredOutput to
                            UniversalAiCapabilityDeclaration(
                                support = UniversalAiCapabilitySupport.Unknown,
                            ),
                    ),
                extensions =
                    extensions(
                        namespace = "com.example.model",
                        name = "family",
                        value = "example-v1",
                    ),
            )

        val decoded = UniversalAiModelDescriptor.fromJson(descriptor.toJson())

        assertEquals(descriptor, decoded)
        assertEquals(131_072L, decoded.limits?.contextWindowTokens)
        assertEquals(120_000L, decoded.limits?.maxInputTokens)
        assertEquals(8_192L, decoded.limits?.maxOutputTokens)
        assertEquals(
            "example-v1",
            decoded.extensions[
                ExtensionNamespace.of("com.example.model")
            ]?.string("family"),
        )
    }

    @Test
    fun futureOrdinaryMembersAreIgnoredWithoutDisplacingCanonicalFields() {
        val descriptor =
            UniversalAiModelDescriptor.fromJson(
                """
                {
                  "contractVersion": "1",
                  "target": {
                    "providerId": "example",
                    "modelId": "model-v1",
                    "futureTargetMember": true
                  },
                  "displayName": "Canonical name",
                  "limits": {
                    "contextWindowTokens": 4096,
                    "maxInputTokens": 3072,
                    "maxOutputTokens": 1024,
                    "futureTokenLimit": 999
                  },
                  "capabilities": {
                    "streaming": {
                      "support": "supported",
                      "futureCapabilityMember": true
                    }
                  },
                  "futureDescriptorMember": {
                    "displayName": "Cannot override"
                  },
                  "extensions": {
                    "com.example.future": {
                      "displayName": "Extension name",
                      "contextWindowTokens": 1
                    }
                  }
                }
                """.trimIndent(),
            )

        assertEquals("Canonical name", descriptor.displayName)
        assertEquals(4_096L, descriptor.limits?.contextWindowTokens)
        assertEquals(
            UniversalAiCapabilitySupport.Supported,
            descriptor.capabilities[UniversalAiCapabilityName.Streaming]?.support,
        )

        val reencoded = json.parseToJsonElement(descriptor.toJson()).jsonObject
        assertFalse("futureDescriptorMember" in reencoded)
        assertFalse(
            "futureTargetMember" in reencoded.getValue("target").jsonObject,
        )
        assertFalse(
            "futureTokenLimit" in reencoded.getValue("limits").jsonObject,
        )
        assertFalse(
            "futureCapabilityMember" in
                reencoded
                    .getValue("capabilities")
                    .jsonObject
                    .getValue("streaming")
                    .jsonObject,
        )
        assertEquals(
            "Canonical name",
            reencoded.getValue("displayName").jsonPrimitive.content,
        )
        assertTrue("extensions" in reencoded)
    }

    @Test
    fun futureOnlyModelLimitsDecodeToNoKnownLimitsAndAreOmittedOnEncode() {
        val descriptor =
            UniversalAiModelDescriptor.fromJson(
                """
                {
                  "contractVersion": "1",
                  "target": {
                    "providerId": "example",
                    "modelId": "model-v1"
                  },
                  "limits": {
                    "futureTokenLimit": 999
                  },
                  "capabilities": {}
                }
                """.trimIndent(),
            )

        assertNull(descriptor.limits)
        assertFalse(
            "limits" in json.parseToJsonElement(descriptor.toJson()).jsonObject,
        )
    }

    @Test
    fun emptyAndFutureOnlyStandaloneLimitsRemainClosedUnderTranscoding() {
        val empty = UniversalAiModelTokenLimits()
        assertEquals(
            "{}",
            CanonicalJson.encode(UniversalAiModelTokenLimits.serializer(), empty),
        )
        assertEquals(
            empty,
            CanonicalJson.decode(
                UniversalAiModelTokenLimits.serializer(),
                "{}",
            ),
        )

        val futureOnly =
            CanonicalJson.decode(
                UniversalAiModelTokenLimits.serializer(),
                """{"futureTokenLimit":999}""",
            )
        val futureReencoded =
            CanonicalJson.encode(
                UniversalAiModelTokenLimits.serializer(),
                futureOnly,
            )
        assertEquals("{}", futureReencoded)
        assertEquals(
            futureOnly,
            CanonicalJson.decode(
                UniversalAiModelTokenLimits.serializer(),
                futureReencoded,
            ),
        )
    }

    @Test
    fun mathematicallyIntegralModelLimitSpellingsDecodeAndReencodeAsIntegers() {
        val descriptor =
            UniversalAiModelDescriptor.fromJson(
                descriptorJson(
                    limits =
                        """
                        {
                          "contextWindowTokens": 4096.0,
                          "maxInputTokens": 3e3,
                          "maxOutputTokens": 1e3
                        }
                        """.trimIndent(),
                ),
            )

        assertEquals(4_096L, descriptor.limits?.contextWindowTokens)
        assertEquals(3_000L, descriptor.limits?.maxInputTokens)
        assertEquals(1_000L, descriptor.limits?.maxOutputTokens)
        val encodedLimits =
            json.parseToJsonElement(descriptor.toJson())
                .jsonObject
                .getValue("limits")
                .jsonObject
        assertEquals("4096", encodedLimits.getValue("contextWindowTokens").jsonPrimitive.content)
        assertEquals("3000", encodedLimits.getValue("maxInputTokens").jsonPrimitive.content)
        assertEquals("1000", encodedLimits.getValue("maxOutputTokens").jsonPrimitive.content)
    }

    @Test
    fun displayNamePreservesValidUnicodeAndRejectsUnsafeOrOversizedValues() {
        assertEquals(
            "モデル v1",
            UniversalAiModelDescriptor(
                target = target(),
                displayName = "モデル v1",
            ).displayName,
        )

        listOf(
            "",
            "   ",
            "line\nbreak",
            charArrayOf('\uD800').concatToString(),
        ).forEach { invalid ->
            assertSemanticFailure(
                expectedCode = "invalid_model_display_name",
                expectedPath = "/displayName",
            ) {
                UniversalAiModelDescriptor(
                    target = target(),
                    displayName = invalid,
                )
            }
        }
        assertSemanticFailure(
            expectedCode = "model_display_name_too_large",
            expectedPath = "/displayName",
        ) {
            UniversalAiModelDescriptor(
                target = target(),
                displayName = "a".repeat(257),
            )
        }
    }

    @Test
    fun modelTokenLimitsEnforceSafeBoundsAndContextRefinementsWithoutSumRule() {
        UniversalAiModelTokenLimits(
            contextWindowTokens = MAX_JSON_SAFE_INTEGER,
            maxInputTokens = MAX_JSON_SAFE_INTEGER,
            maxOutputTokens = MAX_OUTPUT_TOKENS.toLong(),
        )
        UniversalAiModelTokenLimits(
            contextWindowTokens = 100,
            maxInputTokens = 80,
            maxOutputTokens = 40,
        )
        UniversalAiModelTokenLimits()
    }

    @Test
    fun modelTokenLimitFailuresRetainSpecificPaths() {
        assertSemanticFailure(
            expectedCode = "model_token_limit_out_of_range",
            expectedPath = "/contextWindowTokens",
        ) {
            UniversalAiModelTokenLimits(contextWindowTokens = 0)
        }
        assertSemanticFailure(
            expectedCode = "model_token_limit_out_of_range",
            expectedPath = "/contextWindowTokens",
        ) {
            UniversalAiModelTokenLimits(
                contextWindowTokens = MAX_JSON_SAFE_INTEGER + 1,
            )
        }
        assertSemanticFailure(
            expectedCode = "model_token_limit_out_of_range",
            expectedPath = "/maxInputTokens",
        ) {
            UniversalAiModelTokenLimits(maxInputTokens = -1)
        }
        assertSemanticFailure(
            expectedCode = "model_token_limit_out_of_range",
            expectedPath = "/maxOutputTokens",
        ) {
            UniversalAiModelTokenLimits(
                maxOutputTokens = MAX_OUTPUT_TOKENS.toLong() + 1,
            )
        }
        assertSemanticFailure(
            expectedCode = "model_input_limit_exceeds_context",
            expectedPath = "/maxInputTokens",
        ) {
            UniversalAiModelTokenLimits(
                contextWindowTokens = 10,
                maxInputTokens = 11,
            )
        }
        assertSemanticFailure(
            expectedCode = "model_output_limit_exceeds_context",
            expectedPath = "/maxOutputTokens",
        ) {
            UniversalAiModelTokenLimits(
                contextWindowTokens = 10,
                maxOutputTokens = 11,
            )
        }
    }

    @Test
    fun descriptorJsonNormalizesEmptyLimitsAndRejectsNullsAndInvalidVersion() {
        val emptyLimits =
            UniversalAiModelDescriptor.fromJson(
                descriptorJson(limits = "{}"),
            )
        assertNull(emptyLimits.limits)
        assertFalse(
            "limits" in json.parseToJsonElement(emptyLimits.toJson()).jsonObject,
        )

        listOf("displayName", "limits", "extensions").forEach { member ->
            assertFailsWith<SerializationException>(member) {
                UniversalAiModelDescriptor.fromJson(
                    descriptorJson(extraMember = "\"$member\": null"),
                )
            }
        }
        assertFailsWith<SerializationException> {
            UniversalAiModelDescriptor.fromJson(
                """
                {
                  "target": {
                    "providerId": "example",
                    "modelId": "model-v1"
                  },
                  "capabilities": {}
                }
                """.trimIndent(),
            )
        }
        assertFailsWith<SerializationException> {
            UniversalAiModelDescriptor.fromJson(
                """
                {
                  "contractVersion": null,
                  "target": {
                    "providerId": "example",
                    "modelId": "model-v1"
                  },
                  "capabilities": {}
                }
                """.trimIndent(),
            )
        }
        assertFailsWith<SerializationException> {
            UniversalAiModelDescriptor.fromJson(
                """
                {
                  "contractVersion": "1",
                  "target": {
                    "providerId": "example",
                    "modelId": "model-v1"
                  }
                }
                """.trimIndent(),
            )
        }
        assertSemanticFailure(
            expectedCode = "unsupported_contract_version",
            expectedPath = "/contractVersion",
        ) {
            UniversalAiModelDescriptor.fromJson(
                descriptorJson(contractVersion = "2"),
            )
        }
    }

    @Test
    fun descriptorAndLimitsHaveValueEquality() {
        val first =
            UniversalAiModelDescriptor(
                target = target(),
                displayName = "Example",
                limits =
                    UniversalAiModelTokenLimits(
                        contextWindowTokens = 4096,
                        maxInputTokens = 3072,
                        maxOutputTokens = 1024,
                    ),
                capabilities =
                    UniversalAiCapabilitySet.of(
                        UniversalAiCapabilityName.Streaming to
                            UniversalAiCapabilityDeclaration(
                                support = UniversalAiCapabilitySupport.Supported,
                            ),
                    ),
            )
        val second = UniversalAiModelDescriptor.fromJson(first.toJson())

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first.limits, second.limits)
        assertEquals(first.limits.hashCode(), second.limits.hashCode())
    }

    private fun target(): UniversalAiTarget =
        UniversalAiTarget(
            providerId = ProviderId.of("example"),
            modelId = ModelId.of("model-v1"),
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

    private fun descriptorJson(
        contractVersion: String = "1",
        limits: String? = null,
        extraMember: String? = null,
    ): String {
        val optionalMembers =
            listOfNotNull(
                limits?.let { value -> "\"limits\": $value" },
                extraMember,
            ).joinToString(separator = ",\n")
        val optionalPrefix =
            optionalMembers
                .takeIf(String::isNotEmpty)
                ?.let { members -> "$members,\n" }
                .orEmpty()
        return """
            {
              "contractVersion": "$contractVersion",
              "target": {
                "providerId": "example",
                "modelId": "model-v1"
              },
              $optionalPrefix
              "capabilities": {}
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
