package com.maneesh.universalai.connector.internal.provider.openaicompatible

import com.maneesh.universalai.connector.UniversalAiProviderConfiguration
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.OutputId
import com.maneesh.universalai.connector.contract.RequestId
import com.maneesh.universalai.connector.contract.ResponseId
import com.maneesh.universalai.connector.contract.UniversalAiCompletionReason
import com.maneesh.universalai.connector.contract.UniversalAiError
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiOutput
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormatKind
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiUsage
import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.internal.ConnectorEngine
import com.maneesh.universalai.connector.internal.provider.OPENAI_COMPATIBLE_PROVIDER_ID
import com.maneesh.universalai.connector.internal.transport.ConnectorResponseMetadata
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportChunkReader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportHeader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportRequest
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class OpenAiCompatibleChatCompletionsAdapter(
    private val configuration: UniversalAiProviderConfiguration,
    private val transport: ConnectorTransport,
) : ConnectorEngine {
    override suspend fun respond(request: UniversalAiRequest): UniversalAiResponse {
        validateRequest(request)
        val transportRequest = transportRequest(request)
        return transport.execute(transportRequest) { response ->
            if (response.statusCode !in 200..299) {
                throw providerFailure(response)
            }
            translateSuccessfulResponse(response)
        }
    }

    override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> =
        flow {
            validateRequest(request)
            throw unsupportedRequest(OPENAI_COMPATIBLE_STREAMING_MESSAGE)
        }

    private fun transportRequest(request: UniversalAiRequest): ConnectorTransportRequest {
        val credential = resolveCredential()
        val body =
            OPENAI_COMPATIBLE_WIRE_JSON
                .encodeToString(request.toOpenAiCompatibleWire())
                .encodeToByteArray()
        return ConnectorTransportRequest(
            method = "POST",
            baseUrl = configuration.validatedBaseUrl,
            endpoint = CHAT_COMPLETIONS_ENDPOINT,
            adapterHeaders =
                listOf(
                    ConnectorTransportHeader(
                        name = "authorization",
                        value = "Bearer $credential",
                    ),
                    ConnectorTransportHeader(
                        name = "content-type",
                        value = JSON_CONTENT_TYPE,
                    ),
                    ConnectorTransportHeader(
                        name = "accept",
                        value = JSON_CONTENT_TYPE,
                    ),
                ),
            body = body,
        )
    }

    private fun validateRequest(request: UniversalAiRequest) {
        if (request.target.providerId != OPENAI_COMPATIBLE_PROVIDER_ID) {
            throw unsupportedRequest(OPENAI_COMPATIBLE_TARGET_MESSAGE)
        }
        if (request.responseFormat.kind != UniversalAiResponseFormatKind.PlainText) {
            throw unsupportedRequest(OPENAI_COMPATIBLE_RESPONSE_FORMAT_MESSAGE)
        }
        if (!request.extensions.isEmpty) {
            throw unsupportedRequest(OPENAI_COMPATIBLE_EXTENSIONS_MESSAGE)
        }
        if (
            request.input.any { input ->
                input.role != UniversalAiInputRole.System &&
                    input.role != UniversalAiInputRole.User &&
                    input.role != UniversalAiInputRole.Assistant
            }
        ) {
            throw unsupportedRequest(OPENAI_COMPATIBLE_INPUT_ROLE_MESSAGE)
        }
    }

    private fun resolveCredential(): String {
        val credential =
            try {
                configuration.credentialSupplier()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                throw credentialFailure()
            }
        if (
            credential.isBlank() ||
            credential.length > MAX_CREDENTIAL_CHARACTERS ||
            credential.any { character ->
                character.isWhitespace() ||
                    character.code <= 0x1f ||
                    character.code == 0x7f
            }
        ) {
            throw credentialFailure()
        }
        return credential
    }

    private suspend fun translateSuccessfulResponse(
        response: ConnectorTransportResponse,
    ): UniversalAiResponse {
        val bytes = readBoundedBody(response.body)
        val wire =
            try {
                OPENAI_COMPATIBLE_WIRE_JSON
                    .decodeFromString<OpenAiCompatibleChatCompletionResponseWire>(
                        bytes.decodeToString(throwOnInvalidSequence = true),
                    )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SerializationException) {
                throw malformedResponse()
            } catch (_: IllegalArgumentException) {
                throw malformedResponse()
            } catch (_: Throwable) {
                throw malformedResponse()
            }

        return try {
            wire.toCanonical(metadata = response.metadata)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: UniversalAiException) {
            throw failure
        } catch (_: Throwable) {
            throw malformedResponse()
        }
    }
}

