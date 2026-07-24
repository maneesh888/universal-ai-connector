package com.maneesh.universalai.connector.contract.testing

import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.contractSemanticExceptionOrNull
import com.maneesh.universalai.connector.contract.json.CanonicalJson
import com.maneesh.universalai.connector.contract.json.JsonNumberSemantics
import com.maneesh.universalai.connector.contract.schema.GovernedJsonSchemaSubset
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Common request-family oracle for the committed P2-F corpus.
 *
 * Contract-schema failures are classified before request semantics. The JVM tooling remains the
 * authoritative JSON Schema implementation; this helper makes the documented layer and stable
 * code observable on every common-test target without adding JVM-only validator dependencies.
 */
internal object RequestContractFixtureValidator {
    private val providerIdPattern =
        Regex("^[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?$")
    private val tokenPattern = Regex("^[a-z][a-z0-9._-]{0,63}$")

    fun validate(fixture: ContractSeedFixture): ContractSeedIssue? {
        require(fixture.family == ContractSeedFamily.REQUEST)
        val classifiedIssue = classify(fixture.json)
        if (classifiedIssue?.layer == ContractSeedLayer.SCHEMA) {
            return classifiedIssue
        }

        val productionIssue =
            try {
                UniversalAiRequest.fromJson(fixture.json)
                null
            } catch (failure: SerializationException) {
                failure.toSemanticIssueOrDecodeMismatch()
            } catch (failure: IllegalArgumentException) {
                failure.toSemanticIssueOrDecodeMismatch()
            }

        return when {
            classifiedIssue == null && productionIssue == null -> null
            classifiedIssue != null &&
                productionIssue != null &&
                classifiedIssue.code == productionIssue.code &&
                classifiedIssue.path == productionIssue.path -> productionIssue
            classifiedIssue == null && productionIssue != null ->
                semanticIssue(
                    code = "production_request_decode_rejected_valid_fixture",
                    path = productionIssue.path,
                )
            classifiedIssue != null && productionIssue == null ->
                semanticIssue(
                    code = "production_request_decode_accepted_invalid_fixture",
                    path = classifiedIssue.path,
                )
            else ->
                semanticIssue(
                    code = "production_request_semantic_issue_mismatch",
                    path = productionIssue?.path.orEmpty(),
                )
        }
    }

    private fun classify(rawJson: String): ContractSeedIssue? {
        val request =
            try {
                CanonicalJson.parseToElement(rawJson) as? JsonObject
            } catch (_: SerializationException) {
                return schemaIssue("invalid_json", "", null)
            } catch (_: IllegalArgumentException) {
                return schemaIssue("invalid_json", "", null)
            } ?: return schemaIssue("invalid_request", "", "type")

        val contractVersion = request["contractVersion"]
            ?: return schemaIssue("missing_contract_version", "", "required")
        if (
            contractVersion !is JsonPrimitive ||
            !contractVersion.isString ||
            contractVersion.content != ContractSeedFixtures.CONTRACT_VERSION
        ) {
            return schemaIssue(
                code =
                    if (contractVersion is JsonPrimitive && contractVersion.isString) {
                        "unsupported_contract_version"
                    } else {
                        "invalid_contract_version"
                    },
                path = "/contractVersion",
                keyword = "const",
            )
        }

        val targetElement =
            request["target"]
                ?: return schemaIssue("missing_target", "", "required")
        val target =
            targetElement as? JsonObject
                ?: return schemaIssue("invalid_target", "/target", "type")
        val providerId =
            target.requiredString("providerId")
                ?: return schemaIssue("invalid_provider_id", "/target/providerId", "type")
        if (!providerIdPattern.matches(providerId)) {
            return schemaIssue("invalid_provider_id", "/target/providerId", "pattern")
        }
        val modelId =
            target.requiredString("modelId")
                ?: return schemaIssue("invalid_model_id", "/target/modelId", "type")
        if (modelId.isEmpty()) {
            return schemaIssue("invalid_model_id", "/target/modelId", "minLength")
        }
        if (modelId.jsonSchemaCodePointCount() > MAX_MODEL_ID_SCHEMA_CHARACTERS) {
            return schemaIssue("invalid_model_id", "/target/modelId", "maxLength")
        }

        val inputElement =
            request["input"]
                ?: return schemaIssue("missing_input", "", "required")
        val input =
            inputElement as? JsonArray
                ?: return schemaIssue("invalid_input", "/input", "type")
        if (input.isEmpty()) {
            return schemaIssue("empty_input", "/input", "minItems")
        }
        if (input.size > MAX_INPUT_ITEMS) {
            return schemaIssue("input_item_limit_exceeded", "/input", "maxItems")
        }
        val inputObjects = mutableListOf<JsonObject>()
        input.forEachIndexed { index, element ->
            val item =
                element as? JsonObject
                    ?: return schemaIssue("invalid_input_item", "/input/$index", "type")
            val role =
                item.requiredString("role")
                    ?: return schemaIssue(
                        "invalid_input_role",
                        "/input/$index/role",
                        if ("role" in item) "type" else "required",
                    )
            if (!tokenPattern.matches(role)) {
                return schemaIssue("invalid_input_role", "/input/$index/role", "pattern")
            }
            val content =
                item.requiredString("content")
                    ?: return schemaIssue(
                        "invalid_input_content",
                        "/input/$index/content",
                        if ("content" in item) "type" else "required",
                    )
            if (content.isEmpty()) {
                return schemaIssue("invalid_input_content", "/input/$index/content", "minLength")
            }
            if (content.jsonSchemaCodePointCount() > MAX_INPUT_CONTENT_SCHEMA_CHARACTERS) {
                return schemaIssue("invalid_input_content", "/input/$index/content", "maxLength")
            }
            inputObjects += item
        }

        val responseFormat =
            request["responseFormat"]?.let { element ->
                if (element === JsonNull) {
                    return schemaIssue("null_response_format", "/responseFormat", "type")
                }
                val format =
                    element as? JsonObject
                        ?: return schemaIssue("invalid_response_format", "/responseFormat", "type")
                val kind =
                    format.requiredString("kind")
                        ?: return schemaIssue(
                            "invalid_response_format_kind",
                            "/responseFormat/kind",
                            if ("kind" in format) "type" else "required",
                        )
                if (!tokenPattern.matches(kind)) {
                    return schemaIssue(
                        "invalid_response_format_kind",
                        "/responseFormat/kind",
                        "pattern",
                    )
                }
                format["schema"]?.let { schema ->
                    if (schema !is JsonObject && schema.booleanOrNull() == null) {
                        return schemaIssue(
                            "invalid_response_schema",
                            "/responseFormat/schema",
                            "type",
                        )
                    }
                }
                format
            }

        val generation =
            request["generation"]?.let { element ->
                if (element === JsonNull || element !is JsonObject) {
                    return schemaIssue("invalid_generation", "/generation", "type")
                }
                validateGenerationSchema(element)?.let { return it }
                element
            }

        validateSemanticModelId(modelId)?.let { return it }
        inputObjects.forEachIndexed { index, item ->
            val content = checkNotNull(item.requiredString("content"))
            if (content.isBlank()) {
                return semanticIssue("blank_input_content", "/input/$index/content")
            }
        }
        validateResponseFormatSemantics(responseFormat)?.let { return it }
        validateGenerationSemantics(generation)?.let { return it }

        return null
    }

