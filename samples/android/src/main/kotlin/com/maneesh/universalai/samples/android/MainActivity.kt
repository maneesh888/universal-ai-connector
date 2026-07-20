package com.maneesh.universalai.samples.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    private val controller by lazy { AndroidSampleController(lifecycleScope) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UniversalAiSampleTheme {
                AndroidSampleScreen(controller)
            }
        }
        controller.runCompleteDemo()
    }
}

@Composable
private fun UniversalAiSampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = Color(0xFF2F5D50),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFD5E8DF),
                onPrimaryContainer = Color(0xFF16372E),
                secondaryContainer = Color(0xFFF1E6C9),
                onSecondaryContainer = Color(0xFF453916),
                surface = Color(0xFFFFFBF5),
                surfaceVariant = Color(0xFFF2EEE8),
            ),
        content = content,
    )
}

@Composable
private fun AndroidSampleScreen(controller: AndroidSampleController) {
    val state by controller.state.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Universal AI Connector",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Android consumer sample · v${state.version}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusCard(state)
            Button(
                onClick = controller::runCompleteDemo,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isBusy) "Running…" else "Run complete demo")
            }

            ResultCard("One-shot response", state.response)
            ResultCard(
                title = "Ordered stream",
                value = state.streamEvents.ifEmpty { listOf("Not run yet") }.joinToString("\n"),
            )
            ResultCard("Stable typed error", state.error)
            ResultCard("Response cancellation", state.responseCancellation)
            ResultCard("Stream cancellation", state.streamCancellation)

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            Text(
                text = "Run one path",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ActionButtons(controller = controller, enabled = !state.isBusy)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Deterministic local fake · No network · No API key",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun StatusCard(state: AndroidSampleUiState) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (state.headline.contains("failed", ignoreCase = true)) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = state.headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ResultCard(
    title: String,
    value: String,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ActionButtons(
    controller: AndroidSampleController,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = controller::runResponse,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Text("Response")
            }
            OutlinedButton(
                onClick = controller::runStream,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Text("Stream")
            }
            OutlinedButton(
                onClick = controller::runError,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Text("Error")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = controller::runResponseCancellation,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Text("Cancel response")
            }
            OutlinedButton(
                onClick = controller::runStreamCancellation,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Text("Stop stream")
            }
        }
    }
}
