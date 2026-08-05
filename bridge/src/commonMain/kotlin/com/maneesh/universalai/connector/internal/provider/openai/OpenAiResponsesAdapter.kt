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
        if (request.responseFormat != UniversalAiResponseFormat.PlainText) {
            throw unsupportedRequest(OPENAI_RESPONSE_FORMAT_MESSAGE)
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
                WIRE_JSON.decodeFromString<OpenAiResponseWire>(bytes.decodeToString())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SerializationException) {
                throw malformedResponse()
            } catch (_: IllegalArgumentException) {
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
        maxOutputTokens = generation.maxOutputTokens,
        temperature = generation.temperature,
        topP = generation.topP,
    )

private fun OpenAiResponseWire.toCanonical(
    request: UniversalAiRequest,
    metadata: ConnectorResponseMetadata,
): UniversalAiResponse {
    requireWire(objectType == null || objectType == "response")
    requireWire(status == "completed")
    requireWire(error == null)
    requireWire(incompleteDetails == null)

    val responseId = ResponseId.of(requireWireValue(id))
    val responseModel = ModelId.of(requireWireValue(model))
    val providerOutput = requireWireValue(output)
    val canonicalOutputs = mutableListOf<UniversalAiOutput>()
    providerOutput.forEach { item ->
        when (item.type) {
            "reasoning" -> Unit
            "message" -> {
                requireWire(item.status == null || item.status == "completed")
                requireWire(item.role == "assistant")
                val content = requireWireValue(item.content)
                requireWire(content.isNotEmpty())
                val text =
                    buildString {
                        content.forEach { part ->
                            requireWire(part.type == "output_text")
                            append(requireWireValue(part.text))
                        }
                    }
                requireWire(text.isNotEmpty())
                canonicalOutputs +=
                    UniversalAiOutput.text(
                        id = OutputId.of(requireWireValue(item.id)),
                        index = canonicalOutputs.size,
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
    val chunks = mutableListOf<ByteArray>()
    var size = 0
    while (true) {
        val chunk = reader.readChunk() ?: break
        if (chunk.size > MAX_RESPONSE_BODY_BYTES - size) {
            throw malformedResponse()
        }
        chunks += chunk
        size += chunk.size
    }
    requireWire(size > 0)
    val result = ByteArray(size)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(result, destinationOffset = offset)
        offset += chunk.size
    }
    return result
}

private fun providerFailure(response: ConnectorTransportResponse): UniversalAiException {
    val (category, code, message) =
        when (response.statusCode) {
            400, 409, 422 ->
                Triple(
                    UniversalAiErrorCategory.Validation,
                    "provider_invalid_request",
                    OPENAI_INVALID_REQUEST_MESSAGE,
                )

            401 ->
                Triple(
                    UniversalAiErrorCategory.Authentication,
                    "provider_authentication_failed",
                    OPENAI_AUTHENTICATION_MESSAGE,
                )

            403 ->
                Triple(
                    UniversalAiErrorCategory.Authorization,
                    "provider_permission_denied",
                    OPENAI_PERMISSION_MESSAGE,
                )

            404 ->
                Triple(
                    UniversalAiErrorCategory.NotFound,
                    "provider_resource_not_found",
                    OPENAI_NOT_FOUND_MESSAGE,
                )

            429 ->
                Triple(
                    UniversalAiErrorCategory.RateLimit,
                    "provider_rate_limited",
                    OPENAI_RATE_LIMIT_MESSAGE,
                )

            else ->
                Triple(
                    UniversalAiErrorCategory.Provider,
                    "provider_request_failed",
                    OPENAI_PROVIDER_FAILURE_MESSAGE,
                )
        }
    return UniversalAiException(
        UniversalAiError(
            category = category,
            code = UniversalAiErrorCode.of(code),
            message = message,
            metadata = response.safeErrorMetadata(),
        ),
    )
}

private fun ConnectorTransportResponse.safeErrorMetadata(): ExtensionValue.ObjectValue {
    val members = linkedMapOf<String, ExtensionValue>()
    members["statusCode"] = ExtensionValue.number(statusCode.toString())
    metadata.requestId?.let { value ->
        members["requestId"] = ExtensionValue.string(value)
    }
    metadata.retryAfterMillis?.let { value ->
        members["retryAfterMillis"] = ExtensionValue.number(value.toString())
    }
    return ExtensionValue.objectValue(members)
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
    "The active OpenAI adapter package supports only plain-text responses."
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

private const val RESPONSES_ENDPOINT: String = "responses"
private const val MAX_CREDENTIAL_CHARACTERS: Int = 8_192
private const val MAX_RESPONSE_BODY_BYTES: Int = 8 * 1_024 * 1_024

private val WIRE_JSON =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
