package com.jondude1.androidlm.ui

import com.jondude1.androidlm.AppContainer
import com.jondude1.androidlm.models.HfChoice
import com.jondude1.androidlm.models.ModelInfo
import com.jondude1.androidlm.tuner.Fit
import com.jondude1.androidlm.tuner.FitReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A download the app is confident will fit and run at a usable speed on this phone. [paramsB] is the estimated model size in billions. */
data class Recommendation(val repo: String, val choice: HfChoice, val report: FitReport, val paramsB: Double)

/** Slowest generation speed (tokens/s) we are willing to recommend. */
const val MIN_USABLE_TPS = 3.0

/**
 * Before a file is downloaded its internals are unknown, so shape it from the repo name and file size: typical
 * layer / KV-head counts for its size class, and mixture-of-experts if the name says so (e.g. "30B-A3B").
 */
/** Bits per weight for a quant named in a file name, or null if it is not a quantised file we trust (F16, unknown...). */
fun quantBpw(label: String): Double? {
    val n = label.lowercase()
    return when {
        Regex("""(^|[-_.])(f16|fp16|bf16|f32)([-_.]|$)""").containsMatchIn(n) -> null
        "mmproj" in n -> null
        "iq1" in n -> 1.8
        "iq2" in n || "q2_k" in n -> if ("iq2" in n) 2.4 else 2.95
        "iq3" in n -> 3.3
        "q3_k" in n -> 3.9
        "iq4" in n -> 4.5
        "q4_k" in n || "q4_0" in n || "q4_1" in n || "mxfp4" in n -> 4.85
        "q5_" in n -> 5.7
        "q6_k" in n -> 6.6
        "q8_0" in n -> 8.5
        else -> null
    }
}

/** Higher = better balance of quality and size for a phone. */
private fun quantPreference(label: String): Int {
    val n = label.lowercase()
    return when {
        "q4_k_m" in n -> 100
        "q4_k_s" in n || "iq4_xs" in n || "iq4_nl" in n -> 95
        "q4_0" in n || "mxfp4" in n || "q4_1" in n -> 90
        "q5_k_m" in n || "q5_k_s" in n -> 80
        "q3_k_m" in n || "q3_k_l" in n || "iq3" in n -> 70
        "q6_k" in n -> 60
        "q3_k_s" in n || "iq2" in n || "q2_k" in n -> 50
        "q8_0" in n -> 40
        else -> 0
    }
}

fun guessInfo(repo: String, bytes: Long, bpw: Double = 4.8): ModelInfo {
    val params = (bytes * 8.0 / bpw).toLong().coerceAtLeast(1)
    val b = params / 1e9
    val layers = when { b < 10 -> 32; b < 20 -> 40; b < 40 -> 64; else -> 80 }
    val moe = Regex("""(?i)\d+B[-_]?A\d+(\.\d+)?B|moe|mixtral""").containsMatchIn(repo)
    return ModelInfo(
        nParams = params, nLayers = layers, nHeadKv = 8, headDimK = 128, headDimV = 128, ctxTrain = 32768,
        nExpert = if (moe) 128 else 0, nExpertUsed = if (moe) 8 else 0, expertParams = if (moe) (params * 0.93).toLong() else 0,
    )
}

/** Fits in RAM with headroom and generates at a usable speed. Streaming-from-flash and too-big are never recommended. */
fun worksOnThisPhone(c: AppContainer, repo: String, choice: HfChoice): FitReport? {
    val bpw = quantBpw(choice.label) ?: return null
    val rep = c.planner.assess(choice.totalBytes, guessInfo(repo, choice.totalBytes, bpw))
    return if ((rep.fit == Fit.Comfortable || rep.fit == Fit.Tight) && rep.estTps >= MIN_USABLE_TPS) rep else null
}

private val searches = listOf(
    "Qwen3 GGUF", "gemma GGUF", "Llama 3 GGUF", "gpt-oss GGUF", "Phi-4 GGUF", "Mistral GGUF", "SmolLM3 GGUF", "granite GGUF",
)

/**
 * Looks up popular GGUF repos, and for each keeps the best (largest = most capable) file that really works on this phone.
 * Results are ordered by estimated model size (a bigger model is generally more capable). Nothing that fails the fit-and-speed check is included.
 */
suspend fun recommendForThisPhone(c: AppContainer, token: String): List<Recommendation> = coroutineScope {
    val repos = searches.map { q -> async(Dispatchers.IO) { runCatching { c.hf.search(q, token).take(5) }.getOrDefault(emptyList()) } }
        .awaitAll().flatten().distinctBy { it.id }
    val gate = Semaphore(4)
    repos.map { r ->
        async(Dispatchers.IO) {
            gate.withPermit {
                val choices = runCatching { c.hf.choices(r.id, token) }.getOrNull() ?: return@withPermit null
                // Per repo: the best quant that works (Q4_K_M first), not simply the largest file.
                choices.mapNotNull { ch ->
                    val bpw = quantBpw(ch.label) ?: return@mapNotNull null
                    worksOnThisPhone(c, r.id, ch)?.let { Recommendation(r.id, ch, it, ch.totalBytes * 8.0 / bpw / 1e9) }
                }.maxWithOrNull(compareBy({ quantPreference(it.choice.label) }, { it.choice.totalBytes }))
            }
        }
    }.awaitAll().filterNotNull().sortedByDescending { it.paramsB }.take(15)
}
