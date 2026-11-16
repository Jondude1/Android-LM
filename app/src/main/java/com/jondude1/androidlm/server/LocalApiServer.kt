package com.jondude1.androidlm.server

import com.jondude1.androidlm.engine.ChatMsg
import com.jondude1.androidlm.engine.GenEvent
import com.jondude1.androidlm.engine.GenStats
import com.jondude1.androidlm.engine.LlmEngine
import com.jondude1.androidlm.models.LocalModel
import com.jondude1.androidlm.models.ModelStore
import com.jondude1.androidlm.settings.AppSettings
import com.jondude1.androidlm.util.AppJson
import com.jondude1.androidlm.util.ThinkSplitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * An OpenAI-compatible HTTP server (the same API LM Studio exposes), so every other app on the phone - or, if you
 * switch it on, other devices on your network - can use the on-device model. Binds to loopback by default.
 *
 *   GET  /v1/models
 *   POST /v1/chat/completions   (streaming and non-streaming, reasoning split into reasoning_content)
 */
class LocalApiServer(
    private val engine: LlmEngine,
    private val store: ModelStore,
    private val settings: AppSettings,
    private val ensureLoaded: suspend (String?) -> Result<LocalModel>,
    private val scope: CoroutineScope,
) {
    data class State(
        val running: Boolean = false,
        val url: String = "",
        val requests: Int = 0,
        val error: String? = null,
        val log: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private val gate = Mutex() // one generation at a time; the rest queue

    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        val s = settings.value
        val bindAddr = InetAddress.getByName(if (s.serverLan) "0.0.0.0" else "127.0.0.1")
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindAddr, s.serverPort))
            }
        } catch (e: IOException) {
            _state.update { it.copy(running = false, error = "Could not listen on port ${s.serverPort}: ${e.message}") }
            return false
        }
        server = ss
        val host = if (s.serverLan) lanAddress() ?: "0.0.0.0" else "127.0.0.1"
        _state.update { it.copy(running = true, url = "http://$host:${s.serverPort}/v1", error = null) }
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val c = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                launch { handle(c) }
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        acceptJob?.cancel()
        acceptJob = null
        _state.update { it.copy(running = false) }
    }

    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun log(line: String) = _state.update { it.copy(requests = it.requests + 1, log = (listOf(line) + it.log).take(40)) }

    // ---- HTTP plumbing ------------------------------------------------------------------------------------------

    private fun readLine(inp: InputStream): String? {
        val sb = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.size() == 0) null else sb.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
            if (sb.size() > 16 * 1024) throw IOException("header line too long")
        }
        return sb.toString("UTF-8")
    }

    private fun readRequest(inp: InputStream, out: OutputStream): Req? {
        val line = readLine(inp) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(inp) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 16 * 1024 * 1024) throw IOException("request body too large")
        if (headers["expect"]?.contains("100-continue", true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            out.flush()
        }
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = inp.read(body, off, len - off)
            if (r < 0) throw IOException("unexpected end of body")
            off += r
        }
        return Req(parts[0].uppercase(), parts[1], headers, body)
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        500 -> "Internal Server Error"; 501 -> "Not Implemented"; 503 -> "Service Unavailable"; else -> "OK"
    }

    private val cors = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\n"

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        out.write("HTTP/1.1 $code ${reason(code)}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n${cors}Connection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun respondJson(out: OutputStream, code: Int, json: JsonElement) = respond(out, code, "application/json; charset=utf-8", json.toString().toByteArray())

    private fun respondError(out: OutputStream, code: Int, message: String, type: String = "invalid_request_error") =
        respondJson(out, code, buildJsonObject { putJsonObject("error") { put("message", message); put("type", type); put("code", code) } })

    private suspend fun handle(sock: Socket) {
        sock.use {
            sock.soTimeout = 30_000
            val input = BufferedInputStream(sock.getInputStream(), 16 * 1024)
            val out = BufferedOutputStream(sock.getOutputStream())
            try {
                val req = readRequest(input, out) ?: return
                sock.soTimeout = 0
                log("${req.method} ${req.path.substringBefore('?')}")
                route(req, out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // client went away: nothing to answer
            } catch (e: Throwable) {
                runCatching { respondError(out, 500, e.message ?: e.toString(), "server_error") }
            } finally {
                runCatching { out.flush() }
            }
        }
    }

    private suspend fun route(req: Req, out: OutputStream) {
        val path = req.path.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        if (req.method == "OPTIONS") return respond(out, 204, "text/plain", ByteArray(0))

        val key = settings.value.serverApiKey
        if (key.isNotBlank() && path != "/" && path != "/health") {
            val given = req.headers["authorization"]?.removePrefix("Bearer ")?.trim()
            if (given != key) return respondError(out, 401, "Invalid or missing API key", "authentication_error")
        }

        when {
            req.method == "GET" && (path == "/" || path == "/health") ->
                respond(out, 200, "text/plain; charset=utf-8", "Android LM server is running.\n".toByteArray())
            req.method == "GET" && (path == "/v1/models" || path == "/models" || path == "/api/v0/models") -> models(out)
            req.method == "POST" && (path == "/v1/chat/completions" || path == "/chat/completions" || path == "/api/v0/chat/completions") -> chat(req, out)
            req.method == "POST" && path.endsWith("/completions") ->
                respondError(out, 501, "Only /v1/chat/completions is supported.")
            else -> respondError(out, 404, "Unknown route: ${req.method} $path", "not_found")
        }
    }

    // ---- endpoints ----------------------------------------------------------------------------------------------

    private fun models(out: OutputStream) {
        val loaded = engine.state.value.modelId
        respondJson(out, 200, buildJsonObject {
            put("object", "list")
            putJsonArray("data") {
                for (m in store.models.value) addJsonObject {
                    put("id", m.id)
                    put("object", "model")
                    put("owned_by", "android-lm")
                    put("state", if (m.id == loaded) "loaded" else "not-loaded")
                    put("arch", m.info.arch)
                    put("quantization", m.info.quant)
                    put("max_context_length", m.info.ctxTrain)
                }
            }
        })
    }

    private fun flatten(el: JsonElement?): String = when (el) {
        null, is JsonNull -> ""
        is JsonPrimitive -> el.content
        is JsonArray -> el.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull (p as? JsonPrimitive)?.content
            val type = (o["type"] as? JsonPrimitive)?.contentOrNull
            if (type == null || type == "text" || type == "input_text") (o["text"] as? JsonPrimitive)?.contentOrNull else null
        }.joinToString("")
        else -> ""
    }

    private suspend fun chat(req: Req, out: OutputStream) {
        val root = try {
            AppJson.parseToJsonElement(req.body.decodeToString()).jsonObject
        } catch (e: Exception) {
            return respondError(out, 400, "Request body is not valid JSON")
        }
        val arr = root["messages"] as? JsonArray ?: return respondError(out, 400, "'messages' is required")
        val messages = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val role = when (val r = (o["role"] as? JsonPrimitive)?.contentOrNull ?: "user") { "developer" -> "system"; "tool", "function" -> "user"; else -> r }
            ChatMsg(role, flatten(o["content"]))
        }
        if (messages.isEmpty()) return respondError(out, 400, "'messages' is empty")

        fun num(k: String) = (root[k] as? JsonPrimitive)?.doubleOrNull
        val d = settings.value.genParams()
        val params = d.copy(
            temperature = num("temperature")?.toFloat() ?: d.temperature,
            topP = num("top_p")?.toFloat() ?: d.topP,
            topK = num("top_k")?.toInt() ?: d.topK,
            minP = num("min_p")?.toFloat() ?: d.minP,
            repeatPenalty = num("repeat_penalty")?.toFloat() ?: d.repeatPenalty,
            maxTokens = (num("max_completion_tokens") ?: num("max_tokens"))?.toInt() ?: d.maxTokens,
            seed = num("seed")?.toLong() ?: -1,
        )
        val stops = when (val s = root["stop"]) {
            is JsonPrimitive -> listOfNotNull(s.contentOrNull)
            is JsonArray -> s.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        val stream = (root["stream"] as? JsonPrimitive)?.booleanOrNull == true
        val modelReq = (root["model"] as? JsonPrimitive)?.contentOrNull

        gate.withLock {
            val model = ensureLoaded(modelReq).getOrElse { return respondError(out, 503, "Could not load a model: ${it.message}", "server_error") }
            val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
            val created = System.currentTimeMillis() / 1000
            if (stream) streamChat(out, id, created, model.id, messages, params, stops)
            else plainChat(out, id, created, model.id, messages, params, stops)
        }
    }

    private fun finishReason(stats: GenStats) = if (stats.stop == "length" || stats.stop == "context_full") "length" else "stop"

    private fun usageJson(s: GenStats) = buildJsonObject {
        put("prompt_tokens", s.promptTokens)
        put("completion_tokens", s.genTokens)
        put("total_tokens", s.promptTokens + s.genTokens)
    }

    private fun statsJson(s: GenStats) = buildJsonObject {
        put("tokens_per_second", s.genTps)
        put("prompt_tokens_per_second", s.prefillTps)
        put("time_to_first_token", s.prefillMs / 1000.0)
        put("generation_time", s.genMs / 1000.0)
        put("cached_prompt_tokens", s.cachedTokens)
        put("stop_reason", s.stop)
    }

    private suspend fun plainChat(out: OutputStream, id: String, created: Long, model: String, msgs: List<ChatMsg>, p: com.jondude1.androidlm.engine.GenParams, stops: List<String>) {
        val sb = StringBuilder()
        var stats: GenStats? = null
        var error: String? = null
        engine.generate(msgs, p, stops).collect { ev ->
            when (ev) {
                is GenEvent.Token -> sb.append(ev.text)
                is GenEvent.Done -> stats = ev.stats
                is GenEvent.Error -> error = ev.message
                is GenEvent.Trimmed -> {}
            }
        }
        val err = error
        if (err != null) return respondError(out, 500, err, "server_error")
        val s = stats ?: GenStats()
        val (reasoning, answer) = ThinkSplitter.splitAll(sb.toString())
        respondJson(out, 200, buildJsonObject {
            put("id", id)
            put("object", "chat.completion")
            put("created", created)
            put("model", model)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", answer)
                        if (reasoning.isNotEmpty()) put("reasoning_content", reasoning)
                    }
                    put("finish_reason", finishReason(s))
                }
            }
            put("usage", usageJson(s))
            put("stats", statsJson(s))
        })
    }

    private fun chunk(id: String, created: Long, model: String, delta: JsonObject, finish: String?, extra: Map<String, JsonElement> = emptyMap()) = buildJsonObject {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", created)
        put("model", model)
        putJsonArray("choices") {
            addJsonObject {
                put("index", 0)
                put("delta", delta)
                if (finish == null) put("finish_reason", JsonNull) else put("finish_reason", finish)
            }
        }
        extra.forEach { (k, v) -> put(k, v) }
    }

    private suspend fun streamChat(out: OutputStream, id: String, created: Long, model: String, msgs: List<ChatMsg>, p: com.jondude1.androidlm.engine.GenParams, stops: List<String>) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\n${cors}Connection: close\r\n\r\n".toByteArray())
        fun send(json: JsonElement) {
            out.write("data: $json\n\n".toByteArray())
            out.flush()
        }
        send(chunk(id, created, model, buildJsonObject { put("role", "assistant"); put("content", "") }, null))

        val splitter = ThinkSplitter()
        fun emit(segs: List<ThinkSplitter.Seg>) {
            for (seg in segs) {
                if (seg.text.isEmpty()) continue
                send(chunk(id, created, model, buildJsonObject { put(if (seg.reasoning) "reasoning_content" else "content", seg.text) }, null))
            }
        }
        engine.generate(msgs, p, stops).collect { ev ->
            when (ev) {
                is GenEvent.Token -> emit(splitter.push(ev.text))
                is GenEvent.Done -> {
                    emit(splitter.flush())
                    send(chunk(id, created, model, buildJsonObject {}, finishReason(ev.stats), mapOf("usage" to usageJson(ev.stats), "stats" to statsJson(ev.stats))))
                    out.write("data: [DONE]\n\n".toByteArray())
                    out.flush()
                }
                is GenEvent.Error -> {
                    send(buildJsonObject { putJsonObject("error") { put("message", ev.message); put("type", "server_error") } })
                    out.write("data: [DONE]\n\n".toByteArray())
                    out.flush()
                }
                is GenEvent.Trimmed -> {}
            }
        }
    }
}
