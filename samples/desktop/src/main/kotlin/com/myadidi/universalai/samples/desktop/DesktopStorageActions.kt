package com.myadidi.universalai.samples.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class StorageState(
    val busy: Boolean = false,
    val status: String = "Use session credentials, or remember one profile in this OS credential service.",
    val persistenceAvailable: Boolean = true,
    val cleanupFailed: Boolean = false,
)

/** Owned by the window, so changing panels cannot cancel work or hide its eventual outcome. */
internal class DesktopStorageActions(private val scope: CoroutineScope, private val vault: DesktopVault) {
    private val mutableState = MutableStateFlow(StorageState())
    val state = mutableState.asStateFlow()
    private var active: Job? = null

    fun remember(key: String, credential: String) = perform {
        try {
            vault.remember(key, credential)
            mutableState.value = state.value.copy(status = "Remembered in this OS credential service.", cleanupFailed = false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: VaultCleanupFailed) {
            mutableState.value = state.value.copy(persistenceAvailable = false, cleanupFailed = true,
                status = "Saved credential cleanup failed. Retry Clear configuration.")
        } catch (_: Exception) {
            mutableState.value = state.value.copy(persistenceAvailable = false,
                status = "OS credential service unavailable. Session only; persistence disabled.")
        }
    }

    fun restore(key: String, consume: (String) -> Unit) = perform {
        try {
            val restored = vault.restore(key)
            if (restored != null) consume(restored)
            mutableState.value = state.value.copy(status = if (restored == null) "No saved credential for this configuration."
                else "Restored from this OS credential service.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.value = state.value.copy(status = "Could not restore a saved credential. Use a session credential or retry the OS service.")
        }
    }

    fun clear() = perform {
        // Once requested, deletion and reporting survive cancellation, including while queued on the vault mutex.
        withContext(NonCancellable) {
            try {
                vault.clear()
                mutableState.value = state.value.copy(status = "Session and saved credentials cleared.", persistenceAvailable = true, cleanupFailed = false)
            } catch (_: Exception) {
                mutableState.value = state.value.copy(status = "Session cleared. OS credential deletion failed; retry Clear configuration.", cleanupFailed = true)
            }
        }
    }

    suspend fun awaitIdle() { active?.join() }

    private fun perform(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(busy = true)
        active = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { action() }
            finally { mutableState.value = state.value.copy(busy = false) }
        }
    }
}
