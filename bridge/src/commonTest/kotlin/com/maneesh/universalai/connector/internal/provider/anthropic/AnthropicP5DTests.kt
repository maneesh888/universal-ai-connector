package com.maneesh.universalai.connector.internal.provider.anthropic

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiConnectorConfiguration
import com.maneesh.universalai.connector.UniversalAiProviderConfiguration
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.StructuredOutputSchema
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiGenerationParameters
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiOutputKind
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormat
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.internal.transport.ConnectorResponseMetadata
import com.maneesh.universalai.connector.internal.transport.ConnectorServerSentEvent
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportChunkReader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportHeader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportRequest
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportResponse
import com.maneesh.universalai.connector.internal.transport.MALFORMED_RESPONSE_STREAM_MESSAGE
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnthropicP5DTests {
    @Test
    fun translatesOrderedTextBlocksUsageTerminalAndStopsBeforeLateRecords(): Unit = runTest {
        var bodyReads = 0
        val stream =
            successfulAnthropicStream(
                blocks = listOf(listOf("Hé"), listOf("llo")),
                includePing = true,
                includeUnknownOptional = true,
                includeLateRecords = true,
                stopReason = "stop_sequence",
                stopSequence = "DONE",
            )
        val transport =
            ReaderTransport(
                ConnectorTransportChunkReader {
                    bodyReads += 1
                    when (bodyReads) {
                        1 -> stream.encodeToByteArray()
                        else -> error("The adapter read after the authoritative provider terminal.")
                    }
                },
            )
        val adapter = adapter(transport)

        try {
            val events = adapter.stream(request(stopSequences = listOf("DONE"))).toList()

            assertEquals(1, bodyReads)
            assertEquals((1L..7L).toList(), events.map(UniversalAiStreamEvent::sequence))
            assertEquals(
                listOf(
                    UniversalAiStreamEventType.ResponseStarted,
                    UniversalAiStreamEventType.OutputStarted,
                    UniversalAiStreamEventType.OutputDelta,
                    UniversalAiStreamEventType.OutputDelta,
                    UniversalAiStreamEventType.OutputCompleted,
                    UniversalAiStreamEventType.UsageUpdated,
                    UniversalAiStreamEventType.ResponseCompleted,
                ),
                events.map(UniversalAiStreamEvent::type),
            )
            assertEquals(listOf("Hé", "llo"), events.mapNotNull(UniversalAiStreamEvent::delta))
            assertEquals("msg_stream", events.first().responseId.rawValue)
            assertEquals("req_stream", events.first().requestId?.rawValue)
            assertEquals("Héllo", events[4].output?.text)
            assertEquals(8L, events[5].usage?.totalTokens)
            assertEquals("Héllo", events.last().response?.outputs?.single()?.text)
            assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
            assertTrue(events.last().terminal)
        } finally {
            transport.close()
        }
    }

    @Test
    fun requestsStreamingAndAcceptsArbitraryChunksCommentsCrLfAndSplitUtf8(): Unit = runTest {
        val engine =
            MockEngine { request ->
                val body =
                    JSON.parseToJsonElement(
                        request.body.p5dBodyBytes().decodeToString(),
                    ) as JsonObject
                assertTrue((body["stream"] as JsonPrimitive).boolean)
                assertEquals("text/event-stream", request.headers[HttpHeaders.Accept])
                respond(
                    content =
                        ByteReadChannel(
                            successfulAnthropicStream(
                                blocks = listOf(listOf("Hél", "lo")),
                                lineEnding = "\r\n",
                                includeComment = true,
                            ),
                        ),
                    status = HttpStatusCode.OK,
                    headers = eventStreamHeaders(),
                )
            }
        val connector = connector(engine)

        try {
            val events = connector.stream(request()).toList()

            assertEquals("Héllo", events.last().response?.outputs?.single()?.text)
            assertEquals(UniversalAiStreamEventType.ResponseCompleted, events.last().type)
        } finally {
            connector.close()
            engine.close()
        }

        val bytes =
            successfulAnthropicStream(
                blocks = listOf(listOf("Héllo")),
                lineEnding = "\r\n",
                includeComment = true,
            ).encodeToByteArray()
        var byteIndex = 0
        val chunkedConnector =
            connector(
                ReaderTransport(
                    ConnectorTransportChunkReader {
                        if (byteIndex < bytes.size) {
                            byteArrayOf(bytes[byteIndex++])
                        } else {
                            null
                        }
                    },
                ),
            )
        try {
            assertEquals(
                "Héllo",
                chunkedConnector.stream(request()).toList().last().response?.outputs?.single()?.text,
            )
        } finally {
            chunkedConnector.close()
        }
    }

    @Test
    fun translatesStructuredOutputOnlyAfterExactFinalValidation(): Unit = runTest {
        val schema =
            StructuredOutputSchema.parse(
                """
                {
                  "type":"object",
                  "properties":{"answer":{"type":"string"}},
                  "required":["answer"],
                  "additionalProperties":false
                }
                """.trimIndent(),
            )
        val engine =
            MockEngine {
                respond(
                    content =
                        ByteReadChannel(
                            successfulAnthropicStream(
                                blocks = listOf(listOf("{\"answer\":", "\"ready\"}")),
                            ),
                        ),
                    status = HttpStatusCode.OK,
                    headers = eventStreamHeaders(),
                )
            }
        val connector = connector(engine)

        try {
            val events =
                connector
                    .stream(request(UniversalAiResponseFormat.jsonSchema(schema)))
                    .toList()
            val deltas = events.filter { event -> event.type == UniversalAiStreamEventType.OutputDelta }
            val output =
                assertNotNull(
                    events.single { event ->
                        event.type == UniversalAiStreamEventType.OutputCompleted
                    }.output,
                )

            assertEquals(listOf("{\"answer\":\"ready\"}"), deltas.mapNotNull { it.delta })
            assertEquals(UniversalAiOutputKind.StructuredJson, output.kind)
            assertEquals("{\"answer\":\"ready\"}", output.structuredJson?.toJson())
            assertEquals(output, events.last().response?.outputs?.single())
        } finally {
            connector.close()
            engine.close()
        }

        val invalidEngine =
            MockEngine {
                respond(
                    content =
                        ByteReadChannel(
                            successfulAnthropicStream(
                                blocks = listOf(listOf("{\"answer\":1}")),
                            ),
                        ),
                    status = HttpStatusCode.OK,
                    headers = eventStreamHeaders(),
                )
            }
        val invalidConnector = connector(invalidEngine)
        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    invalidConnector
                        .stream(request(UniversalAiResponseFormat.jsonSchema(schema)))
                        .toList()
                }
            assertEquals("malformed_provider_stream", failure.error.code.rawValue)
            assertEquals(ANTHROPIC_MALFORMED_STREAM_MESSAGE, failure.message)
        } finally {
            invalidConnector.close()
            invalidEngine.close()
        }
    }

    @Test
    fun mapsStreamErrorsAndIncompleteStopReasonsToFixedSafeFailures(): Unit = runTest {
        val sensitive = "provider-stream-sensitive-fragment"
        val errorStream =
            anthropicSse(
                "error",
                """
                {
                  "type":"error",
                  "error":{"type":"overloaded_error","message":"$sensitive"}
                }
                """,
            )
        val errorConnector = connector(ReaderTransport(singleChunkReader(errorStream)))
        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    errorConnector.stream(request()).toList()
                }
            assertEquals(UniversalAiErrorCategory.Provider, failure.error.category)
            assertEquals("provider_unavailable", failure.error.code.rawValue)
            assertEquals(ANTHROPIC_UNAVAILABLE_MESSAGE, failure.message)
            assertFalse(failure.stackTraceToString().contains(sensitive))
        } finally {
            errorConnector.close()
        }

        val cases =
            listOf(
                Triple("max_tokens", "provider_output_limit_reached", ANTHROPIC_OUTPUT_LIMIT_MESSAGE),
                Triple("refusal", "provider_refused_response", ANTHROPIC_REFUSAL_MESSAGE),
                Triple("tool_use", "provider_incomplete_response", ANTHROPIC_INCOMPLETE_RESPONSE_MESSAGE),
            )
        cases.forEach { (reason, code, message) ->
            val connector =
                connector(
                    ReaderTransport(
                        singleChunkReader(incompleteAnthropicStream(reason)),
                    ),
                )
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.stream(request()).toList()
                    }
                assertEquals(code, failure.error.code.rawValue)
                assertEquals(message, failure.message)
            } finally {
                connector.close()
            }
        }
    }

    @Test
    fun malformedUnknownRequiredMisorderedDuplicateTruncatedAndOversizedStreamsFailSafely() =
        runTest {
            val sensitive = "stream-sensitive-fragment"
            val malformedStreams =
                listOf(
                    "event: message_start\ndata: {\"type\":\"message_start\"\n\n",
                    messageStartEvent() +
                        contentBlockStartEvent(0) +
                        anthropicSse(
                            "content_block_delta",
                            """
                            {
                              "type":"content_block_delta",
                              "index":0,
                              "delta":{"type":"future_required","text":"$sensitive"}
                            }
                            """,
                        ),
                    messageStartEvent() + messageStartEvent(),
                    contentBlockStartEvent(0),
                    messageStartEvent(),
                    "event: message_start\ndata: {\"type\":\"message_start\"}",
                    "event: future_event\ndata: " + "x".repeat(1_048_577) + "\n\n",
                    anthropicSse(
                        "message_stop",
                        "{\"type\":\"message_start\"}",
                    ),
                    incompleteAnthropicStream("future_stop_reason"),
                )

            malformedStreams.forEachIndexed { index, stream ->
                val connector = connector(ReaderTransport(singleChunkReader(stream)))
                try {
                    val failure =
                        assertFailsWith<UniversalAiException> {
                            connector.stream(request()).toList()
                        }
                    if (index == OVERSIZED_STREAM_CASE_INDEX) {
                        assertEquals(UniversalAiErrorCategory.Transport, failure.error.category)
                        assertEquals(MALFORMED_RESPONSE_STREAM_MESSAGE, failure.message)
                    } else {
                        assertEquals(
                            UniversalAiErrorCategory.Protocol,
                            failure.error.category,
                            "malformed stream case $index",
                        )
                        assertEquals(
                            "malformed_provider_stream",
                            failure.error.code.rawValue,
                            "malformed stream case $index",
                        )
                        assertEquals(
                            ANTHROPIC_MALFORMED_STREAM_MESSAGE,
                            failure.message,
                            "malformed stream case $index",
                        )
                    }
                    assertFalse(failure.stackTraceToString().contains(sensitive))
                } finally {
                    connector.close()
                }
            }
        }

    @Test
    fun enforcesAggregateEventBlockAndOutputBounds(): Unit = runTest {
        val ping = providerEvent("ping", "{\"type\":\"ping\"}")
        val eventBoundTranslator = translator()
        eventBoundTranslator.translate(messageStartEvent().singleProviderEvent())
        repeat(65_535) {
            eventBoundTranslator.translate(ping)
        }
        assertMalformedStream {
            eventBoundTranslator.translate(ping)
        }

        val blockBoundTranslator = translator()
        blockBoundTranslator.translate(messageStartEvent().singleProviderEvent())
        repeat(128) { index ->
            blockBoundTranslator.translate(contentBlockStartEvent(index).singleProviderEvent())
            blockBoundTranslator.translate(contentBlockDeltaEvent(index, "x").singleProviderEvent())
            blockBoundTranslator.translate(contentBlockStopEvent(index).singleProviderEvent())
        }
        assertMalformedStream {
            blockBoundTranslator.translate(contentBlockStartEvent(128).singleProviderEvent())
        }

        val outputBoundTranslator = translator()
        outputBoundTranslator.translate(messageStartEvent().singleProviderEvent())
        outputBoundTranslator.translate(contentBlockStartEvent(0).singleProviderEvent())
        val boundedChunk = "x".repeat(16_384)
        repeat(64) {
            outputBoundTranslator.translate(
                contentBlockDeltaEvent(0, boundedChunk).singleProviderEvent(),
            )
        }
        assertMalformedStream {
            outputBoundTranslator.translate(contentBlockDeltaEvent(0, "x").singleProviderEvent())
        }
    }

    @Test
    fun rejectsDecreasingOrContradictoryCumulativeUsage(): Unit = runTest {
        val decreasingUsageTranslator = translatorWithCompletedTextBlock()
        decreasingUsageTranslator.translate(
            messageDeltaEvent(stopReason = null, outputTokens = 3).singleProviderEvent(),
        )
        assertMalformedStream {
            decreasingUsageTranslator.translate(
                messageDeltaEvent(stopReason = null, outputTokens = 2).singleProviderEvent(),
            )
        }

        listOf(
            "\"input_tokens\":3",
            "\"cache_creation_input_tokens\":2",
            "\"cache_read_input_tokens\":3",
        ).forEach { contradictoryField ->
            val translator = translatorWithCompletedTextBlock()
            assertMalformedStream {
                translator.translate(
                    providerEvent(
                        "message_delta",
                        """
                        {
                          "type":"message_delta",
                          "delta":{"stop_reason":null,"stop_sequence":null},
                          "usage":{$contradictoryField,"output_tokens":2}
                        }
                        """,
                    ),
                )
            }
        }
    }

    @Test
    fun successRequiresEventStreamContentType(): Unit = runTest {
        val engine =
            MockEngine {
                respond(
                    content = ByteReadChannel(successfulAnthropicStream()),
                    status = HttpStatusCode.OK,
                    headers =
                        Headers.build {
                            append(HttpHeaders.ContentType, "application/json")
                        },
                )
            }
        val connector = connector(engine)

        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector.stream(request()).toList()
                }
            assertEquals("malformed_provider_stream", failure.error.code.rawValue)
            assertEquals(ANTHROPIC_MALFORMED_STREAM_MESSAGE, failure.message)
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun cancellationBeforeHeadersRemainsCallerCancellation(): Unit = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val transport =
            object : ConnectorTransport {
                override suspend fun <Result> execute(
                    request: ConnectorTransportRequest,
                    consumeResponse: suspend (ConnectorTransportResponse) -> Result,
                ): Result {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }

                override fun close() = Unit
            }
        val connector = connector(transport)
        try {
            val operation = async { connector.stream(request()).toList() }
            started.await()
            operation.cancel()

            assertFailsWith<CancellationException> {
                operation.await()
            }
            withTimeout(1_000) {
                cancelled.await()
            }
        } finally {
            connector.close()
        }
    }

    @Test
    fun cancellationDuringPartialEventBetweenRecordsAndContentEmitsNoTerminal(): Unit = runTest {
        val prefixes =
            listOf(
                "event: message_start\ndata: {\"type\":\"message_start\"".encodeToByteArray(),
                messageStartEvent().encodeToByteArray(),
                (
                    messageStartEvent() +
                        contentBlockStartEvent(0) +
                        contentBlockDeltaEvent(0, "observable")
                ).encodeToByteArray(),
            )

        prefixes.forEachIndexed { index, prefix ->
            val waiting = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val expectedEventSeen = CompletableDeferred<Unit>()
            var reads = 0
            val connector =
                connector(
                    ReaderTransport(
                        ConnectorTransportChunkReader {
                            if (reads++ == 0) {
                                prefix
                            } else {
                                waiting.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    cancelled.complete(Unit)
                                }
                            }
                        },
                    ),
                )
            val events = mutableListOf<UniversalAiStreamEvent>()
            try {
                val operation =
                    async {
                        connector
                            .stream(request())
                            .onEach { event ->
                                events += event
                                if (
                                    (index == 1 &&
                                        event.type == UniversalAiStreamEventType.ResponseStarted) ||
                                    (index == 2 &&
                                        event.type == UniversalAiStreamEventType.OutputDelta)
                                ) {
                                    expectedEventSeen.complete(Unit)
                                }
                            }
                            .collect()
                    }
                waiting.await()
                if (index > 0) {
                    expectedEventSeen.await()
                }
                operation.cancel()

                assertFailsWith<CancellationException> {
                    operation.await()
                }
                withTimeout(1_000) {
                    cancelled.await()
                }
                when (index) {
                    0 -> assertTrue(events.isEmpty())
                    1 -> assertEquals(
                        listOf(UniversalAiStreamEventType.ResponseStarted),
                        events.map(UniversalAiStreamEvent::type),
                    )
                    else -> assertEquals("observable", events.last().delta)
                }
                assertFalse(events.any(UniversalAiStreamEvent::terminal))
                assertFalse(events.any { event ->
                    event.type == UniversalAiStreamEventType.OutputCompleted
                })
            } finally {
                connector.close()
            }
        }
    }

    @Test
    fun downstreamBackpressureAndPostTerminalCancellationSuppressLaterEvents(): Unit = runTest {
        var reads = 0
        val responseStarted = CompletableDeferred<Unit>()
        val transport =
            ReaderTransport(
                ConnectorTransportChunkReader {
                    reads += 1
                    when (reads) {
                        1 -> messageStartEvent().encodeToByteArray()
                        2 -> contentBlockStartEvent(0).encodeToByteArray()
                        else -> error("The adapter read beyond one rendezvous event.")
                    }
                },
            )
        val adapter = adapter(transport)
        try {
            val operation =
                async {
                    adapter
                        .stream(request())
                        .onEach { event ->
                            if (event.type == UniversalAiStreamEventType.ResponseStarted) {
                                responseStarted.complete(Unit)
                                awaitCancellation()
                            }
                        }.collect()
                }
            responseStarted.await()
            assertTrue(reads in 1..2)
            operation.cancel()
            assertFailsWith<CancellationException> {
                operation.await()
            }
            assertTrue(reads in 1..2)
        } finally {
            transport.close()
        }

        val connector =
            connector(
                ReaderTransport(
                    singleChunkReader(successfulAnthropicStream()),
                ),
            )
        val events = mutableListOf<UniversalAiStreamEvent>()
        val usageSeen = CompletableDeferred<Unit>()
        try {
            val operation =
                async {
                    connector
                        .stream(request())
                        .onEach { event ->
                            events += event
                            if (event.type == UniversalAiStreamEventType.UsageUpdated) {
                                usageSeen.complete(Unit)
                                awaitCancellation()
                            }
                        }.collect()
                }
            usageSeen.await()
            operation.cancel()
            assertFailsWith<CancellationException> {
                operation.await()
            }
            assertEquals(UniversalAiStreamEventType.UsageUpdated, events.last().type)
            assertFalse(events.any(UniversalAiStreamEvent::terminal))
        } finally {
            connector.close()
        }
    }

    @Test
    fun concurrentStreamsKeepTranslationAndCancellationStateIsolated(): Unit = runTest {
        val engine =
            MockEngine {
                respond(
                    content = ByteReadChannel(successfulAnthropicStream()),
                    status = HttpStatusCode.OK,
                    headers = eventStreamHeaders(),
                )
            }
        val connector = connector(engine)
        try {
            val first = async { connector.stream(request()).toList() }
            val second = async { connector.stream(request()).toList() }
            listOf(first.await(), second.await()).forEach { events ->
                assertEquals(
                    (1L..events.size.toLong()).toList(),
                    events.map(UniversalAiStreamEvent::sequence),
                )
                assertEquals("hello", events.last().response?.outputs?.single()?.text)
                assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
            }
        } finally {
            connector.close()
            engine.close()
        }
    }

    private fun connector(engine: MockEngine): UniversalAiConnector =
        UniversalAiConnector(
            configuration =
                UniversalAiConnectorConfiguration(
                    listOf(
                        UniversalAiProviderConfiguration(
                            providerId = ANTHROPIC_PROVIDER_ID,
                            baseUrl = "https://api.example.invalid/v1",
                            credentialSupplier = { "p5d-test-credential" },
                        ),
                    ),
                ),
            httpEngine = engine,
        )

    private fun connector(transport: ConnectorTransport): UniversalAiConnector =
        UniversalAiConnector(adapter(transport))

    private fun adapter(transport: ConnectorTransport): AnthropicMessagesAdapter =
        AnthropicMessagesAdapter(
            configuration =
                UniversalAiProviderConfiguration(
                    providerId = ANTHROPIC_PROVIDER_ID,
                    baseUrl = "https://api.example.invalid/v1",
                    credentialSupplier = { "p5d-test-credential" },
                ),
            transport = transport,
        )

    private fun translator(): AnthropicStreamTranslator =
        AnthropicStreamTranslator(
            request = request(),
            metadata =
                ConnectorResponseMetadata(
                    requestId = "req_stream",
                    retryAfterMillis = null,
                ),
        )

    private fun translatorWithCompletedTextBlock(): AnthropicStreamTranslator =
        translator().apply {
            translate(messageStartEvent().singleProviderEvent())
            translate(contentBlockStartEvent(0).singleProviderEvent())
            translate(contentBlockDeltaEvent(0, "ready").singleProviderEvent())
            translate(contentBlockStopEvent(0).singleProviderEvent())
        }

    private fun request(
        responseFormat: UniversalAiResponseFormat = UniversalAiResponseFormat.PlainText,
        stopSequences: List<String> = emptyList(),
    ): UniversalAiRequest =
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
                        content = "stream",
                    ),
                ),
            responseFormat = responseFormat,
            generation =
                UniversalAiGenerationParameters(
                    maxOutputTokens = 64,
                    stopSequences = stopSequences,
                ),
        )

    private class ReaderTransport(
        private val reader: ConnectorTransportChunkReader,
    ) : ConnectorTransport {
        override suspend fun <Result> execute(
            request: ConnectorTransportRequest,
            consumeResponse: suspend (ConnectorTransportResponse) -> Result,
        ): Result =
            consumeResponse(
                ConnectorTransportResponse(
                    statusCode = 200,
                    headers =
                        listOf(
                            ConnectorTransportHeader(
                                name = "content-type",
                                value = "text/event-stream; charset=utf-8",
                            ),
                            ConnectorTransportHeader(
                                name = "request-id",
                                value = "req_stream",
                            ),
                        ),
                    body = reader,
                ),
            )

        override fun close() = Unit
    }

    private companion object {
        const val OVERSIZED_STREAM_CASE_INDEX: Int = 6
        val ANTHROPIC_PROVIDER_ID: ProviderId = ProviderId.of("anthropic")
        val JSON = Json
    }
}

