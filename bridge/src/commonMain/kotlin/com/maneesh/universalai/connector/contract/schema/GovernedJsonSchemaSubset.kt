package com.maneesh.universalai.connector.contract.schema

import com.maneesh.universalai.connector.contract.json.JsonNumberSemantics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal data class GovernedSchemaIssue(
    val code: String,
    val path: String,
)

/**
 * Structural policy for untrusted structured-output schemas.
 *
 * This is deliberately separate from the schemas that describe connector contracts. It validates
 * the bounded Draft 2020-12 subset that callers may place inside a canonical request; it is not a
 * general JSON Schema evaluator.
 */
internal object GovernedJsonSchemaSubset {
    const val MAX_SCHEMA_BYTES: Int = 65_536
    const val MAX_SCHEMA_DEPTH: Int = 32
    const val MAX_SCHEMA_NODES: Int = 512

    private const val DIALECT = "https://json-schema.org/draft/2020-12/schema"
    private const val MAX_COLLECTION_SIZE = 256
    private const val MAX_COMPOSITION_BRANCHES = 32

    private val allowedKeywords =
        setOf(
            "\$schema",
            "\$defs",
            "\$ref",
            "\$comment",
            "title",
            "description",
            "default",
            "deprecated",
            "examples",
            "format",
            "type",
            "enum",
            "const",
            "minimum",
            "exclusiveMinimum",
            "maximum",
            "exclusiveMaximum",
            "minLength",
            "maxLength",
            "properties",
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
    private val schemaMapKeywords = setOf("\$defs", "properties")
    private val singleSchemaKeywords = setOf("additionalProperties", "items", "not")
    private val compositionKeywords = setOf("allOf", "anyOf", "oneOf")
    private val nonNegativeIntegerKeywords =
        setOf(
            "minLength",
            "maxLength",
            "minProperties",
            "maxProperties",
            "minItems",
            "maxItems",
        )
    private val numberKeywords =
        setOf("minimum", "exclusiveMinimum", "maximum", "exclusiveMaximum")
    private val annotationStringKeywords =
        setOf("\$comment", "title", "description", "format")
    private val allowedTypes =
        setOf("null", "boolean", "object", "array", "number", "integer", "string")

    fun validate(document: JsonElement): GovernedSchemaIssue? {
        val state = TraversalState(root = document as? JsonObject)
        validateSchema(document, "", depth = 1, state = state)?.let { return it }
        return validateReferences(state)
    }

    private fun validateSchema(
        schema: JsonElement,
        path: String,
        depth: Int,
        state: TraversalState,
    ): GovernedSchemaIssue? {
        if (depth > MAX_SCHEMA_DEPTH) {
            return issue("schema_depth_limit_exceeded", path)
        }
        state.nodes += 1
        if (state.nodes > MAX_SCHEMA_NODES) {
            return issue("schema_node_limit_exceeded", path)
        }

        if (schema is JsonPrimitive && !schema.isString && schema.booleanOrNull != null) {
            return null
        }
        val schemaObject = schema as? JsonObject ?: return issue("invalid_schema_node", path)

        for ((keyword, value) in schemaObject) {
            val keywordPath = "$path/${escapePointerToken(keyword)}"
            if (keyword !in allowedKeywords) {
                return issue("unsupported_schema_keyword", keywordPath)
            }
            when {
                keyword == "\$schema" -> {
                    if (value !is JsonPrimitive || !value.isString || value.content != DIALECT) {
                        return issue("unsupported_schema_dialect", keywordPath)
                    }
                }

                keyword == "\$ref" -> {
                    if (value !is JsonPrimitive || !value.isString) {
                        return issue("invalid_schema_reference", keywordPath)
                    }
                    if (!value.content.startsWith("#/\$defs/")) {
                        return issue("unsupported_schema_reference", keywordPath)
                    }
                    state.references += SchemaReference(keywordPath, value.content)
                }

                keyword in schemaMapKeywords -> {
                    val schemas =
                        value as? JsonObject
                            ?: return issue("invalid_schema_keyword", keywordPath)
                    if (schemas.size > MAX_COLLECTION_SIZE) {
                        return issue("schema_collection_limit_exceeded", keywordPath)
                    }
                    for ((name, child) in schemas) {
                        validateSchema(
                            child,
                            "$keywordPath/${escapePointerToken(name)}",
                            depth + 1,
                            state,
                        )?.let { return it }
                    }
                }

                keyword in singleSchemaKeywords -> {
                    validateSchema(value, keywordPath, depth + 1, state)?.let { return it }
                }

                keyword == "prefixItems" -> {
                    val schemas =
                        value as? JsonArray
                            ?: return issue("invalid_schema_keyword", keywordPath)
                    if (schemas.size > MAX_COLLECTION_SIZE) {
                        return issue("schema_collection_limit_exceeded", keywordPath)
                    }
                    schemas.forEachIndexed { index, child ->
                        validateSchema(child, "$keywordPath/$index", depth + 1, state)?.let {
                            return it
                        }
                    }
                }

                keyword in compositionKeywords -> {
                    val schemas =
                        value as? JsonArray
                            ?: return issue("invalid_schema_keyword", keywordPath)
                    if (schemas.isEmpty() || schemas.size > MAX_COMPOSITION_BRANCHES) {
                        return issue("schema_composition_limit_exceeded", keywordPath)
                    }
                    schemas.forEachIndexed { index, child ->
                        validateSchema(child, "$keywordPath/$index", depth + 1, state)?.let {
                            return it
                        }
                    }
                }

                keyword == "type" -> {
                    validateType(value, keywordPath)?.let { return it }
                }

                keyword == "enum" -> {
                    val values =
                        value as? JsonArray
                            ?: return issue("invalid_schema_keyword", keywordPath)
                    if (
                        values.isEmpty() ||
                        values.size > MAX_COLLECTION_SIZE ||
                        values.hasSemanticDuplicates()
                    ) {
                        return issue("invalid_schema_enum", keywordPath)
                    }
                }

                keyword == "required" -> {
                    validateRequired(value, keywordPath)?.let { return it }
                }

                keyword in nonNegativeIntegerKeywords -> {
                    if (!value.isNonNegativeInteger()) {
                        return issue("invalid_schema_keyword", keywordPath)
                    }
                }

                keyword in numberKeywords -> {
                    if (!value.isNumber()) {
                        return issue("invalid_schema_keyword", keywordPath)
                    }
                }

                keyword in annotationStringKeywords -> {
                    if (value !is JsonPrimitive || !value.isString) {
                        return issue("invalid_schema_keyword", keywordPath)
                    }
                }

                keyword == "deprecated" -> {
                    if (value !is JsonPrimitive || value.isString || value.booleanOrNull == null) {
                        return issue("invalid_schema_keyword", keywordPath)
                    }
                }

                keyword == "examples" -> {
                    if (value !is JsonArray) {
                        return issue("invalid_schema_keyword", keywordPath)
                    }
                }
            }
        }

        return null
    }

    private fun validateType(
        value: JsonElement,
        path: String,
    ): GovernedSchemaIssue? {
        val rawTypes =
            when (value) {
                is JsonPrimitive ->
                    if (value.isString) {
                        listOf(value.content)
                    } else {
                        return issue("invalid_schema_type", path)
                    }

                is JsonArray ->
                    value.map {
                        if (it is JsonPrimitive && it.isString) {
                            it.content
                        } else {
                            return issue("invalid_schema_type", path)
                        }
                    }

                else -> return issue("invalid_schema_type", path)
            }

        return if (
            rawTypes.isEmpty() ||
            rawTypes.size != rawTypes.toSet().size ||
            rawTypes.any { it !in allowedTypes }
        ) {
            issue("invalid_schema_type", path)
        } else {
            null
        }
    }

    private fun validateRequired(
        value: JsonElement,
        path: String,
    ): GovernedSchemaIssue? {
        val names = value as? JsonArray ?: return issue("invalid_schema_required", path)
        if (names.size > MAX_COLLECTION_SIZE) {
            return issue("schema_collection_limit_exceeded", path)
        }
        val rawNames =
            names.map {
                if (it is JsonPrimitive && it.isString) {
                    it.content
                } else {
                    return issue("invalid_schema_required", path)
                }
            }
        return if (rawNames.size != rawNames.toSet().size) {
            issue("invalid_schema_required", path)
        } else {
            null
        }
    }

    private fun validateReferences(state: TraversalState): GovernedSchemaIssue? {
        val definitions = state.root?.get("\$defs") as? JsonObject ?: JsonObject(emptyMap())
        val graph =
            definitions.keys.associateWith { name ->
                val references = mutableSetOf<String>()
                collectReferences(definitions.getValue(name), references)
                references
            }

        for (reference in state.references) {
            val name =
                decodeDefinitionName(reference.raw)
                    ?: return issue("unsupported_schema_reference", reference.path)
            if (name !in definitions) {
                return issue("unresolved_schema_reference", reference.path)
            }
        }

        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun hasCycle(name: String): Boolean {
            if (name in visiting) return true
            if (name in visited) return false
            visiting += name
            val cycle = graph[name].orEmpty().any { target -> target in graph && hasCycle(target) }
            visiting -= name
            visited += name
            return cycle
        }
        if (graph.keys.any(::hasCycle)) {
            return issue("recursive_schema_not_supported", "/\$defs")
        }

        return null
    }

    private fun collectReferences(
        element: JsonElement,
        output: MutableSet<String>,
    ) {
        if (element is JsonPrimitive && !element.isString && element.booleanOrNull != null) {
            return
        }
        val schema = element as? JsonObject ?: return
        (schema["\$ref"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.let(::decodeDefinitionName)
            ?.let(output::add)
        schemaMapKeywords.forEach { keyword ->
            (schema[keyword] as? JsonObject)
                ?.values
                ?.forEach { collectReferences(it, output) }
        }
        singleSchemaKeywords.forEach { keyword ->
            schema[keyword]?.let { collectReferences(it, output) }
        }
        (schema["prefixItems"] as? JsonArray)?.forEach { collectReferences(it, output) }
        compositionKeywords.forEach { keyword ->
            (schema[keyword] as? JsonArray)?.forEach { collectReferences(it, output) }
        }
    }

    private fun decodeDefinitionName(reference: String): String? {
        val encoded = reference.removePrefix("#/\$defs/")
        if (
            encoded == reference ||
            encoded.isEmpty() ||
            "/" in encoded ||
            "#" in encoded ||
            "%" in encoded
        ) {
            return null
        }

        return buildString(encoded.length) {
            var index = 0
            while (index < encoded.length) {
                when (val character = encoded[index]) {
                    '~' -> {
                        if (index + 1 >= encoded.length) {
                            return null
                        }
                        when (encoded[index + 1]) {
                            '0' -> append('~')
                            '1' -> append('/')
                            else -> return null
                        }
                        index += 2
                    }

                    else -> {
                        append(character)
                        index += 1
                    }
                }
            }
        }
    }

    private fun JsonElement.isNonNegativeInteger(): Boolean {
        val primitive = this as? JsonPrimitive ?: return false
        if (primitive.isString || primitive.booleanOrNull != null) return false
        val normalized = JsonNumberSemantics.normalize(primitive.content) ?: return false
        return !normalized.startsWith('-') &&
            JsonNumberSemantics.isMathematicalInteger(primitive.content)
    }

    private fun JsonElement.isNumber(): Boolean {
        val primitive = this as? JsonPrimitive ?: return false
        if (primitive.isString || primitive.booleanOrNull != null) return false
        return primitive.content.matches(
            Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"),
        )
    }

    private fun issue(
        code: String,
        path: String,
    ) = GovernedSchemaIssue(code, path)

    private fun JsonArray.hasSemanticDuplicates(): Boolean =
        indices.any { currentIndex ->
            (0 until currentIndex).any { previousIndex ->
                this[currentIndex].semanticEquals(this[previousIndex])
            }
        }

    private fun escapePointerToken(value: String): String = value.replace("~", "~0").replace("/", "~1")

    private data class SchemaReference(
        val path: String,
        val raw: String,
    )

    private class TraversalState(
        val root: JsonObject?,
    ) {
        var nodes: Int = 0
        val references: MutableList<SchemaReference> = mutableListOf()
    }
}
