package com.maneesh.universalai.connector.internal.provider.chatcompletions

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
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormat
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.internal.ConnectorEngine
import com.maneesh.universalai.connector.internal.provider.OPENAI_COMPATIBLE_PROVIDER_ID
import com.maneesh.universalai.connector.internal.provider.OPENROUTER_PROVIDER_ID
import com.maneesh.universalai.connector.internal.provider.openaicompatible.OPENAI_COMPATIBLE_MALFORMED_STREAM_MESSAGE
import com.maneesh.universalai.connector.internal.provider.openaicompatible.OpenAiCompatibleChatCompletionsAdapter
import com.maneesh.universalai.connector.internal.provider.openrouter.OPENROUTER_MALFORMED_STREAM_MESSAGE
import com.maneesh.universalai.connector.internal.provider.openrouter.OpenRouterChatCompletionsAdapter
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatCompletionsP6EStreamingTests {
    @Test
    fun bothAdaptersRequestSseAndTranslateTextUsageAndDone(): Unit = runTest {
        providerIds.forEach { providerId ->
            var observedDocument: JsonObject? = null
            var observedAccept: String? = null
            val engine =
                MockEngine { request ->
                    observedDocument =
                        JSON.parseToJsonElement(
                            request.body.p6eBodyBytes().decodeToString(),
                        ) as JsonObject
                    observedAccept = request.headers[HttpHeaders.Accept]
                    respond(
                        content =
                            ByteReadChannel(
                                successfulStream(
                                    textDeltas = listOf("Hé", "llo"),
                                    finishContent = "!",
                                    separateUsage = true,
                                    comment = true,
                                    lineEnding = "\r\n",
                                ),
                            ),
                        status = HttpStatusCode.OK,
                        headers = eventStreamHeaders(),
                    )
                }
            val connector = connector(providerId, engine)
            try {
                val events = connector.stream(request(providerId)).toList()
                val document = assertNotNull(observedDocument, providerId.rawValue)

                assertTrue((document["stream"] as JsonPrimitive).boolean, providerId.rawValue)
                assertTrue(
                    ((document["stream_options"] as JsonObject)["include_usage"] as JsonPrimitive)
                        .boolean,
                    providerId.rawValue,
                )
                assertEquals("text/event-stream", observedAccept, providerId.rawValue)
                assertEquals(
                    listOf(
                        UniversalAiStreamEventType.ResponseStarted,
                        UniversalAiStreamEventType.OutputStarted,
                        UniversalAiStreamEventType.OutputDelta,
                        UniversalAiStreamEventType.OutputDelta,
                        UniversalAiStreamEventType.OutputDelta,
                        UniversalAiStreamEventType.OutputCompleted,
                        UniversalAiStreamEventType.UsageUpdated,
                        UniversalAiStreamEventType.ResponseCompleted,
                    ),
                    events.map(UniversalAiStreamEvent::type),
                )
                assertEquals((1L..8L).toList(), events.map(UniversalAiStreamEvent::sequence))
                assertEquals(listOf("Hé", "llo", "!"), events.mapNotNull(UniversalAiStreamEvent::delta))
                assertEquals("chatcmpl_stream", events.first().responseId.rawValue)
                assertEquals("req_stream", events.first().requestId?.rawValue)
                assertEquals("Héllo!", events[5].output?.text)
                assertEquals(17L, events[6].usage?.totalTokens)
                assertEquals(providerId, events.last().response?.target?.providerId)
                assertEquals("Héllo!", events.last().response?.outputs?.single()?.text)
                assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
            } finally {
                connector.close()
                engine.close()
            }
        }
    }

    @Test
    fun structuredStreamsSuppressRawFragmentsAndEmitOneRevalidatedCanonicalDelta(): Unit = runTest {
        providerIds.forEach { providerId ->
            val connector =
                connector(
                    providerId,
                    ReaderTransport(
                        singleChunkReader(
                            successfulStream(
                                textDeltas = listOf("{\"answer\":", "\"ready\"}"),
                            ),
                        ),
                    ),
                )
            try {
                val events =
                    connector
                        .stream(
                            request(
                                providerId = providerId,
                                responseFormat =
                                    UniversalAiResponseFormat.jsonSchema(
                                        StructuredOutputSchema.parse(
                                            """
                                            {
                                              "type":"object",
                                              "properties":{"answer":{"type":"string","enum":["ready"]}},
                                              "required":["answer"],
                                              "additionalProperties":false
                                            }
                                            """.trimIndent(),
                                        ),
                                    ),
                            ),
                        ).toList()

                val deltas = events.filter { event -> event.type == UniversalAiStreamEventType.OutputDelta }
                assertEquals(1, deltas.size)
                assertEquals("{\"answer\":\"ready\"}", deltas.single().delta)
                assertNull(events.last().response?.outputs?.single()?.text)
                assertEquals(
                    "{\"answer\":\"ready\"}",
                    assertNotNull(events.last().response?.outputs?.single()?.structuredJson).toJson(),
                )
            } finally {
                connector.close()
            }
        }
    }

    @Test
    fun malformedOrderingSemanticIntrusionsAndMissingDoneFailClosed(): Unit = runTest {
        val sensitive = "provider-stream-sensitive-fragment"
        val malformedStreams =
            listOf(
                sseData("[DONE]"),
                successfulStream().substringBefore(sseData("[DONE]")),
                startChunk() + finishChunk(includeUsage = false) + sseData("[DONE]"),
                startChunk() + contentChunk("ready", id = "changed") + finishChunk() + sseData("[DONE]"),
                startChunk() + finishChunk(finishReason = "tool_calls") + sseData("[DONE]"),
                startChunk() + semanticIntrusionChunk(sensitive) + finishChunk() + sseData("[DONE]"),
                "data: {\"not_json\":\"$sensitive\"\n\n",
            )

        providerIds.forEach { providerId ->
            malformedStreams.forEach { stream ->
                val connector = connector(providerId, ReaderTransport(singleChunkReader(stream)))
                try {
                    val failure =
                        assertFailsWith<UniversalAiException> {
                            connector.stream(request(providerId)).toList()
                        }
                    assertEquals(UniversalAiErrorCategory.Protocol, failure.error.category)
                    assertEquals("malformed_provider_stream", failure.error.code.rawValue)
                    assertEquals(
                        if (providerId == OPENROUTER_PROVIDER_ID) {
                            OPENROUTER_MALFORMED_STREAM_MESSAGE
                        } else {
                            OPENAI_COMPATIBLE_MALFORMED_STREAM_MESSAGE
                        },
                        failure.message,
                    )
                    assertFalse(failure.stackTraceToString().contains(sensitive))
                } finally {
                    connector.close()
                }
            }
        }
    }

    @Test
    fun midStreamErrorsPreservePartialEventsButNeverProviderPayloads(): Unit = runTest {
        val sensitive = "mid-stream-provider-secret"
        providerIds.forEach { providerId ->
            val error =
                if (providerId == OPENROUTER_PROVIDER_ID) {
                    """{"code":429,"message":"$sensitive","metadata":{"error_type":"rate_limit_exceeded","provider_code":"$sensitive"}}"""
                } else {
                    """{"code":"unsafe","message":"$sensitive","nested":{"secret":"$sensitive"}}"""
                }
            val stream = startChunk() + contentChunk("partial") + errorChunk(error)
            val connector = connector(providerId, ReaderTransport(singleChunkReader(stream)))
            val events = mutableListOf<UniversalAiStreamEvent>()
            try {
                val failure =
                    assertFailsWith<UniversalAiException> {
                        connector
                            .stream(request(providerId))
                            .onEach(events::add)
                            .collect()
                    }
                if (providerId == OPENROUTER_PROVIDER_ID) {
                    assertEquals(UniversalAiErrorCategory.RateLimit, failure.error.category)
                    assertEquals("provider_rate_limited", failure.error.code.rawValue)
                } else {
                    assertEquals(UniversalAiErrorCategory.Provider, failure.error.category)
                    assertEquals("provider_request_failed", failure.error.code.rawValue)
                }
                assertEquals("partial", events.last().delta)
                assertFalse(events.any(UniversalAiStreamEvent::terminal))
                assertFalse(failure.stackTraceToString().contains(sensitive))
            } finally {
                connector.close()
            }
        }
    }

    @Test
    fun cancellationBeforeHeadersDuringPartialDataAndBetweenRecordsRemainsCallerCancellation(): Unit =
        runTest {
            providerIds.forEach { providerId ->
                val beforeHeadersStarted = CompletableDeferred<Unit>()
                val beforeHeadersCancelled = CompletableDeferred<Unit>()
                val beforeHeadersTransport =
                    object : ConnectorTransport {
                        override suspend fun <Result> execute(
                            request: ConnectorTransportRequest,
                            consumeResponse: suspend (ConnectorTransportResponse) -> Result,
                        ): Result {
                            beforeHeadersStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                beforeHeadersCancelled.complete(Unit)
                            }
                        }

                        override fun close() = Unit
                    }
                val beforeHeaders = connector(providerId, beforeHeadersTransport)
                try {
                    val operation = async { beforeHeaders.stream(request(providerId)).toList() }
                    beforeHeadersStarted.await()
                    operation.cancel()
                    assertFailsWith<CancellationException> { operation.await() }
                    withTimeout(1_000) { beforeHeadersCancelled.await() }
                } finally {
                    beforeHeaders.close()
                }

                listOf(
                    "data: {\"id\":\"chatcmpl_stream\"".encodeToByteArray(),
                    startChunk().encodeToByteArray(),
                    (startChunk() + contentChunk("observable")).encodeToByteArray(),
                ).forEachIndexed { index, prefix ->
                    val waiting = CompletableDeferred<Unit>()
                    val cancelled = CompletableDeferred<Unit>()
                    var reads = 0
                    val connector =
                        connector(
                            providerId,
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
                                    .stream(request(providerId))
                                    .onEach(events::add)
                                    .collect()
                            }
                        waiting.await()
                        operation.cancel()
                        assertFailsWith<CancellationException> { operation.await() }
                        withTimeout(1_000) { cancelled.await() }
                        if (index == 0) {
                            assertTrue(events.isEmpty())
                        }
                        if (index == 2) {
                            assertEquals("observable", events.last().delta)
                        }
                        assertFalse(events.any(UniversalAiStreamEvent::terminal))
                    } finally {
                        connector.close()
                    }
                }
            }
        }

    @Test
    fun doneStopsBeforeLateRecordsAndPostProviderTerminalCancellationEmitsNoLateTerminal(): Unit =
        runTest {
            var reads = 0
            val direct =
                connector(
                    OPENROUTER_PROVIDER_ID,
                    ReaderTransport(
                        ConnectorTransportChunkReader {
                            reads += 1
                            when (reads) {
                                1 ->
                                    (successfulStream() +
                                        contentChunk("late-sensitive-record"))
                                        .encodeToByteArray()
                                else -> error("The adapter read after [DONE].")
                            }
                        },
                    ),
                )
            try {
                val events = direct.stream(request(OPENROUTER_PROVIDER_ID)).toList()
                assertEquals(1, reads)
                assertEquals("ready", events.last().response?.outputs?.single()?.text)
            } finally {
                direct.close()
            }

            providerIds.forEach { providerId ->
                val connector =
                    connector(
                        providerId,
                        ReaderTransport(singleChunkReader(successfulStream())),
                    )
                val events = mutableListOf<UniversalAiStreamEvent>()
                val usageSeen = CompletableDeferred<Unit>()
                try {
                    val operation =
                        async {
                            connector
                                .stream(request(providerId))
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
                    assertFailsWith<CancellationException> { operation.await() }
                    assertEquals(UniversalAiStreamEventType.UsageUpdated, events.last().type)
                    assertFalse(events.any(UniversalAiStreamEvent::terminal))
                } finally {
                    connector.close()
                }
            }
        }

    private fun connector(
        providerId: ProviderId,
        engine: MockEngine,
    ): UniversalAiConnector =
        UniversalAiConnector(
            configuration =
                UniversalAiConnectorConfiguration(
                    listOf(providerConfiguration(providerId)),
                ),
            httpEngine = engine,
        )

    private fun connector(
        providerId: ProviderId,
        transport: ConnectorTransport,
    ): UniversalAiConnector = UniversalAiConnector(adapter(providerId, transport))

    private fun adapter(
        providerId: ProviderId,
        transport: ConnectorTransport,
    ): ConnectorEngine =
        if (providerId == OPENROUTER_PROVIDER_ID) {
            OpenRouterChatCompletionsAdapter(providerConfiguration(providerId), transport)
        } else {
            OpenAiCompatibleChatCompletionsAdapter(providerConfiguration(providerId), transport)
        }

    private fun providerConfiguration(providerId: ProviderId): UniversalAiProviderConfiguration =
        UniversalAiProviderConfiguration(
            providerId = providerId,
            baseUrl = "https://chat.example.invalid/api/v1",
            credentialSupplier = { "p6e-test-credential" },
        )

    private fun request(
        providerId: ProviderId,
        responseFormat: UniversalAiResponseFormat = UniversalAiResponseFormat.PlainText,
    ): UniversalAiRequest =
        UniversalAiRequest(
            target = UniversalAiTarget(providerId, ModelId.of("requested/provider-model")),
            input =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = "stream",
                    ),
                ),
            responseFormat = responseFormat,
            generation = UniversalAiGenerationParameters(maxOutputTokens = 64),
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
                            ConnectorTransportHeader(name = "request-id", value = "req_stream"),
                        ),
                    body = reader,
                ),
            )

        override fun close() = Unit
    }

    private companion object {
        val providerIds = listOf(OPENROUTER_PROVIDER_ID, OPENAI_COMPATIBLE_PROVIDER_ID)
        val JSON = Json
    }
}

