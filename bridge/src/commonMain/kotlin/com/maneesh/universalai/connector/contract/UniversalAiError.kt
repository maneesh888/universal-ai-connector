@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.contract.extension.Extensions
import com.maneesh.universalai.connector.contract.json.CanonicalJson
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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.jvm.JvmInline
import kotlin.native.HiddenFromObjC

/** A raw-backed, forward-compatible canonical error category. */
@JvmInline
@Serializable(with = UniversalAiErrorCategorySerializer::class)
@HiddenFromObjC
value class UniversalAiErrorCategory private constructor(
    val rawValue: String,
) {
    val isKnown: Boolean
        get() = this in Known

    companion object {
        val Validation = UniversalAiErrorCategory("validation")
        val Authentication = UniversalAiErrorCategory("authentication")
        val Authorization = UniversalAiErrorCategory("authorization")
        val NotFound = UniversalAiErrorCategory("not_found")
        val RateLimit = UniversalAiErrorCategory("rate_limit")
        val Transport = UniversalAiErrorCategory("transport")
        val Provider = UniversalAiErrorCategory("provider")
        val Protocol = UniversalAiErrorCategory("protocol")
        val Internal = UniversalAiErrorCategory("internal")

        private val Known =
            setOf(
                Validation,
                Authentication,
                Authorization,
                NotFound,
                RateLimit,
                Transport,
                Provider,
                Protocol,
                Internal,
            )

        fun of(rawValue: String): UniversalAiErrorCategory {
            contractRequire(
                condition = ERROR_CATEGORY_PATTERN.matches(rawValue),
                code = "invalid_error_category",
                path = "/category",
            ) {
                "Error categories must be 1-$MAX_ERROR_CATEGORY_CHARACTERS lowercase ASCII characters."
            }
            return UniversalAiErrorCategory(rawValue)
        }
    }
}

/** A raw-backed, forward-compatible canonical error code. */
@JvmInline
@Serializable(with = UniversalAiErrorCodeSerializer::class)
@HiddenFromObjC
value class UniversalAiErrorCode private constructor(
    val rawValue: String,
) {
    val isKnown: Boolean
        get() = this in Known

    companion object {
        val InvalidInput = UniversalAiErrorCode("invalid_input")
        val InvalidRequest = UniversalAiErrorCode("invalid_request")
        val InvalidStreamSequence = UniversalAiErrorCode("invalid_stream_sequence")
        val IncompleteStream = UniversalAiErrorCode("incomplete_stream")
        val UnsupportedTerminalEvent = UniversalAiErrorCode("unsupported_terminal_event")
        val SimulatedFailure = UniversalAiErrorCode("simulated_failure")
        val ConnectorFailure = UniversalAiErrorCode("connector_failure")

        private val Known =
            setOf(
                InvalidInput,
                InvalidRequest,
                InvalidStreamSequence,
                IncompleteStream,
                UnsupportedTerminalEvent,
                SimulatedFailure,
                ConnectorFailure,
            )

        fun of(rawValue: String): UniversalAiErrorCode {
            contractRequire(
                condition = ERROR_CODE_PATTERN.matches(rawValue),
                code = "invalid_error_code",
                path = "/code",
            ) {
                "Error codes must be 1-$MAX_ERROR_CODE_CHARACTERS lowercase ASCII characters."
            }
            return UniversalAiErrorCode(rawValue)
        }
    }
}

/**
 * A serializable provider-neutral failure.
 *
 * [message] is safe for ordinary display and logging but is not a machine discriminator.
 */
@Serializable(with = UniversalAiErrorSerializer::class)
@HiddenFromObjC
class UniversalAiError(
    val category: UniversalAiErrorCategory,
    val code: UniversalAiErrorCode,
    val message: String,
    val metadata: ExtensionValue.ObjectValue? = null,
    val extensions: Extensions = Extensions.Empty,
) {
    init {
        contractRequire(
            condition = message.isNotBlank(),
            code = "invalid_error_message",
            path = "/message",
        ) {
            "Error messages must not be blank."
        }
        contractRequire(
            condition = message.isWellFormedContractUnicode(),
            code = "invalid_error_message",
            path = "/message",
        ) {
            "Error messages must contain well-formed Unicode."
        }
        contractRequire(
            condition = message.none(Char::isUnsafeErrorMessageCharacter),
            code = "invalid_error_message",
            path = "/message",
        ) {
            "Error messages must not contain control, line-separator, or bidirectional-control characters."
        }
        contractRequire(
            condition = message.contractUtf8Size() <= MAX_ERROR_MESSAGE_BYTES,
            code = "invalid_error_message",
            path = "/message",
        ) {
            "Error messages must not exceed $MAX_ERROR_MESSAGE_BYTES UTF-8 bytes."
        }
    }

    /** Encodes this error through the strict canonical JSON codec. */
    fun toJson(): String =
        CanonicalJson.encode(
            UniversalAiError.serializer(),
            this,
        )

    override fun equals(other: Any?): Boolean =
        other is UniversalAiError &&
            category == other.category &&
            code == other.code &&
            message == other.message &&
            metadata == other.metadata &&
            extensions == other.extensions

    override fun hashCode(): Int {
        var result = category.hashCode()
        result = 31 * result + code.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + (metadata?.hashCode() ?: 0)
        result = 31 * result + extensions.hashCode()
        return result
    }

    companion object {
        /** Decodes with duplicate-member, document-size, and semantic validation enabled. */
        fun fromJson(json: String): UniversalAiError =
            CanonicalJson.decode(
                UniversalAiError.serializer(),
                json,
            )
    }
}

