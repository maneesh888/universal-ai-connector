package com.maneesh.universalai.samples.android

import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug source-set only. No listener exists on ordinary launch or in a release APK. */
object LiveBootstrap {
    fun start(activity: ComponentActivity, intent: Intent, controller: LiveAiController): Closeable? {
        val enabled = intent.getBooleanExtra("uac_live_bootstrap", false)
        val name = intent.getStringExtra("uac_live_socket")
        intent.removeExtra("uac_live_bootstrap")
        intent.removeExtra("uac_live_socket")
        if (!enabled || name == null || !Regex("uac_live_[0-9a-f]{32}").matches(name)) return null
        val endpoint = try { BootstrapSocket(name) } catch (_: Exception) { return null }
        val active = AtomicBoolean(true)
        val handle = Closeable {
            active.set(false)
            endpoint.close()
        }
        val timeout = activity.lifecycleScope.launch { delay(30_000); handle.close() }
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val seed = endpoint.receive()
                val imported = withContext(Dispatchers.Main) {
                    active.get() && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && controller.importSeed(seed)
                }
                endpoint.acknowledge(imported)
            } catch (_: Exception) {
                // No untrusted payload, exception text, or credential enters logs or UI.
            } finally {
                handle.close()
                timeout.cancel()
            }
        }
        return handle
    }
}

/** Closing must interrupt native accept/read, not merely release the descriptor reference. */
internal class BootstrapSocket(name: String) : Closeable {
    private val server = LocalServerSocket(name)
    private var accepted: LocalSocket? = null
    private var closed = false

    fun receive(): LiveProofSeed {
        val connection = server.accept()
        synchronized(this) {
            if (closed) { connection.close(); error("Bootstrap closed.") }
            accepted = connection
        }
        connection.soTimeout = 5000
        return LiveProofSeed.readFramed(connection.inputStream, enabled = true, peerUid = connection.peerCredentials.uid)
    }

    @Synchronized
    fun acknowledge(imported: Boolean) { accepted?.outputStream?.write(if (imported) 1 else 0) }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { Os.shutdown(server.fileDescriptor, OsConstants.SHUT_RDWR) }
        runCatching { server.close() }
        accepted?.let { connection ->
            runCatching { Os.shutdown(connection.fileDescriptor, OsConstants.SHUT_RDWR) }
            runCatching { connection.close() }
        }
        accepted = null
    }
}