private fun successfulStream(
    textDeltas: List<String> = listOf("ready"),
    finishContent: String? = null,
    separateUsage: Boolean = false,
    comment: Boolean = false,
    lineEnding: String = "\n",
): String =
    buildString {
        if (comment) {
            append(": OPENROUTER PROCESSING").append(lineEnding).append(lineEnding)
        }
        append(startChunk(lineEnding))
        textDeltas.forEach { delta -> append(contentChunk(delta, lineEnding = lineEnding)) }
        append(
            finishChunk(
                includeUsage = !separateUsage,
                content = finishContent,
                lineEnding = lineEnding,
            ),
        )
        if (separateUsage) {
            append(finishChunk(lineEnding = lineEnding))
        }
        append(sseData("[DONE]", lineEnding))
    }

private fun startChunk(lineEnding: String = "\n"): String =
    sseData(
        """{"id":"chatcmpl_stream","object":"chat.completion.chunk","created":123,"model":"resolved/provider-model","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
        lineEnding,
    )

private fun contentChunk(
    content: String,
    id: String = "chatcmpl_stream",
    lineEnding: String = "\n",
): String =
    sseData(
        """{"id":"$id","object":"chat.completion.chunk","created":123,"model":"resolved/provider-model","choices":[{"index":0,"delta":{"content":${JsonPrimitive(content)}}}]}""",
        lineEnding,
    )

private fun finishChunk(
    finishReason: String = "stop",
    includeUsage: Boolean = true,
    content: String? = null,
    lineEnding: String = "\n",
): String =
    sseData(
        """
        {
          "id":"chatcmpl_stream",
          "object":"chat.completion.chunk",
          "created":123,
          "model":"resolved/provider-model",
          "choices":[{"index":0,"delta":{"role":"assistant"${content?.let { value -> ",\"content\":${JsonPrimitive(value)}" } ?: ""}},"finish_reason":"$finishReason"}]
          ${if (includeUsage) ",\"usage\":${usageJson()}" else ""}
        }
        """.trimIndent(),
        lineEnding,
    )

private fun semanticIntrusionChunk(sensitive: String): String =
    sseData(
        """{"id":"chatcmpl_stream","object":"chat.completion.chunk","created":123,"model":"resolved/provider-model","choices":[{"index":0,"delta":{"tool_calls":[{"id":"$sensitive"}]}}]}""",
    )

private fun errorChunk(error: String): String =
    sseData(
        """{"id":"chatcmpl_stream","object":"chat.completion.chunk","created":123,"model":"resolved/provider-model","error":$error,"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}""",
    )

private fun usageJson(): String =
    """{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17,"prompt_tokens_details":{"cached_tokens":2},"completion_tokens_details":{"reasoning_tokens":1}}"""

private fun sseData(
    data: String,
    lineEnding: String = "\n",
): String = data
    .lines()
    .joinToString(separator = lineEnding, postfix = "$lineEnding$lineEnding") { line ->
        "data: $line"
    }

private fun eventStreamHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, "text/event-stream; charset=utf-8")
        append("Request-Id", "req_stream")
    }

private fun singleChunkReader(content: String): ConnectorTransportChunkReader {
    var delivered = false
    return ConnectorTransportChunkReader {
        if (delivered) {
            null
        } else {
            delivered = true
            content.encodeToByteArray()
        }
    }
}

private fun OutgoingContent.p6eBodyBytes(): ByteArray =
    (this as OutgoingContent.ByteArrayContent).bytes()
