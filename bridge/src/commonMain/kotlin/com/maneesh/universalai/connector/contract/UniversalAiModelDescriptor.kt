@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.extension.Extensions
import com.maneesh.universalai.connector.contract.json.CURRENT_CONTRACT_VERSION
import com.maneesh.universalai.connector.contract.json.CanonicalJson
import com.maneesh.universalai.connector.contract.json.JsonNumberSemantics
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.native.HiddenFromObjC

/**
 * Canonical token ceilings for one model.
 *
 * An empty value means that V1 knows no token ceiling. The context window is the combined
 * input-and-output budget. When it is known, neither the input nor output maximum can exceed that
 * combined budget; their individual maxima need not sum to it. These token ceilings do not relax
 * request byte, item, schema, or document limits.
 */
@Serializable(with = UniversalAiModelTokenLimitsSerializer::class)
@HiddenFromObjC
class UniversalAiModelTokenLimits(
    val contextWindowTokens: Long? = null,
    val maxInputTokens: Long? = null,
    val maxOutputTokens: Long? = null,
) {
    internal val isEmpty: Boolean
        get() =
            contextWindowTokens == null &&
                maxInputTokens == null &&
                maxOutputTokens == null

    init {
        contextWindowTokens?.let { value ->
            validateModelTokenLimit(
                value = value,
                path = "/contextWindowTokens",
            )
        }
        maxInputTokens?.let { value ->
            validateModelTokenLimit(
                value = value,
                path = "/maxInputTokens",
            )
        }
        maxOutputTokens?.let { value ->
            validateModelTokenLimit(
                value = value,
                path = "/maxOutputTokens",
                maximum = MAX_OUTPUT_TOKENS.toLong(),
            )
        }
        if (contextWindowTokens != null && maxInputTokens != null) {
            contractRequire(
                condition = maxInputTokens <= contextWindowTokens,
                code = "model_input_limit_exceeds_context",
                path = "/maxInputTokens",
            ) {
                "maxInputTokens must not exceed contextWindowTokens."
            }
        }
        if (contextWindowTokens != null && maxOutputTokens != null) {
            contractRequire(
                condition = maxOutputTokens <= contextWindowTokens,
                code = "model_output_limit_exceeds_context",
                path = "/maxOutputTokens",
            ) {
                "maxOutputTokens must not exceed contextWindowTokens."
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is UniversalAiModelTokenLimits &&
            contextWindowTokens == other.contextWindowTokens &&
            maxInputTokens == other.maxInputTokens &&
            maxOutputTokens == other.maxOutputTokens

    override fun hashCode(): Int {
        var result = contextWindowTokens?.hashCode() ?: 0
        result = 31 * result + (maxInputTokens?.hashCode() ?: 0)
        result = 31 * result + (maxOutputTokens?.hashCode() ?: 0)
        return result
    }
}

/**
 * A serializable provider-neutral model descriptor.
 *
 * [capabilities] is the fully resolved effective set. Provider defaults and sparse model
 * declarations are composed with [UniversalAiCapabilitySet.resolve] before construction.
 * Canonical members remain authoritative over similarly named extension data.
 */
@Serializable(with = UniversalAiModelDescriptorSerializer::class)
@HiddenFromObjC
class UniversalAiModelDescriptor(
    val target: UniversalAiTarget,
    val displayName: String? = null,
    limits: UniversalAiModelTokenLimits? = null,
    val capabilities: UniversalAiCapabilitySet = UniversalAiCapabilitySet.Empty,
    val extensions: Extensions = Extensions.Empty,
) {
    /** Unknown-only future limit objects normalize to no known V1 token limits. */
    val limits: UniversalAiModelTokenLimits? = limits?.takeUnless { value -> value.isEmpty }

    val contractVersion: String
        get() = CURRENT_CONTRACT_VERSION

    init {
        displayName?.let { value ->
            contractRequire(
                condition =
                    value.isWellFormedContractUnicode() &&
                        value.none(Char::isContractControlCharacter) &&
                        value.isNotBlank(),
                code = "invalid_model_display_name",
                path = "/displayName",
            ) {
                "Model display names must be non-blank, well-formed Unicode without control characters."
            }
            contractRequire(
                condition = value.contractUtf8Size() <= MAX_MODEL_DISPLAY_NAME_BYTES,
                code = "model_display_name_too_large",
                path = "/displayName",
            ) {
                "Model display names must not exceed $MAX_MODEL_DISPLAY_NAME_BYTES UTF-8 bytes."
            }
        }
    }

    fun toJson(): String =
        CanonicalJson.encode(
            UniversalAiModelDescriptor.serializer(),
            this,
        )

    override fun equals(other: Any?): Boolean =
        other is UniversalAiModelDescriptor &&
            target == other.target &&
            displayName == other.displayName &&
            limits == other.limits &&
            capabilities == other.capabilities &&
            extensions == other.extensions

    override fun hashCode(): Int {
        var result = target.hashCode()
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + (limits?.hashCode() ?: 0)
        result = 31 * result + capabilities.hashCode()
        result = 31 * result + extensions.hashCode()
        return result
    }

    companion object {
        fun fromJson(json: String): UniversalAiModelDescriptor =
            CanonicalJson.decode(
                UniversalAiModelDescriptor.serializer(),
                json,
            )
    }
}

private const val MAX_MODEL_DISPLAY_NAME_BYTES: Int = 256

private fun validateModelTokenLimit(
    value: Long,
    path: String,
    maximum: Long = MAX_JSON_SAFE_INTEGER,
) {
    contractRequire(
        condition = value in 1..maximum,
        code = "model_token_limit_out_of_range",
        path = path,
    ) {
        "Model token limits must be positive integers no greater than $maximum."
    }
}

internal object UniversalAiModelTokenLimitsSerializer :
    KSerializer<UniversalAiModelTokenLimits> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UniversalAiModelTokenLimits,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException(
                    "UniversalAiModelTokenLimits supports JSON encoding only.",
                )
        val members = linkedMapOf<String, JsonElement>()
        value.contextWindowTokens?.let { members["contextWindowTokens"] = JsonPrimitive(it) }
        value.maxInputTokens?.let { members["maxInputTokens"] = JsonPrimitive(it) }
        value.maxOutputTokens?.let { members["maxOutputTokens"] = JsonPrimitive(it) }
        jsonEncoder.encodeJsonElement(JsonObject(members))
    }

    override fun deserialize(decoder: Decoder): UniversalAiModelTokenLimits {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException(
                    "UniversalAiModelTokenLimits supports JSON decoding only.",
                )
        val document =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: throw SerializationException(
                    "Expected an object for UniversalAiModelTokenLimits.",
                )

        return decodeModelTokenLimits {
            val contextWindowTokens =
                document.optionalModelSafeInteger("contextWindowTokens")
            val maxInputTokens =
                document.optionalModelSafeInteger("maxInputTokens")
            val maxOutputTokens =
                document.optionalModelSafeInteger("maxOutputTokens")
            UniversalAiModelTokenLimits(
                contextWindowTokens = contextWindowTokens,
                maxInputTokens = maxInputTokens,
                maxOutputTokens = maxOutputTokens,
            )
        }
    }
}

internal object UniversalAiModelDescriptorSerializer : KSerializer<UniversalAiModelDescriptor> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UniversalAiModelDescriptor,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException(
                    "UniversalAiModelDescriptor supports JSON encoding only.",
                )
        val members = linkedMapOf<String, JsonElement>()
        members["contractVersion"] = JsonPrimitive(CURRENT_CONTRACT_VERSION)
        members["target"] =
            CanonicalJson.format.encodeToJsonElement(
                UniversalAiTarget.serializer(),
                value.target,
            )
        value.displayName?.let { members["displayName"] = JsonPrimitive(it) }
        value.limits?.let { limits ->
            members["limits"] =
                CanonicalJson.format.encodeToJsonElement(
                    UniversalAiModelTokenLimits.serializer(),
                    limits,
                )
        }
        members["capabilities"] =
            CanonicalJson.format.encodeToJsonElement(
                UniversalAiCapabilitySet.serializer(),
                value.capabilities,
            )
        if (!value.extensions.isEmpty) {
            members["extensions"] =
                CanonicalJson.format.encodeToJsonElement(
                    Extensions.serializer(),
                    value.extensions,
                )
        }
        jsonEncoder.encodeJsonElement(JsonObject(members))
    }

    override fun deserialize(decoder: Decoder): UniversalAiModelDescriptor {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException(
                    "UniversalAiModelDescriptor supports JSON decoding only.",
                )
        val document =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: throw SerializationException(
                    "Expected an object for UniversalAiModelDescriptor.",
                )
        val contractVersion = document.requiredModelString("contractVersion")
        if (contractVersion != CURRENT_CONTRACT_VERSION) {
            throw semanticSerializationException(
                code = "unsupported_contract_version",
                path = "/contractVersion",
                message = "Unsupported contractVersion '$contractVersion'.",
            )
        }

        return decodeModelDescriptor {
            UniversalAiModelDescriptor(
                target =
                    decodeSemanticComponent(pathPrefix = "/target") {
                        CanonicalJson.format.decodeFromJsonElement(
                            UniversalAiTarget.serializer(),
                            document.requiredModelNonNull("target"),
                        )
                    },
                displayName = document.optionalModelString("displayName"),
                limits =
                    document.optionalModelNonNull("limits")?.let { element ->
                        decodeSemanticComponent(pathPrefix = "/limits") {
                            CanonicalJson.format.decodeFromJsonElement(
                                UniversalAiModelTokenLimits.serializer(),
                                element,
                            )
                        }
                    },
                capabilities =
                    decodeSemanticComponent(pathPrefix = "/capabilities") {
                        CanonicalJson.format.decodeFromJsonElement(
                            UniversalAiCapabilitySet.serializer(),
                            document.requiredModelNonNull("capabilities"),
                        )
                    },
                extensions =
                    document.optionalModelNonNull("extensions")?.let { element ->
                        decodeSemanticComponent(pathPrefix = "/extensions") {
                            CanonicalJson.format.decodeFromJsonElement(
                                Extensions.serializer(),
                                element,
                            )
                        }
                    } ?: Extensions.Empty,
            )
        }
    }
}

