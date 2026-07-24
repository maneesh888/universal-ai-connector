@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.extension.Extensions
import com.maneesh.universalai.connector.contract.json.CURRENT_CONTRACT_VERSION
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

/** A raw-backed, forward-compatible stream-event discriminator. */
@JvmInline
@Serializable(with = UniversalAiStreamEventTypeSerializer::class)
@HiddenFromObjC
value class UniversalAiStreamEventType private constructor(
    val rawValue: String,
) {
    val isKnown: Boolean
        get() = this in Known

    companion object {
        val ResponseStarted = UniversalAiStreamEventType("response.started")
        val OutputStarted = UniversalAiStreamEventType("output.started")
        val OutputDelta = UniversalAiStreamEventType("output.delta")
        val OutputCompleted = UniversalAiStreamEventType("output.completed")
        val UsageUpdated = UniversalAiStreamEventType("usage.updated")
        val ResponseCompleted = UniversalAiStreamEventType("response.completed")

        private val Known =
            setOf(
                ResponseStarted,
                OutputStarted,
                OutputDelta,
                OutputCompleted,
                UsageUpdated,
                ResponseCompleted,
            )

        fun of(rawValue: String): UniversalAiStreamEventType {
            contractRequire(
                condition = STREAM_EVENT_TYPE_PATTERN.matches(rawValue),
                code = "invalid_stream_event_type",
                path = "/type",
            ) {
                "Stream-event types must be 1-$MAX_STREAM_EVENT_TYPE_CHARACTERS lowercase ASCII characters."
            }
            return UniversalAiStreamEventType(rawValue)
        }
    }
}

/**
 * One V1 correlated stream event.
 *
 * Unknown future event types remain distinguishable and round-trip through [type]. They may not
 * claim terminal semantics that this contract version cannot safely interpret. Operation failures
 * are delivered out of band as host-native throws carrying a [UniversalAiError] mapping, and caller
 * cancellation remains Kotlin `CancellationException` or Swift `CancellationError`; neither
 * failure nor cancellation is a stream event.
 */
