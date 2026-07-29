package com.maneesh.universalai.connector.internal.transport

import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSentEventsTests {
    @Test
    fun parsesBomCrLfFieldsAndUtf8AcrossSingleByteChunks() = runTest {
        val fixture =
            "\uFEFF: ignored\r\n" +
                "event: update\r\n" +
                "id: event-1\r\n" +
                "retry: 2500\r\n" +
                "data: first 😀\r\n" +
                "data: second\r\n" +
                "\r\n"
        val reader = ConnectorServerSentEventReader(chunkReader(singleByteChunks(fixture)))

        assertEquals(
            ConnectorServerSentEvent(
                data = "first 😀\nsecond",
                event = "update",
                id = "event-1",
                retryMillis = 2_500,
            ),
            reader.readEvent(),
        )
        assertNull(reader.readEvent())
        assertNull(reader.readEvent())
    }

    @Test
    fun handlesCommentsBlankEventsRepeatedFieldsAndLfDelimiters() = runTest {
        val fixture =
            ": comment\n" +
                "event: ignored-without-data\n" +
                "\n" +
                "event: first\n" +
                "event: second\n" +
                "id: old\n" +
                "id: new\n" +
                "retry: 12\n" +
                "retry: invalid\n" +
                "data: one\n" +
                "data\n" +
                "data:three\n" +
                "unknown: ignored\n" +
                "\n"
        val reader = ConnectorServerSentEventReader(chunkReader(listOf(fixture.encodeToByteArray())))

        assertEquals(
            ConnectorServerSentEvent(
                data = "one\n\nthree",
                event = "second",
                id = "new",
                retryMillis = 12,
            ),
            reader.readEvent(),
        )
        assertNull(reader.readEvent())
    }

    @Test
    fun acceptsLfCrLfAndCrLineEndingsAcrossChunkBoundaries() = runTest {
        val fixture = "data: lf\n\ndata: crlf\r\n\r\ndata: cr\r\r"
        val reader =
            ConnectorServerSentEventReader(
                chunkReader(
                    fixture.encodeToByteArray().map { byte -> byteArrayOf(byte) },
                ),
            )

        assertEquals("lf", reader.readEvent()?.data)
        assertEquals("crlf", reader.readEvent()?.data)
        assertEquals("cr", reader.readEvent()?.data)
        assertNull(reader.readEvent())
    }

    @Test
    fun ignoresInvalidRetryAndNullContainingIdWithoutDroppingEventData() = runTest {
        val invalidRetries =
            listOf(
                "-1",
                "1.5",
                "٢٥٠٠",
                "9223372036854775808",
                "86400001",
            )
        val fixture =
            buildString {
                invalidRetries.forEachIndexed { index, retry ->
                    append("id: ignored\u0000id\n")
                    append("retry: $retry\n")
                    append("data: event-$index\n\n")
                }
            }
        val reader = ConnectorServerSentEventReader(chunkReader(listOf(fixture.encodeToByteArray())))

        invalidRetries.indices.forEach { index ->
            assertEquals(
                ConnectorServerSentEvent(
                    data = "event-$index",
                    event = null,
                    id = null,
                    retryMillis = null,
                ),
                reader.readEvent(),
            )
        }
        assertNull(reader.readEvent())
    }

    @Test
    fun dispatchesOnlyAtBlankDelimiterAndDiscardsUnterminatedEndOfStream() = runTest {
        listOf(
            "data: truncated",
            "data: truncated\n",
            "data: first\ndata: second\n",
        ).forEach { fixture ->
            val reader =
                ConnectorServerSentEventReader(
                    chunkReader(listOf(fixture.encodeToByteArray())),
                )
            assertNull(reader.readEvent(), "dispatched unterminated fixture: $fixture")
        }

        val complete =
            ConnectorServerSentEventReader(
                chunkReader(listOf("data:\n\n".encodeToByteArray())),
            )
        assertEquals("", complete.readEvent()?.data)
        assertNull(complete.readEvent())
    }

    @Test
    fun malformedUtf8FailsCanonicallyAfterContentStartsAndThenTerminates() = runTest {
        val malformedFixtures =
            listOf(
                byteArrayOf(
                    'd'.code.toByte(),
                    'a'.code.toByte(),
                    't'.code.toByte(),
                    'a'.code.toByte(),
                    ':'.code.toByte(),
                    ' '.code.toByte(),
                    0xc3.toByte(),
                    0x28,
                    '\n'.code.toByte(),
                    '\n'.code.toByte(),
                ),
                byteArrayOf(
                    'd'.code.toByte(),
                    'a'.code.toByte(),
                    't'.code.toByte(),
                    'a'.code.toByte(),
                    ':'.code.toByte(),
                    ' '.code.toByte(),
                    0xc3.toByte(),
                ),
            )

        malformedFixtures.forEach { malformed ->
            val response =
                ConnectorTransportResponse(
                    statusCode = 200,
                    headers = emptyList(),
                    body = chunkReader(listOf(malformed)),
                )
            val reader = ConnectorServerSentEventReader(response.body)

            assertFalse(response.hasResponseContentStarted)
            val failure =
                assertFailsWith<UniversalAiException> {
                    reader.readEvent()
                }
            assertTrue(response.hasResponseContentStarted)
            assertEquals(UniversalAiErrorCategory.Transport, failure.error.category)
            assertEquals(UniversalAiErrorCode.TransportFailure, failure.error.code)
            assertEquals(MALFORMED_RESPONSE_STREAM_MESSAGE, failure.error.message)
            assertNull(failure.cause)
            assertNull(reader.readEvent())
        }
    }

    @Test
    fun oversizedLineOrEventDataFailsCanonicallyWithoutDispatchingPartialContent() = runTest {
        val fixtures =
            listOf(
                "data: " + "x".repeat(1_048_576) + "\n\n",
                "data: " + "x".repeat(524_288) + "\n" +
                    "data: " + "y".repeat(524_288) + "\n\n",
            )

        fixtures.forEach { fixture ->
            val reader =
                ConnectorServerSentEventReader(
                    chunkReader(listOf(fixture.encodeToByteArray())),
                )
            val failure =
                assertFailsWith<UniversalAiException> {
                    reader.readEvent()
                }

            assertEquals(MALFORMED_RESPONSE_STREAM_MESSAGE, failure.message)
            assertNull(reader.readEvent())
        }
    }

    @Test
    fun cancellationBeforeContentCancelsUnderlyingReadAndKeepsBoundaryClosed() = runTest {
        val cancellationObserved = CompletableDeferred<Unit>()
        val response =
            ConnectorTransportResponse(
                statusCode = 200,
                headers = emptyList(),
                body =
                    ConnectorTransportChunkReader {
                        try {
                            awaitCancellation()
                        } finally {
                            cancellationObserved.complete(Unit)
                        }
                    },
            )
        val reader = ConnectorServerSentEventReader(response.body)
        val operation = async { reader.readEvent() }

        runCurrent()
        operation.cancelAndJoin()

        assertTrue(cancellationObserved.isCompleted)
        assertFalse(response.hasResponseContentStarted)
        assertNull(reader.readEvent())
    }

    @Test
    fun cancellationDuringPartialEventTerminatesWithoutLateEmission() = runTest {
        var readCount = 0
        val body =
            ConnectorTransportChunkReader {
                when (readCount++) {
                    0 -> "data: partial".encodeToByteArray()
                    else -> awaitCancellation()
                }
            }
        val reader = ConnectorServerSentEventReader(body)
        val operation = async { reader.readEvent() }

        runCurrent()
        operation.cancelAndJoin()

        assertNull(reader.readEvent())
    }

    @Test
    fun cancellationBetweenEventsTerminatesWithoutAnotherEvent() = runTest {
        var readCount = 0
        val body =
            ConnectorTransportChunkReader {
                when (readCount++) {
                    0 -> "data: first\n\n".encodeToByteArray()
                    else -> awaitCancellation()
                }
            }
        val reader = ConnectorServerSentEventReader(body)

        assertEquals("first", reader.readEvent()?.data)
        val operation = async { reader.readEvent() }
        runCurrent()
        operation.cancelAndJoin()

        assertNull(reader.readEvent())
    }

    private fun singleByteChunks(value: String): List<ByteArray> =
        value.encodeToByteArray().map { byte -> byteArrayOf(byte) }

    private fun chunkReader(chunks: List<ByteArray>): ConnectorTransportChunkReader {
        var index = 0
        return ConnectorTransportChunkReader {
            chunks.getOrNull(index++)
        }
    }
}
