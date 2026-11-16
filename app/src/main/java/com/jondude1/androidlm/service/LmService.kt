package com.jondude1.androidlm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jondude1.androidlm.MainActivity
import com.jondude1.androidlm.R
import com.jondude1.androidlm.container

/** Keeps the process (and the local API server) alive in the background while the server is on or a model downloads. */
class LmService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Android LM", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Android LM is running")
            .setContentText(container.server.state.value.url.ifEmpty { "Local model service" })
            .setContentIntent(open).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "androidlm:svc").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { wake?.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        private const val CH = "androidlm"
        fun start(c: Context) = c.startForegroundService(Intent(c, LmService::class.java))
        fun stop(c: Context) { c.stopService(Intent(c, LmService::class.java)) }
    }
}
