package com.maneesh.universalai.connector.internal.provider.openai

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiConnectorConfiguration
import com.maneesh.universalai.connector.UniversalAiProviderConfiguration
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.internal.ConnectorResourceOwnership
import com.maneesh.universalai.connector.internal.DeterministicConnectorEngine
import com.maneesh.universalai.connector.internal.provider.ProviderRegistration
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportChunkReader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportHeader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportRequest
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiP4ELifecycleTests {
    @Test
    fun concurrentResponsesAndStreamsKeepRegistryCredentialsAndTranslationStateIsolated() = runTest {
        var credentialCalls = 0
        val engine =
            MockEngine { request ->
                if (request.body.isStreamingRequest()) {
                    respond(
                        content = ByteReadChannel(successfulTextStream(text = "stream-result")),
                        status = HttpStatusCode.OK,
                        headers = eventStreamHeaders(),
                    )
                } else {
                    respond(
                        content = ByteReadChannel(successfulResponse("response-result")),
                        status = HttpStatusCode.OK,
                    )
                }
            }
        val connector =
            connector(engine) {
                credentialCalls += 1
                TEST_CREDENTIAL
            }

        try {
            val firstResponse = async { connector.respond(request("response-one")) }
            val secondResponse = async { connector.respond(request("response-two")) }
            val firstStream = async { connector.stream(request("stream-one")).toList() }
            val secondStream = async { connector.stream(request("stream-two")).toList() }

            assertEquals(
                listOf("response-result", "response-result"),
                listOf(firstResponse.await(), secondResponse.await()).map { response ->
                    response.outputs.single().text
                },
            )
            listOf(firstStream.await(), secondStream.await()).forEach { events ->
                assertEquals((1L..7L).toList(), events.map(UniversalAiStreamEvent::sequence))
                assertEquals("stream-result", events.last().response?.outputs?.single()?.text)
                assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
            }
            assertEquals(4, credentialCalls)
            assertEquals(4, engine.requestHistory.size)
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun concurrentCloseCancelsPendingResponseAndActiveStreamAndReleasesBody() = runTest {
        var credentialCalls = 0
        val pendingResponseStarted = CompletableDeferred<Unit>()
        val pendingResponseCancelled = CompletableDeferred<Unit>()
        val streamResponseStarted = CompletableDeferred<Unit>()
        val firstStreamEventDelivered = CompletableDeferred<Unit>()
        val streamBodyCancelled = CompletableDeferred<Unit>()
        val transport =
            ConcurrentLifecycleTransport(
                pendingResponseStarted = pendingResponseStarted,
                pendingResponseCancelled = pendingResponseCancelled,
                streamResponseStarted = streamResponseStarted,
                streamBodyCancelled = streamBodyCancelled,
            )
        val connector =
            connector(transport) {
                credentialCalls += 1
                TEST_CREDENTIAL
            }
        val delivered = mutableListOf<UniversalAiStreamEvent>()

        try {
            val pendingResponse =
                backgroundScope.async {
                    connector.respond(request("pending-response"))
                }
            val activeStream =
                backgroundScope.async {
                    connector
                        .stream(request("active-stream"))
                        .collect { event ->
                            delivered += event
                            firstStreamEventDelivered.complete(Unit)
                        }
                }

            pendingResponseStarted.await()
            streamResponseStarted.await()
            firstStreamEventDelivered.await()

            List(16) {
                async(Dispatchers.Default) {
                    connector.close()
                }
            }.awaitAll()

            assertFailsWith<CancellationException> {
                pendingResponse.await()
            }
            assertFailsWith<CancellationException> {
                activeStream.await()
            }
            withTimeout(5_000) {
                pendingResponseCancelled.await()
                streamBodyCancelled.await()
            }
            assertEquals(
                listOf(UniversalAiStreamEventType.ResponseStarted),
                delivered.map(UniversalAiStreamEvent::type),
            )
            assertTrue(delivered.none(UniversalAiStreamEvent::terminal))
            assertEquals(2, credentialCalls)
            assertEquals(2, transport.executeCalls)
            assertEquals(1, transport.closeCalls)

            val closedFailure =
                assertFailsWith<UniversalAiException> {
                    connector.respond(request("after-close"))
                }
            assertEquals(UniversalAiErrorCategory.Validation, closedFailure.error.category)
            assertEquals(UniversalAiErrorCode.InvalidRequest, closedFailure.error.code)
            assertEquals(2, credentialCalls)
        } finally {
            connector.close()
        }
    }

    @Test
    fun connectorCloseBeforeProviderTerminalSuppressesTerminalAndReleasesBody() = runTest {
        val bodyCancelled = CompletableDeferred<Unit>()
        val transport = BlockingBeforeTerminalTransport(bodyCancelled)
        val connector = connector(transport) { TEST_CREDENTIAL }
        val delivered = mutableListOf<UniversalAiStreamEvent>()

        try {
            val operation =
                async {
                    connector
                        .stream(request("terminal-close-race"))
                        .onEach { event ->
                            delivered += event
                            if (event.type == UniversalAiStreamEventType.OutputCompleted) {
                                connector.close()
                            }
                        }.collect()
                }

            assertFailsWith<CancellationException> {
                operation.await()
            }
            withTimeout(5_000) {
                bodyCancelled.await()
            }
            assertEquals(UniversalAiStreamEventType.OutputCompleted, delivered.last().type)
            assertFalse(delivered.any(UniversalAiStreamEvent::terminal))
            assertFalse(
                delivered.any { event ->
                    event.type == UniversalAiStreamEventType.UsageUpdated
                },
            )
            assertEquals(1, transport.closeCalls)
        } finally {
            connector.close()
        }
    }

    @Test
    fun deliveredCanonicalTerminalWinsConcurrentConnectorClose() = runTest {
        val engine =
            MockEngine {
                respond(
                    content = ByteReadChannel(successfulTextStream()),
                    status = HttpStatusCode.OK,
                    headers = eventStreamHeaders(),
                )
            }
        val connector = connector(engine) { TEST_CREDENTIAL }

        try {
            val events =
                connector
                    .stream(request("delivered-terminal"))
                    .onEach { event ->
                        if (event.terminal) {
                            connector.close()
                        }
                    }.toList()

            assertEquals(UniversalAiStreamEventType.ResponseCompleted, events.last().type)
            assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
        } finally {
            connector.close()
            engine.close()
        }
    }

    private fun connector(
        engine: MockEngine,
        credentialSupplier: () -> String,
    ): UniversalAiConnector =
        UniversalAiConnector(
            configuration =
                UniversalAiConnectorConfiguration(
                    listOf(
                        UniversalAiProviderConfiguration(
                            providerId = OPENAI_PROVIDER_ID,
                            baseUrl = "https://api.example.invalid/v1",
                            credentialSupplier = credentialSupplier,
                        ),
                    ),
                ),
            httpEngine = engine,
        )

    private fun connector(
        transport: ConnectorTransport,
        credentialSupplier: () -> String,
    ): UniversalAiConnector {
        val providerConfiguration =
            UniversalAiProviderConfiguration(
                providerId = OPENAI_PROVIDER_ID,
                baseUrl = "https://api.example.invalid/v1",
                credentialSupplier = credentialSupplier,
            )
        return UniversalAiConnector.createForTesting(
            engineFactory = ::DeterministicConnectorEngine,
            transport = transport,
            ownership = ConnectorResourceOwnership.Owned,
            providerRegistrations =
                listOf(
                    ProviderRegistration(
                        providerId = OPENAI_PROVIDER_ID,
                        adapterFactory = { boundTransport ->
                            OpenAiResponsesAdapter(providerConfiguration, boundTransport)
                        },
                    ),
                ),
        )
    }

    private fun request(content: String): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = OPENAI_PROVIDER_ID,
                    modelId = ModelId.of("requested-model"),
                ),
            input =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = content,
                    ),
                ),
        )

    private companion object {
        val OPENAI_PROVIDER_ID: ProviderId = ProviderId.of("openai")
        const val TEST_CREDENTIAL: String = "p4e-test-credential"
    }

    private class ConcurrentLifecycleTransport(
        private val pendingResponseStarted: CompletableDeferred<Unit>,
        private val pendingResponseCancelled: CompletableDeferred<Unit>,
        private val streamResponseStarted: CompletableDeferred<Unit>,
        private val streamBodyCancelled: CompletableDeferred<Unit>,
    ) : ConnectorTransport {
        var executeCalls: Int = 0
            private set
        var closeCalls: Int = 0
            private set

        override suspend fun <Result> execute(
            request: ConnectorTransportRequest,
            consumeResponse: suspend (ConnectorTransportResponse) -> Result,
        ): Result {
            executeCalls += 1
            return if (request.body?.isStreamingRequest() == true) {
                streamResponseStarted.complete(Unit)
                var deliveredFirstRecord = false
                try {
                    consumeResponse(
                        ConnectorTransportResponse(
                            statusCode = 200,
                            headers =
                                listOf(
                                    ConnectorTransportHeader(
                                        name = "content-type",
                                        value = "text/event-stream; charset=utf-8",
                                    ),
                                ),
                            body =
                                ConnectorTransportChunkReader {
                                    if (!deliveredFirstRecord) {
                                        deliveredFirstRecord = true
                                        firstStreamRecord().encodeToByteArray()
                                    } else {
                                        awaitCancellation()
                                    }
                                },
                        ),
                    )
                } finally {
                    streamBodyCancelled.complete(Unit)
                }
            } else {
                pendingResponseStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    pendingResponseCancelled.complete(Unit)
                }
            }
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private class BlockingBeforeTerminalTransport(
        private val bodyCancelled: CompletableDeferred<Unit>,
    ) : ConnectorTransport {
        var closeCalls: Int = 0
            private set

        override suspend fun <Result> execute(
            request: ConnectorTransportRequest,
            consumeResponse: suspend (ConnectorTransportResponse) -> Result,
        ): Result {
            check(request.body?.isStreamingRequest() == true)
            var deliveredContent = false
            return try {
                consumeResponse(
                    ConnectorTransportResponse(
                        statusCode = 200,
                        headers =
                            listOf(
                                ConnectorTransportHeader(
                                    name = "content-type",
                                    value = "text/event-stream; charset=utf-8",
                                ),
                            ),
                        body =
                            ConnectorTransportChunkReader {
                                if (!deliveredContent) {
                                    deliveredContent = true
                                    successfulStreamBeforeTerminal().encodeToByteArray()
                                } else {
                                    awaitCancellation()
                                }
                            },
                    ),
                )
            } finally {
                bodyCancelled.complete(Unit)
            }
        }

        override fun close() {
            closeCalls += 1
        }
    }
}

