package com.maneesh.universalai.connector.internal.provider.openrouter

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
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormat
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormatKind
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiUsage
import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.internal.ConnectorEngine
import com.maneesh.universalai.connector.internal.provider.OPENROUTER_PROVIDER_ID
import com.maneesh.universalai.connector.internal.provider.chatcompletions.ChatCompletionsStreamSignal
import com.maneesh.universalai.connector.internal.provider.chatcompletions.ChatCompletionsStreamTranslator
import com.maneesh.universalai.connector.internal.provider.chatcompletions.hasChatCompletionsEventStreamContentType
import com.maneesh.universalai.connector.internal.provider.openai.OpenAiStructuredOutput
import com.maneesh.universalai.connector.internal.transport.ConnectorResponseMetadata
import com.maneesh.universalai.connector.internal.transport.ConnectorServerSentEventReader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportChunkReader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportHeader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportRequest
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal class OpenRouterChatCompletionsAdapter(
    private val configuration: UniversalAiProviderConfiguration,
    private val transport: ConnectorTransport,
) : ConnectorEngine {
    override suspend fun respond(request: UniversalAiRequest): UniversalAiResponse {
        validateRequest(request)
        val transportRequest = transportRequest(request, stream = false)
        return transport.execute(transportRequest) { response ->
            if (response.statusCode !in 200..299) {
                throw providerFailure(response)
            }
            translateSuccessfulResponse(request, response)
        }
    }

    override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> =
        channelFlow<ChatCompletionsStreamSignal> {
            try {
                validateRequest(request)
                val transportRequest = transportRequest(request, stream = true)
                transport.execute(transportRequest) { response ->
                    if (response.statusCode !in 200..299) {
                        throw providerFailure(response)
                    }
                    if (!response.hasChatCompletionsEventStreamContentType()) {
                        throw malformedOpenRouterStream()
                    }
                    val reader = ConnectorServerSentEventReader(response.body)
                    val translator =
                        ChatCompletionsStreamTranslator(
                            request = request,
                            providerId = OPENROUTER_PROVIDER_ID,
                            metadata = response.metadata,
                            json = OPENROUTER_WIRE_JSON,
                            malformedStream = ::malformedOpenRouterStream,
                            providerError = ::openRouterStreamProviderFailure,
                        )
                    while (true) {
                        val event = reader.readEvent() ?: break
                        translator.translate(event).forEach { translated ->
                            send(ChatCompletionsStreamSignal.Event(translated))
                        }
                        if (translator.isTerminal) {
                            return@execute
                        }
                    }
                    translator.finish()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                send(ChatCompletionsStreamSignal.Failure(failure))
            }
        }
            .buffer(Channel.RENDEZVOUS)
            .transform { signal ->
                when (signal) {
                    is ChatCompletionsStreamSignal.Event -> emit(signal.event)
                    is ChatCompletionsStreamSignal.Failure -> throw signal.failure
                }
            }

    private fun transportRequest(
        request: UniversalAiRequest,
        stream: Boolean,
    ): ConnectorTransportRequest {
        val credential = resolveCredential()
        val body =
            OPENROUTER_WIRE_JSON
                .encodeToString(request.toOpenRouterWire(stream))
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
                        value = if (stream) EVENT_STREAM_CONTENT_TYPE else JSON_CONTENT_TYPE,
                    ),
                ),
            body = body,
        )
    }

    private fun validateRequest(request: UniversalAiRequest) {
        if (request.target.providerId != OPENROUTER_PROVIDER_ID) {
            throw unsupportedRequest(OPENROUTER_TARGET_MESSAGE)
        }
        when (request.responseFormat.kind) {
            UniversalAiResponseFormatKind.PlainText -> Unit
            UniversalAiResponseFormatKind.JsonSchema -> {
                val schema =
                    request.responseFormat.schema
                        ?: throw unsupportedRequest(OPENROUTER_RESPONSE_FORMAT_MESSAGE)
                if (!OpenAiStructuredOutput.isSupported(schema)) {
                    throw unsupportedRequest(OPENROUTER_STRUCTURED_SCHEMA_MESSAGE)
                }
            }

            else -> throw unsupportedRequest(OPENROUTER_RESPONSE_FORMAT_MESSAGE)
        }
        if (!request.extensions.isEmpty) {
            throw unsupportedRequest(OPENROUTER_EXTENSIONS_MESSAGE)
        }
        if (
            request.input.any { input ->
                input.role != UniversalAiInputRole.System &&
                    input.role != UniversalAiInputRole.User &&
                    input.role != UniversalAiInputRole.Assistant
            }
        ) {
            throw unsupportedRequest(OPENROUTER_INPUT_ROLE_MESSAGE)
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
                OPENROUTER_WIRE_JSON.decodeFromString<OpenRouterChatCompletionResponseWire>(
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
            wire.toCanonical(
                request = request,
                metadata = response.metadata,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: UniversalAiException) {
            throw failure
        } catch (_: Throwable) {
            throw malformedResponse()
        }
    }
}

private fun UniversalAiRequest.toOpenRouterWire(
    stream: Boolean,
): OpenRouterChatCompletionRequestWire =
    OpenRouterChatCompletionRequestWire(
        model = target.modelId.rawValue,
        messages =
            input.map { item ->
                OpenRouterMessageWire(
                    role = item.role.rawValue,
                    content = item.content,
                )
            },
        stream = stream,
        streamOptions = OpenRouterStreamOptionsWire(includeUsage = true).takeIf { stream },
        maxTokens = generation.maxOutputTokens,
        temperature = generation.temperature,
        topP = generation.topP,
        stop = generation.stopSequences.takeIf { values -> values.isNotEmpty() },
        responseFormat = responseFormat.toOpenRouterWireOrNull(),
        provider =
            OpenRouterProviderPreferencesWire(
                requireParameters = true,
            ),
    )

private fun UniversalAiResponseFormat.toOpenRouterWireOrNull(): OpenRouterResponseFormatWire? =
    schema?.let { schema ->
        OpenRouterResponseFormatWire(
            type = "json_schema",
            jsonSchema =
                OpenRouterJsonSchemaWire(
                    name = OPENROUTER_STRUCTURED_OUTPUT_NAME,
                    strict = true,
                    schema = schema.elementForSerialization(),
                ),
        )
    }

internal fun OpenRouterChatCompletionResponseWire.toCanonical(
    request: UniversalAiRequest,
    metadata: ConnectorResponseMetadata,
): UniversalAiResponse {
    if (error != null) {
        throw openRouterProviderResponseFailure(error, metadata)
    }
    requireWire(objectType == "chat.completion")
    val responseId = ResponseId.of(requireWireValue(id))
    val responseModel = ModelId.of(requireWireValue(model))
    val choice = requireWireValue(choices).singleOrNull() ?: throw malformedResponse()
    choice.error?.let { error -> throw openRouterProviderResponseFailure(error, metadata) }
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
                providerId = OPENROUTER_PROVIDER_ID,
                modelId = responseModel,
            ),
        outputs = listOf(text.toCanonicalOutput(request, responseId)),
        usage = requireWireValue(usage).toCanonical(),
        completionReason = requireWireValue(choice.finishReason).toCanonicalCompletionReason(),
    )
}

