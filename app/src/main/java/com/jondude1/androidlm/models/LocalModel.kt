package com.jondude1.androidlm.models

import kotlinx.serialization.Serializable

/** A GGUF model on this phone (for split models, [path] is the first shard and [sizeBytes] covers all shards). */
@Serializable
data class LocalModel(
    val id: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val info: ModelInfo,
    val shards: Int = 1,
    val complete: Boolean = true,
) {
    val bitsPerWeight: Double get() = if (info.nParams > 0) sizeBytes * 8.0 / info.nParams else 0.0
}
