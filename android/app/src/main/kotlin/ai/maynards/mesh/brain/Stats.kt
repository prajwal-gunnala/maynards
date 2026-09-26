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
class Stats(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("mesh-stats", Context.MODE_PRIVATE)
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<Record>> = _records

    fun add(plan: Plan, a: Answer, heat: Float) {
        if (a.tokens == 0) return
        val r = Record(System.currentTimeMillis(), plan.model.name, plan.slices.size,
            plan.slices.joinToString(" · ") { "${it.name} ${it.count}" }, a.tokPerSec, a.firstTokenMs, a.tokens, heat)
        _records.value = (_records.value + r).takeLast(100)
        prefs.edit().putString("records", JSONArray(_records.value.map { it.toJson() }).toString()).apply()
    }

    fun clear() {
        _records.value = emptyList()
        prefs.edit().remove("records").apply()
    }

    private fun load(): List<Record> = runCatching {
        val a = JSONArray(prefs.getString("records", "[]"))
        List(a.length()) { Record.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
