package com.maneesh.universalai.connector.internal.provider.chatcompletions

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
import com.maneesh.universalai.connector.internal.provider.OPENAI_COMPATIBLE_PROVIDER_ID
import com.maneesh.universalai.connector.internal.provider.OPENROUTER_PROVIDER_ID
import com.maneesh.universalai.connector.internal.provider.ProviderRegistration
import com.maneesh.universalai.connector.internal.provider.openaicompatible.OpenAiCompatibleChatCompletionsAdapter
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

class ChatCompletionsP6FLifecycleTests {
    @Test
    fun concurrentDirectAndGenericOperationsKeepRegistryCredentialsAndStateIsolated() = runTest {
        var directCredentialCalls = 0
        var genericCredentialCalls = 0
        val transport = ConcurrentIsolationTransport(expectedRequestCount = 8)
        val connector =
            connector(
                transport = transport,
                directCredentialSupplier = {
                    directCredentialCalls += 1
                    "$DIRECT_CREDENTIAL-$directCredentialCalls"
                },
                genericCredentialSupplier = {
                    genericCredentialCalls += 1
                    "$GENERIC_CREDENTIAL-$genericCredentialCalls"
                },
            )

        try {
            val operations =
                P6F_PROVIDER_IDS.flatMap { providerId ->
                    listOf(
                        async {
                            val input = "${providerId.rawValue}-response-one"
                            input to connector.respond(request(providerId, input)).outputs.single().text
                        },
                        async {
                            val input = "${providerId.rawValue}-response-two"
                            input to connector.respond(request(providerId, input)).outputs.single().text
                        },
                        async {
                            val input = "${providerId.rawValue}-stream-one"
                            input to connector.stream(request(providerId, input)).toList()
                        },
                        async {
                            val input = "${providerId.rawValue}-stream-two"
                            input to connector.stream(request(providerId, input)).toList()
                        },
                    )
                }

            operations.awaitAll().forEach { (input, result) ->
                when (result) {
                    is String -> assertEquals("$input-result", result)
                    is List<*> -> {
                        val events = result.filterIsInstance<UniversalAiStreamEvent>()
                        assertEquals(
                            (1L..events.size.toLong()).toList(),
                            events.map(UniversalAiStreamEvent::sequence),
                        )
                        assertEquals("$input-result", events.last().response?.outputs?.single()?.text)
                        assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
                    }
                }
            }

            assertEquals(4, directCredentialCalls)
            assertEquals(4, genericCredentialCalls)
            assertEquals(8, transport.maxConcurrentRequests)
            assertEquals(
                P6F_PROVIDER_IDS.flatMapTo(mutableSetOf()) { providerId ->
                    setOf(
                        Triple(providerId, "${providerId.rawValue}-response-one", false),
                        Triple(providerId, "${providerId.rawValue}-response-two", false),
                        Triple(providerId, "${providerId.rawValue}-stream-one", true),
                        Triple(providerId, "${providerId.rawValue}-stream-two", true),
                    )
                },
                transport.observations.mapTo(mutableSetOf()) { observation ->
                    Triple(observation.providerId, observation.input, observation.streaming)
                },
            )
            assertEquals(
                (1..4).mapTo(mutableSetOf()) { index -> "Bearer $DIRECT_CREDENTIAL-$index" },
                transport.observations
                    .filter { observation -> observation.providerId == OPENROUTER_PROVIDER_ID }
                    .mapTo(mutableSetOf(), RequestObservation::authorization),
            )
            assertEquals(
                (1..4).mapTo(mutableSetOf()) { index -> "Bearer $GENERIC_CREDENTIAL-$index" },
                transport.observations
                    .filter { observation -> observation.providerId == OPENAI_COMPATIBLE_PROVIDER_ID }
                    .mapTo(mutableSetOf(), RequestObservation::authorization),
            )
        } finally {
            connector.close()
        }
    }

