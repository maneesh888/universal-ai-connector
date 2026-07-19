package com.maneesh.universalai.connector

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.onCompletion
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
    fun versionAndOneShotResponseAreProductFacing() = runTest {
        assertEquals("0.1.0-alpha.1", connector.version)
        assertEquals("Kotlin echo: hello", connector.respond(" hello "))
    }

    @Test
    fun invalidInputAndForcedFailureUseStableTypedErrors() = runTest {
        val invalidInput = assertFailsWith<UniversalAiConnectorException> {
            connector.respond("  ")
        }
        assertEquals(UniversalAiErrorCode.INVALID_INPUT, invalidInput.code)
        assertEquals("invalid_input", invalidInput.code.stableValue)
        assertEquals("Input must not be empty.", invalidInput.message)

        val forcedFailure = assertFailsWith<UniversalAiConnectorException> {
            connector.respond(UniversalAiConnector.SIMULATED_ERROR_INPUT)
        }
        assertEquals(UniversalAiErrorCode.SIMULATED_FAILURE, forcedFailure.code)
        assertEquals("simulated_failure", forcedFailure.code.stableValue)
        assertEquals(
            "The Universal AI Connector produced the requested simulated failure.",
            forcedFailure.message,
        )
    }

    @Test
    fun streamEmitsOrderedEventsAndCompletes() = runTest {
        var completionCause: Throwable? = IllegalStateException("Not completed")
        val events =
            connector
                .stream("chunk")
                .onCompletion { completionCause = it }
                .toList()

        assertEquals(listOf(1, 2, 3, 4, 5), events.map { it.sequence })
        assertEquals(
            listOf("chunk 1", "chunk 2", "chunk 3", "chunk 4", "chunk 5"),
            events.map { it.text },
        )
        assertNull(completionCause)
    }

    @Test
    fun streamFailureUsesTheStableTypedError() = runTest {
        val failure = assertFailsWith<UniversalAiConnectorException> {
            connector.stream(UniversalAiConnector.SIMULATED_ERROR_INPUT).toList()
        }

        assertEquals(UniversalAiErrorCode.SIMULATED_FAILURE, failure.code)
        assertEquals("simulated_failure", failure.code.stableValue)
    }

    @Test
    fun cancellationBeforeResponseDeliveryProducesNoResult() = runTest {
        var delivered = false
        val request =
            launch(start = CoroutineStart.UNDISPATCHED) {
                connector.respond("cancel me")
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
                connector.stream("stop").collect { event ->
                    events += event
                    firstEvent.complete(Unit)
                }
            }

        firstEvent.await()
        collection.cancelAndJoin()
        runCurrent()
        advanceUntilIdle()

        assertTrue(collection.isCancelled)
        assertEquals(listOf(1), events.map { it.sequence })
    }

    @Test
    fun clientSupportsConcurrentRequestsAndStreams() = runTest {
        val responses =
            List(3) { index ->
                async { connector.respond("request-$index") }
            }.awaitAll()
        val streams =
            List(3) { index ->
                async { connector.stream("stream-$index").toList() }
            }.awaitAll()

        assertEquals(
            listOf(
                "Kotlin echo: request-0",
                "Kotlin echo: request-1",
                "Kotlin echo: request-2",
            ),
            responses,
        )
        streams.forEachIndexed { index, events ->
            assertEquals(listOf(1, 2, 3, 4, 5), events.map { it.sequence })
            assertEquals("stream-$index 1", events.first().text)
            assertEquals("stream-$index 5", events.last().text)
        }
    }
}
