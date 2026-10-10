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
 *  - a split uses the fewest devices, then spreads the layers so every device fills the same share of its
 *    free memory: each keeps the same headroom, and none is filled to the brim while another sits nearly empty;
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

        // 2. Split. First the fewest devices that can hold it: fill the Host, then the biggest helpers in turn.
        val cost = { i: Int -> m.layerBytes[i] + kvPerLayer }
        val chosen = ArrayList<Pair<Device, Long>>()     // device, room for layers
        var next = 0
        for (d in listOf(host) + helpers) {
            if (next >= m.layers) { skipped[d.id] = "not needed"; continue }
            val cap = d.usableBytes - fixed(d, m, hostExtra)
            var used = 0L
            val from = next
            while (next < m.layers && used + cost(next) <= cap) { used += cost(next); next++ }
            if (next > from || d.isHost) chosen += d to maxOf(cap, 0)
        }
        if (next < m.layers) {
            val missing = (next until m.layers).sumOf { cost(it) }
            return Plan(m, Verdict.NOT_POSSIBLE, "Short by ${gb(missing)} GB", emptyList(), need, skipped)
        }

        // 3. Then share the layers among them in proportion to that room, in the same order.
        val counts = balance(m.layers, cost, chosen.map { it.second })
        val slices = ArrayList<Slice>()
        var from = 0
        chosen.forEachIndexed { i, (d, _) ->
            val to = from + counts[i]
            if (to > from || d.isHost) {
                val bytes = (from until to).sumOf { cost(it) } + if (d.isHost) m.otherBytes else 0
                slices += Slice(d.id, d.name, from, to, bytes)
            } else skipped[d.id] = "not needed"
            from = to
        }
        val capacity = chosen.filter { (d, _) -> slices.any { it.deviceId == d.id } }
            .sumOf { (d, room) -> room + fixed(d, m, hostExtra) }
        val devicesUsed = slices.size
        val neededHere = need + HELPER_RESERVE * (devicesUsed - 1)
        return Plan(m, spare(capacity, neededHere), "Needs $devicesUsed devices", slices, need, skipped)
    }

    private fun fixed(d: Device, m: ModelInfo, hostExtra: Long) =
        if (d.isHost) m.otherBytes + HOST_RESERVE + hostExtra else HELPER_RESERVE

    /**
     * Layer counts, in order, so each device's share of the layers' bytes matches its share of the room:
     * all fill to the same fraction f = layers / room. A boundary falls on the layer that brings the running
     * total closest to the running target, never past a device's room. If rounding leaves the last device
     * short of room, the plain fill-in-order counts are used instead (they always fit).
     */
    internal fun balance(layers: Int, cost: (Int) -> Long, room: List<Long>): IntArray {
        val counts = IntArray(room.size)
        val total = (0 until layers).sumOf { cost(it) }
        val f = total.toDouble() / maxOf(room.sum(), 1L)
        var next = 0
        var sum = 0L
        var target = 0.0
        for (i in room.indices) {
            target += f * room[i]
            val from = next
            var used = 0L
            val last = i == room.lastIndex
            while (next < layers) {
                val c = cost(next)
                if (used + c > room[i]) break
                if (!last && sum + c / 2.0 > target) break
                used += c; sum += c; next++
            }
            counts[i] = next - from
        }
        if (next == layers) return counts
        // fall back: fill each device in order (the greedy pass already proved this fits)
        next = 0
        for (i in room.indices) {
            var used = 0L
            val from = next
            while (next < layers && used + cost(next) <= room[i]) { used += cost(next); next++ }
            counts[i] = next - from
        }
        return counts
    }

    private fun spare(have: Long, need: Long) =
        if (have - need >= need * SPARE) Verdict.DOABLE else Verdict.TIGHT

    private fun gb(b: Long) = "%.1f".format(java.util.Locale.US, b / 1e9)
}
