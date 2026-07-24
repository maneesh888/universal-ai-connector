package com.maneesh.universalai.connector.contract.tooling

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.networknt.schema.dialect.Dialects
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.contract.extension.Extensions
import com.maneesh.universalai.connector.contract.json.CanonicalJson
import com.maneesh.universalai.connector.contract.testing.ComponentContractFixtureValidator
import com.maneesh.universalai.connector.contract.testing.ContractSeedFamily
import com.maneesh.universalai.connector.contract.testing.ContractSeedFixture
import com.maneesh.universalai.connector.contract.testing.ContractSeedFixtures
import com.maneesh.universalai.connector.contract.testing.P2GContractFixtureValidator
import com.maneesh.universalai.connector.contract.testing.P2HContractFixtureValidator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

class ContractRepositoryHarnessTests {
    private val contractsRoot =
        Path.of(
            checkNotNull(System.getProperty("uac.contracts.root")) {
                "The uac.contracts.root test property is required."
            },
        )
    private val schemasRoot = contractsRoot.resolve("schemas/v1")
    private val fixturesRoot = contractsRoot.resolve("fixtures/v1")
    private val json = Json { ignoreUnknownKeys = false }
    private val exactNumberJson =
        JsonMapper
            .builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build()

    @Test
    fun schemasAreMetaValidAndUseOnlyTheGovernedBundle() {
        val schemas = loadSchemas()
        val metaRegistry = SchemaRegistry.withDialect(Dialects.getDraft202012())
        val metaSchema =
            metaRegistry.getSchema(
                SchemaLocation.of(Dialects.getDraft202012().id),
            )

        schemas.forEach { (id, document) ->
            val metaErrors = metaSchema.validate(document.source, InputFormat.JSON)
            assertTrue(
                metaErrors.isEmpty(),
                "$id is not a valid Draft 2020-12 schema: $metaErrors",
            )
            validateGovernedKeywords(document.element, id)
            validateReferences(
                schema = document.element,
                knownSchemas = schemas,
                schemaId = id,
                owningDocument = document.element,
            )
        }
    }

    @Test
    fun fixtureManifestExactlyMatchesRepositoryAndExpectedValidationLayer() {
        val schemas = loadSchemas()
        val registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
                builder.schemas(schemas.mapValues { (_, document) -> document.source })
            }
        val manifest =
            json.decodeFromString<FixtureManifest>(
                contractsRoot.resolve("fixture-manifest.json").readText(),
            )

        assertEquals("1", manifest.manifestVersion)
        assertEquals(
            manifest.fixtures.size,
            manifest.fixtures.map { it.id }.toSet().size,
            "Fixture IDs must be unique.",
        )
        assertEquals(
            manifest.fixtures.size,
            manifest.fixtures.map { it.path }.toSet().size,
            "Fixture paths must be unique.",
        )

