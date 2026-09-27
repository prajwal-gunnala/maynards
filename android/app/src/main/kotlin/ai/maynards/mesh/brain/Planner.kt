package ai.maynards.mesh.brain

/** A device the planner may use. The Host is the phone running the brain; it always runs llama-server. */
data class Device(
    val id: String,
    val name: String,
    val usableBytes: Long,     // free memory minus the device's reserve
    val isHost: Boolean = false,
    val rttMs: Double = -1.0,  // average round trip from the Host; -1 for the Host itself
    val heat: Float = 0f,      // thermal headroom: 1.0 means throttling
    val battery: Int = 100,
    val charging: Boolean = true,
)

enum class Verdict { DOABLE, TIGHT, NOT_POSSIBLE }

/** Layers [from, to) of the model on one device. */
data class Slice(val deviceId: String, val name: String, val from: Int, val to: Int, val bytes: Long) {
    val count: Int get() = to - from
}

data class Plan(
    val model: ModelInfo,
    val verdict: Verdict,
    val reason: String,
    val slices: List<Slice>,          // Host first, then helpers in --rpc order
    val needBytes: Long,
    val skipped: Map<String, String>, // device id -> why it was left out
) {
    val split: Boolean get() = slices.size > 1
    val helpers: List<Slice> get() = slices.drop(1)
}

/**
 * Decides where a model runs. Rules:
 *  - never split a model that fits on the Host alone;
 *  - skip devices that are too slow to reach or nearly flat and not charging (heat only warns: the phone throttles itself);
 *  - every device gets one unbroken run of layers; the Host keeps embeddings and output head;
 *  - Doable means at least 15% memory to spare, Tight means less.
 */
object Planner {
    const val HOST_RESERVE = 300_000_000L    // llama-server's working buffers
    const val HELPER_RESERVE = 150_000_000L  // rpc-server's working buffers
    const val MAX_RTT_MS = 60.0
    const val MIN_BATTERY = 20
    const val SPARE = 0.15

    /** KV cache stored at 8 bits (q8_0 is 34 bytes per 32 values). */
    fun kvBytes(m: ModelInfo, ctx: Int): Long = m.kvBytesPerToken * ctx * 17 / 32

    fun plan(m: ModelInfo, devices: List<Device>, ctx: Int = 4096, hostExtra: Long = 0): Plan {
        val host = devices.firstOrNull { it.isHost } ?: error("no host")
        val kv = kvBytes(m, ctx)
        val kvPerLayer = kv / maxOf(m.layers, 1)
        val need = m.weightBytes + kv + HOST_RESERVE + hostExtra

        val skipped = LinkedHashMap<String, String>()
        val biggest = (m.layerBytes.maxOrNull() ?: 0) + kvPerLayer
        val helpers = devices.filter { !it.isHost }.filter { d ->
            val why = when {
                d.rttMs > MAX_RTT_MS -> "link too slow (%.0f ms)".format(d.rttMs)
                !d.charging && d.battery < MIN_BATTERY -> "battery ${d.battery}%"
                d.usableBytes - HELPER_RESERVE < biggest -> "too little memory"
                else -> null
            }
            if (why != null) skipped[d.id] = why
            why == null
        }.sortedByDescending { it.usableBytes }                // fewest devices: biggest first

        // 1. Fits on the Host alone: never split.
        if (host.usableBytes >= need) {
            helpers.forEach { skipped[it.id] = "not needed" }
            return Plan(m, spare(host.usableBytes, need), "Runs on this phone alone",
                listOf(Slice(host.id, host.name, 0, m.layers, m.weightBytes)), need, skipped)
        }

        // 2. Split: the Host keeps the shared tensors and the first layers, helpers continue in order.
        val slices = ArrayList<Slice>()
        var next = 0
        var capacity = 0L
        val order = listOf(host) + helpers
        for (d in order) {
            if (next >= m.layers) { skipped[d.id] = "not needed"; continue }
            val fixed = if (d.isHost) m.otherBytes + HOST_RESERVE + hostExtra else HELPER_RESERVE
            val cap = d.usableBytes - fixed
            var used = 0L
            val from = next
            while (next < m.layers && used + m.layerBytes[next] + kvPerLayer <= cap) {
                used += m.layerBytes[next] + kvPerLayer
                next++
            }
            if (next > from || d.isHost) {
                slices += Slice(d.id, d.name, from, next, used + if (d.isHost) m.otherBytes else 0)
                capacity += maxOf(cap, 0) + fixed
            }
        }

        if (next < m.layers) {
            val missing = (next until m.layers).sumOf { m.layerBytes[it] + kvPerLayer }
            return Plan(m, Verdict.NOT_POSSIBLE, "Short by ${gb(missing)} GB", emptyList(), need, skipped)
        }
        val devicesUsed = slices.size
        val neededHere = need + HELPER_RESERVE * (devicesUsed - 1)
        return Plan(m, spare(capacity, neededHere), "Needs $devicesUsed devices", slices, need, skipped)
    }

    private fun spare(have: Long, need: Long) =
        if (have - need >= need * SPARE) Verdict.DOABLE else Verdict.TIGHT

    private fun gb(b: Long) = "%.1f".format(java.util.Locale.US, b / 1e9)
}
