package com.jondude1.androidlm.models

import android.content.Context
import android.os.Environment
import com.jondude1.androidlm.settings.AppSettings
import com.jondude1.androidlm.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Finds GGUF models on the phone. Models live in a shared folder (/sdcard/AndroidLM/models) when "All files access"
 * is granted - they survive an uninstall and can be dropped in with any file manager or adb - otherwise in the app's
 * own external folder. Extra folders can be added in Settings; nothing is ever copied.
 */
class ModelStore(private val context: Context, private val settings: AppSettings) {
    @Serializable
    private data class CacheEntry(val key: String, val info: ModelInfo)

    @Serializable
    private data class CacheFile(val entries: List<CacheEntry> = emptyList())

    private val cacheFile = File(context.filesDir, "models_index.json")
    private val cache = HashMap<String, ModelInfo>()

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _problems = MutableStateFlow<List<String>>(emptyList())
    /** Files that looked like models but could not be read (corrupt, incomplete download, ...). */
    val problems: StateFlow<List<String>> = _problems.asStateFlow()

    init {
        runCatching {
            AppJson.decodeFromString<CacheFile>(cacheFile.readText()).entries.forEach { cache[it.key] = it.info }
        }
    }

    val hasAllFilesAccess get() = Environment.isExternalStorageManager()

    /** Where new downloads go. */
    fun primaryDir(): File {
        val dir = if (hasAllFilesAccess) File(Environment.getExternalStorageDirectory(), "AndroidLM/models")
        else (context.getExternalFilesDir(null)?.let { File(it, "models") } ?: File(context.filesDir, "models"))
        dir.mkdirs()
        return dir
    }

    fun scanDirs(): List<File> {
        val dirs = LinkedHashSet<File>()
        dirs += primaryDir()
        context.getExternalFilesDir(null)?.let { dirs += File(it, "models") }
        if (hasAllFilesAccess) {
            dirs += File(Environment.getExternalStorageDirectory(), "AndroidLM/models")
        }
        settings.value.extraModelDirs.forEach { dirs += File(it) }
        return dirs.filter { it.isDirectory }
    }

    private val shardRe = Regex("""^(.*)-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _scanning.value = true
        try {
            val files = scanDirs().flatMap { d -> d.walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".gguf", true) }.toList() }
                .distinctBy { it.absolutePath }
                .filter { !it.name.startsWith("mmproj", true) && !it.name.endsWith(".part", true) }

            // group split models by (directory, prefix)
            val singles = ArrayList<File>()
            val groups = LinkedHashMap<String, MutableList<Pair<Int, File>>>()
            val groupTotals = HashMap<String, Int>()
            for (f in files) {
                val m = shardRe.matchEntire(f.name)
                if (m == null) singles += f else {
                    val key = f.parent + "/" + m.groupValues[1]
                    groups.getOrPut(key) { ArrayList() } += (m.groupValues[2].toInt() to f)
                    groupTotals[key] = m.groupValues[3].toInt()
                }
            }

            val out = ArrayList<LocalModel>()
            val bad = ArrayList<String>()
            fun add(first: File, name: String, size: Long, shards: Int, complete: Boolean) {
                val info = infoFor(first) ?: run { bad += first.name; return }
                var id = name
                var n = 2
                while (out.any { it.id == id }) id = "$name-${n++}"
                out += LocalModel(id = id, name = info.name.ifBlank { name }, path = first.absolutePath, sizeBytes = size, info = info, shards = shards, complete = complete)
            }
            for (f in singles) add(f, f.name.removeSuffix(".gguf"), f.length(), 1, true)
            for ((key, list) in groups) {
                val total = groupTotals[key] ?: list.size
                val sorted = list.sortedBy { it.first }
                val first = sorted.firstOrNull { it.first == 1 }?.second ?: continue
                val prefix = key.substringAfterLast('/')
                add(first, prefix, sorted.sumOf { it.second.length() }, total, sorted.size == total)
            }
            saveCache()
            _problems.value = bad
            _models.value = out.sortedBy { it.id.lowercase() }
        } finally {
            _scanning.value = false
        }
    }

    private fun infoFor(f: File): ModelInfo? {
        val key = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
        cache[key]?.let { return it }
        val info = runCatching { GgufReader.read(f) }.getOrNull() ?: return null
        cache[key] = info
        return info
    }

    private fun saveCache() {
        runCatching { cacheFile.writeText(AppJson.encodeToString(CacheFile(cache.map { CacheEntry(it.key, it.value) }))) }
    }

    fun find(id: String): LocalModel? = _models.value.firstOrNull { it.id == id }

    /** Deletes the model's file(s). Returns false if anything could not be removed. */
    suspend fun delete(model: LocalModel): Boolean = withContext(Dispatchers.IO) {
        val first = File(model.path)
        val victims = if (model.shards > 1) {
            val m = shardRe.matchEntire(first.name)
            if (m != null) first.parentFile?.listFiles { f -> shardRe.matchEntire(f.name)?.groupValues?.get(1) == m.groupValues[1] }?.toList().orEmpty()
            else listOf(first)
        } else listOf(first)
        val ok = victims.all { it.delete() }
        refresh()
        ok
    }
}
