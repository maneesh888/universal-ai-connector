@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.extension.Extensions
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
 * Provider-neutral token accounting.
 *
 * Counters use the interoperable JSON safe-integer range. Breakdown keys are raw and
 * forward-compatible; their summed values cannot exceed their corresponding aggregate.
 */
@Serializable(with = UniversalAiUsageSerializer::class)
@HiddenFromObjC
class UniversalAiUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    inputDetails: Map<String, Long> = emptyMap(),
    outputDetails: Map<String, Long> = emptyMap(),
    val extensions: Extensions = Extensions.Empty,
) {
    private val storedInputDetails = inputDetails.toMap()
    private val storedOutputDetails = outputDetails.toMap()

    /** Returns a defensive snapshot whose raw keys retain unknown future breakdown names. */
    val inputDetails: Map<String, Long>
        get() = storedInputDetails.toMap()

    /** Returns a defensive snapshot whose raw keys retain unknown future breakdown names. */
    val outputDetails: Map<String, Long>
        get() = storedOutputDetails.toMap()

    init {
        validateUsageCount(inputTokens, "/inputTokens")
        validateUsageCount(outputTokens, "/outputTokens")
        validateUsageCount(totalTokens, "/totalTokens")
        contractRequire(
            condition = inputTokens + outputTokens == totalTokens,
            code = "usage_total_mismatch",
            path = "/totalTokens",
        ) {
            "totalTokens must equal inputTokens plus outputTokens."
        }
        validateUsageDetails(
            details = storedInputDetails,
            aggregate = inputTokens,
            path = "/inputDetails",
        )
        validateUsageDetails(
            details = storedOutputDetails,
            aggregate = outputTokens,
            path = "/outputDetails",
        )
    }

    internal fun inputDetailsForSerialization(): Map<String, Long> = storedInputDetails

    internal fun outputDetailsForSerialization(): Map<String, Long> = storedOutputDetails

    override fun equals(other: Any?): Boolean =
        other is UniversalAiUsage &&
            inputTokens == other.inputTokens &&
            outputTokens == other.outputTokens &&
            totalTokens == other.totalTokens &&
            storedInputDetails == other.storedInputDetails &&
            storedOutputDetails == other.storedOutputDetails &&
            extensions == other.extensions

    override fun hashCode(): Int {
        var result = inputTokens.hashCode()
        result = 31 * result + outputTokens.hashCode()
        result = 31 * result + totalTokens.hashCode()
        result = 31 * result + storedInputDetails.hashCode()
        result = 31 * result + storedOutputDetails.hashCode()
        result = 31 * result + extensions.hashCode()
        return result
    }
}

internal const val MAX_JSON_SAFE_INTEGER: Long = 9_007_199_254_740_991L

private const val MAX_USAGE_DETAILS: Int = 64

private val USAGE_DETAIL_KEY_PATTERN = Regex("^[a-z][a-z0-9._-]{0,63}$")

private fun validateUsageCount(
    value: Long,
    path: String,
) {
    contractRequire(
        condition = value in 0..MAX_JSON_SAFE_INTEGER,
        code = "usage_token_count_out_of_range",
        path = path,
    ) {
        "Usage token counts must be non-negative JSON safe integers."
    }
}

private fun validateUsageDetails(
    details: Map<String, Long>,
    aggregate: Long,
    path: String,
) {
    contractRequire(
        condition = details.size <= MAX_USAGE_DETAILS,
        code = "usage_detail_limit_exceeded",
        path = path,
    ) {
        "Usage-detail maps must not contain more than $MAX_USAGE_DETAILS entries."
    }
    var sum = 0L
    details.forEach { (key, value) ->
        val memberPath = "$path/${key.escapeUsagePointerToken()}"
        contractRequire(
            condition = USAGE_DETAIL_KEY_PATTERN.matches(key),
            code = "invalid_usage_detail_key",
            path = memberPath,
        ) {
            "Usage-detail keys must be 1-64 lowercase ASCII characters."
        }
        validateUsageCount(value, memberPath)
        contractRequire(
            condition = sum <= aggregate && value <= aggregate - sum,
            code = "usage_details_exceed_aggregate",
            path = path,
        ) {
            "Usage-detail values must not sum to more than their aggregate."
        }
        sum += value
    }
}