    @Test
    fun concurrentCloseCancelsCrossAdapterResponseAndStreamAndReleasesBody() = runTest {
        listOf(
            OPENROUTER_PROVIDER_ID to OPENAI_COMPATIBLE_PROVIDER_ID,
            OPENAI_COMPATIBLE_PROVIDER_ID to OPENROUTER_PROVIDER_ID,
        ).forEach { (responseProviderId, streamProviderId) ->
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
                connector(
                    transport = transport,
                    directCredentialSupplier = {
                        credentialCalls += 1
                        DIRECT_CREDENTIAL
                    },
                    genericCredentialSupplier = {
                        credentialCalls += 1
                        GENERIC_CREDENTIAL
                    },
                )
            val delivered = mutableListOf<UniversalAiStreamEvent>()

            try {
                val pendingResponse =
                    backgroundScope.async {
                        connector.respond(request(responseProviderId, "pending-response"))
                    }
                val activeStream =
                    backgroundScope.async {
                        connector
                            .stream(request(streamProviderId, "active-stream"))
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

                assertFailsWith<CancellationException> { pendingResponse.await() }
                assertFailsWith<CancellationException> { activeStream.await() }
                withTimeout(5_000) {
                    pendingResponseCancelled.await()
                    streamBodyCancelled.await()
                }
                assertTrue(delivered.isNotEmpty())
                assertTrue(
                    delivered.all { event ->
                        event.type == UniversalAiStreamEventType.ResponseStarted ||
                            event.type == UniversalAiStreamEventType.OutputStarted
                    },
                )
                assertTrue(delivered.none(UniversalAiStreamEvent::terminal))
                assertEquals(2, credentialCalls)
                assertEquals(2, transport.executeCalls)
                assertEquals(1, transport.closeCalls)

                val closedFailure =
                    assertFailsWith<UniversalAiException> {
                        connector.respond(request(responseProviderId, "after-close"))
                    }
                assertEquals(UniversalAiErrorCategory.Validation, closedFailure.error.category)
                assertEquals(UniversalAiErrorCode.InvalidRequest, closedFailure.error.code)
                assertEquals(2, credentialCalls)
            } finally {
                connector.close()
            }
        }
    }

    @Test
    fun connectorCloseBeforeProviderTerminalSuppressesTerminalAndReleasesBothBodies() = runTest {
        P6F_PROVIDER_IDS.forEach { providerId ->
            val bodyCancelled = CompletableDeferred<Unit>()
            val transport = BlockingBeforeTerminalTransport(bodyCancelled)
            val connector = connector(transport)
            val delivered = mutableListOf<UniversalAiStreamEvent>()

            try {
                val operation =
                    async {
                        connector
                            .stream(request(providerId, "terminal-close-race"))
                            .onEach { event ->
                                delivered += event
                                if (event.type == UniversalAiStreamEventType.OutputDelta) {
                                    connector.close()
                                }
                            }.collect()
                    }

                assertFailsWith<CancellationException> { operation.await() }
                withTimeout(5_000) { bodyCancelled.await() }
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
    }

    @Test
    fun deliveredCanonicalTerminalWinsConcurrentCloseForBothAdapters() = runTest {
        P6F_PROVIDER_IDS.forEach { providerId ->
            val engine =
                MockEngine {
                    respond(
                        content = ByteReadChannel(p6fSuccessfulStream()),
                        status = HttpStatusCode.OK,
                        headers = p6fEventStreamHeaders(),
                    )
                }
            val connector =
                UniversalAiConnector(
                    configuration =
                        UniversalAiConnectorConfiguration(
                            listOf(providerConfiguration(providerId)),
                        ),
                    httpEngine = engine,
                )

            try {
                val events =
                    connector
                        .stream(request(providerId, "delivered-terminal"))
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
    }

    private fun connector(
        transport: ConnectorTransport,
        directCredentialSupplier: () -> String = { DIRECT_CREDENTIAL },
        genericCredentialSupplier: () -> String = { GENERIC_CREDENTIAL },
    ): UniversalAiConnector {
        val directConfiguration =
            providerConfiguration(OPENROUTER_PROVIDER_ID, directCredentialSupplier)
        val genericConfiguration =
            providerConfiguration(OPENAI_COMPATIBLE_PROVIDER_ID, genericCredentialSupplier)
        return UniversalAiConnector.createForTesting(
            engineFactory = ::DeterministicConnectorEngine,
            transport = transport,
            ownership = ConnectorResourceOwnership.Owned,
            providerRegistrations =
                listOf(
                    ProviderRegistration(
                        providerId = OPENROUTER_PROVIDER_ID,
                        adapterFactory = { boundTransport ->
                            OpenRouterChatCompletionsAdapter(directConfiguration, boundTransport)
                        },
                    ),
                    ProviderRegistration(
                        providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                        adapterFactory = { boundTransport ->
                            OpenAiCompatibleChatCompletionsAdapter(genericConfiguration, boundTransport)
                        },
                    ),
                ),
        )
    }

    private fun providerConfiguration(
        providerId: ProviderId,
        credentialSupplier: () -> String = {
            if (providerId == OPENROUTER_PROVIDER_ID) DIRECT_CREDENTIAL else GENERIC_CREDENTIAL
        },
    ): UniversalAiProviderConfiguration =
        UniversalAiProviderConfiguration(
            providerId = providerId,
            baseUrl =
                if (providerId == OPENROUTER_PROVIDER_ID) {
                    P6F_DIRECT_BASE_URL
                } else {
                    P6F_GENERIC_BASE_URL
                },
            credentialSupplier = credentialSupplier,
        )

    private fun request(
        providerId: ProviderId,
        content: String,
    ): UniversalAiRequest =
        UniversalAiRequest(
            target = UniversalAiTarget(providerId, ModelId.of("requested/provider-model")),
            input = listOf(UniversalAiTextInput(UniversalAiInputRole.User, content)),
            generation = UniversalAiGenerationParameters(maxOutputTokens = 64),
        )

    private companion object {
        const val DIRECT_CREDENTIAL: String = "p6f-direct-test-credential"
        const val GENERIC_CREDENTIAL: String = "p6f-generic-test-credential"
    }

    private data class RequestObservation(
        val providerId: ProviderId,
        val input: String,
        val streaming: Boolean,
        val authorization: String,
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
                Json.parseToJsonElement(checkNotNull(request.body).decodeToString()).jsonObject
            val input =
                requestBody
                    .getValue("messages")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("content")
                    .jsonPrimitive
                    .content
            val providerId = request.p6fProviderId()
            val authorization =
                request.headers
                    .single { header -> header.name.equals("authorization", ignoreCase = true) }
                    .value
            assertFalse(
                checkNotNull(request.body)
                    .decodeToString()
                    .contains(authorization.removePrefix("Bearer ")),
            )
            val observation =
                RequestObservation(
                    providerId = providerId,
                    input = input,
                    streaming = requestBody["stream"]?.jsonPrimitive?.boolean == true,
                    authorization = authorization,
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
                    p6fSuccessfulStream(text = "${observation.input}-result")
                } else {
                    p6fSuccessfulResponse(text = "${observation.input}-result")
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
            return if (request.body?.p6fIsStreamingRequest() == true) {
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
                                        p6fFirstStreamRecord().encodeToByteArray()
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
            check(request.body?.p6fIsStreamingRequest() == true)
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
                                    p6fStreamBeforeTerminal().encodeToByteArray()
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

private val P6F_PROVIDER_IDS =
    listOf(OPENROUTER_PROVIDER_ID, OPENAI_COMPATIBLE_PROVIDER_ID)

private const val P6F_DIRECT_BASE_URL: String = "https://direct.example.invalid/api/v1"
private const val P6F_GENERIC_BASE_URL: String = "https://generic.example.invalid/custom/v1"

private fun ConnectorTransportRequest.p6fProviderId(): ProviderId =
    when {
        url.startsWith(P6F_DIRECT_BASE_URL) ->
            OPENROUTER_PROVIDER_ID
        url.startsWith(P6F_GENERIC_BASE_URL) ->
            OPENAI_COMPATIBLE_PROVIDER_ID
        else -> error("Unexpected P6-F transport URL.")
    }

private fun ByteArray.p6fIsStreamingRequest(): Boolean =
    Json
        .parseToJsonElement(decodeToString())
        .jsonObject["stream"]
        ?.jsonPrimitive
        ?.boolean == true

private fun p6fSuccessfulResponse(text: String): String =
    """
    {
      "id":"chatcmpl_lifecycle",
      "object":"chat.completion",
      "model":"resolved/provider-model",
      "choices":[{
        "index":0,
        "message":{"role":"assistant","content":${JsonPrimitive(text)}},
        "finish_reason":"stop"
      }],
      "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}
    }
    """.trimIndent()

private fun p6fSuccessfulStream(text: String = "hello"): String =
    p6fFirstStreamRecord() +
        p6fContentStreamRecord(text) +
        p6fFinishStreamRecord() +
        p6fSse("[DONE]")

private fun p6fStreamBeforeTerminal(): String =
    p6fFirstStreamRecord() + p6fContentStreamRecord("partial")

private fun p6fFirstStreamRecord(): String =
    p6fSse(
        """{"id":"chatcmpl_lifecycle","object":"chat.completion.chunk","created":123,"model":"resolved/provider-model","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
    )

private fun p6fContentStreamRecord(text: String): String =
    p6fSse(
        """{"id":"chatcmpl_lifecycle","object":"chat.completion.chunk","created":123,"model":"resolved/provider-model","choices":[{"index":0,"delta":{"content":${JsonPrimitive(text)}}}]}""",
    )

private fun p6fFinishStreamRecord(): String =
    p6fSse(
        """{"id":"chatcmpl_lifecycle","object":"chat.completion.chunk","created":123,"model":"resolved/provider-model","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
    )

private fun p6fSse(data: String): String = "data: $data\n\n"

private fun p6fEventStreamHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, "text/event-stream; charset=utf-8")
    }