    private fun validateGenerationSchema(generation: JsonObject): ContractSeedIssue? {
        generation["maxOutputTokens"]?.let { element ->
            val token =
                element.numberTokenOrNull()
                    ?: return schemaIssue(
                        "invalid_max_output_tokens",
                        "/generation/maxOutputTokens",
                        "type",
                    )
            if (!JsonNumberSemantics.isMathematicalInteger(token)) {
                return schemaIssue(
                    "invalid_max_output_tokens",
                    "/generation/maxOutputTokens",
                    "type",
                )
            }
            if (checkNotNull(JsonNumberSemantics.compare(token, "1")) < 0) {
                return schemaIssue(
                    "max_output_tokens_out_of_range",
                    "/generation/maxOutputTokens",
                    "minimum",
                )
            }
            if (
                checkNotNull(
                    JsonNumberSemantics.compare(
                        token,
                        MAX_OUTPUT_TOKENS.toString(),
                    ),
                ) > 0
            ) {
                return schemaIssue(
                    "max_output_tokens_out_of_range",
                    "/generation/maxOutputTokens",
                    "maximum",
                )
            }
            val value =
                JsonNumberSemantics.toExactIntOrNull(token)
                    ?: return schemaIssue(
                        "max_output_tokens_out_of_range",
                        "/generation/maxOutputTokens",
                        "maximum",
                    )
            check(value in 1..MAX_OUTPUT_TOKENS)
        }
        generation["temperature"]?.let { element ->
            val token =
                element.numberTokenOrNull()
                    ?: return schemaIssue(
                        "invalid_temperature",
                        "/generation/temperature",
                        "type",
                    )
            if (checkNotNull(JsonNumberSemantics.compare(token, "0")) < 0) {
                return schemaIssue(
                    "temperature_out_of_range",
                    "/generation/temperature",
                    "minimum",
                )
            }
            if (checkNotNull(JsonNumberSemantics.compare(token, "1")) > 0) {
                return schemaIssue(
                    "temperature_out_of_range",
                    "/generation/temperature",
                    "maximum",
                )
            }
        }
        generation["topP"]?.let { element ->
            val token =
                element.numberTokenOrNull()
                    ?: return schemaIssue("invalid_top_p", "/generation/topP", "type")
            if (checkNotNull(JsonNumberSemantics.compare(token, "0")) <= 0) {
                return schemaIssue("top_p_out_of_range", "/generation/topP", "exclusiveMinimum")
            }
            if (checkNotNull(JsonNumberSemantics.compare(token, "1")) > 0) {
                return schemaIssue("top_p_out_of_range", "/generation/topP", "maximum")
            }
        }
        generation["stopSequences"]?.let { element ->
            val values =
                element as? JsonArray
                    ?: return schemaIssue(
                        "invalid_stop_sequences",
                        "/generation/stopSequences",
                        "type",
                    )
            if (values.size > MAX_STOP_SEQUENCES) {
                return schemaIssue(
                    "stop_sequence_limit_exceeded",
                    "/generation/stopSequences",
                    "maxItems",
                )
            }
            values.forEachIndexed { index, value ->
                val stop =
                    value.stringOrNull()
                        ?: return schemaIssue(
                            "invalid_stop_sequence",
                            "/generation/stopSequences/$index",
                            "type",
                        )
                if (stop.isEmpty()) {
                    return schemaIssue(
                        "invalid_stop_sequence",
                        "/generation/stopSequences/$index",
                        "minLength",
                    )
                }
                if (stop.jsonSchemaCodePointCount() > MAX_STOP_SEQUENCE_SCHEMA_CHARACTERS) {
                    return schemaIssue(
                        "invalid_stop_sequence",
                        "/generation/stopSequences/$index",
                        "maxLength",
                    )
                }
            }
        }
        return null
    }

