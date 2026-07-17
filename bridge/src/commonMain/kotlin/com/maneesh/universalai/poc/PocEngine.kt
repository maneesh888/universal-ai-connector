package com.maneesh.universalai.poc

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class PocEngine {
    fun version(): String = "universal-ai-connector-poc/0.1.0"

    fun greeting(name: String): String {
        val normalizedName = name.trim().ifEmpty { "Swift" }
        return "Hello, $normalizedName, from Kotlin."
    }

    suspend fun respond(input: String): String {
        val normalizedInput = input.trim()
        if (normalizedInput.isEmpty()) {
            throw PocFailure(
                code = "invalid_input",
                message = "Input must not be empty.",
            )
        }

        delay(RESPONSE_DELAY_MILLIS)

        if (normalizedInput == FORCE_ERROR_INPUT) {
            throw PocFailure(
                code = "simulated_failure",
                message = "The Kotlin POC produced the requested simulated failure.",
            )
        }

        return "Kotlin echo: $normalizedInput"
    }

    fun stream(input: String): Flow<PocStreamEvent> = flow {
        val normalizedInput = input.trim()
        if (normalizedInput.isEmpty()) {
            throw PocFailure(
                code = "invalid_input",
                message = "Input must not be empty.",
            )
        }

        repeat(STREAM_EVENT_COUNT) { index ->
            delay(STREAM_DELAY_MILLIS)
            emit(
                PocStreamEvent(
                    sequence = index + 1,
                    text = "$normalizedInput ${index + 1}",
                ),
            )
        }
    }

    companion object {
        const val FORCE_ERROR_INPUT = "__force_error__"
        const val RESPONSE_DELAY_MILLIS = 150L
        const val STREAM_DELAY_MILLIS = 100L
        const val STREAM_EVENT_COUNT = 5
    }
}
