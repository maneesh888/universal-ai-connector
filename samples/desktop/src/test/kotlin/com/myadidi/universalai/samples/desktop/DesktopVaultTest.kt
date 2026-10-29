package com.myadidi.universalai.samples.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*

class DesktopVaultTest {
    private class Backend : VaultBackend {
        var value: String? = null
        var closes = 0
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun remove() { value = null }
        override fun close() { closes++ }
    }
    @Test fun roundTripRequiresTheExactConfigurationAndClearRemovesProfile() = runBlocking<Unit> {
        val backend = Backend()
        val vault = DesktopVault { backend }
        vault.remember("openai|https://one.example/v1", "synthetic-credential")
        assertEquals("synthetic-credential", vault.restore("openai|https://one.example/v1"))
        assertNull(vault.restore("openai|https://two.example/v1"))
        vault.clear()
        assertNull(backend.value)
        assertEquals(4, backend.closes)
    }
    @Test fun unavailableServiceAndNativeLinkErrorsExposeOnlyStaticErrors() = runBlocking<Unit> {
        for (failure in listOf<Throwable>(IllegalStateException("synthetic-secret"), UnsatisfiedLinkError("synthetic-secret"))) {
            val vault = DesktopVault { throw failure }
            val error = assertFailsWith<VaultUnavailable> { vault.remember("key", "synthetic-credential") }
            assertFalse(error.toString().contains("synthetic-secret"))
            assertNull(error.cause)
            assertFailsWith<VaultUnavailable> { vault.clear() }
        }
    }
    @Test fun cancellingNativeWriteRemovesTheCredentialBeforeFinishing() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = Backend()
        val vault = DesktopVault { object : VaultBackend {
            override fun read() = backend.read()
            override fun write(value: String) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)); backend.write(value) }
            override fun remove() = backend.remove()
        } }
        val job = launch { vault.remember("key", "synthetic-credential") }
        withContext(Dispatchers.IO) { check(started.await(5, TimeUnit.SECONDS)) }
        job.cancel(); release.countDown(); job.join()
        assertNull(backend.value)
        assertTrue(job.isCancelled)
    }
    @Test fun clearFailureDoesNotClaimDeletion() = runBlocking<Unit> {
        val vault = DesktopVault { object : VaultBackend {
            override fun read(): String? = null
            override fun write(value: String) {}
            override fun remove() { error("synthetic-provider-body") }
        } }
        assertFailsWith<VaultUnavailable> { vault.clear() }
    }
    @Test fun failedWriteCleansUpPossiblePartialPersistence() = runBlocking<Unit> {
        var removed = false
        val vault = DesktopVault { object : VaultBackend {
            override fun read(): String? = null
            override fun write(value: String) { error("write failed after native persistence") }
            override fun remove() { removed = true }
        } }
        assertFailsWith<VaultUnavailable> { vault.remember("key", "synthetic-credential") }
        assertTrue(removed)
    }
    @Test fun failedWriteAndFailedCleanupNeverClaimSessionOnly() = runBlocking<Unit> {
        val vault = DesktopVault { object : VaultBackend {
            override fun read(): String? = null
            override fun write(value: String) { error("partial write") }
            override fun remove() { error("cleanup failed") }
        } }
        assertFailsWith<VaultCleanupFailed> { vault.remember("key", "synthetic-credential") }
    }
    @Test fun unknownOsCannotSelectAnotherStore() {
        assertFailsWith<IllegalStateException> { nativeVault("OtherOS") }
    }
}
