@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract.extension

import com.maneesh.universalai.connector.contract.contractRequire
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlin.jvm.JvmInline
import kotlin.native.HiddenFromObjC

/**
 * An exact JSON number token.
 *
 * The raw token is retained instead of being forced through `Double`, so arbitrary precision,
 * exponent spelling, trailing zeroes, and negative zero survive a decode/encode round trip.
 */
@JvmInline
@Serializable(with = ExtensionNumberSerializer::class)
@HiddenFromObjC
value class ExtensionNumber private constructor(
    val rawValue: String,
) {
    /** Returns a signed 64-bit integer only when the token is directly and exactly representable. */
    fun toLongOrNull(): Long? = rawValue.toLongOrNull()

    /** Returns a finite binary floating-point value when one can be parsed without overflow. */
    fun toFiniteDoubleOrNull(): Double? =
        rawValue
            .toDoubleOrNull()
            ?.takeIf { value -> value.isFinite() }

    companion object {
        /** Creates an exact number token or throws [IllegalArgumentException] when invalid. */
        fun of(rawValue: String): ExtensionNumber {
            contractRequire(
                condition = rawValue.length <= ExtensionConstraints.MAX_NUMBER_TOKEN_BYTES,
                code = "extension_number_token_too_long",
                path = "",
            ) {
                "Extension number tokens must not exceed " +
                    "${ExtensionConstraints.MAX_NUMBER_TOKEN_BYTES} ASCII bytes."
            }
            contractRequire(
                condition = JSON_NUMBER_PATTERN.matches(rawValue),
                code = "invalid_extension_number",
                path = "",
            ) {
                "Extension numbers must use the JSON number grammar."
            }
            return ExtensionNumber(rawValue)
        }

        fun of(value: Long): ExtensionNumber = ExtensionNumber(value.toString())

        fun of(value: Int): ExtensionNumber = of(value.toLong())

        fun of(value: Double): ExtensionNumber {
            contractRequire(
                condition = value.isFinite(),
                code = "invalid_extension_number",
                path = "",
            ) {
                "Extension numbers cannot represent NaN or infinity."
            }
            return of(value.toString())
        }
    }
}

private val JSON_NUMBER_PATTERN =
    Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

internal object ExtensionNumberSerializer : KSerializer<ExtensionNumber> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(
            serialName = "com.maneesh.universalai.connector.contract.extension.ExtensionNumber",
            kind = PrimitiveKind.DOUBLE,
        )

    override fun serialize(
        encoder: Encoder,
        value: ExtensionNumber,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("ExtensionNumber supports JSON encoding only.")
        jsonEncoder.encodeJsonElement(JsonUnquotedLiteral(value.rawValue))
    }

    override fun deserialize(decoder: Decoder): ExtensionNumber {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("ExtensionNumber supports JSON decoding only.")
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonPrimitive || element.isString || element.content in BOOLEAN_LITERALS) {
            throw SerializationException("Expected a JSON number for ExtensionNumber.")
        }
        return decodeValidated("Invalid ExtensionNumber") {
            ExtensionNumber.of(element.content)
        }
    }
}

private val BOOLEAN_LITERALS = setOf("true", "false")

internal inline fun <T> decodeValidated(
    subject: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = "$subject: ${failure.message ?: "validation failed."}",
            cause = failure,
        )
    }
