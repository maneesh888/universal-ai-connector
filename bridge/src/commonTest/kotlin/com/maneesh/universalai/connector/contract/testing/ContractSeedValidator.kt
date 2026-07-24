package com.maneesh.universalai.connector.contract.testing

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal data class ContractSeedIssue(
    val layer: ContractSeedLayer,
    val code: String,
    val path: String,
    val keyword: String? = null,
)

/**
 * A deliberately small oracle for the P2-E seed.
 *
 * It validates only the V1 envelope and governed extension bag established by P2-E. Later model
 * packages must add their own schema and semantic validators instead of growing family knowledge
 * into this seed helper.
 */
internal object ContractSeedValidator {
    private const val MAX_NAMESPACES = 16
    private const val MAX_ENCODED_BYTES = 64 * 1024
    private const val MAX_DEPTH = 16
    private const val MAX_NODES = 1024
    private const val MAX_CONTAINER_SIZE = 256
    private const val MAX_MEMBER_NAME_BYTES = 256
    private const val MAX_STRING_BYTES = 16 * 1024
    private const val MAX_NUMBER_TOKEN_BYTES = 128

    private val namespacePattern =
        Regex(
            "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?" +
                "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$",
        )
    private val numberPattern =
        Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    private val json =
        Json {
            isLenient = false
            allowSpecialFloatingPointValues = false
        }

    fun validate(fixture: ContractSeedFixture): ContractSeedIssue? {
        val element =
            try {
                json.parseToJsonElement(fixture.json)
            } catch (_: SerializationException) {
                return ContractSeedIssue(
                    layer = ContractSeedLayer.SCHEMA,
                    code = "invalid_json",
                    path = "",
                )
            }

        return when (fixture.family) {
            ContractSeedFamily.CONTRACT_ENVELOPE -> validateEnvelope(element)
            ContractSeedFamily.EXTENSION_VALUE -> validateExtensionValue(element)
            ContractSeedFamily.EXTENSIONS -> validateExtensions(element)
            else -> error("${fixture.family} is not a P2-E seed family.")
        }
    }

    private fun validateEnvelope(element: JsonElement): ContractSeedIssue? {
        val envelope =
            element as? JsonObject
                ?: return schemaIssue("invalid_contract_envelope", "")
        val version =
            envelope["contractVersion"]
                ?: return schemaIssue(
                    code = "missing_contract_version",
                    path = "",
                    keyword = "required",
                )

        if (version !is JsonPrimitive || !version.isString || version.content != "1") {
            return schemaIssue(
                code = "invalid_contract_version",
                path = "/contractVersion",
                keyword = "const",
            )
        }

        return null
    }

    private fun validateExtensions(element: JsonElement): ContractSeedIssue? {
        val extensions =
            element as? JsonObject
                ?: return schemaIssue("invalid_extensions", "")

        if (extensions.size > MAX_NAMESPACES) {
            return schemaIssue(
                code = "extension_namespace_limit_exceeded",
                path = "",
                keyword = "maxProperties",
            )
        }
        if (json.encodeToString(JsonElement.serializer(), extensions).encodeToByteArray().size >
            MAX_ENCODED_BYTES
        ) {
            return semanticIssue("extension_size_limit_exceeded", "")
        }

        val budget = ExtensionBudget()
        for ((namespace, payloadElement) in extensions) {
            val namespacePath = "/${escapePointerToken(namespace)}"
            if (
                namespace.encodeToByteArray().size > 253 ||
                !namespacePattern.matches(namespace)
            ) {
                return schemaIssue(
                    code = "invalid_extension_namespace",
                    path = "",
                    keyword = "propertyNames",
                )
            }

            val payload =
                payloadElement as? JsonObject
                    ?: return schemaIssue(
                        code = "invalid_extension_payload",
                        path = namespacePath,
                        keyword = "type",
                    )
            validateValue(payload, depth = 1, path = namespacePath, budget = budget)?.let {
                return it
            }
        }

        return null
    }

    private fun validateExtensionValue(element: JsonElement): ContractSeedIssue? =
        validateValue(
            value = element,
            depth = 1,
            path = "",
            budget = ExtensionBudget(),
        )

    private fun validateValue(
        value: JsonElement,
        depth: Int,
        path: String,
        budget: ExtensionBudget,
    ): ContractSeedIssue? {
        if ((value is JsonObject || value is JsonArray) && depth > MAX_DEPTH) {
            return semanticIssue("extension_depth_limit_exceeded", path)
        }
        budget.nodes += 1
        if (budget.nodes > MAX_NODES) {
            return semanticIssue("extension_node_limit_exceeded", path)
        }

        return when (value) {
            is JsonObject -> validateObject(value, depth, path, budget)
            is JsonArray -> validateArray(value, depth, path, budget)
            is JsonPrimitive -> validatePrimitive(value, path)
        }
    }

    private fun validateObject(
        value: JsonObject,
        depth: Int,
        path: String,
        budget: ExtensionBudget,
    ): ContractSeedIssue? {
        if (value.size > MAX_CONTAINER_SIZE) {
            return semanticIssue("extension_object_member_limit_exceeded", path)
        }

        for ((name, child) in value) {
            val childPath = "$path/${escapePointerToken(name)}"
            if (name.isEmpty() || name.any { it.code < 0x20 || it.code == 0x7f }) {
                return semanticIssue("invalid_extension_member_name", childPath)
            }
            if (name.encodeToByteArray().size > MAX_MEMBER_NAME_BYTES) {
                return semanticIssue("extension_member_name_too_long", childPath)
            }
            validateValue(child, depth + 1, childPath, budget)?.let { return it }
        }

        return null
    }

    private fun validateArray(
        value: JsonArray,
        depth: Int,
        path: String,
        budget: ExtensionBudget,
    ): ContractSeedIssue? {
        if (value.size > MAX_CONTAINER_SIZE) {
            return semanticIssue("extension_array_element_limit_exceeded", path)
        }

        value.forEachIndexed { index, child ->
            validateValue(child, depth + 1, "$path/$index", budget)?.let { return it }
        }
        return null
    }

    private fun validatePrimitive(
        value: JsonPrimitive,
        path: String,
    ): ContractSeedIssue? {
        if (value === JsonNull || value.booleanOrNull != null) {
            return null
        }
        if (value.isString) {
            return if (value.content.encodeToByteArray().size > MAX_STRING_BYTES) {
                semanticIssue("extension_string_too_long", path)
            } else {
                null
            }
        }

        val token = value.content
        if (!numberPattern.matches(token)) {
            return semanticIssue("invalid_extension_number", path)
        }
        return if (token.encodeToByteArray().size > MAX_NUMBER_TOKEN_BYTES) {
            semanticIssue("extension_number_token_too_long", path)
        } else {
            null
        }
    }

    private fun schemaIssue(
        code: String,
        path: String,
        keyword: String? = null,
    ) = ContractSeedIssue(ContractSeedLayer.SCHEMA, code, path, keyword)

    private fun semanticIssue(
        code: String,
        path: String,
    ) = ContractSeedIssue(ContractSeedLayer.SEMANTIC, code, path)

    private fun escapePointerToken(value: String): String = value.replace("~", "~0").replace("/", "~1")

    private class ExtensionBudget {
        var nodes: Int = 0
    }
}
