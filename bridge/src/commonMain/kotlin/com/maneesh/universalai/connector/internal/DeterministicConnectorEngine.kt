package com.maneesh.universalai.connector.internal

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class DeterministicConnectorEngine {
    suspend fun respond(input: String): String {
        val normalizedInput = input.trim()
        if (normalizedInput.isEmpty()) {
            throw DeterministicConnectorFailure(
                code = "invalid_input",
                message = "Input must not be empty.",
            )
        }

        delay(RESPONSE_DELAY_MILLIS)

        if (normalizedInput == FORCE_ERROR_INPUT) {
            throw DeterministicConnectorFailure(
                code = "simulated_failure",
                message = "The Universal AI Connector produced the requested simulated failure.",
            )
        }

        return "Kotlin echo: $normalizedInput"
    }

    fun stream(input: String): Flow<DeterministicStreamEvent> = flow {
        val normalizedInput = input.trim()
        if (normalizedInput.isEmpty()) {
            throw DeterministicConnectorFailure(
                code = "invalid_input",
                message = "Input must not be empty.",
            )
        }

        if (normalizedInput == FORCE_ERROR_INPUT) {
            delay(STREAM_DELAY_MILLIS)
            throw DeterministicConnectorFailure(
                code = "simulated_failure",
                message = "The Universal AI Connector produced the requested simulated failure.",
            )
        }

        repeat(STREAM_EVENT_COUNT) { index ->
            delay(STREAM_DELAY_MILLIS)
            emit(
                DeterministicStreamEvent(
                    sequence = index + 1,
                    text = "$normalizedInput ${index + 1}",
                ),
            )
        }
    }

    private companion object {
        const val FORCE_ERROR_INPUT = "__force_error__"
        const val RESPONSE_DELAY_MILLIS = 150L
        const val STREAM_DELAY_MILLIS = 100L
        const val STREAM_EVENT_COUNT = 5
    }
}

internal data class DeterministicStreamEvent(
    val sequence: Int,
    val text: String,
)

internal class DeterministicConnectorFailure(
    val code: String,
    override val message: String,
) : IllegalStateException(message)
