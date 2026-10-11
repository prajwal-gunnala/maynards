package ai.maynards.mesh.brain

import android.content.Context

/**
 * Mesh Compiler (MVP): measures a few safe settings for the running plan and keeps one only when it is
 * measurably better. A setting is never applied on a guess: no measurement, no change.
 */
object Compiler {
    /** One timed answer to the fixed benchmark prompt. */
    data class Sample(val decodeTps: Double, val promptTps: Double, val ok: Boolean = true)

    data class Row(val threads: Int, val medianDecode: Double, val medianPrompt: Double, val runs: Int, val failed: Int)
    data class Decision(val threads: Int, val changed: Boolean, val why: String, val table: List<Row>)

    const val MIN_GAIN = 0.05      // 5 %: below this, run-to-run noise on a phone can explain it
    const val REPEATS = 3

    /** Thread counts worth trying on a phone with [cores] cores, current one first. */
    fun candidates(cores: Int, current: Int): List<Int> =
        (listOf(current) + listOf(cores - 2, cores - 4, cores / 2, 4).filter { it >= 2 }).distinct().take(4)

    fun median(xs: List<Double>): Double = xs.sorted().let { s ->
        if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * @param correct answers right on [AnswerCheck] per setting, when checked: a faster setting with fewer
     * correct answers than the baseline is never adopted.
     */
    fun decide(baseline: Int, results: Map<Int, List<Sample>>, correct: Map<Int, Int> = emptyMap()): Decision {
        val table = results.map { (t, xs) ->
            val good = xs.filter { it.ok && it.decodeTps.isFinite() && it.decodeTps > 0 }
            Row(t, median(good.map { it.decodeTps }), median(good.map { it.promptTps }), xs.size, xs.size - good.size)
        }.sortedByDescending { it.medianDecode }
        val base = table.firstOrNull { it.threads == baseline }
            ?: return Decision(baseline, false, "baseline not measured · kept $baseline threads", table)
        if (base.failed > 0 || base.medianDecode <= 0) return Decision(baseline, false, "baseline failed · kept $baseline threads", table)
        val baseCorrect = correct[baseline]
        val worse = table.filter { r -> baseCorrect != null && (correct[r.threads] ?: -1) < baseCorrect }.map { it.threads }.toSet()
        val fastest = table.firstOrNull { it.failed == 0 && it.runs >= REPEATS && it.medianDecode > 0 }
        val best = table.firstOrNull { it.failed == 0 && it.runs >= REPEATS && it.medianDecode > 0 && it.threads !in worse } ?: base
        if (fastest != null && fastest.threads in worse && fastest.threads != baseline &&
            fastest.medianDecode / base.medianDecode - 1 >= MIN_GAIN && best == base)
            return Decision(baseline, false, "kept $baseline threads · ${fastest.threads} was faster but got fewer correct " +
                "(${correct[fastest.threads]} vs $baseCorrect of ${AnswerCheck.items.size})", table)
        val gain = best.medianDecode / base.medianDecode - 1
        return if (best.threads != baseline && gain >= MIN_GAIN)
            Decision(best.threads, true, "%d threads: %.1f vs %.1f tok/s (+%.0f%%)".format(java.util.Locale.US, best.threads, best.medianDecode, base.medianDecode, gain * 100), table)
        else Decision(baseline, false, "kept $baseline threads · no setting was ≥ 5% faster", table)
    }

    // ---- learning from use: every real answer is a sample for the setting that produced it ----

    const val MIN_TOKENS = 20      // shorter answers time mostly overhead
    const val KEEP = 8             // newest samples kept per setting

    /**
     * The setting to use for the next run while learning: a candidate with fewer than [REPEATS] samples first
     * (current one before others, then fewest samples), otherwise the measured winner over [baseline].
     */
    fun nextThreads(samples: Map<Int, List<Double>>, candidates: List<Int>, current: Int, baseline: Int): Int {
        val under = candidates.filter { (samples[it]?.size ?: 0) < REPEATS }
        if (under.isNotEmpty()) return if (current in under) current else under.minBy { samples[it]?.size ?: 0 }
        return learn(samples, baseline).threads
    }

    /** What the answers so far say, by the same rule as Tune (3 samples, ≥5 %, else the baseline). */
    fun learn(samples: Map<Int, List<Double>>, baseline: Int): Decision =
        decide(baseline, samples.mapValues { (_, xs) -> xs.map { Sample(it, 0.0) } })

    /** Profiles are only reused for the same phone model, model file, context and split. */
    fun key(phone: String, plan: Plan, ctx: Int): String =
        listOf(phone, plan.model.file, plan.model.fileBytes, ctx, plan.slices.joinToString(",") { "${it.from}-${it.to}" }).joinToString("|")

    class Profiles(ctx: Context) {
        private val prefs = ctx.getSharedPreferences("mesh-compiler", Context.MODE_PRIVATE)
        fun threads(key: String): Int? = prefs.getInt(key, 0).takeIf { it > 0 }
        fun save(key: String, threads: Int) = prefs.edit().putInt(key, threads).apply()
        fun clear(key: String) = prefs.edit().remove(key).remove("samples:$key").apply()

        var learning: Boolean
            get() = prefs.getBoolean("learning", false)
            set(v) = prefs.edit().putBoolean("learning", v).apply()

        fun samples(key: String): Map<Int, List<Double>> = runCatching {
            val j = org.json.JSONObject(prefs.getString("samples:$key", "{}")!!)
            j.keys().asSequence().associate { k -> k.toInt() to j.getJSONArray(k).let { a -> List(a.length()) { a.getDouble(it) } } }
        }.getOrDefault(emptyMap())

        fun addSample(key: String, threads: Int, decodeTps: Double) {
            if (!decodeTps.isFinite() || decodeTps <= 0) return
            val all = samples(key).toMutableMap()
            all[threads] = ((all[threads] ?: emptyList()) + decodeTps).takeLast(KEEP)
            val j = org.json.JSONObject()
            all.forEach { (t, xs) -> j.put("$t", org.json.JSONArray(xs)) }
            prefs.edit().putString("samples:$key", j.toString()).apply()
        }
    }
}
