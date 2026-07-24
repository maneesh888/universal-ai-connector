package com.maneesh.universalai.connector.contract.schema

import com.maneesh.universalai.connector.contract.json.JsonNumberSemantics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal fun JsonElement.semanticEquals(other: JsonElement): Boolean =
    when {
        this === JsonNull || other === JsonNull -> this === JsonNull && other === JsonNull
        this is JsonArray && other is JsonArray ->
            size == other.size &&
                indices.all { index -> this[index].semanticEquals(other[index]) }
        this is JsonObject && other is JsonObject ->
            keys == other.keys &&
                all { (name, value) ->
                    value.semanticEquals(other.getValue(name))
                }
        this is JsonPrimitive && other is JsonPrimitive ->
            when {
                isString || other.isString -> isString && other.isString && content == other.content
                booleanOrNull != null || other.booleanOrNull != null ->
                    booleanOrNull != null &&
                        other.booleanOrNull != null &&
                        booleanOrNull == other.booleanOrNull
                else ->
                    JsonNumberSemantics.normalize(content) ==
                        JsonNumberSemantics.normalize(other.content)
            }
        else -> false
    }

internal fun JsonElement.semanticHashCode(): Int =
    when (this) {
        JsonNull -> NULL_HASH
        is JsonArray ->
            fold(ARRAY_HASH) { result, value ->
                31 * result + value.semanticHashCode()
            }
        is JsonObject ->
            entries.fold(OBJECT_HASH) { result, (name, value) ->
                result + (31 * name.hashCode() xor value.semanticHashCode())
            }
        is JsonPrimitive ->
            when {
                isString -> 31 * STRING_HASH + content.hashCode()
                booleanOrNull != null -> 31 * BOOLEAN_HASH + booleanOrNull.hashCode()
                else ->
                    31 * NUMBER_HASH +
                        checkNotNull(JsonNumberSemantics.normalize(content)).hashCode()
            }
    }

private const val NULL_HASH: Int = 0x10
private const val BOOLEAN_HASH: Int = 0x20
private const val STRING_HASH: Int = 0x30
private const val NUMBER_HASH: Int = 0x40
private const val ARRAY_HASH: Int = 0x50
private const val OBJECT_HASH: Int = 0x60
