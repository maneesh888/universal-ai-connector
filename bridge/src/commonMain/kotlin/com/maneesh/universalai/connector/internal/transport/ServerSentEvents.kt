package com.maneesh.universalai.connector.internal.transport

import kotlinx.coroutines.CancellationException

/** One incrementally framed server-sent event. */
internal data class ConnectorServerSentEvent(
    val data: String,
    val event: String?,
    val id: String?,
    val retryMillis: Long?,
)

/**
 * Pull-based SSE reader over the provider-neutral response body.
 *
 * Parsing is incremental and preserves body backpressure: bytes are read only while the caller
 * awaits another event. A blank line dispatches an event only after at least one `data` field.
 * End-of-stream discards an unterminated event.
 */
internal class ConnectorServerSentEventReader(
    private val body: ConnectorTransportChunkReader,
) {
    private var chunk: ByteArray = EMPTY_BYTES
    private var chunkIndex: Int = 0
    private var reachedEndOfStream: Boolean = false
    private var terminated: Boolean = false
    private var previousByteWasCarriageReturn: Boolean = false
    private var firstLine: Boolean = true
    private val line = MutableByteBuffer()
    private val dataLines = mutableListOf<String>()
    private var dataBytes: Int = 0
    private var event: String? = null
    private var id: String? = null
    private var retryMillis: Long? = null

    suspend fun readEvent(): ConnectorServerSentEvent? {
        if (terminated) {
            return null
        }
        try {
            while (true) {
                if (chunkIndex >= chunk.size) {
                    if (reachedEndOfStream) {
                        validateUnterminatedLine()
                        terminateAndDiscard()
                        return null
                    }
                    val nextChunk = body.readChunk()
                    if (nextChunk == null) {
                        reachedEndOfStream = true
                        continue
                    }
                    chunk = nextChunk
                    chunkIndex = 0
                }

                val byte = chunk[chunkIndex++]
                when (byte) {
                    CARRIAGE_RETURN -> {
                        finishLine()?.let { return it }
                        previousByteWasCarriageReturn = true
                    }
                    LINE_FEED -> {
                        if (previousByteWasCarriageReturn) {
                            previousByteWasCarriageReturn = false
                        } else {
                            finishLine()?.let { return it }
                        }
                    }
                    else -> {
                        previousByteWasCarriageReturn = false
                        line.append(byte)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            terminateAndDiscard()
            throw cancellation
        } catch (failure: Throwable) {
            terminateAndDiscard()
            throw failure
        }
    }

    private fun finishLine(): ConnectorServerSentEvent? {
        var decodedLine =
            try {
                line.takeBytes().decodeToString(throwOnInvalidSequence = true)
            } catch (_: Throwable) {
                throw malformedResponseStreamException()
            }
        if (firstLine) {
            firstLine = false
            decodedLine = decodedLine.removePrefix(UTF8_BOM)
        }
        if (decodedLine.isEmpty()) {
            return dispatchEvent()
        }
        if (decodedLine.startsWith(':')) {
            return null
        }

        val separator = decodedLine.indexOf(':')
        val field =
            if (separator < 0) {
                decodedLine
            } else {
                decodedLine.substring(0, separator)
            }
        val rawValue =
            if (separator < 0) {
                ""
            } else {
                decodedLine.substring(separator + 1)
            }
        val value = rawValue.removePrefix(" ")
        when (field) {
            "data" -> addDataLine(value)
            "event" -> event = value
            "id" -> if ('\u0000' !in value) id = value
            "retry" -> parseSseRetry(value)?.let { retryMillis = it }
        }
        return null
    }

    private fun validateUnterminatedLine() {
        try {
            line.takeBytes().decodeToString(throwOnInvalidSequence = true)
        } catch (_: Throwable) {
            throw malformedResponseStreamException()
        }
    }

    private fun addDataLine(value: String) {
        val valueBytes = value.encodeToByteArray().size
        val separatorBytes = if (dataLines.isEmpty()) 0 else 1
        if (valueBytes > MAX_SSE_EVENT_DATA_BYTES - dataBytes - separatorBytes) {
            throw malformedResponseStreamException()
        }
        dataLines += value
        dataBytes += valueBytes + separatorBytes
    }

    private fun dispatchEvent(): ConnectorServerSentEvent? {
        if (dataLines.isEmpty()) {
            resetEvent()
            return null
        }
        val parsed =
            ConnectorServerSentEvent(
                data = dataLines.joinToString("\n"),
                event = event,
                id = id,
                retryMillis = retryMillis,
            )
        resetEvent()
        return parsed
    }

    private fun resetEvent() {
        dataLines.clear()
        dataBytes = 0
        event = null
        id = null
        retryMillis = null
    }

    private fun terminateAndDiscard() {
        terminated = true
        chunk = EMPTY_BYTES
        chunkIndex = 0
        line.clear()
        resetEvent()
    }
}

private class MutableByteBuffer {
    private var bytes: ByteArray = ByteArray(INITIAL_LINE_CAPACITY)
    private var size: Int = 0

    fun append(byte: Byte) {
        if (size == MAX_SSE_LINE_BYTES) {
            throw malformedResponseStreamException()
        }
        if (size == bytes.size) {
            bytes = bytes.copyOf(minOf(bytes.size * 2, MAX_SSE_LINE_BYTES))
        }
        bytes[size++] = byte
    }

    fun takeBytes(): ByteArray {
        val result = bytes.copyOf(size)
        size = 0
        return result
    }

    fun clear() {
        size = 0
    }
}

private fun parseSseRetry(value: String): Long? {
    if (value.isEmpty() || !value.all { character -> character in '0'..'9' }) {
        return null
    }
    val parsed = value.toLongOrNull() ?: return null
    return parsed.takeIf { it <= MAX_SSE_RETRY_MILLIS }
}

private const val INITIAL_LINE_CAPACITY: Int = 256
private const val MAX_SSE_LINE_BYTES: Int = 1_048_576
private const val MAX_SSE_EVENT_DATA_BYTES: Int = 1_048_576
private const val MAX_SSE_RETRY_MILLIS: Long = 86_400_000
private const val UTF8_BOM: String = "\uFEFF"
private const val CARRIAGE_RETURN: Byte = 0x0d
private const val LINE_FEED: Byte = 0x0a
private val EMPTY_BYTES: ByteArray = ByteArray(0)
