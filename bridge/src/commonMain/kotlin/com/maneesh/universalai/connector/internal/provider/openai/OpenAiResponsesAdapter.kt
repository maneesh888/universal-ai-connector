package com.maneesh.universalai.connector.internal.provider.openai

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
import com.maneesh.universalai.connector.internal.provider.OPENAI_PROVIDER_ID
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

internal class OpenAiResponsesAdapter(
    private val configuration: UniversalAiProviderConfiguration,
    private val transport: ConnectorTransport,
) : ConnectorEngine {
    override suspend fun respond(request: UniversalAiRequest): UniversalAiResponse {
        validateRequest(request)
        val credential = resolveCredential()
        val body =
            WIRE_JSON
                .encodeToString(request.toOpenAiWire())
                .encodeToByteArray()
        val transportRequest =
            ConnectorTransportRequest(
                method = "POST",
                baseUrl = configuration.validatedBaseUrl,
                endpoint = RESPONSES_ENDPOINT,
                adapterHeaders =
                    listOf(
                        ConnectorTransportHeader(
                            name = "authorization",
                            value = "Bearer $credential",
                        ),
                        ConnectorTransportHeader(
                            name = "content-type",
                            value = "application/json",
                        ),
                        ConnectorTransportHeader(
                            name = "accept",
                            value = "application/json",
                        ),
                    ),
                body = body,
            )

        return transport.execute(transportRequest) { response ->
            if (response.statusCode !in 200..299) {
                throw providerFailure(response)
            }
            translateSuccessfulResponse(
                request = request,
                response = response,
            )
        }
    }

    override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> =
        flow {
            throw unsupportedRequest(OPENAI_STREAMING_UNAVAILABLE_MESSAGE)
        }

    private fun validateRequest(request: UniversalAiRequest) {
        if (request.target.providerId != OPENAI_PROVIDER_ID) {
            throw unsupportedRequest(OPENAI_TARGET_MESSAGE)
        }
        when (request.responseFormat.kind) {
            UniversalAiResponseFormatKind.PlainText -> Unit
            UniversalAiResponseFormatKind.JsonSchema -> {
                val schema =
                    request.responseFormat.schema
                        ?: throw unsupportedRequest(OPENAI_RESPONSE_FORMAT_MESSAGE)
                if (!OpenAiStructuredOutput.isSupported(schema)) {
                    throw unsupportedRequest(OPENAI_STRUCTURED_SCHEMA_MESSAGE)
                }
            }

            else -> throw unsupportedRequest(OPENAI_RESPONSE_FORMAT_MESSAGE)
        }
        if (request.generation.stopSequences.isNotEmpty()) {
            throw unsupportedRequest(OPENAI_STOP_SEQUENCES_MESSAGE)
        }
        if (!request.extensions.isEmpty) {
            throw unsupportedRequest(OPENAI_EXTENSIONS_MESSAGE)
        }
        if (request.input.any { input -> !input.role.isKnown }) {
            throw unsupportedRequest(OPENAI_INPUT_ROLE_MESSAGE)
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
                WIRE_JSON.decodeFromString<OpenAiResponseWire>(
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

private fun UniversalAiRequest.toOpenAiWire(): OpenAiCreateResponseWire =
    OpenAiCreateResponseWire(
        model = target.modelId.rawValue,
        input =
            input.map { item ->
                OpenAiInputMessageWire(
                    role = item.role.rawValue,
                    content = item.content,
                )
            },
        store = false,
        text =
            responseFormat.schema?.let { schema ->
                OpenAiTextConfigurationWire(
                    format =
                        OpenAiTextFormatWire(
                            type = "json_schema",
                            name = OPENAI_STRUCTURED_OUTPUT_NAME,
                            schema = schema.elementForSerialization(),
                            strict = true,
                        ),
                )
            },
        maxOutputTokens = generation.maxOutputTokens,
        temperature = generation.temperature,
        topP = generation.topP,
    )

private fun OpenAiResponseWire.toCanonical(
    request: UniversalAiRequest,
    metadata: ConnectorResponseMetadata,
): UniversalAiResponse {
    requireWire(objectType == "response")
    when (status) {
        "failed" -> {
            requireWire(incompleteDetails == null)
            throw providerResponseFailure(
                error = requireWireValue(error),
                metadata = metadata,
            )
        }

        "incomplete" -> {
            requireWire(error == null)
            throw incompleteResponseFailure(
                details = requireWireValue(incompleteDetails),
                metadata = metadata,
            )
        }

        "completed" -> {
            requireWire(error == null)
            requireWire(incompleteDetails == null)
        }

        else -> throw malformedResponse()
    }

    val responseId = ResponseId.of(requireWireValue(id))
    val responseModel = ModelId.of(requireWireValue(model))
    val providerOutput = requireWireValue(output)
    val canonicalOutputs = mutableListOf<UniversalAiOutput>()
    providerOutput.forEach { item ->
        when (item.type) {
            "reasoning" -> Unit
            "message" -> {
                requireWire(item.status == "completed")
                requireWire(item.role == "assistant")
                val content = requireWireValue(item.content)
                requireWire(content.isNotEmpty())
                val text =
                    buildString {
                        content.forEach { part ->
                            when (part.type) {
                                "output_text" -> append(requireWireValue(part.text))
                                "refusal" -> throw refusalFailure(metadata)
                                else -> throw malformedResponse()
                            }
                        }
                    }
                requireWire(text.isNotEmpty())
                val outputId = OutputId.of(requireWireValue(item.id))
                val outputIndex = canonicalOutputs.size
                canonicalOutputs +=
                    request.responseFormat.schema?.let { schema ->
                        val value =
                            OpenAiStructuredOutput.parseAndValidate(
                                json = text,
                                schema = schema,
                            ) ?: throw malformedStructuredResponse()
                        UniversalAiOutput.structuredJson(
                            id = outputId,
                            index = outputIndex,
                            value = value,
                        )
                    } ?: UniversalAiOutput.text(
                        id = outputId,
                        index = outputIndex,
                        text = text,
                    )
            }

            else -> throw malformedResponse()
        }
    }
    requireWire(canonicalOutputs.isNotEmpty())

    return UniversalAiResponse(
        id = responseId,
        requestId = metadata.requestId.toCanonicalRequestIdOrNull(),
        target =
            UniversalAiTarget(
                providerId = OPENAI_PROVIDER_ID,
                modelId = responseModel,
            ),
        outputs = canonicalOutputs,
        usage = requireWireValue(usage).toCanonical(),
        completionReason = UniversalAiCompletionReason.Stop,
    )
}

private fun OpenAiUsageWire.toCanonical(): UniversalAiUsage {
    val input = requireWireValue(inputTokens)
    val output = requireWireValue(outputTokens)
    val total = requireWireValue(totalTokens)
    requireWire(input >= 0L && output >= 0L && total >= 0L)

    val canonicalInputDetails =
        buildMap {
            inputDetails?.cachedTokens?.let { value ->
                requireWire(value >= 0L)
                put("cached_tokens", value)
            }
            inputDetails?.cacheWriteTokens?.let { value ->
                requireWire(value >= 0L)
                put("cache_write_tokens", value)
            }
        }
    val canonicalOutputDetails =
        buildMap {
            outputDetails?.reasoningTokens?.let { value ->
                requireWire(value >= 0L)
                put("reasoning_tokens", value)
            }
        }
    return UniversalAiUsage(
        inputTokens = input,
        outputTokens = output,
        totalTokens = total,
        inputDetails = canonicalInputDetails,
        outputDetails = canonicalOutputDetails,
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
        if (chunkCount > MAX_RESPONSE_BODY_CHUNKS) {
            throw malformedResponse()
        }
        if (chunk.size > MAX_RESPONSE_BODY_BYTES - size) {
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
    val providerError = readErrorEnvelopeOrNull(response.body)
    val mapping =
        statusErrorMapping(response.statusCode)
            ?: providerError?.let(::providerErrorMapping)
            ?: PROVIDER_FAILURE_MAPPING
    return mapping.toException(
        metadata = response.metadata.safeErrorMetadata(response.statusCode),
    )
}

private fun providerResponseFailure(
    error: OpenAiErrorWire,
    metadata: ConnectorResponseMetadata,
): UniversalAiException =
    providerErrorMapping(error)
        .toException(metadata.safeErrorMetadata(SUCCESS_STATUS_CODE))

private fun incompleteResponseFailure(
    details: OpenAiIncompleteDetailsWire,
    metadata: ConnectorResponseMetadata,
): UniversalAiException {
    val mapping =
        when (details.reason) {
            "max_output_tokens" ->
                ProviderErrorMapping(
                    category = UniversalAiErrorCategory.Provider,
                    code = "provider_output_limit_reached",
                    message = OPENAI_OUTPUT_LIMIT_MESSAGE,
                )

            "content_filter" ->
                ProviderErrorMapping(
                    category = UniversalAiErrorCategory.Provider,
                    code = "provider_response_filtered",
                    message = OPENAI_FILTERED_RESPONSE_MESSAGE,
                )

            else ->
                ProviderErrorMapping(
                    category = UniversalAiErrorCategory.Provider,
                    code = "provider_incomplete_response",
                    message = OPENAI_INCOMPLETE_RESPONSE_MESSAGE,
                )
        }
    return mapping.toException(metadata.safeErrorMetadata(SUCCESS_STATUS_CODE))
}

private fun refusalFailure(
    metadata: ConnectorResponseMetadata,
): UniversalAiException =
    ProviderErrorMapping(
        category = UniversalAiErrorCategory.Provider,
        code = "provider_refused_response",
        message = OPENAI_REFUSAL_MESSAGE,
    ).toException(metadata.safeErrorMetadata(SUCCESS_STATUS_CODE))

private fun statusErrorMapping(statusCode: Int): ProviderErrorMapping? =
    when (statusCode) {
        400, 409, 422 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Validation,
                code = "provider_invalid_request",
                message = OPENAI_INVALID_REQUEST_MESSAGE,
            )

        401 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Authentication,
                code = "provider_authentication_failed",
                message = OPENAI_AUTHENTICATION_MESSAGE,
            )

        403 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Authorization,
                code = "provider_permission_denied",
                message = OPENAI_PERMISSION_MESSAGE,
            )

        404 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.NotFound,
                code = "provider_resource_not_found",
                message = OPENAI_NOT_FOUND_MESSAGE,
            )

        408 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Provider,
                code = "provider_request_timeout",
                message = OPENAI_TIMEOUT_MESSAGE,
            )

        429 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.RateLimit,
                code = "provider_rate_limited",
                message = OPENAI_RATE_LIMIT_MESSAGE,
            )

        500 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Provider,
                code = "provider_server_error",
                message = OPENAI_SERVER_ERROR_MESSAGE,
            )

        502, 503, 504 ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Provider,
                code = "provider_unavailable",
                message = OPENAI_UNAVAILABLE_MESSAGE,
            )

        else -> null
    }

