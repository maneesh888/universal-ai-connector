package com.maneesh.universalai.connector

import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.OutputId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.ResponseId
import com.maneesh.universalai.connector.contract.StructuredOutputSchema
import com.maneesh.universalai.connector.contract.UniversalAiCompletionReason
import com.maneesh.universalai.connector.contract.UniversalAiError
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiGenerationParameters
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiOutputKind
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponseFormat
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiStreamSequenceValidator
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.contract.UNEXPECTED_CONNECTOR_FAILURE_MESSAGE
import com.maneesh.universalai.connector.internal.ConnectorEngine
import com.maneesh.universalai.connector.internal.DeterministicConnectorEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UniversalAiConnectorTests {
    private val connector = UniversalAiConnector()

    @Test
    fun versionAndOneShotResponseUseTheCanonicalShape() = runTest {
        assertEquals("0.1.0-alpha.1", connector.version)

        val request = request(" hello ")
        val response = connector.respond(request)

        assertEquals(ResponseId.of("deterministic-response"), response.id)
        assertNull(response.requestId)
        assertEquals(request.target, response.target)
        assertNull(response.usage)
        assertEquals(UniversalAiCompletionReason.Stop, response.completionReason)
        assertEquals(1, response.outputs.size)
        with(response.outputs.single()) {
            assertEquals(OutputId.of("deterministic-output-0"), id)
            assertEquals(0, index)
            assertEquals(UniversalAiOutputKind.Text, kind)
            assertEquals("Kotlin echo:  hello ", text)
            assertNull(structuredJson)
        }
    }

    @Test
    fun responsePreservesOrderedInputWithoutNormalization() = runTest {
        val request =
            request(
                UniversalAiTextInput(UniversalAiInputRole.System, "  system  "),
                UniversalAiTextInput(UniversalAiInputRole.User, "user\nline"),
                UniversalAiTextInput(UniversalAiInputRole.Assistant, "\tassistant\t"),
            )

        assertEquals(
            "Kotlin echo:   system  \nuser\nline\n\tassistant\t",
            connector.respond(request).outputs.single().text,
        )
    }

    @Test
    fun unsupportedTargetFormatAndGenerationUseFixedValidationErrors() = runTest {
        assertInvalidRequest(
            request =
                request(
                    "hello",
                    providerId = "other",
                ),
            message = "The deterministic connector supports only target deterministic/echo-v1.",
        )
        assertInvalidRequest(
            request =
                request(
                    "hello",
                    modelId = "other-model",
                ),
            message = "The deterministic connector supports only target deterministic/echo-v1.",
        )
        assertInvalidRequest(
            request =
                request(
                    "hello",
                    responseFormat =
                        UniversalAiResponseFormat.jsonSchema(
                            StructuredOutputSchema.parse("""{"type":"object"}"""),
                        ),
                ),
            message = "The deterministic connector supports only plain-text responses.",
        )
        assertInvalidRequest(
            request =
                request(
                    "hello",
                    generation = UniversalAiGenerationParameters(maxOutputTokens = 1),
                ),
            message = "The deterministic connector does not support generation parameters.",
        )
    }

    @Test
    fun exactSingleUserSentinelUsesTheCanonicalProviderError() = runTest {
        val failure =
            assertFailsWith<UniversalAiException> {
                connector.respond(request(UniversalAiConnector.SIMULATED_ERROR_INPUT))
            }

        assertEquals(UniversalAiErrorCategory.Provider, failure.error.category)
        assertEquals(UniversalAiErrorCode.SimulatedFailure, failure.error.code)
        assertEquals(
            "The Universal AI Connector produced the requested simulated failure.",
            failure.message,
        )

        assertEquals(
            "Kotlin echo: ${UniversalAiConnector.SIMULATED_ERROR_INPUT}",
            connector
                .respond(
                    request(
                        UniversalAiTextInput(
                            UniversalAiInputRole.System,
                            UniversalAiConnector.SIMULATED_ERROR_INPUT,
                        ),
                    ),
                ).outputs
                .single()
                .text,
        )
        assertEquals(
            "Kotlin echo: ${UniversalAiConnector.SIMULATED_ERROR_INPUT}\nsecond",
            connector
                .respond(
                    request(
                        UniversalAiTextInput(
                            UniversalAiInputRole.User,
                            UniversalAiConnector.SIMULATED_ERROR_INPUT,
                        ),
                        UniversalAiTextInput(UniversalAiInputRole.User, "second"),
                    ),
                ).outputs
                .single()
                .text,
        )
    }

    @Test
    fun streamEmitsOneValidatorValidCanonicalSequenceAndCompletesNormally() = runTest {
        var completionCause: Throwable? = IllegalStateException("Not completed")
        val validator = UniversalAiStreamSequenceValidator()
        val events =
            connector
                .stream(request("chunk"))
                .onEach(validator::accept)
                .onCompletion { completionCause = it }
                .toList()

        validator.finish()
        assertEquals((1L..6L).toList(), events.map { it.sequence })
        assertEquals(
            listOf(
                UniversalAiStreamEventType.ResponseStarted,
                UniversalAiStreamEventType.OutputStarted,
                UniversalAiStreamEventType.OutputDelta,
                UniversalAiStreamEventType.OutputDelta,
                UniversalAiStreamEventType.OutputCompleted,
                UniversalAiStreamEventType.ResponseCompleted,
            ),
            events.map { it.type },
        )
        assertEquals(listOf(false, false, false, false, false, true), events.map { it.terminal })
        assertEquals(listOf("Kotlin echo: ", "chunk"), events.mapNotNull { it.delta })
        assertEquals(ResponseId.of("deterministic-response"), events.first().responseId)
        assertEquals(OutputId.of("deterministic-output-0"), events[1].outputId)
        assertEquals("Kotlin echo: chunk", events[4].output?.text)
        assertEquals(events[4].output, events.last().response?.outputs?.single())
        assertNull(events.last().response?.usage)
        assertNull(completionCause)
    }

    @Test
    fun forcedStreamFailureEmitsNoEvents() = runTest {
        val events = mutableListOf<UniversalAiStreamEvent>()
        val failure =
            assertFailsWith<UniversalAiException> {
                connector
                    .stream(request(UniversalAiConnector.SIMULATED_ERROR_INPUT))
                    .collect(events::add)
            }

        assertTrue(events.isEmpty())
        assertEquals(UniversalAiErrorCategory.Provider, failure.error.category)
        assertEquals(UniversalAiErrorCode.SimulatedFailure, failure.error.code)
    }

    @Test
    fun synchronousStreamConstructionFailureUsesTheSafeCanonicalError() = runTest {
        val engine = SynchronousFailureEngine()
        val connector = UniversalAiConnector(engine)
        val stream = connector.stream(request("unsafe source"))
        assertEquals(0, engine.streamCalls)

        val failure =
            assertFailsWith<UniversalAiException> {
                stream.collect {}
            }

        assertEquals(1, engine.streamCalls)
        assertEquals(UniversalAiErrorCategory.Internal, failure.error.category)
        assertEquals(UniversalAiErrorCode.ConnectorFailure, failure.error.code)
        assertEquals(UNEXPECTED_CONNECTOR_FAILURE_MESSAGE, failure.error.message)
        assertFalse(failure.error.message.contains(SYNCHRONOUS_FAILURE_DETAIL))
    }

    @Test
    fun synchronousStreamConstructionCancellationRetainsIdentity() = runTest {
        val cancellation = CancellationException("cancel the caller")
        val connector = UniversalAiConnector(SynchronousCancellationEngine(cancellation))

        val delivered =
            assertFailsWith<CancellationException> {
                connector.stream(request("cancel")).collect {}
            }

        assertTrue(delivered === cancellation)
    }

    @Test
    fun canonicalFailureAfterPartialDeltaPreservesEventsAndHasNoTerminalCompletion() = runTest {
        val connector = UniversalAiConnector(PartialFailureEngine())
        val events = mutableListOf<UniversalAiStreamEvent>()
        var completionCause: Throwable? = null

        val failure =
            assertFailsWith<UniversalAiException> {
                connector
                    .stream(request("partial failure"))
                    .onCompletion { completionCause = it }
                    .collect(events::add)
            }

        assertEquals(listOf(1L, 2L, 3L), events.map { it.sequence })
        assertEquals(
            listOf(
                UniversalAiStreamEventType.ResponseStarted,
                UniversalAiStreamEventType.OutputStarted,
                UniversalAiStreamEventType.OutputDelta,
            ),
            events.map { it.type },
        )
        assertEquals(listOf("Kotlin echo: "), events.mapNotNull { it.delta })
        assertTrue(events.none { event -> event.terminal })
        assertTrue(
            events.none { event ->
                event.type == UniversalAiStreamEventType.ResponseCompleted
            },
        )
        assertTrue(completionCause === failure)
        assertEquals(UniversalAiErrorCategory.Provider, failure.error.category)
        assertEquals(UniversalAiErrorCode.SimulatedFailure, failure.error.code)
    }

    @Test
    fun cancellationBeforeResponseDeliveryProducesNoResult() = runTest {
        var delivered = false
        val request =
            launch(start = CoroutineStart.UNDISPATCHED) {
                connector.respond(request("cancel me"))
                delivered = true
            }

        request.cancelAndJoin()
        runCurrent()
        advanceUntilIdle()

        assertTrue(request.isCancelled)
        assertFalse(delivered)
    }

    @Test
    fun cancellationAfterFirstStreamEventProducesNoLaterEvents() = runTest {
        val firstEvent = CompletableDeferred<Unit>()
        val events = mutableListOf<UniversalAiStreamEvent>()
        val collection =
            backgroundScope.launch {
                connector.stream(request("stop")).collect { event ->
                    events += event
                    firstEvent.complete(Unit)
                }
            }

        firstEvent.await()
        collection.cancelAndJoin()
        runCurrent()
        advanceUntilIdle()

        assertTrue(collection.isCancelled)
        assertEquals(listOf(1L), events.map { it.sequence })
    }

    @Test
    fun cancellationAfterPartialDeltaHasNoFailureEventOrNormalCompletion() = runTest {
        val connector = UniversalAiConnector(PartialCancellationEngine())
        val deltaReceived = CompletableDeferred<Unit>()
        val events = mutableListOf<UniversalAiStreamEvent>()
        var completionCause: Throwable? = null
        val collection =
            backgroundScope.launch {
                connector
                    .stream(request("partial cancellation"))
                    .onCompletion { completionCause = it }
                    .collect { event ->
                        events += event
                        if (event.type == UniversalAiStreamEventType.OutputDelta) {
                            deltaReceived.complete(Unit)
                        }
                    }
            }

        deltaReceived.await()
        collection.cancelAndJoin()
        runCurrent()
        advanceUntilIdle()

        assertTrue(collection.isCancelled)
        assertTrue(completionCause is CancellationException)
        assertEquals(listOf(1L, 2L, 3L), events.map { it.sequence })
        assertEquals(
            listOf(
                UniversalAiStreamEventType.ResponseStarted,
                UniversalAiStreamEventType.OutputStarted,
                UniversalAiStreamEventType.OutputDelta,
            ),
            events.map { it.type },
        )
        assertEquals(listOf("Kotlin echo: "), events.mapNotNull { it.delta })
        assertTrue(events.none { event -> event.terminal })
        assertTrue(
            events.none { event ->
                event.type == UniversalAiStreamEventType.ResponseCompleted
            },
        )
    }

    @Test
    fun clientSupportsConcurrentRequestsAndStreams() = runTest {
        val responses =
            List(3) { index ->
                async { connector.respond(request("request-$index")) }
            }.awaitAll()
        val streams =
            List(3) { index ->
                async { connector.stream(request("stream-$index")).toList() }
            }.awaitAll()

        assertEquals(
            listOf(
                "Kotlin echo: request-0",
                "Kotlin echo: request-1",
                "Kotlin echo: request-2",
            ),
            responses.map { it.outputs.single().text },
        )
        streams.forEachIndexed { index, events ->
            assertEquals((1L..6L).toList(), events.map { it.sequence })
            assertEquals(
                listOf("Kotlin echo: ", "stream-$index"),
                events.mapNotNull { it.delta },
            )
            assertEquals("Kotlin echo: stream-$index", events.last().response?.outputs?.single()?.text)
        }
    }

    @Test
    fun cancellingOneConcurrentStreamDoesNotAffectSiblingOperations() = runTest {
        val firstCancelledEvent = CompletableDeferred<Unit>()
        val cancelledEvents = mutableListOf<UniversalAiStreamEvent>()
        val cancelledStream =
            backgroundScope.launch {
                connector.stream(request("cancel only this stream")).collect { event ->
                    cancelledEvents += event
                    firstCancelledEvent.complete(Unit)
                }
            }
        val siblingResponse =
            async {
                connector.respond(request("independent response"))
            }
        val siblingStream =
            async {
                connector.stream(request("independent stream")).toList()
            }

        firstCancelledEvent.await()
        cancelledStream.cancelAndJoin()
        val response = siblingResponse.await()
        val events = siblingStream.await()
        runCurrent()
        advanceUntilIdle()

        assertTrue(cancelledStream.isCancelled)
        assertEquals(listOf(1L), cancelledEvents.map { it.sequence })
        assertTrue(cancelledEvents.none { event -> event.terminal })
        assertEquals("Kotlin echo: independent response", response.outputs.single().text)
        assertEquals((1L..6L).toList(), events.map { it.sequence })
        assertEquals(1, events.count { event -> event.terminal })
        assertEquals(UniversalAiStreamEventType.ResponseCompleted, events.last().type)
        assertEquals("Kotlin echo: independent stream", events.last().response?.outputs?.single()?.text)
    }

    @Test
    fun streamIsColdAndCanBeRecollectedFromTheBeginning() = runTest {
        val stream = connector.stream(request("again"))

        val firstCollection = stream.toList()
        val secondCollection = stream.toList()

        assertEquals(firstCollection, secondCollection)
        assertEquals((1L..6L).toList(), secondCollection.map { it.sequence })
        assertEquals(UniversalAiStreamEventType.ResponseStarted, secondCollection.first().type)
        assertEquals(UniversalAiStreamEventType.ResponseCompleted, secondCollection.last().type)
        assertEquals(
            UniversalAiCompletionReason.Stop,
            secondCollection.last().response?.completionReason,
        )
    }

    private suspend fun assertInvalidRequest(
        request: UniversalAiRequest,
        message: String,
    ) {
        val responseFailure =
            assertFailsWith<UniversalAiException> {
                connector.respond(request)
            }
        assertCanonicalInvalidRequest(responseFailure, message)

        val streamFailure =
            assertFailsWith<UniversalAiException> {
                connector.stream(request).toList()
            }
        assertCanonicalInvalidRequest(streamFailure, message)
    }

    private fun assertCanonicalInvalidRequest(
        failure: UniversalAiException,
        message: String,
    ) {
        assertEquals(UniversalAiErrorCategory.Validation, failure.error.category)
        assertEquals(UniversalAiErrorCode.InvalidRequest, failure.error.code)
        assertEquals(message, failure.message)
        assertNull(failure.error.metadata)
        assertTrue(failure.error.extensions.isEmpty)
    }

    private fun request(
        content: String,
        providerId: String = "deterministic",
        modelId: String = "echo-v1",
        responseFormat: UniversalAiResponseFormat = UniversalAiResponseFormat.PlainText,
        generation: UniversalAiGenerationParameters = UniversalAiGenerationParameters.Default,
    ): UniversalAiRequest =
        request(
            UniversalAiTextInput(
                role = UniversalAiInputRole.User,
                content = content,
            ),
            providerId = providerId,
            modelId = modelId,
            responseFormat = responseFormat,
            generation = generation,
        )

    private fun request(
        vararg input: UniversalAiTextInput,
        providerId: String = "deterministic",
        modelId: String = "echo-v1",
        responseFormat: UniversalAiResponseFormat = UniversalAiResponseFormat.PlainText,
        generation: UniversalAiGenerationParameters = UniversalAiGenerationParameters.Default,
    ): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = ProviderId.of(providerId),
                    modelId = ModelId.of(modelId),
                ),
            input = input.toList(),
            responseFormat = responseFormat,
            generation = generation,
        )

    private class PartialFailureEngine : ConnectorEngine {
        private val delegate = DeterministicConnectorEngine()

        override suspend fun respond(request: UniversalAiRequest) = delegate.respond(request)

        override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> = flow {
            delegate.stream(request).take(PARTIAL_EVENT_COUNT).collect { event -> emit(event) }
            throw UniversalAiException(
                UniversalAiError(
                    category = UniversalAiErrorCategory.Provider,
                    code = UniversalAiErrorCode.SimulatedFailure,
                    message = PARTIAL_FAILURE_MESSAGE,
                ),
            )
        }
    }

    private class PartialCancellationEngine : ConnectorEngine {
        private val delegate = DeterministicConnectorEngine()

        override suspend fun respond(request: UniversalAiRequest) = delegate.respond(request)

        override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> = flow {
            delegate.stream(request).take(PARTIAL_EVENT_COUNT).collect { event -> emit(event) }
            awaitCancellation()
        }
    }

    private class SynchronousFailureEngine : ConnectorEngine {
        private val delegate = DeterministicConnectorEngine()
        var streamCalls: Int = 0
            private set

        override suspend fun respond(request: UniversalAiRequest) = delegate.respond(request)

        override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> {
            streamCalls += 1
            throw IllegalStateException(SYNCHRONOUS_FAILURE_DETAIL)
        }
    }

    private class SynchronousCancellationEngine(
        private val cancellation: CancellationException,
    ) : ConnectorEngine {
        private val delegate = DeterministicConnectorEngine()

        override suspend fun respond(request: UniversalAiRequest) = delegate.respond(request)

        override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> {
            throw cancellation
        }
    }

    private companion object {
        const val PARTIAL_EVENT_COUNT = 3
        const val PARTIAL_FAILURE_MESSAGE = "The deterministic partial stream failed."
        const val SYNCHRONOUS_FAILURE_DETAIL = "source-sensitive failure detail"
    }
}
