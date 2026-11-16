package com.jondude1.androidlm.models

import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** What we learn from a GGUF header, without loading a single weight. Drives fit analysis and tuning. */
@Serializable
data class ModelInfo(
    val arch: String = "",
    val name: String = "",
    val quant: String = "",
    val nParams: Long = 0,
    val nLayers: Int = 0,
    val nEmbd: Int = 0,
    val nHead: Int = 0,
    val nHeadKv: Int = 0,
    val headDimK: Int = 0,
    val headDimV: Int = 0,
    val ctxTrain: Int = 0,
    val nExpert: Int = 0,
    val nExpertUsed: Int = 0,
    val expertParams: Long = 0,
    val hasChatTemplate: Boolean = false,
    val splitCount: Int = 1,
) {
    val isMoe get() = nExpert > 1

    /** Share of the weights that are read for every generated token (1.0 for dense models). */
    val activeFraction: Double
        get() {
            if (nParams <= 0 || !isMoe) return 1.0
            val dense = (nParams - expertParams).coerceAtLeast(0)
            val active = dense + expertParams.toDouble() * nExpertUsed.coerceAtLeast(1) / nExpert
            return (active / nParams).coerceIn(0.01, 1.0)
        }

    /** KV cache bytes needed per token of context. Conservative: ignores sliding-window / SSM layers that need less. */
    fun kvBytesPerToken(kvType: String): Double {
        val kd = if (headDimK > 0) headDimK else if (nHead > 0) nEmbd / nHead else 128
        val vd = if (headDimV > 0) headDimV else kd
        return nLayers.toDouble() * nHeadKv.coerceAtLeast(1) * (kd + vd) * bytesPerElement(kvType)
    }

    companion object {
        fun bytesPerElement(t: String) = when (t) {
            "q8_0" -> 34.0 / 32
            "q5_0" -> 22.0 / 32
            "q4_0" -> 18.0 / 32
            else -> 2.0
        }
    }
}

