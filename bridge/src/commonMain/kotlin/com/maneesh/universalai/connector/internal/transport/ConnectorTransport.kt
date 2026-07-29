package com.maneesh.universalai.connector.internal.transport

/**
 * Provider-neutral request data accepted by the connector's internal HTTP transport.
 *
 * The request is assembled from a validated base URL, an adapter-relative endpoint, and ordered
 * caller/adapter header fields. URL and header policy is applied before Ktor observes the request.
 */
internal class ConnectorTransportRequest(
    val method: String,
    baseUrl: ConnectorBaseUrl,
    endpoint: String,
    callerHeaders: List<ConnectorTransportHeader> = emptyList(),
    adapterHeaders: List<ConnectorTransportHeader> = emptyList(),
    val body: ByteArray? = null,
) {
    val url: String = baseUrl.resolve(endpoint)
    val headers: List<ConnectorTransportHeader> =
        ConnectorHeaderPolicy.compose(
            callerHeaders = callerHeaders,
            adapterHeaders = adapterHeaders,
        )
}

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
    body: ConnectorTransportChunkReader,
) {
    private val trackingBody = ContentTrackingChunkReader(body)

    /**
     * Transport metadata normalized from response headers.
     *
     * Request IDs use the precedence documented by [ConnectorResponseMetadataExtractor].
     * Retry-after values are bounded and expressed as a delay from response receipt.
     */
    val metadata: ConnectorResponseMetadata = ConnectorResponseMetadataExtractor.extract(headers)

    /**
     * A body reader that records the response-content-start boundary before returning the first
     * non-empty byte chunk. Headers alone do not start response content.
     */
    val body: ConnectorTransportChunkReader = trackingBody

    /**
     * Whether at least one response-body byte has been returned to the response consumer.
     *
     * Generation retries are disabled throughout P3. Future retry or reconnect decisions must
     * also treat `true` as an unconditional prohibition.
     */
    val hasResponseContentStarted: Boolean
        get() = trackingBody.hasStarted
}

/** Reads the next available response-body chunk, or returns `null` at end of stream. */
internal fun interface ConnectorTransportChunkReader {
    suspend fun readChunk(): ByteArray?
}

private class ContentTrackingChunkReader(
    private val delegate: ConnectorTransportChunkReader,
) : ConnectorTransportChunkReader {
    var hasStarted: Boolean = false
        private set

    override suspend fun readChunk(): ByteArray? {
        while (true) {
            val chunk = delegate.readChunk() ?: return null
            if (chunk.isEmpty()) {
                continue
            }
            hasStarted = true
            return chunk
        }
    }
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
