package com.maneesh.universalai.connector.internal.provider.openai

import com.maneesh.universalai.connector.contract.StructuredOutputSchema
import com.maneesh.universalai.connector.contract.StructuredOutputValue
import com.maneesh.universalai.connector.contract.json.JsonNumberSemantics
import com.maneesh.universalai.connector.contract.schema.semanticEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The canonical schema contract is intentionally broader than one provider's strict-output
 * dialect. Keep OpenAI compatibility checks internal and fail before credential resolution or
 * dispatch when a governed schema cannot be represented faithfully.
 */
internal object OpenAiStructuredOutput {
    const val MAX_SCHEMA_DEPTH: Int = 10

    private const val MAX_TOTAL_ENUM_VALUES: Int = 1_000
    private const val LARGE_ENUM_VALUE_THRESHOLD: Int = 250
    private const val MAX_LARGE_ENUM_STRING_CHARACTERS: Int = 15_000

    private val supportedKeywords =
        setOf(
            "\$defs",
            "\$ref",
            "title",
            "description",
            "type",
            "enum",
            "const",
            "minimum",
            "exclusiveMinimum",
            "maximum",
            "exclusiveMaximum",
            "properties",
            "required",
            "additionalProperties",
            "items",
            "minItems",
            "maxItems",
            "anyOf",
        )

    fun isSupported(schema: StructuredOutputSchema): Boolean {
        val root = schema.elementForSerialization() as? JsonObject ?: return false
        if (root["anyOf"] != null || root.singleTypeOrNull() != "object") {
            return false
        }

        val state = CompatibilityState()
        if (!validateSchema(root, state)) {
            return false
        }
        if (state.totalEnumValues > MAX_TOTAL_ENUM_VALUES) {
            return false
        }

        val definitions = root["\$defs"] as? JsonObject ?: JsonObject(emptyMap())
        val rootDepth =
            maxSchemaDepth(
                schema = root,
                definitions = definitions,
                currentDepth = 0,
                resolving = emptySet(),
            ) ?: return false
        return rootDepth <= MAX_SCHEMA_DEPTH
    }

    fun parseAndValidate(
        json: String,
        schema: StructuredOutputSchema,
    ): StructuredOutputValue? {
        val value =
            try {
                StructuredOutputValue.parse(json)
            } catch (_: Throwable) {
                return null
            }
        val schemaElement = schema.elementForSerialization() as? JsonObject ?: return null
        val definitions = schemaElement["\$defs"] as? JsonObject ?: JsonObject(emptyMap())
        return value.takeIf {
            matches(
                schema = schemaElement,
                value = it.elementForSerialization(),
                definitions = definitions,
                resolving = emptySet(),
            )
        }
    }

