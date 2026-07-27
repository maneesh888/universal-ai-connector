package com.maneesh.universalai.connector.internal.transport

import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class KtorConnectorTransportTests {
    @Test
    fun forwardsProviderNeutralRequestAndExposesRawResponseInsideCallback() = runTest {
        val requestBody = "request-body".encodeToByteArray()
        val responseBody = ByteArray(20_000) { index -> (index % 251).toByte() }
        val timeouts =
            ConnectorTransportTimeouts(
                connectTimeoutMillis = 1_234,
                requestTimeoutMillis = 5_678,
            )
        val engine =
            MockEngine { request ->
                assertEquals(HttpMethod.Patch, request.method)
                assertEquals(
                    "https://example.invalid/base/path?query=value",
                    request.url.toString(),
                )
                assertEquals(listOf("first", "second"), request.headers.getAll("X-Repeated"))
                val configuredTimeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
                assertEquals(timeouts.connectTimeoutMillis, configuredTimeouts?.connectTimeoutMillis)
                assertEquals(timeouts.requestTimeoutMillis, configuredTimeouts?.requestTimeoutMillis)
                assertContentEquals(
                    requestBody,
                    (request.body as OutgoingContent.ByteArrayContent).bytes(),
                )
                respond(
                    content = ByteReadChannel(responseBody),
                    status = HttpStatusCode.Accepted,
                    headers =
                        Headers.build {
                            append("X-Response", "one")
                            append("X-Response", "two")
                        },
                )
            }
        val transport = createKtorTransport(engine, timeouts)

        try {
            val received =
                transport.execute(
                    ConnectorTransportRequest(
                        method = "PATCH",
                        baseUrl = ConnectorBaseUrl.parse("https://example.invalid/base"),
                        endpoint = "path?query=value",
                        adapterHeaders =
                            listOf(
                                ConnectorTransportHeader("X-Repeated", "first"),
                                ConnectorTransportHeader("X-Repeated", "second"),
                            ),
                        body = requestBody,
                    ),
                ) { response ->
                    assertEquals(202, response.statusCode)
                    assertEquals(
                        listOf("one", "two"),
                        response.headers
                            .filter { header -> header.name.equals("X-Response", ignoreCase = true) }
                            .map(ConnectorTransportHeader::value),
                    )
                    readAll(response.body)
                }

            assertContentEquals(responseBody, received)
            assertEquals(1, engine.requestHistory.size)
        } finally {
            transport.close()
            engine.close()
        }
    }

    @Test
    fun callbackFailurePropagatesAndDoesNotPreventAFollowingExecution() = runTest {
        val engine =
            MockEngine {
                respond(
                    content = ByteReadChannel("response"),
                    status = HttpStatusCode.OK,
                )
            }
        val transport = createKtorTransport(engine)
        val expected = IllegalStateException("consumer failed")

        try {
            val delivered =
                assertFailsWith<IllegalStateException> {
                    transport.execute(
                        ConnectorTransportRequest(
                            method = "GET",
                            baseUrl = ConnectorBaseUrl.parse("https://example.invalid"),
                            endpoint = "first",
                        ),
                    ) {
                        throw expected
                    }
                }
            assertSame(expected, delivered)

            assertEquals(
                "response",
                transport
                    .execute(
                        ConnectorTransportRequest(
                            method = "GET",
                            baseUrl = ConnectorBaseUrl.parse("https://example.invalid"),
                            endpoint = "second",
                        ),
                    ) { response ->
                        readAll(response.body).decodeToString()
                    },
            )
            assertEquals(2, engine.requestHistory.size)
        } finally {
            transport.close()
            engine.close()
        }
    }

    @Test
    fun requestTimeoutIsEnforcedAndMapsToCanonicalTransportFailure() = runTest {
        val engine =
            MockEngine {
                awaitCancellation()
            }
        val transport =
            createKtorTransport(
                engine,
                ConnectorTransportTimeouts(
                    connectTimeoutMillis = 1_000,
                    requestTimeoutMillis = 10,
                ),
            )

        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    transport.execute(defaultRequest()) {
                        error("A timed-out request must not deliver a response.")
                    }
                }

            assertTransportFailure(
                failure = failure,
                code = UniversalAiErrorCode.RequestTimeout,
                message = REQUEST_TIMEOUT_MESSAGE,
            )
        } finally {
            transport.close()
            engine.close()
        }
    }

    @Test
    fun connectAndRequestTimeoutTypesMapWithoutLeakingSourceMessages() = runTest {
        val sourceMessages =
            listOf(
                "fake-connect-sensitive-detail",
                "fake-request-sensitive-detail",
            )
        val failures =
            listOf(
                ConnectTimeoutException(sourceMessages[0]),
                HttpRequestTimeoutException(
                    url = "https://fake-request-sensitive-detail.invalid",
                    timeoutMillis = 25,
                ),
            )

        failures.forEachIndexed { index, sourceFailure ->
            val engine = MockEngine { throw sourceFailure }
            val transport = createKtorTransport(engine)
            try {
                val delivered =
                    assertFailsWith<UniversalAiException> {
                        transport.execute(defaultRequest()) {
                            error("No response is expected.")
                        }
                    }
                val expectedCode =
                    if (index == 0) {
                        UniversalAiErrorCode.ConnectionTimeout
                    } else {
                        UniversalAiErrorCode.RequestTimeout
                    }
                val expectedMessage =
                    if (index == 0) {
                        CONNECTION_TIMEOUT_MESSAGE
                    } else {
                        REQUEST_TIMEOUT_MESSAGE
                    }
                assertTransportFailure(delivered, expectedCode, expectedMessage)
                assertFalse(delivered.toString().contains(sourceMessages[index]))
            } finally {
                transport.close()
                engine.close()
            }
        }
    }

    @Test
    fun preResponseIoFailureMapsToFixedCanonicalTransportFailure() = runTest {
        val sourceMessage = "fake-io-sensitive-detail"
        val engine = MockEngine { throw IOException(sourceMessage) }
        val transport = createKtorTransport(engine)

        try {
            val delivered =
                assertFailsWith<UniversalAiException> {
                    transport.execute(defaultRequest()) {
                        error("No response is expected.")
                    }
                }

            assertTransportFailure(
                failure = delivered,
                code = UniversalAiErrorCode.TransportFailure,
                message = TRANSPORT_FAILURE_MESSAGE,
            )
            assertFalse(delivered.toString().contains(sourceMessage))
        } finally {
            transport.close()
            engine.close()
        }
    }

    @Test
    fun callerCancellationRemainsCancellation() = runTest {
        val cancellation = CancellationException("caller stopped")
        val engine = MockEngine { throw cancellation }
        val transport = createKtorTransport(engine)

        try {
            val delivered =
                assertFailsWith<CancellationException> {
                    transport.execute(defaultRequest()) {
                        error("No response is expected.")
                    }
                }

            assertEquals(cancellation.message, delivered.message)
        } finally {
            transport.close()
            engine.close()
        }
    }

    private fun defaultRequest(): ConnectorTransportRequest =
        ConnectorTransportRequest(
            method = "GET",
            baseUrl = ConnectorBaseUrl.parse("https://example.invalid"),
            endpoint = "resource",
        )

    private fun assertTransportFailure(
        failure: UniversalAiException,
        code: UniversalAiErrorCode,
        message: String,
    ) {
        assertEquals(UniversalAiErrorCategory.Transport, failure.error.category)
        assertEquals(code, failure.error.code)
        assertEquals(message, failure.error.message)
        assertEquals(message, failure.message)
        assertEquals(null, failure.cause)
    }

    private suspend fun readAll(reader: ConnectorTransportChunkReader): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var size = 0
        while (true) {
            val chunk = reader.readChunk() ?: break
            chunks += chunk
            size += chunk.size
        }
        val result = ByteArray(size)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(
                destination = result,
                destinationOffset = offset,
            )
            offset += chunk.size
        }
        return result
    }
}