private fun OutgoingContent.isStreamingRequest(): Boolean =
    Json
        .parseToJsonElement((this as OutgoingContent.ByteArrayContent).bytes().decodeToString())
        .jsonObject["stream"]
        ?.jsonPrimitive
        ?.boolean == true

private fun ByteArray.isStreamingRequest(): Boolean =
    Json
        .parseToJsonElement(decodeToString())
        .jsonObject["stream"]
        ?.jsonPrimitive
        ?.boolean == true

private fun successfulResponse(text: String): String =
    """
    {
      "id":"resp_non_stream",
      "object":"response",
      "status":"completed",
      "model":"resolved-model",
      "output":[
        {
          "id":"message_0",
          "type":"message",
          "status":"completed",
          "role":"assistant",
          "content":[{"type":"output_text","text":"$text"}]
        }
      ],
      "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2},
      "error":null,
      "incomplete_details":null
    }
    """.trimIndent()

private fun eventStreamHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, "text/event-stream; charset=utf-8")
    }

private fun firstStreamRecord(): String =
    successfulTextStream().substringBefore("\n\n") + "\n\n"

private fun successfulStreamBeforeTerminal(): String {
    val records =
        successfulTextStream()
            .split("\n\n")
            .filter(String::isNotEmpty)
    check(records.size > 1)
    return records.dropLast(1).joinToString(separator = "\n\n", postfix = "\n\n")
}
