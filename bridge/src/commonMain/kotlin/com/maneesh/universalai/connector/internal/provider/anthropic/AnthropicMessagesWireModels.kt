package com.maneesh.universalai.connector.internal.provider.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class AnthropicCreateMessageWire(
    val model: String,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val messages: List<AnthropicInputMessageWire>,
    val system: List<AnthropicTextBlockWire>? = null,
    @SerialName("stop_sequences")
    val stopSequences: List<String>? = null,
    @SerialName("output_config")
    val outputConfig: AnthropicOutputConfigurationWire? = null,
)

@Serializable
internal data class AnthropicOutputConfigurationWire(
    val format: AnthropicOutputFormatWire,
)

@Serializable
internal data class AnthropicOutputFormatWire(
    val type: String,
    val schema: JsonElement,
)

@Serializable
internal data class AnthropicInputMessageWire(
    val role: String,
    val content: List<AnthropicTextBlockWire>,
)

@Serializable
internal data class AnthropicTextBlockWire(
    val type: String,
    val text: String,
)

@Serializable
internal data class AnthropicMessageResponseWire(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val content: List<AnthropicResponseContentWire>? = null,
    val model: String? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null,
    @SerialName("stop_sequence")
    val stopSequence: String? = null,
    val usage: AnthropicUsageWire? = null,
)

@Serializable
internal data class AnthropicResponseContentWire(
    val type: String,
    val text: String? = null,
)

@Serializable
internal data class AnthropicUsageWire(
    @SerialName("input_tokens")
    val inputTokens: Long? = null,
    @SerialName("cache_creation_input_tokens")
    val cacheCreationInputTokens: Long? = null,
    @SerialName("cache_read_input_tokens")
    val cacheReadInputTokens: Long? = null,
    @SerialName("output_tokens")
    val outputTokens: Long? = null,
)

@Serializable
internal data class AnthropicErrorEnvelopeWire(
    val type: String? = null,
    val error: AnthropicErrorWire? = null,
    @SerialName("request_id")
    val requestId: String? = null,
)

@Serializable
internal data class AnthropicErrorWire(
    val type: String? = null,
    val message: String? = null,
)