private fun successfulAnthropicStream(
    blocks: List<List<String>> = listOf(listOf("hello")),
    lineEnding: String = "\n",
    includeComment: Boolean = false,
    includePing: Boolean = false,
    includeUnknownOptional: Boolean = false,
    includeLateRecords: Boolean = false,
    stopReason: String = "end_turn",
    stopSequence: String? = null,
): String =
    buildString {
        if (includeComment) {
            append(": keep-alive")
            append(lineEnding)
            append(lineEnding)
        }
        append(messageStartEvent(lineEnding))
        blocks.forEachIndexed { index, deltas ->
            append(contentBlockStartEvent(index, lineEnding))
            if (includePing && index == 0) {
                append(anthropicSse("ping", "{\"type\":\"ping\"}", lineEnding))
            }
            deltas.forEach { delta ->
                append(contentBlockDeltaEvent(index, delta, lineEnding))
            }
            append(contentBlockStopEvent(index, lineEnding))
        }
        if (includeUnknownOptional) {
            append(
                anthropicSse(
                    "future_optional",
                    """
                    {
                      "type":"future_optional",
                      "index":"opaque",
                      "message":"opaque",
                      "delta":["opaque"]
                    }
                    """,
                    lineEnding,
                ),
            )
        }
        append(messageDeltaEvent(stopReason = null, outputTokens = 2, lineEnding = lineEnding))
        append(
            messageDeltaEvent(
                stopReason = stopReason,
                stopSequence = stopSequence,
                outputTokens = 3,
                lineEnding = lineEnding,
            ),
        )
        append(messageStopEvent(lineEnding))
        if (includeLateRecords) {
            append(messageStopEvent(lineEnding))
            append(contentBlockDeltaEvent(0, "late", lineEnding))
            append(
                anthropicSse(
                    "error",
                    """
                    {
                      "type":"error",
                      "error":{"type":"overloaded_error","message":"late failure"}
                    }
                    """,
                    lineEnding,
                ),
            )
        }
    }

