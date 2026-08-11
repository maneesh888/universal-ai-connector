package com.maneesh.universalai.connector.internal.provider.openaicompatible

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiProviderConfiguration
import com.maneesh.universalai.connector.contract.ModelId
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
import com.maneesh.universalai.connector.internal.provider.OPENAI_COMPATIBLE_PROVIDER_ID
import com.maneesh.universalai.connector.internal.provider.ProviderRegistration
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportChunkReader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportHeader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportRequest
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
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

/** P7-C lifecycle acceptance for the frozen Gateway path through the existing generic adapter. */
class OpenAiCompatibleGatewayP7CLifecycleTests {
    @Test
    fun concurrentGatewayResponsesAndStreamsKeepCredentialsAndTranslationStateIsolated() = runTest {
        var credentialCalls = 0
        val transport = ConcurrentGatewayTransport(expectedRequestCount = 8)
        val connector =
            connector(
                transport = transport,
                credentialSupplier = {
                    credentialCalls += 1
                    "$SYNTHETIC_CREDENTIAL-$credentialCalls"
                },
            )

        try {
            val operations =
                (1..4).flatMap { index ->
                    val responseInput = "gateway-response-$index"
                    val streamInput = "gateway-stream-$index"
                    listOf(
                        async {
                            responseInput to
                                connector.respond(request(responseInput)).outputs.single().text
                        },
                        async {
                            val events = connector.stream(request(streamInput)).toList()
                            assertEquals(
                                (1L..events.size.toLong()).toList(),
                                events.map(UniversalAiStreamEvent::sequence),
                            )
                            assertEquals(1, events.count(UniversalAiStreamEvent::terminal))
                            streamInput to events.last().response?.outputs?.single()?.text
                        },
                    )
                }

            assertEquals(
                (1..4).flatMapTo(mutableSetOf()) { index ->
                    setOf(
                        "gateway-response-$index" to "gateway-response-$index-result",
                        "gateway-stream-$index" to "gateway-stream-$index-result",
                    )
                },
                operations.awaitAll().toSet(),
            )
            assertEquals(8, credentialCalls)
            assertEquals(8, transport.maxConcurrentRequests)
            assertEquals(
                (1..8).mapTo(mutableSetOf()) { index -> "Bearer $SYNTHETIC_CREDENTIAL-$index" },
                transport.observations.mapTo(mutableSetOf(), GatewayObservation::authorization),
            )
            assertEquals(
                (1..4).flatMapTo(mutableSetOf()) { index ->
                    setOf(
                        "gateway-response-$index" to false,
                        "gateway-stream-$index" to true,
                    )
                },
                transport.observations.mapTo(mutableSetOf()) { observation ->
                    observation.input to observation.streaming
                },
            )
        } finally {
            connector.close()
        }
    }

    @Test
    fun concurrentCloseCancelsGatewayResponseAndStreamAndReleasesTheActiveBody() = runTest {
        var credentialCalls = 0
        val pendingResponseStarted = CompletableDeferred<Unit>()
        val pendingResponseCancelled = CompletableDeferred<Unit>()
        val streamResponseStarted = CompletableDeferred<Unit>()
        val streamBodyCancelled = CompletableDeferred<Unit>()
        val firstStreamEventDelivered = CompletableDeferred<Unit>()
        val transport =
            ClosingGatewayTransport(
                pendingResponseStarted = pendingResponseStarted,
                pendingResponseCancelled = pendingResponseCancelled,
                streamResponseStarted = streamResponseStarted,
                streamBodyCancelled = streamBodyCancelled,
            )
        val connector =
            connector(
                transport = transport,
                credentialSupplier = {
                    credentialCalls += 1
                    SYNTHETIC_CREDENTIAL
                },
            )
        val delivered = mutableListOf<UniversalAiStreamEvent>()

        try {
            val pendingResponse =
                backgroundScope.async {
                    connector.respond(request("pending-gateway-response"))
                }
            val activeStream =
                backgroundScope.async {
                    connector
                        .stream(request("active-gateway-stream"))
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
                    connector.respond(request("after-close"))
                }
            assertEquals(UniversalAiErrorCategory.Validation, closedFailure.error.category)
            assertEquals(UniversalAiErrorCode.InvalidRequest, closedFailure.error.code)
            assertFalse(closedFailure.stackTraceToString().contains(SYNTHETIC_CREDENTIAL))
            assertEquals(2, credentialCalls)
        } finally {
            connector.close()
        }
    }

