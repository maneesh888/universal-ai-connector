@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector

import com.maneesh.universalai.connector.contract.UniversalAiError
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.toUniversalAiException
import com.maneesh.universalai.connector.internal.ConnectorEngine
import com.maneesh.universalai.connector.internal.ConnectorResourceLease
import com.maneesh.universalai.connector.internal.ConnectorResourceOwnership
import com.maneesh.universalai.connector.internal.DeterministicConnectorEngine
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import com.maneesh.universalai.connector.internal.transport.createDefaultKtorTransport
import com.maneesh.universalai.connector.internal.transport.createKtorTransport
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlin.native.HiddenFromObjC

/**
 * Product-facing deterministic client for Kotlin/JVM and Android consumers.
 *
 * A client instance is reusable, thread-safe, and supports concurrent requests and streams. It
 * owns its default HTTP transport, so callers close it when finished. Suspending work and cold
 * flows remain bound to the caller; cancelling the caller cancels the active operation.
 */
@HiddenFromObjC
class UniversalAiConnector private constructor(
    private val engine: ConnectorEngine,
    private val transportLease: ConnectorResourceLease,
) : AutoCloseable {
    private val closeSignal: CompletableJob = Job()

    /** Creates a connector that owns its supported platform HTTP client and engine. */
    constructor() : this(defaultComponents())

    /**
     * Creates a connector around a caller-owned Ktor engine.
     *
     * The connector owns and closes only its dedicated client wrapper. The caller retains
     * ownership of [httpEngine] and may share it with other connectors.
     */
    constructor(httpEngine: HttpClientEngine) : this(injectedEngineComponents(httpEngine))

    internal constructor(engine: ConnectorEngine) :
        this(
            engine = engine,
            transportLease = ConnectorResourceLease.none(),
        )

    private constructor(components: ConnectorComponents) :
        this(
            engine = components.engine,
            transportLease = components.transportLease,
        )

    /** The current library version. */
    val version: String
        get() = LIBRARY_VERSION

    /** Returns one deterministic response in the caller's coroutine context. */
    suspend fun respond(request: UniversalAiRequest): UniversalAiResponse =
        try {
            runWhileOpen {
                engine.respond(request)
            }
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
            ensureOpen()
            emitAll(streamWhileOpen(engine.stream(request)))
        }
            .catch { failure ->
                if (failure is CancellationException) {
                    throw failure
                }
                throw failure.toUniversalAiException()
            }

    private fun streamWhileOpen(
        source: Flow<UniversalAiStreamEvent>,
    ): Flow<UniversalAiStreamEvent> =
        channelFlow<ConnectorStreamSignal> {
            ensureOpen()
            val operationJob = coroutineContext.job
            val closeHandle =
                closeSignal.invokeOnCompletion {
                    operationJob.cancel(closedOperationCancellation())
                }
            try {
                source.collect { event ->
                    send(ConnectorStreamSignal.Event(event))
                }
            } catch (failure: Throwable) {
                if (!operationJob.isActive) {
                    throw failure
                }
                send(ConnectorStreamSignal.Failure(failure))
            } finally {
                closeHandle.dispose()
            }
        }
            .buffer(Channel.RENDEZVOUS)
            .transform { signal ->
                when (signal) {
                    is ConnectorStreamSignal.Event -> emit(signal.event)
                    is ConnectorStreamSignal.Failure -> throw signal.failure
                }
            }

    /**
     * Cancels active work and releases connector-owned transport resources.
     *
     * Closing is thread-safe and idempotent. The library version remains readable afterward.
     */
    override fun close() {
        if (closeSignal.complete()) {
            transportLease.close()
        }
    }

    private suspend fun <T> runWhileOpen(block: suspend () -> T): T {
        ensureOpen()
        return supervisorScope {
            val operation =
                async(start = CoroutineStart.LAZY) {
                    block()
                }
            val closeHandle =
                closeSignal.invokeOnCompletion {
                    operation.cancel(closedOperationCancellation())
                }
            try {
                operation.start()
                operation.await()
            } finally {
                closeHandle.dispose()
            }
        }
    }

    private fun ensureOpen() {
        if (!closeSignal.isActive) {
            throw closedConnectorException()
        }
    }

    companion object {
        const val LIBRARY_VERSION: String = "0.1.0-alpha.1"
        const val SIMULATED_ERROR_INPUT: String = "__force_error__"

        internal const val CLOSED_MESSAGE: String = "The Universal AI Connector is closed."

        internal fun createForTesting(
            engineFactory: () -> ConnectorEngine,
            transport: ConnectorTransport,
            ownership: ConnectorResourceOwnership,
        ): UniversalAiConnector =
            connectorComponents(
                transportFactory = { transport },
                transportOwnership = ownership,
                engineFactory = engineFactory,
            ).let(::UniversalAiConnector)

        private fun defaultComponents(): ConnectorComponents =
            connectorComponents(
                transportFactory = ::createDefaultKtorTransport,
                transportOwnership = ConnectorResourceOwnership.Owned,
            )

        private fun injectedEngineComponents(httpEngine: HttpClientEngine): ConnectorComponents =
            connectorComponents(
                transportFactory = { createKtorTransport(httpEngine) },
                transportOwnership = ConnectorResourceOwnership.Owned,
            )

        private fun connectorComponents(
            transportFactory: () -> ConnectorTransport,
            transportOwnership: ConnectorResourceOwnership,
            engineFactory: () -> ConnectorEngine = ::DeterministicConnectorEngine,
        ): ConnectorComponents {
            val lease =
                ConnectorResourceLease.of(
                    transport = transportFactory(),
                    ownership = transportOwnership,
                )
            return try {
                ConnectorComponents(
                    engine = engineFactory(),
                    transportLease = lease,
                )
            } catch (failure: Throwable) {
                try {
                    lease.close()
                } catch (_: Throwable) {
                    // Preserve the construction failure while still attempting owned cleanup.
                }
                throw failure
            }
        }

        private fun closedConnectorException(): UniversalAiException =
            UniversalAiException(
                UniversalAiError(
                    category = UniversalAiErrorCategory.Validation,
                    code = UniversalAiErrorCode.InvalidRequest,
                    message = CLOSED_MESSAGE,
                ),
            )

        private fun closedOperationCancellation(): CancellationException =
            CancellationException(CLOSED_MESSAGE)
    }
}

private data class ConnectorComponents(
    val engine: ConnectorEngine,
    val transportLease: ConnectorResourceLease,
)

private sealed interface ConnectorStreamSignal {
    data class Event(
        val event: UniversalAiStreamEvent,
    ) : ConnectorStreamSignal

    data class Failure(
        val failure: Throwable,
    ) : ConnectorStreamSignal
}
