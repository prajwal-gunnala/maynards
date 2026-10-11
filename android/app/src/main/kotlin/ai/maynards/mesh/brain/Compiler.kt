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

    fun decide(baseline: Int, results: Map<Int, List<Sample>>): Decision {
        val table = results.map { (t, xs) ->
            val good = xs.filter { it.ok && it.decodeTps.isFinite() && it.decodeTps > 0 }
            Row(t, median(good.map { it.decodeTps }), median(good.map { it.promptTps }), xs.size, xs.size - good.size)
        }.sortedByDescending { it.medianDecode }
        val base = table.firstOrNull { it.threads == baseline }
            ?: return Decision(baseline, false, "baseline not measured · kept $baseline threads", table)
        if (base.failed > 0 || base.medianDecode <= 0) return Decision(baseline, false, "baseline failed · kept $baseline threads", table)
        val best = table.firstOrNull { it.failed == 0 && it.runs >= REPEATS && it.medianDecode > 0 } ?: base
        val gain = best.medianDecode / base.medianDecode - 1
        return if (best.threads != baseline && gain >= MIN_GAIN)
            Decision(best.threads, true, "%d threads: %.1f vs %.1f tok/s (+%.0f%%)".format(java.util.Locale.US, best.threads, best.medianDecode, base.medianDecode, gain * 100), table)
        else Decision(baseline, false, "kept $baseline threads · no setting was ≥ 5% faster", table)
    }

    /** Profiles are only reused for the same phone model, model file, context and split. */
    fun key(phone: String, plan: Plan, ctx: Int): String =
        listOf(phone, plan.model.file, plan.model.fileBytes, ctx, plan.slices.joinToString(",") { "${it.from}-${it.to}" }).joinToString("|")

    class Profiles(ctx: Context) {
        private val prefs = ctx.getSharedPreferences("mesh-compiler", Context.MODE_PRIVATE)
        fun threads(key: String): Int? = prefs.getInt(key, 0).takeIf { it > 0 }
        fun save(key: String, threads: Int) = prefs.edit().putInt(key, threads).apply()
        fun clear(key: String) = prefs.edit().remove(key).apply()
    }
}
