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
import kotlin.jvm.JvmInline
import kotlin.native.HiddenFromObjC

/** A raw-backed, forward-compatible output-kind discriminator. */
@JvmInline
@Serializable(with = UniversalAiOutputKindSerializer::class)
@HiddenFromObjC
value class UniversalAiOutputKind private constructor(
    val rawValue: String,
) {
    val isKnown: Boolean
        get() = this == Text || this == StructuredJson

    companion object {
        val Text: UniversalAiOutputKind = UniversalAiOutputKind("text")
        val StructuredJson: UniversalAiOutputKind = UniversalAiOutputKind("structured_json")

        fun of(rawValue: String): UniversalAiOutputKind {
            contractRequire(
                condition = OUTPUT_KIND_PATTERN.matches(rawValue),
                code = "invalid_output_kind",
                path = "/kind",
            ) {
                "Output kinds must be 1-$MAX_OUTPUT_KIND_CHARACTERS lowercase ASCII characters."
            }
            return UniversalAiOutputKind(rawValue)
        }
    }
}

/**
 * One ordered provider-neutral response output.
 *
 * Known kinds use typed factories. A future kind preserves its raw discriminator without
 * introducing a provider DTO; its portable payload belongs in [extensions].
 */
@Serializable(with = UniversalAiOutputSerializer::class)
@HiddenFromObjC
class UniversalAiOutput private constructor(
    val id: OutputId,
    val index: Int,
    val kind: UniversalAiOutputKind,
    val text: String?,
    val structuredJson: StructuredOutputValue?,
    val extensions: Extensions,
) {
    init {
        contractRequire(
            condition = index in 0 until MAX_RESPONSE_OUTPUTS,
            code = "output_index_out_of_range",
            path = "/index",
        ) {
            "Output indices must be between 0 and ${MAX_RESPONSE_OUTPUTS - 1}."
        }
        text?.let { value ->
            contractRequire(
                condition = value.isWellFormedContractUnicode(),
                code = "invalid_output_text",
                path = "/text",
            ) {
                "Text output must contain well-formed Unicode."
            }
            contractRequire(
                condition = value.contractUtf8Size() <= MAX_OUTPUT_TEXT_BYTES,
                code = "output_text_too_large",
                path = "/text",
            ) {
                "Text output must not exceed $MAX_OUTPUT_TEXT_BYTES UTF-8 bytes."
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is UniversalAiOutput &&
            id == other.id &&
            index == other.index &&
            kind == other.kind &&
            text == other.text &&
            structuredJson == other.structuredJson &&
            extensions == other.extensions

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + index
        result = 31 * result + kind.hashCode()
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + (structuredJson?.hashCode() ?: 0)
        result = 31 * result + extensions.hashCode()
        return result
    }

    companion object {
        fun text(
            id: OutputId,
            index: Int,
            text: String,
            extensions: Extensions = Extensions.Empty,
        ): UniversalAiOutput =
            UniversalAiOutput(
                id = id,
                index = index,
                kind = UniversalAiOutputKind.Text,
                text = text,
                structuredJson = null,
                extensions = extensions,
            )

        fun structuredJson(
            id: OutputId,
            index: Int,
            value: StructuredOutputValue,
            extensions: Extensions = Extensions.Empty,
        ): UniversalAiOutput =
            UniversalAiOutput(
                id = id,
                index = index,
                kind = UniversalAiOutputKind.StructuredJson,
                text = null,
                structuredJson = value,
                extensions = extensions,
            )

        /** Creates an opaque future output. Known kinds must use their typed factory. */
        fun future(
            id: OutputId,
            index: Int,
            kind: UniversalAiOutputKind,
            extensions: Extensions = Extensions.Empty,
        ): UniversalAiOutput {
            contractRequire(
                condition = !kind.isKnown,
                code = "known_output_kind",
                path = "/kind",
            ) {
                "Known output kinds must use their typed construction path."
            }
            return UniversalAiOutput(
                id = id,
                index = index,
                kind = kind,
                text = null,
                structuredJson = null,
                extensions = extensions,
            )
        }
    }
}

private const val MAX_OUTPUT_KIND_CHARACTERS: Int = 64
private const val MAX_OUTPUT_TEXT_BYTES: Int = 1_048_576
internal const val MAX_RESPONSE_OUTPUTS: Int = 128

private val OUTPUT_KIND_PATTERN = Regex("^[a-z][a-z0-9._-]{0,63}$")

internal object UniversalAiOutputKindSerializer :
    ValidatedStringSerializer<UniversalAiOutputKind>(
        serialName = "com.maneesh.universalai.connector.contract.UniversalAiOutputKind",
        create = UniversalAiOutputKind::of,
        rawValue = UniversalAiOutputKind::rawValue,
    )

internal object UniversalAiOutputSerializer : KSerializer<UniversalAiOutput> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UniversalAiOutput,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("UniversalAiOutput supports JSON encoding only.")
        val members = linkedMapOf<String, JsonElement>()
        members["id"] = JsonPrimitive(value.id.rawValue)
        members["index"] = JsonPrimitive(value.index)
        members["kind"] = JsonPrimitive(value.kind.rawValue)
        when (value.kind) {
            UniversalAiOutputKind.Text -> members["text"] = JsonPrimitive(checkNotNull(value.text))
            UniversalAiOutputKind.StructuredJson ->
                members["json"] = checkNotNull(value.structuredJson).elementForSerialization()
            else -> Unit
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

    override fun deserialize(decoder: Decoder): UniversalAiOutput {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("UniversalAiOutput supports JSON decoding only.")
        val document =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: throw SerializationException("Expected an object for UniversalAiOutput.")

        val id =
            decodeSemanticComponent(pathPrefix = "") {
                OutputId.of(document.requiredOutputString("id"))
            }
        val index = document.requiredOutputInt("index")
        val kind =
            decodeSemanticComponent(pathPrefix = "") {
                UniversalAiOutputKind.of(document.requiredOutputString("kind"))
            }
        val extensions =
            document.optionalOutputNonNull("extensions")?.let { element ->
                decodeSemanticComponent(pathPrefix = "/extensions") {
                    CanonicalJson.format.decodeFromJsonElement(
                        Extensions.serializer(),
                        element,
                    )
                }
            } ?: Extensions.Empty

        return when (kind) {
            UniversalAiOutputKind.Text -> {
                if ("json" in document) {
                    throw semanticSerializationException(
                        code = "unexpected_output_json",
                        path = "/json",
                        message = "Text output must not contain 'json'.",
                    )
                }
                if ("text" !in document) {
                    throw semanticSerializationException(
                        code = "missing_output_text",
                        path = "/text",
                        message = "Text output requires 'text'.",
                    )
                }
                decodeOutput {
                    UniversalAiOutput.text(
                        id = id,
                        index = index,
                        text = document.requiredOutputString("text"),
                        extensions = extensions,
                    )
                }
            }

            UniversalAiOutputKind.StructuredJson -> {
                if ("text" in document) {
                    throw semanticSerializationException(
                        code = "unexpected_output_text",
                        path = "/text",
                        message = "Structured JSON output must not contain 'text'.",
                    )
                }
                if ("json" !in document) {
                    throw semanticSerializationException(
                        code = "missing_structured_output_json",
                        path = "/json",
                        message = "Structured JSON output requires 'json'.",
                    )
                }
                decodeOutput {
                    UniversalAiOutput.structuredJson(
                        id = id,
                        index = index,
                        value =
                            StructuredOutputValue.fromElement(
                                document.getValue("json"),
                            ),
                        extensions = extensions,
                    )
                }
            }

            else -> {
                if ("text" in document) {
                    throw semanticSerializationException(
                        code = "unexpected_output_text",
                        path = "/text",
                        message = "Future output kinds must place portable payloads in extensions.",
                    )
                }
                if ("json" in document) {
                    throw semanticSerializationException(
                        code = "unexpected_output_json",
                        path = "/json",
                        message = "Future output kinds must place portable payloads in extensions.",
                    )
                }
                decodeOutput {
                    UniversalAiOutput.future(
                        id = id,
                        index = index,
                        kind = kind,
                        extensions = extensions,
                    )
                }
            }
        }
    }
}

private fun JsonObject.requiredOutputString(name: String): String {
    val value =
        this[name]
            ?: throw SerializationException("Missing required '$name'.")
    if (value === JsonNull || value !is JsonPrimitive || !value.isString) {
        throw SerializationException("'$name' must be a non-null string.")
    }
    return value.content
}

private fun JsonObject.requiredOutputInt(name: String): Int {
    val value =
        this[name]
            ?: throw SerializationException("Missing required '$name'.")
    if (
        value === JsonNull ||
        value !is JsonPrimitive ||
        value.isString ||
        value.booleanOrNull != null ||
        !JsonNumberSemantics.isMathematicalInteger(value.content)
    ) {
        throw SerializationException("'$name' must be an integer.")
    }
    return JsonNumberSemantics.toExactIntOrNull(value.content)
        ?: throw SerializationException("'$name' is outside the supported integer range.")
}

private fun JsonObject.optionalOutputNonNull(name: String): JsonElement? {
    val value = this[name] ?: return null
    if (value === JsonNull) {
        throw SerializationException("'$name' must not be null.")
    }
    return value
}

private inline fun <T> decodeOutput(block: () -> T): T =
    try {
        block()
    } catch (failure: SerializationException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = failure.message ?: "Invalid UniversalAiOutput.",
            cause = failure,
        )
    }
