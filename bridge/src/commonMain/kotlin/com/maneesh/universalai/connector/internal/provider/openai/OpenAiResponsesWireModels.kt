package com.maneesh.universalai.connector.internal.provider.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class OpenAiCreateResponseWire(
    val model: String,
    val input: List<OpenAiInputMessageWire>,
    val store: Boolean,
    @SerialName("max_output_tokens")
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
)

@Serializable
internal data class OpenAiInputMessageWire(
    val role: String,
    val content: String,
)

@Serializable
internal data class OpenAiResponseWire(
    val id: String? = null,
    @SerialName("object")
    val objectType: String? = null,
    val status: String? = null,
    val model: String? = null,
    val output: List<OpenAiOutputItemWire>? = null,
    val usage: OpenAiUsageWire? = null,
    val error: JsonElement? = null,
    @SerialName("incomplete_details")
    val incompleteDetails: JsonElement? = null,
)

@Serializable
internal data class OpenAiOutputItemWire(
    val id: String? = null,
    val type: String,
    val status: String? = null,
    val role: String? = null,
    val content: List<OpenAiOutputContentWire>? = null,
)

@Serializable
internal data class OpenAiOutputContentWire(
    val type: String,
    val text: String? = null,
)

@Serializable
internal data class OpenAiUsageWire(
    @SerialName("input_tokens")
    val inputTokens: Long? = null,
    @SerialName("output_tokens")
    val outputTokens: Long? = null,
    @SerialName("total_tokens")
    val totalTokens: Long? = null,
    @SerialName("input_tokens_details")
    val inputDetails: OpenAiInputTokenDetailsWire? = null,
    @SerialName("output_tokens_details")
    val outputDetails: OpenAiOutputTokenDetailsWire? = null,
)

@Serializable
internal data class OpenAiInputTokenDetailsWire(
    @SerialName("cached_tokens")
    val cachedTokens: Long? = null,
    @SerialName("cache_write_tokens")
    val cacheWriteTokens: Long? = null,
)

@Serializable
internal data class OpenAiOutputTokenDetailsWire(
    @SerialName("reasoning_tokens")
    val reasoningTokens: Long? = null,
)