    private fun validateSchema(
        schema: JsonElement,
        state: CompatibilityState,
    ): Boolean {
        val objectSchema = schema as? JsonObject ?: return false
        if (objectSchema.keys.any { keyword -> keyword !in supportedKeywords }) {
            return false
        }

        val properties = objectSchema["properties"] as? JsonObject
        val types = objectSchema.typesOrNull() ?: return false
        if (
            objectSchema.keys.any { keyword -> keyword in NUMBER_BOUND_KEYWORDS } &&
            types.none { type -> type == "number" || type == "integer" }
        ) {
            return false
        }
        if (
            objectSchema.keys.any { keyword ->
                keyword == "items" || keyword == "minItems" || keyword == "maxItems"
            } &&
            "array" !in types
        ) {
            return false
        }
        val usesObjectKeywords =
            properties != null ||
                "required" in objectSchema ||
                "additionalProperties" in objectSchema
        if (usesObjectKeywords || "object" in types) {
            if ("object" !in types) {
                return false
            }
            val objectProperties = properties ?: JsonObject(emptyMap())
            val required = objectSchema["required"] as? JsonArray ?: JsonArray(emptyList())
            val requiredNames =
                required.map { element ->
                    (element as? JsonPrimitive)
                        ?.takeIf(JsonPrimitive::isString)
                        ?.content
                        ?: return false
                }
            if (
                requiredNames.toSet() != objectProperties.keys ||
                requiredNames.size != objectProperties.size
            ) {
                return false
            }
            val additionalProperties = objectSchema["additionalProperties"] as? JsonPrimitive
            if (
                additionalProperties == null ||
                additionalProperties.isString ||
                additionalProperties.booleanOrNull != false
            ) {
                return false
            }
        }

        (objectSchema["enum"] as? JsonArray)?.let { values ->
            state.totalEnumValues += values.size
            if (
                values.size > LARGE_ENUM_VALUE_THRESHOLD &&
                values.sumOf { value ->
                    (value as? JsonPrimitive)
                        ?.takeIf(JsonPrimitive::isString)
                        ?.content
                        ?.length
                        ?: 0
                } > MAX_LARGE_ENUM_STRING_CHARACTERS
            ) {
                return false
            }
        }

        (objectSchema["\$defs"] as? JsonObject)
            ?.values
            ?.forEach { child ->
                if (!validateSchema(child, state)) {
                    return false
                }
            }
        properties
            ?.values
            ?.forEach { child ->
                if (!validateSchema(child, state)) {
                    return false
                }
            }
        objectSchema["items"]?.let { child ->
            if (!validateSchema(child, state)) {
                return false
            }
        }
        (objectSchema["anyOf"] as? JsonArray)
            ?.forEach { child ->
                if (!validateSchema(child, state)) {
                    return false
                }
            }
        return true
    }

