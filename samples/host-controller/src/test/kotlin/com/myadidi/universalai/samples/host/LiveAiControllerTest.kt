package com.myadidi.universalai.samples.host

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveAiControllerTest {
    private class Store : LiveCredentialStore {
        val entries = mutableMapOf<String, String>()
        var failClear = false
        override fun read(key: String) = entries[key]
        override fun save(key: String, credential: String) { validateLiveCredential(credential); entries[key] = credential }
        override fun clearAll() { check(!failClear); entries.clear() }
    }
    private class Client : LiveClient {
        var discovery: suspend () -> LiveModels = { LiveModels.Supported(listOf("model-a", "model-b")) }
        var respond: suspend () -> Unit = {}
        val calls = mutableListOf<String>()
        var closes = 0
        override suspend fun listModels(): LiveModels { calls += "list"; return discovery() }
        override suspend fun testConnection(exactModel: String) { calls += exactModel; respond() }
        override fun close() { closes++ }
    }

    @Test fun exactSelectionIsRequiredAndConnectionRediscovers() = runTest {
        val client = Client()
        val controller = LiveAiController(this, Store(), LiveClientFactory { _, _ -> client })
        controller.saveCredential("synthetic-credential")
        controller.loadModels(); advanceUntilIdle()
        assertNull(controller.state.value.selectedModel)
        assertFalse(controller.state.value.canTest)
        controller.selectModel("model-b")
        controller.testConnection(); advanceUntilIdle()
        assertEquals(listOf("list", "list", "model-b"), client.calls)
        assertEquals("model-b", controller.state.value.connectedModel)
        assertEquals(Connection.CONNECTED, controller.state.value.connection)
        assertTrue(client.closes >= 2)
    }

    @Test fun vanishedSelectionNeverSubstitutesAnotherDiscoveredModel() = runTest {
        val client = Client()
        val controller = LiveAiController(this, Store(), LiveClientFactory { _, _ -> client })
        controller.saveCredential("synthetic-credential")
        controller.loadModels(); advanceUntilIdle()
        controller.selectModel("model-b")
        client.discovery = { LiveModels.Supported(listOf("model-a")) }
        controller.testConnection(); advanceUntilIdle()
        assertEquals(listOf("list", "list"), client.calls)
        assertNull(controller.state.value.selectedModel)
        assertEquals(Connection.FAILED, controller.state.value.connection)
    }

    @Test fun supportedEmptyBlocksRespondIncludingDirectTestAction() = runTest {
        val client = Client().apply { discovery = { LiveModels.Supported(emptyList()) } }
        val controller = LiveAiController(this, Store(), LiveClientFactory { _, _ -> client })
        controller.saveCredential("synthetic-credential")
        controller.loadModels(); advanceUntilIdle()
        controller.setManualModel("unlisted")
        controller.testConnection(); advanceUntilIdle()
        assertEquals(Discovery.EMPTY, controller.state.value.discovery)
        assertFalse(controller.state.value.canTest)
        assertEquals("", controller.state.value.manualModel)
        assertEquals(listOf("list", "list"), client.calls)
    }

    @Test fun onlyExplicitUnsupportedAllowsManualExactModel() = runTest {
        val client = Client().apply { discovery = { LiveModels.Unsupported } }
        val controller = LiveAiController(this, Store(), LiveClientFactory { _, _ -> client })
        controller.saveCredential("synthetic-credential")
        controller.setManualModel("ignored")
        assertEquals("", controller.state.value.manualModel)
        controller.loadModels(); advanceUntilIdle()
        controller.setManualModel("vendor/model:exact@revision")
        controller.testConnection(); advanceUntilIdle()
        assertEquals(listOf("list", "list", "vendor/model:exact@revision"), client.calls)
    }

    @Test fun unsupportedToSupportedDoesNotSendManualModel() = runTest {
        val client = Client().apply { discovery = { LiveModels.Unsupported } }
        val controller = LiveAiController(this, Store(), LiveClientFactory { _, _ -> client })
        controller.saveCredential("synthetic-credential")
        controller.loadModels(); advanceUntilIdle()
        controller.setManualModel("model-a")
        client.discovery = { LiveModels.Supported(listOf("model-a")) }
        controller.testConnection(); advanceUntilIdle()
        assertEquals(listOf("list", "list"), client.calls)
        assertEquals("", controller.state.value.manualModel)
        assertEquals(Connection.FAILED, controller.state.value.connection)
    }

    @Test fun failedDiscoveryRedactsErrorAndSupportsExplicitRetry() = runTest {
        val client = Client().apply { discovery = { error("synthetic-secret provider body") } }
        val controller = LiveAiController(this, Store(), LiveClientFactory { _, _ -> client })
        controller.saveCredential("synthetic-secret")
        controller.loadModels(); advanceUntilIdle()
        assertEquals(Discovery.FAILED, controller.state.value.discovery)
        assertFalse(controller.state.value.toString().contains("synthetic-secret"))
        client.discovery = { LiveModels.Supported(listOf("model-a")) }
        controller.loadModels(retry = true)
        assertEquals(Discovery.RETRYING, controller.state.value.discovery)
        advanceUntilIdle()
        assertEquals(Discovery.LOADED, controller.state.value.discovery)
    }

    @Test fun cancelledLateDiscoveryCannotPublishOrOverwriteRetry() = runTest {
        val release = CompletableDeferred<Unit>()
        val oldClient = Client().apply { discovery = { withContext(NonCancellable) { release.await() }; LiveModels.Supported(listOf("late")) } }
        val newClient = Client()
        var creations = 0
        val controller = LiveAiController(this, Store(), LiveClientFactory { _, _ -> if (creations++ == 0) oldClient else newClient })
        controller.saveCredential("synthetic-credential")
        controller.loadModels(); runCurrent(); controller.cancel()
        assertEquals(Discovery.CANCELLED, controller.state.value.discovery)
        controller.loadModels(retry = true); runCurrent()
        release.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("model-a", "model-b"), controller.state.value.models)
        assertFalse(controller.state.value.busy)
        assertTrue(oldClient.closes > 0)
    }

    @Test fun backgroundDuringResponseCancelsWithoutPublishingLateSuccess() = runTest {
        val release = CompletableDeferred<Unit>()
        val client = Client().apply { respond = { withContext(NonCancellable) { release.await() } } }
        val controller = LiveAiController(this, Store(), LiveClientFactory { _, _ -> client })
        controller.saveCredential("synthetic-credential")
        controller.loadModels(); advanceUntilIdle(); controller.selectModel("model-a")
        controller.testConnection(); runCurrent(); controller.deactivate()
        release.complete(Unit); advanceUntilIdle()
        assertEquals(Connection.CANCELLED, controller.state.value.connection)
        assertNull(controller.state.value.connectedModel)
        assertTrue(controller.state.value.credentialStored)
        controller.loadModels(retry = true); advanceUntilIdle()
        assertEquals(Discovery.LOADED, controller.state.value.discovery)
    }

    @Test fun clearCancelsDiscoveryRemovesAllCredentialsAndSeedPreference() = runTest {
        val store = Store()
        val client = Client().apply { discovery = { awaitCancellation() } }
        val controller = LiveAiController(this, store, LiveClientFactory { _, _ -> client })
        controller.saveCredential("synthetic-credential")
        controller.loadModels(); runCurrent(); controller.clearConfiguration(); advanceUntilIdle()
        assertTrue(store.entries.isEmpty())
        assertFalse(controller.state.value.credentialStored)
        assertEquals(Discovery.IDLE, controller.state.value.discovery)
        client.discovery = { LiveModels.Supported(listOf("model-a", "model-b")) }
        controller.saveCredential("new-synthetic-credential")
        controller.loadModels(); advanceUntilIdle()
        assertNull(controller.state.value.selectedModel)
    }

    @Test fun clearFailureIsReportedWithoutClaimingDeletion() = runTest {
        val store = Store().apply { failClear = true }
        val controller = LiveAiController(this, store)
        controller.saveCredential("synthetic-credential"); controller.clearConfiguration()
        assertTrue(store.entries.isNotEmpty())
        assertTrue(controller.state.value.status.contains("could not be cleared"))
        assertFalse(controller.state.value.credentialStored)
    }

    @Test fun endpointChangeCannotReuseCredentialAndCloseIsTerminal() = runTest {
        val controller = LiveAiController(this, Store())
        controller.saveCredential("synthetic-credential")
        controller.configure(LiveProvider.OPENAI, "https://other.example/v1")
        assertFalse(controller.state.value.credentialStored)
        controller.close(); controller.close()
        controller.saveCredential("ignored")
        assertFalse(controller.state.value.credentialStored)
    }

}