internal object UniversalAiUsageSerializer : KSerializer<UniversalAiUsage> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UniversalAiUsage,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("UniversalAiUsage supports JSON encoding only.")
        val members = linkedMapOf<String, JsonElement>()
        members["inputTokens"] = JsonPrimitive(value.inputTokens)
        members["outputTokens"] = JsonPrimitive(value.outputTokens)
        members["totalTokens"] = JsonPrimitive(value.totalTokens)
        if (value.inputDetailsForSerialization().isNotEmpty()) {
            members["inputDetails"] = value.inputDetailsForSerialization().toUsageDetailsObject()
        }
        if (value.outputDetailsForSerialization().isNotEmpty()) {
            members["outputDetails"] = value.outputDetailsForSerialization().toUsageDetailsObject()
        }
        if (!value.extensions.isEmpty) {
            members["extensions"] =
                CanonicalJson.format.encodeToJsonElement(
                    Extensions.serializer(),
                    value.extensions,
                )
        }
        jsonEncoder.encodeJsonElement(JsonObject(members))
    }

    override fun deserialize(decoder: Decoder): UniversalAiUsage {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("UniversalAiUsage supports JSON decoding only.")
        val document =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: throw SerializationException("Expected an object for UniversalAiUsage.")

        return decodeUsage {
            UniversalAiUsage(
                inputTokens = document.requiredSafeUsageInteger("inputTokens"),
                outputTokens = document.requiredSafeUsageInteger("outputTokens"),
                totalTokens = document.requiredSafeUsageInteger("totalTokens"),
                inputDetails = document.optionalUsageDetails("inputDetails"),
                outputDetails = document.optionalUsageDetails("outputDetails"),
                extensions =
                    document.optionalUsageNonNull("extensions")?.let { element ->
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

private fun Map<String, Long>.toUsageDetailsObject(): JsonObject =
    JsonObject(
        entries
            .sortedBy { entry -> entry.key }
            .associate { entry -> entry.key to JsonPrimitive(entry.value) },
    )

private fun JsonObject.requiredSafeUsageInteger(name: String): Long {
    val value =
        this[name]
            ?: throw SerializationException("Missing required '$name'.")
    val primitive =
        value as? JsonPrimitive
            ?: throw SerializationException("'$name' must be an integer.")
    if (
        primitive === JsonNull ||
        primitive.isString ||
        primitive.booleanOrNull != null ||
        !JsonNumberSemantics.isMathematicalInteger(primitive.content)
    ) {
        throw SerializationException("'$name' must be an integer.")
    }
    return primitive.content.toSafeUsageIntegerOrNull()
        ?: throw semanticSerializationException(
            code = "usage_token_count_out_of_range",
            path = "/$name",
            message = "'$name' must be a non-negative JSON safe integer.",
        )
}

private fun JsonObject.optionalUsageDetails(name: String): Map<String, Long> {
    val value = optionalUsageNonNull(name) ?: return emptyMap()
    val details =
        value as? JsonObject
            ?: throw SerializationException("'$name' must be an object.")
    return details.mapValues { (key, element) ->
        val primitive =
            element as? JsonPrimitive
                ?: throw SerializationException("'$name.$key' must be an integer.")
        if (
            primitive.isString ||
            primitive.booleanOrNull != null ||
            !JsonNumberSemantics.isMathematicalInteger(primitive.content)
        ) {
            throw SerializationException("'$name.$key' must be an integer.")
        }
        primitive.content.toSafeUsageIntegerOrNull()
            ?: throw semanticSerializationException(
                code = "usage_token_count_out_of_range",
                path = "/$name/${key.escapeUsagePointerToken()}",
                message = "'$name.$key' must be a non-negative JSON safe integer.",
            )
    }
}

private fun JsonObject.optionalUsageNonNull(name: String): JsonElement? {
    val value = this[name] ?: return null
    if (value === JsonNull) {
        throw SerializationException("'$name' must not be null.")
    }
    return value
}

private fun String.toSafeUsageIntegerOrNull(): Long? {
    if (
        JsonNumberSemantics.compare(this, "0")?.let { comparison -> comparison >= 0 } != true ||
        JsonNumberSemantics.compare(this, MAX_JSON_SAFE_INTEGER.toString())
            ?.let { comparison -> comparison <= 0 } != true
    ) {
        return null
    }
    val normalized = JsonNumberSemantics.normalize(this) ?: return null
    if (normalized == "0") {
        return 0L
    }
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

private fun String.escapeUsagePointerToken(): String =
    replace("~", "~0").replace("/", "~1")

private inline fun <T> decodeUsage(block: () -> T): T =
    try {
        block()
    } catch (failure: SerializationException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = failure.message ?: "Invalid UniversalAiUsage.",
            cause = failure,
        )
    }
