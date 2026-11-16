package com.jondude1.androidlm.engine

/** Receives generated text as raw UTF-8 bytes. Return false to stop generation. */
interface TokenSink {
    fun onToken(bytes: ByteArray): Boolean
}

/** Model load progress in 0..1. Return false to abort the load. */
interface ProgressSink {
    fun onProgress(p: Float): Boolean
}

/**
 * JNI surface of lm_engine.cpp. All strings are UTF-8 byte arrays; all structured results are JSON.
 * Calls are serialised natively; only [cancel] may be called while another call is running.
 */
object Native {
    init {
        System.loadLibrary("androidlm")
    }

    external fun init(nativeLibDir: ByteArray): ByteArray
    external fun recentLog(maxLines: Int): ByteArray
    external fun cancel()
    external fun loadModel(path: ByteArray, cfg: ByteArray, progress: ProgressSink?): ByteArray
    external fun newContext(cfg: ByteArray): ByteArray
    external fun unload()
    external fun clearCache()
    external fun countTokens(text: ByteArray): Int
    external fun generate(messages: ByteArray, gen: ByteArray, sink: TokenSink): ByteArray
    external fun bench(pp: Int, tg: Int, reps: Int): ByteArray
}