object GgufReader {
    private class In(val s: InputStream) {
        private val b = ByteArray(8)
        private fun fill(n: Int) {
            var off = 0
            while (off < n) {
                val r = s.read(b, off, n - off)
                if (r < 0) throw EOFException("truncated GGUF")
                off += r
            }
        }
        fun u8(): Int { fill(1); return b[0].toInt() and 0xFF }
        fun u16(): Int { fill(2); return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) }
        fun u32(): Long {
            fill(4)
            return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
        }
        fun u64(): Long {
            fill(8)
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val k = s.skip(left)
                if (k <= 0) {
                    if (s.read() < 0) throw EOFException("truncated GGUF")
                    left--
                } else left -= k
            }
        }
        fun str(max: Long = 1L shl 24): String {
            val len = u64()
            if (len < 0 || len > max) throw IOException("implausible string length $len")
            val bytes = ByteArray(len.toInt())
            var off = 0
            while (off < bytes.size) {
                val r = s.read(bytes, off, bytes.size - off)
                if (r < 0) throw EOFException("truncated GGUF")
                off += r
            }
            return String(bytes, Charsets.UTF_8)
        }
        fun skipStr() = skip(u64())
    }

    private fun elemSize(t: Int) = when (t) {
        0, 1, 7 -> 1L
        2, 3 -> 2L
        4, 5, 6 -> 4L
        10, 11, 12 -> 8L
        else -> throw IOException("unknown GGUF value type $t")
    }

    /** Reads one value. Returns Long/Double/Boolean/String, a LongArray for small integer arrays we care about, or null if skipped. */
    private fun readValue(i: In, type: Int, wantString: Boolean, keepArray: Boolean): Any? = when (type) {
        0, 1 -> i.u8().toLong()
        2, 3 -> i.u16().toLong()
        4, 5 -> i.u32()
        6 -> java.lang.Float.intBitsToFloat(i.u32().toInt()).toDouble()
        7 -> i.u8() != 0
        8 -> if (wantString) i.str() else { i.skipStr(); null }
        10, 11 -> i.u64()
        12 -> java.lang.Double.longBitsToDouble(i.u64())
        9 -> {
            val et = i.u32().toInt()
            val n = i.u64()
            if (et == 8) {
                for (k in 0 until n) i.skipStr()
                null
            } else if (keepArray && n in 1..4096 && et in intArrayOf(0, 1, 2, 3, 4, 5, 10, 11)) {
                LongArray(n.toInt()) { (readValue(i, et, false, false) as Long) }
            } else {
                i.skip(n * elemSize(et))
                null
            }
        }
        else -> throw IOException("unknown GGUF value type $type")
    }

    private val stringKeys = setOf("general.architecture", "general.name", "general.basename", "general.size_label")

    private fun llamaFtypeName(t: Int) = when (t) {
        0 -> "F32"; 1 -> "F16"; 2 -> "Q4_0"; 3 -> "Q4_1"; 7 -> "Q8_0"; 8 -> "Q5_0"; 9 -> "Q5_1"
        10 -> "Q2_K"; 11 -> "Q3_K_S"; 12 -> "Q3_K_M"; 13 -> "Q3_K_L"; 14 -> "Q4_K_S"; 15 -> "Q4_K_M"
        16 -> "Q5_K_S"; 17 -> "Q5_K_M"; 18 -> "Q6_K"; 19 -> "IQ2_XXS"; 20 -> "IQ2_XS"; 21 -> "Q2_K_S"
        22 -> "IQ3_XS"; 23 -> "IQ3_XXS"; 24 -> "IQ1_S"; 25 -> "IQ4_NL"; 26 -> "IQ3_S"; 27 -> "IQ3_M"
        28 -> "IQ2_S"; 29 -> "IQ2_M"; 30 -> "IQ4_XS"; 31 -> "IQ1_M"; 32 -> "BF16"; 36 -> "TQ1_0"; 37 -> "TQ2_0"
        38 -> "MXFP4"
        else -> ""
    }

    private fun long(v: Any?): Long = when (v) {
        is Long -> v
        is Double -> v.toLong()
        is LongArray -> v.maxOrNull() ?: 0L
        else -> 0L
    }

    /** Parses the header of [file] (first shard for split models). Throws IOException on anything that is not GGUF. */
    fun read(file: File): ModelInfo {
        BufferedInputStream(FileInputStream(file), 1 shl 20).use { stream ->
            val i = In(stream)
            if (i.u32() != 0x46554747L) throw IOException("not a GGUF file")
            val version = i.u32()
            if (version < 2 || version > 3) throw IOException("unsupported GGUF version $version")
            val nTensors = i.u64()
            val nKv = i.u64()
            if (nTensors < 0 || nTensors > 5_000_000 || nKv < 0 || nKv > 1_000_000) throw IOException("implausible GGUF header")

            val kv = HashMap<String, Any>()
            for (k in 0 until nKv) {
                val key = i.str(1 shl 16)
                val type = i.u32().toInt()
                val keep = key.endsWith(".head_count") || key.endsWith(".head_count_kv")
                val v = readValue(i, type, key in stringKeys, keep)
                if (v != null) kv[key] = v
                if (key == "tokenizer.chat_template") kv["__has_template"] = true
            }
            // the chat template is a string we skipped above, so detect it by key presence
            var params = 0L
            var expertParams = 0L
            for (t in 0 until nTensors) {
                val name = i.str(1 shl 16)
                val nd = i.u32().toInt()
                var p = 1L
                for (d in 0 until nd) p *= i.u64().coerceAtLeast(1)
                i.u32() // ggml type
                i.u64() // offset
                params += p
                if (name.contains("_exps")) expertParams += p
            }

            val arch = kv["general.architecture"] as? String ?: ""
            fun a(suffix: String) = kv["$arch.$suffix"]
            val nHead = long(a("attention.head_count")).toInt()
            val nHeadKv = long(a("attention.head_count_kv")).toInt().let { if (it == 0) nHead else it }
            return ModelInfo(
                arch = arch,
                name = (kv["general.name"] as? String) ?: (kv["general.basename"] as? String) ?: "",
                quant = llamaFtypeName(long(kv["general.file_type"]).toInt()),
                nParams = params,
                nLayers = long(a("block_count")).toInt(),
                nEmbd = long(a("embedding_length")).toInt(),
                nHead = nHead,
                nHeadKv = nHeadKv,
                headDimK = long(a("attention.key_length")).toInt(),
                headDimV = long(a("attention.value_length")).toInt(),
                ctxTrain = long(a("context_length")).toInt(),
                nExpert = long(a("expert_count")).toInt(),
                nExpertUsed = long(a("expert_used_count")).toInt(),
                expertParams = expertParams,
                hasChatTemplate = kv["__has_template"] == true,
                splitCount = long(kv["split.count"]).toInt().coerceAtLeast(1),
            )
        }
    }
}
