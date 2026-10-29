package com.myadidi.universalai.samples.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LiveAiScreen(controller: LiveAiController, clearConfiguration: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    // Intentionally not rememberSaveable: typed credentials never enter saved instance state.
    var credential by remember { mutableStateOf("") }
    var choosingModel by remember { mutableStateOf(false) }
    var modelFilter by remember { mutableStateOf("") }
    var choosingProvider by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) credential = ""
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { credential = ""; lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Live connection", style = MaterialTheme.typography.headlineSmall)
        Text("Configure a provider, discover models, then test your exact selection.")
        Box {
            OutlinedButton(
                onClick = { choosingProvider = true },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
            ) { Text("Provider: ${state.provider.title}") }
            DropdownMenu(expanded = choosingProvider, onDismissRequest = { choosingProvider = false }) {
                LiveProvider.entries.forEach { provider ->
                    DropdownMenuItem(text = { Text(provider.title) }, onClick = {
                        credential = ""; choosingModel = false; choosingProvider = false
                        controller.configure(provider, "")
                    })
                }
            }
        }
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = { credential = ""; controller.configure(state.provider, it) },
            label = { Text(if (state.provider == LiveProvider.GATEWAY) "Gateway base URL (required)" else "Base URL (optional)") },
            placeholder = { Text(state.provider.defaultUrl.ifEmpty { "https://gateway.example.com/v1" }) },
            singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = credential, onValueChange = { credential = it },
            label = { Text("Credential") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.saveCredential(credential); credential = "" }, enabled = !state.busy && credential.isNotBlank()) { Text("Save credential") }
            OutlinedButton(onClick = {
                credential = ""; choosingModel = false; modelFilter = ""; clearConfiguration()
            }) { Text("Clear configuration") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (state.credentialStored) "Credential stored securely" else "No credential configured", style = MaterialTheme.typography.titleMedium)
                Text(state.status)
                Text("Discovery: ${state.discovery.name.lowercase()}")
                Text("Connection: ${state.connection.name.lowercase()}")
                state.connectedModel?.let { Text("Connected model: $it") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.loadModels() }, enabled = state.credentialStored && !state.busy) { Text("Load models") }
            OutlinedButton(onClick = { controller.loadModels(retry = true) }, enabled = state.credentialStored && !state.busy && state.discovery in listOf(Discovery.EMPTY, Discovery.FAILED, Discovery.CANCELLED)) { Text("Retry") }
            OutlinedButton(onClick = controller::cancel, enabled = state.busy) { Text("Cancel") }
        }
        if (state.discovery == Discovery.LOADED) {
            Text("${state.models.size} models discovered")
            OutlinedButton(onClick = { choosingModel = !choosingModel }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(state.selectedModel ?: "Choose an exact model")
            }
            if (choosingModel) {
                OutlinedTextField(value = modelFilter, onValueChange = { modelFilter = it }, label = { Text("Filter discovered models") }, singleLine = true)
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    state.models.filter { it.contains(modelFilter, ignoreCase = true) }.forEach { id ->
                        OutlinedButton(onClick = { controller.selectModel(id); choosingModel = false }, modifier = Modifier.fillMaxWidth()) { Text(id) }
                    }
                }
            }
        }
        if (state.discovery == Discovery.UNSUPPORTED) {
            OutlinedTextField(value = state.manualModel, onValueChange = controller::setManualModel,
                label = { Text("Exact model ID") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        }
        Button(onClick = controller::testConnection, enabled = state.canTest, modifier = Modifier.fillMaxWidth()) { Text("Test Connection") }
        Text("Connection testing discovers again before sending. No model substitution or automatic retry.", style = MaterialTheme.typography.bodySmall)
    }
}
