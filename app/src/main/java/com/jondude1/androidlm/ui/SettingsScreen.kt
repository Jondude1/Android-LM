package com.jondude1.androidlm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jondude1.androidlm.AppContainer
import com.jondude1.androidlm.update.UpdateState
import kotlinx.coroutines.launch

@Composable
private fun Toggle(label: String, sub: String? = null, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun Slide(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${fmt.format(value)}", style = MaterialTheme.typography.bodyMedium)
        Slider(value, onChange, valueRange = range)
    }
}

@Composable
fun SettingsScreen(c: AppContainer) {
    val s by c.settings.state.collectAsState()
    val upd by c.updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    var prompt by remember(s.systemPrompt) { mutableStateOf(s.systemPrompt) }
    var token by remember(s.hfToken) { mutableStateOf(s.hfToken) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCard("App updates") {
            KeyValue("Installed version", c.updater.installedVersion)
            Text(
                when (val u = upd) {
                    UpdateState.Idle -> "Checked automatically when you open the app."
                    UpdateState.Checking -> "Checking..."
                    UpdateState.UpToDate -> "You have the latest version."
                    is UpdateState.Available -> "Version ${u.info.tag} is available."
                    is UpdateState.Downloading -> "Downloading ${(u.progress * 100).toInt()}%"
                    is UpdateState.Ready -> "Ready to install."
                    is UpdateState.Failed -> "Update check failed: ${u.message}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            (upd as? UpdateState.Downloading)?.let { LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ scope.launch { c.updater.check() } }, enabled = upd !is UpdateState.Checking && upd !is UpdateState.Downloading) { Text("Check for updates") }
                if (upd is UpdateState.Available || upd is UpdateState.Ready) Button({ scope.launch { c.updater.downloadAndInstall() } }) { Text("Update now") }
            }
        }

        SectionCard("Privacy") {
            Toggle("Offline mode", "Blocks all network access: no downloads, no update checks. Chatting never uses the network either way.", s.offlineMode) { v -> c.settings.update { copy(offlineMode = v) } }
        }

        SectionCard("Behaviour") {
            OutlinedTextField(prompt, { prompt = it; c.settings.update { copy(systemPrompt = it) } }, Modifier.fillMaxWidth(), label = { Text("System prompt") }, minLines = 2, maxLines = 6)
            Toggle("Let reasoning models think", "Models that support it will reason before answering. Turn off for faster replies.", s.thinking) { v -> c.settings.update { copy(thinking = v) } }
            Toggle("Show the thought process", null, s.showReasoning) { v -> c.settings.update { copy(showReasoning = v) } }
            Toggle("Keep the screen on while chatting", null, s.keepScreenOn) { v -> c.settings.update { copy(keepScreenOn = v) } }
        }

        SectionCard("Sampling") {
            Slide("Temperature", s.temperature, 0f..2f, "%.2f") { v -> c.settings.update { copy(temperature = v) } }
            Slide("Top-p", s.topP, 0.1f..1f, "%.2f") { v -> c.settings.update { copy(topP = v) } }
            Slide("Min-p", s.minP, 0f..0.3f, "%.2f") { v -> c.settings.update { copy(minP = v) } }
            Slide("Top-k", s.topK.toFloat(), 0f..100f, "%.0f") { v -> c.settings.update { copy(topK = v.toInt()) } }
            Slide("Repeat penalty", s.repeatPenalty, 1f..1.5f, "%.2f") { v -> c.settings.update { copy(repeatPenalty = v) } }
            Slide("Max reply length", s.maxTokens.toFloat(), 128f..8192f, "%.0f tokens") { v -> c.settings.update { copy(maxTokens = (v / 64).toInt() * 64) } }
        }

        SectionCard("Memory") {
            Slide("Longest conversation the app may allocate", s.maxContext.toFloat(), 2048f..32768f, "%.0f tokens") { v -> c.settings.update { copy(maxContext = (v / 1024).toInt() * 1024) } }
            Text("Longer context uses more RAM. The app lowers it automatically if a model would not fit. Applies the next time a model loads.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Hugging Face") {
            OutlinedTextField(token, { token = it; c.settings.update { copy(hfToken = it.trim()) } }, Modifier.fillMaxWidth(), label = { Text("Access token (only for gated models)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        }
    }
}
