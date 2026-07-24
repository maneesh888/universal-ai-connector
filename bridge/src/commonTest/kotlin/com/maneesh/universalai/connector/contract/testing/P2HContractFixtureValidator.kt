package com.maneesh.universalai.connector.contract.testing

import com.maneesh.universalai.connector.contract.UniversalAiCapabilitySet
import com.maneesh.universalai.connector.contract.UniversalAiModelDescriptor
import com.maneesh.universalai.connector.contract.UniversalAiModelTokenLimits
import com.maneesh.universalai.connector.contract.UniversalAiProviderCapabilityProfile
import com.maneesh.universalai.connector.contract.contractSemanticExceptionOrNull
import com.maneesh.universalai.connector.contract.json.CanonicalJson
import com.maneesh.universalai.connector.contract.json.JsonNumberSemantics
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Common oracle for P2-H capability, profile, model-limit, and descriptor fixtures.
 *
 * The JVM repository harness remains authoritative for Draft 2020-12 validation. This classifier
 * independently identifies the committed schema-negative cases on every common-test target, then
 * requires every schema-valid fixture to agree with the production codecs on semantic code and
 * JSON Pointer.
 */
internal object P2HContractFixtureValidator {
    private val capabilityTokenPattern = Regex("^[a-z][a-z0-9._-]{0,63}$")
    private val providerIdPattern =
        Regex("^[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?$")

