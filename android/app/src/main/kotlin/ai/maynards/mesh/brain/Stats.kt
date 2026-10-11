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
    val promptTokPerSec: Double = 0.0,
    val link: String = "unknown",
    val kvBytes: Long = 0,
    val freeBytes: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("model", model).put("devices", devices)
        .put("layout", layout).put("tps", tokPerSec).put("ttft", firstTokenMs).put("tokens", tokens).put("heat", heat.toDouble())
        .put("prompt_tps", promptTokPerSec).put("link", link).put("kv_bytes", kvBytes).put("free_bytes", freeBytes)

    companion object {
        fun fromJson(j: JSONObject) = Record(j.getLong("at"), j.getString("model"), j.getInt("devices"),
            j.optString("layout"), j.getDouble("tps"), j.getLong("ttft"), j.getInt("tokens"), j.optDouble("heat", -1.0).toFloat(),
            j.optDouble("prompt_tps"), j.optString("link", "unknown"), j.optLong("kv_bytes"), j.optLong("free_bytes"))
    }
}

/** Every answer's numbers, kept on the phone (last 100). */
class Stats(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("mesh-stats", Context.MODE_PRIVATE)
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<Record>> = _records

    fun add(plan: Plan, a: Answer, heat: Float, peers: Collection<ai.maynards.mesh.mesh.Peer> = emptyList(), hostFreeBytes: Long = 0) {
        if (a.tokens == 0) return
        val peerById = peers.associateBy { it.id }
        val route = plan.helpers.joinToString(" · ") { slice ->
            val peer = peerById[slice.deviceId]
            "${peer?.linkKind ?: "local"}${peer?.rttMs?.takeIf { it >= 0 }?.let { " %.0fms".format(it) } ?: ""}"
        }.ifBlank { "host only" }
        val r = Record(System.currentTimeMillis(), plan.model.name, plan.slices.size,
            plan.slices.joinToString(" · ") { "${it.name} ${it.count}" }, a.tokPerSec, a.firstTokenMs, a.tokens, heat,
            a.promptPerSec, route, Planner.kvBytes(plan.model, 4096), hostFreeBytes + peers.sumOf { it.specs.usableBytes })
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