private fun UniversalAiRequest.toOpenAiCompatibleWire():
    OpenAiCompatibleChatCompletionRequestWire =
    OpenAiCompatibleChatCompletionRequestWire(
        model = target.modelId.rawValue,
        messages =
            input.map { item ->
                OpenAiCompatibleMessageWire(
                    role = item.role.rawValue,
                    content = item.content,
                )
            },
        maxTokens = generation.maxOutputTokens,
        temperature = generation.temperature,
        topP = generation.topP,
        stop = generation.stopSequences.takeIf { values -> values.isNotEmpty() },
    )

internal fun OpenAiCompatibleChatCompletionResponseWire.toCanonical(
    metadata: ConnectorResponseMetadata,
): UniversalAiResponse {
    if (error != null) {
        throw providerResponseFailure(metadata)
    }
    requireWire(objectType == "chat.completion")
    val responseId = ResponseId.of(requireWireValue(id))
    val responseModel = ModelId.of(requireWireValue(model))
    val choice = requireWireValue(choices).singleOrNull() ?: throw malformedResponse()
    if (choice.error != null) {
        throw providerResponseFailure(metadata)
    }
    requireWire(choice.delta == null)
    requireWire(choice.index == 0)
    val message = requireWireValue(choice.message)
    requireWire(message.role == "assistant")
    requireWire(
        message.refusal == null &&
            message.reasoning == null &&
            message.reasoningContent == null &&
            message.reasoningDetails == null &&
            message.annotations == null &&
            message.images == null &&
            message.audio == null &&
            message.toolCalls == null &&
            message.functionCall == null,
    )
    val text = requireWireValue(message.content)
    requireWire(text.isNotBlank())

    return UniversalAiResponse(
        id = responseId,
        requestId = metadata.requestId.toCanonicalRequestIdOrNull(),
        target =
            UniversalAiTarget(
                providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                modelId = responseModel,
            ),
        outputs =
            listOf(
                UniversalAiOutput.text(
                    id = OutputId.of(responseId.rawValue),
                    index = 0,
                    text = text,
                ),
            ),
        usage = requireWireValue(usage).toCanonical(),
        completionReason = requireWireValue(choice.finishReason).toCanonicalCompletionReason(),
    )
}

private fun String.toCanonicalCompletionReason(): UniversalAiCompletionReason =
    when (this) {
        "stop" -> UniversalAiCompletionReason.Stop
        "length" -> UniversalAiCompletionReason.MaxOutputTokens
        "content_filter" -> UniversalAiCompletionReason.ContentFilter
        else -> throw malformedResponse()
    }

