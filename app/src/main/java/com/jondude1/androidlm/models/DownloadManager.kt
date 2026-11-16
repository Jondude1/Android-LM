package com.jondude1.androidlm.models

import com.jondude1.androidlm.settings.AppSettings
import com.jondude1.androidlm.util.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

enum class DlState { Queued, Running, Paused, Done, Failed }

data class DownloadItem(
    val id: String,
    val repo: String,
    val label: String,
    val files: List<HfFile>,
    val totalBytes: Long,
    val doneBytes: Long = 0,
    val state: DlState = DlState.Queued,
    val error: String? = null,
    val bytesPerSec: Long = 0,
)

/**
 * Downloads GGUF files from Hugging Face one after another. Interrupted transfers resume from the partial file (the
 * phone's connection will drop on a 20 GB download), and everything lands in the models folder, ready to load.
 */
class DownloadManager(
    private val settings: AppSettings,
    private val store: ModelStore,
    private val scope: CoroutineScope,
) {
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    val active: Flow<Boolean> get() = _items.map { list -> list.any { it.state == DlState.Running || it.state == DlState.Queued } }

    private val jobs = HashMap<String, Job>()
    private val queue = Mutex()

    private fun patch(id: String, f: (DownloadItem) -> DownloadItem) = _items.update { l -> l.map { if (it.id == id) f(it) else it } }

    fun enqueue(repo: String, choice: HfChoice) {
        val id = UUID.randomUUID().toString()
        _items.update { it + DownloadItem(id, repo, choice.label, choice.files, choice.totalBytes) }
        start(id)
    }

    fun resume(id: String) {
        patch(id) { it.copy(state = DlState.Queued, error = null) }
        start(id)
    }

    fun pause(id: String) {
        jobs[id]?.cancel()
        patch(id) { if (it.state == DlState.Running || it.state == DlState.Queued) it.copy(state = DlState.Paused, bytesPerSec = 0) else it }
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
        val item = _items.value.firstOrNull { it.id == id } ?: return
        scope.launch(Dispatchers.IO) {
            item.files.forEach { File(store.primaryDir(), it.name + ".part").delete() }
        }
        _items.update { l -> l.filterNot { it.id == id } }
    }

    fun clearFinished() = _items.update { l -> l.filter { it.state != DlState.Done && it.state != DlState.Failed } }

    private fun start(id: String) {
        jobs[id]?.cancel()
        jobs[id] = scope.launch(Dispatchers.IO) {
            queue.withLock {
                val item = _items.value.firstOrNull { it.id == id } ?: return@withLock
                if (item.state == DlState.Paused) return@withLock
                patch(id) { it.copy(state = DlState.Running, error = null) }
                try {
                    run(id)
                    patch(id) { it.copy(state = DlState.Done, doneBytes = it.totalBytes, bytesPerSec = 0) }
                    store.refresh()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    patch(id) { it.copy(state = DlState.Failed, error = e.message ?: e.toString(), bytesPerSec = 0) }
                }
            }
        }
    }

    private suspend fun run(id: String) = withContext(Dispatchers.IO) {
        val item = _items.value.first { it.id == id }
        val dir = store.primaryDir()
        val token = settings.value.hfToken
        val headers = if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${token.trim()}")

        var completedBytes = 0L
        for (f in item.files) {
            val dest = File(dir, f.name)
            if (dest.exists() && dest.length() == f.size) {
                completedBytes += f.size
                patch(id) { it.copy(doneBytes = completedBytes) }
                continue
            }
            val part = File(dir, f.name + ".part")
            val have = if (part.exists()) part.length() else 0L
            if (dir.usableSpace < f.size - have + (256L shl 20)) throw IOException("Not enough free storage for ${f.name}")

            val url = "https://huggingface.co/${item.repo}/resolve/main/${f.path}"
            var conn = Http.open(url, headers, rangeFrom = have.takeIf { it > 0 }, timeoutMs = 30_000)
            var offset = have
            if (have > 0 && conn.responseCode != 206) { // server ignored the Range header: start over
                conn.disconnect()
                part.delete()
                offset = 0
                conn = Http.open(url, headers, timeoutMs = 30_000)
            }
            try {
                FileOutputStream(part, offset > 0).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(256 * 1024)
                        var lastTick = System.nanoTime()
                        var tickBytes = 0L
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            offset += n
                            tickBytes += n
                            val now = System.nanoTime()
                            if (now - lastTick > 500_000_000L) {
                                val bps = (tickBytes * 1e9 / (now - lastTick)).toLong()
                                patch(id) { it.copy(doneBytes = completedBytes + offset, bytesPerSec = bps) }
                                lastTick = now
                                tickBytes = 0
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (!isActive) return@withContext
            if (f.size > 0 && part.length() != f.size) throw IOException("${f.name}: incomplete (${part.length()} of ${f.size} bytes), resume to continue")
            if (!part.renameTo(dest)) throw IOException("Could not move ${f.name} into place")
            completedBytes += f.size
            patch(id) { it.copy(doneBytes = completedBytes) }
        }
    }
}
