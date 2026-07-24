package com.maneesh.universalai.samples.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSampleControllerTest {
    @Test
    fun completeDemoPublishesEveryDeterministicResult() =
        runTest {
            val controller = AndroidSampleController(this)

            controller.runCompleteDemo()
            advanceUntilIdle()

            val state = controller.state.value
            assertEquals("0.1.0-alpha.1", state.version)
            assertEquals("All deterministic paths passed", state.headline)
            assertEquals("Kotlin echo: hello from Android", state.response)
            assertEquals(
                listOf(
                    "1: response.started",
                    "2: output.started",
                    "3: output.delta · delta=Kotlin echo: ",
                    "4: output.delta · delta=Android stream",
                    "5: output.completed · output=Kotlin echo: Android stream",
                    "6: response.completed · response=Kotlin echo: Android stream · terminal=true",
                ),
                state.streamEvents,
            )
            assertEquals(
                "provider/simulated_failure: " +
                    "The Universal AI Connector produced the requested simulated failure.",
                state.error,
            )
            assertEquals("Cancelled before completion", state.responseCancellation)
            assertEquals(
                "Stopped after 3: output.delta · delta=Kotlin echo: ",
                state.streamCancellation,
            )
            assertFalse(state.isBusy)
        }

    @Test
    fun individualActionsCanBeRunAgain() =
        runTest {
            val controller = AndroidSampleController(this)

            controller.runError()
            advanceUntilIdle()
            assertEquals("Stable error mapping passed", controller.state.value.headline)

            controller.runResponseCancellation()
            advanceUntilIdle()
            assertEquals("Response cancellation passed", controller.state.value.headline)

            controller.runStreamCancellation()
            advanceUntilIdle()
            assertEquals("Stream cancellation passed", controller.state.value.headline)
            assertFalse(controller.state.value.isBusy)
        }

    @Test
    fun immediatelyCompletingActionClearsBusyState() =
        runTest {
            val immediateScope =
                CoroutineScope(coroutineContext + UnconfinedTestDispatcher(testScheduler))
            val controller = AndroidSampleController(immediateScope)

            controller.runResponseCancellation()

            assertEquals("Response cancellation passed", controller.state.value.headline)
            assertFalse(controller.state.value.isBusy)
        }
}
