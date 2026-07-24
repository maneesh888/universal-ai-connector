@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.json.JsonNumberSemantics
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.native.HiddenFromObjC

/**
 * Common generation intent.
 *
 * Omitted values delegate to the selected model/provider defaults. Transport retry and timeout
 * policy deliberately do not belong here. JSON floating-point inputs must be finite binary64
 * values whose ordinary JSON rendering preserves the same mathematical number; decoding rejects
 * underflow or precision-changing coercion.
 */
@Serializable(with = UniversalAiGenerationParametersSerializer::class)
@HiddenFromObjC
class UniversalAiGenerationParameters(
    val maxOutputTokens: Int? = null,
    temperature: Double? = null,
    val topP: Double? = null,
    stopSequences: List<String> = emptyList(),
) {
    private val storedStopSequences = stopSequences.toList()

    val temperature: Double? =
        temperature?.let { value ->
            if (value == 0.0) {
                0.0
            } else {
                value
            }
        }

    val stopSequences: List<String>
        get() = storedStopSequences.toList()

    val isEmpty: Boolean
        get() =
            maxOutputTokens == null &&
                temperature == null &&
                topP == null &&
                storedStopSequences.isEmpty()

    init {
        maxOutputTokens?.let { value ->
            contractRequire(
                condition = value in 1..MAX_OUTPUT_TOKENS,
                code = "max_output_tokens_out_of_range",
                path = "/maxOutputTokens",
            ) {
                "maxOutputTokens must be between 1 and $MAX_OUTPUT_TOKENS."
            }
        }
        this.temperature?.let { value ->
            contractRequire(
                condition = value.isFinite() && value in 0.0..1.0,
                code = "temperature_out_of_range",
                path = "/temperature",
            ) {
                "temperature must be finite and between 0.0 and $MAX_TEMPERATURE."
            }
        }
        topP?.let { value ->
            contractRequire(
                condition = value.isFinite() && value > 0.0 && value <= 1.0,
                code = "top_p_out_of_range",
                path = "/topP",
            ) {
                "topP must be finite, greater than 0.0, and at most 1.0."
            }
        }
        contractRequire(
            condition = storedStopSequences.size <= MAX_STOP_SEQUENCES,
            code = "stop_sequence_limit_exceeded",
            path = "/stopSequences",
        ) {
            "stopSequences must not contain more than $MAX_STOP_SEQUENCES values."
        }
        val duplicateIndex =
            storedStopSequences.indices.firstOrNull { index ->
                storedStopSequences.indexOf(storedStopSequences[index]) != index
            }
        contractRequire(
            condition = duplicateIndex == null,
            code = "duplicate_stop_sequence",
            path = "/stopSequences/${duplicateIndex ?: 0}",
        ) {
            "stopSequences must not contain duplicate values."
        }
        storedStopSequences.forEachIndexed { index, value ->
            contractRequire(
                condition = value.isWellFormedContractUnicode(),
                code = "invalid_stop_sequence",
                path = "/stopSequences/$index",
            ) {
                "Stop sequences must contain well-formed Unicode."
            }
            contractRequire(
                condition = value.isNotEmpty(),
                code = "invalid_stop_sequence",
                path = "/stopSequences/$index",
            ) {
                "Stop sequences must not be empty."
            }
            contractRequire(
                condition = value.contractUtf8Size() <= MAX_STOP_SEQUENCE_BYTES,
                code = "stop_sequence_too_large",
                path = "/stopSequences/$index",
            ) {
                "Stop sequences must not exceed $MAX_STOP_SEQUENCE_BYTES UTF-8 bytes."
            }
        }
        contractRequire(
            condition =
                storedStopSequences.sumOf(String::contractUtf8Size) <=
                    MAX_TOTAL_STOP_SEQUENCE_BYTES,
            code = "stop_sequence_total_too_large",
            path = "/stopSequences",
        ) {
            "stopSequences must not exceed $MAX_TOTAL_STOP_SEQUENCE_BYTES total UTF-8 bytes."
        }
    }

    internal fun stopSequencesForSerialization(): List<String> = storedStopSequences

    override fun equals(other: Any?): Boolean =
        other is UniversalAiGenerationParameters &&
            maxOutputTokens == other.maxOutputTokens &&
            temperature == other.temperature &&
            topP == other.topP &&
            storedStopSequences == other.storedStopSequences

    override fun hashCode(): Int {
        var result = maxOutputTokens ?: 0
        result = 31 * result + (temperature?.hashCode() ?: 0)
        result = 31 * result + (topP?.hashCode() ?: 0)
        result = 31 * result + storedStopSequences.hashCode()
        return result
    }

    companion object {
        val Default: UniversalAiGenerationParameters = UniversalAiGenerationParameters()
    }
}