private fun String.toCanonicalOutput(
    request: UniversalAiRequest,
    responseId: ResponseId,
): UniversalAiOutput =
    request.responseFormat.schema?.let { schema ->
        val value =
            OpenAiStructuredOutput.parseAndValidate(
                json = this,
                schema = schema,
            ) ?: throw malformedStructuredResponse()
        UniversalAiOutput.structuredJson(
            id = OutputId.of(responseId.rawValue),
            index = 0,
            value = value,
        )
    } ?: UniversalAiOutput.text(
        id = OutputId.of(responseId.rawValue),
        index = 0,
        text = this,
    )

private fun String.toCanonicalCompletionReason(): UniversalAiCompletionReason =
    when (this) {
        "stop" -> UniversalAiCompletionReason.Stop
        "length" -> UniversalAiCompletionReason.MaxOutputTokens
        "content_filter" -> UniversalAiCompletionReason.ContentFilter
        else -> throw malformedResponse()
    }

private fun OpenRouterUsageWire.toCanonical(): UniversalAiUsage {
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
                expandedSize =
                    minOf(
                        MAX_RESPONSE_BODY_BYTES,
                        expandedSize * 2,
                    )
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
    val typedMapping = response.decodeErrorEnvelopeOrNull()?.toTypedErrorMappingOrNull()
    return (typedMapping ?: statusErrorMapping(response.statusCode)).toException(
        metadata = response.metadata.safeErrorMetadata(response.statusCode),
    )
}

internal fun openRouterProviderResponseFailure(
    error: OpenRouterErrorWire,
    metadata: ConnectorResponseMetadata,
): UniversalAiException =
    (error.toTypedErrorMappingOrNull() ?: PROVIDER_FAILURE_MAPPING).toException(
        metadata = metadata.safeErrorMetadata(error.code ?: SUCCESS_STATUS_CODE),
)

private fun openRouterStreamProviderFailure(
    element: JsonElement,
    metadata: ConnectorResponseMetadata,
): UniversalAiException {
    val error = element as? JsonObject ?: throw malformedOpenRouterStream()
    val message = (error["message"] as? JsonPrimitive)?.contentOrNull
    if (message.isNullOrBlank()) {
        throw malformedOpenRouterStream()
    }
    val code = (error["code"] as? JsonPrimitive)?.intOrNull
    val metadataObject = error["metadata"] as? JsonObject
    val errorType = (metadataObject?.get("error_type") as? JsonPrimitive)?.contentOrNull
    return openRouterProviderResponseFailure(
        error =
            OpenRouterErrorWire(
                code = code,
                message = message,
                metadata = OpenRouterErrorMetadataWire(errorType = errorType),
            ),
        metadata = metadata,
    )
}

