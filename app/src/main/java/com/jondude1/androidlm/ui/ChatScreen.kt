package com.jondude1.androidlm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.jondude1.androidlm.AppContainer
import com.jondude1.androidlm.engine.EngineStatus
import com.jondude1.androidlm.util.ThinkSplitter

@Composable
fun ChatScreen(c: AppContainer, vm: ChatViewModel) {
    val conv by vm.conv.collectAsState()
    val live by vm.live.collectAsState()
    val error by vm.error.collectAsState()
    val loading by vm.loading.collectAsState()
    val engine by c.engine.state.collectAsState()
    val models by c.models.models.collectAsState()
    val chats by c.chats.conversations.collectAsState()
    val settings by c.settings.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    var historyMenu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val generating = live != null

    LaunchedEffect(conv.messages.size, live?.answer?.length, live?.reasoning?.length) {
        val n = conv.messages.size + (if (live != null) 1 else 0)
        if (n > 0) list.scrollToItem(n - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // header: model picker + history + new chat
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(Modifier.clickable { modelMenu = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(engine.modelName ?: "Pick a model", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            when (engine.status) {
                                EngineStatus.Ready, EngineStatus.Generating -> "${engine.backend} - ${engine.nCtx} ctx"
                                EngineStatus.Loading -> "Loading..."
                                EngineStatus.Error -> "Load failed"
                                else -> "Not loaded"
                            },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    if (models.isEmpty()) DropdownMenuItem({ Text("No models yet - see the Models tab") }, onClick = { modelMenu = false })
                    models.filter { it.complete }.forEach { m ->
                        DropdownMenuItem({ Text("${m.name}  (${fmtBytes(m.sizeBytes)})") }, onClick = { modelMenu = false; vm.selectModel(m) })
                    }
                }
            }
            Box {
                IconButton({ historyMenu = true }) { Icon(Icons.Default.History, "History") }
                DropdownMenu(historyMenu, { historyMenu = false }) {
                    if (chats.isEmpty()) DropdownMenuItem({ Text("No saved chats") }, onClick = { historyMenu = false })
                    chats.take(30).forEach { cv ->
                        DropdownMenuItem(
                            { Text(cv.title, maxLines = 1) },
                            onClick = { historyMenu = false; vm.open(cv.id) },
                            trailingIcon = { IconButton({ vm.delete(cv.id) }) { Icon(Icons.Default.Delete, "Delete") } },
                        )
                    }
                }
            }
            IconButton({ vm.newChat() }) { Icon(Icons.Default.Add, "New chat") }
        }
        if (loading || engine.status == EngineStatus.Loading) {
            LinearProgressIndicator(progress = { engine.progress }, modifier = Modifier.fillMaxWidth())
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (conv.messages.isEmpty() && live == null) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Everything here runs on this phone.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "No account, no cloud. Pick a model above (or download one in the Models tab) and start typing.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(conv.messages, key = { it.id }) { m ->
                    if (m.role == "user") UserBubble(m.content)
                    else {
                        val (r, a) = ThinkSplitter.splitAll(m.content)
                        AssistantBubble(r, a, settings.showReasoning, m.stats?.let { s ->
                            "${fmtTps(s.genTps)} - ${s.genTokens} tokens" + (if (s.prefillTps > 0) " - prompt ${fmtTps(s.prefillTps)}" else "") + (if (s.cachedTokens > 0) " - ${s.cachedTokens} cached" else "")
                        })
                    }
                }
                live?.let { l -> item { AssistantBubble(l.reasoning, l.answer, settings.showReasoning, null, streaming = true) } }
            }
        }

        error?.let {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                TextButton({ vm.dismissError() }) { Text("OK") }
            }
        }

        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message") },
                maxLines = 6, shape = RoundedCornerShape(24.dp),
            )
            if (generating) {
                FilledIconButton({ vm.stop() }) { Icon(Icons.Default.Stop, "Stop") }
            } else {
                FilledIconButton({ val t = input; input = ""; vm.send(t) }, enabled = input.isNotBlank() && !loading) { Icon(Icons.Default.Send, "Send") }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.widthIn(max = 320.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).padding(12.dp)) {
            SelectionContainer { Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}

@Composable
private fun AssistantBubble(reasoning: String, answer: String, showReasoning: Boolean, stats: String?, streaming: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (reasoning.isNotBlank() && showReasoning) {
            val expanded = open || (streaming && answer.isEmpty())
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).clickable { open = !open }.padding(10.dp)) {
                Text(if (streaming && answer.isEmpty()) "Thinking..." else "Thought process", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                if (expanded) Text(reasoning.trim(), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (answer.isNotBlank()) SelectionContainer { Text(answer.trim()) }
        else if (streaming && reasoning.isBlank()) CircularProgressIndicator(Modifier.padding(4.dp).widthIn(max = 20.dp), strokeWidth = 2.dp)
        stats?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