@Serializable(with = UniversalAiStreamEventSerializer::class)
@HiddenFromObjC
class UniversalAiStreamEvent(
    val type: UniversalAiStreamEventType,
    val terminal: Boolean,
    val sequence: Long,
    val responseId: ResponseId,
    val requestId: RequestId? = null,
    val outputId: OutputId? = null,
    val outputIndex: Int? = null,
    val delta: String? = null,
    val output: UniversalAiOutput? = null,
    val usage: UniversalAiUsage? = null,
    val response: UniversalAiResponse? = null,
    val extensions: Extensions = Extensions.Empty,
) {
    val contractVersion: String
        get() = CURRENT_CONTRACT_VERSION

    init {
        contractRequire(
            condition = sequence in 1..MAX_JSON_SAFE_INTEGER,
            code = INVALID_STREAM_SEQUENCE,
            path = "/sequence",
        ) {
            "Stream-event sequence must be a positive JSON safe integer."
        }
        outputIndex?.let { index ->
            contractRequire(
                condition = index in 0 until MAX_RESPONSE_OUTPUTS,
                code = INVALID_STREAM_SEQUENCE,
                path = "/outputIndex",
            ) {
                "Stream-event outputIndex must be between 0 and ${MAX_RESPONSE_OUTPUTS - 1}."
            }
        }
        delta?.let { value ->
            contractRequire(
                condition = value.isNotEmpty(),
                code = INVALID_STREAM_SEQUENCE,
                path = "/delta",
            ) {
                "Stream-event deltas must not be empty."
            }
            contractRequire(
                condition = value.isWellFormedContractUnicode(),
                code = INVALID_STREAM_SEQUENCE,
                path = "/delta",
            ) {
                "Stream-event deltas must contain well-formed Unicode."
            }
            contractRequire(
                condition = value.contractUtf8Size() <= MAX_STREAM_DELTA_BYTES,
                code = INVALID_STREAM_SEQUENCE,
                path = "/delta",
            ) {
                "Stream-event deltas must not exceed $MAX_STREAM_DELTA_BYTES UTF-8 bytes."
            }
        }

        if (!type.isKnown && terminal) {
            throw ContractSemanticException(
                code = UNSUPPORTED_TERMINAL_EVENT,
                path = "/terminal",
                message = "Unknown stream-event types cannot be terminal.",
            )
        }
        contractRequire(
            condition =
                if (type == UniversalAiStreamEventType.ResponseCompleted) {
                    terminal
                } else {
                    !terminal
                },
            code = INVALID_STREAM_SEQUENCE,
            path = "/terminal",
        ) {
            "Only response.completed may be terminal, and it must be terminal."
        }
        contractRequire(
            condition = (outputId == null) == (outputIndex == null),
            code = INVALID_STREAM_SEQUENCE,
            path = if (outputId == null) "/outputId" else "/outputIndex",
        ) {
            "outputId and outputIndex must either both be present or both be absent."
        }
        output?.let { completedOutput ->
            contractRequire(
                condition = outputId == completedOutput.id,
                code = INVALID_STREAM_SEQUENCE,
                path = "/output/id",
            ) {
                "The output payload ID must match outputId."
            }
            contractRequire(
                condition = outputIndex == completedOutput.index,
                code = INVALID_STREAM_SEQUENCE,
                path = "/output/index",
            ) {
                "The output payload index must match outputIndex."
            }
        }
        response?.let { finalResponse ->
            contractRequire(
                condition = responseId == finalResponse.id,
                code = INVALID_STREAM_SEQUENCE,
                path = "/response/id",
            ) {
                "The final response ID must match responseId."
            }
            contractRequire(
                condition = requestId == finalResponse.requestId,
                code = INVALID_STREAM_SEQUENCE,
                path = "/response/requestId",
            ) {
                "The final response requestId must match the event requestId."
            }
        }

        validateKnownEventCoupling()
    }

    /** Encodes through the strict canonical codec, including the required wire-version marker. */
    fun toJson(): String =
        CanonicalJson.encode(
            UniversalAiStreamEvent.serializer(),
            this,
        )

    override fun equals(other: Any?): Boolean =
        other is UniversalAiStreamEvent &&
            type == other.type &&
            terminal == other.terminal &&
            sequence == other.sequence &&
            requestId == other.requestId &&
            responseId == other.responseId &&
            outputId == other.outputId &&
            outputIndex == other.outputIndex &&
            delta == other.delta &&
            output == other.output &&
            usage == other.usage &&
            response == other.response &&
            extensions == other.extensions

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + terminal.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + (requestId?.hashCode() ?: 0)
        result = 31 * result + responseId.hashCode()
        result = 31 * result + (outputId?.hashCode() ?: 0)
        result = 31 * result + (outputIndex ?: 0)
        result = 31 * result + (delta?.hashCode() ?: 0)
        result = 31 * result + (output?.hashCode() ?: 0)
        result = 31 * result + (usage?.hashCode() ?: 0)
        result = 31 * result + (response?.hashCode() ?: 0)
        result = 31 * result + extensions.hashCode()
        return result
    }

    companion object {
        /** Decodes with duplicate-member, document-size, and semantic validation enabled. */
        fun fromJson(json: String): UniversalAiStreamEvent =
            CanonicalJson.decode(
                UniversalAiStreamEvent.serializer(),
                json,
            )
    }

    private fun validateKnownEventCoupling() {
        when (type) {
            UniversalAiStreamEventType.ResponseStarted ->
                requireFields(
                    requiredOutputScope = false,
                    allowedDelta = false,
                    allowedOutput = false,
                    allowedUsage = false,
                    allowedResponse = false,
                )

            UniversalAiStreamEventType.OutputStarted ->
                requireFields(
                    requiredOutputScope = true,
                    allowedDelta = false,
                    allowedOutput = false,
                    allowedUsage = false,
                    allowedResponse = false,
                )

            UniversalAiStreamEventType.OutputDelta ->
                requireFields(
                    requiredOutputScope = true,
                    allowedDelta = true,
                    allowedOutput = false,
                    allowedUsage = false,
                    allowedResponse = false,
                    requiredDelta = true,
                )

            UniversalAiStreamEventType.OutputCompleted ->
                requireFields(
                    requiredOutputScope = true,
                    allowedDelta = false,
                    allowedOutput = true,
                    allowedUsage = false,
                    allowedResponse = false,
                    requiredOutput = true,
                )

            UniversalAiStreamEventType.UsageUpdated ->
                requireFields(
                    requiredOutputScope = false,
                    allowedDelta = false,
                    allowedOutput = false,
                    allowedUsage = true,
                    allowedResponse = false,
                    requiredUsage = true,
                )

            UniversalAiStreamEventType.ResponseCompleted ->
                requireFields(
                    requiredOutputScope = false,
                    allowedDelta = false,
                    allowedOutput = false,
                    allowedUsage = false,
                    allowedResponse = true,
                    requiredResponse = true,
                )

            else -> Unit
        }
    }

    private fun requireFields(
        requiredOutputScope: Boolean,
        allowedDelta: Boolean,
        allowedOutput: Boolean,
        allowedUsage: Boolean,
        allowedResponse: Boolean,
        requiredDelta: Boolean = false,
        requiredOutput: Boolean = false,
        requiredUsage: Boolean = false,
        requiredResponse: Boolean = false,
    ) {
        contractRequire(
            condition =
                if (requiredOutputScope) {
                    outputId != null && outputIndex != null
                } else {
                    outputId == null && outputIndex == null
                },
            code = INVALID_STREAM_SEQUENCE,
            path = "/outputId",
        ) {
            if (requiredOutputScope) {
                "${type.rawValue} requires outputId and outputIndex."
            } else {
                "${type.rawValue} must not contain outputId or outputIndex."
            }
        }
        requireOptionalField(
            present = delta != null,
            allowed = allowedDelta,
            required = requiredDelta,
            path = "/delta",
        )
        requireOptionalField(
            present = output != null,
            allowed = allowedOutput,
            required = requiredOutput,
            path = "/output",
        )
        requireOptionalField(
            present = usage != null,
            allowed = allowedUsage,
            required = requiredUsage,
            path = "/usage",
        )
        requireOptionalField(
            present = response != null,
            allowed = allowedResponse,
            required = requiredResponse,
            path = "/response",
        )
    }

    private fun requireOptionalField(
        present: Boolean,
        allowed: Boolean,
        required: Boolean,
        path: String,
    ) {
        contractRequire(
            condition = (!present || allowed) && (!required || present),
            code = INVALID_STREAM_SEQUENCE,
            path = path,
        ) {
            when {
                required -> "${type.rawValue} requires '$path'."
                else -> "${type.rawValue} must not contain '$path'."
            }
        }
    }
}

