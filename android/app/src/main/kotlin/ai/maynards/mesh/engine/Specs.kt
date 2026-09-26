package ai.maynards.mesh.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject
import java.io.File

/** What a device offers the mesh, re-read every couple of seconds. */
data class Specs(
    val id: String,
    val name: String,
    val kind: String,          // "phone" or "laptop"
    val chip: String,
    val cores: Int,
    val maxGhz: Double,
    val totalBytes: Long,
    val freeBytes: Long,
    val heat: Float,           // 0 = cool, 1 = about to throttle; -1 unknown
    val battery: Int,          // percent
    val charging: Boolean,
) {
    /** Memory kept back for Android and the app: 15% of the device, at least 0.8 GB, at most 2.5 GB. */
    val reserveBytes: Long get() = (totalBytes * 15 / 100).coerceIn(800_000_000L, 2_500_000_000L)

    /** Memory the model may use on this device right now. */
    val usableBytes: Long get() = (freeBytes - reserveBytes).coerceAtLeast(0)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind).put("chip", chip)
        .put("cores", cores).put("maxGhz", maxGhz)
        .put("totalBytes", totalBytes).put("freeBytes", freeBytes)
        .put("heat", heat.toDouble()).put("battery", battery).put("charging", charging)

    companion object {
        fun fromJson(j: JSONObject) = Specs(
            id = j.getString("id"), name = j.getString("name"), kind = j.optString("kind", "phone"),
            chip = j.optString("chip"), cores = j.optInt("cores"), maxGhz = j.optDouble("maxGhz", 0.0),
            totalBytes = j.getLong("totalBytes"), freeBytes = j.getLong("freeBytes"),
            heat = j.optDouble("heat", -1.0).toFloat(), battery = j.optInt("battery", -1),
            charging = j.optBoolean("charging"),
        )

        fun read(ctx: Context): Specs {
            val mem = ActivityManager.MemoryInfo().also {
                (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
            }
            val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            return Specs(
                id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "phone",
                name = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
                kind = "phone",
                chip = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE,
                cores = Runtime.getRuntime().availableProcessors(),
                maxGhz = maxCpuGhz(),
                totalBytes = mem.totalMem,
                freeBytes = mem.availMem,
                heat = runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN).let { if (it.isNaN()) -1f else it },
                battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                charging = plugged,
            )
        }

        private fun maxCpuGhz(): Double = (0 until 16).maxOfOrNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }
                .getOrDefault(0L)
        }?.let { it / 1_000_000.0 } ?: 0.0
    }
}

fun gb(bytes: Long) = "%.1f".format(bytes / 1e9)
