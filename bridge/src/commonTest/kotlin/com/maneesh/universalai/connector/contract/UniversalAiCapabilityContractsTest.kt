package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.extension.ExtensionNamespace
import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.contract.extension.Extensions
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

class UniversalAiCapabilityContractsTest {
    private val json = Json

    @Test
    fun rawBackedNamesAndSupportPreserveFutureValuesWithoutBooleanCollapse() {
        assertTrue(UniversalAiCapabilityName.Streaming.isKnown)
        assertTrue(UniversalAiCapabilityName.StructuredOutput.isKnown)
        assertTrue(UniversalAiCapabilityLimitName.MaxSchemaBytes.isKnown)
        assertTrue(UniversalAiCapabilityLimitName.MaxSchemaDepth.isKnown)

        assertEquals(
            UniversalAiCapabilitySupportState.SUPPORTED,
            UniversalAiCapabilitySupport.Supported.semanticState,
        )
        assertEquals(
            UniversalAiCapabilitySupportState.UNSUPPORTED,
            UniversalAiCapabilitySupport.Unsupported.semanticState,
        )
        assertEquals(
            UniversalAiCapabilitySupportState.UNKNOWN,
            UniversalAiCapabilitySupport.Unknown.semanticState,
        )

        val futureName = UniversalAiCapabilityName.of("future_reasoning")
        val futureSupport = UniversalAiCapabilitySupport.of("conditional")
        val futureLimit = UniversalAiCapabilityLimitName.of("max_steps")

        assertFalse(futureName.isKnown)
        assertFalse(futureSupport.isKnown)
        assertFalse(futureLimit.isKnown)
        assertEquals("future_reasoning", futureName.rawValue)
        assertEquals("conditional", futureSupport.rawValue)
        assertEquals(
            UniversalAiCapabilitySupportState.UNKNOWN,
            futureSupport.semanticState,
        )
        assertEquals("max_steps", futureLimit.rawValue)

        listOf("", "Streaming", "bad/name", "a".repeat(65)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) {
                UniversalAiCapabilityName.of(invalid)
            }
            assertFailsWith<IllegalArgumentException>(invalid) {
                UniversalAiCapabilitySupport.of(invalid)
            }
            assertFailsWith<IllegalArgumentException>(invalid) {
                UniversalAiCapabilityLimitName.of(invalid)
            }
        }
    }

    @Test
    fun minimalProviderProfileUsesRequiredVersionAndCapabilityObject() {
        val profile =
            UniversalAiProviderCapabilityProfile(
                providerId = ProviderId.of("example"),
            )

        assertEquals(
            """{"contractVersion":"1","providerId":"example","capabilities":{}}""",
            profile.toJson(),
        )
        assertEquals(
            profile,
            UniversalAiProviderCapabilityProfile.fromJson(profile.toJson()),
        )
    }

    @Test
    fun futureCapabilitySupportAndLimitsRoundTripWhileOrdinaryMembersAreIgnored() {
        val profile =
            UniversalAiProviderCapabilityProfile.fromJson(
                """
                {
                  "contractVersion": "1",
                  "providerId": "example",
                  "capabilities": {
                    "future_reasoning": {
                      "support": "conditional",
                      "limits": {
                        "max_steps": 64.0
                      },
                      "futureCapabilityMember": true,
                      "extensions": {
                        "com.example.capability": {
                          "mode": "future"
                        }
                      }
                    }
                  },
                  "futureProfileMember": true
                }
                """.trimIndent(),
            )
        val name = UniversalAiCapabilityName.of("future_reasoning")
        val declaration = checkNotNull(profile.capabilities[name])

        assertEquals("conditional", declaration.support.rawValue)
        assertEquals(
            UniversalAiCapabilitySupportState.UNKNOWN,
            declaration.support.semanticState,
        )
        assertEquals(
            64L,
            declaration.limits[UniversalAiCapabilityLimitName.of("max_steps")],
        )
        assertEquals(
            "future",
            declaration.extensions[
                ExtensionNamespace.of("com.example.capability")
            ]?.string("mode"),
        )

        val reencoded = json.parseToJsonElement(profile.toJson()).jsonObject
        assertFalse("futureProfileMember" in reencoded)
        val reencodedDeclaration =
            reencoded
                .getValue("capabilities")
                .jsonObject
                .getValue("future_reasoning")
                .jsonObject
        assertFalse("futureCapabilityMember" in reencodedDeclaration)
        assertEquals(
            "conditional",
            reencodedDeclaration.getValue("support").jsonPrimitive.content,
        )
        assertEquals(
            "64",
            reencodedDeclaration
                .getValue("limits")
                .jsonObject
                .getValue("max_steps")
                .jsonPrimitive
                .content,
        )
        assertTrue("extensions" in reencodedDeclaration)
    }

    @Test
    fun absenceAndExplicitUnknownRemainDistinguishable() {
        val capabilities =
            UniversalAiCapabilitySet.of(
                UniversalAiCapabilityName.Streaming to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Unknown,
                    ),
            )
        val absent = UniversalAiCapabilityName.StructuredOutput

        assertNull(capabilities[absent])
        assertEquals(
            UniversalAiCapabilitySupportState.UNKNOWN,
            capabilities.supportState(absent),
        )
        assertEquals(
            UniversalAiCapabilitySupport.Unknown,
            capabilities[UniversalAiCapabilityName.Streaming]?.support,
        )
        assertEquals(
            UniversalAiCapabilitySupportState.UNKNOWN,
            capabilities.supportState(UniversalAiCapabilityName.Streaming),
        )
    }

    @Test
    fun modelResolutionUsesWholeEntryReplacementAndChecksProviderIdentity() {
        val providerDeclaration =
            UniversalAiCapabilityDeclaration(
                support = UniversalAiCapabilitySupport.Supported,
                limits =
                    mapOf(
                        UniversalAiCapabilityLimitName.MaxSchemaBytes to 65_536L,
                    ),
                extensions =
                    extensions(
                        namespace = "com.example.provider",
                        name = "source",
                        value = "provider",
                    ),
            )
        val providerProfile =
            UniversalAiProviderCapabilityProfile(
                providerId = ProviderId.of("example"),
                capabilities =
                    UniversalAiCapabilitySet.of(
                        UniversalAiCapabilityName.StructuredOutput to providerDeclaration,
                        UniversalAiCapabilityName.Streaming to
                            UniversalAiCapabilityDeclaration(
                                support = UniversalAiCapabilitySupport.Unsupported,
                            ),
                        UniversalAiCapabilityName.of("provider_default") to
                            UniversalAiCapabilityDeclaration(
                                support = UniversalAiCapabilitySupport.Supported,
                            ),
                        UniversalAiCapabilityName.of("batching") to
                            UniversalAiCapabilityDeclaration(
                                support = UniversalAiCapabilitySupport.Supported,
                            ),
                    ),
                extensions =
                    extensions(
                        namespace = "com.example.profile",
                        name = "region",
                        value = "test",
                    ),
            )
        val modelDeclaration =
            UniversalAiCapabilityDeclaration(
                support = UniversalAiCapabilitySupport.Unknown,
                extensions =
                    extensions(
                        namespace = "com.example.model",
                        name = "source",
                        value = "model",
                    ),
            )
        val modelOverrides =
            UniversalAiCapabilitySet.of(
                UniversalAiCapabilityName.StructuredOutput to modelDeclaration,
                UniversalAiCapabilityName.Streaming to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Supported,
                    ),
                UniversalAiCapabilityName.of("batching") to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Unsupported,
                    ),
            )
        val target = target(providerId = "example")

        val inheritedOnly =
            UniversalAiCapabilitySet.resolve(
                providerProfile = providerProfile,
                modelTarget = target,
            )
        val resolved =
            UniversalAiCapabilitySet.resolve(
                providerProfile = providerProfile,
                modelTarget = target,
                modelOverrides = modelOverrides,
            )

        assertEquals(providerProfile.capabilities, inheritedOnly)
        assertEquals(modelDeclaration, resolved[UniversalAiCapabilityName.StructuredOutput])
        assertTrue(
            checkNotNull(resolved[UniversalAiCapabilityName.StructuredOutput])
                .limits
                .isEmpty(),
        )
        assertNull(
            resolved[UniversalAiCapabilityName.StructuredOutput]
                ?.extensions
                ?.get(ExtensionNamespace.of("com.example.provider")),
        )
        assertEquals(
            UniversalAiCapabilitySupport.Supported,
            resolved[UniversalAiCapabilityName.Streaming]?.support,
        )
        assertEquals(
            UniversalAiCapabilitySupport.Supported,
            resolved[UniversalAiCapabilityName.of("provider_default")]?.support,
        )
        assertEquals(
            UniversalAiCapabilitySupport.Unsupported,
            resolved[UniversalAiCapabilityName.of("batching")]?.support,
        )

        val descriptor =
            UniversalAiModelDescriptor(
                target = target,
                capabilities = resolved,
            )
        assertTrue(descriptor.extensions.isEmpty)

        assertSemanticFailure(
            expectedCode = "capability_provider_mismatch",
            expectedPath = "/target/providerId",
        ) {
            UniversalAiCapabilitySet.resolve(
                providerProfile = providerProfile,
                modelTarget = target(providerId = "other"),
                modelOverrides = modelOverrides,
            )
        }
    }

    @Test
    fun capabilityLimitsEnforceBoundsCountsAndKnownUnsupportedCoupling() {
        val futureLimit = UniversalAiCapabilityLimitName.of("future_limit")
        UniversalAiCapabilityDeclaration(
            support = UniversalAiCapabilitySupport.Supported,
            limits = mapOf(futureLimit to MAX_JSON_SAFE_INTEGER),
        )
        UniversalAiCapabilityDeclaration(
            support = UniversalAiCapabilitySupport.Unknown,
            limits = mapOf(futureLimit to 0L),
        )
        UniversalAiCapabilityDeclaration(
            support = UniversalAiCapabilitySupport.of("conditional"),
            limits = mapOf(futureLimit to 1L),
        )
        UniversalAiCapabilitySet.of(
            UniversalAiCapabilityName.Streaming to
                UniversalAiCapabilityDeclaration(
                    support = UniversalAiCapabilitySupport.Supported,
                    limits = mapOf(futureLimit to 1L),
                ),
        )

        assertSemanticFailure(
            expectedCode = "unsupported_capability_limits",
            expectedPath = "/limits",
        ) {
            UniversalAiCapabilityDeclaration(
                support = UniversalAiCapabilitySupport.Unsupported,
                limits = mapOf(futureLimit to 1L),
            )
        }
        assertSemanticFailure(
            expectedCode = "capability_limit_out_of_range",
            expectedPath = "/limits/max_schema_depth",
        ) {
            UniversalAiCapabilityDeclaration(
                support = UniversalAiCapabilitySupport.Supported,
                limits = mapOf(UniversalAiCapabilityLimitName.MaxSchemaDepth to 0L),
            )
        }
        assertSemanticFailure(
            expectedCode = "capability_limit_out_of_range",
            expectedPath = "/limits/future_limit",
        ) {
            UniversalAiCapabilityDeclaration(
                support = UniversalAiCapabilitySupport.Supported,
                limits = mapOf(futureLimit to -1L),
            )
        }
        assertSemanticFailure(
            expectedCode = "capability_limit_count_exceeded",
            expectedPath = "/limits",
        ) {
            UniversalAiCapabilityDeclaration(
                support = UniversalAiCapabilitySupport.Supported,
                limits =
                    (0..64).associate { index ->
                        UniversalAiCapabilityLimitName.of("limit_$index") to 1L
                    },
            )
        }
        assertSemanticFailure(
            expectedCode = "capability_count_exceeded",
            expectedPath = "",
        ) {
            UniversalAiCapabilitySet.of(
                (0..64).associate { index ->
                    UniversalAiCapabilityName.of("capability_$index") to
                        UniversalAiCapabilityDeclaration(
                            support = UniversalAiCapabilitySupport.Unknown,
                        )
                },
            )
        }
        assertSemanticFailure(
            expectedCode = "capability_limit_not_applicable",
            expectedPath = "/streaming/limits/max_schema_bytes",
        ) {
            UniversalAiCapabilitySet.of(
                UniversalAiCapabilityName.Streaming to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Supported,
                        limits =
                            mapOf(
                                UniversalAiCapabilityLimitName.MaxSchemaBytes to 1_024L,
                            ),
                    ),
            )
        }
        assertSemanticFailure(
            expectedCode = "duplicate_capability",
            expectedPath = "",
        ) {
            val declaration =
                UniversalAiCapabilityDeclaration(
                    support = UniversalAiCapabilitySupport.Unknown,
                )
            UniversalAiCapabilitySet.of(
                UniversalAiCapabilityName.Streaming to declaration,
                UniversalAiCapabilityName.Streaming to declaration,
            )
        }
    }

    @Test
    fun invalidCapabilityJsonRetainsStableSemanticCodesAndPaths() {
        assertSemanticFailure(
            expectedCode = "invalid_capability_name",
            expectedPath = "/capabilities/Streaming",
        ) {
            UniversalAiProviderCapabilityProfile.fromJson(
                profileJson(
                    capabilities = """{"Streaming":{"support":"supported"}}""",
                ),
            )
        }
        assertSemanticFailure(
            expectedCode = "invalid_capability_support",
            expectedPath = "/capabilities/streaming/support",
        ) {
            UniversalAiProviderCapabilityProfile.fromJson(
                profileJson(
                    capabilities = """{"streaming":{"support":"Supported"}}""",
                ),
            )
        }
        assertSemanticFailure(
            expectedCode = "capability_limit_out_of_range",
            expectedPath = "/capabilities/structured_output/limits/max_schema_bytes",
        ) {
            UniversalAiProviderCapabilityProfile.fromJson(
                profileJson(
                    capabilities =
                        """
                        {
                          "structured_output": {
                            "support": "supported",
                            "limits": {
                              "max_schema_bytes": 0
                            }
                          }
                        }
                        """.trimIndent(),
                ),
            )
        }
        assertSemanticFailure(
            expectedCode = "unsupported_capability_limits",
            expectedPath = "/capabilities/streaming/limits",
        ) {
            UniversalAiProviderCapabilityProfile.fromJson(
                profileJson(
                    capabilities =
                        """
                        {
                          "streaming": {
                            "support": "unsupported",
                            "limits": {
                              "future_limit": 1
                            }
                          }
                        }
                        """.trimIndent(),
                ),
            )
        }
        assertSemanticFailure(
            expectedCode = "capability_limit_not_applicable",
            expectedPath = "/capabilities/streaming/limits/max_schema_depth",
        ) {
            UniversalAiProviderCapabilityProfile.fromJson(
                profileJson(
                    capabilities =
                        """
                        {
                          "streaming": {
                            "support": "supported",
                            "limits": {
                              "max_schema_depth": 16
                            }
                          }
                        }
                        """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun requiredMembersAndExplicitNullsFailThroughTheStrictCanonicalCodec() {
        assertFailsWith<SerializationException> {
            UniversalAiProviderCapabilityProfile.fromJson(
                """{"providerId":"example","capabilities":{}}""",
            )
        }
        assertFailsWith<SerializationException> {
            UniversalAiProviderCapabilityProfile.fromJson(
                """{"contractVersion":null,"providerId":"example","capabilities":{}}""",
            )
        }
        assertFailsWith<SerializationException> {
            UniversalAiProviderCapabilityProfile.fromJson(
                """{"contractVersion":"1","providerId":"example"}""",
            )
        }
        assertFailsWith<SerializationException> {
            UniversalAiProviderCapabilityProfile.fromJson(
                """{"contractVersion":"1","providerId":"example","capabilities":null}""",
            )
        }
        assertFailsWith<SerializationException> {
            UniversalAiProviderCapabilityProfile.fromJson(
                profileJson(
                    capabilities = """{"streaming":{"support":null}}""",
                ),
            )
        }
        assertFailsWith<SerializationException> {
            UniversalAiProviderCapabilityProfile.fromJson(
                profileJson(
                    capabilities =
                        """{"streaming":{"support":"supported","limits":null}}""",
                ),
            )
        }
        assertFailsWith<SerializationException> {
            UniversalAiProviderCapabilityProfile.fromJson(
                profileJson(
                    capabilities =
                        """{"streaming":{"support":"supported","extensions":null}}""",
                ),
            )
        }
        assertSemanticFailure(
            expectedCode = "unsupported_contract_version",
            expectedPath = "/contractVersion",
        ) {
            UniversalAiProviderCapabilityProfile.fromJson(
                profileJson(
                    contractVersion = "2",
                    capabilities = "{}",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            UniversalAiProviderCapabilityProfile.fromJson(
                """
                {
                  "contractVersion": "1",
                  "providerId": "example",
                  "capabilities": {},
                  "capabilities": {}
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun collectionInputsAndReturnedMapsAreDefensiveSnapshots() {
        val mutableLimits =
            mutableMapOf(
                UniversalAiCapabilityLimitName.MaxSchemaBytes to 1_024L,
            )
        val declaration =
            UniversalAiCapabilityDeclaration(
                support = UniversalAiCapabilitySupport.Supported,
                limits = mutableLimits,
            )
        mutableLimits.clear()
        assertEquals(1, declaration.limits.size)

        val mutableDeclarations =
            mutableMapOf(
                UniversalAiCapabilityName.StructuredOutput to declaration,
            )
        val capabilities = UniversalAiCapabilitySet.of(mutableDeclarations)
        mutableDeclarations.clear()
        assertEquals(1, capabilities.size)

        val returnedLimits = declaration.limits.toMutableMap()
        returnedLimits.clear()
        val returnedDeclarations = capabilities.declarations.toMutableMap()
        returnedDeclarations.clear()
        assertEquals(1, declaration.limits.size)
        assertEquals(1, capabilities.size)

        val equivalent =
            UniversalAiCapabilitySet.of(
                UniversalAiCapabilityName.StructuredOutput to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Supported,
                        limits =
                            mapOf(
                                UniversalAiCapabilityLimitName.MaxSchemaBytes to 1_024L,
                            ),
                    ),
            )
        assertEquals(capabilities, equivalent)
        assertEquals(capabilities.hashCode(), equivalent.hashCode())
    }

    private fun target(providerId: String): UniversalAiTarget =
        UniversalAiTarget(
            providerId = ProviderId.of(providerId),
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

    private fun profileJson(
        capabilities: String,
        contractVersion: String = "1",
    ): String =
        """
        {
          "contractVersion": "$contractVersion",
          "providerId": "example",
          "capabilities": $capabilities
        }
        """.trimIndent()

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
