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

/** A download the app is confident will fit and run at a usable speed on this phone. */
data class Recommendation(val repo: String, val choice: HfChoice, val report: FitReport)

/** Slowest generation speed (tokens/s) we are willing to recommend. */
const val MIN_USABLE_TPS = 3.0

/**
 * Before a file is downloaded its internals are unknown, so shape it from the repo name and file size: typical
 * layer / KV-head counts for its size class, and mixture-of-experts if the name says so (e.g. "30B-A3B").
 */
fun guessInfo(repo: String, bytes: Long): ModelInfo {
    val params = (bytes * 8.0 / 4.8).toLong().coerceAtLeast(1)
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
    val name = choice.label.lowercase()
    if ("mmproj" in name || "bf16" in name || "f32" in name) return null
    val rep = c.planner.assess(choice.totalBytes, guessInfo(repo, choice.totalBytes))
    return if ((rep.fit == Fit.Comfortable || rep.fit == Fit.Tight) && rep.estTps >= MIN_USABLE_TPS) rep else null
}

private val searches = listOf(
    "Qwen3 GGUF", "gemma GGUF", "Llama 3 GGUF", "gpt-oss GGUF", "Phi-4 GGUF", "Mistral GGUF", "SmolLM3 GGUF", "granite GGUF",
)

/**
 * Looks up popular GGUF repos, and for each keeps the best (largest = most capable) file that really works on this phone.
 * Results are ordered biggest-first. Nothing that fails the fit-and-speed check is included.
 */
suspend fun recommendForThisPhone(c: AppContainer, token: String): List<Recommendation> = coroutineScope {
    val repos = searches.map { q -> async(Dispatchers.IO) { runCatching { c.hf.search(q, token).take(5) }.getOrDefault(emptyList()) } }
        .awaitAll().flatten().distinctBy { it.id }
    val gate = Semaphore(4)
    repos.map { r ->
        async(Dispatchers.IO) {
            gate.withPermit {
                val choices = runCatching { c.hf.choices(r.id, token) }.getOrNull() ?: return@withPermit null
                // prefer 4-bit K-quants when several sizes work: best quality per byte
                choices.mapNotNull { ch -> worksOnThisPhone(c, r.id, ch)?.let { Recommendation(r.id, ch, it) } }
                    .maxWithOrNull(compareBy({ it.choice.totalBytes }, { "q4_k_m" in it.choice.label.lowercase() }))
            }
        }
    }.awaitAll().filterNotNull().sortedByDescending { it.choice.totalBytes }.take(15)
}
