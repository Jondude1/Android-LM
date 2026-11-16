package com.jondude1.androidlm.util

/**
 * Streaming stop-sequence filter. Text that could still turn out to be the start of a stop string is held back,
 * so a stop sequence is never partially shown.
 */
class StopFilter(private val stops: List<String>) {
    private val buf = StringBuilder()
    var stopped = false
        private set

    /** Feeds [s] and returns the text that is safe to emit now. */
    fun push(s: String): String {
        if (stops.isEmpty()) return s
        buf.append(s)
        var cut = -1
        for (st in stops) {
            if (st.isEmpty()) continue
            val i = buf.indexOf(st)
            if (i >= 0 && (cut < 0 || i < cut)) cut = i
        }
        if (cut >= 0) {
            val out = buf.substring(0, cut)
            buf.clear()
            stopped = true
            return out
        }
        var hold = 0
        for (st in stops) {
            val max = minOf(st.length - 1, buf.length)
            for (k in max downTo 1) {
                if (buf.endsWith(st.substring(0, k))) {
                    hold = maxOf(hold, k)
                    break
                }
            }
        }
        val emitLen = buf.length - hold
        val out = buf.substring(0, emitLen)
        buf.delete(0, emitLen)
        return out
    }

    fun flush(): String = buf.toString().also { buf.clear() }
}

/** Splits a token stream into "reasoning" (inside <think>...</think>) and answer text, tolerating tags split across chunks. */
class ThinkSplitter(private val open: String = "<think>", private val close: String = "</think>") {
    data class Seg(val reasoning: Boolean, val text: String)

    private var inThink = false
    private val buf = StringBuilder()

    fun push(s: String): List<Seg> {
        buf.append(s)
        val out = ArrayList<Seg>()
        while (true) {
            val tag = if (inThink) close else open
            val idx = buf.indexOf(tag)
            if (idx >= 0) {
                if (idx > 0) out += Seg(inThink, buf.substring(0, idx))
                buf.delete(0, idx + tag.length)
                inThink = !inThink
                continue
            }
            var hold = 0
            for (k in minOf(tag.length - 1, buf.length) downTo 1) {
                if (buf.endsWith(tag.substring(0, k))) {
                    hold = k
                    break
                }
            }
            val emitLen = buf.length - hold
            if (emitLen > 0) {
                out += Seg(inThink, buf.substring(0, emitLen))
                buf.delete(0, emitLen)
            }
            break
        }
        return out
    }

    fun flush(): List<Seg> {
        val r = if (buf.isNotEmpty()) listOf(Seg(inThink, buf.toString())) else emptyList()
        buf.clear()
        return r
    }

    companion object {
        /** Splits a finished message into (reasoning, answer). */
        fun splitAll(text: String): Pair<String, String> {
            if (!text.contains("<think>") && !text.contains("</think>")) return "" to text
            val sp = ThinkSplitter()
            val reasoning = StringBuilder()
            val answer = StringBuilder()
            for (seg in sp.push(text) + sp.flush()) (if (seg.reasoning) reasoning else answer).append(seg.text)
            return reasoning.toString().trim() to answer.toString().trimStart()
        }
    }
}
