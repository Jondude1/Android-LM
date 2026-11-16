package com.jondude1.androidlm.engine

import android.content.Context
import java.io.File

/**
 * GPU drivers on phones occasionally take the whole process down (a native crash cannot be caught). Before any risky
 * native step we leave a marker file; if it is still there on the next start, that step crashed, and the app can
 * blacklist that backend for that model and fall back to the CPU instead of crash-looping.
 */
class CrashGuard(context: Context) {
    private val file = File(context.filesDir, "inflight.marker")

    fun begin(kind: String, modelId: String, deviceName: String) {
        runCatching { file.writeText("$kind|$modelId|$deviceName") }
    }

    fun end() {
        runCatching { file.delete() }
    }

    data class Crash(val kind: String, val modelId: String, val deviceName: String)

    /** Returns (and clears) the step that was in flight when the app last died, if any. */
    fun consumeLeftover(): Crash? {
        if (!file.exists()) return null
        val parts = runCatching { file.readText().split("|") }.getOrNull()
        file.delete()
        if (parts == null || parts.size < 3) return null
        return Crash(parts[0], parts[1], parts.drop(2).joinToString("|"))
    }
}
