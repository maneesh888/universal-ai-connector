package com.maneesh.universalai.samples.jvm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmConsoleSampleTests {
    @Test
    fun printsTheExpectedConsumerFacingOutput() = runTest {
        val output = mutableListOf<String>()

        JvmConsoleSample.execute(output::add)

        assertEquals(
            listOf(
                "Version: 0.1.0-alpha.1",
                "Response: Kotlin echo: hello from JVM",
                "Stream: 1:response.started | 2:output.started | " +
                    "3:output.delta:delta=Kotlin echo:  | " +
                    "4:output.delta:delta=stream | " +
                    "5:output.completed:output=Kotlin echo: stream | " +
                    "6:response.completed:response=Kotlin echo: stream:terminal=true",
                "Error: provider/simulated_failure: " +
                    "The Universal AI Connector produced the requested simulated failure.",
                "One-shot cancellation: cancelled",
                "Stream stopped after event: 3:output.delta:delta=Kotlin echo: ",
            ),
            output,
        )
    }
}
