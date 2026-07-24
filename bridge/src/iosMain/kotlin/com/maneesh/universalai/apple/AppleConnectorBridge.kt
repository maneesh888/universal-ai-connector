@file:OptIn(
    kotlin.experimental.ExperimentalObjCRefinement::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package com.maneesh.universalai.apple

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.contract.ContractSemanticException
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.StructuredOutputSchema
import com.maneesh.universalai.connector.contract.UniversalAiError
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiGenerationParameters
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiOutput
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormat
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormatKind
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.contract.UniversalAiUsage
import com.maneesh.universalai.connector.contract.contractSemanticExceptionOrNull
import com.maneesh.universalai.connector.contract.contractRequire
import com.maneesh.universalai.connector.contract.extension.ExtensionNamespace
import com.maneesh.universalai.connector.contract.extension.ExtensionNumber
import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.contract.extension.Extensions
import com.maneesh.universalai.connector.contract.json.CURRENT_CONTRACT_VERSION
import com.maneesh.universalai.connector.contract.json.JsonNumberSemantics
import com.maneesh.universalai.connector.contract.toUniversalAiException
import com.maneesh.universalai.connector.contract.withPathPrefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlin.native.HiddenFromObjC

/**
 * Product-named callback adapter used privately by the supported Swift Package façade.
 *
 * The default adapter owns no long-lived coroutine scope or resource. Every call launches an
 * independent operation on [Dispatchers.Default], and its returned handle cancels only that call.
 * Cancellation intentionally delivers no success, completion, or error callback.
 */
class AppleConnectorBridge internal constructor(
    private val connector: UniversalAiConnector,
    private val injectedScope: CoroutineScope?,
) {
    private val instrumentation = AppleBridgeInstrumentation()

    constructor() : this(
        connector = UniversalAiConnector(),
        injectedScope = null,
    )

    internal constructor(scope: CoroutineScope) : this(
        connector = UniversalAiConnector(),
        injectedScope = scope,
    )

    fun version(): String = connector.version

    fun respond(
        request: AppleBridgeRequest,
        onSuccess: (AppleBridgeResponse) -> Unit,
        onError: (AppleBridgeError) -> Unit,
    ): AppleCancellationHandle {
        val canonicalRequest =
            try {
                request.toCanonicalRequest()
            } catch (failure: Throwable) {
                onError(failure.toAppleBridgeError())
                return completedCancellationHandle()
            }
        val job = launchOperation {
            val result =
                try {
                    ResponseResult.Success(
                        connector
                            .respond(canonicalRequest)
                            .toAppleBridgeResponse(),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    ResponseResult.Failure(failure.toAppleBridgeError())
                }

            ensureActive()
            when (result) {
                is ResponseResult.Success -> onSuccess(result.value)
                is ResponseResult.Failure -> onError(result.error)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                instrumentation.recordResponseCancellation()
            }
        }
        return AppleCancellationHandle(job)
    }

    fun stream(
        request: AppleBridgeRequest,
        onEvent: (AppleBridgeStreamEvent) -> Unit,
        onComplete: () -> Unit,
        onError: (AppleBridgeError) -> Unit,
    ): AppleCancellationHandle {
        val canonicalRequest =
            try {
                request.toCanonicalRequest()
            } catch (failure: Throwable) {
                onError(failure.toAppleBridgeError())
                return completedCancellationHandle()
            }
        val job = launchOperation {
            var terminalAccepted = false
            val failure: Throwable? =
                try {
                    connector
                        .stream(canonicalRequest)
                        .onEach { event ->
                            if (!terminalAccepted) {
                                ensureActive()
                                onEvent(event.toAppleBridgeStreamEvent())
                                terminalAccepted = event.terminal
                            }
                        }
                        .takeWhile { !terminalAccepted }
                        .collect()
                    null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    failure.takeUnless { terminalAccepted }
                }

            ensureActive()
            if (failure == null) {
                onComplete()
            } else {
                onError(failure.toAppleBridgeError())
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                instrumentation.recordStreamCancellation()
            }
        }
        return AppleCancellationHandle(job)
    }

    private fun completedCancellationHandle(): AppleCancellationHandle {
        val job = Job()
        job.complete()
        return AppleCancellationHandle(job)
    }

    /** Resets test-only cancellation evidence used by Swift integration tests. */
    fun resetInstrumentation() {
        instrumentation.reset()
    }

    /** Returns test-only cancellation evidence used by Swift integration tests. */
    fun instrumentationSnapshot(): AppleBridgeInstrumentationSnapshot =
        instrumentation.snapshot()

    private fun launchOperation(block: suspend CoroutineScope.() -> Unit): Job =
        (injectedScope ?: CoroutineScope(Dispatchers.Default)).launch(block = block)
}

private sealed interface ResponseResult {
    class Success(
        val value: AppleBridgeResponse,
    ) : ResponseResult

    class Failure(
        val error: AppleBridgeError,
    ) : ResponseResult
}

@HiddenFromObjC
internal fun AppleBridgeRequest.toCanonicalRequest(): UniversalAiRequest {
    contractRequire(
        condition = contractVersion == CURRENT_CONTRACT_VERSION,
        code = "unsupported_contract_version",
        path = "/contractVersion",
    ) {
        "Unsupported contractVersion '$contractVersion'."
    }
    val canonicalFormatKind =
        withRequestPath("/responseFormat") {
            UniversalAiResponseFormatKind.of(responseFormat.kind)
        }
    val canonicalFormat =
        when (canonicalFormatKind) {
            UniversalAiResponseFormatKind.PlainText -> {
                contractRequire(
                    condition = responseFormat.schema == null,
                    code = "unexpected_response_schema",
                    path = "/responseFormat/schema",
                ) {
                    "Plain-text responseFormat must not contain a schema."
                }
                UniversalAiResponseFormat.PlainText
            }

            UniversalAiResponseFormatKind.JsonSchema -> {
                val schema =
                    responseFormat.schema
                        ?: throw ContractSemanticException(
                            code = "missing_response_schema",
                            path = "/responseFormat/schema",
                            message = "Structured responseFormat requires a schema.",
                        )
                UniversalAiResponseFormat.jsonSchema(
                    withRequestPath("/responseFormat/schema") {
                        StructuredOutputSchema.fromElement(
                            schema.toJsonElement(),
                        )
                    },
                )
            }

            else -> {
                contractRequire(
                    condition = responseFormat.schema == null,
                    code = "unexpected_response_schema",
                    path = "/responseFormat/schema",
                ) {
                    "Future responseFormat kinds must place extra data in extensions."
                }
                UniversalAiResponseFormat.future(canonicalFormatKind)
            }
        }

    return UniversalAiRequest(
        target =
            withRequestPath("/target") {
                UniversalAiTarget(
                    providerId = ProviderId.of(target.providerRawValue),
                    modelId = ModelId.of(target.modelRawValue),
                )
            },
        input =
            input.mapIndexed { index, item ->
                withRequestPath("/input/$index") {
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.of(item.role),
                        content = item.content,
                    )
                }
            },
        responseFormat = canonicalFormat,
        generation =
            withRequestPath("/generation") {
                UniversalAiGenerationParameters(
                    maxOutputTokens =
                        generation.maxOutputTokens.takeIf {
                            generation.hasMaxOutputTokens
                        }?.let { value ->
                            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                                "maxOutputTokens is outside the supported integer range."
                            }
                            value.toInt()
                        },
                    temperature =
                        generation.temperature.takeIf {
                            generation.hasTemperature
                        },
                    topP =
                        generation.topP.takeIf {
                            generation.hasTopP
                        },
                    stopSequences = generation.stopSequences,
                )
            },
        extensions =
            withRequestPath("/extensions") {
                extensions.toCanonicalExtensions()
            },
    )
}

