package com.maneesh.universalai.samples.android

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiConnectorException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AndroidSampleUiState(
    val version: String,
    val headline: String = "Ready to run",
    val response: String = "Not run yet",
    val streamEvents: List<String> = emptyList(),
    val error: String = "Not run yet",
    val responseCancellation: String = "Not run yet",
    val streamCancellation: String = "Not run yet",
    val isBusy: Boolean = false,
)

internal class AndroidSampleController(
    private val scope: CoroutineScope,
    private val connector: UniversalAiConnector = UniversalAiConnector(),
) {
    private val mutableState = MutableStateFlow(AndroidSampleUiState(version = connector.version))
    val state = mutableState.asStateFlow()

    private var activeJob: Job? = null

    fun runCompleteDemo() =
        launchOperation("Running every deterministic path") {
            val response = connector.respond(RESPONSE_INPUT)
            mutableState.update { it.copy(response = response) }

            val streamEvents =
                connector.stream(STREAM_INPUT).toList().map { event ->
                    "${event.sequence}: ${event.text}"
                }
            mutableState.update { it.copy(streamEvents = streamEvents) }

            mutableState.update { it.copy(error = captureStableError()) }
            proveResponseCancellation()
            mutableState.update { it.copy(streamCancellation = stopStreamAfterFirstEvent()) }
            mutableState.update { it.copy(headline = "All deterministic paths passed") }
        }

    fun runResponse() =
        launchOperation("Requesting one response") {
            val response = connector.respond(RESPONSE_INPUT)
            mutableState.update {
                it.copy(
                    headline = "One-shot response passed",
                    response = response,
                )
            }
        }

    fun runStream() =
        launchOperation("Collecting the ordered stream") {
            val streamEvents =
                connector.stream(STREAM_INPUT).toList().map { event ->
                    "${event.sequence}: ${event.text}"
                }
            mutableState.update {
                it.copy(
                    headline = "Ordered stream passed",
                    streamEvents = streamEvents,
                )
            }
        }

    fun runError() =
        launchOperation("Requesting the simulated error") {
            mutableState.update {
                it.copy(
                    headline = "Stable error mapping passed",
                    error = captureStableError(),
                )
            }
        }

    fun runResponseCancellation() =
        launchOperation("Cancelling an active response") {
            proveResponseCancellation()
            mutableState.update { it.copy(headline = "Response cancellation passed") }
        }

    fun runStreamCancellation() =
        launchOperation("Stopping a stream after one event") {
            mutableState.update {
                it.copy(
                    headline = "Stream cancellation passed",
                    streamCancellation = stopStreamAfterFirstEvent(),
                )
            }
        }

    private fun launchOperation(
        runningHeadline: String,
        operation: suspend () -> Unit,
    ) {
        activeJob?.cancel()
        val newJob =
            scope.launch(start = CoroutineStart.LAZY) {
                mutableState.update { it.copy(headline = runningHeadline, isBusy = true) }
                try {
                    operation()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    mutableState.update {
                        it.copy(headline = "Demo failed: ${failure.message ?: failure::class.simpleName}")
                    }
                } finally {
                    if (activeJob === currentCoroutineContext()[Job]) {
                        mutableState.update { it.copy(isBusy = false) }
                    }
                }
            }
        activeJob = newJob
        newJob.start()
    }

    private suspend fun captureStableError(): String =
        try {
            connector.respond(UniversalAiConnector.SIMULATED_ERROR_INPUT)
            error("The deterministic simulated error did not occur.")
        } catch (failure: UniversalAiConnectorException) {
            "${failure.code.stableValue}: ${failure.message}"
        }

    private suspend fun proveResponseCancellation() {
        coroutineScope {
            val request =
                async(start = CoroutineStart.UNDISPATCHED) {
                    connector.respond("cancel this Android response")
                }
            request.cancelAndJoin()
            check(request.isCancelled)
        }
        mutableState.update { it.copy(responseCancellation = "Cancelled before completion") }
    }

    private suspend fun stopStreamAfterFirstEvent(): String {
        val firstEvent = connector.stream("stop Android stream").take(1).single()
        return "Stopped after ${firstEvent.sequence}: ${firstEvent.text}"
    }

    private companion object {
        const val RESPONSE_INPUT = "hello from Android"
        const val STREAM_INPUT = "Android stream"
    }
}
