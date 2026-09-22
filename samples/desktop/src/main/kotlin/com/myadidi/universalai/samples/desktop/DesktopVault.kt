package com.myadidi.universalai.samples.desktop

import com.github.javakeyring.Keyring
import com.github.javakeyring.KeyringStorageType
import com.myadidi.universalai.samples.host.validateLiveCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Exactly one remembered profile, wholly inside the matching OS credential service. */
interface VaultBackend : AutoCloseable {
    fun read(): String?
    fun write(value: String)
    fun remove()
    override fun close() {}
}

fun nativeVault(os: String = System.getProperty("os.name")): VaultBackend {
    val type = when {
        os.startsWith("Mac", ignoreCase = true) -> KeyringStorageType.OSX_KEYCHAIN
        os.startsWith("Windows", ignoreCase = true) -> KeyringStorageType.WINDOWS_CREDENTIAL_STORE
        os.startsWith("Linux", ignoreCase = true) -> KeyringStorageType.GNOME_KEYRING
        else -> error("OS credential service unavailable")
    }
    val keyring = Keyring.create(type)
    return object : VaultBackend {
        override fun read(): String? = keyring.getPassword(SERVICE, ACCOUNT)
        override fun write(value: String) = keyring.setPassword(SERVICE, ACCOUNT, value)
        override fun remove() {
            // Replacing first also handles libraries that throw when deleting an absent item.
            // If deletion fails, the remaining item contains no credential.
            keyring.setPassword(SERVICE, ACCOUNT, "cleared")
            keyring.deletePassword(SERVICE, ACCOUNT)
        }
        override fun close() = keyring.close()
    }
}
private const val SERVICE = "com.myadidi.universalai.desktop.live"
private const val ACCOUNT = "remembered-profile"

class VaultCleanupFailed : Exception("Saved credential cleanup failed. Retry Clear configuration.")

class VaultUnavailable : Exception("OS credential service unavailable; use session-only credentials.")

/** Native work never runs on the UI thread. Operations serialize, including cancellation cleanup. */
class DesktopVault(private val backend: () -> VaultBackend = { nativeVault() }) {
    private val mutex = Mutex()
    suspend fun remember(key: String, credential: String) = mutex.withLock {
        validateLiveCredential(credential)
        var attempted = false
        try {
            withContext(Dispatchers.IO) {
                backend().use { attempted = true; it.write("$key\n$credential") }
            }
            currentCoroutineContext().ensureActive()
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            if (attempted) {
                try { withContext(NonCancellable) { clearNative() } }
                catch (_: Exception) { throw VaultCleanupFailed() }
            }
            if (failure is CancellationException) throw failure
            throw VaultUnavailable()
        }
    }
    suspend fun restore(key: String): String? = mutex.withLock {
        try {
            withContext(Dispatchers.IO) {
                backend().use {
                    val value = it.read() ?: return@use null
                    val parts = value.split('\n', limit = 2)
                    if (parts.size == 2 && parts[0] == key) parts[1].also(::validateLiveCredential) else null
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw VaultUnavailable() }
        catch (_: LinkageError) { throw VaultUnavailable() }
    }
    suspend fun clear() = withContext(NonCancellable) { mutex.withLock { clearNative() } }
    private suspend fun clearNative() {
        try { withContext(Dispatchers.IO) { backend().use { it.remove() } } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw VaultUnavailable() }
        catch (_: LinkageError) { throw VaultUnavailable() }
    }
}
