package com.maneesh.universalai.samples.android

import android.content.Intent
import android.net.LocalServerSocket
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
        val server = try { LocalServerSocket(name) } catch (_: Exception) { return null }
        val active = AtomicBoolean(true)
        var socket: android.net.LocalSocket? = null
        val handle = Closeable {
            active.set(false)
            runCatching { socket?.close() }
            runCatching { server.close() }
        }
        val timeout = activity.lifecycleScope.launch { delay(30_000); handle.close() }
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                server.accept().use { connection ->
                    socket = connection
                    check(active.get())
                    connection.soTimeout = 5000
                    val seed = LiveProofSeed.read(connection.inputStream, enabled = true, peerUid = connection.peerCredentials.uid)
                    val imported = withContext(Dispatchers.Main) {
                        active.get() && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && controller.importSeed(seed)
                    }
                    connection.outputStream.write(if (imported) 1 else 0)
                }
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
