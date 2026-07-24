@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline
import kotlin.native.HiddenFromObjC

/** A stable provider-registry key. The raw value is never normalized. */
@JvmInline
@Serializable(with = ProviderIdSerializer::class)
@HiddenFromObjC
value class ProviderId private constructor(
    val rawValue: String,
) {
    companion object {
        fun of(rawValue: String): ProviderId {
            contractRequire(
                condition = rawValue.length <= MAX_PROVIDER_ID_CHARACTERS,
                code = "invalid_provider_id",
                path = "/providerId",
            ) {
                "Provider IDs must not exceed $MAX_PROVIDER_ID_CHARACTERS ASCII characters."
            }
            contractRequire(
                condition = PROVIDER_ID_PATTERN.matches(rawValue),
                code = "invalid_provider_id",
                path = "/providerId",
            ) {
                "Provider IDs must use lowercase ASCII letters, digits, '.', '_', or '-'."
            }
            return ProviderId(rawValue)
        }
    }
}

/**
 * An opaque provider-owned model identifier.
 *
 * Model IDs preserve their exact value and deliberately allow provider separators such as `/`,
 * `:`, and `@`. They are not URLs or filesystem paths.
 */
@JvmInline
@Serializable(with = ModelIdSerializer::class)
@HiddenFromObjC
value class ModelId private constructor(
    val rawValue: String,
) {
    companion object {
        fun of(rawValue: String): ModelId {
            contractRequire(
                condition = rawValue.isNotEmpty(),
                code = "invalid_model_id",
                path = "/modelId",
            ) {
                "Model IDs must not be empty."
            }
            contractRequire(
                condition = rawValue.isWellFormedContractUnicode(),
                code = "invalid_model_id",
                path = "/modelId",
            ) {
                "Model IDs must contain well-formed Unicode."
            }
            contractRequire(
                condition =
                    rawValue.none { character ->
                        character.isWhitespace() || character.isContractControlCharacter()
                    },
                code = "invalid_model_id",
                path = "/modelId",
            ) {
                "Model IDs must not contain whitespace or control characters."
            }
            contractRequire(
                condition = rawValue.contractUtf8Size() <= MAX_MODEL_ID_BYTES,
                code = "invalid_model_id",
                path = "/modelId",
            ) {
                "Model IDs must not exceed $MAX_MODEL_ID_BYTES UTF-8 bytes."
            }
            return ModelId(rawValue)
        }
    }
}

private const val MAX_PROVIDER_ID_CHARACTERS: Int = 64
private const val MAX_MODEL_ID_BYTES: Int = 256

private val PROVIDER_ID_PATTERN =
    Regex("^[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?$")

internal object ProviderIdSerializer : ValidatedStringSerializer<ProviderId>(
    serialName = "com.maneesh.universalai.connector.contract.ProviderId",
    create = ProviderId::of,
    rawValue = ProviderId::rawValue,
)

internal object ModelIdSerializer : ValidatedStringSerializer<ModelId>(
    serialName = "com.maneesh.universalai.connector.contract.ModelId",
    create = ModelId::of,
    rawValue = ModelId::rawValue,
)

internal abstract class ValidatedStringSerializer<T>(
    serialName: String,
    private val create: (String) -> T,
    private val rawValue: (T) -> String,
) : KSerializer<T> {
    final override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    final override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        encoder.encodeString(rawValue(value))
    }

    final override fun deserialize(decoder: Decoder): T =
        try {
            create(decoder.decodeString())
        } catch (failure: IllegalArgumentException) {
            throw SerializationException(
                message = failure.message ?: "Invalid contract identifier.",
                cause = failure,
            )
        }
}
