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
import kotlin.jvm.JvmInline
import kotlin.native.HiddenFromObjC

/** A raw-backed, forward-compatible successful response completion reason. */
@JvmInline
@Serializable(with = UniversalAiCompletionReasonSerializer::class)
@HiddenFromObjC
value class UniversalAiCompletionReason private constructor(
    val rawValue: String,
) {
    val isKnown: Boolean
        get() = this == Stop || this == MaxOutputTokens || this == ContentFilter

    companion object {
        val Stop = UniversalAiCompletionReason("stop")
        val MaxOutputTokens = UniversalAiCompletionReason("max_output_tokens")
        val ContentFilter = UniversalAiCompletionReason("content_filter")

        fun of(rawValue: String): UniversalAiCompletionReason {
            contractRequire(
                condition = COMPLETION_REASON_PATTERN.matches(rawValue),
                code = "invalid_completion_reason",
                path = "/completionReason",
            ) {
                "Completion reasons must be 1-$MAX_COMPLETION_REASON_CHARACTERS lowercase ASCII characters."
            }
            return UniversalAiCompletionReason(rawValue)
        }
    }
}

/** A V1 provider-neutral completed response. */
@Serializable(with = UniversalAiResponseSerializer::class)
@HiddenFromObjC
class UniversalAiResponse(
    val id: ResponseId,
    val requestId: RequestId? = null,
    val target: UniversalAiTarget,
    outputs: List<UniversalAiOutput>,
    val usage: UniversalAiUsage? = null,
    val completionReason: UniversalAiCompletionReason,
    val extensions: Extensions = Extensions.Empty,
) {
    private val storedOutputs = outputs.toList()

    val contractVersion: String
        get() = CURRENT_CONTRACT_VERSION

    /** Returns a defensive snapshot retaining canonical output order. */
    val outputs: List<UniversalAiOutput>
        get() = storedOutputs.toList()

    init {
        contractRequire(
            condition = storedOutputs.size <= MAX_RESPONSE_OUTPUTS,
            code = "response_output_limit_exceeded",
            path = "/outputs",
        ) {
            "Responses must not contain more than $MAX_RESPONSE_OUTPUTS outputs."
        }
        storedOutputs.forEachIndexed { expectedIndex, output ->
            contractRequire(
                condition = output.index == expectedIndex,
                code = "output_index_mismatch",
                path = "/outputs/$expectedIndex/index",
            ) {
                "Each output index must equal its position in the response outputs list."
            }
            val firstIndex = storedOutputs.indexOfFirst { candidate -> candidate.id == output.id }
            contractRequire(
                condition = firstIndex == expectedIndex,
                code = "duplicate_output_id",
                path = "/outputs/$expectedIndex/id",
            ) {
                "Response output IDs must be unique."
            }
        }
    }

    /** Encodes through the strict canonical codec, including the required wire-version marker. */
    fun toJson(): String =
        CanonicalJson.encode(
            UniversalAiResponse.serializer(),
            this,
        )

    internal fun outputsForSerialization(): List<UniversalAiOutput> = storedOutputs

    override fun equals(other: Any?): Boolean =
        other is UniversalAiResponse &&
            id == other.id &&
            requestId == other.requestId &&
            target == other.target &&
            storedOutputs == other.storedOutputs &&
            usage == other.usage &&
            completionReason == other.completionReason &&
            extensions == other.extensions

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (requestId?.hashCode() ?: 0)
        result = 31 * result + target.hashCode()
        result = 31 * result + storedOutputs.hashCode()
        result = 31 * result + (usage?.hashCode() ?: 0)
        result = 31 * result + completionReason.hashCode()
        result = 31 * result + extensions.hashCode()
        return result
    }

    companion object {
        /** Decodes with duplicate-member, document-size, and semantic validation enabled. */
        fun fromJson(json: String): UniversalAiResponse =
            CanonicalJson.decode(
                UniversalAiResponse.serializer(),
                json,
            )
    }
}

private const val MAX_COMPLETION_REASON_CHARACTERS: Int = 64

private val COMPLETION_REASON_PATTERN = Regex("^[a-z][a-z0-9._-]{0,63}$")

internal object UniversalAiCompletionReasonSerializer :
    ValidatedStringSerializer<UniversalAiCompletionReason>(
        serialName = "com.maneesh.universalai.connector.contract.UniversalAiCompletionReason",
        create = UniversalAiCompletionReason::of,
        rawValue = UniversalAiCompletionReason::rawValue,
    )