    private fun maxSchemaDepth(
        schema: JsonElement,
        definitions: JsonObject,
        currentDepth: Int,
        resolving: Set<String>,
    ): Int? {
        val objectSchema = schema as? JsonObject ?: return null
        var maximum = currentDepth

        (objectSchema["\$ref"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.let { reference ->
                val name = reference.definitionNameOrNull() ?: return null
                if (name in resolving) {
                    return null
                }
                val definition = definitions[name] ?: return null
                maximum =
                    maxOf(
                        maximum,
                        maxSchemaDepth(
                            schema = definition,
                            definitions = definitions,
                            currentDepth = currentDepth,
                            resolving = resolving + name,
                        ) ?: return null,
                    )
            }

        objectSchema.typesOrNull() ?: return null
        val nextDepth = currentDepth + 1
        maximum = maxOf(maximum, nextDepth)
        if (maximum > MAX_SCHEMA_DEPTH) {
            return maximum
        }

        val children =
            buildList {
                (objectSchema["\$defs"] as? JsonObject)?.values?.let(::addAll)
                (objectSchema["properties"] as? JsonObject)?.values?.let(::addAll)
                objectSchema["items"]?.let(::add)
                (objectSchema["anyOf"] as? JsonArray)?.let(::addAll)
            }
        children.forEach { child ->
            maximum =
                maxOf(
                    maximum,
                    maxSchemaDepth(
                        schema = child,
                        definitions = definitions,
                        currentDepth = nextDepth,
                        resolving = resolving,
                    ) ?: return null,
                )
        }
        return maximum
    }

    private fun matches(
        schema: JsonElement,
        value: JsonElement,
        definitions: JsonObject,
        resolving: Set<String>,
    ): Boolean {
        val objectSchema = schema as? JsonObject ?: return false

        (objectSchema["\$ref"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.let { reference ->
                val name = reference.definitionNameOrNull() ?: return false
                if (name in resolving) {
                    return false
                }
                val definition = definitions[name] ?: return false
                if (!matches(definition, value, definitions, resolving + name)) {
                    return false
                }
            }

        val types = objectSchema.typesOrNull() ?: return false
        if (types.isNotEmpty() && types.none { type -> value.matchesType(type) }) {
            return false
        }
        (objectSchema["enum"] as? JsonArray)?.let { values ->
            if (values.none { candidate -> candidate.semanticEquals(value) }) {
                return false
            }
        }
        objectSchema["const"]?.let { constant ->
            if (!constant.semanticEquals(value)) {
                return false
            }
        }
        if (!matchesNumberBounds(objectSchema, value)) {
            return false
        }

        (objectSchema["anyOf"] as? JsonArray)?.let { branches ->
            if (branches.none { branch -> matches(branch, value, definitions, resolving) }) {
                return false
            }
        }

        if (value is JsonObject && "object" in types) {
            val properties = objectSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            if (value.keys != properties.keys) {
                return false
            }
            properties.forEach { (name, childSchema) ->
                val childValue = value[name] ?: return false
                if (!matches(childSchema, childValue, definitions, resolving)) {
                    return false
                }
            }
        }

        if (value is JsonArray && "array" in types) {
            if (!matchesArrayBounds(objectSchema, value)) {
                return false
            }
            objectSchema["items"]?.let { itemSchema ->
                if (value.any { item -> !matches(itemSchema, item, definitions, resolving) }) {
                    return false
                }
            }
        }
        return true
    }

    private fun matchesArrayBounds(
        schema: JsonObject,
        value: JsonArray,
    ): Boolean {
        fun compare(keyword: String): Int? =
            (schema[keyword] as? JsonPrimitive)
                ?.content
                ?.let { bound ->
                    JsonNumberSemantics.compare(value.size.toString(), bound)
                }

        return (compare("minItems")?.let { it >= 0 } ?: true) &&
            (compare("maxItems")?.let { it <= 0 } ?: true)
    }

    private fun matchesNumberBounds(
        schema: JsonObject,
        value: JsonElement,
    ): Boolean {
        val number =
            (value as? JsonPrimitive)
                ?.takeUnless { primitive ->
                    primitive.isString ||
                        primitive.booleanOrNull != null ||
                        primitive === JsonNull
                }
                ?.content
                ?.takeIf(JsonNumberSemantics::isNumber)
                ?: return true

        fun compare(keyword: String): Int? =
            (schema[keyword] as? JsonPrimitive)
                ?.content
                ?.let { bound -> JsonNumberSemantics.compare(number, bound) }

        return (compare("minimum")?.let { it >= 0 } ?: true) &&
            (compare("exclusiveMinimum")?.let { it > 0 } ?: true) &&
            (compare("maximum")?.let { it <= 0 } ?: true) &&
            (compare("exclusiveMaximum")?.let { it < 0 } ?: true)
    }

    private fun JsonObject.typesOrNull(): Set<String>? =
        when (val type = this["type"]) {
            null -> emptySet()
            is JsonPrimitive ->
                type
                    .takeIf(JsonPrimitive::isString)
                    ?.content
                    ?.let(::setOf)

            is JsonArray ->
                type
                    .map { element ->
                        (element as? JsonPrimitive)
                            ?.takeIf(JsonPrimitive::isString)
                            ?.content
                            ?: return null
                    }
                    .toSet()

            else -> null
        }

    private fun JsonObject.singleTypeOrNull(): String? =
        (this["type"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content

    private fun JsonElement.matchesType(type: String): Boolean =
        when (type) {
            "null" -> this === JsonNull
            "boolean" ->
                this is JsonPrimitive &&
                    !isString &&
                    booleanOrNull != null

            "object" -> this is JsonObject
            "array" -> this is JsonArray
            "number" ->
                this is JsonPrimitive &&
                    !isString &&
                    booleanOrNull == null &&
                    JsonNumberSemantics.isNumber(content)

            "integer" ->
                this is JsonPrimitive &&
                    !isString &&
                    booleanOrNull == null &&
                    JsonNumberSemantics.isMathematicalInteger(content)

            "string" -> this is JsonPrimitive && isString
            else -> false
        }

    private fun String.definitionNameOrNull(): String? {
        val prefix = "#/\$defs/"
        if (!startsWith(prefix)) {
            return null
        }
        val encoded = removePrefix(prefix)
        if (encoded.isEmpty() || encoded.contains('/')) {
            return null
        }
        return encoded
            .replace("~1", "/")
            .replace("~0", "~")
    }

    private class CompatibilityState {
        var totalEnumValues: Int = 0
    }

    private val NUMBER_BOUND_KEYWORDS =
        setOf(
            "minimum",
            "exclusiveMinimum",
            "maximum",
            "exclusiveMaximum",
        )
}