private fun OpenAiCompatibleUsageWire.toCanonical(): UniversalAiUsage {
    val input = requireWireValue(promptTokens)
    val output = requireWireValue(completionTokens)
    val total = requireWireValue(totalTokens)
    requireWire(input >= 0 && output >= 0 && total >= 0)
    val inputDetails =
        buildMap {
            promptDetails?.cachedTokens?.let { value ->
                requireWire(value >= 0)
                put("cached_tokens", value)
            }
        }
    val outputDetails =
        buildMap {
            completionDetails?.reasoningTokens?.let { value ->
                requireWire(value >= 0)
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

private fun String?.toCanonicalRequestIdOrNull(): RequestId? =
    this?.let { value ->
        try {
            RequestId.of(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

private suspend fun readBoundedBody(reader: ConnectorTransportChunkReader): ByteArray {
    var buffer = ByteArray(INITIAL_RESPONSE_BODY_CAPACITY)
    var size = 0
    var chunkCount = 0
    while (true) {
        val chunk = reader.readChunk() ?: break
        chunkCount += 1
        if (
            chunkCount > MAX_RESPONSE_BODY_CHUNKS ||
            chunk.size > MAX_RESPONSE_BODY_BYTES - size
        ) {
            throw malformedResponse()
        }
        val requiredSize = size + chunk.size
        if (requiredSize > buffer.size) {
            var expandedSize = buffer.size
            while (expandedSize < requiredSize) {
                expandedSize = minOf(MAX_RESPONSE_BODY_BYTES, expandedSize * 2)
            }
            buffer = buffer.copyOf(expandedSize)
        }
        chunk.copyInto(buffer, destinationOffset = size)
        size = requiredSize
    }
    requireWire(size > 0)
    return if (size == buffer.size) buffer else buffer.copyOf(size)
}

private fun providerFailure(response: ConnectorTransportResponse): UniversalAiException =
    PROVIDER_FAILURE_MAPPING.toException(
        metadata = response.metadata.safeErrorMetadata(response.statusCode),
    )

private fun providerResponseFailure(metadata: ConnectorResponseMetadata): UniversalAiException =
    PROVIDER_FAILURE_MAPPING.toException(
        metadata = metadata.safeErrorMetadata(SUCCESS_STATUS_CODE),
    )

private fun ConnectorResponseMetadata.safeErrorMetadata(
    statusCode: Int,
): ExtensionValue.ObjectValue {
    val members = linkedMapOf<String, ExtensionValue>()
    members["statusCode"] = ExtensionValue.number(statusCode.toString())
    requestId?.let { value -> members["requestId"] = ExtensionValue.string(value) }
    retryAfterMillis?.let { value ->
        members["retryAfterMillis"] = ExtensionValue.number(value.toString())
    }
    return ExtensionValue.objectValue(members)
}

private data class ProviderErrorMapping(
    val category: UniversalAiErrorCategory,
    val code: String,
    val message: String,
) {
    fun toException(metadata: ExtensionValue.ObjectValue): UniversalAiException =
        UniversalAiException(
            UniversalAiError(
                category = category,
                code = UniversalAiErrorCode.of(code),
                message = message,
                metadata = metadata,
            ),
        )
}

private fun credentialFailure(): UniversalAiException =
    UniversalAiException(
        UniversalAiError(
            category = UniversalAiErrorCategory.Authentication,
            code = UniversalAiErrorCode.of("missing_credential"),
            message = OPENAI_COMPATIBLE_CREDENTIAL_MESSAGE,
        ),
    )

private fun unsupportedRequest(message: String): UniversalAiException =
    UniversalAiException(
        UniversalAiError(
            category = UniversalAiErrorCategory.Validation,
            code = UniversalAiErrorCode.InvalidRequest,
            message = message,
        ),
    )

private fun malformedResponse(): UniversalAiException =
    UniversalAiException(
        UniversalAiError(
            category = UniversalAiErrorCategory.Protocol,
            code = UniversalAiErrorCode.of("malformed_provider_response"),
            message = OPENAI_COMPATIBLE_MALFORMED_RESPONSE_MESSAGE,
        ),
    )

private fun requireWire(condition: Boolean) {
    if (!condition) {
        throw malformedResponse()
    }
}

private fun <T : Any> requireWireValue(value: T?): T {
    requireWire(value != null)
    return checkNotNull(value)
}

internal const val OPENAI_COMPATIBLE_CREDENTIAL_MESSAGE: String =
    "The OpenAI-compatible endpoint requires a non-blank host-supplied credential."
internal const val OPENAI_COMPATIBLE_MALFORMED_RESPONSE_MESSAGE: String =
    "The OpenAI-compatible response was malformed or unsupported."
internal const val OPENAI_COMPATIBLE_TARGET_MESSAGE: String =
    "The generic adapter accepts only the canonical openai-compatible provider."
internal const val OPENAI_COMPATIBLE_RESPONSE_FORMAT_MESSAGE: String =
    "The active generic adapter package supports plain-text responses only."
internal const val OPENAI_COMPATIBLE_EXTENSIONS_MESSAGE: String =
    "The active generic adapter package does not support request extensions."
internal const val OPENAI_COMPATIBLE_INPUT_ROLE_MESSAGE: String =
    "The generic adapter supports only system, user, and assistant text input roles."
internal const val OPENAI_COMPATIBLE_STREAMING_MESSAGE: String =
    "The active generic adapter package does not support streaming."
internal const val OPENAI_COMPATIBLE_PROVIDER_FAILURE_MESSAGE: String =
    "The OpenAI-compatible endpoint could not complete the request."

private const val CHAT_COMPLETIONS_ENDPOINT: String = "chat/completions"
private const val JSON_CONTENT_TYPE: String = "application/json"
private const val MAX_CREDENTIAL_CHARACTERS: Int = 8_192
private const val MAX_RESPONSE_BODY_BYTES: Int = 8 * 1_024 * 1_024
private const val INITIAL_RESPONSE_BODY_CAPACITY: Int = 8 * 1_024
private const val MAX_RESPONSE_BODY_CHUNKS: Int = 4 * 1_024
private const val SUCCESS_STATUS_CODE: Int = 200

private val PROVIDER_FAILURE_MAPPING =
    ProviderErrorMapping(
        category = UniversalAiErrorCategory.Provider,
        code = "provider_request_failed",
        message = OPENAI_COMPATIBLE_PROVIDER_FAILURE_MESSAGE,
    )

internal val OPENAI_COMPATIBLE_WIRE_JSON =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
