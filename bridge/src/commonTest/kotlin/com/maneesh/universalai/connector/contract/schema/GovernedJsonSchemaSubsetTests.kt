package com.maneesh.universalai.connector.contract.schema

import com.maneesh.universalai.connector.contract.StructuredOutputSchema
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.contractSemanticExceptionOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class GovernedJsonSchemaSubsetTests {
    private val json = Json

    @Test
    fun acceptsBooleanMinimalAndEverySupportedKeywordCategory() {
        assertNull(GovernedJsonSchemaSubset.validate(JsonPrimitive(true)))
        assertNull(GovernedJsonSchemaSubset.validate(JsonPrimitive(false)))
        assertNull(GovernedJsonSchemaSubset.validate(JsonObject(emptyMap())))

        val completeSchema =
            parse(
                """
                {
                  "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
                  "${'$'}defs": {
                    "text": {
                      "type": "string"
                    }
                  },
                  "${'$'}ref": "#/${'$'}defs/text",
                  "${'$'}comment": "governed annotation",
                  "title": "Complete schema",
                  "description": "Exercises every supported keyword category.",
                  "default": null,
                  "deprecated": false,
                  "examples": [
                    null,
                    "example",
                    {
                      "nested": true
                    }
                  ],
                  "format": "future-annotation",
                  "type": [
                    "null",
                    "boolean",
                    "object",
                    "array",
                    "number",
                    "integer",
                    "string"
                  ],
                  "enum": [
                    null,
                    1,
                    "one"
                  ],
                  "const": {
                    "fixed": true
                  },
                  "minimum": -10.5,
                  "exclusiveMinimum": -11,
                  "maximum": 10.5,
                  "exclusiveMaximum": 11,
                  "minLength": 0,
                  "maxLength": 64,
                  "properties": {
                    "value": {
                      "${'$'}ref": "#/${'$'}defs/text"
                    }
                  },
                  "required": [],
                  "additionalProperties": false,
                  "minProperties": 0,
                  "maxProperties": 4,
                  "items": true,
                  "prefixItems": [
                    false,
                    {
                      "type": "string"
                    }
                  ],
                  "minItems": 0,
                  "maxItems": 4,
                  "allOf": [
                    true
                  ],
                  "anyOf": [
                    false,
                    true
                  ],
                  "oneOf": [
                    {
                      "type": "string"
                    }
                  ],
                  "not": false
                }
                """,
            )

        assertNull(GovernedJsonSchemaSubset.validate(completeSchema))
    }

    @Test
    fun acceptsDraftDialectAndEscapedLocalDefinitionReferences() {
        val schema =
            parse(
                """
                {
                  "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
                  "${'$'}defs": {
                    "path/name~v1": {
                      "type": "string"
                    }
                  },
                  "${'$'}ref": "#/${'$'}defs/path~1name~0v1"
                }
                """,
            )

        assertNull(GovernedJsonSchemaSubset.validate(schema))
    }

    @Test
    fun enforcesSchemaDepthBoundary() {
        assertNull(
            GovernedJsonSchemaSubset.validate(
                nestedSchema(depth = GovernedJsonSchemaSubset.MAX_SCHEMA_DEPTH),
            ),
        )
        assertIssue(
            expectedCode = "schema_depth_limit_exceeded",
            issue =
                GovernedJsonSchemaSubset.validate(
                    nestedSchema(depth = GovernedJsonSchemaSubset.MAX_SCHEMA_DEPTH + 1),
                ),
        )
    }

    @Test
    fun enforcesSchemaNodeBoundaryAcrossCollections() {
        val definitions =
            (0 until 256).associate { index ->
                "definition_$index" to JsonPrimitive(true)
            }
        val prefixItemsAtBoundary =
            JsonArray(
                List(GovernedJsonSchemaSubset.MAX_SCHEMA_NODES - definitions.size - 1) {
                    JsonPrimitive(true)
                },
            )
        val atBoundary =
            JsonObject(
                mapOf(
                    "\$defs" to JsonObject(definitions),
                    "prefixItems" to prefixItemsAtBoundary,
                ),
            )

        assertNull(GovernedJsonSchemaSubset.validate(atBoundary))
        assertIssue(
            expectedCode = "schema_node_limit_exceeded",
            issue =
                GovernedJsonSchemaSubset.validate(
                    JsonObject(
                        atBoundary +
                            (
                                "prefixItems" to
                                    JsonArray(
                                        prefixItemsAtBoundary + JsonPrimitive(true),
                                    )
                            ),
                    ),
                ),
        )
    }

    @Test
    fun enforcesEveryDocumentedCollectionBoundary() {
        listOf("\$defs", "properties").forEach { keyword ->
            val atBoundary = schemaMap(size = 256)
            assertNull(
                GovernedJsonSchemaSubset.validate(
                    JsonObject(mapOf(keyword to atBoundary)),
                ),
                keyword,
            )
            assertIssue(
                expectedCode = "schema_collection_limit_exceeded",
                expectedPath = "/$keyword",
                issue =
                    GovernedJsonSchemaSubset.validate(
                        JsonObject(mapOf(keyword to schemaMap(size = 257))),
                    ),
            )
        }

        assertNull(
            GovernedJsonSchemaSubset.validate(
                JsonObject(
                    mapOf(
                        "prefixItems" to JsonArray(List(256) { JsonPrimitive(true) }),
                    ),
                ),
            ),
        )
        assertIssue(
            expectedCode = "schema_collection_limit_exceeded",
            expectedPath = "/prefixItems",
            issue =
                GovernedJsonSchemaSubset.validate(
                    JsonObject(
                        mapOf(
                            "prefixItems" to JsonArray(List(257) { JsonPrimitive(true) }),
                        ),
                    ),
                ),
        )

        assertNull(
            GovernedJsonSchemaSubset.validate(
                JsonObject(mapOf("required" to uniqueStringArray(size = 256))),
            ),
        )
        assertIssue(
            expectedCode = "schema_collection_limit_exceeded",
            expectedPath = "/required",
            issue =
                GovernedJsonSchemaSubset.validate(
                    JsonObject(mapOf("required" to uniqueStringArray(size = 257))),
                ),
        )

        assertNull(
            GovernedJsonSchemaSubset.validate(
                JsonObject(mapOf("enum" to uniqueStringArray(size = 256))),
            ),
        )
        assertIssue(
            expectedCode = "invalid_schema_enum",
            expectedPath = "/enum",
            issue =
                GovernedJsonSchemaSubset.validate(
                    JsonObject(mapOf("enum" to uniqueStringArray(size = 257))),
                ),
        )
    }

    @Test
    fun enforcesEveryCompositionBoundary() {
        listOf("allOf", "anyOf", "oneOf").forEach { keyword ->
            assertNull(
                GovernedJsonSchemaSubset.validate(
                    JsonObject(
                        mapOf(keyword to JsonArray(List(32) { JsonPrimitive(true) })),
                    ),
                ),
                keyword,
            )
            assertIssue(
                expectedCode = "schema_composition_limit_exceeded",
                expectedPath = "/$keyword",
                issue =
                    GovernedJsonSchemaSubset.validate(
                        JsonObject(
                            mapOf(keyword to JsonArray(List(33) { JsonPrimitive(true) })),
                        ),
                    ),
            )
        }
    }

    @Test
    fun enforcesCompactUtf8SizeBoundary() {
        val jsonOverheadBytes = """{"description":""}""".encodeToByteArray().size
        val atBoundary =
            """{"description":"${
                "x".repeat(GovernedJsonSchemaSubset.MAX_SCHEMA_BYTES - jsonOverheadBytes)
            }"}"""
        assertEquals(
            GovernedJsonSchemaSubset.MAX_SCHEMA_BYTES,
            atBoundary.encodeToByteArray().size,
        )
        StructuredOutputSchema.parse(atBoundary)

        val failure = assertFails { StructuredOutputSchema.parse(atBoundary.replace("\"}", "x\"}")) }
        assertEquals(
            "schema_size_limit_exceeded",
            failure.contractSemanticExceptionOrNull()?.code,
        )
        assertEquals("", failure.contractSemanticExceptionOrNull()?.path)
    }

    @Test
    fun rejectsMalformedKeywordShapesWithStablePaths() {
        assertIssueFor(
            document = parse("null"),
            expectedCode = "invalid_schema_node",
            expectedPath = "",
        )
        assertIssueFor(
            document = JsonPrimitive("true"),
            expectedCode = "invalid_schema_node",
            expectedPath = "",
        )
        assertIssueFor(
            document = JsonObject(mapOf("pattern" to JsonPrimitive("[a-z]+"))),
            expectedCode = "unsupported_schema_keyword",
            expectedPath = "/pattern",
        )
        assertIssueFor(
            document = JsonObject(mapOf("\$schema" to JsonPrimitive(false))),
            expectedCode = "unsupported_schema_dialect",
            expectedPath = "/\$schema",
        )
        assertIssueFor(
            document = JsonObject(mapOf("\$ref" to JsonPrimitive(false))),
            expectedCode = "invalid_schema_reference",
            expectedPath = "/\$ref",
        )

        listOf("\$defs", "properties").forEach { keyword ->
            assertIssueFor(
                document = JsonObject(mapOf(keyword to JsonArray(emptyList()))),
                expectedCode = "invalid_schema_keyword",
                expectedPath = "/$keyword",
            )
        }
        assertIssueFor(
            document =
                JsonObject(
                    mapOf(
                        "properties" to
                            JsonObject(mapOf("bad/child" to parse("null"))),
                    ),
                ),
            expectedCode = "invalid_schema_node",
            expectedPath = "/properties/bad~1child",
        )

        listOf("additionalProperties", "items", "not").forEach { keyword ->
            assertIssueFor(
                document = JsonObject(mapOf(keyword to JsonPrimitive("not-a-schema"))),
                expectedCode = "invalid_schema_node",
                expectedPath = "/$keyword",
            )
        }
        assertIssueFor(
            document = JsonObject(mapOf("prefixItems" to JsonObject(emptyMap()))),
            expectedCode = "invalid_schema_keyword",
            expectedPath = "/prefixItems",
        )
        assertIssueFor(
            document =
                JsonObject(
                    mapOf("prefixItems" to JsonArray(listOf(JsonPrimitive("not-a-schema")))),
                ),
            expectedCode = "invalid_schema_node",
            expectedPath = "/prefixItems/0",
        )

        listOf("allOf", "anyOf", "oneOf").forEach { keyword ->
            assertIssueFor(
                document = JsonObject(mapOf(keyword to JsonObject(emptyMap()))),
                expectedCode = "invalid_schema_keyword",
                expectedPath = "/$keyword",
            )
            assertIssueFor(
                document = JsonObject(mapOf(keyword to JsonArray(emptyList()))),
                expectedCode = "schema_composition_limit_exceeded",
                expectedPath = "/$keyword",
            )
        }
        assertIssueFor(
            document =
                JsonObject(
                    mapOf("oneOf" to JsonArray(listOf(JsonPrimitive("not-a-schema")))),
                ),
            expectedCode = "invalid_schema_node",
            expectedPath = "/oneOf/0",
        )

        listOf(
            JsonPrimitive(false),
            JsonArray(emptyList()),
            JsonArray(listOf(JsonPrimitive("future"))),
            JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive(1))),
        ).forEach { malformedType ->
            assertIssueFor(
                document = JsonObject(mapOf("type" to malformedType)),
                expectedCode = "invalid_schema_type",
                expectedPath = "/type",
            )
        }
        assertIssueFor(
            document = JsonObject(mapOf("enum" to JsonObject(emptyMap()))),
            expectedCode = "invalid_schema_keyword",
            expectedPath = "/enum",
        )
        assertIssueFor(
            document = JsonObject(mapOf("enum" to JsonArray(emptyList()))),
            expectedCode = "invalid_schema_enum",
            expectedPath = "/enum",
        )
        assertIssueFor(
            document = JsonObject(mapOf("required" to JsonObject(emptyMap()))),
            expectedCode = "invalid_schema_required",
            expectedPath = "/required",
        )
        assertIssueFor(
            document = JsonObject(mapOf("required" to JsonArray(listOf(JsonPrimitive(1))))),
            expectedCode = "invalid_schema_required",
            expectedPath = "/required",
        )

        listOf(
            "minLength",
            "maxLength",
            "minProperties",
            "maxProperties",
            "minItems",
            "maxItems",
        ).forEach { keyword ->
            assertIssueFor(
                document = JsonObject(mapOf(keyword to JsonPrimitive(-1))),
                expectedCode = "invalid_schema_keyword",
                expectedPath = "/$keyword",
            )
        }
        assertIssueFor(
            document = JsonObject(mapOf("maxItems" to JsonPrimitive(1.5))),
            expectedCode = "invalid_schema_keyword",
            expectedPath = "/maxItems",
        )
        listOf(
            "minimum",
            "exclusiveMinimum",
            "maximum",
            "exclusiveMaximum",
        ).forEach { keyword ->
            assertIssueFor(
                document = JsonObject(mapOf(keyword to JsonPrimitive("not-a-number"))),
                expectedCode = "invalid_schema_keyword",
                expectedPath = "/$keyword",
            )
        }
        listOf("\$comment", "title", "description", "format").forEach { keyword ->
            assertIssueFor(
                document = JsonObject(mapOf(keyword to JsonPrimitive(false))),
                expectedCode = "invalid_schema_keyword",
                expectedPath = "/$keyword",
            )
        }
        assertIssueFor(
            document = JsonObject(mapOf("deprecated" to JsonPrimitive("false"))),
            expectedCode = "invalid_schema_keyword",
            expectedPath = "/deprecated",
        )
        assertIssueFor(
            document = JsonObject(mapOf("examples" to JsonObject(emptyMap()))),
            expectedCode = "invalid_schema_keyword",
            expectedPath = "/examples",
        )
    }

    @Test
    fun reportsRootNestedUnsupportedAndRecursiveReferencePaths() {
        assertIssue(
            expectedCode = "unresolved_schema_reference",
            expectedPath = "/\$ref",
            issue =
                GovernedJsonSchemaSubset.validate(
                    parse("""{"${'$'}ref":"#/${'$'}defs/missing"}"""),
                ),
        )

        val nestedUnresolved =
            parse(
                """
                {
                  "${'$'}defs": {
                    "present": true
                  },
                  "properties": {
                    "answer/value": {
                      "${'$'}ref": "#/${'$'}defs/missing"
                    }
                  }
                }
                """,
            )
        assertIssue(
            expectedCode = "unresolved_schema_reference",
            expectedPath = "/properties/answer~1value/\$ref",
            issue = GovernedJsonSchemaSubset.validate(nestedUnresolved),
        )

        assertIssue(
            expectedCode = "unsupported_schema_reference",
            expectedPath = "/\$ref",
            issue =
                GovernedJsonSchemaSubset.validate(
                    parse("""{"${'$'}ref":"https://example.com/schema"}"""),
                ),
        )
        assertIssue(
            expectedCode = "unsupported_schema_reference",
            expectedPath = "/properties/value/\$ref",
            issue =
                GovernedJsonSchemaSubset.validate(
                    parse(
                        """
                        {
                          "${'$'}defs": {
                            "present": true
                          },
                          "properties": {
                            "value": {
                              "${'$'}ref": "#/${'$'}defs/present/nested"
                            }
                          }
                        }
                        """,
                    ),
                ),
        )
        assertIssue(
            expectedCode = "recursive_schema_not_supported",
            expectedPath = "/\$defs",
            issue =
                GovernedJsonSchemaSubset.validate(
                    parse(
                        """
                        {
                          "${'$'}defs": {
                            "node": {
                              "${'$'}ref": "#/${'$'}defs/node"
                            }
                          },
                          "${'$'}ref": "#/${'$'}defs/node"
                        }
                        """,
                    ),
                ),
        )
    }

    @Test
    fun acceptsEmptyRequiredAndRejectsDuplicateSemanticLists() {
        assertNull(
            GovernedJsonSchemaSubset.validate(
                parse("""{"required":[]}"""),
            ),
        )
        assertIssue(
            expectedCode = "invalid_schema_enum",
            issue =
                GovernedJsonSchemaSubset.validate(
                    parse("""{"enum":[1,1.0]}"""),
                ),
        )
        assertIssue(
            expectedCode = "invalid_schema_required",
            issue =
                GovernedJsonSchemaSubset.validate(
                    parse("""{"required":["value","value"]}"""),
                ),
        )
        assertIssue(
            expectedCode = "invalid_schema_type",
            issue =
                GovernedJsonSchemaSubset.validate(
                    parse("""{"type":["string","string"]}"""),
                ),
        )
    }

    @Test
    fun embeddedRequestRebasesGovernedSchemaFailureToCanonicalPath() {
        val failure =
            assertFails {
                UniversalAiRequest.fromJson(
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "deterministic",
                        "modelId": "echo-v1"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "responseFormat": {
                        "kind": "json_schema",
                        "schema": {
                          "${'$'}defs": {
                            "present": true
                          },
                          "properties": {
                            "answer/value": {
                              "${'$'}ref": "#/${'$'}defs/missing"
                            }
                          }
                        }
                      }
                    }
                    """.trimIndent(),
                )
            }

        assertEquals(
            "unresolved_schema_reference",
            failure.contractSemanticExceptionOrNull()?.code,
        )
        assertEquals(
            "/responseFormat/schema/properties/answer~1value/\$ref",
            failure.contractSemanticExceptionOrNull()?.path,
        )
    }

    private fun nestedSchema(depth: Int): JsonElement {
        var value: JsonElement = JsonObject(emptyMap())
        repeat(depth - 1) {
            value = JsonObject(mapOf("items" to value))
        }
        return value
    }

    private fun schemaMap(size: Int): JsonObject =
        JsonObject(
            (0 until size).associate { index ->
                "schema_$index" to JsonPrimitive(true)
            },
        )

    private fun uniqueStringArray(size: Int): JsonArray =
        JsonArray(
            (0 until size).map { index ->
                JsonPrimitive("value_$index")
            },
        )

    private fun parse(source: String): JsonElement = json.parseToJsonElement(source.trimIndent())

    private fun assertIssueFor(
        document: JsonElement,
        expectedCode: String,
        expectedPath: String,
    ) {
        assertIssue(
            expectedCode = expectedCode,
            expectedPath = expectedPath,
            issue = GovernedJsonSchemaSubset.validate(document),
        )
    }

    private fun assertIssue(
        expectedCode: String,
        expectedPath: String? = null,
        issue: GovernedSchemaIssue?,
    ) {
        assertEquals(expectedCode, issue?.code)
        if (expectedPath != null) {
            assertEquals(expectedPath, issue?.path)
        }
    }
}
