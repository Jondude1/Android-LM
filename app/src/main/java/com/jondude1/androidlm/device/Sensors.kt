package com.jondude1.androidlm.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Live readings: free memory, thermal state, battery temperature. */
class Sensors(private val context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _thermal = MutableStateFlow(pm.currentThermalStatus)
    /** PowerManager.THERMAL_STATUS_*: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown. */
    val thermal: StateFlow<Int> = _thermal.asStateFlow()

    init {
        pm.addThermalStatusListener(context.mainExecutor) { _thermal.value = it }
    }

    data class Mem(val total: Long, val available: Long, val swapTotal: Long, val swapFree: Long)

    fun memory(): Mem {
        var total = 0L; var avail = 0L; var swapT = 0L; var swapF = 0L
        runCatching {
            File("/proc/meminfo").forEachLine { l ->
                val kb = l.substringAfter(':').trim().substringBefore(' ').toLongOrNull()?.times(1024) ?: return@forEachLine
                when {
                    l.startsWith("MemTotal:") -> total = kb
                    l.startsWith("MemAvailable:") -> avail = kb
                    l.startsWith("SwapTotal:") -> swapT = kb
                    l.startsWith("SwapFree:") -> swapF = kb
                }
            }
        }
        return Mem(total, avail, swapT, swapF)
    }

    /** Resident memory of this process in bytes. */
    fun processRss(): Long = runCatching {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong()?.times(1024) ?: 0L
    }.getOrDefault(0L)

    fun batteryTempC(): Float {
        val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (t == Int.MIN_VALUE) Float.NaN else t / 10f
    }

    fun batteryPercent(): Int {
        val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level < 0) -1 else level * 100 / scale
    }

    fun isCharging(): Boolean {
        val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val s = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }

    companion object {
        fun thermalName(s: Int) = when (s) {
            0 -> "Cool"; 1 -> "Warm"; 2 -> "Moderate"; 3 -> "Severe"; 4 -> "Critical"; 5 -> "Emergency"; 6 -> "Shutdown"; else -> "Unknown"
        }
    }
}