    fun validate(fixture: ContractSeedFixture): ContractSeedIssue? {
        require(fixture.family.isP2HFamily)
        val classifiedIssue = classify(fixture)
        if (classifiedIssue?.layer == ContractSeedLayer.SCHEMA) {
            return classifiedIssue
        }

        val productionIssue =
            try {
                productionRoundTrip(fixture)
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
                    code = "production_p2h_decode_rejected_valid_fixture",
                    path = productionIssue.path,
                )
            classifiedIssue != null && productionIssue == null ->
                semanticIssue(
                    code = "production_p2h_decode_accepted_invalid_fixture",
                    path = classifiedIssue.path,
                )
            else ->
                semanticIssue(
                    code = "production_p2h_semantic_issue_mismatch",
                    path = productionIssue?.path.orEmpty(),
                )
        }
    }

    fun productionRoundTrip(fixture: ContractSeedFixture): String {
        require(fixture.family.isP2HFamily)
        return when (fixture.family) {
            ContractSeedFamily.CAPABILITY_SET ->
                transcode(UniversalAiCapabilitySet.serializer(), fixture.json)
            ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE ->
                UniversalAiProviderCapabilityProfile.fromJson(fixture.json).toJson()
            ContractSeedFamily.MODEL_TOKEN_LIMITS ->
                transcode(UniversalAiModelTokenLimits.serializer(), fixture.json)
            ContractSeedFamily.MODEL_DESCRIPTOR ->
                UniversalAiModelDescriptor.fromJson(fixture.json).toJson()
            else -> error("${fixture.family} is not a P2-H fixture family.")
        }
    }

    private fun classify(fixture: ContractSeedFixture): ContractSeedIssue? {
        val element =
            try {
                CanonicalJson.parseToElement(fixture.json)
            } catch (_: SerializationException) {
                return schemaIssue("invalid_json", "", null)
            } catch (_: IllegalArgumentException) {
                return schemaIssue("invalid_json", "", null)
            }

        return when (fixture.family) {
            ContractSeedFamily.CAPABILITY_SET ->
                validateCapabilitySet(element, path = "")
            ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE ->
                validateProviderCapabilityProfile(element)
            ContractSeedFamily.MODEL_TOKEN_LIMITS ->
                validateModelTokenLimits(element, path = "")
            ContractSeedFamily.MODEL_DESCRIPTOR ->
                validateModelDescriptor(element)
            else -> error("${fixture.family} is not a P2-H fixture family.")
        }
    }

    private fun validateCapabilitySet(
        element: JsonElement,
        path: String,
    ): ContractSeedIssue? {
        val capabilities =
            element as? JsonObject
                ?: return schemaIssue("invalid_capabilities", path, "type")
        if (capabilities.size > MAX_CAPABILITIES) {
            return schemaIssue("capability_count_exceeded", path, "maxProperties")
        }

        capabilities.forEach { (rawName, declarationElement) ->
            if (!capabilityTokenPattern.matches(rawName)) {
                return schemaIssue("invalid_capability_name", path, "propertyNames")
            }
            val capabilityPath = "$path/${rawName.escapePointerToken()}"
            val declaration =
                declarationElement as? JsonObject
                    ?: return schemaIssue(
                        "invalid_capability_declaration",
                        capabilityPath,
                        "type",
                    )
            val support =
                declaration["support"]
                    ?: return schemaIssue(
                        "missing_capability_support",
                        capabilityPath,
                        "required",
                    )
            val rawSupport =
                support.stringOrNull()
                    ?: return schemaIssue(
                        "invalid_capability_support",
                        "$capabilityPath/support",
                        "type",
                    )
            if (!capabilityTokenPattern.matches(rawSupport)) {
                return schemaIssue(
                    "invalid_capability_support",
                    "$capabilityPath/support",
                    "pattern",
                )
            }

            val limits = declaration["limits"]
            if (limits != null) {
                val limitObject =
                    limits as? JsonObject
                        ?: return schemaIssue(
                            "invalid_capability_limits",
                            "$capabilityPath/limits",
                            "type",
                        )
                if (limitObject.size > MAX_CAPABILITY_LIMITS) {
                    return schemaIssue(
                        "capability_limit_count_exceeded",
                        "$capabilityPath/limits",
                        "maxProperties",
                    )
                }
                limitObject.forEach { (rawLimitName, value) ->
                    if (!capabilityTokenPattern.matches(rawLimitName)) {
                        return schemaIssue(
                            "invalid_capability_limit_name",
                            "$capabilityPath/limits",
                            "propertyNames",
                        )
                    }
                    val limitPath =
                        "$capabilityPath/limits/${rawLimitName.escapePointerToken()}"
                    integerSchemaIssue(
                        element = value,
                        path = limitPath,
                        code = "capability_limit_out_of_range",
                        minimum = "0",
                        maximum = MAX_JSON_SAFE_INTEGER,
                    )?.let { return it }
                }

                listOf("max_schema_bytes", "max_schema_depth").forEach { knownName ->
                    val token = limitObject[knownName]?.numberTokenOrNull() ?: return@forEach
                    if (JsonNumberSemantics.compare(token, "0") == 0) {
                        return semanticIssue(
                            "capability_limit_out_of_range",
                            "$capabilityPath/limits/$knownName",
                        )
                    }
                }
                if (rawSupport == "unsupported" && limitObject.isNotEmpty()) {
                    return semanticIssue(
                        "unsupported_capability_limits",
                        "$capabilityPath/limits",
                    )
                }
                if (rawName != "structured_output") {
                    listOf("max_schema_bytes", "max_schema_depth").forEach { knownName ->
                        if (knownName in limitObject) {
                            return semanticIssue(
                                "capability_limit_not_applicable",
                                "$capabilityPath/limits/$knownName",
                            )
                        }
                    }
                }
            }

            declaration["extensions"]?.let { extensions ->
                if (extensions !is JsonObject) {
                    return schemaIssue(
                        "invalid_extensions",
                        "$capabilityPath/extensions",
                        "type",
                    )
                }
            }
        }
        return null
    }

    private fun validateProviderCapabilityProfile(element: JsonElement): ContractSeedIssue? {
        val profile =
            element as? JsonObject
                ?: return schemaIssue("invalid_provider_capability_profile", "", "type")
        validateVersionedDocument(profile)?.let { return it }

        val providerId =
            profile["providerId"]
                ?: return schemaIssue("missing_provider_id", "", "required")
        val rawProviderId =
            providerId.stringOrNull()
                ?: return schemaIssue("invalid_provider_id", "/providerId", "type")
        if (rawProviderId.isEmpty()) {
            return schemaIssue("invalid_provider_id", "/providerId", "minLength")
        }
        if (rawProviderId.jsonSchemaCodePointCount() > MAX_PROVIDER_ID_CHARACTERS) {
            return schemaIssue("invalid_provider_id", "/providerId", "maxLength")
        }
        if (!providerIdPattern.matches(rawProviderId)) {
            return schemaIssue("invalid_provider_id", "/providerId", "pattern")
        }

        val capabilities =
            profile["capabilities"]
                ?: return schemaIssue("missing_capabilities", "", "required")
        validateCapabilitySet(capabilities, "/capabilities")?.let { return it }
        profile["extensions"]?.let { extensions ->
            if (extensions !is JsonObject) {
                return schemaIssue("invalid_extensions", "/extensions", "type")
            }
        }
        return null
    }

    private fun validateModelTokenLimits(
        element: JsonElement,
        path: String,
    ): ContractSeedIssue? {
        val limits =
            element as? JsonObject
                ?: return schemaIssue("invalid_model_limits", path, "type")

        val knownMaximums =
            listOf(
                "contextWindowTokens" to MAX_JSON_SAFE_INTEGER,
                "maxInputTokens" to MAX_JSON_SAFE_INTEGER,
                "maxOutputTokens" to MAX_MODEL_OUTPUT_TOKENS,
            )
        knownMaximums.forEach { (name, maximum) ->
            val value = limits[name] ?: return@forEach
            integerSchemaIssue(
                element = value,
                path = "$path/$name",
                code =
                    if (value.numberTokenOrNull() == null) {
                        "invalid_model_token_limit"
                    } else {
                        "model_token_limit_out_of_range"
                    },
                minimum = "1",
                maximum = maximum,
            )?.let { return it }
        }

        val context = limits["contextWindowTokens"]?.numberTokenOrNull()
        val input = limits["maxInputTokens"]?.numberTokenOrNull()
        val output = limits["maxOutputTokens"]?.numberTokenOrNull()
        if (
            context != null &&
            input != null &&
            JsonNumberSemantics.compare(input, context)?.let { comparison -> comparison > 0 } == true
        ) {
            return semanticIssue(
                "model_input_limit_exceeds_context",
                "$path/maxInputTokens",
            )
        }
        if (
            context != null &&
            output != null &&
            JsonNumberSemantics.compare(output, context)?.let { comparison -> comparison > 0 } == true
        ) {
            return semanticIssue(
                "model_output_limit_exceeds_context",
                "$path/maxOutputTokens",
            )
        }
        return null
    }

    private fun validateModelDescriptor(element: JsonElement): ContractSeedIssue? {
        val descriptor =
            element as? JsonObject
                ?: return schemaIssue("invalid_model_descriptor", "", "type")
        validateVersionedDocument(descriptor)?.let { return it }

        val target =
            descriptor["target"]
                ?: return schemaIssue("missing_target", "", "required")
        validateTarget(target)?.let { return it }

        descriptor["displayName"]?.let { displayElement ->
            val displayName =
                displayElement.stringOrNull()
                    ?: return schemaIssue(
                        "invalid_model_display_name",
                        "/displayName",
                        "type",
                    )
            if (displayName.isEmpty()) {
                return schemaIssue(
                    "invalid_model_display_name",
                    "/displayName",
                    "minLength",
                )
            }
            if (displayName.jsonSchemaCodePointCount() > MAX_MODEL_DISPLAY_NAME_CHARACTERS) {
                return schemaIssue(
                    "invalid_model_display_name",
                    "/displayName",
                    "maxLength",
                )
            }
            if (
                displayName.isBlank() ||
                displayName.any { character ->
                    character.code < 0x20 || character.code == 0x7f
                }
            ) {
                return semanticIssue("invalid_model_display_name", "/displayName")
            }
            if (displayName.encodeToByteArray().size > MAX_MODEL_DISPLAY_NAME_BYTES) {
                return semanticIssue("model_display_name_too_large", "/displayName")
            }
        }

        descriptor["limits"]?.let { limits ->
            validateModelTokenLimits(limits, "/limits")?.let { return it }
        }
        val capabilities =
            descriptor["capabilities"]
                ?: return schemaIssue("missing_capabilities", "", "required")
        validateCapabilitySet(capabilities, "/capabilities")?.let { return it }
        descriptor["extensions"]?.let { extensions ->
            if (extensions !is JsonObject) {
                return schemaIssue("invalid_extensions", "/extensions", "type")
            }
        }
        return null
    }

    private fun validateTarget(element: JsonElement): ContractSeedIssue? {
        val target =
            element as? JsonObject
                ?: return schemaIssue("invalid_model_target", "/target", "type")
        if ("providerId" !in target) {
            return schemaIssue("missing_provider_id", "/target", "required")
        }
        if ("modelId" !in target) {
            return schemaIssue("missing_model_id", "/target", "required")
        }
        if (target["providerId"]?.stringOrNull() == null) {
            return schemaIssue("invalid_provider_id", "/target/providerId", "type")
        }
        if (target["modelId"]?.stringOrNull() == null) {
            return schemaIssue("invalid_model_id", "/target/modelId", "type")
        }
        return null
    }

    private fun validateVersionedDocument(document: JsonObject): ContractSeedIssue? {
        val version =
            document["contractVersion"]
                ?: return schemaIssue("missing_contract_version", "", "required")
        return if (
            version !is JsonPrimitive ||
            !version.isString ||
            version.content != ContractSeedFixtures.CONTRACT_VERSION
        ) {
            schemaIssue(
                code =
                    if (version is JsonPrimitive && version.isString) {
                        "unsupported_contract_version"
                    } else {
                        "invalid_contract_version"
                    },
                path = "/contractVersion",
                keyword = "const",
            )
        } else {
            null
        }
    }

    private fun integerSchemaIssue(
        element: JsonElement,
        path: String,
        code: String,
        minimum: String,
        maximum: String,
    ): ContractSeedIssue? {
        val token =
            element.numberTokenOrNull()
                ?: return schemaIssue(code, path, "type")
        if (!JsonNumberSemantics.isMathematicalInteger(token)) {
            return schemaIssue(code, path, "type")
        }
        if (
            JsonNumberSemantics.compare(token, minimum)
                ?.let { comparison -> comparison < 0 } == true
        ) {
            return schemaIssue(code, path, "minimum")
        }
        if (
            JsonNumberSemantics.compare(token, maximum)
                ?.let { comparison -> comparison > 0 } == true
        ) {
            return schemaIssue(code, path, "maximum")
        }
        return null
    }

    private fun Throwable.toSemanticIssueOrDecodeMismatch(): ContractSeedIssue {
        val issue = contractSemanticExceptionOrNull()
        return if (issue == null) {
            semanticIssue(
                code = "production_p2h_decode_failed_without_semantic_issue",
                path = "",
            )
        } else {
            semanticIssue(issue.code, issue.path)
        }
    }

    private fun <T> transcode(
        serializer: KSerializer<T>,
        rawJson: String,
    ): String {
        val decoded = CanonicalJson.decode(serializer, rawJson)
        return CanonicalJson.encode(serializer, decoded)
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)
            ?.takeIf { primitive -> primitive.isString }
            ?.content

    private fun JsonElement.numberTokenOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive === JsonNull || primitive.isString || primitive.booleanOrNull != null) {
            return null
        }
        return primitive.content.takeIf(JsonNumberSemantics::isNumber)
    }

    private fun String.escapePointerToken(): String =
        replace("~", "~0").replace("/", "~1")

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

    private const val MAX_CAPABILITIES: Int = 64
    private const val MAX_CAPABILITY_LIMITS: Int = 64
    private const val MAX_PROVIDER_ID_CHARACTERS: Int = 64
    private const val MAX_MODEL_DISPLAY_NAME_CHARACTERS: Int = 256
    private const val MAX_MODEL_DISPLAY_NAME_BYTES: Int = 256
    private const val MAX_JSON_SAFE_INTEGER: String = "9007199254740991"
    private const val MAX_MODEL_OUTPUT_TOKENS: String = "1048576"
}

internal val ContractSeedFamily.isP2HFamily: Boolean
    get() =
        when (this) {
            ContractSeedFamily.CAPABILITY_SET,
            ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE,
            ContractSeedFamily.MODEL_TOKEN_LIMITS,
            ContractSeedFamily.MODEL_DESCRIPTOR,
            -> true
            else -> false
        }
