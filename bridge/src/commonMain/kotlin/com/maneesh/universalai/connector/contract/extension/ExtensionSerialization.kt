@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.maneesh.universalai.connector.contract.extension

import com.maneesh.universalai.connector.contract.ContractSemanticException
import com.maneesh.universalai.connector.contract.withPathPrefix
import kotlinx.serialization.KSerializer
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
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull

/**
 * JSON serializer for [ExtensionValue].
 *
 * Duplicate member names must be rejected by the canonical codec before this serializer receives
 * a materialized [JsonObject], because the DOM cannot expose names discarded during parsing.
 */
internal object ExtensionValueSerializer : KSerializer<ExtensionValue> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: ExtensionValue,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("ExtensionValue supports JSON encoding only.")
        validateStandaloneExtensionValue(value)
        jsonEncoder.encodeJsonElement(value.toJsonElement())
    }

    override fun deserialize(decoder: Decoder): ExtensionValue {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("ExtensionValue supports JSON decoding only.")
        return decodeValidated("Invalid ExtensionValue") {
            jsonDecoder.decodeJsonElement().toExtensionValue(path = "").also { value ->
                validateStandaloneExtensionValue(value)
            }
        }
    }
}

/**
 * JSON serializer for [Extensions].
 *
 * The surrounding canonical contract serializer owns omission of an empty bag. Duplicate
 * namespace or nested object names must be rejected by the canonical codec before this serializer
 * receives a materialized [JsonObject].
 */
internal object ExtensionsSerializer : KSerializer<Extensions> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: Extensions,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("Extensions supports JSON encoding only.")
        jsonEncoder.encodeJsonElement(
            extensionEntriesToJsonElement(value.entriesForSerialization()),
        )
    }

    override fun deserialize(decoder: Decoder): Extensions {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("Extensions supports JSON decoding only.")
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonObject) {
            throw SerializationException("Expected a JSON object for Extensions.")
        }

        return decodeValidated("Invalid Extensions") {
            Extensions.of(
                element.map { (rawNamespace, rawPayload) ->
                    val namespacePath = extensionChildPath("", rawNamespace)
                    val payload =
                        rawPayload as? JsonObject
                            ?: throw ContractSemanticException(
                                code = "invalid_extension_payload",
                                path = namespacePath,
                                message =
                                    "Each extension namespace payload must be a JSON object.",
                            )
                    ExtensionNamespace.atPath(rawNamespace, namespacePath) to
                        (
                            payload.toExtensionValue(
                                path = namespacePath,
                            ) as ExtensionValue.ObjectValue
                        )
                }.toMap(),
            )
        }
    }
}

internal fun extensionEntriesToJsonElement(
    entries: Map<ExtensionNamespace, ExtensionValue.ObjectValue>,
): JsonObject =
    JsonObject(
        entries.entries
            .sortedBy { entry -> entry.key.rawValue }
            .associate { entry ->
                entry.key.rawValue to entry.value.toJsonElement()
            },
    )

internal fun ExtensionValue.toJsonElement(): JsonElement =
    when (this) {
        ExtensionValue.Null -> JsonNull
        is ExtensionValue.BooleanValue -> JsonPrimitive(value)
        is ExtensionValue.StringValue -> JsonPrimitive(value)
        is ExtensionValue.NumberValue -> JsonUnquotedLiteral(value.rawValue)
        is ExtensionValue.ArrayValue ->
            JsonArray(
                valuesForSerialization().map { value -> value.toJsonElement() },
            )

        is ExtensionValue.ObjectValue ->
            JsonObject(
                membersForSerialization().entries
                    .sortedBy { entry -> entry.key }
                    .associate { entry ->
                        entry.key to entry.value.toJsonElement()
                    },
            )
    }

private fun JsonElement.toExtensionValue(path: String): ExtensionValue =
    when (this) {
        JsonNull -> ExtensionValue.Null
        is JsonArray ->
            ExtensionValue.ArrayValue(
                mapIndexed { index, element ->
                    element.toExtensionValue("$path/$index")
                },
            )

        is JsonObject ->
            ExtensionValue.ObjectValue(
                map { (name, element) ->
                    name to
                        element.toExtensionValue(
                            extensionChildPath(path, name),
                        )
                }.toMap(),
            )

        is JsonPrimitive ->
            when {
                isString -> ExtensionValue.StringValue(content)
                booleanOrNull != null -> ExtensionValue.boolean(booleanOrNull!!)
                else ->
                    try {
                        ExtensionValue.number(ExtensionNumber.of(content))
                    } catch (failure: ContractSemanticException) {
                        throw failure.withPathPrefix(path)
                    }
            }
    }