internal object UniversalAiResponseSerializer : KSerializer<UniversalAiResponse> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UniversalAiResponse,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("UniversalAiResponse supports JSON encoding only.")
        val members = linkedMapOf<String, JsonElement>()
        members["contractVersion"] = JsonPrimitive(CURRENT_CONTRACT_VERSION)
        members["id"] = JsonPrimitive(value.id.rawValue)
        value.requestId?.let { requestId ->
            members["requestId"] = JsonPrimitive(requestId.rawValue)
        }
        members["target"] =
            CanonicalJson.format.encodeToJsonElement(
                UniversalAiTarget.serializer(),
                value.target,
            )
        members["outputs"] =
            JsonArray(
                value.outputsForSerialization().map { output ->
                    CanonicalJson.format.encodeToJsonElement(
                        UniversalAiOutput.serializer(),
                        output,
                    )
                },
            )
        value.usage?.let { usage ->
            members["usage"] =
                CanonicalJson.format.encodeToJsonElement(
                    UniversalAiUsage.serializer(),
                    usage,
                )
        }
        members["completionReason"] = JsonPrimitive(value.completionReason.rawValue)
        if (!value.extensions.isEmpty) {
            members["extensions"] =
                CanonicalJson.format.encodeToJsonElement(
                    Extensions.serializer(),
                    value.extensions,
                )
        }
        jsonEncoder.encodeJsonElement(JsonObject(members))
    }

    override fun deserialize(decoder: Decoder): UniversalAiResponse {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("UniversalAiResponse supports JSON decoding only.")
        val document =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: throw SerializationException("Expected an object for UniversalAiResponse.")

        val contractVersion = document.requiredResponseString("contractVersion")
        if (contractVersion != CURRENT_CONTRACT_VERSION) {
            throw semanticSerializationException(
                code = "unsupported_contract_version",
                path = "/contractVersion",
                message = "Unsupported contractVersion '$contractVersion'.",
            )
        }

        return decodeResponse {
            UniversalAiResponse(
                id =
                    decodeSemanticComponent(pathPrefix = "") {
                        ResponseId.of(document.requiredResponseString("id"))
                    },
                requestId =
                    document.optionalResponseNonNull("requestId")?.let { element ->
                        val rawValue =
                            element as? JsonPrimitive
                                ?: throw SerializationException(
                                    "'requestId' must be a string.",
                                )
                        if (!rawValue.isString) {
                            throw SerializationException("'requestId' must be a string.")
                        }
                        decodeSemanticComponent(pathPrefix = "") {
                            RequestId.of(rawValue.content)
                        }
                    },
                target =
                    decodeSemanticComponent(pathPrefix = "/target") {
                        CanonicalJson.format.decodeFromJsonElement(
                            UniversalAiTarget.serializer(),
                            document.requiredResponseNonNull("target"),
                        )
                    },
                outputs =
                    document.requiredResponseArray("outputs").mapIndexed { index, element ->
                        decodeSemanticComponent(pathPrefix = "/outputs/$index") {
                            CanonicalJson.format.decodeFromJsonElement(
                                UniversalAiOutput.serializer(),
                                element,
                            )
                        }
                    },
                usage =
                    document.optionalResponseNonNull("usage")?.let { element ->
                        decodeSemanticComponent(pathPrefix = "/usage") {
                            CanonicalJson.format.decodeFromJsonElement(
                                UniversalAiUsage.serializer(),
                                element,
                            )
                        }
                    },
                completionReason =
                    decodeSemanticComponent(pathPrefix = "") {
                        UniversalAiCompletionReason.of(
                            document.requiredResponseString("completionReason"),
                        )
                    },
                extensions =
                    document.optionalResponseNonNull("extensions")?.let { element ->
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

private fun JsonObject.requiredResponseNonNull(name: String): JsonElement =
    this[name]
        ?.takeUnless { it === JsonNull }
        ?: throw SerializationException("Missing required non-null '$name'.")

private fun JsonObject.optionalResponseNonNull(name: String): JsonElement? {
    val value = this[name] ?: return null
    if (value === JsonNull) {
        throw SerializationException("'$name' must not be null.")
    }
    return value
}

private fun JsonObject.requiredResponseString(name: String): String {
    val value = requiredResponseNonNull(name)
    if (value !is JsonPrimitive || !value.isString) {
        throw SerializationException("'$name' must be a string.")
    }
    return value.content
}

private fun JsonObject.requiredResponseArray(name: String): JsonArray =
    requiredResponseNonNull(name) as? JsonArray
        ?: throw SerializationException("'$name' must be an array.")

private inline fun <T> decodeResponse(block: () -> T): T =
    try {
        block()
    } catch (failure: SerializationException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = failure.message ?: "Invalid UniversalAiResponse.",
            cause = failure,
        )
    }
