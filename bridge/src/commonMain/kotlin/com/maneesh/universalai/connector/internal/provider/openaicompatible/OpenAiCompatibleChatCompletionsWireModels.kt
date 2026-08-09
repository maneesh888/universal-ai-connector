package com.maneesh.universalai.connector.internal.provider.openaicompatible

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class OpenAiCompatibleChatCompletionRequestWire(
    val model: String,
    val messages: List<OpenAiCompatibleMessageWire>,
    val stream: Boolean = false,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    val stop: List<String>? = null,
)

@Serializable
internal data class OpenAiCompatibleMessageWire(
    val role: String,
    val content: String,
)

@Serializable
internal data class OpenAiCompatibleChatCompletionResponseWire(
    val id: String? = null,
    @SerialName("object")
    val objectType: String? = null,
    val model: String? = null,
    val choices: List<OpenAiCompatibleChoiceWire>? = null,
    val usage: OpenAiCompatibleUsageWire? = null,
    val error: JsonElement? = null,
)

@Serializable
internal data class OpenAiCompatibleChoiceWire(
    val index: Int? = null,
    val message: OpenAiCompatibleResponseMessageWire? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
    val error: JsonElement? = null,
    val delta: JsonElement? = null,
)

@Serializable
internal data class OpenAiCompatibleResponseMessageWire(
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
internal data class OpenAiCompatibleUsageWire(
    @SerialName("prompt_tokens")
    val promptTokens: Long? = null,
    @SerialName("completion_tokens")
    val completionTokens: Long? = null,
    @SerialName("total_tokens")
    val totalTokens: Long? = null,
    @SerialName("prompt_tokens_details")
    val promptDetails: OpenAiCompatiblePromptTokenDetailsWire? = null,
    @SerialName("completion_tokens_details")
    val completionDetails: OpenAiCompatibleCompletionTokenDetailsWire? = null,
)

@Serializable
internal data class OpenAiCompatiblePromptTokenDetailsWire(
    @SerialName("cached_tokens")
    val cachedTokens: Long? = null,
)

@Serializable
internal data class OpenAiCompatibleCompletionTokenDetailsWire(
    @SerialName("reasoning_tokens")
    val reasoningTokens: Long? = null,
)
