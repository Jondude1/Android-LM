package com.jondude1.androidlm.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class CoreInfo(val index: Int, val maxKhz: Long, val part: Int, val cls: String)

/** What this phone is made of. Everything the planner and tuner decide is based on this, not on a device list. */
@Serializable
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val sdk: Int,
    val totalRam: Long,
    val cores: List<CoreInfo>,
    val features: List<String>,
) {
    val prime get() = cores.filter { it.cls == "prime" }.map { it.index }
    val mid get() = cores.filter { it.cls == "mid" }.map { it.index }
    val little get() = cores.filter { it.cls == "little" }.map { it.index }

    /** Cores worth running inference on: everything except the efficiency cluster. */
    val perf get() = (prime + mid).sorted().ifEmpty { cores.map { it.index } }

    /** Stable id used to key saved tuning results. */
    val fingerprint get() = "$manufacturer-$model-$soc-${totalRam shr 30}G".replace(' ', '_')

    val title get() = "$manufacturer $model".trim()

    private fun rank(c: CoreInfo) = (when (c.cls) { "prime" -> 2L; "mid" -> 1L; else -> 0L }) * 100_000_000L + c.maxKhz

    /** The [n] fastest cores, used for CPU pinning. */
    fun coresFor(n: Int): List<Int> =
        cores.sortedWith(compareByDescending<CoreInfo> { rank(it) }.thenByDescending { it.index }).take(n.coerceIn(1, cores.size.coerceAtLeast(1))).map { it.index }.sorted()

    /** Thread counts worth benchmarking: generation is memory-bound, so "all cores" is often not the fastest. */
    fun threadCandidates(): List<Int> {
        val total = cores.size.coerceAtLeast(1)
        val perfN = perf.size.coerceAtLeast(1)
        val primeN = prime.size.coerceAtLeast(1)
        return listOf(perfN, primeN.coerceAtLeast(2), perfN - 1, perfN + little.size / 2)
            .map { it.coerceIn(1, total) }.distinct().sortedDescending().take(4)
    }

    val hasI8mm get() = "i8mm" in features
    val hasDotprod get() = "asimddp" in features
    val hasSve get() = "sve" in features || "sve2" in features
    val hasSme get() = features.any { it.startsWith("sme") }
}

object DeviceProfiler {
    // Arm part numbers whose class is known; frequency decides for everything else.
    private val littleParts = setOf(0xd03, 0xd04, 0xd05, 0xd46, 0xd80)
    private val primeParts = setOf(0xd44, 0xd48, 0xd4e, 0xd82, 0xd85)

    fun read(context: Context): DeviceProfile {
        val (parts, features) = parseCpuInfo()
        val count = maxOf(Runtime.getRuntime().availableProcessors(), parts.size)
        val freqs = (0 until count).map { readFreq(it) }
        val maxF = freqs.maxOrNull() ?: 0L

        val cores = (0 until count).map { i ->
            val f = freqs[i]
            val part = parts.getOrElse(i) { 0 }
            val cls = when {
                part in littleParts -> "little"
                maxF > 0 && f > 0 && f < maxF * 0.55 -> "little"
                part in primeParts -> "prime"
                maxF > 0 && f >= maxF * 0.9 -> "prime"
                maxF > 0 && f > 0 -> "mid"
                else -> "mid"
            }
            CoreInfo(i, f, part, cls)
        }.let(::fixSingleClass)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val soc = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != "unknown" }.joinToString(" ")
            .ifBlank { Build.HARDWARE }

        return DeviceProfile(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            soc = soc,
            sdk = Build.VERSION.SDK_INT,
            totalRam = mi.totalMem,
            cores = cores,
            features = features,
        )
    }

    /** If every core ended up "prime" (homogeneous or unreadable frequencies) there is no little cluster to avoid. */
    private fun fixSingleClass(cores: List<CoreInfo>): List<CoreInfo> =
        if (cores.map { it.cls }.distinct().size == 1 && cores.firstOrNull()?.cls == "prime") cores.map { it.copy(cls = "mid") } else cores

    private fun readFreq(i: Int): Long {
        val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
        return runCatching { f.readText().trim().toLong() }.getOrDefault(0L)
    }

    private fun parseCpuInfo(): Pair<List<Int>, List<String>> {
        val parts = ArrayList<Int>()
        var features = emptyList<String>()
        runCatching {
            File("/proc/cpuinfo").forEachLine { line ->
                val idx = line.indexOf(':')
                if (idx < 0) return@forEachLine
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                when (key) {
                    "cpu part" -> parts += value.removePrefix("0x").toIntOrNull(16) ?: 0
                    "features" -> if (features.isEmpty()) features = value.split(' ').filter { it.isNotBlank() }
                }
            }
        }
        return parts to features
    }
}