/**
 * Stateful, side-effect-free validator for one decoded stream.
 *
 * Feed events in observation order through [accept], then call [finish] when the surrounding Flow
 * completes. The validator owns only in-memory protocol state and performs no coroutine work.
 */
@HiddenFromObjC
class UniversalAiStreamSequenceValidator {
    private var expectedSequence = 1L
    private var started = false
    private var terminalAccepted = false
    private var expectedRequestId: RequestId? = null
    private var expectedResponseId: ResponseId? = null
    private val outputsById = mutableMapOf<OutputId, OutputState>()
    private val outputIdsByIndex = mutableMapOf<Int, OutputId>()
    private var latestUsage: UniversalAiUsage? = null

    fun accept(event: UniversalAiStreamEvent) {
        streamRequire(
            condition = !terminalAccepted,
            path = "/terminal",
        ) {
            "A stream must not emit events after its terminal event."
        }
        streamRequire(
            condition = event.sequence == expectedSequence,
            path = "/sequence",
        ) {
            "Expected stream sequence $expectedSequence but received ${event.sequence}."
        }

        if (!started) {
            streamRequire(
                condition = event.type == UniversalAiStreamEventType.ResponseStarted,
                path = "/type",
            ) {
                "response.started must be the first stream event."
            }
            started = true
            expectedRequestId = event.requestId
            expectedResponseId = event.responseId
        } else {
            streamRequire(
                condition = event.type != UniversalAiStreamEventType.ResponseStarted,
                path = "/type",
            ) {
                "response.started must occur exactly once."
            }
            streamRequire(
                condition = event.requestId == expectedRequestId,
                path = "/requestId",
            ) {
                "Every stream event must retain the initial requestId."
            }
            streamRequire(
                condition = event.responseId == expectedResponseId,
                path = "/responseId",
            ) {
                "Every stream event must retain the initial responseId."
            }
        }

        rejectOutputScopedEventAfterCompletion(event)
        when (event.type) {
            UniversalAiStreamEventType.ResponseStarted -> Unit
            UniversalAiStreamEventType.OutputStarted -> acceptOutputStarted(event)
            UniversalAiStreamEventType.OutputDelta -> acceptOutputDelta(event)
            UniversalAiStreamEventType.OutputCompleted -> acceptOutputCompleted(event)
            UniversalAiStreamEventType.UsageUpdated -> acceptUsageUpdated(event)
            UniversalAiStreamEventType.ResponseCompleted -> acceptResponseCompleted(event)
            else -> Unit
        }

        expectedSequence += 1
        if (event.terminal) {
            terminalAccepted = true
        }
    }

