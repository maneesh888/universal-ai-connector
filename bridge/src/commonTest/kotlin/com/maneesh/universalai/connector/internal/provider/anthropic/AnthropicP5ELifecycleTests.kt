package com.maneesh.universalai.connector.internal.provider.anthropic

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiConnectorConfiguration
import com.maneesh.universalai.connector.UniversalAiProviderConfiguration
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiGenerationParameters
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnthropicP5ELifecycleTests {
    @Test
    fun concurrentResponsesAndStreamsKeepRegistryCredentialsAndTranslationStateIsolated() = runTest {
        var credentialCalls = 0
        val transport = ConcurrentIsolationTransport(expectedRequestCount = 4)
        val connector =
            connector(transport) {
                credentialCalls += 1
                "$TEST_CREDENTIAL-$credentialCalls"
            }

        try {
            val firstResponse = async { connector.respond(request("response-one")) }
            val secondResponse = async { connector.respond(request("response-two")) }
            val firstStream = async { connector.stream(request("stream-one")).toList() }
            val secondStream = async { connector.stream(request("stream-two")).toList() }

            assertEquals(
                "response-one-result",
                firstResponse.await().outputs.single().text,
            )
            assertEquals(
                "response-two-result",
                secondResponse.await().outputs.single().text,
            )
            listOf(
                "stream-one-result" to firstStream.await(),
                "stream-two-result" to secondStream.await(),
            ).forEach { (expectedText, events) ->
                assertEquals(
                    (1L..events.size.toLong()).toList(),
                    events.map(UniversalAiStreamEvent::sequence),
                )
                assertEquals(expectedText, events.last().response?.outputs?.single()?.text)
                assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
            }
            assertEquals(4, credentialCalls)
            assertEquals(4, transport.maxConcurrentRequests)
            assertEquals(
                setOf(
                    "response-one" to false,
                    "response-two" to false,
                    "stream-one" to true,
                    "stream-two" to true,
                ),
                transport.observations.map { observation ->
                    observation.input to observation.streaming
                }.toSet(),
            )
            assertEquals(
                (1..4).mapTo(mutableSetOf()) { index ->
                    "$TEST_CREDENTIAL-$index"
                },
                transport.observations.mapTo(mutableSetOf(), RequestObservation::apiKey),
            )
        } finally {
            connector.close()
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
                            if (event.type == UniversalAiStreamEventType.OutputDelta) {
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
            assertEquals(UniversalAiStreamEventType.OutputDelta, delivered.last().type)
            assertFalse(delivered.any(UniversalAiStreamEvent::terminal))
            assertFalse(
                delivered.any { event ->
                    event.type == UniversalAiStreamEventType.OutputCompleted ||
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
                    content = ByteReadChannel(successfulLifecycleStream()),
                    status = HttpStatusCode.OK,
                    headers = eventStreamHeaders(),
                )
            }
        val connector =
            UniversalAiConnector(
                configuration =
                    UniversalAiConnectorConfiguration(
                        listOf(
                            UniversalAiProviderConfiguration(
                                providerId = ANTHROPIC_PROVIDER_ID,
                                baseUrl = "https://api.example.invalid/v1",
                                credentialSupplier = { TEST_CREDENTIAL },
                            ),
                        ),
                    ),
                httpEngine = engine,
            )

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
        transport: ConnectorTransport,
        credentialSupplier: () -> String,
    ): UniversalAiConnector {
        val providerConfiguration =
            UniversalAiProviderConfiguration(
                providerId = ANTHROPIC_PROVIDER_ID,
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
                        providerId = ANTHROPIC_PROVIDER_ID,
                        adapterFactory = { boundTransport ->
                            AnthropicMessagesAdapter(providerConfiguration, boundTransport)
                        },
                    ),
                ),
        )
    }

    private fun request(content: String): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = ANTHROPIC_PROVIDER_ID,
                    modelId = ModelId.of("requested-model"),
                ),
            input =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = content,
                    ),
                ),
            generation = UniversalAiGenerationParameters(maxOutputTokens = 64),
        )

    private companion object {
        val ANTHROPIC_PROVIDER_ID: ProviderId = ProviderId.of("anthropic")
        const val TEST_CREDENTIAL: String = "p5e-test-credential"
    }

    private data class RequestObservation(
        val input: String,
        val streaming: Boolean,
        val apiKey: String,
    )

    private class ConcurrentIsolationTransport(
        private val expectedRequestCount: Int,
    ) : ConnectorTransport {
        private val observationLock = Mutex()
        private val allRequestsStarted = CompletableDeferred<Unit>()
        private val mutableObservations = mutableListOf<RequestObservation>()

        val observations: List<RequestObservation>
            get() = mutableObservations.toList()

        var maxConcurrentRequests: Int = 0
            private set

        override suspend fun <Result> execute(
            request: ConnectorTransportRequest,
            consumeResponse: suspend (ConnectorTransportResponse) -> Result,
        ): Result {
            val requestBody =
                Json
                    .parseToJsonElement(checkNotNull(request.body).decodeToString())
                    .jsonObject
            val observation =
                RequestObservation(
                    input =
                        requestBody
                            .getValue("messages")
                            .jsonArray
                            .single()
                            .jsonObject
                            .getValue("content")
                            .jsonArray
                            .single()
                            .jsonObject
                            .getValue("text")
                            .jsonPrimitive
                            .content,
                    streaming = requestBody["stream"]?.jsonPrimitive?.boolean == true,
                    apiKey =
                        request.headers
                            .single { header ->
                                header.name.equals("x-api-key", ignoreCase = true)
                            }.value,
                )
            observationLock.withLock {
                mutableObservations += observation
                maxConcurrentRequests = maxOf(maxConcurrentRequests, mutableObservations.size)
                if (mutableObservations.size == expectedRequestCount) {
                    allRequestsStarted.complete(Unit)
                }
            }
            allRequestsStarted.await()

            val responseBody =
                if (observation.streaming) {
                    successfulLifecycleStream(text = "${observation.input}-result")
                } else {
                    successfulLifecycleResponse(text = "${observation.input}-result")
                }
            var responseDelivered = false
            return consumeResponse(
                ConnectorTransportResponse(
                    statusCode = 200,
                    headers =
                        if (observation.streaming) {
                            listOf(
                                ConnectorTransportHeader(
                                    name = "content-type",
                                    value = "text/event-stream; charset=utf-8",
                                ),
                            )
                        } else {
                            emptyList()
                        },
                    body =
                        ConnectorTransportChunkReader {
                            if (responseDelivered) {
                                null
                            } else {
                                responseDelivered = true
                                responseBody.encodeToByteArray()
                            }
                        },
                ),
            )
        }

        override fun close() = Unit
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
                                        firstLifecycleStreamRecord().encodeToByteArray()
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
                                    lifecycleStreamBeforeTerminal().encodeToByteArray()
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

private fun ByteArray.isStreamingRequest(): Boolean =
    Json
        .parseToJsonElement(decodeToString())
        .jsonObject["stream"]
        ?.jsonPrimitive
        ?.boolean == true

private fun successfulLifecycleResponse(text: String): String =
    """
    {
      "id":"msg_non_stream",
      "type":"message",
      "role":"assistant",
      "content":[{"type":"text","text":${JsonPrimitive(text)}}],
      "model":"resolved-model",
      "stop_reason":"end_turn",
      "stop_sequence":null,
      "usage":{"input_tokens":1,"output_tokens":1}
    }
    """.trimIndent()

private fun successfulLifecycleStream(text: String = "hello"): String =
    lifecycleMessageStartEvent() +
        lifecycleContentBlockStartEvent() +
        lifecycleContentBlockDeltaEvent(text) +
        lifecycleContentBlockStopEvent() +
        lifecycleMessageDeltaEvent() +
        lifecycleMessageStopEvent()

private fun lifecycleStreamBeforeTerminal(): String =
    lifecycleMessageStartEvent() +
        lifecycleContentBlockStartEvent() +
        lifecycleContentBlockDeltaEvent("partial") +
        lifecycleContentBlockStopEvent()

private fun firstLifecycleStreamRecord(): String = lifecycleMessageStartEvent()

private fun lifecycleMessageStartEvent(): String =
    lifecycleSse(
        "message_start",
        """
        {
          "type":"message_start",
          "message":{
            "id":"msg_stream",
            "type":"message",
            "role":"assistant",
            "content":[],
            "model":"resolved-model",
            "stop_reason":null,
            "stop_sequence":null,
            "usage":{"input_tokens":2,"output_tokens":1}
          }
        }
        """,
    )

private fun lifecycleContentBlockStartEvent(): String =
    lifecycleSse(
        "content_block_start",
        """
        {
          "type":"content_block_start",
          "index":0,
          "content_block":{"type":"text","text":""}
        }
        """,
    )

private fun lifecycleContentBlockDeltaEvent(text: String): String =
    lifecycleSse(
        "content_block_delta",
        """
        {
          "type":"content_block_delta",
          "index":0,
          "delta":{"type":"text_delta","text":${JsonPrimitive(text)}}
        }
        """,
    )

private fun lifecycleContentBlockStopEvent(): String =
    lifecycleSse(
        "content_block_stop",
        """{"type":"content_block_stop","index":0}""",
    )

private fun lifecycleMessageDeltaEvent(): String =
    lifecycleSse(
        "message_delta",
        """
        {
          "type":"message_delta",
          "delta":{"stop_reason":"end_turn","stop_sequence":null},
          "usage":{"output_tokens":3}
        }
        """,
    )

private fun lifecycleMessageStopEvent(): String =
    lifecycleSse(
        "message_stop",
        """{"type":"message_stop"}""",
    )

private fun lifecycleSse(
    eventName: String,
    json: String,
): String =
    buildString {
        append("event: ")
        append(eventName)
        append('\n')
        append("data: ")
        append(Json.parseToJsonElement(json).toString())
        append("\n\n")
    }

private fun eventStreamHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, "text/event-stream; charset=utf-8")
    }