private suspend fun ConnectorTransportResponse.decodeErrorEnvelopeOrNull(): OpenRouterErrorWire? {
    val bytes =
        try {
            readBoundedBody(body)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return null
        }
    return try {
        OPENROUTER_WIRE_JSON
            .decodeFromString<OpenRouterChatCompletionResponseWire>(
                bytes.decodeToString(throwOnInvalidSequence = true),
            ).error
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }
}

private fun statusErrorMapping(statusCode: Int): ProviderErrorMapping =
    when (statusCode) {
        400, 409, 413, 422 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Validation,
                code = "provider_invalid_request",
                message = OPENROUTER_INVALID_REQUEST_MESSAGE,
            )
        401 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Authentication,
                code = "provider_authentication_failed",
                message = OPENROUTER_AUTHENTICATION_MESSAGE,
            )
        402 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Authorization,
                code = "provider_permission_denied",
                message = OPENROUTER_PAYMENT_REQUIRED_MESSAGE,
            )
        403 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Authorization,
                code = "provider_permission_denied",
                message = OPENROUTER_PERMISSION_MESSAGE,
            )
        404 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.NotFound,
                code = "provider_resource_not_found",
                message = OPENROUTER_NOT_FOUND_MESSAGE,
            )
        408, 504 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Provider,
                code = "provider_request_timeout",
                message = OPENROUTER_TIMEOUT_MESSAGE,
            )
        429 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.RateLimit,
                code = "provider_rate_limited",
                message = OPENROUTER_RATE_LIMIT_MESSAGE,
            )
        502, 503 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Provider,
                code = "provider_unavailable",
                message = OPENROUTER_UNAVAILABLE_MESSAGE,
            )
        in 500..599 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Provider,
                code = "provider_server_error",
                message = OPENROUTER_SERVER_ERROR_MESSAGE,
            )
        else -> PROVIDER_FAILURE_MAPPING
    }

private fun OpenRouterErrorWire.toTypedErrorMappingOrNull(): ProviderErrorMapping? =
    when (metadata?.errorType) {
        "context_length_exceeded",
        "max_tokens_exceeded",
        "token_limit_exceeded",
        "string_too_long",
        "invalid_request",
        "invalid_prompt",
        "precondition_failed",
        "payload_too_large",
        "unprocessable",
        "invalid_image",
        "image_too_large",
        "image_too_small",
        "unsupported_image_format",
        "image_download_failed",
        ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Validation,
                code = "provider_invalid_request",
                message = OPENROUTER_INVALID_REQUEST_MESSAGE,
            )

        "authentication" ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Authentication,
                code = "provider_authentication_failed",
                message = OPENROUTER_AUTHENTICATION_MESSAGE,
            )

        "permission_denied" ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Authorization,
                code = "provider_permission_denied",
                message = OPENROUTER_PERMISSION_MESSAGE,
            )

        "payment_required" ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Authorization,
                code = "provider_permission_denied",
                message = OPENROUTER_PAYMENT_REQUIRED_MESSAGE,
            )

        "rate_limit_exceeded" ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.RateLimit,
                code = "provider_rate_limited",
                message = OPENROUTER_RATE_LIMIT_MESSAGE,
            )

        "not_found", "image_not_found" ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.NotFound,
                code = "provider_resource_not_found",
                message = OPENROUTER_NOT_FOUND_MESSAGE,
            )

        "content_policy_violation", "refusal" ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Provider,
                code = "provider_response_filtered",
                message = OPENROUTER_FILTERED_RESPONSE_MESSAGE,
            )

        "provider_overloaded", "provider_unavailable" ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Provider,
                code = "provider_unavailable",
                message = OPENROUTER_UNAVAILABLE_MESSAGE,
            )

        "timeout" ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Provider,
                code = "provider_request_timeout",
                message = OPENROUTER_TIMEOUT_MESSAGE,
            )

        "server" ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Provider,
                code = "provider_server_error",
                message = OPENROUTER_SERVER_ERROR_MESSAGE,
            )

        "unmapped" -> PROVIDER_FAILURE_MAPPING
        else -> null
    }

private fun ConnectorResponseMetadata.safeErrorMetadata(
    statusCode: Int,
): ExtensionValue.ObjectValue {
    val members = linkedMapOf<String, ExtensionValue>()
    members["statusCode"] = ExtensionValue.number(statusCode.toString())
    requestId?.let { value ->
        members["requestId"] = ExtensionValue.string(value)
    }
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
            message = OPENROUTER_CREDENTIAL_MESSAGE,
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
            message = OPENROUTER_MALFORMED_RESPONSE_MESSAGE,
        ),
    )

