package com.jondude1.androidlm.tuner

import com.jondude1.androidlm.device.DeviceProfile
import com.jondude1.androidlm.device.Sensors
import com.jondude1.androidlm.engine.ContextConfig
import com.jondude1.androidlm.engine.LoadConfig
import com.jondude1.androidlm.engine.NativeDevice
import com.jondude1.androidlm.engine.RunConfig
import com.jondude1.androidlm.models.LocalModel
import com.jondude1.androidlm.models.ModelInfo
import com.jondude1.androidlm.settings.AppSettings

enum class Fit(val label: String) {
    Comfortable("Runs great"),
    Tight("Fits, but tight"),
    Streaming("Streams from storage"),
    TooBig("Too big"),
}

data class FitReport(
    val fit: Fit,
    val needBytes: Long,
    val ctx: Int,
    val kvType: String,
    /** Estimated generation speed in tokens/s (0 = unknown). Replaced by measurements once the model is tuned. */
    val estTps: Double,
    val note: String,
)

data class CapRow(val label: String, val sizeGb: Double, val fit: Fit, val estTps: Double)

/**
 * Decides what a phone can run and how. No device database: it reasons from this phone's RAM, core layout and
 * whatever the tuner has measured, so it holds for phones that do not exist yet.
 */
class Planner(
    private val profile: DeviceProfile,
    private val sensors: Sensors,
    private val tuneStore: TuneStore,
    private val settings: AppSettings,
) {
    private val overhead = 700L shl 20 // compute buffers, runtime, tokenizer

    /** Effective memory bandwidth (GB/s) available to inference: measured if we have it, otherwise a class estimate. */
    fun effectiveGBps(): Double {
        val m = tuneStore.effectiveGBps()
        if (m > 0) return m
        val gb = profile.totalRam shr 30
        return when {
            gb >= 15 -> 26.0
            gb >= 11 -> 21.0
            gb >= 7 -> 15.0
            else -> 9.0
        }
    }

    /**
     * Assesses a model of [sizeBytes]. Android reclaims file-backed memory cheaply, so a model larger than free RAM
     * still runs through mmap, paging weights in from flash; the estimate reflects how badly that hurts. Mixture-of-experts
     * models suffer far less, because each token only touches a small share of the weights.
     */
    fun assess(sizeBytes: Long, info: ModelInfo, wantCtx: Int? = null, forceKv: String? = null): FitReport {
        val total = profile.totalRam.toDouble()
        val train = if (info.ctxTrain > 0) info.ctxTrain else 4096
        val cap = settings.value.maxContext.let { if (it <= 0) 32768 else it }
        var ctx = minOf(wantCtx ?: cap, train).coerceAtLeast(512)

        var kv = forceKv ?: "f16"
        if (forceKv == null && info.kvBytesPerToken("f16") * ctx > 0.10 * total) kv = "q8_0"
        fun need(c: Int) = sizeBytes + (info.kvBytesPerToken(kv) * c).toLong() + overhead
        while (need(ctx) > 0.72 * total && ctx > 2048) ctx = (ctx / 2).coerceAtLeast(2048)
        val need = need(ctx)

        val frac = info.activeFraction
        val activeGb = sizeBytes * frac / 1e9
        val bw = effectiveGBps()
        val ramTps = if (activeGb > 0) bw / activeGb else 0.0

        val fit = when {
            need <= 0.55 * total -> Fit.Comfortable
            need <= 0.78 * total -> Fit.Tight
            sizeBytes <= 4.0 * total && (frac <= 0.35 || sizeBytes <= 1.8 * total) -> Fit.Streaming
            else -> Fit.TooBig
        }
        val flashGBps = 1.4
        val tps = when (fit) {
            Fit.Comfortable, Fit.Tight -> ramTps
            Fit.Streaming ->
                if (frac <= 0.35) minOf(ramTps, flashGBps / (activeGb * 0.35).coerceAtLeast(0.05))
                else minOf(ramTps, flashGBps / ((sizeBytes - 0.6 * total).coerceAtLeast(1e8) / 1e9))
            Fit.TooBig -> 0.0
        }
        val note = when (fit) {
            Fit.Comfortable -> "Fits in RAM with room to spare."
            Fit.Tight -> "Fits, but other apps may get closed. Prefer a short context."
            Fit.Streaming ->
                if (info.isMoe) "Bigger than RAM, but only ~${(frac * 100).toInt()}% of the weights are used per token, so it still moves."
                else "Bigger than RAM: weights page in from storage on every token. Expect it to be slow."
            Fit.TooBig -> "Does not fit this phone. Try a smaller or lower-bit version."
        }
        return FitReport(fit, need, ctx, kv, tps, note)
    }

    fun assess(model: LocalModel) = assess(model.sizeBytes, model.info)

    /** A starting configuration for [model] on [device] (null = CPU). */
    fun defaultConfig(model: LocalModel, device: NativeDevice? = null, threads: Int? = null): RunConfig {
        val openCl = device?.backend?.contains("OpenCL", true) == true
        // The OpenCL backend has no usable flash attention, and a quantised V cache requires it: keep the cache f16 there.
        val rep = assess(model.sizeBytes, model.info, forceKv = if (openCl) "f16" else null)
        val t = (threads ?: profile.perf.size).coerceAtLeast(1)
        val quantKv = rep.kvType != "f16"
        val ub = if (device != null || profile.totalRam >= (11L shl 30)) 512 else 256
        return RunConfig(
            load = LoadConfig(
                device = device?.index ?: -1,
                nGpuLayers = if (device != null) 999 else 0,
                loadMode = "auto",
                lazy = if (model.info.isMoe) "auto" else "off",
            ),
            ctx = ContextConfig(
                nCtx = rep.ctx,
                nBatch = 512,
                nUbatch = ub,
                nThreads = t,
                nThreadsBatch = t,
                flashAttn = when { openCl -> "off"; quantKv -> "on"; else -> "auto" },
                typeK = rep.kvType,
                typeV = rep.kvType,
                cpuCores = profile.coresFor(t),
                strictCpu = true,
            ),
            deviceName = device?.label ?: "CPU",
        )
    }

    /** The configuration to load with: what the tuner found for this model if available, else a sensible default. */
    fun planFor(model: LocalModel, nativeDevices: List<NativeDevice>): RunConfig {
        val tuned = tuneStore.get(model.id)
        if (tuned != null) {
            val dev = if (tuned.best.load.device >= 0) nativeDevices.firstOrNull { it.label == tuned.best.deviceName } else null
            if (tuned.best.load.device < 0 || dev != null) {
                return defaultConfig(model, dev, tuned.best.ctx.nThreads)
            }
        }
        return defaultConfig(model)
    }

    /** "What is the biggest thing this phone can run?" as a grid, for the Device screen. */
    fun capabilityTable(): List<Pair<String, List<CapRow>>> {
        data class Shape(val label: String, val paramsB: Double, val layers: Int, val moeActive: Double? = null)
        val shapes = listOf(
            Shape("4B", 4.0, 36), Shape("8B", 8.0, 36), Shape("14B", 14.0, 40), Shape("32B", 32.0, 64), Shape("70B", 70.0, 80),
            Shape("30B-A3B MoE", 30.0, 48, 3.3 / 30.0), Shape("120B-A5B MoE", 120.0, 36, 5.1 / 120.0),
        )
        val quants = listOf("Q4_K_M" to 4.85, "Q3_K_M" to 3.9, "Q2_K" to 2.95)
        return quants.map { (q, bpw) ->
            q to shapes.map { s ->
                val params = (s.paramsB * 1e9).toLong()
                val size = (params * bpw / 8).toLong()
                val info = ModelInfo(
                    nParams = params, nLayers = s.layers, nHeadKv = 8, headDimK = 128, headDimV = 128, ctxTrain = 32768,
                    nExpert = if (s.moeActive != null) 128 else 0, nExpertUsed = if (s.moeActive != null) 8 else 0,
                    expertParams = if (s.moeActive != null) (params * 0.93).toLong() else 0,
                )
                val r = assess(size, info, wantCtx = 4096)
                CapRow(s.label, size / 1e9, r.fit, r.estTps)
            }
        }
    }
}
