package ai.maynards.origami.core

import kotlin.math.ceil
import kotlin.math.pow

/**
 * Will it hold? For every board that spans a gap, the sag and bending stress under a stated load, from
 * textbook beam formulas. Material values are cautious handbook figures (estimates, not measured on this
 * stock); the verdict is a guide, not a certified rating.
 */
object Structure {
    enum class Material(val label: String, val stiffnessGpa: Double, val strengthMpa: Double) {
        PLYWOOD("Birch plywood", 8.0, 35.0),
        MDF("MDF", 3.5, 25.0),
        ACRYLIC("Acrylic", 3.0, 70.0),
    }

    /** What the board carries: spread evenly (books, things on a table) or one weight in the middle (a person). */
    data class Load(val kg: Double, val pointLoad: Boolean = false)

    enum class Verdict { OK, SAGS, FAILS }

    data class Check(
        val partId: String, val partName: String, val spanMm: Double, val load: Load,
        val sagMm: Double, val limitMm: Double, val stressMpa: Double, val allowedMpa: Double,
        val verdict: Verdict, val advice: String,
    )

    const val SAFETY = 3.0
    private val THICKNESSES = listOf(3, 6, 9, 12, 15, 18, 24)

    /** The load a template is checked for unless the person changes it. */
    fun defaultLoad(spec: OrganizerSpec): Load = when (spec.template) {
        Template.BOOKSHELF -> Load(25.0 * (spec.widthMm - 2 * spec.materialThicknessMm) / 1000.0)   // paperbacks ≈ 25 kg per metre
        Template.TABLE -> Load(10.0)
        Template.ORGANIZER -> Load(0.0)
    }

    fun check(design: OrganizerDesign, material: Material = Material.PLYWOOD, load: Load = defaultLoad(design.spec)): List<Check> {
        val spec = design.spec
        val t = spec.materialThicknessMm.toDouble()
        val spans: List<Triple<Part, Double, Double>> = when (spec.template) {       // part, span, board width
            Template.TABLE -> design.parts.filter { it.plane == PanelPlane.TOP }.map { Triple(it, spec.widthMm - 3 * t, spec.depthMm.toDouble()) }
            Template.BOOKSHELF -> design.parts.filter { it.plane == PanelPlane.SHELF }.map { Triple(it, spec.widthMm - 2 * t, it.heightMm) }
            Template.ORGANIZER -> emptyList()        // it rests on its base: nothing spans a gap
        }
        return spans.map { (part, span, width) -> beam(part, span, width, t, material, load) }
    }

    fun beam(part: Part, spanMm: Double, widthMm: Double, thicknessMm: Double, material: Material, load: Load): Check {
        val (sag, stress) = sagAndStress(spanMm, widthMm, thicknessMm, material, load)
        val limit = spanMm / 360
        val allowed = material.strengthMpa / SAFETY
        val verdict = when {
            stress > allowed -> Verdict.FAILS
            sag > spanMm / 180 -> Verdict.FAILS
            sag > limit -> Verdict.SAGS
            else -> Verdict.OK
        }
        val advice = if (verdict == Verdict.OK) "" else {
            val needed = THICKNESSES.firstOrNull { th ->
                val (s, st) = sagAndStress(spanMm, widthMm, th.toDouble(), material, load)
                s <= limit && st <= allowed
            }
            if (needed != null) "use $needed mm, or add a support in the middle" else "add a support in the middle"
        }
        return Check(part.id, part.name, spanMm, load, sag, limit, stress, allowed, verdict, advice)
    }

    /** Simply supported beam: sag in mm and peak bending stress in MPa. */
    fun sagAndStress(spanMm: Double, widthMm: Double, thicknessMm: Double, material: Material, load: Load): Pair<Double, Double> {
        val e = material.stiffnessGpa * 1e3                 // N/mm²
        val i = widthMm * thicknessMm.pow(3) / 12           // mm⁴
        val f = load.kg * 9.81                              // N
        val (sag, moment) = if (load.pointLoad)
            f * spanMm.pow(3) / (48 * e * i) to f * spanMm / 4
        else
            5 * f * spanMm.pow(3) / (384 * e * i) to f * spanMm / 8
        return sag to moment * (thicknessMm / 2) / i
    }

    /** The heaviest spread load a board takes within the "looks flat" limit and the allowed stress. */
    fun maxLoadKg(spanMm: Double, widthMm: Double, thicknessMm: Double, material: Material): Double {
        val (s1, st1) = sagAndStress(spanMm, widthMm, thicknessMm, material, Load(1.0))
        return minOf((spanMm / 360) / s1, (material.strengthMpa / SAFETY) / st1).let { ceil(it * 10) / 10 }
    }
}

/**
 * Laser kerf: the beam removes material, so a cut part comes out smaller and a slot wider. Growing outlines by
 * half the kerf and shrinking slots by half the kerf makes parts come out at their drawn size and press-fit.
 */
object Kerf {
    /** Offsets a simple polygon outward by [d] (miter joins); orientation-independent. */
    fun grow(poly: List<Point2>, d: Double): List<Point2> {
        val p = if (poly.size > 1 && poly.first() == poly.last()) poly.dropLast(1) else poly
        if (p.size < 3 || d == 0.0) return poly
        val sign = if (Mesh3D.signedArea(p) > 0) 1.0 else -1.0   // outward = right of travel for CCW (y up)
        fun normal(a: Point2, b: Point2): Point2 {
            val dx = b.x - a.x; val dy = b.y - a.y; val l = kotlin.math.hypot(dx, dy)
            return Point2(sign * dy / l, -sign * dx / l)
        }
        val out = p.indices.map { i ->
            val prev = p[(i - 1 + p.size) % p.size]; val cur = p[i]; val next = p[(i + 1) % p.size]
            val n1 = normal(prev, cur); val n2 = normal(cur, next)
            val dot = n1.x * n2.x + n1.y * n2.y
            val k = d / (1 + dot).coerceAtLeast(1e-6)
            Point2(cur.x + (n1.x + n2.x) * k, cur.y + (n1.y + n2.y) * k)
        }
        return if (poly.first() == poly.last()) out + out.first() else out
    }

    /** A slot shrunk by half the kerf on every side (never below zero). */
    fun shrink(c: Cutout, d: Double): Cutout =
        Cutout(c.x + d, c.y + d, (c.width - 2 * d).coerceAtLeast(0.0), (c.height - 2 * d).coerceAtLeast(0.0))
}
