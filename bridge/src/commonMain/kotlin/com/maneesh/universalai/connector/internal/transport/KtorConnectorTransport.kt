package com.maneesh.universalai.connector.internal.transport

import com.maneesh.universalai.connector.contract.UniversalAiError
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException

/**
 * Creates the platform-default Ktor client owned by its returned transport.
 */
internal expect fun createDefaultHttpClient(): HttpClient

/** Creates a transport that owns both its Ktor client and the platform-default engine. */
internal fun createDefaultKtorTransport(): ConnectorTransport =
    KtorConnectorTransport(createDefaultHttpClient())

/**
 * Creates a transport that owns its Ktor client wrapper but does not own [httpEngine].
 *
 * Ktor clients constructed from an existing engine leave that engine under caller ownership.
 */
internal fun createKtorTransport(httpEngine: HttpClientEngine): ConnectorTransport =
    KtorConnectorTransport(
        HttpClient(httpEngine) {
            install(HttpTimeout)
        },
    )

private class KtorConnectorTransport(
    private val httpClient: HttpClient,
) : ConnectorTransport {
    override suspend fun <Result> execute(
        request: ConnectorTransportRequest,
        consumeResponse: suspend (ConnectorTransportResponse) -> Result,
    ): Result {
        validateConnectorTransportHeaders(request.headers)
        try {
            return httpClient
                .prepareRequest(request.url) {
                    method = HttpMethod(request.method)
                    timeout {
                        connectTimeoutMillis = request.timeouts.connectTimeoutMillis
                        requestTimeoutMillis = request.timeouts.requestTimeoutMillis
                    }
                    headers {
                        request.headers.forEach { header ->
                            append(header.name, header.value)
                        }
                    }
                    request.body?.let(::setBody)
                }.execute { response ->
                    consumeResponse(
                        ConnectorTransportResponse(
                            statusCode = response.status.value,
                            headers =
                                buildList {
                                    response.headers.entries().forEach { (name, values) ->
                                        values.forEach { value ->
                                            add(ConnectorTransportHeader(name, value))
                                        }
                                    }
                                },
                            body = KtorConnectorTransportChunkReader(response.bodyAsChannel()),
                        ),
                    )
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ConnectTimeoutException) {
            throw connectorTimeoutException(ConnectorTimeoutKind.Connection)
        } catch (_: HttpRequestTimeoutException) {
            throw connectorTimeoutException(ConnectorTimeoutKind.Request)
        }
    }

    override fun close() {
        httpClient.close()
    }
}

internal enum class ConnectorTimeoutKind {
    Connection,
    Request,
}

internal fun connectorTimeoutException(kind: ConnectorTimeoutKind): UniversalAiException =
    when (kind) {
        ConnectorTimeoutKind.Connection ->
            UniversalAiException(
                UniversalAiError(
                    category = UniversalAiErrorCategory.Transport,
                    code = UniversalAiErrorCode.of("connection_timeout"),
                    message = "The HTTP connection timed out.",
                ),
            )
        ConnectorTimeoutKind.Request ->
            UniversalAiException(
                UniversalAiError(
                    category = UniversalAiErrorCategory.Transport,
                    code = UniversalAiErrorCode.of("request_timeout"),
                    message = "The HTTP request timed out.",
                ),
            )
    }

private class KtorConnectorTransportChunkReader(
    private val channel: ByteReadChannel,
) : ConnectorTransportChunkReader {
    override suspend fun readChunk(): ByteArray? {
        val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
        while (true) {
            when (val bytesRead = channel.readAvailable(buffer)) {
                -1 -> return null
                0 -> continue
                buffer.size -> return buffer
                else -> return buffer.copyOf(bytesRead)
            }
        }
    }

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 8 * 1024
    }
}