private inline fun <T> withRequestPath(
    pathPrefix: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (failure: ContractSemanticException) {
        throw failure.withPathPrefix(pathPrefix)
    }

@HiddenFromObjC
internal fun UniversalAiResponse.toAppleBridgeResponse(): AppleBridgeResponse =
    AppleBridgeResponse(
        contractVersion = contractVersion,
        id = id.rawValue,
        requestId = requestId?.rawValue,
        target = target.toAppleBridgeTarget(),
        outputs = outputs.map(UniversalAiOutput::toAppleBridgeOutput),
        usage = usage?.toAppleBridgeUsage(),
        completionReason = completionReason.rawValue,
        extensions = extensions.toAppleBridgeExtensions(),
    )

@HiddenFromObjC
internal fun UniversalAiStreamEvent.toAppleBridgeStreamEvent(): AppleBridgeStreamEvent =
    AppleBridgeStreamEvent(
        contractVersion = contractVersion,
        type = type.rawValue,
        terminal = terminal,
        sequence = sequence,
        responseId = responseId.rawValue,
        requestId = requestId?.rawValue,
        outputId = outputId?.rawValue,
        hasOutputIndex = outputIndex != null,
        outputIndex = outputIndex ?: 0,
        delta = delta,
        output = output?.toAppleBridgeOutput(),
        usage = usage?.toAppleBridgeUsage(),
        response = response?.toAppleBridgeResponse(),
        extensions = extensions.toAppleBridgeExtensions(),
    )

private fun UniversalAiTarget.toAppleBridgeTarget(): AppleBridgeTarget =
    AppleBridgeTarget(
        providerRawValue = providerId.rawValue,
        modelRawValue = modelId.rawValue,
    )

private fun UniversalAiOutput.toAppleBridgeOutput(): AppleBridgeOutput =
    AppleBridgeOutput(
        id = id.rawValue,
        index = index,
        kind = kind.rawValue,
        text = text,
        structuredJson = structuredJson?.elementForSerialization()?.toAppleBridgeJsonValue(),
        extensions = extensions.toAppleBridgeExtensions(),
    )

private fun UniversalAiUsage.toAppleBridgeUsage(): AppleBridgeUsage =
    AppleBridgeUsage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        inputDetails =
            inputDetails.entries
                .sortedBy { entry -> entry.key }
                .map { entry -> AppleBridgeLongEntry(entry.key, entry.value) },
        outputDetails =
            outputDetails.entries
                .sortedBy { entry -> entry.key }
                .map { entry -> AppleBridgeLongEntry(entry.key, entry.value) },
        extensions = extensions.toAppleBridgeExtensions(),
    )

