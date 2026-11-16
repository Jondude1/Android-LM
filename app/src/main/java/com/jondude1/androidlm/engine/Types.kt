package com.jondude1.androidlm.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- what the native layer reports --------------------------------------------------------------------------------

@Serializable
data class NativeDevice(
    val index: Int,
    val name: String,
    val description: String = "",
    val type: String = "cpu",
    val backend: String = "",
    val free: Long = 0,
    val total: Long = 0,
) {
    val isGpu get() = type == "gpu" || type == "igpu"
    val label get() = "$name - $description".trim(' ', '-')
}

@Serializable
data class InitResult(val devices: List<NativeDevice> = emptyList(), val system: String = "")

@Serializable
data class LoadResult(
    val ok: Boolean,
    val error: String? = null,
    @SerialName("load_ms") val loadMs: Double = 0.0,
    val desc: String = "",
    val size: Long = 0,
    val params: Long = 0,
    @SerialName("n_layer") val nLayer: Int = 0,
    @SerialName("n_ctx_train") val nCtxTrain: Int = 0,
)

@Serializable
data class CtxResult(
    val ok: Boolean,
    val error: String? = null,
    @SerialName("n_ctx") val nCtx: Int = 0,
    @SerialName("n_batch") val nBatch: Int = 0,
    val pinned: Boolean = false,
)

@Serializable
data class GenResult(
    val ok: Boolean,
    val error: String? = null,
    val stop: String = "",
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("cached_tokens") val cachedTokens: Int = 0,
    @SerialName("prefill_tokens") val prefillTokens: Int = 0,
    @SerialName("gen_tokens") val genTokens: Int = 0,
    @SerialName("prefill_ms") val prefillMs: Double = 0.0,
    @SerialName("gen_ms") val genMs: Double = 0.0,
    @SerialName("n_ctx") val nCtx: Int = 0,
)

@Serializable
data class BenchResult(
    val ok: Boolean,
    val error: String? = null,
    @SerialName("pp_tps") val ppTps: Double = 0.0,
    @SerialName("tg_tps") val tgTps: Double = 0.0,
    @SerialName("pp_tokens") val ppTokens: Int = 0,
    @SerialName("tg_tokens") val tgTokens: Int = 0,
)

// ---- what we tell the native layer ---------------------------------------------------------------------------------

@Serializable
data class LoadConfig(
    /** -1 = CPU only, otherwise the native device index (OpenCL / Vulkan GPU). */
    val device: Int = -1,
    @SerialName("n_gpu_layers") val nGpuLayers: Int = 999,
    /** auto | mmap | mlock | direct_io | none */
    @SerialName("load_mode") val loadMode: String = "auto",
    /** off | auto | on : read MoE expert tensors on demand instead of up front (needs mmap). */
    val lazy: String = "auto",
)

@Serializable
data class ContextConfig(
    @SerialName("n_ctx") val nCtx: Int = 4096,
    @SerialName("n_batch") val nBatch: Int = 512,
    @SerialName("n_ubatch") val nUbatch: Int = 512,
    @SerialName("n_threads") val nThreads: Int = 4,
    @SerialName("n_threads_batch") val nThreadsBatch: Int = 4,
    /** auto | on | off */
    @SerialName("flash_attn") val flashAttn: String = "auto",
    /** f16 | q8_0 | q5_0 | q4_0 */
    @SerialName("type_k") val typeK: String = "f16",
    @SerialName("type_v") val typeV: String = "f16",
    @SerialName("offload_kqv") val offloadKqv: Boolean = true,
    @SerialName("op_offload") val opOffload: Boolean = true,
    @SerialName("cpu_cores") val cpuCores: List<Int> = emptyList(),
    @SerialName("strict_cpu") val strictCpu: Boolean = true,
    val poll: Int = 50,
)

/** Everything needed to bring a model up: where it runs and how. This is what the tuner optimises. */
@Serializable
data class RunConfig(
    val load: LoadConfig = LoadConfig(),
    val ctx: ContextConfig = ContextConfig(),
    val deviceName: String = "CPU",
)

@Serializable
data class GenParams(
    @SerialName("max_tokens") val maxTokens: Int = 2048,
    val temperature: Float = 0.7f,
    @SerialName("top_k") val topK: Int = 40,
    @SerialName("top_p") val topP: Float = 0.95f,
    @SerialName("min_p") val minP: Float = 0.05f,
    @SerialName("repeat_penalty") val repeatPenalty: Float = 1.0f,
    val seed: Long = -1,
    val thinking: Boolean = true,
)

@Serializable
data class ChatMsg(val role: String, val content: String)

@Serializable
data class GenStats(
    val promptTokens: Int = 0,
    val cachedTokens: Int = 0,
    val prefillTokens: Int = 0,
    val genTokens: Int = 0,
    val prefillMs: Double = 0.0,
    val genMs: Double = 0.0,
    val stop: String = "",
) {
    val prefillTps get() = if (prefillMs > 0 && prefillTokens > 0) prefillTokens / (prefillMs / 1000.0) else 0.0
    val genTps get() = if (genMs > 0 && genTokens > 0) genTokens / (genMs / 1000.0) else 0.0
}

sealed interface GenEvent {
    data class Token(val text: String) : GenEvent
    data class Trimmed(val droppedMessages: Int) : GenEvent
    data class Done(val stats: GenStats) : GenEvent
    data class Error(val message: String) : GenEvent
}

enum class EngineStatus { Idle, Loading, Ready, Generating, Benchmarking, Error }

data class EngineState(
    val status: EngineStatus = EngineStatus.Idle,
    val modelId: String? = null,
    val modelName: String? = null,
    val backend: String = "",
    val progress: Float = 0f,
    val nCtx: Int = 0,
    val runConfig: RunConfig? = null,
    val error: String? = null,
    val loadSeconds: Double = 0.0,
)