    /**
     * Confirms that the surrounding stream ended after its explicit response.completed event.
     */
    fun finish() {
        if (!terminalAccepted) {
            throw ContractSemanticException(
                code = INCOMPLETE_STREAM,
                path = "/terminal",
                message = "The stream completed without a response.completed terminal event.",
            )
        }
    }

    private fun acceptOutputStarted(event: UniversalAiStreamEvent) {
        val id = checkNotNull(event.outputId)
        val index = checkNotNull(event.outputIndex)
        streamRequire(
            condition = id !in outputsById,
            path = "/outputId",
        ) {
            "Each outputId must be started exactly once."
        }
        streamRequire(
            condition = index !in outputIdsByIndex,
            path = "/outputIndex",
        ) {
            "Each outputIndex must identify exactly one output."
        }
        outputsById[id] = OutputState(id = id, index = index)
        outputIdsByIndex[index] = id
    }

    private fun acceptOutputDelta(event: UniversalAiStreamEvent) {
        val state = requireOpenOutput(event)
        val delta = checkNotNull(event.delta)
        val deltaBytes = delta.contractUtf8Size()
        streamRequire(
            condition =
                state.accumulatedDeltaBytes <=
                    MAX_STREAM_DELTA_BYTES - deltaBytes,
            path = "/delta",
        ) {
            "Accumulated output deltas must not exceed $MAX_STREAM_DELTA_BYTES UTF-8 bytes."
        }
        state.deltas.append(delta)
        state.accumulatedDeltaBytes += deltaBytes
    }

    private fun acceptOutputCompleted(event: UniversalAiStreamEvent) {
        val state = requireOpenOutput(event)
        val completedOutput = checkNotNull(event.output)
        val expectedContent =
            completedOutput.text
                ?: completedOutput.structuredJson?.toJson()
        if (expectedContent == null) {
            streamRequire(
                condition = state.deltas.isEmpty(),
                path = "/output",
            ) {
                "Future output kinds cannot complete after portable delta content."
            }
        } else if (state.deltas.isNotEmpty()) {
            streamRequire(
                condition = state.deltas.toString() == expectedContent,
                path = "/output",
            ) {
                "Concatenated output deltas must equal the completed output content."
            }
        }
        state.completedOutput = completedOutput
    }

    private fun acceptUsageUpdated(event: UniversalAiStreamEvent) {
        val current = checkNotNull(event.usage)
        latestUsage?.let { previous ->
            requireMonotonicUsage(
                previous = previous,
                current = current,
                path = "/usage",
            )
        }
        latestUsage = current
    }

    private fun acceptResponseCompleted(event: UniversalAiStreamEvent) {
        val finalResponse = checkNotNull(event.response)
        streamRequire(
            condition = outputsById.values.none { state -> !state.isCompleted },
            path = "/response/outputs",
        ) {
            "response.completed requires every started output to be completed."
        }
        val completedOutputs =
            outputsById.values
                .sortedBy { state -> state.index }
                .map { state -> checkNotNull(state.completedOutput) }
        streamRequire(
            condition = finalResponse.outputs == completedOutputs,
            path = "/response/outputs",
        ) {
            "The final response outputs must equal the completed stream outputs in index order."
        }

        latestUsage?.let { previous ->
            val finalUsage =
                finalResponse.usage
                    ?: throw ContractSemanticException(
                        code = INVALID_STREAM_SEQUENCE,
                        path = "/response/usage",
                        message =
                            "A final response must contain usage after usage.updated was emitted.",
                    )
            requireMonotonicUsage(
                previous = previous,
                current = finalUsage,
                path = "/response/usage",
            )
        }
    }

