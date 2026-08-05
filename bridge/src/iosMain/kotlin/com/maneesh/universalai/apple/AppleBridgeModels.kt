package com.maneesh.universalai.apple

/**
 * Private Apple callback-bridge DTOs.
 *
 * These types are exported only for the Swift package implementation. The supported Swift API
 * maps them into Swift-native value types, while canonical Kotlin and serialization types remain
 * hidden from the generated framework header.
 */

sealed class AppleBridgeJsonValue

class AppleBridgeJsonNull : AppleBridgeJsonValue()

class AppleBridgeJsonBoolean(
    val value: Boolean,
) : AppleBridgeJsonValue()

class AppleBridgeJsonString(
    val value: String,
) : AppleBridgeJsonValue()

/** An exact JSON number token that has not been coerced through binary floating point. */
class AppleBridgeJsonNumber(
    val rawValue: String,
) : AppleBridgeJsonValue()

class AppleBridgeJsonArray(
    values: List<AppleBridgeJsonValue>,
) : AppleBridgeJsonValue() {
    val values: List<AppleBridgeJsonValue> = values.toList()
}

class AppleBridgeJsonObjectEntry(
    val name: String,
    val value: AppleBridgeJsonValue,
)

class AppleBridgeJsonObject(
    entries: List<AppleBridgeJsonObjectEntry>,
) : AppleBridgeJsonValue() {
    val entries: List<AppleBridgeJsonObjectEntry> = entries.toList()
}

class AppleBridgeExtensionEntry(
    val namespace: String,
    val payload: AppleBridgeJsonObject,
)

class AppleBridgeExtensions(
    entries: List<AppleBridgeExtensionEntry>,
) {
    val entries: List<AppleBridgeExtensionEntry> = entries.toList()
}

class AppleBridgeTarget(
    val providerRawValue: String,
    val modelRawValue: String,
)

class AppleBridgeTextInput(
    val role: String,
    val content: String,
)

class AppleBridgeResponseFormat(
    val kind: String,
    val schema: AppleBridgeJsonValue?,
)

class AppleBridgeGenerationParameters(
    val hasMaxOutputTokens: Boolean,
    val maxOutputTokens: Long,
    val hasTemperature: Boolean,
    val temperature: Double,
    val hasTopP: Boolean,
    val topP: Double,
    stopSequences: List<String>,
) {
    val stopSequences: List<String> = stopSequences.toList()
}

class AppleBridgeRequest(
    val contractVersion: String,
    val target: AppleBridgeTarget,
    input: List<AppleBridgeTextInput>,
    val responseFormat: AppleBridgeResponseFormat,
    val generation: AppleBridgeGenerationParameters,
    val extensions: AppleBridgeExtensions,
) {
    val input: List<AppleBridgeTextInput> = input.toList()
}

class AppleBridgeOutput(
    val id: String,
    val index: Int,
    val kind: String,
    val text: String?,
    val structuredJson: AppleBridgeJsonValue?,
    val extensions: AppleBridgeExtensions,
)

class AppleBridgeLongEntry(
    val name: String,
    val value: Long,
)

class AppleBridgeUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    inputDetails: List<AppleBridgeLongEntry>,
    outputDetails: List<AppleBridgeLongEntry>,
    val extensions: AppleBridgeExtensions,
) {
    val inputDetails: List<AppleBridgeLongEntry> = inputDetails.toList()
    val outputDetails: List<AppleBridgeLongEntry> = outputDetails.toList()
}

class AppleBridgeResponse(
    val contractVersion: String,
    val id: String,
    val requestId: String?,
    val target: AppleBridgeTarget,
    outputs: List<AppleBridgeOutput>,
    val usage: AppleBridgeUsage?,
    val completionReason: String,
    val extensions: AppleBridgeExtensions,
) {
    val outputs: List<AppleBridgeOutput> = outputs.toList()
}

/** One complete canonical stream-event envelope for the private Swift-package bridge layer. */
class AppleBridgeStreamEvent(
    val contractVersion: String,
    val type: String,
    val terminal: Boolean,
    val sequence: Long,
    val responseId: String,
    val requestId: String?,
    val outputId: String?,
    val hasOutputIndex: Boolean,
    val outputIndex: Int,
    val delta: String?,
    val output: AppleBridgeOutput?,
    val usage: AppleBridgeUsage?,
    val response: AppleBridgeResponse?,
    val extensions: AppleBridgeExtensions,
)

/** A raw-preserving canonical failure exported for mapping into the supported Swift error type. */
class AppleBridgeError(
    val category: String,
    val code: String,
    val message: String,
    val metadata: AppleBridgeJsonObject?,
    val extensions: AppleBridgeExtensions,
)

/** Test-only cancellation evidence consumed by the Swift integration suite. */
class AppleBridgeInstrumentationSnapshot(
    val responseCancellations: Int,
    val streamCancellations: Int,
)