        val repositoryFixturePaths =
            Files.walk(fixturesRoot).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.fileName.toString().endsWith(".json") }
                    .map { contractsRoot.parent.relativize(it).toString().replace('\\', '/') }
                    .toList()
                    .toSet()
            }
        assertEquals(
            repositoryFixturePaths,
            manifest.fixtures.map { it.path }.toSet(),
            "Every tracked fixture must appear exactly once in the manifest.",
        )
        assertDirectSchemaCoverage(
            schemaIds = schemas.keys,
            fixtureSchemaIds = manifest.fixtures.map { it.schemaId }.toSet(),
            fixtureDescription = "fixture",
        )
        assertDirectSchemaCoverage(
            schemaIds = schemas.keys,
            fixtureSchemaIds =
                manifest.fixtures
                .filter { it.expectedResult == "valid" }
                .map { it.schemaId }
                .toSet(),
            fixtureDescription = "valid fixture",
        )

        manifest.fixtures.forEach { fixture ->
            assertEquals("1", fixture.contractVersion, "${fixture.id} has the wrong contract major.")
            assertTrue(fixture.id.isNotBlank(), "Fixture IDs must not be blank.")
            assertTrue(fixture.family.isNotBlank(), "${fixture.id} must name its model family.")
            assertTrue(
                fixture.path.startsWith("contracts/fixtures/v1/") && ".." !in fixture.path,
                "${fixture.id} has an unsafe or wrong-version path.",
            )
            assertTrue(fixture.schemaId in schemas, "${fixture.id} names an unknown schema.")

            if (fixture.expectedResult == "invalid") {
                assertTrue(!fixture.expectedLayer.isNullOrBlank(), "${fixture.id} needs a failure layer.")
                assertTrue(!fixture.expectedCode.isNullOrBlank(), "${fixture.id} needs a stable failure code.")
            } else {
                assertEquals("valid", fixture.expectedResult, "${fixture.id} has an unknown result.")
                assertEquals(null, fixture.expectedLayer, "${fixture.id} must not declare a failure layer.")
                assertEquals(null, fixture.expectedCode, "${fixture.id} must not declare a failure code.")
            }

            val fixturePath = contractsRoot.parent.resolve(fixture.path)
            val fixtureSource = fixturePath.readText()
            val validationErrors =
                registry
                    .getSchema(SchemaLocation.of(fixture.schemaId))
                    .validate(exactNumberJson.readTree(fixtureSource))
            when (fixture.expectedLayer) {
                "schema" -> {
                    assertTrue(!fixture.expectedKeyword.isNullOrBlank(), "${fixture.id} needs a schema keyword.")
                    assertTrue(fixture.expectedPath != null, "${fixture.id} needs an instance path.")
                    assertEquals(
                        1,
                        validationErrors.size,
                        "${fixture.id} must fail for exactly its documented schema reason: $validationErrors",
                    )
                    val error = validationErrors.single()
                    assertEquals(
                        fixture.expectedKeyword,
                        error.keyword,
                        "${fixture.id} failed at the wrong schema keyword: $error",
                    )
                    assertEquals(
                        fixture.expectedPath,
                        error.instanceLocation.toString(),
                        "${fixture.id} failed at the wrong instance path: $error",
                    )
                }

                "semantic" -> {
                    assertEquals(
                        null,
                        fixture.expectedKeyword,
                        "${fixture.id} must not name a schema keyword.",
                    )
                    assertTrue(
                        fixture.expectedPath != null,
                        "${fixture.id} needs a semantic JSON Pointer.",
                    )
                    assertTrue(
                        validationErrors.isEmpty(),
                        "${fixture.id} unexpectedly failed contract-schema validation: $validationErrors",
                    )
                }

                else -> {
                    assertEquals(null, fixture.expectedKeyword, "${fixture.id} must not name a schema keyword.")
                    assertEquals(null, fixture.expectedPath, "${fixture.id} must not name a failure path.")
                    assertTrue(
                        validationErrors.isEmpty(),
                        "${fixture.id} unexpectedly failed contract-schema validation: $validationErrors",
                    )
                }
            }
        }
    }

    @Test
    fun productionReencodingsOfValidAndCompatibilityFixturesRemainSchemaValid() {
        val schemas = loadSchemas()
        val registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
                builder.schemas(schemas.mapValues { (_, document) -> document.source })
            }

        ContractSeedFixtures.all
            .filter { fixture -> fixture.isValid }
            .forEach { fixture ->
                val reencoded = productionRoundTrip(fixture)
                val validationErrors =
                    registry
                        .getSchema(SchemaLocation.of(fixture.schemaId))
                        .validate(exactNumberJson.readTree(reencoded))

                assertTrue(
                    validationErrors.isEmpty(),
                    "${fixture.id} production re-encoding no longer satisfies " +
                        "${fixture.schemaId}: $validationErrors",
                )
            }
    }

    @Test
    fun commonMultiplatformCorpusIsMechanicallyIdenticalToRepositoryFixtures() {
        val manifest =
            json.decodeFromString<FixtureManifest>(
                contractsRoot.resolve("fixture-manifest.json").readText(),
            )
        val manifestById = manifest.fixtures.associateBy { it.id }

        assertEquals(
            manifestById.keys,
            ContractSeedFixtures.all.map { it.id }.toSet(),
            "The common multiplatform corpus and fixture manifest must contain the same IDs.",
        )
        ContractSeedFixtures.all.forEach { seed ->
            val manifestEntry = manifestById.getValue(seed.id)
            val repositoryBytes =
                contractsRoot.parent
                    .resolve(seed.repositoryPath)
                    .readBytes()

            assertTrue(
                repositoryFixtureBytesMatch(
                    embeddedJson = seed.json,
                    repositoryBytes = repositoryBytes,
                ),
                "${seed.id} drifted byte-for-byte from the common corpus.",
            )
            assertEquals(seed.repositoryPath, manifestEntry.path)
            assertEquals(seed.schemaId, manifestEntry.schemaId)
            assertEquals(seed.family.name.lowercase(), manifestEntry.family)
            assertEquals(seed.expectedLayer?.name?.lowercase(), manifestEntry.expectedLayer)
            assertEquals(seed.expectedCode, manifestEntry.expectedCode)
            assertEquals(seed.expectedKeyword, manifestEntry.expectedKeyword)
            assertEquals(seed.expectedPath, manifestEntry.expectedPath)
            assertEquals(seed.compatibilityPurpose, manifestEntry.compatibilityPurpose)
            assertEquals(
                if (seed.isValid) "valid" else "invalid",
                manifestEntry.expectedResult,
            )
        }
    }

    @Test
    fun commonCorpusDriftCheckRejectsEveryEndOfFileWhitespaceChange() {
        val embeddedJson = """{"value":"exact"}"""
        val exactRepositoryBytes = "$embeddedJson\n".encodeToByteArray()

        assertTrue(
            repositoryFixtureBytesMatch(embeddedJson, exactRepositoryBytes),
        )
        listOf(
            embeddedJson.encodeToByteArray(),
            "$embeddedJson\r\n".encodeToByteArray(),
            "$embeddedJson\n\n".encodeToByteArray(),
            "$embeddedJson \n".encodeToByteArray(),
            "$embeddedJson\n ".encodeToByteArray(),
        ).forEach { driftedBytes ->
            assertFalse(
                repositoryFixtureBytesMatch(embeddedJson, driftedBytes),
                "EOF whitespace drift must not match the embedded corpus.",
            )
        }
    }

    @Test
    fun referenceIntegrityRejectsUnresolvedLocalPointersWithoutFixtureCoverage() {
        val id = "urn:universal-ai-connector:test:unresolved-reference"
        val element =
            json.parseToJsonElement(
                """
                {
                  "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
                  "${'$'}id": "$id",
                  "${'$'}defs": {
                    "present": {
                      "type": "string"
                    }
                  },
                  "${'$'}ref": "#/${'$'}defs/missing"
                }
                """.trimIndent(),
            ).jsonObject
        val document = SchemaDocument(source = element.toString(), element = element)

        assertFailsWith<AssertionError> {
            validateReferences(
                schema = element,
                knownSchemas = mapOf(id to document),
                schemaId = id,
                owningDocument = element,
            )
        }
    }

    @Test
    fun directFixtureCoverageRejectsSyntheticOrphanSchemaIds() {
        val coveredSchemaId = "urn:universal-ai-connector:test:covered"
        val orphanSchemaId = "urn:universal-ai-connector:test:orphan"

        val failure =
            assertFailsWith<AssertionError> {
                assertDirectSchemaCoverage(
                    schemaIds = setOf(coveredSchemaId, orphanSchemaId),
                    fixtureSchemaIds = setOf(coveredSchemaId),
                    fixtureDescription = "synthetic fixture",
                )
            }

        assertTrue(
            failure.message.orEmpty().contains(orphanSchemaId),
            "The orphan schema ID must be named by the coverage failure.",
        )
    }

    private fun loadSchemas(): Map<String, SchemaDocument> {
        val documents =
            Files.list(schemasRoot).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.fileName.toString().endsWith(".schema.json") }
                    .map { path ->
                        val source = path.readText()
                        val element = json.parseToJsonElement(source).jsonObject
                        val id = element.getValue("\$id").jsonPrimitive.content
                        id to SchemaDocument(source, element)
                    }.toList()
            }
        assertTrue(documents.isNotEmpty(), "At least one authoritative schema is required.")
        assertEquals(
            documents.size,
            documents.map { it.first }.toSet().size,
            "Authoritative schema IDs must be unique.",
        )
        return documents.toMap()
    }

    private fun productionRoundTrip(fixture: ContractSeedFixture): String =
        when (fixture.family) {
            ContractSeedFamily.CONTRACT_ENVELOPE -> {
                val envelope =
                    CanonicalJson.decode(
                        JsonObject.serializer(),
                        fixture.json,
                    )
                CanonicalJson.encode(JsonObject.serializer(), envelope)
            }
            ContractSeedFamily.EXTENSION_VALUE -> {
                val value =
                    CanonicalJson.decode(
                        ExtensionValue.serializer(),
                        fixture.json,
                    )
                CanonicalJson.encode(ExtensionValue.serializer(), value)
            }
            ContractSeedFamily.EXTENSIONS -> {
                val extensions =
                    CanonicalJson.decode(
                        Extensions.serializer(),
                        fixture.json,
                    )
                CanonicalJson.encode(Extensions.serializer(), extensions)
            }
            ContractSeedFamily.PROVIDER_ID,
            ContractSeedFamily.MODEL_ID,
            ContractSeedFamily.MODEL_TARGET,
            ContractSeedFamily.TEXT_INPUT,
            ContractSeedFamily.RESPONSE_FORMAT,
            ContractSeedFamily.GENERATION_PARAMETERS,
            -> ComponentContractFixtureValidator.productionRoundTrip(fixture)
            ContractSeedFamily.REQUEST ->
                UniversalAiRequest.fromJson(fixture.json).toJson()
            ContractSeedFamily.OPERATION_ID,
            ContractSeedFamily.OUTPUT,
            ContractSeedFamily.USAGE,
            ContractSeedFamily.ERROR,
            ContractSeedFamily.RESPONSE,
            ContractSeedFamily.STREAM_EVENT,
            ContractSeedFamily.STREAM_SEQUENCE,
            -> P2GContractFixtureValidator.productionRoundTrip(fixture)
            ContractSeedFamily.CAPABILITY_SET,
            ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE,
            ContractSeedFamily.MODEL_TOKEN_LIMITS,
            ContractSeedFamily.MODEL_DESCRIPTOR,
            -> P2HContractFixtureValidator.productionRoundTrip(fixture)
        }

    private fun validateGovernedKeywords(
        schema: JsonElement,
        schemaId: String,
        location: String = "#",
    ) {
        if (schema is JsonPrimitive && schema.booleanOrNull != null) {
            return
        }
        val schemaObject =
            schema as? JsonObject
                ?: error("$schemaId $location must be an object or Boolean schema.")
        schemaObject.keys.forEach { keyword ->
            assertTrue(
                keyword in CONTRACT_SCHEMA_KEYWORDS,
                "$schemaId uses unsupported keyword $keyword at $location.",
            )
        }

        schemaObject["\$defs"]
            ?.jsonObject
            ?.forEach { (name, child) ->
                validateGovernedKeywords(child, schemaId, "$location/\$defs/$name")
            }
        schemaObject["properties"]
            ?.jsonObject
            ?.forEach { (name, child) ->
                validateGovernedKeywords(child, schemaId, "$location/properties/$name")
            }
        listOf("additionalProperties", "items", "not", "propertyNames").forEach { keyword ->
            schemaObject[keyword]?.let { child ->
                validateGovernedKeywords(child, schemaId, "$location/$keyword")
            }
        }
        listOf("prefixItems", "allOf", "anyOf", "oneOf").forEach { keyword ->
            (schemaObject[keyword] as? JsonArray)?.forEachIndexed { index, child ->
                validateGovernedKeywords(child, schemaId, "$location/$keyword/$index")
            }
        }
    }

    private fun validateReferences(
        schema: JsonElement,
        knownSchemas: Map<String, SchemaDocument>,
        schemaId: String,
        owningDocument: JsonElement,
    ) {
        if (schema is JsonPrimitive && schema.booleanOrNull != null) {
            return
        }
        val schemaObject = schema.jsonObject
        schemaObject["\$ref"]?.let { value ->
            val reference = value.jsonPrimitive.content
            assertTrue(
                referenceResolves(reference, knownSchemas, owningDocument),
                "$schemaId contains an unresolved, unsupported, or external reference: $reference",
            )
        }
        schemaObject["\$defs"]
            ?.jsonObject
            ?.values
            ?.forEach { validateReferences(it, knownSchemas, schemaId, owningDocument) }
        schemaObject["properties"]
            ?.jsonObject
            ?.values
            ?.forEach { validateReferences(it, knownSchemas, schemaId, owningDocument) }
        listOf("additionalProperties", "items", "not", "propertyNames").forEach { keyword ->
            schemaObject[keyword]?.let {
                validateReferences(it, knownSchemas, schemaId, owningDocument)
            }
        }
        listOf("prefixItems", "allOf", "anyOf", "oneOf").forEach { keyword ->
            (schemaObject[keyword] as? JsonArray)?.forEach {
                validateReferences(it, knownSchemas, schemaId, owningDocument)
            }
        }
    }

    private fun referenceResolves(
        reference: String,
        knownSchemas: Map<String, SchemaDocument>,
        owningDocument: JsonElement,
    ): Boolean {
        val fragmentMarker = reference.indexOf('#')
        val base =
            if (fragmentMarker >= 0) {
                reference.substring(0, fragmentMarker)
            } else {
                reference
            }
        val fragment =
            if (fragmentMarker >= 0) {
                reference.substring(fragmentMarker + 1)
            } else {
                null
            }
        if (fragment?.contains('#') == true || fragment?.contains('%') == true) {
            return false
        }

        val targetDocument =
            if (base.isEmpty()) {
                owningDocument
            } else {
                knownSchemas[base]?.element ?: return false
            }
        return fragment == null || resolveJsonPointer(targetDocument, fragment)
    }

    private fun resolveJsonPointer(
        document: JsonElement,
        pointer: String,
    ): Boolean {
        if (pointer.isEmpty()) {
            return true
        }
        if (!pointer.startsWith('/')) {
            return false
        }

        var current = document
        pointer.removePrefix("/").split('/').forEach { encodedToken ->
            val token = decodeJsonPointerToken(encodedToken) ?: return false
            current =
                when (current) {
                    is JsonObject -> current[token] ?: return false
                    is JsonArray -> {
                        val index = token.toIntOrNull() ?: return false
                        if (index < 0 || index >= current.size || token != index.toString()) {
                            return false
                        }
                        current[index]
                    }
                    is JsonPrimitive -> return false
                }
        }
        return true
    }

    private fun decodeJsonPointerToken(token: String): String? {
        val decoded = StringBuilder()
        var index = 0
        while (index < token.length) {
            if (token[index] != '~') {
                decoded.append(token[index])
                index += 1
                continue
            }
            if (index + 1 >= token.length) {
                return null
            }
            when (token[index + 1]) {
                '0' -> decoded.append('~')
                '1' -> decoded.append('/')
                else -> return null
            }
            index += 2
        }
        return decoded.toString()
    }

    private data class SchemaDocument(
        val source: String,
        val element: JsonObject,
    )

    @Serializable
    private data class FixtureManifest(
        val manifestVersion: String,
        val fixtures: List<FixtureEntry>,
    )

    @Serializable
    private data class FixtureEntry(
        val id: String,
        val path: String,
        val contractVersion: String,
        val family: String,
        val schemaId: String,
        val expectedResult: String,
        val expectedLayer: String? = null,
        val expectedCode: String? = null,
        val expectedKeyword: String? = null,
        val expectedPath: String? = null,
        val compatibilityPurpose: String? = null,
    )

    private companion object {
        val CONTRACT_SCHEMA_KEYWORDS =
            setOf(
                "\$schema",
                "\$id",
                "\$defs",
                "\$ref",
                "\$comment",
                "title",
                "description",
                "default",
                "deprecated",
                "examples",
                "type",
                "const",
                "enum",
                "minimum",
                "exclusiveMinimum",
                "maximum",
                "exclusiveMaximum",
                "minLength",
                "maxLength",
                "pattern",
                "properties",
                "propertyNames",
                "required",
                "additionalProperties",
                "minProperties",
                "maxProperties",
                "items",
                "prefixItems",
                "minItems",
                "maxItems",
                "allOf",
                "anyOf",
                "oneOf",
                "not",
            )
    }
}

private fun repositoryFixtureBytesMatch(
    embeddedJson: String,
    repositoryBytes: ByteArray,
): Boolean =
    repositoryBytes.contentEquals(
        "$embeddedJson\n".encodeToByteArray(),
    )

private fun assertDirectSchemaCoverage(
    schemaIds: Set<String>,
    fixtureSchemaIds: Set<String>,
    fixtureDescription: String,
) {
    val orphanSchemaIds = schemaIds - fixtureSchemaIds
    assertTrue(
        orphanSchemaIds.isEmpty(),
        "Authoritative schemas without a directly associated $fixtureDescription: " +
            orphanSchemaIds.sorted().joinToString(),
    )
    assertTrue(
        fixtureSchemaIds.all { it in schemaIds },
        "Unknown schema IDs referenced by a $fixtureDescription: " +
            (fixtureSchemaIds - schemaIds).sorted().joinToString(),
    )
}
