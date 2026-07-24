package com.maneesh.universalai.samples.android

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
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
import kotlinx.coroutines.flow.first
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
            val response = connector.respond(request(RESPONSE_INPUT))
            mutableState.update { it.copy(response = response.textOutput()) }

            val streamEvents =
                connector
                    .stream(request(STREAM_INPUT))
                    .toList()
                    .map { event -> event.render() }
            mutableState.update { it.copy(streamEvents = streamEvents) }

            mutableState.update { it.copy(error = captureStableError()) }
            proveResponseCancellation()
            mutableState.update { it.copy(streamCancellation = stopStreamAtFirstOutputDelta()) }
            mutableState.update { it.copy(headline = "All deterministic paths passed") }
        }

    fun runResponse() =
        launchOperation("Requesting one response") {
            val response = connector.respond(request(RESPONSE_INPUT))
            mutableState.update {
                it.copy(
                    headline = "One-shot response passed",
                    response = response.textOutput(),
                )
            }
        }

    fun runStream() =
        launchOperation("Collecting the ordered stream") {
            val streamEvents =
                connector
                    .stream(request(STREAM_INPUT))
                    .toList()
                    .map { event -> event.render() }
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
        launchOperation("Stopping at the first output delta") {
            mutableState.update {
                it.copy(
                    headline = "Stream cancellation passed",
                    streamCancellation = stopStreamAtFirstOutputDelta(),
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
            connector.respond(request(UniversalAiConnector.SIMULATED_ERROR_INPUT))
            error("The deterministic simulated error did not occur.")
        } catch (failure: UniversalAiException) {
            "${failure.error.category.rawValue}/${failure.error.code.rawValue}: " +
                failure.error.message
        }

    private suspend fun proveResponseCancellation() {
        coroutineScope {
            val requestJob =
                async(start = CoroutineStart.UNDISPATCHED) {
                    connector.respond(request("cancel this Android response"))
                }
            requestJob.cancelAndJoin()
            check(requestJob.isCancelled)
        }
        mutableState.update { it.copy(responseCancellation = "Cancelled before completion") }
    }

    private suspend fun stopStreamAtFirstOutputDelta(): String {
        val firstDelta =
            connector
                .stream(request("stop Android stream"))
                .first { event -> event.type == UniversalAiStreamEventType.OutputDelta }
        return "Stopped after ${firstDelta.render()}"
    }

    private fun request(input: String): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = ProviderId.of("deterministic"),
                    modelId = ModelId.of("echo-v1"),
                ),
            input =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = input,
                    ),
                ),
        )

    private fun UniversalAiResponse.textOutput(): String =
        checkNotNull(outputs.single().text) {
            "The deterministic connector must return one text output."
        }

    private fun UniversalAiStreamEvent.render(): String =
        buildString {
            append(sequence)
            append(": ")
            append(type.rawValue)
            delta?.let { value ->
                append(" · delta=")
                append(value)
            }
            output?.let { completedOutput ->
                append(" · output=")
                append(checkNotNull(completedOutput.text))
            }
            response?.let { completedResponse ->
                append(" · response=")
                append(completedResponse.textOutput())
            }
            if (terminal) {
                append(" · terminal=true")
            }
        }

    private companion object {
        const val RESPONSE_INPUT = "hello from Android"
        const val STREAM_INPUT = "Android stream"
    }
}
