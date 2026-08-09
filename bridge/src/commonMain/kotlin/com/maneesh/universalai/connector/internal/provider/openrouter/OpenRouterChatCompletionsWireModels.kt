package com.maneesh.universalai.connector.internal.provider.openrouter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class OpenRouterChatCompletionRequestWire(
    val model: String,
    val messages: List<OpenRouterMessageWire>,
    val stream: Boolean = false,
    @SerialName("stream_options")
    val streamOptions: OpenRouterStreamOptionsWire? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    val stop: List<String>? = null,
    @SerialName("response_format")
    val responseFormat: OpenRouterResponseFormatWire? = null,
    val provider: OpenRouterProviderPreferencesWire,
)

@Serializable
internal data class OpenRouterStreamOptionsWire(
    @SerialName("include_usage")
    val includeUsage: Boolean,
)

@Serializable
internal data class OpenRouterResponseFormatWire(
    val type: String,
    @SerialName("json_schema")
    val jsonSchema: OpenRouterJsonSchemaWire,
)

@Serializable
internal data class OpenRouterJsonSchemaWire(
    val name: String,
    val strict: Boolean,
    val schema: JsonElement,
)

@Serializable
internal data class OpenRouterProviderPreferencesWire(
    @SerialName("require_parameters")
    val requireParameters: Boolean,
)

@Serializable
internal data class OpenRouterMessageWire(
    val role: String,
    val content: String,
)

@Serializable
internal data class OpenRouterChatCompletionResponseWire(
    val id: String? = null,
    @SerialName("object")
    val objectType: String? = null,
    val model: String? = null,
    val choices: List<OpenRouterChoiceWire>? = null,
    val usage: OpenRouterUsageWire? = null,
    val error: OpenRouterErrorWire? = null,
)

@Serializable
internal data class OpenRouterChoiceWire(
    val index: Int? = null,
    val message: OpenRouterResponseMessageWire? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
    val error: OpenRouterErrorWire? = null,
    val delta: JsonElement? = null,
)

@Serializable
internal data class OpenRouterResponseMessageWire(
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
internal data class OpenRouterErrorWire(
    val code: Int? = null,
    val message: String? = null,
    val metadata: OpenRouterErrorMetadataWire? = null,
)

@Serializable
internal data class OpenRouterErrorMetadataWire(
    @SerialName("error_type")
    val errorType: String? = null,
)

@Serializable
internal data class OpenRouterUsageWire(
    @SerialName("prompt_tokens")
    val promptTokens: Long? = null,
    @SerialName("completion_tokens")
    val completionTokens: Long? = null,
    @SerialName("total_tokens")
    val totalTokens: Long? = null,
    @SerialName("prompt_tokens_details")
    val promptDetails: OpenRouterPromptTokenDetailsWire? = null,
    @SerialName("completion_tokens_details")
    val completionDetails: OpenRouterCompletionTokenDetailsWire? = null,
)

@Serializable
internal data class OpenRouterPromptTokenDetailsWire(
    @SerialName("cached_tokens")
    val cachedTokens: Long? = null,
)

@Serializable
internal data class OpenRouterCompletionTokenDetailsWire(
    @SerialName("reasoning_tokens")
    val reasoningTokens: Long? = null,
)
