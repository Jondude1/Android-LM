package com.jondude1.androidlm.settings

import android.content.Context
import com.jondude1.androidlm.engine.GenParams
import com.jondude1.androidlm.util.AppJson
import com.jondude1.androidlm.util.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class SettingsData(
    // generation defaults
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.0f,
    val maxTokens: Int = 2048,
    val showReasoning: Boolean = true,
    val thinking: Boolean = true,
    val systemPrompt: String = "You are a helpful, concise assistant running entirely on this phone.",
    // memory / context
    /** Upper bound for the context window the planner may pick (0 = no cap beyond what fits). */
    val maxContext: Int = 8192,
    val extraModelDirs: List<String> = emptyList(),
    // network
    val offlineMode: Boolean = false,
    val hfToken: String = "",
    // server
    val serverPort: Int = 1234,
    val serverLan: Boolean = false,
    val serverApiKey: String = "",
    val serverAutostart: Boolean = false,
    // misc
    val lastModelId: String = "",
    val keepScreenOn: Boolean = false,
    val lastUpdateCheck: Long = 0,
) {
    fun genParams() = GenParams(
        maxTokens = maxTokens, temperature = temperature, topK = topK, topP = topP, minP = minP,
        repeatPenalty = repeatPenalty, thinking = thinking,
    )
}

/** Tiny JSON-in-SharedPreferences store exposed as a StateFlow. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsData> = _state.asStateFlow()
    val value get() = _state.value

    init {
        Http.offline = _state.value.offlineMode
    }

    private fun load(): SettingsData =
        runCatching { AppJson.decodeFromString<SettingsData>(prefs.getString("data", null) ?: "{}") }.getOrDefault(SettingsData())

    @Synchronized
    fun update(block: SettingsData.() -> SettingsData) {
        val next = _state.value.block()
        _state.value = next
        Http.offline = next.offlineMode
        prefs.edit().putString("data", AppJson.encodeToString(next)).apply()
    }
}
