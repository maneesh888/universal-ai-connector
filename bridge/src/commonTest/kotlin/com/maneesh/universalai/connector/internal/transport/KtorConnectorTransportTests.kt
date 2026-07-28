package com.maneesh.universalai.connector.internal.transport

import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KtorConnectorTransportTests {
    @Test
    fun forwardsProviderNeutralRequestAndExposesRawResponseInsideCallback() = runTest {
        val requestBody = "request-body".encodeToByteArray()
        val responseBody = ByteArray(20_000) { index -> (index % 251).toByte() }
        val engine =
            MockEngine { request ->
                assertEquals(HttpMethod.Patch, request.method)
                assertEquals(
                    "https://example.invalid/base/path?query=value",
                    request.url.toString(),
                )
                assertEquals(listOf("first", "second"), request.headers.getAll("X-Repeated"))
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
        val transport = createKtorTransport(engine)

        try {
            val received =
                transport.execute(
                    ConnectorTransportRequest(
                        method = "PATCH",
                        url = "https://example.invalid/base/path?query=value",
                        headers =
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
                            url = "https://example.invalid/first",
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
                            url = "https://example.invalid/second",
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
    fun rejectsHeaderInjectionBeforeCallingEngine() = runTest {
        val secret = "injected-header-secret"
        val engine =
            MockEngine {
                error("The engine must not receive an invalid header.")
            }
        val transport = createKtorTransport(engine)

        try {
            val failure =
                assertFailsWith<ConnectorTransportPolicyException> {
                    transport.execute(
                        ConnectorTransportRequest(
                            method = "GET",
                            url = "https://example.invalid/headers",
                            headers =
                                listOf(
                                    ConnectorTransportHeader(
                                        name = "X-Test",
                                        value = "safe\r\nAuthorization: $secret",
                                    ),
                                ),
                        ),
                    ) { response ->
                        readAll(response.body)
                    }
                }

            assertEquals(
                ConnectorTransportPolicyViolation.InvalidHeaderValue,
                failure.violation,
            )
            assertFalse(failure.message.orEmpty().contains(secret))
            assertTrue(engine.requestHistory.isEmpty())
        } finally {
            transport.close()
            engine.close()
        }
    }

    @Test
    fun requestTimeoutMapsToStableCanonicalTransportError() = runTest {
        val engine =
            MockEngine {
                delay(5_000)
                respond("too late")
            }
        val transport = createKtorTransport(engine)

        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    transport.execute(
                        ConnectorTransportRequest(
                            method = "GET",
                            url = "https://example.invalid/timeout",
                            timeouts =
                                ConnectorTransportTimeouts(
                                    connectTimeoutMillis = 10,
                                    requestTimeoutMillis = 25,
                                ),
                        ),
                    ) { response ->
                        readAll(response.body)
                    }
                }

            assertEquals(UniversalAiErrorCategory.Transport, failure.error.category)
            assertEquals("request_timeout", failure.error.code.rawValue)
            assertEquals("The HTTP request timed out.", failure.error.message)
        } finally {
            transport.close()
            engine.close()
        }
    }

    @Test
    fun connectionTimeoutMapsWithoutLeakingEngineDiagnostics() = runTest {
        val secret = "engine-timeout-secret"
        val engine =
            MockEngine {
                throw ConnectTimeoutException(secret, IllegalStateException(secret))
            }
        val transport = createKtorTransport(engine)

        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    transport.execute(
                        ConnectorTransportRequest(
                            method = "GET",
                            url = "https://example.invalid/timeout",
                        ),
                    ) { response ->
                        readAll(response.body)
                    }
                }

            assertEquals(UniversalAiErrorCategory.Transport, failure.error.category)
            assertEquals("connection_timeout", failure.error.code.rawValue)
            assertEquals("The HTTP connection timed out.", failure.error.message)
            assertEquals(null, failure.cause)
            assertEquals(false, failure.message.orEmpty().contains(secret))
        } finally {
            transport.close()
            engine.close()
        }
    }

    @Test
    fun callerCancellationRemainsCancellation() = runTest {
        val started = CompletableDeferred<Unit>()
        val engine =
            MockEngine {
                started.complete(Unit)
                awaitCancellation()
            }
        val transport = createKtorTransport(engine)

        try {
            val request =
                async {
                    transport.execute(
                        ConnectorTransportRequest(
                            method = "GET",
                            url = "https://example.invalid/cancel",
                        ),
                    ) { response ->
                        readAll(response.body)
                    }
                }
            started.await()
            request.cancelAndJoin()

            assertTrue(request.isCancelled)
            assertFailsWith<CancellationException> {
                request.await()
            }
        } finally {
            transport.close()
            engine.close()
        }
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