    private fun requireOpenOutput(event: UniversalAiStreamEvent): OutputState {
        val id = checkNotNull(event.outputId)
        val index = checkNotNull(event.outputIndex)
        val state =
            outputsById[id]
                ?: throw ContractSemanticException(
                    code = INVALID_STREAM_SEQUENCE,
                    path = "/outputId",
                    message = "${event.type.rawValue} requires a preceding output.started event.",
                )
        streamRequire(
            condition = state.index == index && outputIdsByIndex[index] == id,
            path = "/outputIndex",
        ) {
            "Output event ID and index correlation must remain stable."
        }
        streamRequire(
            condition = !state.isCompleted,
            path = "/outputId",
        ) {
            "An output must not emit events after output.completed."
        }
        return state
    }

    private fun rejectOutputScopedEventAfterCompletion(event: UniversalAiStreamEvent) {
        val id = event.outputId ?: return
        val index = checkNotNull(event.outputIndex)
        val stateById = outputsById[id]
        val stateByIndex = outputIdsByIndex[index]?.let(outputsById::get)
        streamRequire(
            condition =
                stateById?.isCompleted != true &&
                    stateByIndex?.isCompleted != true,
            path = "/outputId",
        ) {
            "An output must not emit events after output.completed."
        }
    }

    private fun requireMonotonicUsage(
        previous: UniversalAiUsage,
        current: UniversalAiUsage,
        path: String,
    ) {
        streamRequire(
            condition = current.inputTokens >= previous.inputTokens,
            path = "$path/inputTokens",
        ) {
            "Cumulative inputTokens must not decrease."
        }
        streamRequire(
            condition = current.outputTokens >= previous.outputTokens,
            path = "$path/outputTokens",
        ) {
            "Cumulative outputTokens must not decrease."
        }
        streamRequire(
            condition = current.totalTokens >= previous.totalTokens,
            path = "$path/totalTokens",
        ) {
            "Cumulative totalTokens must not decrease."
        }
        requireMonotonicBreakdown(
            previous = previous.inputDetails,
            current = current.inputDetails,
            path = "$path/inputDetails",
        )
        requireMonotonicBreakdown(
            previous = previous.outputDetails,
            current = current.outputDetails,
            path = "$path/outputDetails",
        )
    }

    private fun requireMonotonicBreakdown(
        previous: Map<String, Long>,
        current: Map<String, Long>,
        path: String,
    ) {
        previous.forEach { (name, previousValue) ->
            val currentValue = current[name]
            streamRequire(
                condition = currentValue != null && currentValue >= previousValue,
                path = "$path/${name.escapeStreamPointerToken()}",
            ) {
                "Previously reported usage-detail keys must remain present and must not decrease."
            }
        }
    }

    private class OutputState(
        val id: OutputId,
        val index: Int,
    ) {
        val deltas = StringBuilder()
        var accumulatedDeltaBytes: Int = 0
        var completedOutput: UniversalAiOutput? = null

        val isCompleted: Boolean
            get() = completedOutput != null
    }
}

internal object UniversalAiStreamEventTypeSerializer :
    ValidatedStringSerializer<UniversalAiStreamEventType>(
        serialName = "com.maneesh.universalai.connector.contract.UniversalAiStreamEventType",
        create = UniversalAiStreamEventType::of,
        rawValue = UniversalAiStreamEventType::rawValue,
    )

