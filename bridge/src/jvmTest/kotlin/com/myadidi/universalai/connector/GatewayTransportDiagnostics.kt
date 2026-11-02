@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package com.myadidi.universalai.connector

import com.myadidi.universalai.connector.internal.transport.ConnectorTransport
import com.myadidi.universalai.connector.internal.transport.ConnectorTransportTimeouts
import com.myadidi.universalai.connector.internal.transport.createKtorTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.HttpSendPipeline
import io.ktor.util.AttributeKey
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Observes the original engine failure before the production transport's private error mapping. */
internal class GatewayDiagnosticEngine(
    private val delegate: HttpClientEngine,
    private val diagnostic: (String) -> Unit = System.err::println,
    private val sensitiveValues: () -> List<String> = { emptyList() },
) : HttpClientEngine by delegate {
    private val closed = AtomicBoolean()

    // Delegating install() would bind the delegate's execute(), bypassing this observer.
    override fun install(client: HttpClient) {
        client.sendPipeline.intercept(HttpSendPipeline.Engine) {
            // Ktor creates an engine call job later. Keep the originating caller job
            // separately so its cancellation cannot race the request execution job.
            context.attributes.put(CALLER_JOB, currentCoroutineContext().job)
            proceed()
        }
        super<HttpClientEngine>.install(client)
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val attempt = attempts.incrementAndGet()
        return try {
            delegate.execute(data)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val caller = data.attributes.getOrNull(CALLER_JOB)
            if (caller?.isActive != false) {
                try {
                    val chain = mutableListOf<Throwable>()
                    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
                    var next: Throwable? = failure
                    while (next != null && chain.size < 4 && visited.add(next)) {
                        chain.add(next)
                        next = next.cause
                    }
                    val result = buildJsonObject {
                        put("phase", "engine_before_response")
                        put("caller_context_available", caller != null)
                        put("utc", Instant.now().toString())
                        put("transport_attempt", attempt)
                        put("exception_classes", JsonArray(chain.map { JsonPrimitive(it.javaClass.simpleName.takeIf(KNOWN_CLASSES::contains) ?: "other") }))
                        put("classifications", JsonArray(chain.flatMap(::classifyNetworkFailure).distinct().map(::JsonPrimitive)))
                        put("cause_chain_truncated", next != null)
                        put("unfiltered_text_retained", false)
                    }.toString()
                    val sensitive = sensitiveValues() + listOfNotNull(data.headers["Authorization"]?.removePrefix("Bearer "))
                    val safe = if (sensitive.any { it.isNotEmpty() && result.contains(it) }) {
                        "{\"outcome\":\"sensitive_collision_redacted\"}"
                    } else result
                    diagnostic("UAC_GATEWAY_TRANSPORT_DIAGNOSTIC $safe")
                } catch (_: Exception) {
                    // Preserve the original failure, even if the diagnostic sink is unavailable.
                }
            }
            throw failure
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) delegate.close()
    }

    private companion object {
        val attempts = AtomicLong()
        val CALLER_JOB = AttributeKey<Job>("uac.gateway.diagnostic.caller-job")
        val KNOWN_CLASSES = setOf("IOException", "EOFException", "SocketException", "SocketTimeoutException", "ConnectException", "UnknownHostException", "SSLException", "SSLHandshakeException", "SSLPeerUnverifiedException", "TLSException", "ConnectTimeoutException", "HttpRequestTimeoutException", "ClosedReadChannelException", "ClosedWriteChannelException", "ChannelReadException", "ChannelWriteException", "ClientEngineClosedException")
    }
}

private fun classifyNetworkFailure(failure: Throwable): List<String> {
    val message = failure.message?.takeIf { it.length <= 2_048 }?.lowercase() ?: return emptyList()
    return buildList {
        if ("connection reset" in message || "econnreset" in message) add("connection_reset")
        if ("connection refused" in message || "econnrefused" in message) add("connection_refused")
        if ("broken pipe" in message || "epipe" in message) add("broken_pipe")
        if (message == "eof" || "unexpected eof" in message || "unexpected end of" in message || "end of file" in message) add("unexpected_eof")
        if ("no route to host" in message || "network is unreachable" in message) add("network_unreachable")
        if ("timed out" in message || "timeout" in message) add("timeout_mentioned")
        if ("handshake" in message || "certificate" in message) add("tls_mentioned")
    }
}

/** A live fixture owns both the public client wrapper and its injected platform engine. */
internal class GatewayDiagnosticClient(
    configuration: UniversalAiConnectorConfiguration,
    engine: HttpClientEngine = CIO.create(),
    diagnostic: (String) -> Unit = System.err::println,
    sensitiveValues: () -> List<String> = { emptyList() },
) {
    private val observed = GatewayDiagnosticEngine(engine, diagnostic, sensitiveValues)
    private val client = try { UniversalAiConnector(configuration, observed) } catch (failure: Throwable) {
        closeGatewayResources(failure, observed::close)
        throw failure
    }

    suspend fun <Result> use(block: suspend (UniversalAiConnector) -> Result): Result {
        var original: Throwable? = null
        try {
            return block(client)
        } catch (failure: Throwable) {
            original = failure
            throw failure
        } finally {
            closeGatewayResources(original, client::close, observed::close)
        }
    }
}

internal fun createGatewayDiagnosticTransport(
    sensitiveValues: () -> List<String>,
    engine: HttpClientEngine = CIO.create(),
    createTransport: (HttpClientEngine) -> ConnectorTransport = { createKtorTransport(it, ConnectorTransportTimeouts()) },
): ConnectorTransport {
    val observed = GatewayDiagnosticEngine(engine, sensitiveValues = sensitiveValues)
    val transport = try { createTransport(observed) } catch (failure: Throwable) {
        closeGatewayResources(failure, observed::close)
        throw failure
    }
    val closed = AtomicBoolean()
    return object : ConnectorTransport by transport {
        override fun close() {
            if (closed.compareAndSet(false, true)) closeGatewayResources(null, transport::close, observed::close)
        }
    }
}

private fun closeGatewayResources(original: Throwable?, vararg actions: () -> Unit) {
    var failed = false
    actions.forEach { action -> try { action() } catch (_: Throwable) { failed = true } }
    if (failed) {
        val safe = IllegalStateException("Gateway test resource cleanup failed.")
        if (original == null) throw safe else original.addSuppressed(safe)
    }
}