    private fun validateSemanticModelId(modelId: String): ContractSeedIssue? =
        try {
            ModelId.of(modelId)
            null
        } catch (_: IllegalArgumentException) {
            semanticIssue("invalid_model_id", "/target/modelId")
        }

    private fun validateResponseFormatSemantics(
        responseFormat: JsonObject?,
    ): ContractSeedIssue? {
        responseFormat ?: return null
        val kind = checkNotNull(responseFormat.requiredString("kind"))
        val schema = responseFormat["schema"]
        return when (kind) {
            "plain_text" ->
                if (schema != null) {
                    semanticIssue("unexpected_response_schema", "/responseFormat/schema")
                } else {
                    null
                }

            "json_schema" -> {
                if (schema == null) {
                    return semanticIssue("missing_response_schema", "/responseFormat/schema")
                }
                GovernedJsonSchemaSubset.validate(schema)?.let { issue ->
                    semanticIssue(
                        code = issue.code,
                        path = "/responseFormat/schema${issue.path}",
                    )
                }
            }

            else -> null
        }
    }

    private fun validateGenerationSemantics(
        generation: JsonObject?,
    ): ContractSeedIssue? {
        listOf("temperature", "topP").forEach { name ->
            val token =
                generation
                    ?.get(name)
                    ?.numberTokenOrNull()
                    ?: return@forEach
            if (JsonNumberSemantics.toSemanticallyRoundTrippableDoubleOrNull(token) == null) {
                return semanticIssue(
                    code = "generation_number_not_round_trippable",
                    path = "/generation/$name",
                )
            }
        }

        val values =
            generation
                ?.get("stopSequences")
                as? JsonArray
                ?: return null
        val seen = mutableSetOf<String>()
        values.forEachIndexed { index, element ->
            val value = checkNotNull(element.stringOrNull())
            if (!seen.add(value)) {
                return semanticIssue(
                    code = "duplicate_stop_sequence",
                    path = "/generation/stopSequences/$index",
                )
            }
        }
        return null
    }

    private fun JsonObject.requiredString(name: String): String? =
        this[name]?.stringOrNull()

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

    private fun JsonElement.numberTokenOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString || primitive.booleanOrNull != null || primitive === JsonNull) {
            return null
        }
        return primitive.content.takeIf(JsonNumberSemantics::isNumber)
    }

    private fun JsonElement.booleanOrNull(): Boolean? =
        (this as? JsonPrimitive)?.booleanOrNull

    private fun schemaIssue(
        code: String,
        path: String,
        keyword: String?,
    ) = ContractSeedIssue(
        layer = ContractSeedLayer.SCHEMA,
        code = code,
        path = path,
        keyword = keyword,
    )

    private fun semanticIssue(
        code: String,
        path: String,
    ) = ContractSeedIssue(
        layer = ContractSeedLayer.SEMANTIC,
        code = code,
        path = path,
    )

    private fun Throwable.toSemanticIssueOrDecodeMismatch(): ContractSeedIssue {
        val semanticFailure = contractSemanticExceptionOrNull()
        return if (semanticFailure == null) {
            semanticIssue(
                code = "production_request_decode_failed_without_semantic_issue",
                path = "",
            )
        } else {
            semanticIssue(
                code = semanticFailure.code,
                path = semanticFailure.path,
            )
        }
    }

    private const val MAX_MODEL_ID_SCHEMA_CHARACTERS: Int = 256
    private const val MAX_INPUT_ITEMS: Int = 128
    private const val MAX_INPUT_CONTENT_SCHEMA_CHARACTERS: Int = 262_144
    private const val MAX_OUTPUT_TOKENS: Int = 1_048_576
    private const val MAX_STOP_SEQUENCES: Int = 16
    private const val MAX_STOP_SEQUENCE_SCHEMA_CHARACTERS: Int = 1_024
}
