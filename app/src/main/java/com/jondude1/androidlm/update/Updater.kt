package com.jondude1.androidlm.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jondude1.androidlm.BuildConfig
import com.jondude1.androidlm.settings.AppSettings
import com.jondude1.androidlm.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private data class GhAsset(val name: String = "", val size: Long = 0, val browser_download_url: String = "")

@Serializable
private data class GhRelease(val tag_name: String = "", val name: String? = null, val body: String? = null, val assets: List<GhAsset> = emptyList())

data class UpdateInfo(val tag: String, val versionCode: Int, val title: String, val apkUrl: String, val sizeBytes: Long, val notes: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update straight from this project's public GitHub releases: no store, no manual install. The CI build publishes
 * a release per push; the app compares the release number with its own version code, downloads the APK and hands it to
 * the system installer (every build is signed with the same permanent key, so Android accepts it as an update).
 */
class Updater(private val context: Context, private val settings: AppSettings) {
    private val repo = "Jondude1/Android-LM"
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var dismissedTag: String? = null

    val installedVersion: String get() = BuildConfig.VERSION_NAME

    /** Quiet check on resume, at most every 30 minutes. */
    suspend fun maybeCheck() {
        if (System.currentTimeMillis() - settings.value.lastUpdateCheck < 30 * 60 * 1000L) return
        check(quiet = true)
    }

    suspend fun check(quiet: Boolean = false) {
        if (_state.value is UpdateState.Downloading) return
        if (!quiet) _state.value = UpdateState.Checking
        try {
            val info = withContext(Dispatchers.IO) { fetchLatest() }
            settings.update { copy(lastUpdateCheck = System.currentTimeMillis()) }
            _state.value = when {
                info == null || info.versionCode <= BuildConfig.VERSION_CODE -> if (quiet) UpdateState.Idle else UpdateState.UpToDate
                info.tag == dismissedTag && quiet -> UpdateState.Idle
                else -> UpdateState.Available(info)
            }
        } catch (e: Throwable) {
            _state.value = if (quiet) UpdateState.Idle else UpdateState.Failed(e.message ?: e.toString())
        }
    }

    private fun fetchLatest(): UpdateInfo? {
        val body = Http.getString("https://api.github.com/repos/$repo/releases/latest", mapOf("Accept" to "application/vnd.github+json"))
        val rel = com.jondude1.androidlm.util.AppJson.decodeFromString<GhRelease>(body)
        val apk = rel.assets.firstOrNull { it.name.endsWith(".apk", true) } ?: return null
        val code = Regex("""(\d+)$""").find(rel.tag_name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return UpdateInfo(rel.tag_name, code, rel.name ?: rel.tag_name, apk.browser_download_url, apk.size, rel.body.orEmpty())
    }

    fun dismiss() {
        (_state.value as? UpdateState.Available)?.let { dismissedTag = it.info.tag }
        _state.value = UpdateState.Idle
    }

    suspend fun downloadAndInstall() {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Ready -> { install(s.file); return }
            else -> return
        }
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val target = File(dir, "AndroidLM-${info.tag}.apk")
                val c = Http.open(info.apkUrl, timeoutMs = 30_000)
                try {
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                    c.inputStream.use { input ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(128 * 1024)
                            var done = 0L
                            var last = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.nanoTime()
                                if (now - last > 200_000_000L && total > 0) {
                                    last = now
                                    _state.value = UpdateState.Downloading(info, done.toFloat() / total)
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                target
            }
            _state.value = UpdateState.Ready(info, file)
            install(file)
        } catch (e: Throwable) {
            _state.value = UpdateState.Failed(e.message ?: e.toString())
        }
    }

    /** Hands the APK to the package installer, first sending the user to the "install unknown apps" switch if needed. */
    fun install(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