internal object UniversalAiStreamEventSerializer : KSerializer<UniversalAiStreamEvent> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UniversalAiStreamEvent,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException(
                    "UniversalAiStreamEvent supports JSON encoding only.",
                )
        val members = linkedMapOf<String, JsonElement>()
        members["contractVersion"] = JsonPrimitive(CURRENT_CONTRACT_VERSION)
        members["type"] = JsonPrimitive(value.type.rawValue)
        members["terminal"] = JsonPrimitive(value.terminal)
        members["sequence"] = JsonPrimitive(value.sequence)
        value.requestId?.let { requestId ->
            members["requestId"] = JsonPrimitive(requestId.rawValue)
        }
        members["responseId"] = JsonPrimitive(value.responseId.rawValue)
        value.outputId?.let { outputId ->
            members["outputId"] = JsonPrimitive(outputId.rawValue)
        }
        value.outputIndex?.let { outputIndex ->
            members["outputIndex"] = JsonPrimitive(outputIndex)
        }
        value.delta?.let { delta ->
            members["delta"] = JsonPrimitive(delta)
        }
        value.output?.let { output ->
            members["output"] =
                CanonicalJson.format.encodeToJsonElement(
                    UniversalAiOutput.serializer(),
                    output,
                )
        }
        value.usage?.let { usage ->
            members["usage"] =
                CanonicalJson.format.encodeToJsonElement(
                    UniversalAiUsage.serializer(),
                    usage,
                )
        }
        value.response?.let { response ->
            members["response"] =
                CanonicalJson.format.encodeToJsonElement(
                    UniversalAiResponse.serializer(),
                    response,
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

    override fun deserialize(decoder: Decoder): UniversalAiStreamEvent {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException(
                    "UniversalAiStreamEvent supports JSON decoding only.",
                )
        val document =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: throw SerializationException("Expected an object for UniversalAiStreamEvent.")

        val contractVersion = document.requiredStreamString("contractVersion")
        if (contractVersion != CURRENT_CONTRACT_VERSION) {
            throw semanticSerializationException(
                code = "unsupported_contract_version",
                path = "/contractVersion",
                message = "Unsupported contractVersion '$contractVersion'.",
            )
        }

        return decodeStreamEvent {
            UniversalAiStreamEvent(
                type =
                    decodeSemanticComponent(pathPrefix = "") {
                        UniversalAiStreamEventType.of(
                            document.requiredStreamString("type"),
                        )
                    },
                terminal = document.requiredStreamBoolean("terminal"),
                sequence = document.requiredStreamSafeInteger("sequence"),
                requestId =
                    document.optionalStreamNonNull("requestId")?.let { element ->
                        RequestId.ofAtPath(
                            rawValue = element.requireStreamString("requestId"),
                            path = "/requestId",
                        )
                    },
                responseId =
                    ResponseId.ofAtPath(
                        rawValue = document.requiredStreamString("responseId"),
                        path = "/responseId",
                    ),
                outputId =
                    document.optionalStreamNonNull("outputId")?.let { element ->
                        OutputId.ofAtPath(
                            rawValue = element.requireStreamString("outputId"),
                            path = "/outputId",
                        )
                    },
                outputIndex =
                    document.optionalStreamNonNull("outputIndex")?.let { element ->
                        element.requireStreamInt("outputIndex")
                    },
                delta =
                    document.optionalStreamNonNull("delta")?.let { element ->
                        element.requireStreamString("delta")
                    },
                output =
                    document.optionalStreamNonNull("output")?.let { element ->
                        decodeSemanticComponent(pathPrefix = "/output") {
                            CanonicalJson.format.decodeFromJsonElement(
                                UniversalAiOutput.serializer(),
                                element,
                            )
                        }
                    },
                usage =
                    document.optionalStreamNonNull("usage")?.let { element ->
                        decodeSemanticComponent(pathPrefix = "/usage") {
                            CanonicalJson.format.decodeFromJsonElement(
                                UniversalAiUsage.serializer(),
                                element,
                            )
                        }
                    },
                response =
                    document.optionalStreamNonNull("response")?.let { element ->
                        decodeSemanticComponent(pathPrefix = "/response") {
                            CanonicalJson.format.decodeFromJsonElement(
                                UniversalAiResponse.serializer(),
                                element,
                            )
                        }
                    },
                extensions =
                    document.optionalStreamNonNull("extensions")?.let { element ->
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

private const val MAX_STREAM_EVENT_TYPE_CHARACTERS = 64
private const val MAX_STREAM_DELTA_BYTES = 1_048_576
private const val INVALID_STREAM_SEQUENCE = "invalid_stream_sequence"
private const val INCOMPLETE_STREAM = "incomplete_stream"
private const val UNSUPPORTED_TERMINAL_EVENT = "unsupported_terminal_event"

private val STREAM_EVENT_TYPE_PATTERN = Regex("^[a-z][a-z0-9._-]{0,63}$")

private fun JsonObject.requiredStreamNonNull(name: String): JsonElement =
    this[name]
        ?.takeUnless { it === JsonNull }
        ?: throw SerializationException("Missing required non-null '$name'.")

private fun JsonObject.optionalStreamNonNull(name: String): JsonElement? {
    val value = this[name] ?: return null
    if (value === JsonNull) {
        throw SerializationException("'$name' must not be null.")
    }
    return value
}

private fun JsonObject.requiredStreamString(name: String): String =
    requiredStreamNonNull(name).requireStreamString(name)

private fun JsonElement.requireStreamString(name: String): String {
    val primitive = this as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        throw SerializationException("'$name' must be a string.")
    }
    return primitive.content
}

private fun JsonObject.requiredStreamBoolean(name: String): Boolean {
    val primitive = requiredStreamNonNull(name) as? JsonPrimitive
    return primitive
        ?.takeUnless { it.isString }
        ?.booleanOrNull
        ?: throw SerializationException("'$name' must be a boolean.")
}

private fun JsonObject.requiredStreamSafeInteger(name: String): Long {
    val primitive =
        requiredStreamNonNull(name) as? JsonPrimitive
            ?: throw SerializationException("'$name' must be an integer.")
    if (
        primitive.isString ||
        primitive.booleanOrNull != null ||
        !JsonNumberSemantics.isMathematicalInteger(primitive.content)
    ) {
        throw SerializationException("'$name' must be an integer.")
    }
    return primitive.content.toStreamSafeIntegerOrNull()
        ?: throw semanticSerializationException(
            code = INVALID_STREAM_SEQUENCE,
            path = "/$name",
            message = "'$name' must be a positive JSON safe integer.",
        )
}

private fun JsonElement.requireStreamInt(name: String): Int {
    val primitive = this as? JsonPrimitive
    if (
        primitive == null ||
        primitive.isString ||
        primitive.booleanOrNull != null ||
        !JsonNumberSemantics.isMathematicalInteger(primitive.content)
    ) {
        throw SerializationException("'$name' must be an integer.")
    }
    return JsonNumberSemantics.toExactIntOrNull(primitive.content)
        ?: throw semanticSerializationException(
            code = INVALID_STREAM_SEQUENCE,
            path = "/$name",
            message = "'$name' is outside the supported integer range.",
        )
}

private fun String.toStreamSafeIntegerOrNull(): Long? {
    if (
        JsonNumberSemantics.compare(this, "1")?.let { comparison -> comparison >= 0 } != true ||
        JsonNumberSemantics.compare(this, MAX_JSON_SAFE_INTEGER.toString())
            ?.let { comparison -> comparison <= 0 } != true
    ) {
        return null
    }
    val normalized = JsonNumberSemantics.normalize(this) ?: return null
    val exponentMarker = normalized.lastIndexOf('e')
    if (exponentMarker <= 0 || exponentMarker == normalized.lastIndex) {
        return null
    }
    val digits = normalized.substring(0, exponentMarker)
    if (digits.startsWith('-')) {
        return null
    }
    val exponent = normalized.substring(exponentMarker + 1).toIntOrNull() ?: return null
    if (exponent < 0 || digits.length + exponent > 16) {
        return null
    }
    return buildString(digits.length + exponent) {
        append(digits)
        repeat(exponent) {
            append('0')
        }
    }.toLongOrNull()
}

private fun streamRequire(
    condition: Boolean,
    path: String,
    message: () -> String,
) {
    contractRequire(
        condition = condition,
        code = INVALID_STREAM_SEQUENCE,
        path = path,
        message = message,
    )
}

private fun String.escapeStreamPointerToken(): String =
    replace("~", "~0").replace("/", "~1")

private inline fun <T> decodeStreamEvent(block: () -> T): T =
    try {
        block()
    } catch (failure: SerializationException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = failure.message ?: "Invalid UniversalAiStreamEvent.",
            cause = failure,
        )
    }
