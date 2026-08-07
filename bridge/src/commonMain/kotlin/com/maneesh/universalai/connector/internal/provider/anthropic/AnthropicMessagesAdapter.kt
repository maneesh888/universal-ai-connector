package com.maneesh.universalai.connector.internal.provider.anthropic

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
import com.maneesh.universalai.connector.internal.provider.ANTHROPIC_PROVIDER_ID
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

internal class AnthropicMessagesAdapter(
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
            translateSuccessfulResponse(request, response)
        }
    }

    override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> =
        flow {
            validateRequest(request)
            throw unsupportedRequest(ANTHROPIC_STREAMING_MESSAGE)
        }

    private fun transportRequest(request: UniversalAiRequest): ConnectorTransportRequest {
        val credential = resolveCredential()
        val body =
            ANTHROPIC_WIRE_JSON
                .encodeToString(request.toAnthropicWire())
                .encodeToByteArray()
        return ConnectorTransportRequest(
            method = "POST",
            baseUrl = configuration.validatedBaseUrl,
            endpoint = MESSAGES_ENDPOINT,
            adapterHeaders =
                listOf(
                    ConnectorTransportHeader(
                        name = "x-api-key",
                        value = credential,
                    ),
                    ConnectorTransportHeader(
                        name = "anthropic-version",
                        value = ANTHROPIC_API_VERSION,
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
        if (request.target.providerId != ANTHROPIC_PROVIDER_ID) {
            throw unsupportedRequest(ANTHROPIC_TARGET_MESSAGE)
        }
        if (request.responseFormat.kind != UniversalAiResponseFormatKind.PlainText) {
            throw unsupportedRequest(ANTHROPIC_RESPONSE_FORMAT_MESSAGE)
        }
        if (request.generation.maxOutputTokens == null) {
            throw unsupportedRequest(ANTHROPIC_MAX_OUTPUT_TOKENS_MESSAGE)
        }
        if (request.generation.temperature != null || request.generation.topP != null) {
            throw unsupportedRequest(ANTHROPIC_SAMPLING_MESSAGE)
        }
        if (!request.extensions.isEmpty) {
            throw unsupportedRequest(ANTHROPIC_EXTENSIONS_MESSAGE)
        }
        validateInputRoles(request)
    }

    private fun validateInputRoles(request: UniversalAiRequest) {
        var index = 0
        while (
            index < request.input.size &&
            request.input[index].role == UniversalAiInputRole.System
        ) {
            index += 1
        }
        if (index == request.input.size) {
            throw unsupportedRequest(ANTHROPIC_INPUT_ROLE_MESSAGE)
        }

        var expectedRole = UniversalAiInputRole.User
        while (index < request.input.size) {
            val role = request.input[index].role
            if (role != expectedRole) {
                throw unsupportedRequest(ANTHROPIC_INPUT_ROLE_MESSAGE)
            }
            expectedRole =
                if (expectedRole == UniversalAiInputRole.User) {
                    UniversalAiInputRole.Assistant
                } else {
                    UniversalAiInputRole.User
                }
            index += 1
        }
        if (expectedRole != UniversalAiInputRole.Assistant) {
            throw unsupportedRequest(ANTHROPIC_INPUT_ROLE_MESSAGE)
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
        request: UniversalAiRequest,
        response: ConnectorTransportResponse,
    ): UniversalAiResponse {
        val bytes = readBoundedBody(response.body)
        val wire =
            try {
                ANTHROPIC_WIRE_JSON.decodeFromString<AnthropicMessageResponseWire>(
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
            wire.toCanonical(request, response.metadata)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: UniversalAiException) {
            throw failure
        } catch (_: Throwable) {
            throw malformedResponse()
        }
    }
}

private fun UniversalAiRequest.toAnthropicWire(): AnthropicCreateMessageWire {
    val firstMessageIndex =
        input.indexOfFirst { item -> item.role != UniversalAiInputRole.System }
    val system =
        input
            .take(firstMessageIndex)
            .map { item -> AnthropicTextBlockWire(type = "text", text = item.content) }
            .ifEmpty { null }
    val messages =
        input
            .drop(firstMessageIndex)
            .map { item ->
                AnthropicInputMessageWire(
                    role = item.role.rawValue,
                    content =
                        listOf(
                            AnthropicTextBlockWire(
                                type = "text",
                                text = item.content,
                            ),
                        ),
                )
            }
    return AnthropicCreateMessageWire(
        model = target.modelId.rawValue,
        maxTokens = checkNotNull(generation.maxOutputTokens),
        messages = messages,
        system = system,
        stopSequences = generation.stopSequences.takeIf(List<String>::isNotEmpty),
    )
}

private fun AnthropicMessageResponseWire.toCanonical(
    request: UniversalAiRequest,
    metadata: ConnectorResponseMetadata,
): UniversalAiResponse {
    requireWire(type == "message")
    requireWire(role == "assistant")
    val responseId = ResponseId.of(requireWireValue(id))
    val responseModel = ModelId.of(requireWireValue(model))
    val providerContent = requireWireValue(content)
    requireWire(providerContent.isNotEmpty())
    val text =
        buildString {
            providerContent.forEach { block ->
                requireWire(block.type == "text")
                append(requireWireValue(block.text))
            }
        }
    requireWire(text.isNotEmpty())

    val completionReason =
        when (stopReason) {
            "end_turn" -> {
                requireWire(stopSequence == null)
                UniversalAiCompletionReason.Stop
            }

            "stop_sequence" -> {
                val matchedSequence = requireWireValue(stopSequence)
                requireWire(matchedSequence in request.generation.stopSequences)
                UniversalAiCompletionReason.Stop
            }

            else -> throw malformedResponse()
        }

    return UniversalAiResponse(
        id = responseId,
        requestId = metadata.requestId.toCanonicalRequestIdOrNull(),
        target =
            UniversalAiTarget(
                providerId = ANTHROPIC_PROVIDER_ID,
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
        completionReason = completionReason,
    )
}

private fun AnthropicUsageWire.toCanonical(): UniversalAiUsage {
    val uncachedInput = requireWireValue(inputTokens)
    val cacheCreationInput = cacheCreationInputTokens ?: 0L
    val cacheReadInput = cacheReadInputTokens ?: 0L
    val output = requireWireValue(outputTokens)
    requireWire(
        uncachedInput >= 0L &&
            cacheCreationInput >= 0L &&
            cacheReadInput >= 0L &&
            output >= 0L,
    )
    requireWire(uncachedInput <= Long.MAX_VALUE - cacheCreationInput)
    val inputWithCacheCreation = uncachedInput + cacheCreationInput
    requireWire(inputWithCacheCreation <= Long.MAX_VALUE - cacheReadInput)
    val input = inputWithCacheCreation + cacheReadInput
    requireWire(input <= Long.MAX_VALUE - output)
    val inputDetails =
        buildMap {
            cacheCreationInputTokens?.let { value -> put("cache_write_tokens", value) }
            cacheReadInputTokens?.let { value -> put("cached_tokens", value) }
        }
    return UniversalAiUsage(
        inputTokens = input,
        outputTokens = output,
        totalTokens = input + output,
        inputDetails = inputDetails,
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

private suspend fun providerFailure(
    response: ConnectorTransportResponse,
): UniversalAiException {
    readErrorEnvelopeOrNull(response.body)
    val mapping =
        when (response.statusCode) {
            400, 409, 413, 422 ->
                ProviderErrorMapping(
                    UniversalAiErrorCategory.Validation,
                    "provider_invalid_request",
                    ANTHROPIC_INVALID_REQUEST_MESSAGE,
                )

            401 ->
                ProviderErrorMapping(
                    UniversalAiErrorCategory.Authentication,
                    "provider_authentication_failed",
                    ANTHROPIC_AUTHENTICATION_MESSAGE,
                )

            403 ->
                ProviderErrorMapping(
                    UniversalAiErrorCategory.Authorization,
                    "provider_permission_denied",
                    ANTHROPIC_PERMISSION_MESSAGE,
                )

            404 ->
                ProviderErrorMapping(
                    UniversalAiErrorCategory.NotFound,
                    "provider_resource_not_found",
                    ANTHROPIC_NOT_FOUND_MESSAGE,
                )

            429 ->
                ProviderErrorMapping(
                    UniversalAiErrorCategory.RateLimit,
                    "provider_rate_limited",
                    ANTHROPIC_RATE_LIMIT_MESSAGE,
                )

            500 ->
                ProviderErrorMapping(
                    UniversalAiErrorCategory.Provider,
                    "provider_server_error",
                    ANTHROPIC_SERVER_ERROR_MESSAGE,
                )

            504 ->
                ProviderErrorMapping(
                    UniversalAiErrorCategory.Provider,
                    "provider_request_timeout",
                    ANTHROPIC_TIMEOUT_MESSAGE,
                )

            529 ->
                ProviderErrorMapping(
                    UniversalAiErrorCategory.Provider,
                    "provider_unavailable",
                    ANTHROPIC_UNAVAILABLE_MESSAGE,
                )

            else -> PROVIDER_FAILURE_MAPPING
        }
    return mapping.toException(response.metadata.safeErrorMetadata(response.statusCode))
}

private suspend fun readErrorEnvelopeOrNull(
    reader: ConnectorTransportChunkReader,
): AnthropicErrorEnvelopeWire? {
    val bytes = readOptionalBoundedBody(reader) ?: return null
    if (bytes.isEmpty()) {
        return null
    }
    return try {
        ANTHROPIC_WIRE_JSON.decodeFromString<AnthropicErrorEnvelopeWire>(
            bytes.decodeToString(throwOnInvalidSequence = true),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }
}

private suspend fun readOptionalBoundedBody(
    reader: ConnectorTransportChunkReader,
): ByteArray? {
    var buffer = ByteArray(INITIAL_ERROR_BODY_CAPACITY)
    var size = 0
    var chunkCount = 0
    return try {
        while (true) {
            val chunk = reader.readChunk() ?: break
            chunkCount += 1
            if (
                chunkCount > MAX_ERROR_BODY_CHUNKS ||
                chunk.size > MAX_ERROR_BODY_BYTES - size
            ) {
                return null
            }
            val requiredSize = size + chunk.size
            if (requiredSize > buffer.size) {
                var expandedSize = buffer.size
                while (expandedSize < requiredSize) {
                    expandedSize = minOf(MAX_ERROR_BODY_BYTES, expandedSize * 2)
                }
                buffer = buffer.copyOf(expandedSize)
            }
            chunk.copyInto(buffer, destinationOffset = size)
            size = requiredSize
        }
        if (size == buffer.size) buffer else buffer.copyOf(size)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }
}

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
            message = ANTHROPIC_CREDENTIAL_MESSAGE,
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
            message = ANTHROPIC_MALFORMED_RESPONSE_MESSAGE,
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

internal const val ANTHROPIC_CREDENTIAL_MESSAGE: String =
    "Anthropic authentication requires a non-blank host-supplied credential."
internal const val ANTHROPIC_MALFORMED_RESPONSE_MESSAGE: String =
    "The Anthropic response was malformed or unsupported."
internal const val ANTHROPIC_TARGET_MESSAGE: String =
    "The Anthropic adapter accepts only the canonical anthropic provider."
internal const val ANTHROPIC_RESPONSE_FORMAT_MESSAGE: String =
    "The active Anthropic adapter package supports plain-text responses only."
internal const val ANTHROPIC_MAX_OUTPUT_TOKENS_MESSAGE: String =
    "The Anthropic adapter requires maxOutputTokens."
internal const val ANTHROPIC_SAMPLING_MESSAGE: String =
    "The Anthropic adapter does not support explicit sampling parameters."
internal const val ANTHROPIC_EXTENSIONS_MESSAGE: String =
    "The active Anthropic adapter package does not support request extensions."
internal const val ANTHROPIC_INPUT_ROLE_MESSAGE: String =
    "The Anthropic adapter requires leading system input followed by alternating user and assistant turns ending with user."
internal const val ANTHROPIC_STREAMING_MESSAGE: String =
    "The active Anthropic adapter package does not support streaming."
internal const val ANTHROPIC_INVALID_REQUEST_MESSAGE: String =
    "Anthropic rejected the request."
internal const val ANTHROPIC_AUTHENTICATION_MESSAGE: String =
    "Anthropic rejected the request authentication."
internal const val ANTHROPIC_PERMISSION_MESSAGE: String =
    "Anthropic denied access to the requested resource."
internal const val ANTHROPIC_NOT_FOUND_MESSAGE: String =
    "Anthropic could not find the requested resource."
internal const val ANTHROPIC_RATE_LIMIT_MESSAGE: String =
    "Anthropic rate-limited the request."
internal const val ANTHROPIC_SERVER_ERROR_MESSAGE: String =
    "Anthropic encountered a server error."
internal const val ANTHROPIC_TIMEOUT_MESSAGE: String =
    "Anthropic timed out while processing the request."
internal const val ANTHROPIC_UNAVAILABLE_MESSAGE: String =
    "Anthropic is temporarily unavailable."
internal const val ANTHROPIC_PROVIDER_FAILURE_MESSAGE: String =
    "Anthropic could not complete the request."

private const val MESSAGES_ENDPOINT: String = "messages"
private const val ANTHROPIC_API_VERSION: String = "2023-06-01"
private const val JSON_CONTENT_TYPE: String = "application/json"
private const val MAX_CREDENTIAL_CHARACTERS: Int = 8_192
private const val MAX_RESPONSE_BODY_BYTES: Int = 8 * 1_024 * 1_024
private const val INITIAL_RESPONSE_BODY_CAPACITY: Int = 8 * 1_024
private const val MAX_RESPONSE_BODY_CHUNKS: Int = 4 * 1_024
private const val MAX_ERROR_BODY_BYTES: Int = 256 * 1_024
private const val INITIAL_ERROR_BODY_CAPACITY: Int = 4 * 1_024
private const val MAX_ERROR_BODY_CHUNKS: Int = 1_024

private val PROVIDER_FAILURE_MAPPING =
    ProviderErrorMapping(
        category = UniversalAiErrorCategory.Provider,
        code = "provider_request_failed",
        message = ANTHROPIC_PROVIDER_FAILURE_MESSAGE,
    )

internal val ANTHROPIC_WIRE_JSON =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
