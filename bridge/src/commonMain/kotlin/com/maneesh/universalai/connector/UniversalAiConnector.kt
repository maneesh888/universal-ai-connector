@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector

import com.maneesh.universalai.connector.internal.DeterministicConnectorEngine
import com.maneesh.universalai.connector.internal.DeterministicConnectorFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlin.native.HiddenFromObjC

/**
 * Product-facing deterministic client for Kotlin/JVM and Android consumers.
 *
 * A client instance is reusable, thread-safe, and supports concurrent requests and streams. It
 * owns no coroutine scope or other resource, so no cleanup is required. Suspending work and cold
 * flows run in the caller's coroutine context; cancelling the caller cancels the active operation.
 */
@HiddenFromObjC
class UniversalAiConnector {
    private val engine = DeterministicConnectorEngine()

    /** The current library version. */
    val version: String
        get() = LIBRARY_VERSION

    /** Returns one deterministic response in the caller's coroutine context. */
    suspend fun respond(input: String): String =
        try {
            engine.respond(input)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw failure.toConnectorException()
        }

    /**
     * Returns a cold, ordered stream that runs in the collector's coroutine context.
     *
     * Cancelling collection immediately cancels the active stream.
     */
    fun stream(input: String): Flow<UniversalAiStreamEvent> =
        engine
            .stream(input)
            .map { event ->
                UniversalAiStreamEvent(
                    sequence = event.sequence,
                    text = event.text,
                )
            }.catch { failure ->
                if (failure is CancellationException) {
                    throw failure
                }
                throw failure.toConnectorException()
            }

    companion object {
        const val LIBRARY_VERSION: String = "0.1.0-alpha.1"
        const val SIMULATED_ERROR_INPUT: String = "__force_error__"
    }
}

private fun Throwable.toConnectorException(): UniversalAiConnectorException =
    when (this) {
        is UniversalAiConnectorException -> this
        is DeterministicConnectorFailure ->
            when (code) {
                "invalid_input" ->
                    UniversalAiConnectorException(
                        code = UniversalAiErrorCode.INVALID_INPUT,
                        message = "Input must not be empty.",
                    )
                "simulated_failure" ->
                    UniversalAiConnectorException(
                        code = UniversalAiErrorCode.SIMULATED_FAILURE,
                        message = "The Universal AI Connector produced the requested simulated failure.",
                    )
                else -> connectorFailure()
            }
        else -> connectorFailure()
    }

private fun Throwable.connectorFailure(): UniversalAiConnectorException =
    UniversalAiConnectorException(
        code = UniversalAiErrorCode.CONNECTOR_FAILURE,
        message = message ?: "Unknown Universal AI Connector failure.",
    )
