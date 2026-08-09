package com.maneesh.universalai.connector.internal.provider.chatcompletions

import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.OutputId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.RequestId
import com.maneesh.universalai.connector.contract.ResponseId
import com.maneesh.universalai.connector.contract.UniversalAiCompletionReason
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiOutput
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiUsage
import com.maneesh.universalai.connector.internal.provider.openai.OpenAiStructuredOutput
import com.maneesh.universalai.connector.internal.transport.ConnectorResponseMetadata
import com.maneesh.universalai.connector.internal.transport.ConnectorServerSentEvent
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal sealed interface ChatCompletionsStreamSignal {
    data class Event(
        val event: UniversalAiStreamEvent,
    ) : ChatCompletionsStreamSignal

    data class Failure(
        val failure: Throwable,
    ) : ChatCompletionsStreamSignal
}

internal class ChatCompletionsStreamTranslator(
    private val request: UniversalAiRequest,
    private val providerId: ProviderId,
    private val metadata: ConnectorResponseMetadata,
    private val json: Json,
    private val malformedStream: () -> UniversalAiException,
    private val providerError: (JsonElement, ConnectorResponseMetadata) -> UniversalAiException,
) {
    private var canonicalSequence = 1L
    private var providerEventCount = 0
    private var responseId: ResponseId? = null
    private var responseModel: ModelId? = null
    private var created: Long? = null
    private var outputStarted = false
    private val text = StringBuilder()
    private var textBytes = 0
    private var completionReason: UniversalAiCompletionReason? = null
    private var usage: UniversalAiUsage? = null

    var isTerminal: Boolean = false
        private set

    fun translate(event: ConnectorServerSentEvent): List<UniversalAiStreamEvent> =
        try {
            translateSafely(event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: UniversalAiException) {
            throw failure
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
        providerEventCount += 1
        streamRequire(providerEventCount <= MAX_PROVIDER_STREAM_EVENTS)
        streamRequire(event.event == null && event.id == null && event.retryMillis == null)
        if (event.data == DONE_SENTINEL) {
            return complete()
        }
        streamRequire(event.data.isNotBlank())

        val chunk = json.decodeFromString<ChatCompletionsStreamChunkWire>(event.data)
        correlate(chunk)
        chunk.error?.let { error -> translateProviderErrorChunk(chunk, error) }

        val choices = streamValue(chunk.choices)
        val translated =
            when {
                choices.isEmpty() -> {
                    streamRequire(completionReason != null)
                    streamRequire(chunk.usage != null)
                    emptyList()
                }

                completionReason != null -> {
                    streamRequire(choices.size == 1)
                    streamRequire(chunk.usage != null)
                    validateTerminalUsageChoice(choices.single())
                    emptyList()
                }

                else -> {
                    streamRequire(choices.size == 1)
                    translateChoice(choices.single())
                }
            }
        chunk.usage?.let { wire ->
            streamRequire(completionReason != null)
            streamRequire(usage == null)
            usage = wire.toCanonical()
        }
        return translated
    }

    private fun correlate(chunk: ChatCompletionsStreamChunkWire) {
        streamRequire(chunk.objectType == CHAT_COMPLETION_CHUNK_OBJECT)
        val chunkResponseId = ResponseId.of(streamValue(chunk.id))
        val chunkModel = ModelId.of(streamValue(chunk.model))
        val chunkCreated = streamValue(chunk.created)
        streamRequire(chunkCreated >= 0L)
        responseId?.let { value -> streamRequire(value == chunkResponseId) }
            ?: run { responseId = chunkResponseId }
        responseModel?.let { value -> streamRequire(value == chunkModel) }
            ?: run { responseModel = chunkModel }
        created?.let { value -> streamRequire(value == chunkCreated) }
            ?: run { created = chunkCreated }
    }

    private fun translateChoice(
        choice: ChatCompletionsStreamChoiceWire,
    ): List<UniversalAiStreamEvent> {
        streamRequire(choice.index == CANONICAL_OUTPUT_INDEX)
        streamRequire(choice.message == null && choice.logprobs == null)
        val delta = streamValue(choice.delta)
        streamRequire(
            delta.refusal == null &&
                delta.reasoning == null &&
                delta.reasoningContent == null &&
                delta.reasoningDetails == null &&
                delta.annotations == null &&
                delta.images == null &&
                delta.audio == null &&
                delta.toolCalls == null &&
                delta.functionCall == null,
        )

        choice.error?.let { error ->
            streamRequire(outputStarted)
            streamRequire(choice.finishReason == ERROR_FINISH_REASON)
            streamRequire(delta.role == null)
            streamRequire(delta.content == null || delta.content.isEmpty())
            throw providerError(error, metadata)
        }

        streamRequire(completionReason == null)
        delta.role?.let { role ->
            streamRequire(role == ASSISTANT_ROLE)
        }
        val finishReason = choice.finishReason
        val content = delta.content
        streamRequire(delta.role != null || content != null || finishReason != null)
        val events = mutableListOf<UniversalAiStreamEvent>()
        if (!outputStarted) {
            outputStarted = true
            events += canonicalEvent(UniversalAiStreamEventType.ResponseStarted)
            events +=
                canonicalEvent(
                    type = UniversalAiStreamEventType.OutputStarted,
                    outputId = outputId(),
                    outputIndex = CANONICAL_OUTPUT_INDEX,
                )
        }
        content?.takeIf(String::isNotEmpty)?.let { value ->
            val bytes = value.encodeToByteArray().size
            streamRequire(bytes <= MAX_STREAM_OUTPUT_BYTES - textBytes)
            text.append(value)
            textBytes += bytes
            if (request.responseFormat.schema == null) {
                events +=
                    canonicalEvent(
                        type = UniversalAiStreamEventType.OutputDelta,
                        outputId = outputId(),
                        outputIndex = CANONICAL_OUTPUT_INDEX,
                        delta = value,
                    )
            }
        }
        finishReason?.let { value ->
            completionReason = value.toCanonicalCompletionReason()
        }
        return events
    }

    private fun validateTerminalUsageChoice(choice: ChatCompletionsStreamChoiceWire) {
        streamRequire(choice.index == CANONICAL_OUTPUT_INDEX)
        streamRequire(choice.error == null && choice.message == null && choice.logprobs == null)
        val delta = streamValue(choice.delta)
        streamRequire(delta.role == null || delta.role == ASSISTANT_ROLE)
        streamRequire(delta.content == null || delta.content.isEmpty())
        streamRequire(
            delta.refusal == null &&
                delta.reasoning == null &&
                delta.reasoningContent == null &&
                delta.reasoningDetails == null &&
                delta.annotations == null &&
                delta.images == null &&
                delta.audio == null &&
                delta.toolCalls == null &&
                delta.functionCall == null,
        )
        streamRequire(
            choice.finishReason?.toCanonicalCompletionReason() == completionReason,
        )
    }

    private fun translateProviderErrorChunk(
        chunk: ChatCompletionsStreamChunkWire,
        error: JsonElement,
    ): Nothing {
        streamRequire(outputStarted)
        streamRequire(chunk.usage == null)
        val choice = streamValue(chunk.choices).singleOrNull()
        streamRequire(choice != null)
        val terminalChoice = checkNotNull(choice)
        streamRequire(terminalChoice.index == CANONICAL_OUTPUT_INDEX)
        streamRequire(terminalChoice.finishReason == ERROR_FINISH_REASON)
        streamRequire(
            terminalChoice.error == null &&
                terminalChoice.message == null &&
                terminalChoice.logprobs == null,
        )
        val delta = streamValue(terminalChoice.delta)
        streamRequire(
            delta.role == null &&
                (delta.content == null || delta.content.isEmpty()) &&
                delta.refusal == null &&
                delta.reasoning == null &&
                delta.reasoningContent == null &&
                delta.reasoningDetails == null &&
                delta.annotations == null &&
                delta.images == null &&
                delta.audio == null &&
                delta.toolCalls == null &&
                delta.functionCall == null,
        )
        throw providerError(error, metadata)
    }

    private fun complete(): List<UniversalAiStreamEvent> {
        streamRequire(outputStarted)
        val responseId = streamValue(responseId)
        val responseModel = streamValue(responseModel)
        val completionReason = streamValue(completionReason)
        val usage = streamValue(usage)
        val finalText = text.toString()
        streamRequire(finalText.isNotBlank())
        val output =
            request.responseFormat.schema?.let { schema ->
                val value = OpenAiStructuredOutput.parseAndValidate(finalText, schema)
                streamRequire(value != null)
                UniversalAiOutput.structuredJson(
                    id = outputId(),
                    index = CANONICAL_OUTPUT_INDEX,
                    value = checkNotNull(value),
                )
            } ?: UniversalAiOutput.text(
                id = outputId(),
                index = CANONICAL_OUTPUT_INDEX,
                text = finalText,
            )
        val response =
            UniversalAiResponse(
                id = responseId,
                requestId = metadata.requestId.toCanonicalRequestIdOrNull(),
                target = UniversalAiTarget(providerId = providerId, modelId = responseModel),
                outputs = listOf(output),
                usage = usage,
                completionReason = completionReason,
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
            add(canonicalEvent(type = UniversalAiStreamEventType.UsageUpdated, usage = usage))
            add(
                canonicalEvent(
                    type = UniversalAiStreamEventType.ResponseCompleted,
                    terminal = true,
                    response = response,
                ),
            )
        }
    }

    private fun ChatCompletionsStreamUsageWire.toCanonical(): UniversalAiUsage {
        val input = streamValue(promptTokens)
        val output = streamValue(completionTokens)
        val total = streamValue(totalTokens)
        streamRequire(input >= 0L && output >= 0L && total >= 0L)
        streamRequire(
            input <= MAX_CANONICAL_TOKEN_COUNT &&
                output <= MAX_CANONICAL_TOKEN_COUNT &&
                total <= MAX_CANONICAL_TOKEN_COUNT,
        )
        val inputDetails =
            buildMap {
                promptDetails?.cachedTokens?.let { value ->
                    streamRequire(value in 0L..MAX_CANONICAL_TOKEN_COUNT)
                    put("cached_tokens", value)
                }
            }
        val outputDetails =
            buildMap {
                completionDetails?.reasoningTokens?.let { value ->
                    streamRequire(value in 0L..MAX_CANONICAL_TOKEN_COUNT)
                    put("reasoning_tokens", value)
                }
            }
        return UniversalAiUsage(
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            inputDetails = inputDetails,
            outputDetails = outputDetails,
        )
    }

    private fun String.toCanonicalCompletionReason(): UniversalAiCompletionReason =
        when (this) {
            "stop" -> UniversalAiCompletionReason.Stop
            "length" -> UniversalAiCompletionReason.MaxOutputTokens
            "content_filter" -> UniversalAiCompletionReason.ContentFilter
            else -> throw malformedStream()
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

    private fun streamRequire(condition: Boolean) {
        if (!condition) {
            throw malformedStream()
        }
    }

    private fun <T : Any> streamValue(value: T?): T {
        streamRequire(value != null)
        return checkNotNull(value)
    }
}

internal fun ConnectorTransportResponse.hasChatCompletionsEventStreamContentType(): Boolean =
    headers
        .firstOrNull { header -> header.name.equals("content-type", ignoreCase = true) }
        ?.value
        ?.substringBefore(';')
        ?.trim()
        ?.equals(EVENT_STREAM_CONTENT_TYPE, ignoreCase = true) == true

private fun String?.toCanonicalRequestIdOrNull(): RequestId? =
    this?.let { value ->
        try {
            RequestId.of(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

@Serializable
internal data class ChatCompletionsStreamChunkWire(
    val id: String? = null,
    @SerialName("object")
    val objectType: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<ChatCompletionsStreamChoiceWire>? = null,
    val usage: ChatCompletionsStreamUsageWire? = null,
    val error: JsonElement? = null,
)

@Serializable
internal data class ChatCompletionsStreamChoiceWire(
    val index: Int? = null,
    val delta: ChatCompletionsStreamDeltaWire? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
    val error: JsonElement? = null,
    val message: JsonElement? = null,
    val logprobs: JsonElement? = null,
)

@Serializable
internal data class ChatCompletionsStreamDeltaWire(
    val role: String? = null,
    val content: String? = null,
    val refusal: JsonElement? = null,
    val reasoning: JsonElement? = null,
    @SerialName("reasoning_content")
    val reasoningContent: JsonElement? = null,
    @SerialName("reasoning_details")
    val reasoningDetails: JsonElement? = null,
    val annotations: JsonElement? = null,
    val images: JsonElement? = null,
    val audio: JsonElement? = null,
    @SerialName("tool_calls")
    val toolCalls: JsonElement? = null,
    @SerialName("function_call")
    val functionCall: JsonElement? = null,
)

@Serializable
internal data class ChatCompletionsStreamUsageWire(
    @SerialName("prompt_tokens")
    val promptTokens: Long? = null,
    @SerialName("completion_tokens")
    val completionTokens: Long? = null,
    @SerialName("total_tokens")
    val totalTokens: Long? = null,
    @SerialName("prompt_tokens_details")
    val promptDetails: ChatCompletionsStreamPromptDetailsWire? = null,
    @SerialName("completion_tokens_details")
    val completionDetails: ChatCompletionsStreamCompletionDetailsWire? = null,
)

@Serializable
internal data class ChatCompletionsStreamPromptDetailsWire(
    @SerialName("cached_tokens")
    val cachedTokens: Long? = null,
)

@Serializable
internal data class ChatCompletionsStreamCompletionDetailsWire(
    @SerialName("reasoning_tokens")
    val reasoningTokens: Long? = null,
)

private const val DONE_SENTINEL: String = "[DONE]"
private const val CHAT_COMPLETION_CHUNK_OBJECT: String = "chat.completion.chunk"
private const val ASSISTANT_ROLE: String = "assistant"
private const val ERROR_FINISH_REASON: String = "error"
private const val EVENT_STREAM_CONTENT_TYPE: String = "text/event-stream"
private const val CANONICAL_OUTPUT_INDEX: Int = 0
private const val MAX_PROVIDER_STREAM_EVENTS: Int = 65_536
private const val MAX_STREAM_OUTPUT_BYTES: Int = 1_048_576
private const val MAX_CANONICAL_TOKEN_COUNT: Long = 9_007_199_254_740_991L