    private fun connector(
        transport: ConnectorTransport,
        credentialSupplier: () -> String,
    ): UniversalAiConnector {
        val configuration =
            UniversalAiProviderConfiguration(
                providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                baseUrl = GATEWAY_BASE_URL,
                credentialSupplier = credentialSupplier,
            )
        return UniversalAiConnector.createForTesting(
            engineFactory = ::DeterministicConnectorEngine,
            transport = transport,
            ownership = ConnectorResourceOwnership.Owned,
            providerRegistrations =
                listOf(
                    ProviderRegistration(
                        providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                        adapterFactory = { boundTransport ->
                            OpenAiCompatibleChatCompletionsAdapter(configuration, boundTransport)
                        },
                    ),
                ),
        )
    }

    private fun request(content: String): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
                    modelId = ModelId.of("gateway-selected-model"),
                ),
            input = listOf(UniversalAiTextInput(UniversalAiInputRole.User, content)),
        )

    private companion object {
        const val GATEWAY_BASE_URL: String = "https://gateway.example.invalid/v1"
        const val SYNTHETIC_CREDENTIAL: String = "synthetic-p7c-gateway-credential"
    }

    private data class GatewayObservation(
        val input: String,
        val streaming: Boolean,
        val authorization: String,
    )

    private class ConcurrentGatewayTransport(
        private val expectedRequestCount: Int,
    ) : ConnectorTransport {
        private val observationLock = Mutex()
        private val allRequestsStarted = CompletableDeferred<Unit>()
        private val mutableObservations = mutableListOf<GatewayObservation>()

        val observations: List<GatewayObservation>
            get() = mutableObservations.toList()

        var maxConcurrentRequests: Int = 0
            private set

        override suspend fun <Result> execute(
            request: ConnectorTransportRequest,
            consumeResponse: suspend (ConnectorTransportResponse) -> Result,
        ): Result {
            assertEquals("$GATEWAY_BASE_URL/chat/completions", request.url)
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
                GatewayObservation(
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
                    successfulGatewayStream("${observation.input}-result")
                } else {
                    successfulGatewayResponseWithoutUsage("${observation.input}-result")
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

    private class ClosingGatewayTransport(
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
            assertEquals("$GATEWAY_BASE_URL/chat/completions", request.url)
            val authorization =
                request.headers
                    .single { header -> header.name.equals("authorization", ignoreCase = true) }
                    .value
            assertEquals("Bearer $SYNTHETIC_CREDENTIAL", authorization)
            assertFalse(checkNotNull(request.body).decodeToString().contains(SYNTHETIC_CREDENTIAL))
            executeCalls += 1
            return if (request.body.isStreamingRequest()) {
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
                                        firstGatewayStreamRecord().encodeToByteArray()
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
}

private fun ByteArray?.isStreamingRequest(): Boolean =
    this != null &&
        Json
            .parseToJsonElement(decodeToString())
            .jsonObject["stream"]
            ?.jsonPrimitive
            ?.boolean == true

private fun successfulGatewayResponseWithoutUsage(text: String): String =
    """
    {
      "id":"chatcmpl_gateway_p7c",
      "object":"chat.completion",
      "model":"gateway-selected-model",
      "choices":[{
        "index":0,
        "message":{"role":"assistant","content":${JsonPrimitive(text)}},
        "finish_reason":"stop"
      }]
    }
    """.trimIndent()

private fun successfulGatewayStream(text: String): String =
    firstGatewayStreamRecord() +
        gatewaySse(
            """{"id":"chatcmpl_gateway_p7c","object":"chat.completion.chunk","created":123,"model":"gateway-selected-model","choices":[{"index":0,"delta":{"content":${JsonPrimitive(text)}}}]}""",
        ) +
        gatewaySse(
            """{"id":"chatcmpl_gateway_p7c","object":"chat.completion.chunk","created":123,"model":"gateway-selected-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
        ) +
        gatewaySse("[DONE]")

private fun firstGatewayStreamRecord(): String =
    gatewaySse(
        """{"id":"chatcmpl_gateway_p7c","object":"chat.completion.chunk","created":123,"model":"gateway-selected-model","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
    )

private fun gatewaySse(data: String): String = "data: $data\n\n"
