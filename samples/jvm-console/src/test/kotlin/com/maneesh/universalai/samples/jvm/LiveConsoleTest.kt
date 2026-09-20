package com.maneesh.universalai.samples.jvm

import com.maneesh.universalai.samples.host.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class LiveConsoleTest {
    @Test fun rediscoveryUsesOnlyTheExactModelAndDiscardsResponseBodies() = runTest {
        val calls = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val client = object : LiveClient {
            override suspend fun listModels(): LiveModels { calls += "list"; return LiveModels.Supported(listOf("exact", "other")) }
            override suspend fun testConnection(exactModel: String) { calls += exactModel }
            override fun close() { calls += "close" }
        }
        assertTrue(runLiveConsole(this, LiveProvider.OPENAI, "", "synthetic-credential", "exact", lines::add, LiveClientFactory { _, _ -> client }))
        assertEquals(listOf("list", "close", "list", "exact", "close"), calls)
        assertFalse(lines.joinToString().contains("synthetic-credential"))
    }
    @Test fun emptyFailureCancellationAndMissingSelectionBlockResponse() = runTest {
        for (result in listOf("empty", "failure", "cancelled", "missing")) {
            var responses = 0
            val client = object : LiveClient {
                override suspend fun listModels(): LiveModels = when (result) {
                    "empty" -> LiveModels.Supported(emptyList())
                    "failure" -> error("synthetic-secret response body")
                    "cancelled" -> throw CancellationException("synthetic-secret")
                    else -> LiveModels.Supported(listOf("other"))
                }
                override suspend fun testConnection(exactModel: String) { responses++ }
                override fun close() {}
            }
            val output = mutableListOf<String>()
            assertFalse(runLiveConsole(this, LiveProvider.OPENAI, "", "synthetic-credential", "exact", output::add, LiveClientFactory { _, _ -> client }))
            assertEquals(0, responses)
            assertFalse(output.joinToString().contains("synthetic-secret"))
        }
    }
    @Test fun unsupportedIsTheOnlyManualContinuation() = runTest {
        var selected: String? = null
        val client = object : LiveClient {
            override suspend fun listModels() = LiveModels.Unsupported
            override suspend fun testConnection(exactModel: String) { selected = exactModel }
            override fun close() {}
        }
        assertTrue(runLiveConsole(this, LiveProvider.GATEWAY, "http://localhost:8880/v1", "synthetic-credential", "vendor/exact:v1", {}, LiveClientFactory { _, _ -> client }))
        assertEquals("vendor/exact:v1", selected)
    }
}
