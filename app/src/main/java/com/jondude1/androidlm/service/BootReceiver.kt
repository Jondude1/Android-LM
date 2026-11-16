package com.jondude1.androidlm.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jondude1.androidlm.container

/** Brings the local server back after a reboot when "start server automatically" is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val c = context.applicationContext.container
        if (c.settings.value.serverAutostart && !c.server.state.value.running) {
            runCatching { LmService.start(context) }
            c.server.start()
        }
    }
}
