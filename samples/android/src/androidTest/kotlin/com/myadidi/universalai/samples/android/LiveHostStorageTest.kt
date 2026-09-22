package com.myadidi.universalai.samples.android

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LiveHostStorageTest {
    @Test fun keystoreRoundTripBackupRecoveryAuthenticationAndClear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = "live-test-${UUID.randomUUID()}"
        val alias = "uac.sample.test.${UUID.randomUUID()}"
        val directory = File(context.noBackupFilesDir, storage)
        val store = KeystoreLiveCredentialStore(context, storage, alias)
        val key = "openai|https://api.openai.com/v1"
        val synthetic = "synthetic-keystore-round-trip"
        try {
            assertNull(store.read(key))
            store.save(key, synthetic)
            assertEquals(synthetic, KeystoreLiveCredentialStore(context, storage, alias).read(key))
            val encryptedFile = directory.listFiles()!!.single()
            assertFalse(encryptedFile.readBytes().toString(Charsets.ISO_8859_1).contains(synthetic))
            // Simulate the backup-only state produced by an interrupted AtomicFile update.
            assertTrue(encryptedFile.renameTo(File(encryptedFile.path + ".bak")))
            assertFalse(encryptedFile.exists())
            assertEquals(synthetic, store.read(key))
            assertTrue(encryptedFile.exists())
            val bytes = encryptedFile.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            encryptedFile.writeBytes(bytes)
            assertThrows(Exception::class.java) { store.read(key) }
            store.save(key, "synthetic-updated-value")
            assertEquals("synthetic-updated-value", store.read(key))
            store.clearAll()
            assertFalse(directory.exists())
            assertNull(KeystoreLiveCredentialStore(context, storage, alias).read(key))
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse(keyStore.containsAlias(alias))
        } finally { store.clearAll() }
    }

    @Test fun bootstrapCloseUnblocksNoClientAcceptAndReleasesSocketName() {
        val name = "uac_live_test_${UUID.randomUUID()}"
        val socket = BootstrapSocket(name)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<Boolean> {
                try { socket.receive(); false } catch (_: Exception) { true }
            }
            android.os.SystemClock.sleep(100)
            assertFalse(waiting.isDone)
            socket.close()
            socket.close()
            assertTrue(waiting.get(2, TimeUnit.SECONDS))
            BootstrapSocket(name).close()
        } finally {
            socket.close()
            executor.shutdownNow()
        }
    }
}
