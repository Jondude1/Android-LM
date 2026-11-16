package com.jondude1.androidlm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jondude1.androidlm.AppContainer
import com.jondude1.androidlm.chat.ChatMessage
import com.jondude1.androidlm.chat.Conversation
import com.jondude1.androidlm.engine.ChatMsg
import com.jondude1.androidlm.engine.EngineStatus
import com.jondude1.androidlm.engine.GenEvent
import com.jondude1.androidlm.engine.GenStats
import com.jondude1.androidlm.models.LocalModel
import com.jondude1.androidlm.util.ThinkSplitter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Live(val reasoning: String = "", val answer: String = "", val tps: Double = 0.0)

class ChatViewModel(private val c: AppContainer) : ViewModel() {
    private val _conv = MutableStateFlow(Conversation())
    val conv: StateFlow<Conversation> = _conv.asStateFlow()
    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var job: Job? = null
    val busy get() = job?.isActive == true

    fun newChat() { stop(); _conv.value = Conversation(); _live.value = null; _error.value = null }
    fun open(id: String) { stop(); c.chats.get(id)?.let { _conv.value = it }; _live.value = null }
    fun delete(id: String) { c.chats.delete(id); if (_conv.value.id == id) newChat() }
    fun dismissError() { _error.value = null }

    fun selectModel(m: LocalModel) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            c.loadModel(m).onFailure { _error.value = "Could not load ${m.name}: ${it.message}" }
            _loading.value = false
        }
    }

    fun stop() { c.engine.cancel() }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        job = viewModelScope.launch {
            _error.value = null
            val st = c.engine.state.value
            if (st.status != EngineStatus.Ready) {
                val m = c.models.models.value.firstOrNull { it.id == c.settings.value.lastModelId && it.complete } ?: c.models.models.value.firstOrNull { it.complete }
                if (m == null) { _error.value = "No model on this phone yet. Open the Models tab and download one."; return@launch }
                _loading.value = true
                val r = c.loadModel(m)
                _loading.value = false
                if (r.isFailure) { _error.value = "Could not load ${m.name}: ${r.exceptionOrNull()?.message}"; return@launch }
            }
            val modelId = c.engine.state.value.modelId.orEmpty()
            var conv = _conv.value.copy(modelId = modelId)
            conv = conv.copy(messages = conv.messages + ChatMessage(role = "user", content = t), title = if (conv.messages.isEmpty()) t.take(40) else conv.title)
            _conv.value = conv
            c.chats.save(conv)

            val sys = conv.systemPrompt ?: c.settings.value.systemPrompt
            val msgs = buildList {
                if (sys.isNotBlank()) add(ChatMsg("system", sys))
                for (m in conv.messages) add(ChatMsg(m.role, if (m.role == "assistant") ThinkSplitter.splitAll(m.content).second else m.content))
            }
            val splitter = ThinkSplitter()
            val raw = StringBuilder()
            val reasoning = StringBuilder()
            val answer = StringBuilder()
            var stats: GenStats? = null
            var lastUi = 0L
            _live.value = Live()
            c.engine.generate(msgs, c.settings.value.genParams()).collect { ev ->
                when (ev) {
                    is GenEvent.Token -> {
                        raw.append(ev.text)
                        for (s in splitter.push(ev.text)) (if (s.reasoning) reasoning else answer).append(s.text)
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 60) {
                            lastUi = now
                            _live.value = Live(reasoning.toString(), answer.toString())
                        }
                    }
                    is GenEvent.Done -> stats = ev.stats
                    is GenEvent.Error -> _error.value = ev.message
                    is GenEvent.Trimmed -> {}
                }
            }
            for (s in splitter.flush()) (if (s.reasoning) reasoning else answer).append(s.text)
            _live.value = null
            if (raw.isNotEmpty()) {
                val full = if (reasoning.isNotEmpty()) "<think>$reasoning</think>$answer" else answer.toString()
                conv = _conv.value.copy(messages = _conv.value.messages + ChatMessage(role = "assistant", content = full, stats = stats))
                _conv.value = conv
                c.chats.save(conv)
            }
        }
    }
}
