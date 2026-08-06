package com.maneesh.universalai.connector.internal.provider.openai

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiConnectorConfiguration
import com.maneesh.universalai.connector.UniversalAiProviderConfiguration
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.StructuredOutputSchema
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiOutputKind
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormat
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportChunkReader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportRequest
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportResponse
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportHeader
import com.maneesh.universalai.connector.internal.transport.MALFORMED_RESPONSE_STREAM_MESSAGE
import com.maneesh.universalai.connector.internal.transport.createKtorTransport
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
import kotlinx.serialization.encodeToString
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

class OpenAiP4DTests {
    @Test
    fun translatesOrderedTextLifecycleReasoningUsageAndTerminalAndSuppressesLateRecords() = runTest {
        val engine =
            MockEngine { request ->
                val body =
                    STREAM_JSON.parseToJsonElement(
                        request.body.p4dBodyBytes().decodeToString(),
                    ) as JsonObject
                assertTrue(body["stream"]?.let { (it as JsonPrimitive).boolean } == true)
                assertEquals("text/event-stream", request.headers[HttpHeaders.Accept])
                respond(
                    content =
                        ByteReadChannel(
                            successfulTextStream(
                                text = "Héllo",
                                includeLateRecords = true,
                            ),
                        ),
                    status = HttpStatusCode.OK,
                    headers = eventStreamHeaders(requestId = "req_stream"),
                )
            }
        val transport = createKtorTransport(engine)
        val adapter = adapter(transport)

        try {
            val events = adapter.stream(request()).toList()

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
            assertEquals(listOf("Hél", "lo"), events.mapNotNull(UniversalAiStreamEvent::delta))
            assertEquals("req_stream", events.first().requestId?.rawValue)
            assertEquals("message_0", events[1].outputId?.rawValue)
            assertEquals("Héllo", events[4].output?.text)
            assertEquals(3L, events[5].usage?.totalTokens)
            assertEquals("Héllo", events.last().response?.outputs?.single()?.text)
            assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
            assertTrue(events.last().terminal)
        } finally {
            transport.close()
            engine.close()
        }
    }

    @Test
    fun translatesArbitrarilyChunkedSseWithCommentsCrLfAndSplitUtf8() = runTest {
        nextByteIndex = 0
        val bytes =
            successfulTextStream(
                text = "Héllo",
                lineEnding = "\r\n",
                includeComment = true,
            ).encodeToByteArray()
        val transport =
            ReaderTransport(
                ConnectorTransportChunkReader {
                    if (nextByteIndex < bytes.size) {
                        byteArrayOf(bytes[nextByteIndex++])
                    } else {
                        null
                    }
                },
            )
        val connector = connector(transport)

        try {
            val events = connector.stream(request()).toList()

            assertEquals("Héllo", events.last().response?.outputs?.single()?.text)
            assertEquals(UniversalAiStreamEventType.ResponseCompleted, events.last().type)
            assertTrue(events.last().terminal)
        } finally {
            connector.close()
        }
    }

    @Test
    fun acceptsZeroBasedMonotonicallyIncreasingProviderSequenceWithGaps() = runTest {
        var providerSequence = 0L
        val stream =
            Regex("\"sequence_number\":\\d+")
                .replace(successfulTextStream()) {
                    val current = providerSequence
                    providerSequence += 2
                    "\"sequence_number\":$current"
                }
        val connector =
            connector(
                ReaderTransport(
                    ConnectorTransportChunkReader {
                        stream.encodeToByteArray()
                    },
                ),
            )

        try {
            val events = connector.stream(request()).toList()

            assertEquals(UniversalAiStreamEventType.ResponseCompleted, events.last().type)
            assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
        } finally {
            connector.close()
        }
    }

