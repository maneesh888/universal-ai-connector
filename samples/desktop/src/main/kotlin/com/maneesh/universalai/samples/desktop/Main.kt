package com.maneesh.universalai.samples.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.maneesh.universalai.samples.host.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DesktopLiveInput(val provider: LiveProvider, val baseUrl: String, val model: String, credential: String) {
    private var secret: String? = credential
    fun takeCredential(): String = checkNotNull(secret).also { secret = null }
    override fun toString() = "DesktopLiveInput(redacted)"
}

fun main(args: Array<String>) {
    val input = if (args.isEmpty()) null else {
        val provider = if (args.size == 2 && args[0] == "--live") LiveProvider.entries.firstOrNull { it.id == args[1] } else null
        if (provider == null) { System.err.println("Use no arguments for deterministic mode, or --live with a delivered provider ID."); return }
        val prefix = if (provider == LiveProvider.GATEWAY) "GATEWAY" else provider.id.uppercase()
        val credential = System.getenv("${prefix}_API_KEY").orEmpty()
        val model = System.getenv("${prefix}_LIVE_MODEL").orEmpty()
        val baseUrl = System.getenv("${prefix}_LIVE_BASE_URL").orEmpty()
        try { validateLiveCredential(credential); LiveConfiguration(provider, baseUrl); com.maneesh.universalai.connector.contract.ModelId.of(model) }
        catch (_: Exception) { System.err.println("Explicit live mode requires valid process-scoped configuration."); return }
        DesktopLiveInput(provider, baseUrl, model, credential)
    }
    application {
    Window(onCloseRequest = ::exitApplication, title = "Universal AI Connector", state = rememberWindowState(width = 980.dp, height = 820.dp)) {
        MaterialTheme { Surface(Modifier.fillMaxSize()) { DesktopApp(input) } }
    }
    }
}

@Composable
fun DesktopApp(input: DesktopLiveInput? = null) {
    val scope = rememberCoroutineScope()
    val credentials = remember { SessionCredentials() }
    val live = remember { LiveAiController(scope, credentials).also { controller ->
        input?.let { controller.configure(it.provider, it.baseUrl); controller.saveCredential(it.takeCredential()) }
    } }
    val demo = remember { DemoController(scope) }
    val vault = remember { DesktopVault() }
    var liveMode by remember { mutableStateOf(input != null) }
    var initialSelection by remember { mutableStateOf(input?.model) }
    val liveState by live.state.collectAsState()
    LaunchedEffect(liveState.discovery) {
        initialSelection?.let { model ->
            if (liveState.discovery == Discovery.LOADED) { live.selectModel(model); initialSelection = null }
            else if (liveState.discovery == Discovery.UNSUPPORTED) { live.setManualModel(model); initialSelection = null }
        }
    }
    DisposableEffect(Unit) { onDispose { live.close(); demo.close() } }
    Column(Modifier.padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Universal AI Connector", style = MaterialTheme.typography.headlineLarge)
        Text("Desktop live testing · macOS, Windows & Linux", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = !liveMode, onClick = { live.deactivate(); liveMode = false }, label = { Text("Deterministic") }, modifier = Modifier.testTag("mode-deterministic"))
            FilterChip(selected = liveMode, onClick = { demo.cancel(); liveMode = true }, label = { Text("Live") }, modifier = Modifier.testTag("mode-live"))
        }
        if (liveMode) LivePanel(live, credentials, vault) else DemoPanel(demo)
    }
}

@Composable
private fun DemoPanel(controller: DemoController) {
    val state by controller.state.collectAsState()
    Text("Try the connector without credentials or network access.")
    Text(state.headline, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("demo-status"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = controller::runCompleteDemo, enabled = !state.isBusy, modifier = Modifier.testTag("demo-all")) { Text("Run all checks") }
        OutlinedButton(onClick = controller::runResponse, enabled = !state.isBusy) { Text("Response") }
        OutlinedButton(onClick = controller::runStream, enabled = !state.isBusy) { Text("Stream") }
        OutlinedButton(onClick = controller::runError, enabled = !state.isBusy) { Text("Typed error") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = controller::runResponseCancellation, enabled = !state.isBusy) { Text("Cancel response") }
        OutlinedButton(onClick = controller::runStreamCancellation, enabled = !state.isBusy) { Text("Cancel stream") }
    }
    ResultCard("Response", state.response)
    ResultCard("Ordered streaming", state.streamEvents.joinToString("\n").ifEmpty { "Not run yet" })
    ResultCard("Typed error", state.error)
    ResultCard("Cancellation", "Response: ${state.responseCancellation}\nStream: ${state.streamCancellation}")
}

@Composable
private fun ResultCard(title: String, text: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text)
    } }
}

