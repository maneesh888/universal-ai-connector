@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector

import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.toUniversalAiException
import com.maneesh.universalai.connector.internal.ConnectorEngine
import com.maneesh.universalai.connector.internal.DeterministicConnectorEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlin.native.HiddenFromObjC

/**
 * Product-facing deterministic client for Kotlin/JVM and Android consumers.
 *
 * A client instance is reusable, thread-safe, and supports concurrent requests and streams. It
 * owns no coroutine scope or other resource, so no cleanup is required. Suspending work and cold
 * flows run in the caller's coroutine context; cancelling the caller cancels the active operation.
 */
@HiddenFromObjC
class UniversalAiConnector internal constructor(
    private val engine: ConnectorEngine,
) {
    constructor() : this(DeterministicConnectorEngine())

    /** The current library version. */
    val version: String
        get() = LIBRARY_VERSION

    /** Returns one deterministic response in the caller's coroutine context. */
    suspend fun respond(request: UniversalAiRequest): UniversalAiResponse =
        try {
            engine.respond(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw failure.toUniversalAiException()
        }

    /**
     * Returns a cold, ordered stream that runs in the collector's coroutine context.
     *
     * Cancelling collection immediately cancels the active stream.
     */
    fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> =
        flow {
            emitAll(engine.stream(request))
        }
            .catch { failure ->
                if (failure is CancellationException) {
                    throw failure
                }
                throw failure.toUniversalAiException()
            }

    companion object {
        const val LIBRARY_VERSION: String = "0.1.0-alpha.1"
        const val SIMULATED_ERROR_INPUT: String = "__force_error__"
    }
}
