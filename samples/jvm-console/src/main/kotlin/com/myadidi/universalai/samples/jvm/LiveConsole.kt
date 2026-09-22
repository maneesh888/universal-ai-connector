package com.myadidi.universalai.samples.jvm

import com.myadidi.universalai.samples.host.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/** Headless equivalent of the desktop discovery, selection, and Test Connection actions. */
internal suspend fun runLiveConsole(
    scope: CoroutineScope,
    provider: LiveProvider,
    baseUrl: String,
    credential: String,
    exactModel: String,
    writeLine: (String) -> Unit,
    factory: LiveClientFactory = PublicLiveClientFactory,
): Boolean {
    val controller = LiveAiController(scope, SessionCredentials(), factory)
    return try {
        controller.configure(provider, baseUrl)
        controller.saveCredential(credential)
        if (!controller.state.value.credentialStored) {
            writeLine("Configuration or credential invalid.")
            return false
        }
        writeLine("Discovery: loading")
        controller.loadModels()
        val discovered = controller.state.first { !it.busy }
        writeLine("Discovery: ${discovered.discovery}")
        when (discovered.discovery) {
            Discovery.LOADED -> controller.selectModel(exactModel)
            Discovery.UNSUPPORTED -> controller.setManualModel(exactModel)
            else -> return false
        }
        if (!controller.state.value.canTest) {
            writeLine("Exact model unavailable. No substitute used.")
            return false
        }
        writeLine("Test Connection: rediscovering before exact-model response")
        controller.testConnection()
        val result = controller.state.first { !it.busy }
        writeLine("Connection: ${result.connection}")
        if (result.connection == Connection.CONNECTED) {
            writeLine("Exact selected model: ${result.connectedModel}")
            writeLine("Response received; body discarded.")
            true
        } else false
    } finally { controller.close() }
}

/** SIGINT/process shutdown cancels the structured operation before the JVM exits. */
internal fun <T> runConsoleLifecycle(action: suspend CoroutineScope.() -> T): T {
    val lifetime = kotlinx.coroutines.Job()
    val runtime = Runtime.getRuntime()
    val shutdown = Thread({
        lifetime.cancel()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(3_000) { lifetime.join() }
        }
    }, "uac-console-cleanup")
    runtime.addShutdownHook(shutdown)
    return try { kotlinx.coroutines.runBlocking(lifetime, action) }
    finally {
        lifetime.cancel()
        runCatching { runtime.removeShutdownHook(shutdown) }
    }
}
