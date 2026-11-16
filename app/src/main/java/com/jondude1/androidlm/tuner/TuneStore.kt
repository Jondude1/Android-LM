package com.jondude1.androidlm.tuner

import android.content.Context
import com.jondude1.androidlm.engine.RunConfig
import com.jondude1.androidlm.util.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Trial(
    val deviceName: String,
    val threads: Int,
    val ppTps: Double = 0.0,
    val tgTps: Double = 0.0,
    val thermal: Int = 0,
    val note: String = "",
    val failed: Boolean = false,
) {
    /** Seconds a typical chat turn would take: 300 prompt tokens, 150 generated. Lower is better. */
    val turnSeconds get() = if (failed || ppTps <= 0 || tgTps <= 0) Double.MAX_VALUE else 300.0 / ppTps + 150.0 / tgTps
}

@Serializable
data class TuneResult(
    val modelId: String,
    val modelBytes: Long,
    val best: RunConfig,
    val trials: List<Trial>,
    val timestamp: Long,
    /** Effective memory bandwidth observed while generating (GB/s); lets us predict speed for other models. */
    val effectiveGBps: Double = 0.0,
)

@Serializable
private data class TuneFile(
    val results: Map<String, TuneResult> = emptyMap(),
    /** "modelId|deviceName" pairs that crashed or failed: never tried again automatically. */
    val bad: List<String> = emptyList(),
    val effectiveGBps: Double = 0.0,
)

/** Remembers, per phone and model, what the tuner found - and what crashed. */
class TuneStore(context: Context, private val fingerprint: String) {
    private val file = File(context.filesDir, "tune.json")
    private var data = runCatching { AppJson.decodeFromString<TuneFile>(file.readText()) }.getOrDefault(TuneFile())

    private val _version = MutableStateFlow(0)
    /** Bumps whenever stored results change, so the UI can refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun key(modelId: String) = "$fingerprint|$modelId"

    @Synchronized
    private fun save() {
        runCatching { file.writeText(AppJson.encodeToString(data)) }
        _version.value++
    }

    @Synchronized fun get(modelId: String): TuneResult? = data.results[key(modelId)]

    @Synchronized
    fun put(r: TuneResult) {
        val bw = if (r.effectiveGBps > 0) r.effectiveGBps else data.effectiveGBps
        data = data.copy(results = data.results + (key(r.modelId) to r), effectiveGBps = bw)
        save()
    }

    @Synchronized fun isBad(modelId: String, deviceName: String) = "$modelId|$deviceName" in data.bad

    @Synchronized
    fun markBad(modelId: String, deviceName: String) {
        if (isBad(modelId, deviceName)) return
        data = data.copy(bad = data.bad + "$modelId|$deviceName")
        save()
    }

    @Synchronized
    fun clearBad(modelId: String) {
        data = data.copy(bad = data.bad.filterNot { it.startsWith("$modelId|") })
        save()
    }

    @Synchronized fun badFor(modelId: String): List<String> = data.bad.filter { it.startsWith("$modelId|") }.map { it.substringAfter('|') }

    /** Best measurement of this phone's effective memory bandwidth so far (0 = unknown). */
    @Synchronized fun effectiveGBps() = data.effectiveGBps

    @Synchronized
    fun forget(modelId: String) {
        data = data.copy(results = data.results - key(modelId))
        save()
    }
}
