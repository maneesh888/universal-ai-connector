@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.json.CanonicalJson
import com.maneesh.universalai.connector.contract.schema.GovernedJsonSchemaSubset
import com.maneesh.universalai.connector.contract.schema.semanticEquals
import com.maneesh.universalai.connector.contract.schema.semanticHashCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlin.native.HiddenFromObjC

/**
 * A validated user-supplied schema for structured output.
 *
 * JSON DOM and validator types remain internal. Callers provide and retrieve ordinary JSON text.
 */
@Serializable(with = StructuredOutputSchemaSerializer::class)
@HiddenFromObjC
class StructuredOutputSchema private constructor(
    private val element: JsonElement,
) {
    /** Returns a compact JSON representation of the validated schema. */
    fun toJson(): String =
        CanonicalJson.encode(
            JsonElement.serializer(),
            element,
        )

    internal fun elementForSerialization(): JsonElement = element

    override fun equals(other: Any?): Boolean =
        other is StructuredOutputSchema && element.semanticEquals(other.element)

    override fun hashCode(): Int = element.semanticHashCode()

    companion object {
        /** Parses and validates a governed Draft 2020-12 schema. */
        fun parse(json: String): StructuredOutputSchema =
            fromElement(
                CanonicalJson.parseToElement(json),
            )

        internal fun fromElement(element: JsonElement): StructuredOutputSchema {
            val encoded =
                CanonicalJson.encode(
                    JsonElement.serializer(),
                    element,
                )
            contractRequire(
                condition =
                    encoded.encodeToByteArray().size <=
                        GovernedJsonSchemaSubset.MAX_SCHEMA_BYTES,
                code = "schema_size_limit_exceeded",
                path = "",
            ) {
                "Structured-output schema exceeds " +
                    "${GovernedJsonSchemaSubset.MAX_SCHEMA_BYTES} compact UTF-8 bytes."
            }
            val issue = GovernedJsonSchemaSubset.validate(element)
            if (issue != null) {
                throw ContractSemanticException(
                    code = issue.code,
                    path = issue.path,
                    message = "${issue.code} at ${issue.path}",
                )
            }
            return StructuredOutputSchema(element)
        }
    }
}

internal object StructuredOutputSchemaSerializer : KSerializer<StructuredOutputSchema> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: StructuredOutputSchema,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException(
                    "StructuredOutputSchema supports JSON encoding only.",
                )
        jsonEncoder.encodeJsonElement(value.elementForSerialization())
    }

    override fun deserialize(decoder: Decoder): StructuredOutputSchema {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException(
                    "StructuredOutputSchema supports JSON decoding only.",
                )
        return try {
            StructuredOutputSchema.fromElement(jsonDecoder.decodeJsonElement())
        } catch (failure: IllegalArgumentException) {
            throw SerializationException(
                message = failure.message ?: "Invalid structured-output schema.",
                cause = failure,
            )
        }
    }
}
