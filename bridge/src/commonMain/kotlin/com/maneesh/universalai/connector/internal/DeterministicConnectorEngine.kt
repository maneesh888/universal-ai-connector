package com.maneesh.universalai.connector.internal

import com.maneesh.universalai.connector.contract.OutputId
import com.maneesh.universalai.connector.contract.ResponseId
import com.maneesh.universalai.connector.contract.UniversalAiCompletionReason
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
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiStreamSequenceValidator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Internal behavior seam for deterministic lifecycle tests; it is not a transport API. */
internal interface ConnectorEngine {
    suspend fun respond(request: UniversalAiRequest): UniversalAiResponse

    fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent>
}

internal class DeterministicConnectorEngine : ConnectorEngine {
    override suspend fun respond(request: UniversalAiRequest): UniversalAiResponse {
        validateRequest(request)

        delay(RESPONSE_DELAY_MILLIS)

        if (request.isForcedFailure()) {
            throw simulatedFailure()
        }

        return responseFor(request)
    }

    override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> = flow {
        validateRequest(request)

        if (request.isForcedFailure()) {
            delay(STREAM_DELAY_MILLIS)
            throw simulatedFailure()
        }

        val validator = UniversalAiStreamSequenceValidator()
        streamEventsFor(request).forEach { event ->
            validator.accept(event)
            delay(STREAM_DELAY_MILLIS)
            emit(event)
        }
        validator.finish()
    }

    private fun validateRequest(request: UniversalAiRequest) {
        if (
            request.target.providerId.rawValue != DETERMINISTIC_PROVIDER_ID ||
            request.target.modelId.rawValue != DETERMINISTIC_MODEL_ID
        ) {
            throw invalidRequest(UNSUPPORTED_TARGET_MESSAGE)
        }
        if (request.responseFormat != UniversalAiResponseFormat.PlainText) {
            throw invalidRequest(UNSUPPORTED_RESPONSE_FORMAT_MESSAGE)
        }
        if (request.generation != UniversalAiGenerationParameters.Default) {
            throw invalidRequest(UNSUPPORTED_GENERATION_MESSAGE)
        }
    }

    private fun responseFor(request: UniversalAiRequest): UniversalAiResponse {
        val output =
            UniversalAiOutput.text(
                id = OUTPUT_ID,
                index = 0,
                text = "$ECHO_PREFIX${request.joinedInput()}",
            )
        return UniversalAiResponse(
            id = RESPONSE_ID,
            target = request.target,
            outputs = listOf(output),
            usage = null,
            completionReason = UniversalAiCompletionReason.Stop,
        )
    }

    private fun streamEventsFor(request: UniversalAiRequest): List<UniversalAiStreamEvent> {
        val response = responseFor(request)
        val output = response.outputs.single()
        val correlation =
            StreamCorrelation(
                responseId = response.id,
                outputId = output.id,
                outputIndex = output.index,
            )
        return listOf(
            correlation.event(
                type = UniversalAiStreamEventType.ResponseStarted,
                sequence = 1L,
            ),
            correlation.event(
                type = UniversalAiStreamEventType.OutputStarted,
                sequence = 2L,
                outputScoped = true,
            ),
            correlation.event(
                type = UniversalAiStreamEventType.OutputDelta,
                sequence = 3L,
                outputScoped = true,
                delta = ECHO_PREFIX,
            ),
            correlation.event(
                type = UniversalAiStreamEventType.OutputDelta,
                sequence = 4L,
                outputScoped = true,
                delta = request.joinedInput(),
            ),
            correlation.event(
                type = UniversalAiStreamEventType.OutputCompleted,
                sequence = 5L,
                outputScoped = true,
                output = output,
            ),
            correlation.event(
                type = UniversalAiStreamEventType.ResponseCompleted,
                sequence = 6L,
                terminal = true,
                response = response,
            ),
        )
    }

    private fun UniversalAiRequest.joinedInput(): String =
        input.joinToString(separator = "\n") { item -> item.content }

    private fun UniversalAiRequest.isForcedFailure(): Boolean {
        val onlyInput = input.singleOrNull() ?: return false
        return onlyInput.role == UniversalAiInputRole.User &&
            onlyInput.content == FORCE_ERROR_INPUT
    }

    private fun invalidRequest(message: String): UniversalAiException =
        UniversalAiException(
            UniversalAiError(
                category = UniversalAiErrorCategory.Validation,
                code = UniversalAiErrorCode.InvalidRequest,
                message = message,
            ),
        )

    private fun simulatedFailure(): UniversalAiException =
        UniversalAiException(
            UniversalAiError(
                category = UniversalAiErrorCategory.Provider,
                code = UniversalAiErrorCode.SimulatedFailure,
                message = SIMULATED_FAILURE_MESSAGE,
            ),
        )

    private data class StreamCorrelation(
        val responseId: ResponseId,
        val outputId: OutputId,
        val outputIndex: Int,
    ) {
        fun event(
            type: UniversalAiStreamEventType,
            sequence: Long,
            terminal: Boolean = false,
            outputScoped: Boolean = false,
            delta: String? = null,
            output: UniversalAiOutput? = null,
            response: UniversalAiResponse? = null,
        ): UniversalAiStreamEvent =
            UniversalAiStreamEvent(
                type = type,
                terminal = terminal,
                sequence = sequence,
                responseId = responseId,
                outputId = outputId.takeIf { outputScoped },
                outputIndex = outputIndex.takeIf { outputScoped },
                delta = delta,
                output = output,
                response = response,
            )
    }

    private companion object {
        const val DETERMINISTIC_PROVIDER_ID = "deterministic"
        const val DETERMINISTIC_MODEL_ID = "echo-v1"
        const val FORCE_ERROR_INPUT = "__force_error__"
        const val ECHO_PREFIX = "Kotlin echo: "
        const val RESPONSE_DELAY_MILLIS = 150L
        const val STREAM_DELAY_MILLIS = 100L
        const val UNSUPPORTED_TARGET_MESSAGE =
            "The deterministic connector supports only target deterministic/echo-v1."
        const val UNSUPPORTED_RESPONSE_FORMAT_MESSAGE =
            "The deterministic connector supports only plain-text responses."
        const val UNSUPPORTED_GENERATION_MESSAGE =
            "The deterministic connector does not support generation parameters."
        const val SIMULATED_FAILURE_MESSAGE =
            "The Universal AI Connector produced the requested simulated failure."

        val RESPONSE_ID = ResponseId.of("deterministic-response")
        val OUTPUT_ID = OutputId.of("deterministic-output-0")
    }
}