private fun AppleBridgeExtensions.toCanonicalExtensions(): Extensions {
    require(entries.map { entry -> entry.namespace }.distinct().size == entries.size) {
        "Extension bags must not contain duplicate namespaces."
    }
    return Extensions.of(
        entries.associate { entry ->
            ExtensionNamespace.of(entry.namespace) to entry.payload.toExtensionObjectValue()
        },
    )
}

private fun Extensions.toAppleBridgeExtensions(): AppleBridgeExtensions =
    AppleBridgeExtensions(
        entries.entries
            .sortedBy { entry -> entry.key.rawValue }
            .map { entry ->
                AppleBridgeExtensionEntry(
                    namespace = entry.key.rawValue,
                    payload = entry.value.toAppleBridgeJsonObject(),
                )
            },
    )

private fun AppleBridgeJsonObject.toExtensionObjectValue(): ExtensionValue.ObjectValue {
    require(entries.map { entry -> entry.name }.distinct().size == entries.size) {
        "Extension objects must not contain duplicate member names."
    }
    return ExtensionValue.objectValue(
        entries.associate { entry ->
            entry.name to entry.value.toExtensionValue()
        },
    )
}

private fun AppleBridgeJsonValue.toExtensionValue(): ExtensionValue =
    when (this) {
        is AppleBridgeJsonNull -> ExtensionValue.Null
        is AppleBridgeJsonBoolean -> ExtensionValue.boolean(value)
        is AppleBridgeJsonString -> ExtensionValue.string(value)
        is AppleBridgeJsonNumber -> ExtensionValue.number(ExtensionNumber.of(rawValue))
        is AppleBridgeJsonArray -> ExtensionValue.array(values.map { value -> value.toExtensionValue() })
        is AppleBridgeJsonObject -> toExtensionObjectValue()
    }

