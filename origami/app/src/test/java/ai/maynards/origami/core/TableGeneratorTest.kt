package ai.maynards.origami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TableGeneratorTest {
    private val spec = OrganizerSpec(widthMm = 400, heightMm = 120, depthMm = 220, materialThicknessMm = 6, template = Template.TABLE)
    private val design = OrganizerGenerator.generate(spec)

    @Test fun topTwoLegsAndAStretcherWithAMatingSlotForEveryTab() {
        assertEquals(listOf("top", "left", "right", "stretcher"), design.parts.map { it.id })
        assertEquals(6, design.joints.size)                              // 2 per leg into the top + 2 stretcher ends
        design.joints.forEach { j ->
            assertNotNull(design.part(j.partA)!!.tabs.firstOrNull { it.edge == j.edgeA && abs(it.centerMm - j.tabCenterMm) < 1e-6 })
            assertTrue(design.part(j.partB)!!.cutouts.any {
                j.slotCenter.x in it.x..(it.x + it.width) && j.slotCenter.y in it.y..(it.y + it.height) })
            assertEquals(6.2, j.slotWidthMm, 1e-9)
        }
    }

    @Test fun legSlotsInTheTopLineUpWithTheLegsInThe3dAssembly() {
        val top = design.part("top")!!
        val left = design.part("left")!!
        val legX = left.assembledPoint(Point2(0.0, 0.0), spec).x
        design.joints.filter { it.partB == "top" && it.partA == "left" }.forEach { j ->
            assertEquals(legX, top.assembledPoint(j.slotCenter, spec).x, 1e-9)
        }
    }

    @Test fun stretcherFitsBetweenTheLegsAndItsSlotsMeetIt() {
        val t = 6.0
        val s = design.part("stretcher")!!
        val start = s.assembledPoint(Point2(0.0, 0.0), spec).x
        val end = s.assembledPoint(Point2(s.widthMm, 0.0), spec).x
        assertEquals(2 * t, start, 1e-9)                // left leg's inner face
        assertEquals(400 - 2 * t, end, 1e-9)            // right leg's inner face
        val slot = design.joints.first { it.id == "stretcher-left" }.slotCenter
        val legPoint = design.part("left")!!.assembledPoint(slot, spec)
        assertEquals(s.assembledPoint(Point2(0.0, 0.0), spec).y, legPoint.y, 1e-9)
    }

    @Test fun legsStandOnTheFloorUnderTheTop() {
        val leg = design.part("right")!!
        assertEquals(0.0, leg.assembledPoint(Point2(0.0, leg.heightMm), spec).z, 1e-9)
        assertEquals(120.0 - 6.0, leg.assembledPoint(Point2(0.0, 0.0), spec).z, 1e-9)
        assertEquals(120.0, design.part("top")!!.assembledPoint(Point2(0.0, 0.0), spec).z, 1e-9)
    }

    @Test fun laysOutOnAStandardSheetAndExports() {
        val fits = listOf(StockSheet(600, 400), StockSheet(600, 600), StockSheet(900, 600)).map { SheetLayoutPlanner.bestLayout(design, it).fits }
        println("table 400x220x120 fits 600x400/600x600/900x600: $fits")
        val layout = SheetLayoutPlanner.bestLayout(design, StockSheet(600, 600))
        assertTrue(layout.fits)
        assertTrue(ProjectJsonExporter.render(design, layout).contains("\"template\": \"TABLE\""))
        assertTrue(SvgExporter.render(design, layout).contains("<svg"))
        assertTrue(ProjectJsonExporter.render(design, layout).contains("\"stretcher\""))
    }

    @Test fun rejectsTablesThatCannotHoldTheirJoints() {
        assertThrows(IllegalArgumentException::class.java) { OrganizerGenerator.generate(spec.copy(materialThicknessMm = 2)) }
        assertThrows(IllegalArgumentException::class.java) { OrganizerGenerator.generate(spec.copy(depthMm = 60)) }
        assertThrows(IllegalArgumentException::class.java) { OrganizerGenerator.generate(spec.copy(heightMm = 900)) }
    }

    @Test fun organizerIsUnchanged() {
        val o = OrganizerGenerator.generate(OrganizerSpec())
        assertEquals(6, o.parts.size)
        assertEquals(16, o.joints.size)
    }
}
