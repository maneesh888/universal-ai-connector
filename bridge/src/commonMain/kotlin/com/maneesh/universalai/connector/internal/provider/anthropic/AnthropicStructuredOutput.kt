package com.maneesh.universalai.connector.internal.provider.anthropic

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
import kotlinx.serialization.json.intOrNull

/**
 * Anthropic's structured-output dialect is narrower than the governed canonical schema subset.
 * Reject unsupported constraints before credential resolution or dispatch instead of weakening
 * caller intent, then validate returned JSON against the original governed schema.
 */
internal object AnthropicStructuredOutput {
    const val MAX_OPTIONAL_PROPERTIES: Int = 24
    const val MAX_UNION_PARAMETERS: Int = 16

    private val supportedKeywords =
        setOf(
            "\$defs",
            "\$ref",
            "title",
            "description",
            "default",
            "format",
            "type",
            "enum",
            "const",
            "properties",
            "required",
            "additionalProperties",
            "items",
            "minItems",
            "anyOf",
            "allOf",
        )

    private val supportedStringFormats =
        setOf(
            "date-time",
            "time",
            "date",
            "duration",
            "email",
            "hostname",
            "uri",
            "ipv4",
            "ipv6",
            "uuid",
        )

    fun isSupported(schema: StructuredOutputSchema): Boolean {
        val root = schema.elementForSerialization() as? JsonObject ?: return false
        val state = CompatibilityState()
        return validateSchema(root, state, insideAllOf = false) &&
            state.optionalProperties <= MAX_OPTIONAL_PROPERTIES &&
            state.unionParameters <= MAX_UNION_PARAMETERS
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
        val root = schema.elementForSerialization() as? JsonObject ?: return null
        val definitions = root["\$defs"] as? JsonObject ?: JsonObject(emptyMap())
        return value.takeIf {
            matches(
                schema = root,
                value = it.elementForSerialization(),
                definitions = definitions,
                resolving = emptySet(),
            )
        }
    }

    private fun validateSchema(
        schema: JsonElement,
        state: CompatibilityState,
        insideAllOf: Boolean,
    ): Boolean {
        val objectSchema = schema as? JsonObject ?: return false
        if (objectSchema.keys.any { keyword -> keyword !in supportedKeywords }) {
            return false
        }
        if (insideAllOf && "\$ref" in objectSchema) {
            return false
        }

        val types = objectSchema.typesOrNull() ?: return false
        val properties = objectSchema["properties"] as? JsonObject
        val required = objectSchema.requiredNamesOrNull() ?: return false
        val usesObjectKeywords =
            properties != null ||
                "required" in objectSchema ||
                "additionalProperties" in objectSchema
        if (usesObjectKeywords || "object" in types) {
            if ("object" !in types) {
                return false
            }
            val objectProperties = properties ?: JsonObject(emptyMap())
            if (required.any { name -> name !in objectProperties }) {
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
            state.optionalProperties += objectProperties.size - required.size
            if (state.optionalProperties > MAX_OPTIONAL_PROPERTIES) {
                return false
            }
        }

        val usesArrayKeywords = "items" in objectSchema || "minItems" in objectSchema
        if ((usesArrayKeywords || "array" in types) && "array" !in types) {
            return false
        }
        (objectSchema["minItems"] as? JsonPrimitive)?.let { minimum ->
            if (minimum.isString || minimum.intOrNull !in 0..1) {
                return false
            }
        }

        (objectSchema["format"] as? JsonPrimitive)?.let { format ->
            if (!format.isString || format.content !in supportedStringFormats) {
                return false
            }
            if ("string" !in types) {
                return false
            }
        }

        (objectSchema["enum"] as? JsonArray)?.let { values ->
            if (values.any { value -> !value.isScalar() }) {
                return false
            }
        }
        objectSchema["const"]?.let { value ->
            if (!value.isScalar()) {
                return false
            }
        }

        val anyOf = objectSchema["anyOf"] as? JsonArray
        val allOf = objectSchema["allOf"] as? JsonArray
        if (("anyOf" in objectSchema && anyOf == null) || ("allOf" in objectSchema && allOf == null)) {
            return false
        }
        if ((anyOf != null && anyOf.isEmpty()) || (allOf != null && allOf.isEmpty())) {
            return false
        }
        if (objectSchema["type"] is JsonArray || anyOf != null) {
            state.unionParameters += 1
            if (state.unionParameters > MAX_UNION_PARAMETERS) {
                return false
            }
        }

        (objectSchema["\$defs"] as? JsonObject)
            ?.values
            ?.forEach { child ->
                if (!validateSchema(child, state, insideAllOf = false)) {
                    return false
                }
            }
        properties
            ?.values
            ?.forEach { child ->
                if (!validateSchema(child, state, insideAllOf = false)) {
                    return false
                }
            }
        objectSchema["items"]?.let { child ->
            if (!validateSchema(child, state, insideAllOf = false)) {
                return false
            }
        }
        anyOf?.forEach { child ->
            if (!validateSchema(child, state, insideAllOf = false)) {
                return false
            }
        }
        allOf?.forEach { child ->
            if (!validateSchema(child, state, insideAllOf = true)) {
                return false
            }
        }
        return true
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
        (objectSchema["anyOf"] as? JsonArray)?.let { branches ->
            if (branches.none { branch -> matches(branch, value, definitions, resolving) }) {
                return false
            }
        }
        (objectSchema["allOf"] as? JsonArray)?.let { branches ->
            if (branches.any { branch -> !matches(branch, value, definitions, resolving) }) {
                return false
            }
        }

        if (value is JsonObject && ("object" in types || "properties" in objectSchema)) {
            val properties = objectSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val required = objectSchema.requiredNamesOrNull() ?: return false
            if (required.any { name -> name !in value } || value.keys.any { name -> name !in properties }) {
                return false
            }
            value.forEach { (name, childValue) ->
                val childSchema = properties[name] ?: return false
                if (!matches(childSchema, childValue, definitions, resolving)) {
                    return false
                }
            }
        }

        if (value is JsonArray && ("array" in types || "items" in objectSchema)) {
            val minimum = (objectSchema["minItems"] as? JsonPrimitive)?.intOrNull ?: 0
            if (value.size < minimum) {
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

    private fun JsonObject.requiredNamesOrNull(): Set<String>? =
        when (val required = this["required"]) {
            null -> emptySet()
            is JsonArray ->
                required
                    .map { element ->
                        (element as? JsonPrimitive)
                            ?.takeIf(JsonPrimitive::isString)
                            ?.content
                            ?: return null
                    }.toSet()

            else -> null
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
                    }.toSet()

            else -> null
        }

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

    private fun JsonElement.isScalar(): Boolean =
        this === JsonNull || this is JsonPrimitive

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
        var optionalProperties: Int = 0
        var unionParameters: Int = 0
    }
}
