package com.jondude1.androidlm.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jondude1.androidlm.AppContainer
import com.jondude1.androidlm.device.Sensors
import com.jondude1.androidlm.engine.NativeDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DeviceScreen(c: AppContainer) {
    val thermal by c.sensors.thermal.collectAsState()
    val tuning by c.tuner.progress.collectAsState()
    var devices by remember { mutableStateOf<List<NativeDevice>>(emptyList()) }
    var system by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }
    var mem by remember { mutableStateOf(c.sensors.memory()) }
    var rss by remember { mutableStateOf(0L) }
    var battery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val r = c.engine.ensureInit()
        devices = r.devices; system = r.system
        while (true) {
            mem = c.sensors.memory(); rss = c.sensors.processRss()
            battery = "${c.sensors.batteryPercent()}% - ${"%.1f".format(c.sensors.batteryTempC())} C" + if (c.sensors.isCharging()) " - charging" else ""
            delay(2000)
        }
    }
    val p = c.profile

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        c.lastCrash?.let { SectionCard("Heads up") { Text(it, style = MaterialTheme.typography.bodyMedium) } }

        SectionCard(p.title.ifBlank { "This phone" }) {
            KeyValue("Chip", p.soc.ifBlank { "unknown" })
            KeyValue("Memory", fmtBytes(p.totalRam) + "  (free now ${fmtBytes(mem.available)})")
            KeyValue("App is using", fmtBytes(rss))
            KeyValue("CPU cores", "${p.prime.size} prime + ${p.mid.size} mid + ${p.little.size} efficiency")
            KeyValue("Used for AI", "${p.perf.size} performance cores")
            KeyValue("Arm features", listOfNotNull("dotprod".takeIf { p.hasDotprod }, "i8mm".takeIf { p.hasI8mm }, "SVE".takeIf { p.hasSve }, "SME".takeIf { p.hasSme }).joinToString(", ").ifEmpty { "baseline" })
            KeyValue("Temperature", Sensors.thermalName(thermal) + " - battery $battery")
            KeyValue("Effective memory speed", "%.0f GB/s".format(c.planner.effectiveGBps()) + if (c.tuneStore.effectiveGBps() > 0) " (measured)" else " (estimate)")
        }

        SectionCard("Compute backends") {
            if (devices.isEmpty()) Text("Detecting...", style = MaterialTheme.typography.bodyMedium)
            devices.forEach { d ->
                KeyValue(d.backend.ifBlank { d.type }, d.label + if (d.total > 0) "  ${fmtBytes(d.total)}" else "")
            }
            Text(
                "The best backend and thread count for each model is found by the Optimize button in the Models tab. GPU backends that crash are switched off automatically.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        tuning?.let { t ->
            SectionCard("Optimizing") {
                Text(if (t.error != null) "Stopped: ${t.error}" else t.label, style = MaterialTheme.typography.bodyMedium)
                if (!t.finished) LinearProgressIndicator(progress = { if (t.total > 0) t.step.toFloat() / t.total else 0f }, modifier = Modifier.fillMaxWidth())
                t.trials.forEach { tr ->
                    KeyValue("${tr.deviceName} x${tr.threads}", if (tr.failed) "failed: ${tr.note}" else "${fmtTps(tr.tgTps)} out, ${fmtTps(tr.ppTps)} in")
                }
                Row {
                    if (!t.finished) OutlinedButton({ c.tuner.cancel() }) { Text("Cancel") } else OutlinedButton({ c.tuner.dismiss() }) { Text("Dismiss") }
                }
            }
        }

        SectionCard("What this phone can run") {
            Text("Estimated generation speed with a 4K context. Real numbers replace these once you Optimize a model.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val table = remember { c.planner.capabilityTable() }
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                table.forEach { (quant, rows) ->
                    Text(quant, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    rows.forEach { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(r.label, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall)
                            Text("%.1f GB".format(r.sizeGb), Modifier.width(60.dp), style = MaterialTheme.typography.bodySmall)
                            Pill(r.fit.label, fitColor(r.fit))
                            Text(fmtTps(r.estTps), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        SectionCard("Backend log") {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            OutlinedButton({ scope.launch { log = c.engine.recentLog(120) } }) { Text("Show latest") }
            if (log.isNotBlank()) Text(log, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
    }
}