private fun providerErrorMapping(error: OpenAiErrorWire): ProviderErrorMapping {
    val discriminators = setOfNotNull(error.code, error.type)
    return when {
        discriminators.any { value ->
            value in
                setOf(
                    "invalid_request_error",
                    "invalid_prompt",
                    "context_length_exceeded",
                )
        } ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Validation,
                code = "provider_invalid_request",
                message = OPENAI_INVALID_REQUEST_MESSAGE,
            )

        "invalid_api_key" in discriminators ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Authentication,
                code = "provider_authentication_failed",
                message = OPENAI_AUTHENTICATION_MESSAGE,
            )

        "insufficient_permissions" in discriminators ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Authorization,
                code = "provider_permission_denied",
                message = OPENAI_PERMISSION_MESSAGE,
            )

        "not_found" in discriminators ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.NotFound,
                code = "provider_resource_not_found",
                message = OPENAI_NOT_FOUND_MESSAGE,
            )

        discriminators.any { value ->
            value == "rate_limit_exceeded" || value == "insufficient_quota"
        } ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.RateLimit,
                code = "provider_rate_limited",
                message = OPENAI_RATE_LIMIT_MESSAGE,
            )

        discriminators.any { value ->
            value == "request_timeout" || value == "timeout"
        } ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Provider,
                code = "provider_request_timeout",
                message = OPENAI_TIMEOUT_MESSAGE,
            )

        discriminators.any { value ->
            value == "server_error" || value == "internal_server_error"
        } ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Provider,
                code = "provider_server_error",
                message = OPENAI_SERVER_ERROR_MESSAGE,
            )

        discriminators.any { value ->
            value == "overloaded_error" || value == "service_unavailable"
        } ->
            ProviderErrorMapping(
                category = UniversalAiErrorCategory.Provider,
                code = "provider_unavailable",
                message = OPENAI_UNAVAILABLE_MESSAGE,
            )

        else -> PROVIDER_FAILURE_MAPPING
    }
}

