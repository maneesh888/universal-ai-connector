package com.maneesh.universalai.connector.internal.transport

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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
