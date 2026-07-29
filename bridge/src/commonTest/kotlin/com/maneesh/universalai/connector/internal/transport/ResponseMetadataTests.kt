package com.maneesh.universalai.connector.internal.transport

import io.ktor.http.toHttpDate
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponseMetadataTests {
    @Test
    fun requestIdUsesCaseInsensitiveAllowlistAndDeterministicPrecedence() {
        val metadata =
            ConnectorResponseMetadataExtractor.extract(
                headers =
                    listOf(
                        ConnectorTransportHeader("X-Correlation-ID", " correlation "),
                        ConnectorTransportHeader("Request-Id", "request"),
                        ConnectorTransportHeader("x-request-ID", " preferred "),
                        ConnectorTransportHeader("x-request-id", "later"),
                    ),
                nowEpochMilliseconds = FIXED_NOW_MILLIS,
            )

        assertEquals("preferred", metadata.requestId)
    }

    @Test
    fun requestIdSkipsInvalidValuesAndRemainsAbsentWithoutAnAllowlistedField() {
        val metadata =
            ConnectorResponseMetadataExtractor.extract(
                headers =
                    listOf(
                        ConnectorTransportHeader("X-Request-Id", " \t"),
                        ConnectorTransportHeader("X-Request-Id", "bad\rvalue"),
                        ConnectorTransportHeader("X-Request-Id", "\uD800"),
                        ConnectorTransportHeader("X-Request-Id", "x".repeat(257)),
                        ConnectorTransportHeader("Request-ID", " fallback "),
                        ConnectorTransportHeader("Trace-Id", "not-allowlisted"),
                    ),
                nowEpochMilliseconds = FIXED_NOW_MILLIS,
            )

        assertEquals("fallback", metadata.requestId)
        assertNull(
            ConnectorResponseMetadataExtractor
                .extract(
                    headers = listOf(ConnectorTransportHeader("Trace-Id", "trace")),
                    nowEpochMilliseconds = FIXED_NOW_MILLIS,
                ).requestId,
        )
        assertEquals(
            "é".repeat(128),
            ConnectorResponseMetadataExtractor
                .extract(
                    headers =
                        listOf(
                            ConnectorTransportHeader("X-Request-Id", "é".repeat(128)),
                        ),
                    nowEpochMilliseconds = FIXED_NOW_MILLIS,
                ).requestId,
        )
        assertNull(
            ConnectorResponseMetadataExtractor
                .extract(
                    headers =
                        listOf(
                            ConnectorTransportHeader("X-Request-Id", "é".repeat(129)),
                        ),
                    nowEpochMilliseconds = FIXED_NOW_MILLIS,
                ).requestId,
        )
    }

    @Test
    fun retryAfterAcceptsBoundedDeltaSecondsAndFirstValidField() {
        val metadata =
            ConnectorResponseMetadataExtractor.extract(
                headers =
                    listOf(
                        ConnectorTransportHeader("Retry-After", "-1"),
                        ConnectorTransportHeader("retry-after", "invalid"),
                        ConnectorTransportHeader("retry-after", "٤٢"),
                        ConnectorTransportHeader("RETRY-AFTER", " 42 "),
                        ConnectorTransportHeader("retry-after", "7"),
                    ),
                nowEpochMilliseconds = FIXED_NOW_MILLIS,
            )

        assertEquals(42_000, metadata.retryAfterMillis)
        assertEquals(
            0,
            ConnectorResponseMetadataExtractor
                .extract(
                    headers = listOf(ConnectorTransportHeader("Retry-After", "0")),
                    nowEpochMilliseconds = FIXED_NOW_MILLIS,
                ).retryAfterMillis,
        )
        assertEquals(
            42_000,
            ConnectorResponseMetadataExtractor
                .extract(
                    headers =
                        listOf(
                            ConnectorTransportHeader(
                                "Retry-After",
                                " ".repeat(126) + "42",
                            ),
                        ),
                    nowEpochMilliseconds = FIXED_NOW_MILLIS,
                ).retryAfterMillis,
        )
    }

    @Test
    fun retryAfterAcceptsFutureHttpDateAndRejectsPastOrExcessiveDates() {
        val futureDate = GMTDate(FIXED_NOW_MILLIS + 30_000).toHttpDate()
        val pastDate = GMTDate(FIXED_NOW_MILLIS - 1_000).toHttpDate()
        val excessiveDate = GMTDate(FIXED_NOW_MILLIS + 86_401_000).toHttpDate()

        assertEquals(
            30_000,
            ConnectorResponseMetadataExtractor
                .extract(
                    headers = listOf(ConnectorTransportHeader("Retry-After", futureDate)),
                    nowEpochMilliseconds = FIXED_NOW_MILLIS,
                ).retryAfterMillis,
        )
        listOf(
            pastDate,
            excessiveDate,
            "not-a-date",
            Long.MAX_VALUE.toString(),
            "1".repeat(129),
            " ".repeat(129) + "42",
        ).forEach { value ->
            assertNull(
                ConnectorResponseMetadataExtractor
                    .extract(
                        headers = listOf(ConnectorTransportHeader("Retry-After", value)),
                        nowEpochMilliseconds = FIXED_NOW_MILLIS,
                    ).retryAfterMillis,
            )
        }
    }

    @Test
    fun responseExposesMetadataAndStartsContentAtFirstReturnedBodyByte() = runTest {
        var readIndex = 0
        val response =
            ConnectorTransportResponse(
                statusCode = 200,
                headers =
                    listOf(
                        ConnectorTransportHeader("X-Request-Id", "response-id"),
                        ConnectorTransportHeader("Retry-After", "9"),
                    ),
                body =
                    ConnectorTransportChunkReader {
                        when (readIndex++) {
                            0 -> ByteArray(0)
                            1 -> "content".encodeToByteArray()
                            else -> null
                        }
                    },
            )

        assertEquals("response-id", response.metadata.requestId)
        assertEquals(9_000, response.metadata.retryAfterMillis)
        assertFalse(response.hasResponseContentStarted)
        assertContentEquals("content".encodeToByteArray(), response.body.readChunk())
        assertTrue(response.hasResponseContentStarted)
        assertNull(response.body.readChunk())
        assertTrue(response.hasResponseContentStarted)
    }

    @Test
    fun bodyFailureBeforeReturningBytesDoesNotCrossContentBoundary() = runTest {
        val expected = IllegalStateException("body failed before returning content")
        val response =
            ConnectorTransportResponse(
                statusCode = 200,
                headers = emptyList(),
                body = ConnectorTransportChunkReader { throw expected },
            )

        val delivered =
            assertFailsWith<IllegalStateException> {
                response.body.readChunk()
            }

        assertEquals(expected, delivered)
        assertFalse(response.hasResponseContentStarted)
    }

    private companion object {
        const val FIXED_NOW_MILLIS: Long = 1_785_326_400_000
    }
}