private suspend fun readErrorEnvelopeOrNull(
    reader: ConnectorTransportChunkReader,
): OpenAiErrorWire? {
    val bytes = readOptionalBoundedBody(reader) ?: return null
    if (bytes.isEmpty()) {
        return null
    }
    return try {
        WIRE_JSON
            .decodeFromString<OpenAiErrorEnvelopeWire>(
                bytes.decodeToString(throwOnInvalidSequence = true),
            )
            .error
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
                    expandedSize =
                        minOf(
                            MAX_ERROR_BODY_BYTES,
                            expandedSize * 2,
                        )
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
            message = OPENAI_CREDENTIAL_MESSAGE,
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
            message = OPENAI_MALFORMED_RESPONSE_MESSAGE,
        ),
    )

private fun malformedStructuredResponse(): UniversalAiException =
    UniversalAiException(
        UniversalAiError(
            category = UniversalAiErrorCategory.Protocol,
            code = UniversalAiErrorCode.of("invalid_structured_provider_response"),
            message = OPENAI_INVALID_STRUCTURED_RESPONSE_MESSAGE,
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

internal const val OPENAI_CREDENTIAL_MESSAGE: String =
    "OpenAI authentication requires a non-blank host-supplied credential."
internal const val OPENAI_MALFORMED_RESPONSE_MESSAGE: String =
    "The OpenAI response was malformed or unsupported."
internal const val OPENAI_TARGET_MESSAGE: String =
    "The OpenAI adapter accepts only the canonical openai provider."
internal const val OPENAI_RESPONSE_FORMAT_MESSAGE: String =
    "The OpenAI adapter does not support the requested response format."
internal const val OPENAI_STRUCTURED_SCHEMA_MESSAGE: String =
    "The governed schema is not supported by OpenAI strict structured output."
internal const val OPENAI_STOP_SEQUENCES_MESSAGE: String =
    "The OpenAI Responses adapter does not support stop sequences."
internal const val OPENAI_EXTENSIONS_MESSAGE: String =
    "The active OpenAI adapter package does not support request extensions."
internal const val OPENAI_INPUT_ROLE_MESSAGE: String =
    "The OpenAI adapter does not support the requested input role."
internal const val OPENAI_STREAMING_UNAVAILABLE_MESSAGE: String =
    "OpenAI streaming is not available in the active adapter package."
internal const val OPENAI_INVALID_REQUEST_MESSAGE: String =
    "OpenAI rejected the request."
internal const val OPENAI_AUTHENTICATION_MESSAGE: String =
    "OpenAI rejected the request authentication."
internal const val OPENAI_PERMISSION_MESSAGE: String =
    "OpenAI denied access to the requested resource."
internal const val OPENAI_NOT_FOUND_MESSAGE: String =
    "OpenAI could not find the requested resource."
internal const val OPENAI_RATE_LIMIT_MESSAGE: String =
    "OpenAI rate-limited or quota-limited the request."
internal const val OPENAI_PROVIDER_FAILURE_MESSAGE: String =
    "OpenAI could not complete the request."
internal const val OPENAI_TIMEOUT_MESSAGE: String =
    "OpenAI timed out while processing the request."
internal const val OPENAI_SERVER_ERROR_MESSAGE: String =
    "OpenAI encountered a server error."
internal const val OPENAI_UNAVAILABLE_MESSAGE: String =
    "OpenAI is temporarily unavailable."
internal const val OPENAI_OUTPUT_LIMIT_MESSAGE: String =
    "OpenAI stopped before completing the response because the output limit was reached."
internal const val OPENAI_FILTERED_RESPONSE_MESSAGE: String =
    "OpenAI did not complete the response because it was filtered."
internal const val OPENAI_INCOMPLETE_RESPONSE_MESSAGE: String =
    "OpenAI returned an incomplete response."
internal const val OPENAI_REFUSAL_MESSAGE: String =
    "OpenAI refused to produce the requested response."
internal const val OPENAI_INVALID_STRUCTURED_RESPONSE_MESSAGE: String =
    "The OpenAI structured response did not match the requested governed schema."

private const val RESPONSES_ENDPOINT: String = "responses"
private const val OPENAI_STRUCTURED_OUTPUT_NAME: String = "universal_ai_response"
private const val MAX_CREDENTIAL_CHARACTERS: Int = 8_192
private const val MAX_RESPONSE_BODY_BYTES: Int = 8 * 1_024 * 1_024
private const val INITIAL_RESPONSE_BODY_CAPACITY: Int = 8 * 1_024
private const val MAX_RESPONSE_BODY_CHUNKS: Int = 4 * 1_024
private const val MAX_ERROR_BODY_BYTES: Int = 256 * 1_024
private const val INITIAL_ERROR_BODY_CAPACITY: Int = 4 * 1_024
private const val MAX_ERROR_BODY_CHUNKS: Int = 1_024
private const val SUCCESS_STATUS_CODE: Int = 200

private val PROVIDER_FAILURE_MAPPING =
    ProviderErrorMapping(
        category = UniversalAiErrorCategory.Provider,
        code = "provider_request_failed",
        message = OPENAI_PROVIDER_FAILURE_MESSAGE,
    )

private val WIRE_JSON =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
