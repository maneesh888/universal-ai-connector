package com.maneesh.universalai.poc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class PocBridge internal constructor(
    private val scope: CoroutineScope,
    private val engine: PocEngine,
) {
    constructor() : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        engine = PocEngine(),
    )

    fun version(): String = engine.version()

    fun greeting(name: String): String = engine.greeting(name)

    fun resetInstrumentation() {
        PocInstrumentation.reset()
    }

    fun instrumentationSnapshot(): PocInstrumentationSnapshot =
        PocInstrumentation.snapshot()

    fun respond(
        input: String,
        onSuccess: (String) -> Unit,
        onError: (PocBridgeError) -> Unit,
    ): PocCancellationHandle {
        val job = scope.launch {
            try {
                val response = engine.respond(input)
                ensureActive()
                onSuccess(response)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: PocFailure) {
                onError(
                    PocBridgeError(
                        code = failure.code,
                        message = failure.message,
                    ),
                )
            } catch (error: Throwable) {
                onError(
                    PocBridgeError(
                        code = "bridge_failure",
                        message = error.message ?: "Unknown Kotlin bridge failure.",
                    ),
                )
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                PocInstrumentation.recordUnaryCancellation()
            }
        }

        return PocCancellationHandle(job)
    }

    fun stream(
        input: String,
        onEvent: (PocStreamEvent) -> Unit,
        onComplete: () -> Unit,
        onError: (PocBridgeError) -> Unit,
    ): PocCancellationHandle {
        val job = scope.launch {
            try {
                engine.stream(input).collect { event ->
                    ensureActive()
                    onEvent(event)
                }
                ensureActive()
                onComplete()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: PocFailure) {
                onError(
                    PocBridgeError(
                        code = failure.code,
                        message = failure.message,
                    ),
                )
            } catch (error: Throwable) {
                onError(
                    PocBridgeError(
                        code = "bridge_failure",
                        message = error.message ?: "Unknown Kotlin bridge failure.",
                    ),
                )
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                PocInstrumentation.recordStreamCancellation()
            }
        }

        return PocCancellationHandle(job)
    }
}