internal object UniversalAiGenerationParametersSerializer :
    KSerializer<UniversalAiGenerationParameters> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UniversalAiGenerationParameters,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException(
                    "UniversalAiGenerationParameters supports JSON encoding only.",
                )
        val members = linkedMapOf<String, JsonElement>()
        value.maxOutputTokens?.let { members["maxOutputTokens"] = JsonPrimitive(it) }
        value.temperature?.let { members["temperature"] = JsonPrimitive(it) }
        value.topP?.let { members["topP"] = JsonPrimitive(it) }
        if (value.stopSequencesForSerialization().isNotEmpty()) {
            members["stopSequences"] =
                JsonArray(
                    value.stopSequencesForSerialization().map(::JsonPrimitive),
                )
        }
        jsonEncoder.encodeJsonElement(JsonObject(members))
    }

    override fun deserialize(decoder: Decoder): UniversalAiGenerationParameters {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException(
                    "UniversalAiGenerationParameters supports JSON decoding only.",
                )
        val element = jsonDecoder.decodeJsonElement()
        val parameters =
            element as? JsonObject
                ?: throw SerializationException("Expected an object for generation.")

        return decodeParameters {
            UniversalAiGenerationParameters(
                maxOutputTokens = parameters.optionalInt("maxOutputTokens"),
                temperature = parameters.optionalDouble("temperature"),
                topP = parameters.optionalDouble("topP"),
                stopSequences = parameters.optionalStringList("stopSequences"),
            )
        }
    }
}

internal const val MAX_OUTPUT_TOKENS: Int = 1_048_576
internal const val MAX_STOP_SEQUENCES: Int = 16
internal const val MAX_STOP_SEQUENCE_BYTES: Int = 1_024
internal const val MAX_TOTAL_STOP_SEQUENCE_BYTES: Int = 4_096
internal const val MAX_TEMPERATURE: Double = 1.0

private fun JsonObject.optionalElement(name: String): JsonElement? {
    val value = this[name] ?: return null
    if (value === JsonNull) {
        throw SerializationException("'$name' must not be null.")
    }
    return value
}

private fun JsonObject.optionalInt(name: String): Int? {
    val value = optionalElement(name) ?: return null
    val primitive =
        value as? JsonPrimitive
            ?: throw SerializationException("'$name' must be an integer.")
    if (primitive.isString || primitive.booleanOrNull != null) {
        throw SerializationException("'$name' must be an integer.")
    }
    if (!JsonNumberSemantics.isMathematicalInteger(primitive.content)) {
        throw SerializationException("'$name' must be an integer.")
    }
    return JsonNumberSemantics.toExactIntOrNull(primitive.content)
        ?: throw SerializationException("'$name' is outside the supported integer range.")
}

private fun JsonObject.optionalDouble(name: String): Double? {
    val value = optionalElement(name) ?: return null
    val primitive =
        value as? JsonPrimitive
            ?: throw SerializationException("'$name' must be a number.")
    if (primitive.isString || primitive.booleanOrNull != null) {
        throw SerializationException("'$name' must be a number.")
    }
    return JsonNumberSemantics.toSemanticallyRoundTrippableDoubleOrNull(primitive.content)
        ?: throw semanticSerializationException(
            code = "generation_number_not_round_trippable",
            path = "/$name",
            message =
                "'$name' must be a finite binary64 value that preserves its exact JSON number.",
        )
}

private fun JsonObject.optionalStringList(name: String): List<String> {
    val value = optionalElement(name) ?: return emptyList()
    val array =
        value as? JsonArray
            ?: throw SerializationException("'$name' must be an array.")
    return array.mapIndexed { index, item ->
        if (item === JsonNull || item !is JsonPrimitive || !item.isString) {
            throw SerializationException("'$name/$index' must be a non-null string.")
        }
        item.content
    }
}

private inline fun <T> decodeParameters(block: () -> T): T =
    try {
        block()
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = failure.message ?: "Invalid generation.",
            cause = failure,
        )
    }
