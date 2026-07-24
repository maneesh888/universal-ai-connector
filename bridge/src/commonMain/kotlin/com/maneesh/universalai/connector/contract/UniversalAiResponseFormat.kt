@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.jvm.JvmInline
import kotlin.native.HiddenFromObjC

/** A raw-backed response-format discriminator. */
@JvmInline
@Serializable(with = UniversalAiResponseFormatKindSerializer::class)
@HiddenFromObjC
value class UniversalAiResponseFormatKind private constructor(
    val rawValue: String,
) {
    val isKnown: Boolean
        get() = this == PlainText || this == JsonSchema

    companion object {
        val PlainText: UniversalAiResponseFormatKind =
            UniversalAiResponseFormatKind("plain_text")
        val JsonSchema: UniversalAiResponseFormatKind =
            UniversalAiResponseFormatKind("json_schema")

        fun of(rawValue: String): UniversalAiResponseFormatKind {
            contractRequire(
                condition = RESPONSE_FORMAT_KIND_PATTERN.matches(rawValue),
                code = "invalid_response_format_kind",
                path = "/kind",
            ) {
                "Response-format kinds must be 1-$MAX_FORMAT_KIND_CHARACTERS lowercase ASCII characters."
            }
            return UniversalAiResponseFormatKind(rawValue)
        }
    }
}

/**
 * Provider-neutral response-format intent.
 *
 * V1 knows plain text and governed JSON Schema output. Unknown future kind values retain their raw
 * discriminator while their unknown ordinary members are ignored.
 */
@Serializable(with = UniversalAiResponseFormatSerializer::class)
@HiddenFromObjC
class UniversalAiResponseFormat private constructor(
    val kind: UniversalAiResponseFormatKind,
    val schema: StructuredOutputSchema?,
) {
    val isKnown: Boolean
        get() = kind.isKnown

    override fun equals(other: Any?): Boolean =
        other is UniversalAiResponseFormat &&
            kind == other.kind &&
            schema == other.schema

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + (schema?.hashCode() ?: 0)
        return result
    }

    companion object {
        val PlainText: UniversalAiResponseFormat =
            UniversalAiResponseFormat(
                kind = UniversalAiResponseFormatKind.PlainText,
                schema = null,
            )

        fun jsonSchema(
            schema: StructuredOutputSchema,
        ): UniversalAiResponseFormat {
            return UniversalAiResponseFormat(
                kind = UniversalAiResponseFormatKind.JsonSchema,
                schema = schema,
            )
        }

        /** Creates an opaque future format. Known types must use their typed factory. */
        fun future(kind: UniversalAiResponseFormatKind): UniversalAiResponseFormat {
            contractRequire(
                condition = !kind.isKnown,
                code = "known_response_format_kind",
                path = "/kind",
            ) {
                "Known response-format kinds must use their typed construction path."
            }
            return UniversalAiResponseFormat(
                kind = kind,
                schema = null,
            )
        }
    }
}

internal object UniversalAiResponseFormatKindSerializer :
    ValidatedStringSerializer<UniversalAiResponseFormatKind>(
        serialName =
            "com.maneesh.universalai.connector.contract.UniversalAiResponseFormatKind",
        create = UniversalAiResponseFormatKind::of,
        rawValue = UniversalAiResponseFormatKind::rawValue,
    )

internal object UniversalAiResponseFormatSerializer : KSerializer<UniversalAiResponseFormat> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UniversalAiResponseFormat,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException(
                    "UniversalAiResponseFormat supports JSON encoding only.",
                )
        val members = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        members["kind"] = JsonPrimitive(value.kind.rawValue)
        if (value.kind == UniversalAiResponseFormatKind.JsonSchema) {
            members["schema"] = checkNotNull(value.schema).elementForSerialization()
        }
        jsonEncoder.encodeJsonElement(JsonObject(members))
    }

    override fun deserialize(decoder: Decoder): UniversalAiResponseFormat {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException(
                    "UniversalAiResponseFormat supports JSON decoding only.",
                )
        val element = jsonDecoder.decodeJsonElement()
        val format =
            element as? JsonObject
                ?: throw SerializationException("Expected an object for responseFormat.")
        val rawKind = format.requiredString("kind")
        val kind =
            decodeFormat {
                UniversalAiResponseFormatKind.of(rawKind)
            }
        if (format["schema"] === JsonNull) {
            throw SerializationException("'schema' must not be null when present.")
        }

        return when (kind) {
            UniversalAiResponseFormatKind.PlainText -> {
                if ("schema" in format) {
                    throw semanticSerializationException(
                        code = "unexpected_response_schema",
                        path = "/schema",
                        message = "Plain-text responseFormat must not contain 'schema'.",
                    )
                }
                UniversalAiResponseFormat.PlainText
            }

            UniversalAiResponseFormatKind.JsonSchema -> {
                val schemaElement =
                    format["schema"]
                        ?.takeUnless { it === JsonNull }
                        ?: throw semanticSerializationException(
                            code = "missing_response_schema",
                            path = "/schema",
                            message = "Structured responseFormat requires non-null 'schema'.",
                        )
                decodeSemanticComponent(pathPrefix = "/schema") {
                    UniversalAiResponseFormat.jsonSchema(
                        schema = StructuredOutputSchema.fromElement(schemaElement),
                    )
                }
            }

            else -> {
                if ("schema" in format) {
                    throw semanticSerializationException(
                        code = "unexpected_response_schema",
                        path = "/schema",
                        message =
                            "Future response-format payloads must use governed extensions.",
                    )
                }
                UniversalAiResponseFormat.future(kind)
            }
        }
    }
}

private const val MAX_FORMAT_KIND_CHARACTERS: Int = 64

private val RESPONSE_FORMAT_KIND_PATTERN = Regex("^[a-z][a-z0-9._-]{0,63}$")

private fun JsonObject.requiredString(name: String): String {
    val value =
        this[name]
            ?: throw SerializationException("Missing required '$name'.")
    if (value === JsonNull || value !is JsonPrimitive || !value.isString) {
        throw SerializationException("'$name' must be a non-null string.")
    }
    return value.content
}

private inline fun <T> decodeFormat(block: () -> T): T =
    try {
        block()
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = failure.message ?: "Invalid responseFormat.",
            cause = failure,
        )
    }