private fun ExtensionValue.ObjectValue.toAppleBridgeJsonObject(): AppleBridgeJsonObject =
    AppleBridgeJsonObject(
        members.entries
            .sortedBy { entry -> entry.key }
            .map { entry ->
                AppleBridgeJsonObjectEntry(
                    name = entry.key,
                    value = entry.value.toAppleBridgeJsonValue(),
                )
            },
    )

private fun ExtensionValue.toAppleBridgeJsonValue(): AppleBridgeJsonValue =
    when (this) {
        ExtensionValue.Null -> AppleBridgeJsonNull()
        is ExtensionValue.BooleanValue -> AppleBridgeJsonBoolean(value)
        is ExtensionValue.StringValue -> AppleBridgeJsonString(value)
        is ExtensionValue.NumberValue -> AppleBridgeJsonNumber(value.rawValue)
        is ExtensionValue.ArrayValue ->
            AppleBridgeJsonArray(values.map { value -> value.toAppleBridgeJsonValue() })
        is ExtensionValue.ObjectValue -> toAppleBridgeJsonObject()
    }

private fun AppleBridgeJsonValue.toJsonElement(): JsonElement =
    when (this) {
        is AppleBridgeJsonNull -> JsonNull
        is AppleBridgeJsonBoolean -> JsonPrimitive(value)
        is AppleBridgeJsonString -> JsonPrimitive(value)
        is AppleBridgeJsonNumber ->
            JsonUnquotedLiteral(
                rawValue.also { value ->
                    contractRequire(
                        condition = JsonNumberSemantics.isNumber(value),
                        code = "invalid_json_number",
                        path = "",
                    ) {
                        "Numbers must use the JSON number grammar."
                    }
                },
            )

        is AppleBridgeJsonArray -> JsonArray(values.map { value -> value.toJsonElement() })
        is AppleBridgeJsonObject -> {
            require(entries.map { entry -> entry.name }.distinct().size == entries.size) {
                "JSON objects must not contain duplicate member names."
            }
            JsonObject(
                entries.associate { entry ->
                    entry.name to entry.value.toJsonElement()
                },
            )
        }
    }

private fun JsonElement.toAppleBridgeJsonValue(): AppleBridgeJsonValue =
    when (this) {
        JsonNull -> AppleBridgeJsonNull()
        is JsonArray -> AppleBridgeJsonArray(map { value -> value.toAppleBridgeJsonValue() })
        is JsonObject ->
            AppleBridgeJsonObject(
                entries.map { entry ->
                    AppleBridgeJsonObjectEntry(
                        name = entry.key,
                        value = entry.value.toAppleBridgeJsonValue(),
                    )
                },
            )

        is JsonPrimitive ->
            when {
                isString -> AppleBridgeJsonString(content)
                booleanOrNull != null -> AppleBridgeJsonBoolean(booleanOrNull!!)
                else -> AppleBridgeJsonNumber(content)
            }
    }

private fun Throwable.toAppleBridgeError(): AppleBridgeError {
    val canonicalError =
        when (this) {
            is UniversalAiException -> error
            else -> {
                val semanticFailure = contractSemanticExceptionOrNull()
                if (semanticFailure != null || this is IllegalArgumentException) {
                    semanticFailure.toRequestValidationError()
                } else {
                    toUniversalAiException().error
                }
            }
        }
    return canonicalError.toAppleBridgeError()
}

private fun ContractSemanticException?.toRequestValidationError(): UniversalAiError =
    UniversalAiError(
        category = UniversalAiErrorCategory.Validation,
        code = UniversalAiErrorCode.InvalidRequest,
        message = REQUEST_VALIDATION_MESSAGE,
        metadata =
            this?.let { failure ->
                ExtensionValue.objectValue(
                    "path" to ExtensionValue.string(failure.path),
                    "validationCode" to ExtensionValue.string(failure.code),
                )
            },
    )

private fun UniversalAiError.toAppleBridgeError(): AppleBridgeError =
    AppleBridgeError(
        category = category.rawValue,
        code = code.rawValue,
        message = message,
        metadata = metadata?.toAppleBridgeJsonObject(),
        extensions = extensions.toAppleBridgeExtensions(),
    )

private const val REQUEST_VALIDATION_MESSAGE: String = "Request validation failed."
