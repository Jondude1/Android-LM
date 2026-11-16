package com.jondude1.androidlm.chat

import android.content.Context
import com.jondude1.androidlm.engine.GenStats
import com.jondude1.androidlm.util.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val stats: GenStats? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val modelId: String = "",
    /** null = use the default system prompt from Settings. */
    val systemPrompt: String? = null,
    val messages: List<ChatMessage> = emptyList(),
)

/** Conversations are plain JSON files in app-private storage. They never leave the phone. */
class ChatStore(context: Context) {
    private val dir = File(context.filesDir, "chats").apply { mkdirs() }
    private val _all = MutableStateFlow(loadAll())
    val conversations: StateFlow<List<Conversation>> = _all.asStateFlow()

    private fun loadAll(): List<Conversation> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { AppJson.decodeFromString<Conversation>(f.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    @Synchronized
    fun save(c: Conversation) {
        val updated = c.copy(updatedAt = System.currentTimeMillis())
        runCatching { File(dir, "${updated.id}.json").writeText(AppJson.encodeToString(updated)) }
        _all.value = (listOf(updated) + _all.value.filterNot { it.id == updated.id }).sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.json").delete()
        _all.value = _all.value.filterNot { it.id == id }
    }

    fun get(id: String): Conversation? = _all.value.firstOrNull { it.id == id }
}