private fun incompleteAnthropicStream(stopReason: String): String =
    messageStartEvent() +
        contentBlockStartEvent(0) +
        contentBlockDeltaEvent(0, "partial") +
        contentBlockStopEvent(0) +
        messageDeltaEvent(stopReason = stopReason, outputTokens = 3)

private fun messageStartEvent(lineEnding: String = "\n"): String =
    anthropicSse(
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
            "usage":{
              "input_tokens":2,
              "cache_creation_input_tokens":1,
              "cache_read_input_tokens":2,
              "output_tokens":1
            }
          }
        }
        """,
        lineEnding,
    )

private fun contentBlockStartEvent(
    index: Int,
    lineEnding: String = "\n",
): String =
    anthropicSse(
        "content_block_start",
        """
        {
          "type":"content_block_start",
          "index":$index,
          "content_block":{"type":"text","text":""}
        }
        """,
        lineEnding,
    )

private fun contentBlockDeltaEvent(
    index: Int,
    text: String,
    lineEnding: String = "\n",
): String =
    anthropicSse(
        "content_block_delta",
        """
        {
          "type":"content_block_delta",
          "index":$index,
          "delta":{"type":"text_delta","text":${JsonPrimitive(text)}}
        }
        """,
        lineEnding,
    )

private fun contentBlockStopEvent(
    index: Int,
    lineEnding: String = "\n",
): String =
    anthropicSse(
        "content_block_stop",
        "{\"type\":\"content_block_stop\",\"index\":$index}",
        lineEnding,
    )

private fun messageDeltaEvent(
    stopReason: String? = "end_turn",
    stopSequence: String? = null,
    outputTokens: Long,
    lineEnding: String = "\n",
): String {
    val stopReasonJson = stopReason?.let { value -> JsonPrimitive(value).toString() } ?: "null"
    val stopSequenceJson =
        stopSequence?.let { value -> JsonPrimitive(value).toString() } ?: "null"
    return anthropicSse(
        "message_delta",
        """
        {
          "type":"message_delta",
          "delta":{"stop_reason":$stopReasonJson,"stop_sequence":$stopSequenceJson},
          "usage":{"output_tokens":$outputTokens}
        }
        """,
        lineEnding,
    )
}

private fun messageStopEvent(lineEnding: String = "\n"): String =
    anthropicSse(
        "message_stop",
        "{\"type\":\"message_stop\"}",
        lineEnding,
    )

private fun anthropicSse(
    eventName: String,
    json: String,
    lineEnding: String = "\n",
): String =
    buildString {
        append("event: ")
        append(eventName)
        append(lineEnding)
        append("data: ")
        append(Json.parseToJsonElement(json).toString())
        append(lineEnding)
        append(lineEnding)
    }

private fun providerEvent(
    eventName: String,
    json: String,
): ConnectorServerSentEvent =
    ConnectorServerSentEvent(
        data = Json.parseToJsonElement(json).toString(),
        event = eventName,
        id = null,
        retryMillis = null,
    )

private fun String.singleProviderEvent(): ConnectorServerSentEvent {
    val normalized = replace("\r\n", "\n")
    val eventName =
        normalized
            .lineSequence()
            .single { line -> line.startsWith("event: ") }
            .removePrefix("event: ")
    val data =
        normalized
            .lineSequence()
            .single { line -> line.startsWith("data: ") }
            .removePrefix("data: ")
    return ConnectorServerSentEvent(
        data = data,
        event = eventName,
        id = null,
        retryMillis = null,
    )
}

private fun assertMalformedStream(block: () -> Unit) {
    val failure = assertFailsWith<UniversalAiException>(block = block)
    assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
    assertEquals("malformed_provider_stream", failure.error.code.rawValue)
    assertEquals(ANTHROPIC_MALFORMED_STREAM_MESSAGE, failure.message)
}

private fun singleChunkReader(stream: String): ConnectorTransportChunkReader {
    var delivered = false
    return ConnectorTransportChunkReader {
        if (delivered) {
            null
        } else {
            delivered = true
            stream.encodeToByteArray()
        }
    }
}

private fun eventStreamHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, "text/event-stream; charset=utf-8")
        append("request-id", "req_stream")
    }

private fun OutgoingContent.p5dBodyBytes(): ByteArray =
    (this as OutgoingContent.ByteArrayContent).bytes()