    @Test
    fun translatesStructuredOutputThroughOnePortableCanonicalDelta() = runTest {
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
        val providerText = """{"answer": "ready"}"""
        val engine =
            MockEngine {
                respond(
                    content =
                        ByteReadChannel(
                            successfulTextStream(
                                text = providerText,
                                deltas = listOf("""{"answer": """, """"ready"}"""),
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
                    events
                        .single { event ->
                            event.type == UniversalAiStreamEventType.OutputCompleted
                        }.output,
                )

            assertEquals(1, deltas.size)
            assertEquals("""{"answer":"ready"}""", deltas.single().delta)
            assertEquals(UniversalAiOutputKind.StructuredJson, output.kind)
            assertEquals("""{"answer":"ready"}""", output.structuredJson?.toJson())
            assertEquals(output, events.last().response?.outputs?.single())
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun rejectsCumulativeStructuredOutputOverByteLimitAcrossParts() = runTest {
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
        val firstPart = "a".repeat(524_288)
        val secondPart = "b".repeat(524_289)
        var sequence = 1
        val stream =
            buildString {
                append(createdEvent(sequence++))
                append(reasoningItemAddedEvent(sequence++, lineEnding = "\n"))
                append(reasoningItemDoneEvent(sequence++, lineEnding = "\n"))
                append(outputItemAddedEvent(sequence++))
                append(contentPartAddedEvent(sequence++))
                append(outputTextDeltaEvent(sequence++, firstPart))
                append(outputTextDoneEvent(sequence++, firstPart, lineEnding = "\n"))
                append(contentPartDoneEvent(sequence++, firstPart, lineEnding = "\n"))
                append(contentPartAddedEvent(sequence++, contentIndex = 1))
                append(outputTextDeltaEvent(sequence, secondPart, contentIndex = 1))
            }
        val engine =
            MockEngine {
                respond(
                    content = ByteReadChannel(stream),
                    status = HttpStatusCode.OK,
                    headers = eventStreamHeaders(),
                )
            }
        val connector = connector(engine)
        val events = mutableListOf<UniversalAiStreamEvent>()

        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector
                        .stream(request(UniversalAiResponseFormat.jsonSchema(schema)))
                        .collect(events::add)
                }

            assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
            assertEquals("malformed_provider_stream", failure.error.code.rawValue)
            assertEquals(OPENAI_MALFORMED_STREAM_MESSAGE, failure.message)
            assertEquals(
                listOf(
                    UniversalAiStreamEventType.ResponseStarted,
                    UniversalAiStreamEventType.OutputStarted,
                ),
                events.map(UniversalAiStreamEvent::type),
            )
            assertFalse(events.any(UniversalAiStreamEvent::terminal))
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun preservesMultipleProviderMessageOrderWhileOmittingReasoningItems() = runTest {
        val engine =
            MockEngine {
                respond(
                    content = ByteReadChannel(successfulTwoMessageStream()),
                    status = HttpStatusCode.OK,
                    headers = eventStreamHeaders(),
                )
            }
        val connector = connector(engine)

        try {
            val events = connector.stream(request()).toList()
            val completedOutputs =
                events
                    .filter { event -> event.type == UniversalAiStreamEventType.OutputCompleted }
                    .mapNotNull(UniversalAiStreamEvent::output)

            assertEquals(listOf(0, 1), completedOutputs.map { output -> output.index })
            assertEquals(listOf("first", "second"), completedOutputs.mapNotNull { output -> output.text })
            assertEquals(completedOutputs, events.last().response?.outputs)
            assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun providerFailureIncompleteAndErrorEventsRemainOutOfBandSafeFailures() = runTest {
        val cases =
            listOf(
                FailureCase(
                    terminal =
                        sseEvent(
                            """
                            {
                              "type":"response.failed",
                              "sequence_number":2,
                              "response":{
                                "id":"resp_stream",
                                "object":"response",
                                "status":"failed",
                                "model":"resolved-model",
                                "output":[],
                                "usage":null,
                                "error":{"code":"server_error","message":"sensitive provider detail"},
                                "incomplete_details":null
                              }
                            }
                            """,
                        ),
                    category = UniversalAiErrorCategory.Provider,
                    code = "provider_server_error",
                ),
                FailureCase(
                    terminal =
                        sseEvent(
                            """
                            {
                              "type":"response.incomplete",
                              "sequence_number":2,
                              "response":{
                                "id":"resp_stream",
                                "object":"response",
                                "status":"incomplete",
                                "model":"resolved-model",
                                "output":[],
                                "usage":null,
                                "error":null,
                                "incomplete_details":{"reason":"max_output_tokens"}
                              }
                            }
                            """,
                        ),
                    category = UniversalAiErrorCategory.Provider,
                    code = "provider_output_limit_reached",
                ),
                FailureCase(
                    terminal =
                        sseEvent(
                            """
                            {
                              "type":"error",
                              "sequence_number":2,
                              "code":"rate_limit_exceeded",
                              "message":"sensitive provider detail",
                              "param":null
                            }
                            """,
                        ),
                    category = UniversalAiErrorCategory.RateLimit,
                    code = "provider_rate_limited",
                ),
            )

        cases.forEach { case ->
            val engine =
                MockEngine {
                    respond(
                        content = ByteReadChannel(createdEvent() + case.terminal),
                        status = HttpStatusCode.OK,
                        headers = eventStreamHeaders(),
                    )
                }
            val connector = connector(engine)
            val events = mutableListOf<UniversalAiStreamEvent>()
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.stream(request()).collect(events::add)
                    }

                assertEquals(listOf(UniversalAiStreamEventType.ResponseStarted), events.map { it.type })
                assertEquals(case.category, failure.error.category)
                assertEquals(case.code, failure.error.code.rawValue)
                assertFalse(failure.stackTraceToString().contains("sensitive provider detail"))
                assertFalse(events.any(UniversalAiStreamEvent::terminal))
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun malformedUnknownMisorderedDuplicateTruncatedMissingAndOversizedStreamsFailSafely() = runTest {
        val sensitive = "stream-sensitive-fragment"
        val malformedStreams =
            listOf(
                sseEvent("""{"type":"response.created","sequence_number":"""),
                createdEvent() +
                    sseEvent(
                        """
                        {
                          "type":"response.future_required",
                          "sequence_number":2,
                          "payload":"$sensitive"
                        }
                        """,
                    ),
                createdEvent() +
                    sseEvent(
                        """
                        {
                          "type":"response.output_text.delta",
                          "sequence_number":2,
                          "output_index":0,
                          "item_id":"message_0",
                          "content_index":0,
                          "delta":"$sensitive"
                        }
                        """,
                    ),
                createdEvent() +
                    reasoningItemAddedEvent(sequence = 2, lineEnding = "\n") +
                    reasoningItemDoneEvent(sequence = 3, lineEnding = "\n") +
                    outputItemAddedEvent(sequence = 4) +
                    outputItemAddedEvent(sequence = 5),
                createdEvent() +
                    sseEvent(
                        """
                        {
                          "type":"response.in_progress",
                          "sequence_number":1,
                          "response":{
                            "id":"resp_stream",
                            "object":"response",
                            "status":"in_progress",
                            "model":"resolved-model",
                            "output":[],
                            "usage":null,
                            "error":null,
                            "incomplete_details":null
                          }
                        }
                        """,
                    ),
                createdEvent(),
                "data: {\"type\":\"response.created\",\"sequence_number\":1",
                "data: " + "x".repeat(1_048_577) + "\n\n",
                sseEvent(
                    """
                    {
                      "type":"response.created",
                      "sequence_number":1,
                      "response":{
                        "id":"resp_stream",
                        "object":"response",
                        "status":"in_progress",
                        "model":"resolved-model",
                        "output":[],
                        "usage":null,
                        "error":null,
                        "incomplete_details":null
                      }
                    }
                    """,
                    eventName = "response.completed",
                ),
            )

        malformedStreams.forEachIndexed { index, stream ->
            val engine =
                MockEngine {
                    respond(
                        content = ByteReadChannel(stream),
                        status = HttpStatusCode.OK,
                        headers = eventStreamHeaders(),
                    )
                }
            val connector = connector(engine)
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector.stream(request()).toList()
                    }

                if (index == OVERSIZED_STREAM_CASE_INDEX) {
                    assertEquals(UniversalAiErrorCategory.Transport, failure.error.category)
                    assertEquals(UniversalAiErrorCode.TransportFailure, failure.error.code)
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
                        OPENAI_MALFORMED_STREAM_MESSAGE,
                        failure.message,
                        "malformed stream case $index",
                    )
                }
                assertFalse(failure.stackTraceToString().contains(sensitive))
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun successRequiresEventStreamContentType() = runTest {
        val engine =
            MockEngine {
                respond(
                    content = ByteReadChannel(successfulTextStream()),
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
            assertEquals(OPENAI_MALFORMED_STREAM_MESSAGE, failure.message)
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun cancellationBeforeHeadersRemainsCallerCancellation() = runTest {
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
    fun cancellationDuringPartialEventAndBetweenRecordsEmitsNoTerminal() = runTest {
        val prefixes =
            listOf(
                "data: {\"type\":\"response.created\"".encodeToByteArray(),
                createdEvent().encodeToByteArray(),
            )

        prefixes.forEachIndexed { index, prefix ->
            val waiting = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val responseStarted = CompletableDeferred<Unit>()
            var reads = 0
            val reader =
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
                }
            val connector = connector(ReaderTransport(reader))
            val events = mutableListOf<UniversalAiStreamEvent>()
            try {
                val operation =
                    async {
                        connector
                            .stream(request())
                            .onEach { event ->
                                events += event
                                if (event.type == UniversalAiStreamEventType.ResponseStarted) {
                                    responseStarted.complete(Unit)
                                }
                            }.collect()
                    }
                waiting.await()
                if (index > 0) {
                    responseStarted.await()
                }
                operation.cancel()

                assertFailsWith<CancellationException> {
                    operation.await()
                }
                withTimeout(1_000) {
                    cancelled.await()
                }
                if (index == 0) {
                    assertTrue(events.isEmpty())
                } else {
                    assertEquals(
                        listOf(UniversalAiStreamEventType.ResponseStarted),
                        events.map(UniversalAiStreamEvent::type),
                    )
                }
                assertFalse(events.any(UniversalAiStreamEvent::terminal))
            } finally {
                connector.close()
            }
        }
    }

    @Test
    fun cancellationDuringContentStopsWithoutOutputOrResponseTerminal() = runTest {
        val prefix =
            createdEvent() +
                reasoningItemAddedEvent(sequence = 2, lineEnding = "\n") +
                reasoningItemDoneEvent(sequence = 3, lineEnding = "\n") +
                outputItemAddedEvent(sequence = 4) +
                contentPartAddedEvent(sequence = 5) +
                outputTextDeltaEvent(sequence = 6, delta = "observable")
        val waiting = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val deltaSeen = CompletableDeferred<Unit>()
        var reads = 0
        val reader =
            ConnectorTransportChunkReader {
                if (reads++ == 0) {
                    prefix.encodeToByteArray()
                } else {
                    waiting.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }
        val connector = connector(ReaderTransport(reader))
        val events = mutableListOf<UniversalAiStreamEvent>()
        try {
            val operation =
                async {
                    connector
                        .stream(request())
                        .onEach { event ->
                            events += event
                            if (event.type == UniversalAiStreamEventType.OutputDelta) {
                                deltaSeen.complete(Unit)
                            }
                        }.collect()
                }
            deltaSeen.await()
            waiting.await()
            operation.cancel()

            assertFailsWith<CancellationException> {
                operation.await()
            }
            withTimeout(1_000) {
                cancelled.await()
            }
            assertEquals("observable", events.last().delta)
            assertFalse(events.any(UniversalAiStreamEvent::terminal))
            assertFalse(events.any { event -> event.type == UniversalAiStreamEventType.OutputCompleted })
        } finally {
            connector.close()
        }
    }

    @Test
    fun downstreamBackpressureBoundsProviderReadAheadToOneCanonicalEvent() = runTest {
        var reads = 0
        val responseStarted = CompletableDeferred<Unit>()
        val reader =
            ConnectorTransportChunkReader {
                reads += 1
                when (reads) {
                    1 -> createdEvent().encodeToByteArray()
                    2 ->
                        outputItemAddedEvent(
                            sequence = 2,
                            providerIndex = 0,
                        ).encodeToByteArray()
                    else -> error("The adapter read beyond its single rendezvous event.")
                }
            }
        val transport = ReaderTransport(reader)
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
    }

    @Test
    fun cancellationAfterProviderTerminalWinsBeforeCanonicalTerminalDelivery() = runTest {
        val connector =
            connector(
                ReaderTransport(
                    ConnectorTransportChunkReader {
                        successfulTextStream().encodeToByteArray()
                    },
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
    fun concurrentStreamsKeepTranslationStateIsolated() = runTest {
        val engine =
            MockEngine {
                respond(
                    content = ByteReadChannel(successfulTextStream(text = "isolated")),
                    status = HttpStatusCode.OK,
                    headers = eventStreamHeaders(),
                )
            }
        val connector = connector(engine)
        try {
            val first = async { connector.stream(request()).toList() }
            val second = async { connector.stream(request()).toList() }
            val results = listOf(first.await(), second.await())

            results.forEach { events ->
                assertEquals((1L..7L).toList(), events.map(UniversalAiStreamEvent::sequence))
                assertEquals("isolated", events.last().response?.outputs?.single()?.text)
                assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
            }
        } finally {
            connector.close()
            engine.close()
        }
    }

    @Test
    fun authoritativeProviderTerminalReturnsBeforeAnotherBodyRead() = runTest {
        var reads = 0
        val reader =
            ConnectorTransportChunkReader {
                reads += 1
                when (reads) {
                    1 -> successfulTextStream().encodeToByteArray()
                    else -> error("The adapter must stop reading after the authoritative terminal.")
                }
            }
        val connector = connector(ReaderTransport(reader))

        try {
            val events = connector.stream(request()).toList()

            assertEquals(1, reads)
            assertEquals(UniversalAiStreamEventType.ResponseCompleted, events.last().type)
            assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
        } finally {
            connector.close()
        }
    }

    private var nextByteIndex: Int = 0

    private fun connector(engine: MockEngine): UniversalAiConnector =
        UniversalAiConnector(
            configuration =
                UniversalAiConnectorConfiguration(
                    listOf(
                        UniversalAiProviderConfiguration(
                            providerId = OPENAI_PROVIDER_ID,
                            baseUrl = "https://api.example.invalid/v1",
                            credentialSupplier = { "p4d-test-credential" },
                        ),
                    ),
                ),
            httpEngine = engine,
        )

    private fun connector(transport: ConnectorTransport): UniversalAiConnector =
        UniversalAiConnector(
            adapter(transport),
        )

    private fun adapter(transport: ConnectorTransport): OpenAiResponsesAdapter =
        OpenAiResponsesAdapter(
            configuration =
                UniversalAiProviderConfiguration(
                    providerId = OPENAI_PROVIDER_ID,
                    baseUrl = "https://api.example.invalid/v1",
                    credentialSupplier = { "p4d-test-credential" },
                ),
            transport = transport,
        )

    private fun request(
        responseFormat: UniversalAiResponseFormat = UniversalAiResponseFormat.PlainText,
    ): UniversalAiRequest =
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
                        content = "stream",
                    ),
                ),
            responseFormat = responseFormat,
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
                        ),
                    body = reader,
                ),
            )

        override fun close() = Unit
    }

    private data class FailureCase(
        val terminal: String,
        val category: UniversalAiErrorCategory,
        val code: String,
    )

    private companion object {
        const val OVERSIZED_STREAM_CASE_INDEX: Int = 7
        val OPENAI_PROVIDER_ID: ProviderId = ProviderId.of("openai")
    }
}

internal fun successfulTextStream(
    text: String = "hello",
    deltas: List<String> =
        listOf(
            text.take((text.length + 1) / 2),
            text.drop((text.length + 1) / 2),
        ).filter(String::isNotEmpty),
    lineEnding: String = "\n",
    includeComment: Boolean = false,
    includeLateRecords: Boolean = false,
): String {
    var sequence = 1
    return buildString {
        if (includeComment) {
            append(": keep-alive")
            append(lineEnding)
            append(lineEnding)
        }
        append(createdEvent(sequence++, lineEnding))
        append(inProgressEvent(sequence++, lineEnding))
        append(reasoningItemAddedEvent(sequence++, lineEnding))
        append(reasoningItemDoneEvent(sequence++, lineEnding))
        append(outputItemAddedEvent(sequence++, lineEnding))
        append(contentPartAddedEvent(sequence++, lineEnding))
        deltas.forEach { delta ->
            append(outputTextDeltaEvent(sequence++, delta, lineEnding))
        }
        append(outputTextDoneEvent(sequence++, text, lineEnding))
        append(contentPartDoneEvent(sequence++, text, lineEnding))
        append(outputItemDoneEvent(sequence++, text, lineEnding))
        append(responseCompletedEvent(sequence++, text, lineEnding))
        if (includeLateRecords) {
            append(responseCompletedEvent(sequence++, text, lineEnding))
            append(outputTextDeltaEvent(sequence++, "late", lineEnding))
            append(
                sseEvent(
                    """
                    {
                      "type":"error",
                      "sequence_number":$sequence,
                      "code":"server_error",
                      "message":"late sensitive failure",
                      "param":null
                    }
                    """,
                    lineEnding = lineEnding,
                ),
            )
        }
    }
}

private fun successfulTwoMessageStream(): String {
    var sequence = 1
    val messages = listOf("message_0" to "first", "message_1" to "second")
    return buildString {
        append(createdEvent(sequence++))
        append(reasoningItemAddedEvent(sequence++, lineEnding = "\n"))
        append(reasoningItemDoneEvent(sequence++, lineEnding = "\n"))
        messages.forEachIndexed { canonicalIndex, (itemId, text) ->
            val providerIndex = canonicalIndex + 1
            append(
                outputItemAddedEvent(
                    sequence = sequence++,
                    providerIndex = providerIndex,
                    itemId = itemId,
                ),
            )
            append(
                contentPartAddedEvent(
                    sequence = sequence++,
                    providerIndex = providerIndex,
                    itemId = itemId,
                ),
            )
            append(
                outputTextDeltaEvent(
                    sequence = sequence++,
                    delta = text,
                    providerIndex = providerIndex,
                    itemId = itemId,
                ),
            )
            append(
                outputTextDoneEvent(
                    sequence = sequence++,
                    text = text,
                    lineEnding = "\n",
                    providerIndex = providerIndex,
                    itemId = itemId,
                ),
            )
            append(
                contentPartDoneEvent(
                    sequence = sequence++,
                    text = text,
                    lineEnding = "\n",
                    providerIndex = providerIndex,
                    itemId = itemId,
                ),
            )
            append(
                outputItemDoneEvent(
                    sequence = sequence++,
                    text = text,
                    lineEnding = "\n",
                    providerIndex = providerIndex,
                    itemId = itemId,
                ),
            )
        }
        append(responseCompletedWithMessagesEvent(sequence, messages))
    }
}

private fun createdEvent(
    sequence: Int = 1,
    lineEnding: String = "\n",
): String =
    sseEvent(
        """
        {
          "type":"response.created",
          "sequence_number":$sequence,
          "future_optional_field":true,
          "response":{
            "id":"resp_stream",
            "object":"response",
            "status":"in_progress",
            "model":"resolved-model",
            "output":[],
            "usage":null,
            "error":null,
            "incomplete_details":null
          }
        }
        """,
        lineEnding = lineEnding,
    )

private fun inProgressEvent(
    sequence: Int,
    lineEnding: String,
): String =
    sseEvent(
        """
        {
          "type":"response.in_progress",
          "sequence_number":$sequence,
          "response":{
            "id":"resp_stream",
            "object":"response",
            "status":"in_progress",
            "model":"resolved-model",
            "output":[],
            "usage":null,
            "error":null,
            "incomplete_details":null
          }
        }
        """,
        lineEnding = lineEnding,
    )

private fun reasoningItemAddedEvent(
    sequence: Int,
    lineEnding: String,
): String =
    sseEvent(
        """
        {
          "type":"response.output_item.added",
          "sequence_number":$sequence,
          "output_index":0,
          "item":{"id":"reasoning_0","type":"reasoning","status":"in_progress"}
        }
        """,
        lineEnding = lineEnding,
    )

private fun reasoningItemDoneEvent(
    sequence: Int,
    lineEnding: String,
): String =
    sseEvent(
        """
        {
          "type":"response.output_item.done",
          "sequence_number":$sequence,
          "output_index":0,
          "item":{"id":"reasoning_0","type":"reasoning","status":"completed"}
        }
        """,
        lineEnding = lineEnding,
    )

private fun outputItemAddedEvent(
    sequence: Int,
    lineEnding: String = "\n",
    providerIndex: Int = 1,
    itemId: String = "message_0",
): String =
    sseEvent(
        """
        {
          "type":"response.output_item.added",
          "sequence_number":$sequence,
          "output_index":$providerIndex,
          "item":{
            "id":${Json.encodeToString(itemId)},
            "type":"message",
            "status":"in_progress",
            "role":"assistant",
            "content":[]
          }
        }
        """,
        lineEnding = lineEnding,
    )

private fun contentPartAddedEvent(
    sequence: Int,
    lineEnding: String = "\n",
    providerIndex: Int = 1,
    itemId: String = "message_0",
    contentIndex: Int = 0,
): String =
    sseEvent(
        """
        {
          "type":"response.content_part.added",
          "sequence_number":$sequence,
          "output_index":$providerIndex,
          "item_id":${Json.encodeToString(itemId)},
          "content_index":$contentIndex,
          "part":{"type":"output_text","text":""}
        }
        """,
        lineEnding = lineEnding,
    )

private fun outputTextDeltaEvent(
    sequence: Int,
    delta: String,
    lineEnding: String = "\n",
    providerIndex: Int = 1,
    itemId: String = "message_0",
    contentIndex: Int = 0,
): String =
    sseEvent(
        """
        {
          "type":"response.output_text.delta",
          "sequence_number":$sequence,
          "output_index":$providerIndex,
          "item_id":${Json.encodeToString(itemId)},
          "content_index":$contentIndex,
          "delta":${Json.encodeToString(delta)}
        }
        """,
        eventName = "response.output_text.delta",
        lineEnding = lineEnding,
    )

private fun outputTextDoneEvent(
    sequence: Int,
    text: String,
    lineEnding: String,
    providerIndex: Int = 1,
    itemId: String = "message_0",
): String =
    sseEvent(
        """
        {
          "type":"response.output_text.done",
          "sequence_number":$sequence,
          "output_index":$providerIndex,
          "item_id":${Json.encodeToString(itemId)},
          "content_index":0,
          "text":${Json.encodeToString(text)}
        }
        """,
        lineEnding = lineEnding,
    )

private fun contentPartDoneEvent(
    sequence: Int,
    text: String,
    lineEnding: String,
    providerIndex: Int = 1,
    itemId: String = "message_0",
): String =
    sseEvent(
        """
        {
          "type":"response.content_part.done",
          "sequence_number":$sequence,
          "output_index":$providerIndex,
          "item_id":${Json.encodeToString(itemId)},
          "content_index":0,
          "part":{"type":"output_text","text":${Json.encodeToString(text)}}
        }
        """,
        lineEnding = lineEnding,
    )

private fun outputItemDoneEvent(
    sequence: Int,
    text: String,
    lineEnding: String,
    providerIndex: Int = 1,
    itemId: String = "message_0",
): String =
    sseEvent(
        """
        {
          "type":"response.output_item.done",
          "sequence_number":$sequence,
          "output_index":$providerIndex,
          "item":{
            "id":${Json.encodeToString(itemId)},
            "type":"message",
            "status":"completed",
            "role":"assistant",
            "content":[{"type":"output_text","text":${Json.encodeToString(text)}}]
          }
        }
        """,
        lineEnding = lineEnding,
    )

private fun responseCompletedEvent(
    sequence: Int,
    text: String,
    lineEnding: String,
): String =
    sseEvent(
        """
        {
          "type":"response.completed",
          "sequence_number":$sequence,
          "response":{
            "id":"resp_stream",
            "object":"response",
            "status":"completed",
            "model":"resolved-model",
            "output":[
              {"id":"reasoning_0","type":"reasoning","status":"completed"},
              {
                "id":"message_0",
                "type":"message",
                "status":"completed",
                "role":"assistant",
                "content":[{"type":"output_text","text":${Json.encodeToString(text)}}]
              }
            ],
            "usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3},
            "error":null,
            "incomplete_details":null
          }
        }
        """,
        lineEnding = lineEnding,
    )

private fun responseCompletedWithMessagesEvent(
    sequence: Int,
    messages: List<Pair<String, String>>,
): String {
    val messageItems =
        messages.joinToString(separator = ",") { (itemId, text) ->
            """
            {
              "id":${Json.encodeToString(itemId)},
              "type":"message",
              "status":"completed",
              "role":"assistant",
              "content":[{"type":"output_text","text":${Json.encodeToString(text)}}]
            }
            """.trimIndent()
        }
    return sseEvent(
        """
        {
          "type":"response.completed",
          "sequence_number":$sequence,
          "response":{
            "id":"resp_stream",
            "object":"response",
            "status":"completed",
            "model":"resolved-model",
            "output":[
              {"id":"reasoning_0","type":"reasoning","status":"completed"},
              $messageItems
            ],
            "usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3},
            "error":null,
            "incomplete_details":null
          }
        }
        """,
    )
}

private fun sseEvent(
    json: String,
    eventName: String? = null,
    lineEnding: String = "\n",
): String =
    buildString {
        eventName?.let {
            append("event: ")
            append(it)
            append(lineEnding)
        }
        json
            .trimIndent()
            .lineSequence()
            .forEach { line ->
                append("data: ")
                append(line)
                append(lineEnding)
            }
        append(lineEnding)
    }

private fun eventStreamHeaders(requestId: String? = null): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, "text/event-stream; charset=utf-8")
        requestId?.let { append("X-Request-Id", it) }
    }

private fun OutgoingContent.p4dBodyBytes(): ByteArray =
    (this as OutgoingContent.ByteArrayContent).bytes()

private val STREAM_JSON = Json
