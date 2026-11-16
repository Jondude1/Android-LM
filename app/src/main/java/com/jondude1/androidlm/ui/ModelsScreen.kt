package com.jondude1.androidlm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jondude1.androidlm.AppContainer
import com.jondude1.androidlm.engine.EngineStatus
import com.jondude1.androidlm.models.DlState
import com.jondude1.androidlm.models.HfChoice
import com.jondude1.androidlm.models.HfModel
import com.jondude1.androidlm.models.LocalModel
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(c: AppContainer, chatVm: ChatViewModel, onOpenChat: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("On this phone") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Discover") })
        }
        if (tab == 0) Library(c, chatVm, onOpenChat) else Discover(c)
    }
}

@Composable
private fun Library(c: AppContainer, chatVm: ChatViewModel, onOpenChat: () -> Unit) {
    val models by c.models.models.collectAsState()
    val engine by c.engine.state.collectAsState()
    val tuneVersion by c.tuneStore.version.collectAsState()
    val downloads by c.downloads.items.collectAsState()
    val tuning by c.tuner.progress.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<LocalModel?>(null) }
    val busy = engine.status == EngineStatus.Loading || engine.status == EngineStatus.Generating || engine.status == EngineStatus.Benchmarking

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (downloads.isNotEmpty()) item { DownloadsCard(c) }
        if (!c.models.hasAllFilesAccess) item {
            SectionCard("Keep models across reinstalls") {
                Text("Models are kept inside the app. Allow \"all files access\" and they live in /sdcard/AndroidLM/models instead, so they survive an uninstall and you can copy files in yourself.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton({
                    val i = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${c.app.packageName}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    c.app.startActivity(i)
                }) { Text("Allow") }
            }
        }
        if (models.isEmpty()) item {
            SectionCard {
                Text("No models yet.", style = MaterialTheme.typography.titleSmall)
                Text("Open Discover to download one - the Device tab shows what size your phone handles best. You can also drop .gguf files into ${c.models.primaryDir().absolutePath}.", style = MaterialTheme.typography.bodySmall)
                TextButton({ scope.launch { c.models.refresh() } }) { Text("Rescan") }
            }
        }
        items(models, key = { it.id }) { m ->
            @Suppress("UNUSED_EXPRESSION") tuneVersion
            val rep = c.planner.assess(m)
            val tuned = c.tuneStore.get(m.id)
            val isLoaded = engine.modelId == m.id && engine.status == EngineStatus.Ready
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        Text(
                            listOf(m.info.arch, m.info.quant, fmtBytes(m.sizeBytes), if (m.info.isMoe) "MoE ${m.info.nExpertUsed}/${m.info.nExpert}" else null).filterNotNull().filter { it.isNotBlank() }.joinToString(" - "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Pill(rep.fit.label, fitColor(rep.fit))
                }
                val speed = tuned?.best?.let { b -> tuned.trials.filter { it.deviceName == b.deviceName && !it.failed }.maxByOrNull { it.tgTps } }
                Text(
                    if (speed != null) "Measured on this phone: ${fmtTps(speed.tgTps)} generating, ${fmtTps(speed.ppTps)} reading (${tuned.best.deviceName})"
                    else if (rep.estTps > 0) "Estimated ~${fmtTps(rep.estTps)} - run Optimize for a real number. ${rep.note}" else rep.note,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ if (isLoaded) onOpenChat() else { chatVm.selectModel(m); onOpenChat() } }, enabled = !busy && m.complete && rep.fit != com.jondude1.androidlm.tuner.Fit.TooBig) {
                        Text(if (isLoaded) "Chat" else "Load & chat")
                    }
                    OutlinedButton({ scope.launch { c.tuner.tune(m) } }, enabled = !busy && tuning?.finished != false && m.complete && rep.fit != com.jondude1.androidlm.tuner.Fit.TooBig) { Text(if (tuned != null) "Re-optimize" else "Optimize") }
                    TextButton({ confirmDelete = m }) { Text("Delete") }
                }
                if (!m.complete) Text("Some shards are missing.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${m.name}?") },
            text = { Text("The file is removed from this phone.") },
            confirmButton = { TextButton({ confirmDelete = null; scope.launch { c.models.delete(m) } }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun DownloadsCard(c: AppContainer) {
    val items by c.downloads.items.collectAsState()
    SectionCard("Downloads") {
        items.forEach { d ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(d.repo.substringAfter('/') + " - " + d.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                if (d.state != DlState.Done) LinearProgressIndicator(progress = { if (d.totalBytes > 0) d.doneBytes.toFloat() / d.totalBytes else 0f }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (d.state) {
                            DlState.Running -> "${fmtBytes(d.doneBytes)} / ${fmtBytes(d.totalBytes)} - ${fmtBytes(d.bytesPerSec)}/s"
                            DlState.Queued -> "Queued"
                            DlState.Paused -> "Paused at ${fmtBytes(d.doneBytes)} / ${fmtBytes(d.totalBytes)}"
                            DlState.Done -> "Done"
                            DlState.Failed -> "Failed: ${d.error}"
                        },
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (d.state) {
                        DlState.Running, DlState.Queued -> TextButton({ c.downloads.pause(d.id) }) { Text("Pause") }
                        DlState.Paused, DlState.Failed -> TextButton({ c.downloads.resume(d.id) }) { Text("Resume") }
                        DlState.Done -> {}
                    }
                    if (d.state != DlState.Done) TextButton({ c.downloads.cancel(d.id) }) { Text("Cancel") }
                }
            }
        }
        if (items.any { it.state == DlState.Done || it.state == DlState.Failed }) TextButton({ c.downloads.clearFinished() }) { Text("Clear finished") }
    }
}

@Composable
private fun Discover(c: AppContainer) {
    val scope = rememberCoroutineScope()
    val settings by c.settings.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<HfModel>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<HfModel?>(null) }
    var choices by remember { mutableStateOf<List<HfChoice>>(emptyList()) }
    var loadingChoices by remember { mutableStateOf(false) }
    var recs by remember { mutableStateOf<List<Recommendation>?>(null) }
    var loadingRecs by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    fun loadRecs() {
        if (loadingRecs || settings.offlineMode) return
        scope.launch {
            loadingRecs = true; error = null
            runCatching { recommendForThisPhone(c, settings.hfToken) }.onSuccess { recs = it }.onFailure { error = it.message }
            loadingRecs = false
        }
    }
    val tuneVer by c.tuneStore.version.collectAsState()
    // Re-check when offline mode changes or the tuner measures this phone's real speed.
    androidx.compose.runtime.LaunchedEffect(settings.offlineMode, tuneVer) { loadRecs() }

    fun search() {
        searched = true
        scope.launch {
            searching = true; error = null
            runCatching { c.hf.search(query.ifBlank { "gguf" }, settings.hfToken) }
                .onSuccess { results = it }.onFailure { error = it.message }
            searching = false
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (settings.offlineMode) {
            SectionCard { Text("Offline mode is on, so downloads are blocked. Turn it off in Settings to browse Hugging Face.", style = MaterialTheme.typography.bodyMedium) }
        }
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search Hugging Face (GGUF)") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ search() }, enabled = !searching) { Text(if (searching) "Searching..." else "Search") }
            listOf("qwen3", "gemma", "llama", "gpt-oss").forEach { s -> TextButton({ query = s; search() }) { Text(s) } }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val dls by c.downloads.items.collectAsState()
        if (dls.isNotEmpty()) DownloadsCard(c)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!searched) {
                item {
                    Text("Recommended for this phone", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Only models that fit in this phone's memory and should generate at ${MIN_USABLE_TPS.toInt()}+ tokens/s are listed, largest first. Search below to look further.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (loadingRecs) { Text("Checking what fits...", style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (recs?.isEmpty() == true) Text("Nothing suitable found right now. Try a search, or check your connection.", style = MaterialTheme.typography.bodySmall)
                }
                items(recs.orEmpty(), key = { it.repo + it.choice.label }) { r ->
                    SectionCard {
                        Text(r.repo.substringAfter('/'), style = MaterialTheme.typography.titleSmall)
                        Text(r.choice.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill(r.report.fit.label, fitColor(r.report.fit))
                            Text("${fmtBytes(r.choice.totalBytes)} - about ${fmtTps(r.report.estTps)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Button({ c.downloads.enqueue(r.repo, r.choice) }) { Text("Get") }
                        }
                    }
                }
            }
            items(results, key = { it.id }) { r ->
                SectionCard(modifier = Modifier.clickable {
                    open = r; choices = emptyList(); loadingChoices = true
                    scope.launch {
                        runCatching { c.hf.choices(r.id, settings.hfToken) }.onSuccess { choices = it }.onFailure { error = it.message }
                        loadingChoices = false
                    }
                }) {
                    Text(r.id, style = MaterialTheme.typography.titleSmall)
                    Text("${r.downloads} downloads - ${r.likes} likes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    open?.let { repo ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(repo.id, style = MaterialTheme.typography.titleSmall) },
            text = {
                if (loadingChoices) Text("Loading file list...")
                else if (choices.none { worksOnThisPhone(c, repo.id, it) != null }) Text("No file in this repository fits and runs well on this phone (${choices.size} checked). Try a smaller model or a lower-bit version.")
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(choices.filter { worksOnThisPhone(c, repo.id, it) != null }, key = { it.label }) { ch ->
                        val rep = worksOnThisPhone(c, repo.id, ch)!!
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(ch.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                Text(fmtBytes(ch.totalBytes) + (if (ch.files.size > 1) " - ${ch.files.size} parts" else "") + " - about ${fmtTps(rep.estTps)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Pill(rep.fit.label, fitColor(rep.fit))
                            TextButton({ c.downloads.enqueue(repo.id, ch); open = null }) { Text("Get") }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ open = null }) { Text("Close") } },
        )
    }
}
