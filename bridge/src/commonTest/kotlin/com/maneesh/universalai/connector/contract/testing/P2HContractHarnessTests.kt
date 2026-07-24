package com.maneesh.universalai.connector.contract.testing

import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiCapabilityDeclaration
import com.maneesh.universalai.connector.contract.UniversalAiCapabilityName
import com.maneesh.universalai.connector.contract.UniversalAiCapabilitySet
import com.maneesh.universalai.connector.contract.UniversalAiCapabilitySupport
import com.maneesh.universalai.connector.contract.UniversalAiCapabilitySupportState
import com.maneesh.universalai.connector.contract.UniversalAiModelDescriptor
import com.maneesh.universalai.connector.contract.UniversalAiProviderCapabilityProfile
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.contractSemanticExceptionOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class P2HContractHarnessTests {
    private val json = Json

    @Test
    fun schemaValidP2HFixturesExerciseProductionContracts() {
        ContractSeedFixtures.all
            .filter { fixture ->
                fixture.family.isP2HFamily &&
                    fixture.expectedLayer != ContractSeedLayer.SCHEMA
            }.forEach { fixture ->
                if (fixture.isValid) {
                    val encoded = P2HContractFixtureValidator.productionRoundTrip(fixture)
                    assertEquals(
                        encoded,
                        P2HContractFixtureValidator.productionRoundTrip(
                            fixture.copy(json = encoded),
                        ),
                        fixture.id,
                    )
                } else {
                    val failure =
                        assertFails(fixture.id) {
                            P2HContractFixtureValidator.productionRoundTrip(fixture)
                        }
                    assertDocumentedSemanticFailure(fixture, failure)
                }
            }
    }

    @Test
    fun capabilityStatesKeepAbsentAndExplicitUnknownDistinct() {
        val fixture = fixture("v1-capability-set-complete")
        val capabilities =
            com.maneesh.universalai.connector.contract.json.CanonicalJson.decode(
                UniversalAiCapabilitySet.serializer(),
                fixture.json,
            )

        assertEquals(
            UniversalAiCapabilitySupportState.SUPPORTED,
            capabilities.supportState(UniversalAiCapabilityName.Streaming),
        )
        assertEquals(
            UniversalAiCapabilitySupportState.UNSUPPORTED,
            capabilities.supportState(UniversalAiCapabilityName.of("legacy_text")),
        )
        assertEquals(
            UniversalAiCapabilitySupport.Unknown,
            capabilities[UniversalAiCapabilityName.of("embeddings")]?.support,
        )
        assertNull(capabilities[UniversalAiCapabilityName.of("absent_capability")])
        assertEquals(
            UniversalAiCapabilitySupportState.UNKNOWN,
            capabilities.supportState(UniversalAiCapabilityName.of("absent_capability")),
        )
    }

    @Test
    fun compatibilityFixturesPreserveGovernedValuesAndDropOrdinaryMembers() {
        val emptyLimits = roundTripObject("v1-model-token-limits-empty")
        val futureOnlyStandaloneLimits =
            roundTripObject("v1-model-token-limits-future-only")
        val capabilitySet = roundTripObject("v1-capability-set-future-values")
        val futureDeclaration = capabilitySet.getValue("future_audio").asObject()
        assertEquals(
            "conditionally_supported",
            futureDeclaration.getValue("support").asPrimitive().content,
        )
        assertEquals(
            "4096",
            futureDeclaration
                .getValue("limits")
                .asObject()
                .getValue("max_frame_bytes")
                .asPrimitive()
                .content,
        )
        assertTrue("extensions" in futureDeclaration)
        assertFalse("futureDeclarationMember" in futureDeclaration)

        val profile = roundTripObject("v1-provider-capability-profile-future-additive")
        val profileCapability =
            profile
                .getValue("capabilities")
                .asObject()
                .getValue("future_audio")
                .asObject()
        assertEquals("preview", profileCapability.getValue("support").asPrimitive().content)
        assertTrue("extensions" in profile)
        assertFalse("futureProfileMember" in profile)

        val descriptor = roundTripObject("v1-model-descriptor-future-additive")
        val descriptorTarget = descriptor.getValue("target").asObject()
        val descriptorLimits = descriptor.getValue("limits").asObject()
        val descriptorCapability =
            descriptor
                .getValue("capabilities")
                .asObject()
                .getValue("future_audio")
                .asObject()
        assertFalse("futureDescriptorMember" in descriptor)
        assertFalse("futureTargetMember" in descriptorTarget)
        assertFalse("futureLimit" in descriptorLimits)
        assertEquals("preview", descriptorCapability.getValue("support").asPrimitive().content)
        assertTrue("extensions" in descriptor)

        val futureOnlyDescriptorLimits =
            roundTripObject("v1-model-descriptor-future-only-limits")
        val emptyDescriptorLimits =
            roundTripObject("v1-model-descriptor-empty-limits")
        assertTrue(emptyLimits.isEmpty())
        assertTrue(futureOnlyStandaloneLimits.isEmpty())
        assertFalse("limits" in futureOnlyDescriptorLimits)
        assertFalse("limits" in emptyDescriptorLimits)
    }

    @Test
    fun resolverUsesProviderDefaultsAndWholeEntryModelReplacement() {
        val profile =
            UniversalAiProviderCapabilityProfile.fromJson(
                fixture("v1-provider-capability-profile-complete").json,
            )
        val modelOnlyName = UniversalAiCapabilityName.of("future_model_only")
        val overrides =
            UniversalAiCapabilitySet.of(
                UniversalAiCapabilityName.Streaming to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Unknown,
                    ),
                modelOnlyName to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Supported,
                    ),
            )
        val target =
            UniversalAiTarget(
                providerId = ProviderId.of("example"),
                modelId = ModelId.of("model-v1"),
            )

        val resolved = UniversalAiCapabilitySet.resolve(profile, target, overrides)

        val streaming = assertNotNull(resolved[UniversalAiCapabilityName.Streaming])
        assertEquals(UniversalAiCapabilitySupport.Unknown, streaming.support)
        assertTrue(streaming.limits.isEmpty())
        assertEquals(
            UniversalAiCapabilitySupport.Unsupported,
            resolved[UniversalAiCapabilityName.StructuredOutput]?.support,
        )
        assertEquals(
            UniversalAiCapabilitySupport.Supported,
            resolved[modelOnlyName]?.support,
        )

        val descriptor =
            UniversalAiModelDescriptor(
                target = target,
                capabilities = resolved,
            )
        assertTrue(descriptor.extensions.isEmpty)
        assertFalse(profile.extensions.isEmpty)
    }

    @Test
    fun resolverRejectsProviderMismatchAtTheStablePointer() {
        val profile =
            UniversalAiProviderCapabilityProfile.fromJson(
                fixture("v1-provider-capability-profile-minimal").json,
            )
        val mismatchedTarget =
            UniversalAiTarget(
                providerId = ProviderId.of("other"),
                modelId = ModelId.of("model-v1"),
            )

        val failure =
            assertFails {
                UniversalAiCapabilitySet.resolve(profile, mismatchedTarget)
            }
        val issue = assertNotNull(failure.contractSemanticExceptionOrNull())
        assertEquals("capability_provider_mismatch", issue.code)
        assertEquals("/target/providerId", issue.path)
    }

    @Test
    fun minimalDocumentsOmitOptionalFieldsButKeepRequiredEmptyCapabilities() {
        val profile = roundTripObject("v1-provider-capability-profile-minimal")
        val descriptor = roundTripObject("v1-model-descriptor-minimal")

        assertTrue(profile.getValue("capabilities").asObject().isEmpty())
        assertFalse("extensions" in profile)
        assertTrue(descriptor.getValue("capabilities").asObject().isEmpty())
        assertFalse("displayName" in descriptor)
        assertFalse("limits" in descriptor)
        assertFalse("extensions" in descriptor)
    }

    private fun fixture(id: String): ContractSeedFixture =
        ContractSeedFixtures.all.single { fixture -> fixture.id == id }

    private fun roundTripObject(id: String): JsonObject =
        json.parseToJsonElement(
            P2HContractFixtureValidator.productionRoundTrip(fixture(id)),
        ).asObject()

    private fun JsonObject.asObject(): JsonObject = this

    private fun kotlinx.serialization.json.JsonElement.asObject(): JsonObject = this as JsonObject

    private fun kotlinx.serialization.json.JsonElement.asPrimitive(): JsonPrimitive =
        this as JsonPrimitive

    private fun assertDocumentedSemanticFailure(
        fixture: ContractSeedFixture,
        failure: Throwable,
    ) {
        val issue =
            assertNotNull(
                failure.contractSemanticExceptionOrNull(),
                "${fixture.id} did not expose a structured semantic failure: $failure",
            )
        assertEquals(fixture.expectedCode, issue.code, fixture.id)
        assertEquals(fixture.expectedPath, issue.path, fixture.id)
    }
}
