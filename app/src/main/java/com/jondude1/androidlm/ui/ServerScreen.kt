package com.jondude1.androidlm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jondude1.androidlm.AppContainer
import com.jondude1.androidlm.service.LmService

@Composable
fun ServerScreen(c: AppContainer) {
    val ctx = LocalContext.current
    val st by c.server.state.collectAsState()
    val s by c.settings.state.collectAsState()
    var port by remember(s.serverPort) { mutableStateOf(s.serverPort.toString()) }
    var key by remember(s.serverApiKey) { mutableStateOf(s.serverApiKey) }

    fun toggle() {
        if (st.running) { c.server.stop(); LmService.stop(ctx) }
        else {
            c.settings.update { copy(serverPort = port.toIntOrNull()?.coerceIn(1024, 65535) ?: 1234, serverApiKey = key.trim()) }
            LmService.start(ctx)
            c.server.start()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCard("Local API server") {
            Text(
                "Serves the model on this phone with the same API LM Studio and OpenAI use, so other apps (Termux scripts, Home Assistant, a laptop on your Wi-Fi) can talk to it. Nothing leaves your network.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (st.running) "Running" else "Stopped", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Button({ toggle() }) { Text(if (st.running) "Stop" else "Start") }
            }
            if (st.running) {
                Text(st.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton({
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", st.url))
                }) { Text("Copy address") }
            }
            st.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        SectionCard("Settings") {
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), label = { Text("Port") }, singleLine = true, enabled = !st.running, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API key (optional)") }, singleLine = true, enabled = !st.running)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow other devices on my network")
                    Text("Off = only apps on this phone can connect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(s.serverLan, { v -> c.settings.update { copy(serverLan = v) } }, enabled = !st.running)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Start with the app", Modifier.weight(1f))
                Switch(s.serverAutostart, { v -> c.settings.update { copy(serverAutostart = v) } })
            }
            if (s.serverLan && s.serverApiKey.isBlank()) Text("Tip: set an API key when sharing on the network.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        SectionCard("Try it") {
            Text("curl ${st.url.ifEmpty { "http://127.0.0.1:${s.serverPort}/v1" }}/chat/completions \\\n  -H 'Content-Type: application/json' \\\n  -d '{\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}'", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            Text("Models load on demand: the first request loads whichever model you used last.", style = MaterialTheme.typography.bodySmall)
        }
        if (st.log.isNotEmpty()) SectionCard("Recent requests (${st.requests})") {
            st.log.take(15).forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