private const val MAX_ERROR_CATEGORY_CHARACTERS: Int = 64
private const val MAX_ERROR_CODE_CHARACTERS: Int = 128
private const val MAX_ERROR_MESSAGE_BYTES: Int = 4_096

private val ERROR_CATEGORY_PATTERN = Regex("^[a-z][a-z0-9._-]{0,63}$")
private val ERROR_CODE_PATTERN = Regex("^[a-z][a-z0-9._-]{0,127}$")

internal object UniversalAiErrorCategorySerializer :
    ValidatedStringSerializer<UniversalAiErrorCategory>(
        serialName = "com.maneesh.universalai.connector.contract.UniversalAiErrorCategory",
        create = UniversalAiErrorCategory::of,
        rawValue = UniversalAiErrorCategory::rawValue,
    )

internal object UniversalAiErrorCodeSerializer :
    ValidatedStringSerializer<UniversalAiErrorCode>(
        serialName = "com.maneesh.universalai.connector.contract.UniversalAiErrorCode",
        create = UniversalAiErrorCode::of,
        rawValue = UniversalAiErrorCode::rawValue,
    )

internal object UniversalAiErrorSerializer : KSerializer<UniversalAiError> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UniversalAiError,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("UniversalAiError supports JSON encoding only.")
        val members = linkedMapOf<String, JsonElement>()
        members["category"] = JsonPrimitive(value.category.rawValue)
        members["code"] = JsonPrimitive(value.code.rawValue)
        members["message"] = JsonPrimitive(value.message)
        value.metadata?.let { metadata ->
            members["metadata"] =
                CanonicalJson.format.encodeToJsonElement(
                    ExtensionValue.serializer(),
                    metadata,
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

    override fun deserialize(decoder: Decoder): UniversalAiError {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("UniversalAiError supports JSON decoding only.")
        val document =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: throw SerializationException("Expected an object for UniversalAiError.")

        return decodeError {
            UniversalAiError(
                category =
                    decodeSemanticComponent(pathPrefix = "") {
                        UniversalAiErrorCategory.of(document.requiredErrorString("category"))
                    },
                code =
                    decodeSemanticComponent(pathPrefix = "") {
                        UniversalAiErrorCode.of(document.requiredErrorString("code"))
                    },
                message = document.requiredErrorString("message"),
                metadata =
                    document.optionalErrorNonNull("metadata")?.let { element ->
                        val value =
                            decodeSemanticComponent(pathPrefix = "/metadata") {
                                CanonicalJson.format.decodeFromJsonElement(
                                    ExtensionValue.serializer(),
                                    element,
                                )
                            }
                        value as? ExtensionValue.ObjectValue
                            ?: throw SerializationException("'metadata' must be an object.")
                    },
                extensions =
                    document.optionalErrorNonNull("extensions")?.let { element ->
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

private fun JsonObject.requiredErrorString(name: String): String {
    val value =
        this[name]
            ?: throw SerializationException("Missing required '$name'.")
    if (value === JsonNull || value !is JsonPrimitive || !value.isString) {
        throw SerializationException("'$name' must be a non-null string.")
    }
    return value.content
}

private fun JsonObject.optionalErrorNonNull(name: String): JsonElement? {
    val value = this[name] ?: return null
    if (value === JsonNull) {
        throw SerializationException("'$name' must not be null.")
    }
    return value
}

private inline fun <T> decodeError(block: () -> T): T =
    try {
        block()
    } catch (failure: SerializationException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = failure.message ?: "Invalid UniversalAiError.",
            cause = failure,
        )
    }
