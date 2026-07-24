@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.extension.Extensions
import com.maneesh.universalai.connector.contract.json.CURRENT_CONTRACT_VERSION
import com.maneesh.universalai.connector.contract.json.CanonicalJson
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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.native.HiddenFromObjC

/** A V1 provider-neutral request. */
@Serializable(with = UniversalAiRequestSerializer::class)
@HiddenFromObjC
class UniversalAiRequest(
    val target: UniversalAiTarget,
    input: List<UniversalAiTextInput>,
    val responseFormat: UniversalAiResponseFormat = UniversalAiResponseFormat.PlainText,
    val generation: UniversalAiGenerationParameters = UniversalAiGenerationParameters.Default,
    val extensions: Extensions = Extensions.Empty,
) {
    private val storedInput = input.toList()

    val contractVersion: String
        get() = CURRENT_CONTRACT_VERSION

    /** Returns a defensive snapshot that retains the caller's input order. */
    val input: List<UniversalAiTextInput>
        get() = storedInput.toList()

    init {
        contractRequire(
            condition = storedInput.isNotEmpty(),
            code = "empty_input",
            path = "/input",
        ) {
            "A request must contain at least one input item."
        }
        contractRequire(
            condition = storedInput.size <= MAX_INPUT_ITEMS,
            code = "input_item_limit_exceeded",
            path = "/input",
        ) {
            "A request must not contain more than $MAX_INPUT_ITEMS input items."
        }
        contractRequire(
            condition =
                storedInput.sumOf { item -> item.content.contractUtf8Size() } <=
                    MAX_TOTAL_INPUT_CONTENT_BYTES,
            code = "input_content_total_too_large",
            path = "/input",
        ) {
            "Request input must not exceed $MAX_TOTAL_INPUT_CONTENT_BYTES total UTF-8 bytes."
        }
    }

    /** Encodes through the strict canonical codec, including the required wire-version marker. */
    fun toJson(): String =
        CanonicalJson.encode(
            UniversalAiRequest.serializer(),
            this,
        )

    internal fun inputForSerialization(): List<UniversalAiTextInput> = storedInput

    override fun equals(other: Any?): Boolean =
        other is UniversalAiRequest &&
            target == other.target &&
            storedInput == other.storedInput &&
            responseFormat == other.responseFormat &&
            generation == other.generation &&
            extensions == other.extensions

    override fun hashCode(): Int {
        var result = target.hashCode()
        result = 31 * result + storedInput.hashCode()
        result = 31 * result + responseFormat.hashCode()
        result = 31 * result + generation.hashCode()
        result = 31 * result + extensions.hashCode()
        return result
    }

    companion object {
        /** Decodes with duplicate-member, document-size, and semantic validation enabled. */
        fun fromJson(json: String): UniversalAiRequest =
            CanonicalJson.decode(
                UniversalAiRequest.serializer(),
                json,
            )
    }
}

internal object UniversalAiRequestSerializer : KSerializer<UniversalAiRequest> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UniversalAiRequest,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("UniversalAiRequest supports JSON encoding only.")
        val members = linkedMapOf<String, JsonElement>()
        members["contractVersion"] = JsonPrimitive(CURRENT_CONTRACT_VERSION)
        members["target"] =
            CanonicalJson.format.encodeToJsonElement(
                UniversalAiTarget.serializer(),
                value.target,
            )
        members["input"] =
            JsonArray(
                value.inputForSerialization().map { item ->
                    CanonicalJson.format.encodeToJsonElement(
                        UniversalAiTextInput.serializer(),
                        item,
                    )
                },
            )
        if (value.responseFormat != UniversalAiResponseFormat.PlainText) {
            members["responseFormat"] =
                CanonicalJson.format.encodeToJsonElement(
                    UniversalAiResponseFormat.serializer(),
                    value.responseFormat,
                )
        }
        if (!value.generation.isEmpty) {
            members["generation"] =
                CanonicalJson.format.encodeToJsonElement(
                    UniversalAiGenerationParameters.serializer(),
                    value.generation,
                )
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

    override fun deserialize(decoder: Decoder): UniversalAiRequest {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("UniversalAiRequest supports JSON decoding only.")
        val document =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: throw SerializationException("Expected an object for UniversalAiRequest.")

        val contractVersion = document.requiredString("contractVersion")
        if (contractVersion != CURRENT_CONTRACT_VERSION) {
            throw semanticSerializationException(
                code = "unsupported_contract_version",
                path = "/contractVersion",
                message = "Unsupported contractVersion '$contractVersion'.",
            )
        }

        return decodeRequest {
            UniversalAiRequest(
                target =
                    decodeSemanticComponent(pathPrefix = "/target") {
                        CanonicalJson.format.decodeFromJsonElement(
                            UniversalAiTarget.serializer(),
                            document.requiredNonNull("target"),
                        )
                    },
                input =
                    document.requiredArray("input").mapIndexed { index, item ->
                        decodeSemanticComponent(pathPrefix = "/input/$index") {
                            CanonicalJson.format.decodeFromJsonElement(
                                UniversalAiTextInput.serializer(),
                                item,
                            )
                        }
                    },
                responseFormat =
                    document.optionalNonNull("responseFormat")?.let { element ->
                        decodeSemanticComponent(pathPrefix = "/responseFormat") {
                            CanonicalJson.format.decodeFromJsonElement(
                                UniversalAiResponseFormat.serializer(),
                                element,
                            )
                        }
                    } ?: UniversalAiResponseFormat.PlainText,
                generation =
                    document.optionalNonNull("generation")?.let { element ->
                        decodeSemanticComponent(pathPrefix = "/generation") {
                            CanonicalJson.format.decodeFromJsonElement(
                                UniversalAiGenerationParameters.serializer(),
                                element,
                            )
                        }
                    } ?: UniversalAiGenerationParameters.Default,
                extensions =
                    document.optionalNonNull("extensions")?.let { element ->
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

private fun JsonObject.requiredNonNull(name: String): JsonElement =
    this[name]
        ?.takeUnless { it === JsonNull }
        ?: throw SerializationException("Missing required non-null '$name'.")

private fun JsonObject.optionalNonNull(name: String): JsonElement? {
    val value = this[name] ?: return null
    if (value === JsonNull) {
        throw SerializationException("'$name' must not be null.")
    }
    return value
}

private fun JsonObject.requiredString(name: String): String {
    val value = requiredNonNull(name)
    if (value !is JsonPrimitive || !value.isString) {
        throw SerializationException("'$name' must be a string.")
    }
    return value.content
}

private fun JsonObject.requiredArray(name: String): JsonArray =
    requiredNonNull(name) as? JsonArray
        ?: throw SerializationException("'$name' must be an array.")

private inline fun <T> decodeRequest(block: () -> T): T =
    try {
        block()
    } catch (failure: SerializationException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = failure.message ?: "Invalid UniversalAiRequest.",
            cause = failure,
        )
    }
