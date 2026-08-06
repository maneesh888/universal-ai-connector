package com.maneesh.universalai.connector.internal.provider.openai

import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.OutputId
import com.maneesh.universalai.connector.contract.ResponseId
import com.maneesh.universalai.connector.contract.UniversalAiError
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiOutput
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.internal.transport.ConnectorResponseMetadata
import com.maneesh.universalai.connector.internal.transport.ConnectorServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

internal class OpenAiStreamTranslator(
    private val request: UniversalAiRequest,
    private val metadata: ConnectorResponseMetadata,
) {
    private var providerSequence: Long? = null
    private var canonicalSequence = 1L
    private var eventCount = 0
    private var responseId: ResponseId? = null
    private var responseModel: ModelId? = null
    private var lastProviderOutputIndex = -1
    private var nextCanonicalOutputIndex = 0
    private val providerItemsByIndex = linkedMapOf<Int, ProviderItemState>()
    private val providerItemIndexesById = mutableMapOf<String, Int>()
    private val completedOutputsByIndex = mutableMapOf<Int, UniversalAiOutput>()

    var isTerminal: Boolean = false
        private set

    fun translate(event: ConnectorServerSentEvent): List<UniversalAiStreamEvent> =
        try {
            translateSafely(event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: UniversalAiException) {
            when {
                failure.error.code.rawValue == MALFORMED_PROVIDER_STREAM_CODE -> throw failure
                failure.error.category == UniversalAiErrorCategory.Protocol -> throw malformedStream()
                else -> throw failure
            }
        } catch (_: SerializationException) {
            throw malformedStream()
        } catch (_: IllegalArgumentException) {
            throw malformedStream()
        } catch (_: Throwable) {
            throw malformedStream()
        }

    fun finish() {
        if (!isTerminal) {
            throw malformedStream()
        }
    }

    private fun translateSafely(
        event: ConnectorServerSentEvent,
    ): List<UniversalAiStreamEvent> {
        streamRequire(!isTerminal)
        eventCount += 1
        streamRequire(eventCount <= MAX_PROVIDER_STREAM_EVENTS)

        val wire =
            OPENAI_WIRE_JSON.decodeFromString<OpenAiStreamEventWire>(event.data)
        val type = streamValue(wire.type)
        streamRequire(
            event.event == null ||
                event.event == DEFAULT_SSE_EVENT ||
                event.event == type,
        )
        acceptProviderSequence(streamValue(wire.sequenceNumber))

        return when (type) {
            "response.created" -> responseCreated(wire)
            "response.in_progress" -> responseInProgress(wire)
            "response.output_item.added" -> outputItemAdded(wire)
            "response.content_part.added" -> contentPartAdded(wire)
            "response.output_text.delta" -> outputTextDelta(wire)
            "response.output_text.done" -> outputTextDone(wire)
            "response.content_part.done" -> contentPartDone(wire)
            "response.output_item.done" -> outputItemDone(wire)
            "response.completed" -> responseCompleted(wire)
            "response.failed" -> responseFailed(wire)
            "response.incomplete" -> responseIncomplete(wire)
            "error" -> providerError(wire)
            else -> throw malformedStream()
        }
    }

    private fun acceptProviderSequence(sequence: Long) {
        streamRequire(sequence >= 0)
        providerSequence?.let { previous ->
            streamRequire(sequence > previous)
        }
        providerSequence = sequence
    }

    private fun responseCreated(
        wire: OpenAiStreamEventWire,
    ): List<UniversalAiStreamEvent> {
        streamRequire(responseId == null)
        streamRequire(providerItemsByIndex.isEmpty())
        val response = streamValue(wire.response)
        streamRequire(response.objectType == RESPONSE_OBJECT_TYPE)
        streamRequire(response.status == RESPONSE_IN_PROGRESS_STATUS)
        streamRequire(response.error == null)
        streamRequire(response.incompleteDetails == null)
        streamRequire(response.output?.isEmpty() == true)
        streamRequire(response.usage == null)
        responseId = ResponseId.of(streamValue(response.id))
        responseModel = ModelId.of(streamValue(response.model))
        return listOf(
            canonicalEvent(
                type = UniversalAiStreamEventType.ResponseStarted,
            ),
        )
    }

    private fun responseInProgress(
        wire: OpenAiStreamEventWire,
    ): List<UniversalAiStreamEvent> {
        streamRequire(providerItemsByIndex.isEmpty())
        val response = correlatedResponse(wire)
        streamRequire(response.status == RESPONSE_IN_PROGRESS_STATUS)
        streamRequire(response.error == null)
        streamRequire(response.incompleteDetails == null)
        streamRequire(response.output?.isEmpty() == true)
        streamRequire(response.usage == null)
        return emptyList()
    }

    private fun outputItemAdded(
        wire: OpenAiStreamEventWire,
    ): List<UniversalAiStreamEvent> {
        requireStarted()
        val providerIndex = streamValue(wire.outputIndex)
        streamRequire(providerIndex == lastProviderOutputIndex + 1)
        streamRequire(providerIndex in 0 until MAX_PROVIDER_OUTPUT_ITEMS)
        val item = streamValue(wire.item)
        val itemId = streamValue(item.id)
        streamRequire(itemId !in providerItemIndexesById)
        streamRequire(providerIndex !in providerItemsByIndex)

        val state =
            when (item.type) {
                "reasoning" -> {
                    streamRequire(item.status == null || item.status == ITEM_IN_PROGRESS_STATUS)
                    ProviderItemState(
                        providerId = itemId,
                        providerIndex = providerIndex,
                        kind = ProviderItemKind.Reasoning,
                    )
                }

                "message" -> {
                    streamRequire(item.status == ITEM_IN_PROGRESS_STATUS)
                    streamRequire(item.role == ASSISTANT_ROLE)
                    streamRequire(item.content?.isEmpty() == true)
                    streamRequire(nextCanonicalOutputIndex < MAX_CANONICAL_OUTPUTS)
                    ProviderItemState(
                        providerId = itemId,
                        providerIndex = providerIndex,
                        kind = ProviderItemKind.Message,
                        outputId = OutputId.of(itemId),
                        canonicalIndex = nextCanonicalOutputIndex++,
                    )
                }

                else -> throw malformedStream()
            }
        providerItemsByIndex[providerIndex] = state
        providerItemIndexesById[itemId] = providerIndex
        lastProviderOutputIndex = providerIndex

        return state.outputId?.let { outputId ->
            listOf(
                canonicalEvent(
                    type = UniversalAiStreamEventType.OutputStarted,
                    outputId = outputId,
                    outputIndex = streamValue(state.canonicalIndex),
                ),
            )
        } ?: emptyList()
    }

    private fun contentPartAdded(
        wire: OpenAiStreamEventWire,
    ): List<UniversalAiStreamEvent> {
        val state = requireMessageState(wire)
        streamRequire(!state.completed)
        val contentIndex = streamValue(wire.contentIndex)
        streamRequire(contentIndex == state.parts.size)
        val part = streamValue(wire.part)
        when (part.type) {
            "output_text" -> {
                streamRequire(part.text == "")
                streamRequire(part.refusal == null)
                state.parts[contentIndex] = TextPartState()
            }

            "refusal" -> throw refusalFailure(metadata)
            else -> throw malformedStream()
        }
        return emptyList()
    }

    private fun outputTextDelta(
        wire: OpenAiStreamEventWire,
    ): List<UniversalAiStreamEvent> {
        val state = requireMessageState(wire)
        val part = requireOpenTextPart(state, wire)
        val delta = streamValue(wire.delta)
        streamRequire(delta.isNotEmpty())
        val deltaBytes = delta.encodeToByteArray().size
        streamRequire(deltaBytes <= MAX_STREAM_OUTPUT_BYTES - state.textBytes)
        part.append(delta)
        state.textBytes += deltaBytes

        return if (request.responseFormat.schema == null) {
            listOf(
                canonicalEvent(
                    type = UniversalAiStreamEventType.OutputDelta,
                    outputId = streamValue(state.outputId),
                    outputIndex = streamValue(state.canonicalIndex),
                    delta = delta,
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun outputTextDone(
        wire: OpenAiStreamEventWire,
    ): List<UniversalAiStreamEvent> {
        val state = requireMessageState(wire)
        val part = requireOpenTextPart(state, wire)
        streamRequire(!part.textDone)
        streamRequire(streamValue(wire.text) == part.text.toString())
        part.textDone = true
        return emptyList()
    }

    private fun contentPartDone(
        wire: OpenAiStreamEventWire,
    ): List<UniversalAiStreamEvent> {
        val state = requireMessageState(wire)
        val partState = requireOpenTextPart(state, wire)
        streamRequire(partState.textDone)
        val part = streamValue(wire.part)
        streamRequire(part.type == "output_text")
        streamRequire(part.refusal == null)
        streamRequire(streamValue(part.text) == partState.text.toString())
        partState.contentDone = true
        return emptyList()
    }

    private fun outputItemDone(
        wire: OpenAiStreamEventWire,
    ): List<UniversalAiStreamEvent> {
        requireStarted()
        val providerIndex = streamValue(wire.outputIndex)
        val state =
            providerItemsByIndex[providerIndex]
                ?: throw malformedStream()
        streamRequire(!state.completed)
        val item = streamValue(wire.item)
        streamRequire(item.id == state.providerId)
        streamRequire(item.type == state.kind.wireType)

        if (state.kind == ProviderItemKind.Reasoning) {
            streamRequire(item.status == null || item.status == ITEM_COMPLETED_STATUS)
            state.completed = true
            return emptyList()
        }

        streamRequire(item.status == ITEM_COMPLETED_STATUS)
        streamRequire(item.role == ASSISTANT_ROLE)
        val content = streamValue(item.content)
        streamRequire(content.size == state.parts.size)
        content.forEachIndexed { index, part ->
            val partState = state.parts[index] ?: throw malformedStream()
            streamRequire(partState.textDone && partState.contentDone)
            streamRequire(part.type == "output_text")
            streamRequire(part.refusal == null)
            streamRequire(streamValue(part.text) == partState.text.toString())
        }

        val output =
            item.toStreamCanonicalOutput(
                request = request,
                outputIndex = streamValue(state.canonicalIndex),
                metadata = metadata,
            )
        streamRequire(output.id == streamValue(state.outputId))
        state.completed = true
        completedOutputsByIndex[output.index] = output

        return buildList {
            output.structuredJson?.let { value ->
                add(
                    canonicalEvent(
                        type = UniversalAiStreamEventType.OutputDelta,
                        outputId = output.id,
                        outputIndex = output.index,
                        delta = value.toJson(),
                    ),
                )
            }
            add(
                canonicalEvent(
                    type = UniversalAiStreamEventType.OutputCompleted,
                    outputId = output.id,
                    outputIndex = output.index,
                    output = output,
                ),
            )
        }
    }

    private fun responseCompleted(
        wire: OpenAiStreamEventWire,
    ): List<UniversalAiStreamEvent> {
        val responseWire = correlatedResponse(wire)
        streamRequire(responseWire.status == ITEM_COMPLETED_STATUS)
        streamRequire(providerItemsByIndex.values.all(ProviderItemState::completed))
        val finalProviderOutput = streamValue(responseWire.output)
        streamRequire(finalProviderOutput.size == providerItemsByIndex.size)
        finalProviderOutput.forEachIndexed { index, item ->
            val state = providerItemsByIndex[index] ?: throw malformedStream()
            streamRequire(item.id == state.providerId)
            streamRequire(item.type == state.kind.wireType)
        }

        val response =
            responseWire.toStreamCanonicalResponse(
                request = request,
                metadata = metadata,
            )
        val completedOutputs =
            completedOutputsByIndex
                .entries
                .sortedBy { entry -> entry.key }
                .map { entry -> entry.value }
        streamRequire(response.outputs == completedOutputs)

        isTerminal = true
        return buildList {
            response.usage?.let { usage ->
                add(
                    canonicalEvent(
                        type = UniversalAiStreamEventType.UsageUpdated,
                        usage = usage,
                    ),
                )
            }
            add(
                canonicalEvent(
                    type = UniversalAiStreamEventType.ResponseCompleted,
                    terminal = true,
                    response = response,
                ),
            )
        }
    }

    private fun responseFailed(wire: OpenAiStreamEventWire): Nothing {
        val response = correlatedResponse(wire)
        streamRequire(response.status == "failed")
        streamRequire(response.incompleteDetails == null)
        throw providerResponseFailure(
            error = streamValue(response.error),
            metadata = metadata,
        )
    }

    private fun responseIncomplete(wire: OpenAiStreamEventWire): Nothing {
        val response = correlatedResponse(wire)
        streamRequire(response.status == "incomplete")
        streamRequire(response.error == null)
        throw incompleteResponseFailure(
            details = streamValue(response.incompleteDetails),
            metadata = metadata,
        )
    }

    private fun providerError(wire: OpenAiStreamEventWire): Nothing {
        streamRequire(streamValue(wire.message).isNotBlank())
        throw providerResponseFailure(
            error =
                OpenAiErrorWire(
                    code = wire.code,
                    message = wire.message,
                    param = wire.param,
                ),
            metadata = metadata,
        )
    }

    private fun correlatedResponse(wire: OpenAiStreamEventWire): OpenAiResponseWire {
        val expectedResponseId = streamValue(responseId)
        val expectedModel = streamValue(responseModel)
        val response = streamValue(wire.response)
        streamRequire(response.objectType == RESPONSE_OBJECT_TYPE)
        streamRequire(response.id == expectedResponseId.rawValue)
        streamRequire(response.model == expectedModel.rawValue)
        return response
    }

    private fun requireMessageState(
        wire: OpenAiStreamEventWire,
    ): ProviderItemState {
        requireStarted()
        val providerIndex = streamValue(wire.outputIndex)
        val state =
            providerItemsByIndex[providerIndex]
                ?: throw malformedStream()
        streamRequire(state.kind == ProviderItemKind.Message)
        streamRequire(wire.itemId == state.providerId)
        return state
    }

    private fun requireOpenTextPart(
        state: ProviderItemState,
        wire: OpenAiStreamEventWire,
    ): TextPartState {
        streamRequire(!state.completed)
        val contentIndex = streamValue(wire.contentIndex)
        val part = state.parts[contentIndex] ?: throw malformedStream()
        streamRequire(!part.contentDone)
        return part
    }

    private fun requireStarted() {
        streamValue(responseId)
        streamValue(responseModel)
    }

    private fun canonicalEvent(
        type: UniversalAiStreamEventType,
        terminal: Boolean = false,
        outputId: OutputId? = null,
        outputIndex: Int? = null,
        delta: String? = null,
        output: UniversalAiOutput? = null,
        usage: com.maneesh.universalai.connector.contract.UniversalAiUsage? = null,
        response: UniversalAiResponse? = null,
    ): UniversalAiStreamEvent {
        val event =
            UniversalAiStreamEvent(
                type = type,
                terminal = terminal,
                sequence = canonicalSequence,
                responseId = streamValue(responseId),
                requestId = metadata.requestId.toCanonicalRequestIdOrNull(),
                outputId = outputId,
                outputIndex = outputIndex,
                delta = delta,
                output = output,
                usage = usage,
                response = response,
            )
        canonicalSequence += 1
        return event
    }

    private data class ProviderItemState(
        val providerId: String,
        val providerIndex: Int,
        val kind: ProviderItemKind,
        val outputId: OutputId? = null,
        val canonicalIndex: Int? = null,
        val parts: MutableMap<Int, TextPartState> = linkedMapOf(),
        var textBytes: Int = 0,
        var completed: Boolean = false,
    )

    private data class TextPartState(
        val text: StringBuilder = StringBuilder(),
        var textDone: Boolean = false,
        var contentDone: Boolean = false,
    ) {
        fun append(delta: String) {
            streamRequire(!textDone && !contentDone)
            text.append(delta)
        }
    }

    private enum class ProviderItemKind(
        val wireType: String,
    ) {
        Reasoning("reasoning"),
        Message("message"),
    }
}

private fun OpenAiOutputItemWire.toStreamCanonicalOutput(
    request: UniversalAiRequest,
    outputIndex: Int,
    metadata: ConnectorResponseMetadata,
): UniversalAiOutput =
    try {
        toCanonicalOutputOrNull(
            request = request,
            outputIndex = outputIndex,
            metadata = metadata,
        ) ?: throw malformedStream()
    } catch (failure: UniversalAiException) {
        when {
            failure.error.code.rawValue == MALFORMED_PROVIDER_STREAM_CODE -> throw failure
            failure.error.category == UniversalAiErrorCategory.Protocol -> throw malformedStream()
            else -> throw failure
        }
    }

private fun OpenAiResponseWire.toStreamCanonicalResponse(
    request: UniversalAiRequest,
    metadata: ConnectorResponseMetadata,
): UniversalAiResponse =
    try {
        toCanonical(
            request = request,
            metadata = metadata,
        )
    } catch (failure: UniversalAiException) {
        when {
            failure.error.code.rawValue == MALFORMED_PROVIDER_STREAM_CODE -> throw failure
            failure.error.category == UniversalAiErrorCategory.Protocol -> throw malformedStream()
            else -> throw failure
        }
    }

internal fun malformedStream(): UniversalAiException =
    UniversalAiException(
        UniversalAiError(
            category = UniversalAiErrorCategory.Protocol,
            code = UniversalAiErrorCode.of(MALFORMED_PROVIDER_STREAM_CODE),
            message = OPENAI_MALFORMED_STREAM_MESSAGE,
        ),
    )

private fun streamRequire(condition: Boolean) {
    if (!condition) {
        throw malformedStream()
    }
}

private fun <T : Any> streamValue(value: T?): T {
    streamRequire(value != null)
    return checkNotNull(value)
}

internal const val OPENAI_MALFORMED_STREAM_MESSAGE: String =
    "The OpenAI response stream was malformed or unsupported."

private const val MALFORMED_PROVIDER_STREAM_CODE: String = "malformed_provider_stream"
private const val DEFAULT_SSE_EVENT: String = "message"
private const val RESPONSE_OBJECT_TYPE: String = "response"
private const val RESPONSE_IN_PROGRESS_STATUS: String = "in_progress"
private const val ITEM_IN_PROGRESS_STATUS: String = "in_progress"
private const val ITEM_COMPLETED_STATUS: String = "completed"
private const val ASSISTANT_ROLE: String = "assistant"
private const val MAX_PROVIDER_STREAM_EVENTS: Int = 65_536
private const val MAX_PROVIDER_OUTPUT_ITEMS: Int = 128
private const val MAX_CANONICAL_OUTPUTS: Int = 128
private const val MAX_STREAM_OUTPUT_BYTES: Int = 1_048_576
