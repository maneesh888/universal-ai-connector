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
                "Stream: 1:stream 1 | 2:stream 2 | 3:stream 3 | 4:stream 4 | 5:stream 5",
                "Error: simulated_failure: " +
                    "The Universal AI Connector produced the requested simulated failure.",
                "One-shot cancellation: cancelled",
                "Stream stopped after event: 1:stop 1",
            ),
            output,
        )
    }
}