private fun JsonObject.requiredModelNonNull(name: String): JsonElement =
    this[name]
        ?.takeUnless { value -> value === JsonNull }
        ?: throw SerializationException("Missing required non-null '$name'.")

private fun JsonObject.optionalModelNonNull(name: String): JsonElement? {
    val value = this[name] ?: return null
    if (value === JsonNull) {
        throw SerializationException("'$name' must not be null.")
    }
    return value
}

private fun JsonObject.requiredModelString(name: String): String {
    val value = requiredModelNonNull(name)
    if (value !is JsonPrimitive || !value.isString) {
        throw SerializationException("'$name' must be a string.")
    }
    return value.content
}

private fun JsonObject.optionalModelString(name: String): String? {
    val value = optionalModelNonNull(name) ?: return null
    if (value !is JsonPrimitive || !value.isString) {
        throw SerializationException("'$name' must be a string.")
    }
    return value.content
}

private fun JsonObject.optionalModelSafeInteger(name: String): Long? {
    val value = optionalModelNonNull(name) ?: return null
    val primitive =
        value as? JsonPrimitive
            ?: throw SerializationException("'$name' must be an integer.")
    if (
        primitive.isString ||
        primitive.booleanOrNull != null ||
        !JsonNumberSemantics.isMathematicalInteger(primitive.content)
    ) {
        throw SerializationException("'$name' must be an integer.")
    }
    return primitive.content.toModelSafeIntegerOrNull()
        ?: throw semanticSerializationException(
            code = "model_token_limit_out_of_range",
            path = "/$name",
            message = "'$name' must be a positive JSON safe integer.",
        )
}

