package ai.maynards.mesh.ui

import ai.maynards.mesh.engine.Net
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What holds this phone back, and a button to the exact page that changes it. An app cannot flip vivo's
 * system switches itself, so each row shows the live state and opens the page where the owner can.
 */
data class Speed(
    val cpuMaxGhz: Double?, val cpuHwGhz: Double?,   // fastest cores: allowed now vs the chip's top speed
    val swapGb: Double?,                              // zram is always there; Extended RAM adds more
    val batteryFree: Boolean,                         // exempt from battery optimisation
    val usb: Boolean,                                 // USB tethering is up
    val thermal: Int,                                 // PowerManager.THERMAL_STATUS_*, 0 = none
)

private fun readSpeed(ctx: Context): Speed {
    fun khz(f: File) = runCatching { f.readText().trim().toLong() }.getOrNull()
    val policies = File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.startsWith("policy") }.orEmpty()
    // kHz; below 100 MHz is not a real CPU (the emulator reports 2)
    val top = policies.mapNotNull { p -> khz(File(p, "cpuinfo_max_freq"))?.takeIf { it > 100_000 }?.let { hw -> p to hw } }.maxByOrNull { it.second }
    val cap = top?.let { khz(File(it.first, "scaling_max_freq")) }
    val swap = runCatching {
        File("/proc/meminfo").readLines().first { it.startsWith("SwapTotal") }.split(Regex("\\s+"))[1].toLong() * 1024 / 1e9
    }.getOrNull()
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return Speed(
        cap?.let { it / 1e6 }, top?.second?.let { it / 1e6 }, swap,
        pm.isIgnoringBatteryOptimizations(ctx.packageName),
        Net.addresses().any { it.kind == "usb" },
        pm.currentThermalStatus,
    )
}

/** Open the first of these that exists on this phone. */
private fun open(ctx: Context, vararg intents: Intent) {
    for (i in intents) {
        if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

private fun settings(cls: String) = Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.$cls"))

/** Pull down the control centre, where Monster Mode lives. Hidden API: falls back to the battery page. */
@SuppressLint("WrongConstant")
private fun controlCentre(ctx: Context): Boolean = runCatching {
    val sb = ctx.getSystemService("statusbar")!!
    sb.javaClass.getMethod("expandSettingsPanel").invoke(sb)
}.isSuccess

@SuppressLint("BatteryLife")
@Composable
fun SpeedCard() {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf<Speed?>(null) }
    LaunchedEffect(Unit) { while (true) { s = withContext(Dispatchers.IO) { readSpeed(ctx) }; delay(2000) } }
    val now = s ?: return
    val full = now.cpuMaxGhz != null && now.cpuHwGhz != null && now.cpuMaxGhz >= now.cpuHwGhz * 0.99

    NBox {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("SPEED")
            Row1("Monster Mode",
                if (now.cpuMaxGhz == null) "–" else "CPU %.1f / %.1f GHz".format(now.cpuMaxGhz, now.cpuHwGhz), if (full) Term else Warn) {
                if (!controlCentre(ctx)) open(ctx, Intent("com.iqoo.powersaving.battery.settings.jump"), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            }
            Row1("Extended RAM", now.swapGb?.let { "swap %.0f GB".format(it) } ?: "–",
                if ((now.swapGb ?: 0.0) > 9) Warn else Term) {
                open(ctx, Intent("com.vivo.settings.INTERNAL_STORAGE_SETTINGS_THOUSAND"), Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            }
            Row1("Battery limits", if (now.batteryFree) "off" else "on", if (now.batteryFree) Term else Warn) {
                open(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                    Intent("com.iqoo.powersaving.battery.high.power.jump"), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            Row1("USB tethering", if (now.usb) "on" else "off", if (now.usb) Term else Warn) {
                open(ctx, settings("Settings\$VivoTetherSettingsActivity"), settings("Settings\$TetherSettingsActivity"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
            Row1("Heat", listOf("cool", "light", "moderate", "severe", "critical", "emergency", "shutdown").getOrElse(now.thermal) { "–" },
                if (now.thermal <= 1) Term else if (now.thermal == 2) Warn else Bad, button = null) {}
        }
    }
}

@Composable
private fun Row1(name: String, state: String, color: androidx.compose.ui.graphics.Color, button: String? = "Open", onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, color = Ink)
            Text(state, fontSize = 12.sp, color = color, fontFamily = FontFamily.Monospace)
        }
        if (button != null) {
            Spacer(Modifier.width(10.dp))
            NButton(button, Modifier.width(88.dp), onClick = onClick)
        }
    }
}
