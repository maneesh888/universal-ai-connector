package com.myadidi.universalai.samples.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*

class DesktopStorageActionsTest {
    @Test fun removingTheLivePanelDoesNotCancelDeletionOrLoseItsOutcome() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var removed = false
        val windowJob = SupervisorJob()
        val windowScope = CoroutineScope(coroutineContext + windowJob)
        val panelJob = Job(windowJob)
        val storage = DesktopStorageActions(windowScope, DesktopVault { object : VaultBackend {
            override fun read(): String? = null
            override fun write(value: String) {}
            override fun remove() { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); removed = true }
        } })
        storage.clear()
        withContext(Dispatchers.IO) { check(entered.await(5, TimeUnit.SECONDS)) }
        panelJob.cancelAndJoin() // Switching modes removes only the panel's composition.
        val closing = async { storage.awaitIdle() }
        yield()
        assertFalse(closing.isCompleted)
        assertTrue(storage.state.value.busy)
        release.countDown()
        closing.await()
        assertTrue(removed)
        assertEquals("Session and saved credentials cleared.", storage.state.value.status)
        assertFalse(storage.state.value.busy)
        windowJob.cancelAndJoin()
    }

    @Test fun cancellationWhileDeletionIsQueuedStillClearsAndReportsTheResult() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var removed = false
        val vault = DesktopVault { object : VaultBackend {
            override fun read(): String? { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return null }
            override fun write(value: String) {}
            override fun remove() { removed = true }
        } }
        val reading = launch { vault.restore("key") }
        withContext(Dispatchers.IO) { check(entered.await(5, TimeUnit.SECONDS)) }
        val windowJob = Job()
        val storage = DesktopStorageActions(CoroutineScope(coroutineContext + windowJob), vault)
        storage.clear() // Enters non-cancellable region before waiting on the occupied mutex.
        windowJob.cancel()
        release.countDown()
        reading.join()
        windowJob.join()
        assertTrue(removed)
        assertFalse(storage.state.value.busy)
        assertFalse(storage.state.value.cleanupFailed)
        assertEquals("Session and saved credentials cleared.", storage.state.value.status)
    }

    @Test fun failedDeletionRemainsVisibleAfterWorkFinishes() = runBlocking<Unit> {
        val storage = DesktopStorageActions(this, DesktopVault { object : VaultBackend {
            override fun read(): String? = null
            override fun write(value: String) {}
            override fun remove() { error("synthetic-sensitive-detail") }
        } })
        storage.clear()
        storage.awaitIdle()
        assertTrue(storage.state.value.cleanupFailed)
        assertFalse(storage.state.value.busy)
        assertFalse(storage.state.value.status.contains("synthetic-sensitive-detail"))
        assertTrue(storage.state.value.status.contains("deletion failed"))
    }
}
