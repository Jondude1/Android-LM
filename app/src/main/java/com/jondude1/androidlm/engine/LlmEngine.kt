package com.jondude1.androidlm.engine

import android.content.Context
import com.jondude1.androidlm.models.LocalModel
import com.jondude1.androidlm.util.AppJson
import com.jondude1.androidlm.util.StopFilter
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kotlin face of the native engine. One model at a time; every native call runs on a single dedicated thread
 * (big stack: the first GPU kernel compile is stack hungry), so callers can fire requests from anywhere.
 */
class LlmEngine(private val app: Context, val crashGuard: CrashGuard) {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(null, r, "llm-native", 16L shl 20) }
        .asCoroutineDispatcher()

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile private var initResult: InitResult? = null
    val devices: List<NativeDevice> get() = initResult?.devices.orEmpty()
    val systemInfo: String get() = initResult?.system.orEmpty()

    private inline fun <reified T> dec(b: ByteArray): T = AppJson.decodeFromString<T>(String(b, Charsets.UTF_8))
    private inline fun <reified T> enc(x: T): ByteArray = AppJson.encodeToString(x).toByteArray(Charsets.UTF_8)

    /** Loads the ggml backends (CPU variants, OpenCL, Vulkan) and enumerates devices. Safe to call repeatedly. */
    suspend fun ensureInit(): InitResult = withContext(dispatcher) {
        initResult ?: dec<InitResult>(Native.init(app.applicationInfo.nativeLibraryDir.toByteArray())).also { initResult = it }
    }

    // ---- low-level steps (used by the tuner) -------------------------------------------------------------------

    suspend fun loadRaw(path: String, cfg: LoadConfig, onProgress: (Float) -> Unit = {}): LoadResult = withContext(dispatcher) {
        ensureInitLocked()
        dec<LoadResult>(
            Native.loadModel(path.toByteArray(), enc(cfg), object : ProgressSink {
                override fun onProgress(p: Float): Boolean {
                    onProgress(p)
                    return true
                }
            })
        )
    }

    suspend fun newContext(cfg: ContextConfig): CtxResult = withContext(dispatcher) { dec<CtxResult>(Native.newContext(enc(cfg))) }

    suspend fun benchRaw(pp: Int, tg: Int, reps: Int = 1): BenchResult = withContext(dispatcher) { dec<BenchResult>(Native.bench(pp, tg, reps)) }

    private fun ensureInitLocked() {
        if (initResult == null) initResult = dec<InitResult>(Native.init(app.applicationInfo.nativeLibraryDir.toByteArray()))
    }

    // ---- high-level: bring a model up -------------------------------------------------------------------------

    /**
     * Loads [model] with [run]. If the context cannot be allocated (out of memory, unsupported cache type), steps down a
     * ladder - smaller context, quantised KV cache, smaller batches - until something fits, and returns the config that worked.
     */
    suspend fun load(model: LocalModel, run: RunConfig, onProgress: (Float) -> Unit = {}): Result<RunConfig> = withContext(dispatcher) {
        _state.update { EngineState(status = EngineStatus.Loading, modelId = model.id, modelName = model.name, backend = run.deviceName) }
        crashGuard.begin("load", model.id, run.deviceName)
        try {
            ensureInitLocked()
            val t0 = System.nanoTime()
            val lr = dec<LoadResult>(
                Native.loadModel(model.path.toByteArray(), enc(run.load), object : ProgressSink {
                    override fun onProgress(p: Float): Boolean {
                        _state.update { it.copy(progress = p) }
                        onProgress(p)
                        return true
                    }
                })
            )
            if (!lr.ok) error(lr.error ?: "model failed to load")

            var used: ContextConfig? = null
            var lastErr = ""
            for (cfg in contextLadder(run.ctx, lr.nCtxTrain)) {
                val cr = dec<CtxResult>(Native.newContext(enc(cfg)))
                if (cr.ok) {
                    used = cfg.copy(nCtx = cr.nCtx)
                    break
                }
                lastErr = cr.error ?: "context creation failed"
            }
            if (used == null) {
                Native.unload()
                error(lastErr)
            }

            // Warm-up: GPU backends compile kernels on first use. Doing it now keeps the first reply fast, and a driver
            // that is going to misbehave does so here, while the crash guard is armed.
            val warm = dec<BenchResult>(Native.bench(16, 4, 1))
            if (!warm.ok) {
                Native.unload()
                error("${run.deviceName}: ${warm.error ?: "warm-up failed"}")
            }

            val final = run.copy(ctx = used)
            val secs = (System.nanoTime() - t0) / 1e9
            _state.value = EngineState(
                status = EngineStatus.Ready, modelId = model.id, modelName = model.name, backend = run.deviceName,
                progress = 1f, nCtx = used.nCtx, runConfig = final, loadSeconds = secs,
            )
            Result.success(final)
        } catch (e: Throwable) {
            _state.value = EngineState(status = EngineStatus.Error, modelId = model.id, modelName = model.name, backend = run.deviceName, error = e.message ?: e.toString())
            Result.failure(e)
        } finally {
            crashGuard.end()
        }
    }

    private fun contextLadder(c: ContextConfig, nCtxTrain: Int): Sequence<ContextConfig> = sequence {
        val start = if (nCtxTrain > 0) minOf(c.nCtx, nCtxTrain) else c.nCtx
        var cur = c.copy(nCtx = start)
        yield(cur)
        var n = start
        while (n > 2048) {
            n = maxOf(2048, n / 2)
            cur = cur.copy(
                nCtx = n,
                nBatch = minOf(cur.nBatch, 256),
                nUbatch = minOf(cur.nUbatch, 256),
                typeK = if (cur.typeK == "f16") "q8_0" else cur.typeK,
                typeV = if (cur.typeV == "f16") "q8_0" else cur.typeV,
            )
            yield(cur)
        }
        // last resort: smallest plain configuration
        yield(cur.copy(nCtx = 2048, nBatch = 128, nUbatch = 128, typeK = "f16", typeV = "f16", flashAttn = "off", offloadKqv = false))
    }

    suspend fun unload() = withContext(dispatcher) {
        Native.unload()
        _state.value = EngineState()
    }

    fun cancel() = Native.cancel()

    suspend fun countTokens(text: String): Int = withContext(dispatcher) { Native.countTokens(text.toByteArray()) }

    suspend fun recentLog(lines: Int = 120): String = withContext(dispatcher) { String(Native.recentLog(lines), Charsets.UTF_8) }

    fun markBenchmarking(on: Boolean) {
        _state.update {
            when {
                on -> it.copy(status = EngineStatus.Benchmarking)
                it.status == EngineStatus.Benchmarking -> it.copy(status = if (it.modelId != null && it.runConfig != null) EngineStatus.Ready else EngineStatus.Idle)
                else -> it
            }
        }
    }

    // ---- generation ---------------------------------------------------------------------------------------------

    /**
     * Streams a reply to [messages]. The engine keeps the KV cache of the previous turn, so a growing conversation only
     * pays for its new tokens. If the conversation outgrows the context window, the oldest turns are dropped (and
     * reported through [GenEvent.Trimmed]) rather than failing.
     */
    fun generate(messages: List<ChatMsg>, params: GenParams, stops: List<String> = emptyList()): Flow<GenEvent> = callbackFlow {
        val finished = AtomicBoolean(false)
        launch(dispatcher) {
            _state.update { if (it.status == EngineStatus.Ready) it.copy(status = EngineStatus.Generating) else it }
            var msgs = messages
            var dropped = 0
            try {
                var attempts = 0
                while (true) {
                    val filter = StopFilter(stops)
                    var stoppedByFilter = false
                    val sink = object : TokenSink {
                        override fun onToken(bytes: ByteArray): Boolean {
                            val out = filter.push(String(bytes, Charsets.UTF_8))
                            if (out.isNotEmpty() && trySend(GenEvent.Token(out)).isFailure) return false
                            if (filter.stopped) {
                                stoppedByFilter = true
                                return false
                            }
                            return true
                        }
                    }
                    val res = dec<GenResult>(Native.generate(enc(msgs), enc(params), sink))
                    if (!res.ok && res.error == "context_overflow" && attempts < 12) {
                        val trimmed = dropOldestTurn(msgs)
                        if (trimmed == null) {
                            trySend(GenEvent.Error("This message is longer than the model's context window (${res.nCtx} tokens)."))
                            break
                        }
                        msgs = trimmed
                        dropped++
                        attempts++
                        continue
                    }
                    if (!res.ok) {
                        trySend(GenEvent.Error(res.error ?: "generation failed"))
                        break
                    }
                    if (!stoppedByFilter) filter.flush().let { if (it.isNotEmpty()) trySend(GenEvent.Token(it)) }
                    if (dropped > 0) trySend(GenEvent.Trimmed(dropped))
                    trySend(
                        GenEvent.Done(
                            GenStats(
                                promptTokens = res.promptTokens, cachedTokens = res.cachedTokens, prefillTokens = res.prefillTokens,
                                genTokens = res.genTokens, prefillMs = res.prefillMs, genMs = res.genMs,
                                stop = if (stoppedByFilter) "stop" else res.stop,
                            )
                        )
                    )
                    break
                }
            } catch (e: Throwable) {
                trySend(GenEvent.Error(e.message ?: e.toString()))
            } finally {
                finished.set(true)
                _state.update { if (it.status == EngineStatus.Generating) it.copy(status = EngineStatus.Ready) else it }
                close()
            }
        }
        awaitClose { if (!finished.get()) Native.cancel() }
    }

    /** Drops the oldest non-system turn (user message plus its answer). Null when only the latest message is left. */
    private fun dropOldestTurn(msgs: List<ChatMsg>): List<ChatMsg>? {
        val firstIdx = msgs.indexOfFirst { it.role != "system" }
        if (firstIdx < 0) return null
        val nonSystem = msgs.count { it.role != "system" }
        if (nonSystem <= 1) return null
        val out = msgs.toMutableList()
        out.removeAt(firstIdx)
        if (firstIdx < out.size && out[firstIdx].role == "assistant" && out.count { it.role != "system" } > 1) out.removeAt(firstIdx)
        return out
    }
}
