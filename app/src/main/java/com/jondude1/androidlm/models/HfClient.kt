package com.jondude1.androidlm.models

import com.jondude1.androidlm.util.AppJson
import com.jondude1.androidlm.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URLEncoder

@Serializable
data class HfModel(
    val id: String,
    val downloads: Long = 0,
    val likes: Long = 0,
    val tags: List<String> = emptyList(),
)

@Serializable
data class HfFile(val path: String, val size: Long) {
    val name get() = path.substringAfterLast('/')
}

/** One downloadable choice: a single .gguf, or all shards of a split model. */
data class HfChoice(val label: String, val files: List<HfFile>) {
    val totalBytes get() = files.sumOf { it.size }
}

@Serializable
private data class HfLfs(val size: Long = 0)

@Serializable
private data class HfTreeEntry(val type: String = "", val path: String = "", val size: Long = 0, val lfs: HfLfs? = null)

/** Model discovery on Hugging Face. This is the only part of the app (besides updates) that can use the network. */
object HfClient {
    private fun auth(token: String) = if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${token.trim()}")

    suspend fun search(query: String, token: String): List<HfModel> = withContext(Dispatchers.IO) {
        val q = if (query.isBlank()) "" else "&search=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val body = Http.getString("https://huggingface.co/api/models?filter=gguf&sort=downloads&direction=-1&limit=40$q", auth(token))
        AppJson.decodeFromString(ListSerializer(HfModel.serializer()), body)
    }

    suspend fun choices(repoId: String, token: String): List<HfChoice> = withContext(Dispatchers.IO) {
        val body = Http.getString("https://huggingface.co/api/models/$repoId/tree/main?recursive=true", auth(token))
        val entries = AppJson.decodeFromString(ListSerializer(HfTreeEntry.serializer()), body)
            .filter { it.type == "file" && it.path.endsWith(".gguf", true) && !it.path.substringAfterLast('/').startsWith("mmproj", true) }
            .map { HfFile(it.path, it.lfs?.size?.takeIf { s -> s > 0 } ?: it.size) }

        val shardRe = Regex("""^(.*)-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)
        val singles = ArrayList<HfChoice>()
        val groups = LinkedHashMap<String, MutableList<HfFile>>()
        for (f in entries) {
            val m = shardRe.matchEntire(f.path)
            if (m == null) singles += HfChoice(f.name, listOf(f))
            else groups.getOrPut(m.groupValues[1]) { ArrayList() } += f
        }
        val grouped = groups.map { (prefix, list) ->
            HfChoice("${prefix.substringAfterLast('/')}.gguf (${list.size} parts)", list.sortedBy { it.path })
        }
        (singles + grouped).sortedBy { it.totalBytes }
    }
}
