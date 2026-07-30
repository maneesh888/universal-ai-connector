package com.maneesh.universalai.connector

import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.ResponseId
import com.maneesh.universalai.connector.contract.UniversalAiCompletionReason
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.internal.ConnectorEngine
import com.maneesh.universalai.connector.internal.ConnectorResourceOwnership
import com.maneesh.universalai.connector.internal.DeterministicConnectorEngine
import com.maneesh.universalai.connector.internal.provider.ProviderRegistration
import com.maneesh.universalai.connector.internal.transport.ConnectorBaseUrl
import com.maneesh.universalai.connector.internal.transport.ConnectorServerSentEventReader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportChunkReader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportRequest
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportResponse
import com.maneesh.universalai.connector.internal.transport.createKtorTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UniversalAiConnectorLifecycleTests {
    @Test
    fun ownedTransportClosesExactlyOnceAcrossConcurrentCloseCalls() = runTest {
        val transport = RecordingTransport()
        val connector =
            UniversalAiConnector.createForTesting(
                engineFactory = ::DeterministicConnectorEngine,
                transport = transport,
                ownership = ConnectorResourceOwnership.Owned,
            )

        List(32) {
            async(Dispatchers.Default) {
                connector.close()
            }
        }.awaitAll()
        connector.close()

        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun borrowedSharedTransportRemainsOpenAndUsable() = runTest {
        val transport = RecordingTransport()
        val first =
            UniversalAiConnector.createForTesting(
                engineFactory = ::DeterministicConnectorEngine,
                transport = transport,
                ownership = ConnectorResourceOwnership.Borrowed,
            )
        val second =
            UniversalAiConnector.createForTesting(
                engineFactory = ::DeterministicConnectorEngine,
                transport = transport,
                ownership = ConnectorResourceOwnership.Borrowed,
            )

        first.close()
        second.close()

        assertEquals(0, transport.closeCalls)
        assertEquals(
            "usable",
            transport.execute(
                request =
                    ConnectorTransportRequest(
                        method = "GET",
                        baseUrl = ConnectorBaseUrl.parse("https://example.invalid"),
                        endpoint = "probe",
                    ),
            ) {
                "usable"
            },
        )
        assertEquals(1, transport.executeCalls)
    }

    @Test
    fun constructionFailureClosesOnlyOwnedTransportAndPreservesFailure() {
        val expected = IllegalStateException("construction failed")
        val ownedTransport = RecordingTransport()
        val ownedFailure =
            assertFailsWith<IllegalStateException> {
                UniversalAiConnector.createForTesting(
                    engineFactory = { throw expected },
                    transport = ownedTransport,
                    ownership = ConnectorResourceOwnership.Owned,
                )
            }

        assertSame(expected, ownedFailure)
        assertEquals(1, ownedTransport.closeCalls)

        val borrowedTransport = RecordingTransport()
        val borrowedFailure =
            assertFailsWith<IllegalStateException> {
                UniversalAiConnector.createForTesting(
                    engineFactory = { throw expected },
                    transport = borrowedTransport,
                    ownership = ConnectorResourceOwnership.Borrowed,
                )
            }

        assertSame(expected, borrowedFailure)
        assertEquals(0, borrowedTransport.closeCalls)
    }

    @Test
    fun injectedEngineRemainsCallerOwnedSharedAndUnusedByDeterministicBehavior() = runTest {
        val engine =
            MockEngine {
                respond(
                    content = ByteReadChannel("probe"),
                    status = HttpStatusCode.OK,
                )
            }
        val first = UniversalAiConnector(engine)
        val second = UniversalAiConnector(engine)

        try {
            assertEquals(
                "Kotlin echo: first",
                first.respond(request("first")).outputs.single().text,
            )
            first.close()
            assertEquals(
                "Kotlin echo: second",
                second.respond(request("second")).outputs.single().text,
            )
            second.close()
            assertTrue(engine.requestHistory.isEmpty())

            val callerClient = HttpClient(engine)
            try {
                assertEquals(
                    "probe",
                    callerClient.get("https://example.invalid/probe").bodyAsText(),
                )
            } finally {
                callerClient.close()
            }
            assertEquals(1, engine.requestHistory.size)
        } finally {
            first.close()
            second.close()
            engine.close()
        }
    }

    @Test
    fun useAfterCloseReturnsOneStableCanonicalErrorAndVersionRemainsReadable() = runTest {
        val connector = UniversalAiConnector()
        val streamCreatedBeforeClose = connector.stream(request("stream"))
        connector.close()

        assertEquals(UniversalAiConnector.LIBRARY_VERSION, connector.version)
        assertClosedFailure(
            assertFailsWith<UniversalAiException> {
                connector.respond(request("response"))
            },
        )

        val events = mutableListOf<UniversalAiStreamEvent>()
        assertClosedFailure(
            assertFailsWith<UniversalAiException> {
                streamCreatedBeforeClose.collect(events::add)
            },
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun closeCancelsActiveResponseAndStreamWithoutLateDelivery() = runTest {
        val engine = BlockingEngine()
        val connector = UniversalAiConnector(engine)
        val response =
            backgroundScope.async {
                connector.respond(request("response"))
            }
        val stream =
            backgroundScope.async {
                connector.stream(request("stream")).toList()
            }

        engine.responseStarted.await()
        engine.streamStarted.await()
        connector.close()

        assertFailsWith<CancellationException> {
            response.await()
        }
        assertFailsWith<CancellationException> {
            stream.await()
        }
        assertTrue(engine.responseCancelled)
        assertTrue(engine.streamCancelled)
    }

    @Test
    fun closeCancelsPendingTransportResponseThroughRegisteredAdapter() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val requestCancelled = CompletableDeferred<Unit>()
        val httpEngine =
            MockEngine {
                requestStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    requestCancelled.complete(Unit)
                }
            }
        val transport = createKtorTransport(httpEngine)
        val connector =
            transportBackedConnector(
                transport = transport,
                adapter = ::TransportBackedFakeAdapter,
            )

        try {
            val operation =
                backgroundScope.async {
                    connector.respond(request("response", providerId = FAKE_PROVIDER_ID))
                }
            requestStarted.await()

            connector.close()

            assertFailsWith<CancellationException> {
                operation.await()
            }
            requestCancelled.await()
        } finally {
            connector.close()
            httpEngine.close()
        }
    }

    @Test
    fun closeCancelsActiveTransportStreamAndSuppressesLateTerminalDelivery() = runTest {
        val responseBody = ByteChannel(autoFlush = true)
        val responseStarted = CompletableDeferred<Unit>()
        val firstEventDelivered = CompletableDeferred<Unit>()
        val httpEngine =
            MockEngine {
                responseStarted.complete(Unit)
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                )
            }
        val connector =
            transportBackedConnector(
                transport = createKtorTransport(httpEngine),
                adapter = ::TransportBackedFakeAdapter,
            )
        val delivered = mutableListOf<UniversalAiStreamEvent>()

        try {
            val operation =
                backgroundScope.async {
                    connector
                        .stream(request("stream", providerId = FAKE_PROVIDER_ID))
                        .collect { event ->
                            delivered += event
                            firstEventDelivered.complete(Unit)
                        }
                }
            responseStarted.await()
            responseBody.writeStringUtf8("data: started\n\n")
            firstEventDelivered.await()

            connector.close()

            assertFailsWith<CancellationException> {
                operation.await()
            }
            assertTrue(responseBody.isClosedForRead)
            assertEquals(
                listOf(UniversalAiStreamEventType.ResponseStarted),
                delivered.map(UniversalAiStreamEvent::type),
            )
            assertTrue(delivered.none(UniversalAiStreamEvent::terminal))
        } finally {
            connector.close()
            responseBody.cancel(CancellationException("test cleanup"))
            httpEngine.close()
        }
    }

    @Test
    fun firstTerminalEventStopsTransportStreamExactlyOnce() = runTest {
        val httpEngine =
            MockEngine {
                respond(
                    content =
                        ByteReadChannel(
                            "data: started\n\n" +
                                "data: completed\n\n" +
                                "data: late\n\n",
                        ),
                    status = HttpStatusCode.OK,
                )
            }
        val connector =
            transportBackedConnector(
                transport = createKtorTransport(httpEngine),
                adapter = ::TransportBackedFakeAdapter,
            )

        try {
            val delivered =
                connector
                    .stream(request("stream", providerId = FAKE_PROVIDER_ID))
                    .toList()

            assertEquals(
                listOf(
                    UniversalAiStreamEventType.ResponseStarted,
                    UniversalAiStreamEventType.ResponseCompleted,
                ),
                delivered.map(UniversalAiStreamEvent::type),
            )
            assertEquals(1, delivered.count(UniversalAiStreamEvent::terminal))
        } finally {
            connector.close()
            httpEngine.close()
        }
    }

    @Test
    fun transportStreamCannotCompleteSuccessfullyWithoutOneTerminalEvent() = runTest {
        val httpEngine =
            MockEngine {
                respond(
                    content = ByteReadChannel("data: started\n\n"),
                    status = HttpStatusCode.OK,
                )
            }
        val connector =
            transportBackedConnector(
                transport = createKtorTransport(httpEngine),
                adapter = ::TransportBackedFakeAdapter,
            )
        val delivered = mutableListOf<UniversalAiStreamEvent>()

        try {
            val failure =
                assertFailsWith<UniversalAiException> {
                    connector
                        .stream(request("stream", providerId = FAKE_PROVIDER_ID))
                        .collect(delivered::add)
                }

            assertEquals(UniversalAiErrorCategory.Internal, failure.error.category)
            assertEquals(UniversalAiErrorCode.ConnectorFailure, failure.error.code)
            assertEquals(
                listOf(UniversalAiStreamEventType.ResponseStarted),
                delivered.map(UniversalAiStreamEvent::type),
            )
            assertTrue(delivered.none(UniversalAiStreamEvent::terminal))
        } finally {
            connector.close()
            httpEngine.close()
        }
    }

    private fun assertClosedFailure(failure: UniversalAiException) {
        assertEquals(UniversalAiErrorCategory.Validation, failure.error.category)
        assertEquals(UniversalAiErrorCode.InvalidRequest, failure.error.code)
        assertEquals(UniversalAiConnector.CLOSED_MESSAGE, failure.error.message)
        assertEquals(null, failure.error.metadata)
        assertTrue(failure.error.extensions.isEmpty)
    }

    private fun request(
        content: String,
        providerId: String = "deterministic",
    ): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = ProviderId.of(providerId),
                    modelId = ModelId.of("echo-v1"),
                ),
            input =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = content,
                    ),
            ),
        )

    private fun transportBackedConnector(
        transport: ConnectorTransport,
        adapter: (ConnectorTransport) -> ConnectorEngine,
    ): UniversalAiConnector =
        UniversalAiConnector.createForTesting(
            engineFactory = ::DeterministicConnectorEngine,
            transport = transport,
            ownership = ConnectorResourceOwnership.Owned,
            providerRegistrations =
                listOf(
                    ProviderRegistration(
                        providerId = ProviderId.of(FAKE_PROVIDER_ID),
                        adapterFactory = adapter,
                    ),
                ),
        )

    private class RecordingTransport : ConnectorTransport {
        var closeCalls: Int = 0
            private set
        var executeCalls: Int = 0
            private set

        override suspend fun <Result> execute(
            request: ConnectorTransportRequest,
            consumeResponse: suspend (ConnectorTransportResponse) -> Result,
        ): Result {
            executeCalls += 1
            return consumeResponse(
                ConnectorTransportResponse(
                    statusCode = 204,
                    headers = emptyList(),
                    body = ConnectorTransportChunkReader { null },
                ),
            )
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private class BlockingEngine : ConnectorEngine {
        val responseStarted = CompletableDeferred<Unit>()
        val streamStarted = CompletableDeferred<Unit>()
        var responseCancelled: Boolean = false
            private set
        var streamCancelled: Boolean = false
            private set

        override suspend fun respond(request: UniversalAiRequest): UniversalAiResponse {
            responseStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                responseCancelled = true
            }
        }

        override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> = flow {
            streamStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                streamCancelled = true
            }
        }
    }

    private class TransportBackedFakeAdapter(
        private val transport: ConnectorTransport,
    ) : ConnectorEngine {
        override suspend fun respond(request: UniversalAiRequest): UniversalAiResponse =
            transport.execute(transportRequest()) { response ->
                response.body.readChunk()
                response(request)
            }

        override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> =
            channelFlow {
                transport.execute(transportRequest()) { response ->
                    val reader = ConnectorServerSentEventReader(response.body)
                    while (true) {
                        val event = reader.readEvent() ?: break
                        send(streamEvent(event.data, request))
                    }
                }
            }

        private fun transportRequest(): ConnectorTransportRequest =
            ConnectorTransportRequest(
                method = "POST",
                baseUrl = ConnectorBaseUrl.parse("https://example.invalid"),
                endpoint = "fake",
            )

        private fun streamEvent(
            data: String,
            request: UniversalAiRequest,
        ): UniversalAiStreamEvent {
            val response = response(request)
            return when (data) {
                "completed" ->
                    UniversalAiStreamEvent(
                        type = UniversalAiStreamEventType.ResponseCompleted,
                        terminal = true,
                        sequence = 2,
                        responseId = response.id,
                        response = response,
                    )

                "started" ->
                    UniversalAiStreamEvent(
                        type = UniversalAiStreamEventType.ResponseStarted,
                        terminal = false,
                        sequence = 1,
                        responseId = response.id,
                    )

                else ->
                    UniversalAiStreamEvent(
                        type = UniversalAiStreamEventType.ResponseStarted,
                        terminal = false,
                        sequence = 3,
                        responseId = response.id,
                    )
            }
        }

        private fun response(request: UniversalAiRequest): UniversalAiResponse =
            UniversalAiResponse(
                id = ResponseId.of("fake-response"),
                target = request.target,
                outputs = emptyList(),
                completionReason = UniversalAiCompletionReason.Stop,
            )
    }

    private companion object {
        const val FAKE_PROVIDER_ID = "fake-provider"
    }
}
