package com.maneesh.universalai.apple

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppleConnectorBridgeTests {
    @Test
    fun versionAndResponseUseProductFacingValues() = runTest {
        val bridge = AppleConnectorBridge(this)
        val successes = mutableListOf<String>()
        val errors = mutableListOf<AppleBridgeError>()

        assertEquals("0.1.0-alpha.1", bridge.version())
        bridge.respond(
            input = " hello ",
            onSuccess = successes::add,
            onError = errors::add,
        )
        advanceUntilIdle()

        assertEquals(listOf("Kotlin echo: hello"), successes)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun responseFailuresUseStableProductErrorsExactlyOnce() = runTest {
        val bridge = AppleConnectorBridge(this)
        val successes = mutableListOf<String>()
        val errors = mutableListOf<AppleBridgeError>()

        bridge.respond(
            input = "__force_error__",
            onSuccess = successes::add,
            onError = errors::add,
        )
        advanceUntilIdle()

        assertTrue(successes.isEmpty())
        assertEquals(1, errors.size)
        assertEquals("simulated_failure", errors.single().code)
        assertEquals(
            "The Universal AI Connector produced the requested simulated failure.",
            errors.single().message,
        )
    }

    @Test
    fun streamEmitsOrderedEventsAndOneCompletion() = runTest {
        val bridge = AppleConnectorBridge(this)
        val events = mutableListOf<AppleBridgeStreamEvent>()
        var completions = 0
        val errors = mutableListOf<AppleBridgeError>()

        bridge.stream(
            input = "chunk",
            onEvent = events::add,
            onComplete = { completions += 1 },
            onError = errors::add,
        )
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3, 4, 5), events.map { it.sequence })
        assertEquals(
            listOf("chunk 1", "chunk 2", "chunk 3", "chunk 4", "chunk 5"),
            events.map { it.text },
        )
        assertEquals(1, completions)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun streamFailureUsesOneStableErrorTerminal() = runTest {
        val bridge = AppleConnectorBridge(this)
        val events = mutableListOf<AppleBridgeStreamEvent>()
        var completions = 0
        val errors = mutableListOf<AppleBridgeError>()

        bridge.stream(
            input = "__force_error__",
            onEvent = events::add,
            onComplete = { completions += 1 },
            onError = errors::add,
        )
        advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertEquals(0, completions)
        assertEquals(1, errors.size)
        assertEquals("simulated_failure", errors.single().code)
    }

    @Test
    fun responseCancellationDeliversNoCallback() = runTest {
        val bridge = AppleConnectorBridge(this)
        bridge.resetInstrumentation()
        var callbackDelivered = false

        val handle =
            bridge.respond(
                input = "cancel",
                onSuccess = { callbackDelivered = true },
                onError = { callbackDelivered = true },
            )
        handle.cancel()
        runCurrent()
        advanceUntilIdle()

        assertFalse(callbackDelivered)
        assertEquals(1, bridge.instrumentationSnapshot().responseCancellations)
    }

    @Test
    fun streamCancellationAfterFirstEventDeliversNoTerminal() = runTest {
        val bridge = AppleConnectorBridge(this)
        bridge.resetInstrumentation()
        val events = mutableListOf<AppleBridgeStreamEvent>()
        var terminalDelivered = false
        lateinit var handle: AppleCancellationHandle

        handle =
            bridge.stream(
                input = "stop",
                onEvent = { event ->
                    events += event
                    handle.cancel()
                },
                onComplete = { terminalDelivered = true },
                onError = { terminalDelivered = true },
            )
        advanceUntilIdle()

        assertEquals(listOf(1), events.map { it.sequence })
        assertFalse(terminalDelivered)
        assertEquals(1, bridge.instrumentationSnapshot().streamCancellations)
    }

    @Test
    fun concurrentOperationsKeepIndependentExactlyOnceTerminals() = runTest {
        val bridge = AppleConnectorBridge(this)
        val responses = mutableListOf<String>()
        val responseErrors = mutableListOf<AppleBridgeError>()
        val streamEvents = List(3) { mutableListOf<AppleBridgeStreamEvent>() }
        val streamCompletions = MutableList(3) { 0 }
        val streamErrors = List(3) { mutableListOf<AppleBridgeError>() }

        repeat(3) { index ->
            bridge.respond(
                input = "request-$index",
                onSuccess = responses::add,
                onError = responseErrors::add,
            )
            bridge.stream(
                input = "stream-$index",
                onEvent = streamEvents[index]::add,
                onComplete = { streamCompletions[index] += 1 },
                onError = streamErrors[index]::add,
            )
        }
        advanceUntilIdle()

        assertEquals(
            setOf(
                "Kotlin echo: request-0",
                "Kotlin echo: request-1",
                "Kotlin echo: request-2",
            ),
            responses.toSet(),
        )
        assertEquals(3, responses.size)
        assertTrue(responseErrors.isEmpty())
        repeat(3) { index ->
            assertEquals(listOf(1, 2, 3, 4, 5), streamEvents[index].map { it.sequence })
            assertEquals(1, streamCompletions[index])
            assertTrue(streamErrors[index].isEmpty())
        }
    }
}
