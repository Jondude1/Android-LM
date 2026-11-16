package com.jondude1.androidlm.tuner

import com.jondude1.androidlm.device.DeviceProfile
import com.jondude1.androidlm.device.Sensors
import com.jondude1.androidlm.engine.ContextConfig
import com.jondude1.androidlm.engine.LlmEngine
import com.jondude1.androidlm.engine.LoadConfig
import com.jondude1.androidlm.engine.NativeDevice
import com.jondude1.androidlm.models.LocalModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Benchmarks a model on this phone's own hardware and keeps the winner.
 *
 * Candidates: the CPU at several thread counts (decode is memory-bound, so "all cores" is often slower than
 * "just the fast cores") and every GPU device the backends exposed (OpenCL / Vulkan). Each is scored by how long a typical
 * chat turn would take. GPU drivers that crash the process are caught by the crash guard and never tried again.
 */
class Tuner(
    private val engine: LlmEngine,
    private val profile: DeviceProfile,
    private val planner: Planner,
    private val store: TuneStore,
    private val sensors: Sensors,
) {
    data class Progress(
        val step: Int,
        val total: Int,
        val label: String,
        val trials: List<Trial> = emptyList(),
        val finished: Boolean = false,
        val error: String? = null,
    )

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        engine.cancel()
    }

    fun dismiss() {
        if (_progress.value?.finished == true) _progress.value = null
    }

    private fun benchCtx(threads: Int, gpu: Boolean, openCl: Boolean) = ContextConfig(
        nCtx = 768, nBatch = 512, nUbatch = if (gpu) 512 else 256,
        nThreads = threads, nThreadsBatch = threads,
        flashAttn = if (openCl) "off" else "auto",
        cpuCores = profile.coresFor(threads), strictCpu = true,
    )

    /** Waits (up to a minute) for a throttled phone to cool, so one hot trial does not unfairly lose. */
    private suspend fun cooldown(): Int {
        var waited = 0
        while (sensors.thermal.value >= 3 && waited < 60_000 && !cancelled) {
            delay(5_000)
            waited += 5_000
        }
        return sensors.thermal.value
    }

    suspend fun tune(model: LocalModel): TuneResult? {
        cancelled = false
        val devices: List<NativeDevice> = engine.ensureInit().devices
        val gpus = devices.filter { it.isGpu && !store.isBad(model.id, it.label) }
        val threadCands = profile.threadCandidates()
        val total = threadCands.size + gpus.size
        val trials = ArrayList<Trial>()
        var step = 0

        fun report(label: String, finished: Boolean = false, error: String? = null) {
            _progress.value = Progress(step, total, label, trials.toList(), finished, error)
        }

        engine.markBenchmarking(true)
        report("Loading ${model.name} on the CPU...")
        var pp = 96
        var tg = 16
        try {
            // ---- CPU: several thread counts on one loaded model ----
            engine.crashGuard.begin("tune", model.id, "CPU")
            val lr = engine.loadRaw(model.path, LoadConfig(device = -1, nGpuLayers = 0, lazy = if (model.info.isMoe) "auto" else "off")) {
                if (!cancelled) report("Loading ${model.name} on the CPU... ${(it * 100).toInt()}%")
            }
            engine.crashGuard.end()
            if (!lr.ok) {
                report("", true, lr.error ?: "Model failed to load")
                return null
            }
            for (t in threadCands) {
                if (cancelled) break
                val thermal = cooldown()
                report("CPU, $t threads...")
                val cr = engine.newContext(benchCtx(t, gpu = false, openCl = false))
                if (!cr.ok) {
                    trials += Trial("CPU", t, failed = true, note = cr.error ?: "context failed")
                } else {
                    val br = engine.benchRaw(pp, tg)
                    trials += if (br.ok) Trial("CPU", t, br.ppTps, br.tgTps, thermal)
                    else Trial("CPU", t, failed = true, note = br.error ?: "bench failed")
                    if (br.ok && br.tgTps < 2.0) { pp = 32; tg = 6 } // very slow model: keep the remaining trials short
                }
                step++
                report("CPU, $t threads done")
            }

            // ---- GPUs: one reload each ----
            for (gpu in gpus) {
                if (cancelled) break
                val thermal = cooldown()
                report("Loading on ${gpu.label}...")
                val openCl = gpu.backend.contains("OpenCL", true)
                val threads = minOf(4, profile.perf.size).coerceAtLeast(1)
                engine.crashGuard.begin("tune", model.id, gpu.label)
                try {
                    val g = engine.loadRaw(model.path, LoadConfig(device = gpu.index, nGpuLayers = 999, lazy = if (model.info.isMoe) "auto" else "off")) {
                        if (!cancelled) report("Loading on ${gpu.label}... ${(it * 100).toInt()}%")
                    }
                    if (!g.ok) {
                        trials += Trial(gpu.label, threads, failed = true, note = g.error ?: "load failed")
                        if (!cancelled) store.markBad(model.id, gpu.label)
                    } else {
                        val cr = engine.newContext(benchCtx(threads, gpu = true, openCl = openCl))
                        if (!cr.ok) {
                            trials += Trial(gpu.label, threads, failed = true, note = cr.error ?: "context failed")
                            if (!cancelled) store.markBad(model.id, gpu.label)
                        } else {
                            report("Benchmarking ${gpu.label}...")
                            val br = engine.benchRaw(pp, tg)
                            trials += if (br.ok) Trial(gpu.label, threads, br.ppTps, br.tgTps, thermal)
                            else Trial(gpu.label, threads, failed = true, note = br.error ?: "bench failed")
                            if (!br.ok && !cancelled) store.markBad(model.id, gpu.label)
                        }
                    }
                } finally {
                    engine.crashGuard.end()
                }
                step++
                report("${gpu.label} done")
            }

            if (cancelled) {
                report("Cancelled", true)
                return null
            }
            val best = trials.filter { !it.failed }.minByOrNull { it.turnSeconds }
            if (best == null) {
                report("", true, "No configuration produced a result")
                return null
            }
            val bestDev = devices.firstOrNull { it.label == best.deviceName && it.isGpu }
            val cfg = planner.defaultConfig(model, bestDev, best.threads)
            val activeGb = model.sizeBytes * model.info.activeFraction / 1e9
            val result = TuneResult(
                modelId = model.id,
                modelBytes = model.sizeBytes,
                best = cfg,
                trials = trials.toList(),
                timestamp = System.currentTimeMillis(),
                effectiveGBps = best.tgTps * activeGb,
            )
            store.put(result)
            step = total
            report("Best: ${best.deviceName}, ${best.threads} threads - ${"%.1f".format(best.tgTps)} tok/s", finished = true)
            return result
        } catch (e: Throwable) {
            report("", true, e.message ?: e.toString())
            return null
        } finally {
            engine.crashGuard.end()
            engine.unload()
        }
    }
}
