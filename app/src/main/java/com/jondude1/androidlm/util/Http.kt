package com.jondude1.androidlm.util

import com.jondude1.androidlm.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP helper. Everything the app does is local; the network is only touched for user-initiated model
 * downloads and update checks, and "Offline mode" blocks both.
 */
object Http {
    @Volatile
    var offline: Boolean = false

    class HttpException(val code: Int, message: String) : IOException(message)

    private fun trustedForAuth(u: URL) = u.host == "huggingface.co" || u.host.endsWith(".huggingface.co") || u.host == "hf.co"

    /**
     * Opens [url], following redirects by hand: GitHub and Hugging Face both bounce downloads to a CDN on another
     * host, and credentials must not follow the redirect there.
     */
    fun open(
        url: String,
        headers: Map<String, String> = emptyMap(),
        rangeFrom: Long? = null,
        timeoutMs: Int = 20_000,
        maxRedirects: Int = 8,
    ): HttpURLConnection {
        if (offline) throw IOException("Offline mode is on - network access is disabled")
        var u = URL(url)
        var hops = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "AndroidLM/${BuildConfig.VERSION_NAME}")
            c.setRequestProperty("Accept", "*/*")
            for ((k, v) in headers) {
                if (k.equals("Authorization", true) && !trustedForAuth(u)) continue
                c.setRequestProperty(k, v)
            }
            if (rangeFrom != null && rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-")
            val code = c.responseCode
            if (code in 301..308 && code != 304) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without Location header")
                c.disconnect()
                u = URL(u, loc)
                if (++hops > maxRedirects) throw IOException("Too many redirects")
                continue
            }
            if (code !in 200..299) {
                val body = runCatching { c.errorStream?.readBytes()?.decodeToString()?.take(300) }.getOrNull().orEmpty()
                c.disconnect()
                throw HttpException(code, "HTTP $code for ${u.host}${u.path}: $body")
            }
            return c
        }
    }

    fun getString(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = open(url, headers)
        try {
            return c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
