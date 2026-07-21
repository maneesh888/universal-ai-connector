package com.maneesh.universalai.apple

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiConnectorException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
        input: String,
        onSuccess: (String) -> Unit,
        onError: (AppleBridgeError) -> Unit,
    ): AppleCancellationHandle {
        val job = launchOperation {
            val result =
                try {
                    ResponseResult.Success(connector.respond(input))
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
        input: String,
        onEvent: (AppleBridgeStreamEvent) -> Unit,
        onComplete: () -> Unit,
        onError: (AppleBridgeError) -> Unit,
    ): AppleCancellationHandle {
        val job = launchOperation {
            var streamError: AppleBridgeError? = null
            connector
                .stream(input)
                .catch { failure ->
                    if (failure is CancellationException) {
                        throw failure
                    }
                    streamError = failure.toAppleBridgeError()
                }.collect { event ->
                    ensureActive()
                    onEvent(
                        AppleBridgeStreamEvent(
                            sequence = event.sequence,
                            text = event.text,
                        ),
                    )
                }

            ensureActive()
            val terminalError = streamError
            if (terminalError == null) {
                onComplete()
            } else {
                onError(terminalError)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                instrumentation.recordStreamCancellation()
            }
        }
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
        val value: String,
    ) : ResponseResult

    class Failure(
        val error: AppleBridgeError,
    ) : ResponseResult
}

private fun Throwable.toAppleBridgeError(): AppleBridgeError =
    when (this) {
        is UniversalAiConnectorException ->
            AppleBridgeError(
                code = code.stableValue,
                message = message,
            )
        else ->
            AppleBridgeError(
                code = "connector_failure",
                message = message ?: "Unknown Universal AI Connector failure.",
            )
    }