@Composable
private fun LivePanel(controller: LiveAiController, credentials: SessionCredentials, vault: DesktopVault) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var credential by remember { mutableStateOf("") }
    var vaultBusy by remember { mutableStateOf(false) }
    var storageStatus by remember { mutableStateOf("Session only. Remember stores one profile in this OS credential service.") }
    var persistenceAvailable by remember { mutableStateOf(true) }
    var pickerOpen by remember { mutableStateOf(false) }
    val enabled = !state.busy && !vaultBusy
    Text("Choose a delivered provider, load models, then test your exact selection.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiveProvider.entries.forEach { provider ->
            FilterChip(selected = state.provider == provider, enabled = enabled,
                onClick = { credential = ""; controller.configure(provider, "") }, label = { Text(provider.title) })
        }
    }
    OutlinedTextField(state.baseUrl, onValueChange = { credential = ""; controller.configure(state.provider, it) },
        enabled = enabled, label = { Text("Base URL (defaults for direct providers)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("base-url"))
    OutlinedTextField(credential, onValueChange = { credential = it }, enabled = enabled,
        label = { Text("Credential") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("credential"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { controller.saveCredential(credential); credential = "" }, enabled = enabled && credential.isNotEmpty(), modifier = Modifier.testTag("use-credential")) { Text("Use for session") }
        OutlinedButton(enabled = enabled && state.credentialStored && persistenceAvailable, onClick = {
            vaultBusy = true
            scope.launch {
                try {
                    val key = LiveConfiguration(state.provider, state.baseUrl).credentialKey
                    vault.remember(key, checkNotNull(credentials.read(key)))
                    storageStatus = "Remembered in this OS credential service."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: VaultCleanupFailed) { persistenceAvailable = false; storageStatus = "Saved credential cleanup failed. Retry Clear configuration." }
                catch (_: Exception) { persistenceAvailable = false; storageStatus = "OS credential service unavailable. Session only; persistence disabled." }
                finally { vaultBusy = false }
            }
        }) { Text("Remember securely") }
        OutlinedButton(enabled = enabled && persistenceAvailable, onClick = {
            vaultBusy = true
            scope.launch {
                try {
                    val key = LiveConfiguration(state.provider, state.baseUrl).credentialKey
                    val restored = vault.restore(key)
                    if (restored == null) storageStatus = "No saved credential for this configuration."
                    else { controller.saveCredential(restored); storageStatus = "Restored from this OS credential service." }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { storageStatus = "Could not restore a saved credential. Use a session credential or retry the OS service." }
                finally { vaultBusy = false }
            }
        }) { Text("Restore saved") }
    }
    Text(storageStatus, modifier = Modifier.testTag("storage-status"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { controller.loadModels() }, enabled = enabled && state.credentialStored, modifier = Modifier.testTag("load-models")) { Text("Load models") }
        OutlinedButton(onClick = { controller.loadModels(retry = true) }, enabled = enabled && state.credentialStored) { Text("Retry discovery") }
        OutlinedButton(onClick = controller::cancel, enabled = state.busy, modifier = Modifier.testTag("cancel-live")) { Text("Cancel") }
    }
    Text("Discovery: ${state.discovery}", modifier = Modifier.testTag("discovery-status"))
    if (state.discovery == Discovery.LOADED) {
        Box {
            OutlinedButton(onClick = { pickerOpen = true }, enabled = enabled, modifier = Modifier.testTag("model-picker")) { Text(state.selectedModel ?: "Choose an exact model") }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                state.models.forEach { id -> DropdownMenuItem(text = { Text(id) }, onClick = { controller.selectModel(id); pickerOpen = false }) }
            }
        }
    }
    if (state.discovery == Discovery.UNSUPPORTED) {
        OutlinedTextField(state.manualModel, onValueChange = controller::setManualModel, enabled = enabled,
            label = { Text("Exact model ID (discovery unsupported)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("manual-model"))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = controller::testConnection, enabled = state.canTest && !vaultBusy, modifier = Modifier.testTag("test-connection")) { Text("Test Connection") }
        OutlinedButton(enabled = !vaultBusy, modifier = Modifier.testTag("clear-configuration"), onClick = {
            credential = ""; controller.clearConfiguration(); vaultBusy = true
            scope.launch {
                try { vault.clear(); storageStatus = "Session and saved credentials cleared."; persistenceAvailable = true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { storageStatus = "Session cleared. OS credential deletion failed; retry Clear configuration." }
                finally { vaultBusy = false }
            }
        }) { Text("Clear configuration") }
    }
    ResultCard("Connection: ${state.connection}", state.status + (state.connectedModel?.let { "\nExact model: $it\nResponse received; body discarded." } ?: ""))
}