private fun malformedStructuredResponse(): UniversalAiException =
    UniversalAiException(
        UniversalAiError(
            category = UniversalAiErrorCategory.Protocol,
            code = UniversalAiErrorCode.of("invalid_structured_provider_response"),
            message = OPENROUTER_INVALID_STRUCTURED_RESPONSE_MESSAGE,
        ),
    )

internal fun malformedOpenRouterStream(): UniversalAiException =
    UniversalAiException(
        UniversalAiError(
            category = UniversalAiErrorCategory.Protocol,
            code = UniversalAiErrorCode.of("malformed_provider_stream"),
            message = OPENROUTER_MALFORMED_STREAM_MESSAGE,
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

internal const val OPENROUTER_CREDENTIAL_MESSAGE: String =
    "OpenRouter authentication requires a non-blank host-supplied credential."
internal const val OPENROUTER_MALFORMED_RESPONSE_MESSAGE: String =
    "The OpenRouter response was malformed or unsupported."
internal const val OPENROUTER_TARGET_MESSAGE: String =
    "The OpenRouter adapter accepts only the canonical openrouter provider."
internal const val OPENROUTER_RESPONSE_FORMAT_MESSAGE: String =
    "The OpenRouter adapter supports only plain-text and governed JSON-schema responses."
internal const val OPENROUTER_STRUCTURED_SCHEMA_MESSAGE: String =
    "The requested schema cannot be represented faithfully by the OpenRouter adapter."
internal const val OPENROUTER_INVALID_STRUCTURED_RESPONSE_MESSAGE: String =
    "The OpenRouter structured response did not satisfy the requested schema."
internal const val OPENROUTER_EXTENSIONS_MESSAGE: String =
    "The active OpenRouter adapter package does not support request extensions."
internal const val OPENROUTER_INPUT_ROLE_MESSAGE: String =
    "The OpenRouter adapter supports only system, user, and assistant text input roles."
internal const val OPENROUTER_MALFORMED_STREAM_MESSAGE: String =
    "The OpenRouter response stream was malformed or unsupported."
internal const val OPENROUTER_INVALID_REQUEST_MESSAGE: String =
    "OpenRouter rejected the request."
internal const val OPENROUTER_AUTHENTICATION_MESSAGE: String =
    "OpenRouter rejected the request authentication."
internal const val OPENROUTER_PERMISSION_MESSAGE: String =
    "OpenRouter denied access to the requested resource."
internal const val OPENROUTER_PAYMENT_REQUIRED_MESSAGE: String =
    "OpenRouter denied the request because the account has insufficient credit."
internal const val OPENROUTER_NOT_FOUND_MESSAGE: String =
    "OpenRouter could not find the requested resource."
internal const val OPENROUTER_RATE_LIMIT_MESSAGE: String =
    "OpenRouter rate-limited or quota-limited the request."
internal const val OPENROUTER_PROVIDER_FAILURE_MESSAGE: String =
    "OpenRouter could not complete the request."
internal const val OPENROUTER_FILTERED_RESPONSE_MESSAGE: String =
    "OpenRouter filtered or refused the response."
internal const val OPENROUTER_SERVER_ERROR_MESSAGE: String =
    "OpenRouter encountered a server error."
internal const val OPENROUTER_TIMEOUT_MESSAGE: String =
    "OpenRouter timed out while processing the request."
internal const val OPENROUTER_UNAVAILABLE_MESSAGE: String =
    "OpenRouter is temporarily unavailable."

private const val CHAT_COMPLETIONS_ENDPOINT: String = "chat/completions"
private const val JSON_CONTENT_TYPE: String = "application/json"
private const val EVENT_STREAM_CONTENT_TYPE: String = "text/event-stream"
private const val MAX_CREDENTIAL_CHARACTERS: Int = 8_192
private const val MAX_RESPONSE_BODY_BYTES: Int = 8 * 1_024 * 1_024
private const val INITIAL_RESPONSE_BODY_CAPACITY: Int = 8 * 1_024
private const val MAX_RESPONSE_BODY_CHUNKS: Int = 4 * 1_024
private const val SUCCESS_STATUS_CODE: Int = 200
private const val OPENROUTER_STRUCTURED_OUTPUT_NAME: String = "universal_ai_response"

private val PROVIDER_FAILURE_MAPPING =
    ProviderErrorMapping(
        category = UniversalAiErrorCategory.Provider,
        code = "provider_request_failed",
        message = OPENROUTER_PROVIDER_FAILURE_MESSAGE,
    )

internal val OPENROUTER_WIRE_JSON =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
