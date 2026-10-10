package ai.maynards.mesh.brain

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One answer, with the conditions it ran under. */
data class Record(
    val at: Long,
    val model: String,
    val devices: Int,
    val layout: String,        // e.g. "This phone 25 · iQOO 23"
    val tokPerSec: Double,
    val firstTokenMs: Long,
    val tokens: Int,
    val heat: Float,
) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("model", model).put("devices", devices)
        .put("layout", layout).put("tps", tokPerSec).put("ttft", firstTokenMs).put("tokens", tokens).put("heat", heat.toDouble())

    companion object {
        fun fromJson(j: JSONObject) = Record(j.getLong("at"), j.getString("model"), j.getInt("devices"),
            j.optString("layout"), j.getDouble("tps"), j.getLong("ttft"), j.getInt("tokens"), j.optDouble("heat", -1.0).toFloat())
    }
}

/** Every answer's numbers, kept on the phone (last 100). */
data class MeterState(
    val residencySeconds: Long = 0,
    val gbHeld: Double = 0.0,
    val tokensServed: Long = 0,
    val earningsUsd: Double = 0.0,
    val cloudBaselineUsd: Double = 0.0,
    val savingsPct: Double = 92.8,
)

/** Every answer's numbers, kept on the phone (last 100). */
class Stats(ctx: Context) {
    companion object {
        const val PHONE_RAM_USD_PER_GB_MONTH = 0.168
        const val CLOUD_GPU_USD_PER_GB_MONTH = 2.34
        const val SECONDS_PER_MONTH = 720.0 * 3600.0
        const val TOKEN_USD_PER_TOKEN = 0.10 / 1_000_000.0
        const val CLOUD_TOKEN_USD_PER_TOKEN = 2.00 / 1_000_000.0
    }

    private val prefs = ctx.getSharedPreferences("mesh-stats", Context.MODE_PRIVATE)
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<Record>> = _records

    private val _meter = MutableStateFlow(loadMeter())
    val meter: StateFlow<MeterState> = _meter

    fun add(plan: Plan, a: Answer, heat: Float) {
        if (a.tokens == 0) return
        val r = Record(System.currentTimeMillis(), plan.model.name, plan.slices.size,
            plan.slices.joinToString(" · ") { "${it.name} ${it.count}" }, a.tokPerSec, a.firstTokenMs, a.tokens, heat)
        _records.value = (_records.value + r).takeLast(100)
        prefs.edit().putString("records", JSONArray(_records.value.map { it.toJson() }).toString()).apply()

        // Track slice memory and served tokens
        val hostSlice = plan.slices.firstOrNull { it.deviceId == "this" || it.name.contains("phone", ignoreCase = true) }
        val gb = hostSlice?.let { it.bytes.toDouble() / 1e9 } ?: (plan.needBytes.toDouble() / 1e9 / plan.slices.size.coerceAtLeast(1))
        recordTokens(a.tokens, gb)
    }

    fun recordResidency(gb: Double, seconds: Long) {
        val cur = _meter.value
        val newSec = cur.residencySeconds + seconds
        val resEarn = (gb * newSec) * (PHONE_RAM_USD_PER_GB_MONTH / SECONDS_PER_MONTH)
        val tokEarn = (cur.tokensServed.toDouble()) * TOKEN_USD_PER_TOKEN
        val cloudCost = (gb * newSec) * (CLOUD_GPU_USD_PER_GB_MONTH / SECONDS_PER_MONTH) + (cur.tokensServed.toDouble()) * CLOUD_TOKEN_USD_PER_TOKEN
        val savings = if (cloudCost > 0.0) ((cloudCost - (resEarn + tokEarn)) / cloudCost) * 100.0 else 92.8

        val next = cur.copy(
            residencySeconds = newSec,
            gbHeld = gb,
            earningsUsd = resEarn + tokEarn,
            cloudBaselineUsd = cloudCost,
            savingsPct = savings
        )
        _meter.value = next
        saveMeter(next)
    }

    fun recordTokens(tokens: Int, gb: Double = _meter.value.gbHeld) {
        if (tokens <= 0) return
        val cur = _meter.value
        val newTokens = cur.tokensServed + tokens
        val resEarn = (gb * cur.residencySeconds) * (PHONE_RAM_USD_PER_GB_MONTH / SECONDS_PER_MONTH)
        val tokEarn = (newTokens.toDouble()) * TOKEN_USD_PER_TOKEN
        val cloudCost = (gb * cur.residencySeconds) * (CLOUD_GPU_USD_PER_GB_MONTH / SECONDS_PER_MONTH) + (newTokens.toDouble()) * CLOUD_TOKEN_USD_PER_TOKEN
        val savings = if (cloudCost > 0.0) ((cloudCost - (resEarn + tokEarn)) / cloudCost) * 100.0 else 92.8

        val next = cur.copy(
            gbHeld = gb,
            tokensServed = newTokens,
            earningsUsd = resEarn + tokEarn,
            cloudBaselineUsd = cloudCost,
            savingsPct = savings
        )
        _meter.value = next
        saveMeter(next)
    }

    fun clear() {
        _records.value = emptyList()
        prefs.edit().remove("records").apply()
    }

    private fun load(): List<Record> = runCatching {
        val a = JSONArray(prefs.getString("records", "[]"))
        List(a.length()) { Record.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun loadMeter(): MeterState = runCatching {
        val sec = prefs.getLong("meter_sec", 0L)
        val gb = prefs.getString("meter_gb", "0.0")?.toDoubleOrNull() ?: 0.0
        val tok = prefs.getLong("meter_tokens", 0L)
        val earn = prefs.getString("meter_earn", "0.0")?.toDoubleOrNull() ?: 0.0
        val cloud = prefs.getString("meter_cloud", "0.0")?.toDoubleOrNull() ?: 0.0
        val sav = prefs.getString("meter_sav", "92.8")?.toDoubleOrNull() ?: 92.8
        MeterState(sec, gb, tok, earn, cloud, sav)
    }.getOrDefault(MeterState())

    private fun saveMeter(m: MeterState) {
        prefs.edit()
            .putLong("meter_sec", m.residencySeconds)
            .putString("meter_gb", m.gbHeld.toString())
            .putLong("meter_tokens", m.tokensServed)
            .putString("meter_earn", m.earningsUsd.toString())
            .putString("meter_cloud", m.cloudBaselineUsd.toString())
            .putString("meter_sav", m.savingsPct.toString())
            .apply()
    }
}
