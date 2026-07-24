package com.maneesh.universalai.connector.contract.testing

import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiUsage
import com.maneesh.universalai.connector.contract.contractSemanticExceptionOrNull
import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.contract.extension.Extensions
import com.maneesh.universalai.connector.contract.json.CanonicalJson
import com.maneesh.universalai.connector.contract.schema.GovernedJsonSchemaSubset
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractHarnessTests {
    private val json = Json

    @Test
    fun fixturesFailAtTheirDocumentedBoundary() {
        assertEquals(
            ContractSeedFixtures.all.size,
            ContractSeedFixtures.all.map { it.id }.toSet().size,
            "Fixture IDs must remain unique.",
        )
        assertEquals(
            ContractSeedFixtures.all.size,
            ContractSeedFixtures.all.map { it.repositoryPath }.toSet().size,
            "Fixture paths must remain unique.",
        )

        ContractSeedFixtures.all.forEach { fixture ->
            val issue = ContractFixtureValidator.validate(fixture)
            if (fixture.isValid) {
                assertNull(issue, "${fixture.id} unexpectedly failed: $issue")
            } else {
                assertEquals(fixture.expectedLayer, issue?.layer, fixture.id)
                assertEquals(fixture.expectedCode, issue?.code, fixture.id)
                assertEquals(fixture.expectedKeyword, issue?.keyword, fixture.id)
                assertEquals(fixture.expectedPath, issue?.path, fixture.id)
            }
        }
    }

    @Test
    fun schemaValidComponentFixturesExerciseProductionSerializers() {
        ContractSeedFixtures.all
            .filter { fixture ->
                fixture.family.isComponentFamily &&
                    fixture.expectedLayer != ContractSeedLayer.SCHEMA
            }.forEach { fixture ->
                if (fixture.isValid) {
                    val encoded = ComponentContractFixtureValidator.productionRoundTrip(fixture)
                    assertEquals(
                        encoded,
                        ComponentContractFixtureValidator.productionRoundTrip(
                            fixture.copy(json = encoded),
                        ),
                        fixture.id,
                    )
                } else {
                    val failure =
                        assertFails(fixture.id) {
                            ComponentContractFixtureValidator.productionRoundTrip(fixture)
                        }
                    assertDocumentedSemanticFailure(fixture, failure)
                }
            }
    }

    @Test
    fun schemaValidRequestFixturesExerciseTheProductionDecoder() {
        ContractSeedFixtures.all
            .filter { fixture ->
                fixture.family == ContractSeedFamily.REQUEST &&
                    fixture.expectedLayer != ContractSeedLayer.SCHEMA
            }.forEach { fixture ->
                if (fixture.isValid) {
                    val decoded = UniversalAiRequest.fromJson(fixture.json)
                    assertEquals(
                        decoded,
                        UniversalAiRequest.fromJson(decoded.toJson()),
                        fixture.id,
                    )
                } else {
                    val failure =
                        assertFails(fixture.id) {
                            UniversalAiRequest.fromJson(fixture.json)
                        }
                    assertDocumentedSemanticFailure(fixture, failure)
                }
            }
    }

    @Test
    fun schemaValidP2GFixturesExerciseProductionContracts() {
        ContractSeedFixtures.all
            .filter { fixture ->
                fixture.family.isP2GFamily &&
                    fixture.expectedLayer != ContractSeedLayer.SCHEMA
            }.forEach { fixture ->
                if (fixture.isValid) {
                    val encoded = P2GContractFixtureValidator.productionRoundTrip(fixture)
                    assertEquals(
                        encoded,
                        P2GContractFixtureValidator.productionRoundTrip(
                            fixture.copy(json = encoded),
                        ),
                        fixture.id,
                    )
                } else {
                    val failure =
                        assertFails(fixture.id) {
                            P2GContractFixtureValidator.productionRoundTrip(fixture)
                        }
                    assertDocumentedSemanticFailure(fixture, failure)
                }
            }
    }

    @Test
    fun componentCompatibilityFixturesPreserveRawValuesAndDropOrdinaryMembers() {
        val provider =
            componentRoundTripJson("v1-provider-id-future") as JsonPrimitive
        val model =
            componentRoundTripJson("v1-model-id-future") as JsonPrimitive
        val target =
            componentRoundTripJson("v1-model-target-future-member") as JsonObject
        val input =
            componentRoundTripJson("v1-text-input-future-role") as JsonObject
        val format =
            componentRoundTripJson("v1-response-format-future-kind") as JsonObject
        val generation =
            componentRoundTripJson("v1-generation-parameters-future-member") as JsonObject

        assertEquals("future-provider", provider.content)
        assertEquals("future/model:v2", model.content)
        assertEquals("future-provider", target.getValue("providerId").asPrimitive().content)
        assertEquals("future/model:v2", target.getValue("modelId").asPrimitive().content)
        assertFalse("futureTargetMember" in target)
        assertEquals("critic_v2", input.getValue("role").asPrimitive().content)
        assertFalse("futureInputMember" in input)
        assertEquals("future_binary", format.getValue("kind").asPrimitive().content)
        assertFalse("futureFormatMember" in format)
        assertTrue(generation.isEmpty())
    }

    @Test
    fun futureRequestValuesRemainDistinctWhileOrdinaryMembersAreDropped() {
        val fixture =
            ContractSeedFixtures.all.single { it.id == "v1-request-future-values" }
        val decoded = UniversalAiRequest.fromJson(fixture.json)

        assertEquals("critic_v2", decoded.input.single().role.rawValue)
        assertFalse(decoded.input.single().role.isKnown)
        assertEquals("future_binary", decoded.responseFormat.kind.rawValue)
        assertFalse(decoded.responseFormat.isKnown)

        val original = json.parseToJsonElement(fixture.json) as JsonObject
        val encoded = json.parseToJsonElement(decoded.toJson()) as JsonObject
        assertFalse("futureRequestMember" in encoded)
        assertFalse("futureTargetMember" in encoded.getValue("target").asObject())
        assertFalse("futureInputMember" in encoded.getValue("input").asArray().single().asObject())
        assertFalse("futureFormatMember" in encoded.getValue("responseFormat").asObject())
        assertFalse("generation" in encoded)
        assertEquals(original.getValue("extensions"), encoded.getValue("extensions"))
    }

    @Test
    fun integralExponentMaxOutputTokensDecodesExactly() {
        val fixture =
            ContractSeedFixtures.all.single { it.id == "v1-request-integral-exponent-token" }
        val decoded = UniversalAiRequest.fromJson(fixture.json)

        assertEquals(1, decoded.generation.maxOutputTokens)
    }

    @Test
    fun integralUsageNumberFormsDecodeExactly() {
        val fixture =
            ContractSeedFixtures.all.single { it.id == "v1-usage-integral-number-forms" }
        val usage =
            CanonicalJson.decode(
                UniversalAiUsage.serializer(),
                fixture.json,
            )

        assertEquals(1L, usage.inputTokens)
        assertEquals(2L, usage.outputTokens)
        assertEquals(3L, usage.totalTokens)
        assertEquals(1L, usage.inputDetails.getValue("cached"))
        assertEquals(2L, usage.outputDetails.getValue("visible"))
    }

    @Test
    fun p2gCompatibilityFixturesPreserveRawValuesAndDropOrdinaryMembers() {
        val output = p2gRoundTripJson("v1-output-future-kind").asObject()
        val usage = p2gRoundTripJson("v1-usage-future-breakdowns").asObject()
        val error = p2gRoundTripJson("v1-error-future-values").asObject()
        val response = p2gRoundTripJson("v1-response-future-values").asObject()
        val event = p2gRoundTripJson("v1-stream-event-future-type").asObject()
        val sequence = p2gRoundTripJson("v1-stream-sequence-future-event").asArray()

        assertEquals("future_audio", output.getValue("kind").asPrimitive().content)
        assertFalse("futureOutputMember" in output)
        assertTrue("extensions" in output)

        assertEquals(
            3L,
            usage
                .getValue("inputDetails")
                .asObject()
                .getValue("future_cached_v2")
                .asPrimitive()
                .content
                .toLong(),
        )
        assertFalse("futureUsageMember" in usage)

        assertEquals("future_provider", error.getValue("category").asPrimitive().content)
        assertEquals("future_condition_v2", error.getValue("code").asPrimitive().content)
        assertFalse("futureErrorMember" in error)

        assertEquals("future_pause", response.getValue("completionReason").asPrimitive().content)
        val futureResponseOutput =
            response
                .getValue("outputs")
                .asArray()
                .single()
                .asObject()
        assertEquals(
            "future_audio",
            futureResponseOutput.getValue("kind").asPrimitive().content,
        )
        assertFalse("futureResponseMember" in response)
        assertFalse("futureTargetMember" in response.getValue("target").asObject())
        assertFalse("futureOutputMember" in futureResponseOutput)

        assertEquals("future.trace", event.getValue("type").asPrimitive().content)
        assertFalse("futureEventMember" in event)
        assertTrue("extensions" in event)

        val futureSequenceEvent = sequence[1].asObject()
        assertEquals("future.trace", futureSequenceEvent.getValue("type").asPrimitive().content)
        assertFalse("futureEventMember" in futureSequenceEvent)
        assertTrue("extensions" in futureSequenceEvent)
    }

    @Test
    fun extensionNumberKeepsItsRawJsonToken() {
        val fixture = ContractSeedFixtures.all.single { it.id == "v1-extension-primitives" }
        val root = json.parseToJsonElement(fixture.json) as JsonObject
        val payload = root.getValue("com.example.connector") as JsonObject
        val number = payload.getValue("numberValue") as JsonPrimitive

        assertEquals("1.2300e+4", number.content)
        assertTrue(!number.isString)
    }

    @Test
    fun extensionFixturesExerciseTheProductionSerializerAndSemanticValidation() {
        ContractSeedFixtures.all
            .filter { it.family == ContractSeedFamily.EXTENSIONS }
            .forEach { fixture ->
                if (fixture.isValid) {
                    val decoded = CanonicalJson.decode(Extensions.serializer(), fixture.json)
                    val encoded = CanonicalJson.encode(Extensions.serializer(), decoded)
                    assertEquals(
                        json.parseToJsonElement(fixture.json),
                        json.parseToJsonElement(encoded),
                        fixture.id,
                    )
                } else if (fixture.expectedLayer == ContractSeedLayer.SEMANTIC) {
                    val failure =
                        assertFailsWith<SerializationException>(fixture.id) {
                            CanonicalJson.decode(Extensions.serializer(), fixture.json)
                        }
                    assertDocumentedSemanticFailure(fixture, failure)
                }
            }
    }

    @Test
    fun extensionValueFixturesExerciseTheProductionSerializerAndSemanticValidation() {
        ContractSeedFixtures.all
            .filter { it.family == ContractSeedFamily.EXTENSION_VALUE }
            .forEach { fixture ->
                if (fixture.isValid) {
                    val decoded =
                        CanonicalJson.decode(
                            ExtensionValue.serializer(),
                            fixture.json,
                        )
                    val encoded =
                        CanonicalJson.encode(
                            ExtensionValue.serializer(),
                            decoded,
                        )
                    val roundTripped =
                        CanonicalJson.decode(
                            ExtensionValue.serializer(),
                            encoded,
                        )
                    assertEquals(decoded, roundTripped, fixture.id)
                } else if (fixture.expectedLayer == ContractSeedLayer.SEMANTIC) {
                    val failure =
                        assertFailsWith<SerializationException>(fixture.id) {
                            CanonicalJson.decode(
                                ExtensionValue.serializer(),
                                fixture.json,
                            )
                        }
                    assertDocumentedSemanticFailure(fixture, failure)
                }
            }
    }

    @Test
    fun governedSubsetAcceptsAcyclicLocalDefinitions() {
        val schema =
            json.parseToJsonElement(
                """
                {
                  "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
                  "${'$'}defs": {
                    "answer": {
                      "type": "string",
                      "minLength": 1
                    }
                  },
                  "type": "object",
                  "properties": {
                    "answer": {
                      "${'$'}ref": "#/${'$'}defs/answer"
                    }
                  },
                  "required": ["answer"],
                  "additionalProperties": false
                }
                """.trimIndent(),
            )

        assertNull(GovernedJsonSchemaSubset.validate(schema))
    }

    @Test
    fun governedSubsetRejectsUnsupportedExternalAndRecursiveFeatures() {
        val cases =
            listOf(
                """{"type":"string","pattern":"[a-z]+"}""" to "unsupported_schema_keyword",
                """{"${'$'}ref":"https://example.com/schema"}""" to
                    "unsupported_schema_reference",
                """
                {
                  "${'$'}defs": {
                    "node": {
                      "${'$'}ref": "#/${'$'}defs/node"
                    }
                  },
                  "${'$'}ref": "#/${'$'}defs/node"
                }
                """.trimIndent() to "recursive_schema_not_supported",
            )

        cases.forEach { (rawSchema, expectedCode) ->
            assertEquals(
                expectedCode,
                GovernedJsonSchemaSubset.validate(json.parseToJsonElement(rawSchema))?.code,
                rawSchema,
            )
        }
    }

    @Test
    fun governedSubsetDoesNotInterpretReferenceShapedInstanceDataAsSchemas() {
        val schema =
            json.parseToJsonElement(
                """
                {
                  "${'$'}defs": {
                    "node": {
                      "type": "object",
                      "const": {
                        "${'$'}ref": "#/${'$'}defs/node"
                      },
                      "examples": [
                        {
                          "${'$'}ref": "#/${'$'}defs/node"
                        }
                      ]
                    }
                  },
                  "${'$'}ref": "#/${'$'}defs/node"
                }
                """.trimIndent(),
            )

        assertNull(GovernedJsonSchemaSubset.validate(schema))
    }

    private fun JsonElement.asObject(): JsonObject = this as JsonObject

    private fun JsonElement.asArray(): JsonArray = this as JsonArray

    private fun JsonElement.asPrimitive(): JsonPrimitive = this as JsonPrimitive

    private fun componentRoundTripJson(id: String): JsonElement {
        val fixture = ContractSeedFixtures.all.single { it.id == id }
        return json.parseToJsonElement(
            ComponentContractFixtureValidator.productionRoundTrip(fixture),
        )
    }

    private fun p2gRoundTripJson(id: String): JsonElement {
        val fixture = ContractSeedFixtures.all.single { it.id == id }
        return json.parseToJsonElement(
            P2GContractFixtureValidator.productionRoundTrip(fixture),
        )
    }

    private fun assertDocumentedSemanticFailure(
        fixture: ContractSeedFixture,
        failure: Throwable,
    ) {
        val semanticFailure =
            assertNotNull(
                failure.contractSemanticExceptionOrNull(),
                "${fixture.id} did not expose a structured semantic failure: $failure",
            )
        assertEquals(fixture.expectedCode, semanticFailure.code, fixture.id)
        assertEquals(fixture.expectedPath, semanticFailure.path, fixture.id)
    }
}