private fun String.toModelSafeIntegerOrNull(): Long? {
    if (
        JsonNumberSemantics.compare(this, "1")?.let { comparison -> comparison >= 0 } != true ||
        JsonNumberSemantics.compare(this, MAX_JSON_SAFE_INTEGER.toString())
            ?.let { comparison -> comparison <= 0 } != true
    ) {
        return null
    }
    val normalized = JsonNumberSemantics.normalize(this) ?: return null
    val exponentMarker = normalized.lastIndexOf('e')
    if (exponentMarker <= 0 || exponentMarker == normalized.lastIndex) {
        return null
    }
    val digits = normalized.substring(0, exponentMarker)
    if (digits.startsWith('-')) {
        return null
    }
    val exponent = normalized.substring(exponentMarker + 1).toIntOrNull() ?: return null
    if (exponent < 0) {
        return null
    }
    return buildString(digits.length + exponent) {
        append(digits)
        repeat(exponent) {
            append('0')
        }
    }.toLongOrNull()
}

private inline fun <T> decodeModelTokenLimits(block: () -> T): T =
    try {
        block()
    } catch (failure: SerializationException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = failure.message ?: "Invalid UniversalAiModelTokenLimits.",
            cause = failure,
        )
    }

private inline fun <T> decodeModelDescriptor(block: () -> T): T =
    try {
        block()
    } catch (failure: SerializationException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = failure.message ?: "Invalid UniversalAiModelDescriptor.",
            cause = failure,
        )
    }
