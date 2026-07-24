package com.maneesh.universalai.connector.contract.testing

import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiGenerationParameters
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormat
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.contract.contractSemanticExceptionOrNull
import com.maneesh.universalai.connector.contract.json.CanonicalJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Direct component-family oracle for the committed P2-F corpus.
 *
 * The JVM harness remains authoritative for JSON Schema. This common helper classifies the direct
 * schema-negative component cases and requires every schema-valid decode to either succeed or
 * expose a structured production semantic code and JSON Pointer.
 */
internal object ComponentContractFixtureValidator {
    private val providerIdPattern =
        Regex("^[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?$")

    fun validate(fixture: ContractSeedFixture): ContractSeedIssue? {
        require(fixture.family.isComponentFamily)
        classifySchema(fixture)?.let { return it }

        return try {
            productionRoundTrip(fixture)
            null
        } catch (failure: SerializationException) {
            failure.toSemanticIssueOrDecodeMismatch()
        } catch (failure: IllegalArgumentException) {
            failure.toSemanticIssueOrDecodeMismatch()
        }
    }

    fun productionRoundTrip(fixture: ContractSeedFixture): String {
        require(fixture.family.isComponentFamily)
        return when (fixture.family) {
            ContractSeedFamily.PROVIDER_ID ->
                transcode(ProviderId.serializer(), fixture.json)
            ContractSeedFamily.MODEL_ID ->
                transcode(ModelId.serializer(), fixture.json)
            ContractSeedFamily.MODEL_TARGET ->
                transcode(UniversalAiTarget.serializer(), fixture.json)
            ContractSeedFamily.TEXT_INPUT ->
                transcode(UniversalAiTextInput.serializer(), fixture.json)
            ContractSeedFamily.RESPONSE_FORMAT ->
                transcode(UniversalAiResponseFormat.serializer(), fixture.json)
            ContractSeedFamily.GENERATION_PARAMETERS ->
                transcode(UniversalAiGenerationParameters.serializer(), fixture.json)
            else -> error("${fixture.family} is not a component fixture family.")
        }
    }

    private fun classifySchema(fixture: ContractSeedFixture): ContractSeedIssue? {
        val element =
            try {
                CanonicalJson.parseToElement(fixture.json)
            } catch (_: SerializationException) {
                return schemaIssue("invalid_json", "", null)
            } catch (_: IllegalArgumentException) {
                return schemaIssue("invalid_json", "", null)
            }

        return when (fixture.family) {
            ContractSeedFamily.PROVIDER_ID -> validateProviderIdSchema(element)
            ContractSeedFamily.MODEL_ID -> validateStringSchema(element, "invalid_model_id")
            ContractSeedFamily.MODEL_TARGET -> validateTargetSchema(element)
            ContractSeedFamily.TEXT_INPUT,
            ContractSeedFamily.RESPONSE_FORMAT,
            ContractSeedFamily.GENERATION_PARAMETERS,
            -> {
                if (element !is JsonObject) {
                    schemaIssue("invalid_${fixture.family.name.lowercase()}", "", "type")
                } else {
                    null
                }
            }
            else -> error("${fixture.family} is not a component fixture family.")
        }
    }

    private fun validateProviderIdSchema(element: JsonElement): ContractSeedIssue? {
        val value =
            element.stringOrNull()
                ?: return schemaIssue("invalid_provider_id", "", "type")
        if (value.isEmpty()) {
            return schemaIssue("invalid_provider_id", "", "minLength")
        }
        if (value.jsonSchemaCodePointCount() > MAX_PROVIDER_ID_CHARACTERS) {
            return schemaIssue("invalid_provider_id", "", "maxLength")
        }
        return if (!providerIdPattern.matches(value)) {
            schemaIssue("invalid_provider_id", "", "pattern")
        } else {
            null
        }
    }

    private fun validateStringSchema(
        element: JsonElement,
        code: String,
    ): ContractSeedIssue? {
        val value =
            element.stringOrNull()
                ?: return schemaIssue(code, "", "type")
        return when {
            value.isEmpty() -> schemaIssue(code, "", "minLength")
            value.jsonSchemaCodePointCount() > MAX_MODEL_ID_SCHEMA_CHARACTERS ->
                schemaIssue(code, "", "maxLength")
            else -> null
        }
    }

    private fun validateTargetSchema(element: JsonElement): ContractSeedIssue? {
        val target =
            element as? JsonObject
                ?: return schemaIssue("invalid_model_target", "", "type")
        if ("providerId" !in target) {
            return schemaIssue("missing_provider_id", "", "required")
        }
        if ("modelId" !in target) {
            return schemaIssue("missing_model_id", "", "required")
        }
        return null
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

    private fun Throwable.toSemanticIssueOrDecodeMismatch(): ContractSeedIssue {
        val semanticFailure = contractSemanticExceptionOrNull()
        return if (semanticFailure == null) {
            semanticIssue(
                code = "production_component_decode_failed_without_semantic_issue",
                path = "",
            )
        } else {
            semanticIssue(
                code = semanticFailure.code,
                path = semanticFailure.path,
            )
        }
    }

    private fun <T> transcode(
        serializer: KSerializer<T>,
        rawJson: String,
    ): String {
        val decoded = CanonicalJson.decode(serializer, rawJson)
        return CanonicalJson.encode(serializer, decoded)
    }

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

    private const val MAX_PROVIDER_ID_CHARACTERS: Int = 64
    private const val MAX_MODEL_ID_SCHEMA_CHARACTERS: Int = 256
}

internal val ContractSeedFamily.isComponentFamily: Boolean
    get() =
        when (this) {
            ContractSeedFamily.PROVIDER_ID,
            ContractSeedFamily.MODEL_ID,
            ContractSeedFamily.MODEL_TARGET,
            ContractSeedFamily.TEXT_INPUT,
            ContractSeedFamily.RESPONSE_FORMAT,
            ContractSeedFamily.GENERATION_PARAMETERS,
            -> true
            else -> false
        }
