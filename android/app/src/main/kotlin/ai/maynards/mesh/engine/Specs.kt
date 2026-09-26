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
            val kernel = meminfo()
            val offer = Offer.bytes(ctx)   // what the owner of this phone has agreed to lend
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
                totalBytes = kernel?.first ?: mem.totalMem,
                // Never report less than either source says: Android's own figure is the pessimistic one.
                // A cap set by the phone's owner is applied here, so both the Kotlin planner and the Rust
                // host see the same, smaller, honest offer without either needing to know about the setting.
                freeBytes = maxOf(kernel?.second ?: 0L, mem.availMem).let { free ->
                    val total = kernel?.first ?: mem.totalMem
                    val reserve = (total * 15 / 100).coerceIn(800_000_000L, 2_500_000_000L)
                    if (offer > 0) minOf(free, offer + reserve) else free
                },
                heat = runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN).let { if (it.isNaN()) -1f else it },
                battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                charging = plugged,
            )
        }

        /**
         * What the kernel itself says, as MemTotal and MemAvailable in bytes.
         *
         * ActivityManager.availMem is deliberately conservative, and on ROMs with memory extension it
         * can report many gigabytes less than the kernel will actually hand out, which made the planner
         * refuse models that fit comfortably. /proc/meminfo is world readable on Android, and
         * MemAvailable is the kernel's own estimate of what can be allocated without swapping.
         */
        private fun meminfo(): Pair<Long, Long>? = runCatching {
            var total = 0L
            var avail = 0L
            File("/proc/meminfo").forEachLine { line ->
                val kb = line.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull()
                if (kb != null) when {
                    line.startsWith("MemTotal:") -> total = kb * 1024
                    line.startsWith("MemAvailable:") -> avail = kb * 1024
                }
            }
            if (total > 0 && avail > 0) total to avail else null
        }.getOrNull()

        private fun maxCpuGhz(): Double = (0 until 16).maxOfOrNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }
                .getOrDefault(0L)
        }?.let { it / 1_000_000.0 } ?: 0.0
    }
}

fun gb(bytes: Long) = "%.1f".format(bytes / 1e9)
