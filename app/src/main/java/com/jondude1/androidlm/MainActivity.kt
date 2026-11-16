package com.jondude1.androidlm

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jondude1.androidlm.service.LmService
import com.jondude1.androidlm.ui.AndroidLmTheme
import com.jondude1.androidlm.ui.ChatScreen
import com.jondude1.androidlm.ui.ChatViewModel
import com.jondude1.androidlm.ui.DeviceScreen
import com.jondude1.androidlm.ui.ModelsScreen
import com.jondude1.androidlm.ui.ServerScreen
import com.jondude1.androidlm.ui.SettingsScreen
import com.jondude1.androidlm.update.UpdateState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        setContent { AndroidLmTheme { Root(c) } }
        // Other apps (ATLAS) wake the server with this action; stay out of the way and keep it up from then on.
        val serve = intent?.action == ACTION_SERVE
        if (serve && !c.settings.value.serverAutostart) c.settings.update { copy(serverAutostart = true) }
        if ((serve || c.settings.value.serverAutostart) && !c.server.state.value.running) {
            LmService.start(this)
            c.server.start()
        }
        if (serve) moveTaskToBack(true)
    }

    companion object {
        const val ACTION_SERVE = "com.jondude1.androidlm.SERVE"
    }
}

private enum class Tab(val label: String) { Chat("Chat"), Models("Models"), Device("Device"), Server("Server"), Settings("Settings") }

@Composable
private fun Root(c: AppContainer) {
    val vm: ChatViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(c) as T
    })
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val upd by c.updater.state.collectAsState()
    val settings by c.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity

    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { notif.launch(Manifest.permission.POST_NOTIFICATIONS) }

    LaunchedEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    // Quiet update check each time the app comes to the front.
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { c.updater.maybeCheck() }
    }
    // Keep the service alive while downloads run.
    val dlActive by c.downloads.active.collectAsState(false)
    LaunchedEffect(dlActive) { if (dlActive) LmService.start(activity) else if (!c.server.state.value.running) LmService.stop(activity) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val icons = listOf(Icons.Default.Chat, Icons.Default.Storage, Icons.Default.Memory, Icons.Default.Cloud, Icons.Default.Settings)
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(tab == i, { tab = i }, { Icon(icons[i], t.label) }, label = { Text(t.label) })
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (val u = upd) {
                is UpdateState.Available, is UpdateState.Ready -> {
                    val tag = (u as? UpdateState.Available)?.info?.tag ?: (u as UpdateState.Ready).info.tag
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Update available: $tag", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                        TextButton({ scope.launch { c.updater.downloadAndInstall() } }) { Text("Update") }
                        TextButton({ c.updater.dismiss() }) { Text("Later") }
                    }
                }
                is UpdateState.Downloading -> LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth())
                else -> {}
            }
            when (Tab.entries[tab]) {
                Tab.Chat -> ChatScreen(c, vm)
                Tab.Models -> ModelsScreen(c, vm) { tab = 0 }
                Tab.Device -> DeviceScreen(c)
                Tab.Server -> ServerScreen(c)
                Tab.Settings -> SettingsScreen(c)
            }
        }
    }
}
