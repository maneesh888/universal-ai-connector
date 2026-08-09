package com.maneesh.universalai.connector.internal.provider.anthropic

import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.OutputId
import com.maneesh.universalai.connector.contract.RequestId
import com.maneesh.universalai.connector.contract.ResponseId
import com.maneesh.universalai.connector.contract.UniversalAiCompletionReason
import com.maneesh.universalai.connector.contract.UniversalAiError
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiOutput
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiUsage
import com.maneesh.universalai.connector.internal.provider.ANTHROPIC_PROVIDER_ID
import com.maneesh.universalai.connector.internal.transport.ConnectorResponseMetadata
import com.maneesh.universalai.connector.internal.transport.ConnectorServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

internal class AnthropicStreamTranslator(
    private val request: UniversalAiRequest,
    private val metadata: ConnectorResponseMetadata,
) {
    private var canonicalSequence = 1L
    private var eventCount = 0
    private var responseId: ResponseId? = null
    private var responseModel: ModelId? = null
    private var startUsage: AnthropicStreamUsageState? = null
    private var lastOutputTokens: Long? = null
    private var openContentIndex: Int? = null
    private val contentBlocks = mutableListOf<AnthropicTextStreamState>()
    private var messageDeltaSeen = false
    private var stopReason: String? = null
    private var stopSequence: String? = null
    private var totalTextBytes = 0

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
                failure.error.category == UniversalAiErrorCategory.Protocol ->
                    throw malformedAnthropicStream()

                else -> throw failure
            }
        } catch (_: SerializationException) {
            throw malformedAnthropicStream()
        } catch (_: IllegalArgumentException) {
            throw malformedAnthropicStream()
        } catch (_: Throwable) {
            throw malformedAnthropicStream()
        }

    fun finish() {
        if (!isTerminal) {
            throw malformedAnthropicStream()
        }
    }

    private fun translateSafely(
        event: ConnectorServerSentEvent,
    ): List<UniversalAiStreamEvent> {
        streamRequire(!isTerminal)
        eventCount += 1
        streamRequire(eventCount <= MAX_PROVIDER_STREAM_EVENTS)

        val type =
            streamValue(
                ANTHROPIC_WIRE_JSON
                    .decodeFromString<AnthropicStreamEventTypeWire>(event.data)
                    .type,
            )
        streamRequire(event.event == type)
        if (type !in SUPPORTED_PROVIDER_STREAM_EVENT_TYPES) {
            return emptyList()
        }
        val wire = ANTHROPIC_WIRE_JSON.decodeFromString<AnthropicStreamEventWire>(event.data)

        return when (type) {
            "message_start" -> messageStart(wire)
            "content_block_start" -> contentBlockStart(wire)
            "content_block_delta" -> contentBlockDelta(wire)
            "content_block_stop" -> contentBlockStop(wire)
            "message_delta" -> messageDelta(wire)
            "message_stop" -> messageStop(wire)
            "ping" -> ping(wire)
            "error" -> providerError(wire)
            else -> throw malformedAnthropicStream()
        }
    }

    private fun messageStart(wire: AnthropicStreamEventWire): List<UniversalAiStreamEvent> {
        streamRequire(responseId == null)
        streamRequire(contentBlocks.isEmpty())
        streamRequire(
            wire.index == null &&
                wire.contentBlock == null &&
                wire.delta == null &&
                wire.usage == null &&
                wire.error == null,
        )
        val message = streamValue(wire.message)
        streamRequire(message.type == "message")
        streamRequire(message.role == "assistant")
        streamRequire(message.content?.isEmpty() == true)
        streamRequire(message.stopReason == null && message.stopSequence == null)
        responseId = ResponseId.of(streamValue(message.id))
        responseModel = ModelId.of(streamValue(message.model))
        val usage = streamValue(message.usage).toStreamStartUsage()
        startUsage = usage
        lastOutputTokens = usage.initialOutputTokens

        return listOf(
            canonicalEvent(
                type = UniversalAiStreamEventType.ResponseStarted,
            ),
        )
    }

    private fun contentBlockStart(
        wire: AnthropicStreamEventWire,
    ): List<UniversalAiStreamEvent> {
        requireStarted()
        streamRequire(!messageDeltaSeen)
        streamRequire(openContentIndex == null)
        streamRequire(
            wire.message == null &&
                wire.delta == null &&
                wire.usage == null &&
                wire.error == null,
        )
        val index = streamValue(wire.index)
        streamRequire(index == contentBlocks.size)
        streamRequire(index in 0 until MAX_PROVIDER_CONTENT_BLOCKS)
        if (request.responseFormat.schema != null) {
            streamRequire(index == 0)
        }
        val block = streamValue(wire.contentBlock)
        streamRequire(block.type == "text")
        streamRequire(block.text == "")
        contentBlocks += AnthropicTextStreamState()
        openContentIndex = index

        return if (index == 0) {
            listOf(
                canonicalEvent(
                    type = UniversalAiStreamEventType.OutputStarted,
                    outputId = outputId(),
                    outputIndex = CANONICAL_OUTPUT_INDEX,
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun contentBlockDelta(
        wire: AnthropicStreamEventWire,
    ): List<UniversalAiStreamEvent> {
        requireStarted()
        streamRequire(!messageDeltaSeen)
        streamRequire(
            wire.message == null &&
                wire.contentBlock == null &&
                wire.usage == null &&
                wire.error == null,
        )
        val index = streamValue(wire.index)
        streamRequire(openContentIndex == index)
        val state = contentBlocks.getOrNull(index) ?: throw malformedAnthropicStream()
        streamRequire(!state.completed)
        val delta = streamValue(wire.delta)
        streamRequire(delta.type == "text_delta")
        streamRequire(delta.stopReason == null && delta.stopSequence == null)
        val text = streamValue(delta.text)
        streamRequire(text.isNotEmpty())
        val textBytes = text.encodeToByteArray().size
        streamRequire(textBytes <= MAX_STREAM_OUTPUT_BYTES - totalTextBytes)
        state.text.append(text)
        totalTextBytes += textBytes

        return if (request.responseFormat.schema == null) {
            listOf(
                canonicalEvent(
                    type = UniversalAiStreamEventType.OutputDelta,
                    outputId = outputId(),
                    outputIndex = CANONICAL_OUTPUT_INDEX,
                    delta = text,
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun contentBlockStop(
        wire: AnthropicStreamEventWire,
    ): List<UniversalAiStreamEvent> {
        requireStarted()
        streamRequire(!messageDeltaSeen)
        streamRequire(
            wire.message == null &&
                wire.contentBlock == null &&
                wire.delta == null &&
                wire.usage == null &&
                wire.error == null,
        )
        val index = streamValue(wire.index)
        streamRequire(openContentIndex == index)
        val state = contentBlocks.getOrNull(index) ?: throw malformedAnthropicStream()
        streamRequire(!state.completed)
        streamRequire(state.text.isNotEmpty())
        state.completed = true
        openContentIndex = null
        return emptyList()
    }

    private fun messageDelta(wire: AnthropicStreamEventWire): List<UniversalAiStreamEvent> {
        val usageState = streamValue(startUsage)
        streamRequire(contentBlocks.isNotEmpty())
        streamRequire(openContentIndex == null)
        streamRequire(contentBlocks.all(AnthropicTextStreamState::completed))
        streamRequire(
            wire.message == null &&
                wire.index == null &&
                wire.contentBlock == null &&
                wire.error == null,
        )
        val delta = streamValue(wire.delta)
        streamRequire(delta.type == null && delta.text == null)
        val usage = streamValue(wire.usage)
        usage.inputTokens?.let { value -> streamRequire(value == usageState.uncachedInputTokens) }
        usage.cacheCreationInputTokens?.let { value ->
            streamRequire(value == usageState.cacheCreationInputTokens)
        }
        usage.cacheReadInputTokens?.let { value ->
            streamRequire(value == usageState.cacheReadInputTokens)
        }
        val outputTokens = streamValue(usage.outputTokens)
        streamRequire(outputTokens >= streamValue(lastOutputTokens))
        streamRequire(outputTokens <= MAX_CANONICAL_TOKEN_COUNT)
        lastOutputTokens = outputTokens

        delta.stopReason?.let { reason ->
            stopReason?.let { previous -> streamRequire(previous == reason) }
            when (reason) {
                "end_turn" -> streamRequire(delta.stopSequence == null)
                "stop_sequence" -> {
                    val sequence = streamValue(delta.stopSequence)
                    streamRequire(sequence in request.generation.stopSequences)
                    stopSequence?.let { previous -> streamRequire(previous == sequence) }
                    stopSequence = sequence
                }

                "max_tokens" -> {
                    streamRequire(delta.stopSequence == null)
                    throw outputLimitFailure(metadata)
                }

                "refusal" -> {
                    streamRequire(delta.stopSequence == null)
                    throw refusalFailure(metadata)
                }

                "model_context_window_exceeded", "tool_use", "pause_turn" -> {
                    streamRequire(delta.stopSequence == null)
                    throw incompleteResponseFailure(metadata)
                }

                else -> throw malformedAnthropicStream()
            }
            stopReason = reason
        } ?: streamRequire(delta.stopSequence == null)
        messageDeltaSeen = true
        return emptyList()
    }

    private fun messageStop(wire: AnthropicStreamEventWire): List<UniversalAiStreamEvent> {
        streamRequire(messageDeltaSeen)
        streamRequire(openContentIndex == null)
        streamRequire(contentBlocks.isNotEmpty())
        streamRequire(contentBlocks.all(AnthropicTextStreamState::completed))
        streamRequire(
            wire.message == null &&
                wire.index == null &&
                wire.contentBlock == null &&
                wire.delta == null &&
                wire.usage == null &&
                wire.error == null,
        )
        val reason = streamValue(stopReason)
        streamRequire(reason == "end_turn" || reason == "stop_sequence")
        val text = contentBlocks.joinToString(separator = "") { state -> state.text.toString() }
        streamRequire(text.isNotEmpty())
        val output =
            request.responseFormat.schema?.let { schema ->
                streamRequire(contentBlocks.size == 1)
                UniversalAiOutput.structuredJson(
                    id = outputId(),
                    index = CANONICAL_OUTPUT_INDEX,
                    value =
                        streamValue(
                            AnthropicStructuredOutput.parseAndValidate(text, schema),
                        ),
                )
            } ?: UniversalAiOutput.text(
                id = outputId(),
                index = CANONICAL_OUTPUT_INDEX,
                text = text,
            )
        val usage = streamValue(startUsage).toCanonical(streamValue(lastOutputTokens))
        val response =
            UniversalAiResponse(
                id = streamValue(responseId),
                requestId = metadata.requestId.toStreamRequestIdOrNull(),
                target =
                    UniversalAiTarget(
                        providerId = ANTHROPIC_PROVIDER_ID,
                        modelId = streamValue(responseModel),
                    ),
                outputs = listOf(output),
                usage = usage,
                completionReason = UniversalAiCompletionReason.Stop,
            )

        isTerminal = true
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
            add(
                canonicalEvent(
                    type = UniversalAiStreamEventType.UsageUpdated,
                    usage = usage,
                ),
            )
            add(
                canonicalEvent(
                    type = UniversalAiStreamEventType.ResponseCompleted,
                    terminal = true,
                    response = response,
                ),
            )
        }
    }

    private fun ping(wire: AnthropicStreamEventWire): List<UniversalAiStreamEvent> {
        streamRequire(
            wire.message == null &&
                wire.index == null &&
                wire.contentBlock == null &&
                wire.delta == null &&
                wire.usage == null &&
                wire.error == null,
        )
        return emptyList()
    }

    private fun providerError(wire: AnthropicStreamEventWire): Nothing {
        streamRequire(
            wire.message == null &&
                wire.index == null &&
                wire.contentBlock == null &&
                wire.delta == null &&
                wire.usage == null,
        )
        val error = streamValue(wire.error)
        streamRequire(streamValue(error.type).isNotBlank())
        streamRequire(streamValue(error.message).isNotBlank())
        throw anthropicStreamProviderFailure(error, metadata)
    }

    private fun requireStarted() {
        streamValue(responseId)
        streamValue(responseModel)
        streamValue(startUsage)
    }

    private fun outputId(): OutputId = OutputId.of(streamValue(responseId).rawValue)

    private fun canonicalEvent(
        type: UniversalAiStreamEventType,
        terminal: Boolean = false,
        outputId: OutputId? = null,
        outputIndex: Int? = null,
        delta: String? = null,
        output: UniversalAiOutput? = null,
        usage: UniversalAiUsage? = null,
        response: UniversalAiResponse? = null,
    ): UniversalAiStreamEvent {
        val event =
            UniversalAiStreamEvent(
                type = type,
                terminal = terminal,
                sequence = canonicalSequence,
                responseId = streamValue(responseId),
                requestId = metadata.requestId.toStreamRequestIdOrNull(),
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

    private data class AnthropicTextStreamState(
        val text: StringBuilder = StringBuilder(),
        var completed: Boolean = false,
    )
}

private data class AnthropicStreamUsageState(
    val uncachedInputTokens: Long,
    val cacheCreationInputTokens: Long?,
    val cacheReadInputTokens: Long?,
    val initialOutputTokens: Long,
) {
    fun toCanonical(outputTokens: Long): UniversalAiUsage {
        val cacheCreationInput = cacheCreationInputTokens ?: 0L
        val cacheReadInput = cacheReadInputTokens ?: 0L
        streamRequire(uncachedInputTokens <= Long.MAX_VALUE - cacheCreationInput)
        val withCacheCreation = uncachedInputTokens + cacheCreationInput
        streamRequire(withCacheCreation <= Long.MAX_VALUE - cacheReadInput)
        val inputTokens = withCacheCreation + cacheReadInput
        streamRequire(inputTokens <= Long.MAX_VALUE - outputTokens)
        return UniversalAiUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = inputTokens + outputTokens,
            inputDetails =
                buildMap {
                    cacheCreationInputTokens?.let { value ->
                        put("cache_write_tokens", value)
                    }
                    cacheReadInputTokens?.let { value ->
                        put("cached_tokens", value)
                    }
                },
        )
    }
}

private fun AnthropicUsageWire.toStreamStartUsage(): AnthropicStreamUsageState {
    val uncachedInput = streamValue(inputTokens)
    val cacheCreationInput = cacheCreationInputTokens ?: 0L
    val cacheReadInput = cacheReadInputTokens ?: 0L
    val output = streamValue(outputTokens)
    streamRequire(
        uncachedInput >= 0L &&
            cacheCreationInput >= 0L &&
            cacheReadInput >= 0L &&
            output >= 0L,
    )
    streamRequire(
        uncachedInput <= MAX_CANONICAL_TOKEN_COUNT &&
            cacheCreationInput <= MAX_CANONICAL_TOKEN_COUNT &&
            cacheReadInput <= MAX_CANONICAL_TOKEN_COUNT &&
            output <= MAX_CANONICAL_TOKEN_COUNT,
    )
    return AnthropicStreamUsageState(
        uncachedInputTokens = uncachedInput,
        cacheCreationInputTokens = cacheCreationInputTokens,
        cacheReadInputTokens = cacheReadInputTokens,
        initialOutputTokens = output,
    )
}

private fun String?.toStreamRequestIdOrNull(): RequestId? =
    this?.let { value ->
        try {
            RequestId.of(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

internal fun malformedAnthropicStream(): UniversalAiException =
    UniversalAiException(
        UniversalAiError(
            category = UniversalAiErrorCategory.Protocol,
            code = UniversalAiErrorCode.of(MALFORMED_PROVIDER_STREAM_CODE),
            message = ANTHROPIC_MALFORMED_STREAM_MESSAGE,
        ),
    )

private fun streamRequire(condition: Boolean) {
    if (!condition) {
        throw malformedAnthropicStream()
    }
}

private fun <T : Any> streamValue(value: T?): T {
    streamRequire(value != null)
    return checkNotNull(value)
}

internal const val ANTHROPIC_MALFORMED_STREAM_MESSAGE: String =
    "The Anthropic response stream was malformed or unsupported."

private const val MALFORMED_PROVIDER_STREAM_CODE: String = "malformed_provider_stream"
private const val MAX_PROVIDER_STREAM_EVENTS: Int = 65_536
private const val MAX_PROVIDER_CONTENT_BLOCKS: Int = 128
private const val CANONICAL_OUTPUT_INDEX: Int = 0
private const val MAX_STREAM_OUTPUT_BYTES: Int = 1_048_576
private const val MAX_CANONICAL_TOKEN_COUNT: Long = 9_007_199_254_740_991L
private val SUPPORTED_PROVIDER_STREAM_EVENT_TYPES: Set<String> =
    setOf(
        "message_start",
        "content_block_start",
        "content_block_delta",
        "content_block_stop",
        "message_delta",
        "message_stop",
        "ping",
        "error",
    )
