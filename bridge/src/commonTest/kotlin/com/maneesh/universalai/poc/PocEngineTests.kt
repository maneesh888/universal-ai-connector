package com.maneesh.universalai.poc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PocEngineTests {
    private val engine = PocEngine()

    @Test
    fun synchronousCallsReturnDeterministicValues() {
        assertEquals("universal-ai-connector-poc/0.1.0", engine.version())
        assertEquals("Hello, iOS, from Kotlin.", engine.greeting("iOS"))
    }

    @Test
    fun asyncResponseCompletesWithEcho() = runTest {
        assertEquals("Kotlin echo: hello", engine.respond("hello"))
    }

    @Test
    fun forcedErrorPreservesCodeAndMessage() = runTest {
        val failure = assertFailsWith<PocFailure> {
            engine.respond(PocEngine.FORCE_ERROR_INPUT)
        }

        assertEquals("simulated_failure", failure.code)
        assertEquals(
            "The Kotlin POC produced the requested simulated failure.",
            failure.message,
        )
    }

    @Test
    fun streamEmitsOrderedEvents() = runTest {
        val events = engine.stream("chunk").toList()

        assertEquals(listOf(1, 2, 3, 4, 5), events.map { it.sequence })
        assertEquals(
            listOf("chunk 1", "chunk 2", "chunk 3", "chunk 4", "chunk 5"),
            events.map { it.text },
        )
    }

    @Test
    fun cancellationHandleStopsFlowAndRecordsCancellation() = runTest {
        PocInstrumentation.reset()
        val firstEvent = CompletableDeferred<Unit>()
        val receivedEvents = mutableListOf<PocStreamEvent>()
        val bridge = PocBridge(backgroundScope, engine)

        val handle = bridge.stream(
            input = "cancel",
            onEvent = {
                receivedEvents += it
                firstEvent.complete(Unit)
            },
            onComplete = {
                error("Cancelled stream must not complete.")
            },
            onError = {
                error("Cancelled stream must not fail: ${it.code}")
            },
        )

        async { firstEvent.await() }.await()
        handle.cancel()
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, receivedEvents.size)
        assertEquals(1, bridge.instrumentationSnapshot().streamCancellations)
    }

    @Test
    fun cancellationHandleStopsUnaryCallbackAndRecordsCancellation() = runTest {
        PocInstrumentation.reset()
        var callbackDelivered = false
        val bridge = PocBridge(backgroundScope, engine)

        val handle = bridge.respond(
            input = "cancel",
            onSuccess = { callbackDelivered = true },
            onError = { callbackDelivered = true },
        )

        handle.cancel()
        runCurrent()
        advanceUntilIdle()

        assertTrue(!callbackDelivered)
        assertEquals(1, bridge.instrumentationSnapshot().unaryCancellations)
    }
}
