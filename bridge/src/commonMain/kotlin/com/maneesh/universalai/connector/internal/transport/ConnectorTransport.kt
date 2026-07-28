package com.maneesh.universalai.connector.internal.transport

/**
 * Provider-neutral request data accepted by the connector's internal HTTP transport.
 *
 * Requests produced by [ConnectorTransportPolicy] carry an already-resolved URL, composed
 * headers, and bounded timeout configuration. The transport still accepts direct requests for
 * deterministic low-level tests.
 */
internal data class ConnectorTransportRequest(
    val method: String,
    val url: String,
    val headers: List<ConnectorTransportHeader> = emptyList(),
    val body: ByteArray? = null,
    val timeouts: ConnectorTransportTimeouts = ConnectorTransportTimeouts.Default,
)

/** One raw HTTP header field. Repeated fields remain separate ordered entries. */
internal data class ConnectorTransportHeader(
    val name: String,
    val value: String,
)

/**
 * Provider-neutral response data whose body is valid only while the transport callback runs.
 */
internal class ConnectorTransportResponse(
    val statusCode: Int,
    val headers: List<ConnectorTransportHeader>,
    val body: ConnectorTransportChunkReader,
)

/** Reads the next available response-body chunk, or returns `null` at end of stream. */
internal fun interface ConnectorTransportChunkReader {
    suspend fun readChunk(): ByteArray?
}

/**
 * Callback-scoped provider-neutral HTTP transport.
 *
 * Implementations release the response and its body when [consumeResponse] returns or fails. A
 * response or chunk reader must therefore not escape the callback.
 */
internal interface ConnectorTransport {
    suspend fun <Result> execute(
        request: ConnectorTransportRequest,
        consumeResponse: suspend (ConnectorTransportResponse) -> Result,
    ): Result

    /** Releases connector-owned transport resources. Safe to invoke repeatedly. */
    fun close()
}
