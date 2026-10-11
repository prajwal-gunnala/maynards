package ai.maynards.origami.core

import ai.maynards.origami.core.Structure.Load
import ai.maynards.origami.core.Structure.Material
import ai.maynards.origami.core.Structure.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StructureTest {
    @Test fun textbookBeamNumbers() {
        // 1000 mm span, 100 × 10 mm board, E = 8 GPa, 10 kg spread: δ = 5FL³/(384EI) = 19.16 mm, σ = 7.36 MPa
        val (sag, stress) = Structure.sagAndStress(1000.0, 100.0, 10.0, Material.PLYWOOD, Load(10.0))
        assertEquals(19.16, sag, 0.01)
        assertEquals(7.36, stress, 0.01)
        // the same weight at the centre sags 8/5 as much and stresses twice as much
        val (ps, pst) = Structure.sagAndStress(1000.0, 100.0, 10.0, Material.PLYWOOD, Load(10.0, pointLoad = true))
        assertEquals(sag * 8 / 5, ps, 1e-9)
        assertEquals(stress * 2, pst, 1e-9)
    }

    @Test fun catalogueBookshelfHoldsItsBooksAndAThinOneIsFlagged() {
        val shelf = OrganizerGenerator.generate(Catalogue.items.first { it.id == "bookshelf" }.spec)
        val ok = Structure.check(shelf)
        assertEquals(4, ok.size)
        assertTrue(ok.joinToString { "${it.sagMm}" }, ok.all { it.verdict == Verdict.OK })

        val wide = OrganizerGenerator.generate(OrganizerSpec(1000, 800, 250, 6, 3, template = Template.BOOKSHELF))
        val bad = Structure.check(wide)
        assertTrue(bad.all { it.verdict != Verdict.OK })
        assertTrue(bad.first().advice, bad.first().advice.startsWith("use ") || bad.first().advice.startsWith("add"))
    }

    @Test fun mdfSagsMoreThanPlywood() {
        val d = OrganizerGenerator.generate(Catalogue.items.first { it.id == "side-table" }.spec)
        val ply = Structure.check(d, Material.PLYWOOD).single()
        val mdf = Structure.check(d, Material.MDF).single()
        assertEquals(8.0 / 3.5, mdf.sagMm / ply.sagMm, 1e-9)
    }

    @Test fun aPersonOnTheStepStoolIsCheckedAsACentreLoad() {
        val stool = OrganizerGenerator.generate(Catalogue.items.first { it.id == "step-stool" }.spec)
        val c = Structure.check(stool, load = Load(90.0, pointLoad = true)).single()
        assertTrue(c.load.pointLoad)
        assertTrue("12 mm at 90 kg: ${c.verdict} ${c.sagMm} mm ${c.stressMpa} MPa", c.verdict != Verdict.OK)
    }

    @Test fun organizersHaveNothingSpanningAGap() {
        assertTrue(Structure.check(OrganizerGenerator.generate(OrganizerSpec())).isEmpty())
    }

    @Test fun maxLoadIsWhereTheChecksBind() {
        val max = Structure.maxLoadKg(576.0, 238.0, 12.0, Material.PLYWOOD)
        val (sag, stress) = Structure.sagAndStress(576.0, 238.0, 12.0, Material.PLYWOOD, Load(max))
        assertTrue(sag >= 576.0 / 360 * 0.99 || stress >= 35.0 / 3 * 0.99)
    }

    @Test fun kerfGrowsOutlinesAndShrinksSlots() {
        val sq = listOf(Point2(0.0, 0.0), Point2(10.0, 0.0), Point2(10.0, 10.0), Point2(0.0, 10.0))
        val g = Kerf.grow(sq, 0.1)
        assertEquals(10.2 * 10.2, abs(Mesh3D.signedArea(g)), 1e-9)
        assertEquals(10.2 * 10.2, abs(Mesh3D.signedArea(Kerf.grow(sq.reversed(), 0.1))), 1e-9)   // either orientation
        // an outline with a tab keeps its shape, just larger
        val tabbed = OrganizerGenerator.generate(OrganizerSpec()).part("left")!!.outline
        assertTrue(abs(Mesh3D.signedArea(Kerf.grow(tabbed, 0.1))) > abs(Mesh3D.signedArea(tabbed)))
        assertEquals(Cutout(1.1, 2.1, 2.8, 0.8), Kerf.shrink(Cutout(1.0, 2.0, 3.0, 1.0), 0.1).let {
            Cutout(Math.round(it.x * 10) / 10.0, Math.round(it.y * 10) / 10.0, Math.round(it.width * 10) / 10.0, Math.round(it.height * 10) / 10.0) })
    }
}
