@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.json.CanonicalJson
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
 * Immutable structured JSON output without a public serialization-DOM dependency.
 *
 * Equality follows JSON semantics: object-member order and equivalent number spellings do not
 * change the value.
 */
@Serializable(with = StructuredOutputValueSerializer::class)
@HiddenFromObjC
class StructuredOutputValue private constructor(
    private val element: JsonElement,
) {
    /** Returns compact JSON for this value. */
    fun toJson(): String =
        CanonicalJson.encode(
            JsonElement.serializer(),
            element,
        )

    internal fun elementForSerialization(): JsonElement = element

    override fun equals(other: Any?): Boolean =
        other is StructuredOutputValue && element.semanticEquals(other.element)

    override fun hashCode(): Int = element.semanticHashCode()

    companion object {
        /** Parses one canonical JSON value with duplicate-member and document-limit checks. */
        fun parse(json: String): StructuredOutputValue =
            StructuredOutputValue(
                CanonicalJson.parseToElement(json),
            )

        internal fun fromElement(element: JsonElement): StructuredOutputValue =
            StructuredOutputValue(element)
    }
}

internal object StructuredOutputValueSerializer : KSerializer<StructuredOutputValue> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: StructuredOutputValue,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException(
                    "StructuredOutputValue supports JSON encoding only.",
                )
        jsonEncoder.encodeJsonElement(value.elementForSerialization())
    }

    override fun deserialize(decoder: Decoder): StructuredOutputValue {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException(
                    "StructuredOutputValue supports JSON decoding only.",
                )
        return StructuredOutputValue.fromElement(jsonDecoder.decodeJsonElement())
    }
}
