package com.maneesh.universalai.connector.internal.transport

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
import kotlinx.io.IOException

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
internal fun createKtorTransport(
    httpEngine: HttpClientEngine,
    timeouts: ConnectorTransportTimeouts = ConnectorTransportTimeouts(),
): ConnectorTransport =
    KtorConnectorTransport(
        httpClient =
            HttpClient(httpEngine) {
                install(HttpTimeout)
            },
        timeouts = timeouts,
    )

private class KtorConnectorTransport(
    private val httpClient: HttpClient,
    private val timeouts: ConnectorTransportTimeouts = ConnectorTransportTimeouts(),
) : ConnectorTransport {
    override suspend fun <Result> execute(
        request: ConnectorTransportRequest,
        consumeResponse: suspend (ConnectorTransportResponse) -> Result,
    ): Result {
        try {
            return httpClient
                .prepareRequest(request.url) {
                    method = HttpMethod(request.method)
                    timeout {
                        connectTimeoutMillis = timeouts.connectTimeoutMillis
                        requestTimeoutMillis = timeouts.requestTimeoutMillis
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
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: ConnectTimeoutException) {
            throw connectionTimeoutException()
        } catch (_: HttpRequestTimeoutException) {
            throw requestTimeoutException()
        } catch (_: IOException) {
            throw transportFailureException()
        }
    }

    override fun close() {
        httpClient.close()
    }
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
