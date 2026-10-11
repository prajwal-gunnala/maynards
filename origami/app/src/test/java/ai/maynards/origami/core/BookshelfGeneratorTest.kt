package ai.maynards.origami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BookshelfGeneratorTest {
    private val spec = OrganizerSpec(widthMm = 600, heightMm = 800, depthMm = 250, materialThicknessMm = 12, compartments = 4, template = Template.BOOKSHELF)
    private val design = OrganizerGenerator.generate(spec)

    @Test fun twoSidesShelvesAndAStripWithAMatingSlotForEveryTab() {
        assertEquals(listOf("left", "right", "shelf-1", "shelf-2", "shelf-3", "shelf-4", "strip"), design.parts.map { it.id })
        assertEquals(4 * 4 + 2, design.joints.size)
        design.joints.forEach { j ->
            assertTrue(j.id, design.part(j.partA)!!.tabs.any { it.edge == j.edgeA && abs(it.centerMm - j.tabCenterMm) < 1e-6 })
            assertTrue(j.id, design.part(j.partB)!!.cutouts.any {
                j.slotCenter.x in it.x..(it.x + it.width) && j.slotCenter.y in it.y..(it.y + it.height) })
        }
    }

    @Test fun everyShelfSlotSitsWhereItsTabPassesThroughTheSide() {
        for (j in design.joints.filter { it.partA.startsWith("shelf") }) {
            val shelf = design.part(j.partA)!!
            // the tab's centre on the shelf's edge, and the slot's centre in the side, must be the same 3D point
            // except across the side's thickness (x)
            val tabPoint = shelf.assembledPoint(Point2(if (j.edgeA == Edge.LEFT) 0.0 else shelf.widthMm, j.tabCenterMm), spec)
            val slotPoint = design.part(j.partB)!!.assembledPoint(j.slotCenter, spec)
            assertEquals(j.id, tabPoint.y, slotPoint.y, 1e-9)
            assertEquals(j.id, tabPoint.z, slotPoint.z, 1e-9)
        }
    }

    @Test fun shelvesSpanTheSidesFromPlinthToTop() {
        val t = 12.0
        val lowest = OrganizerGenerator.shelfZ(spec, 0); val highest = OrganizerGenerator.shelfZ(spec, 3)
        assertEquals(40 + t / 2, lowest, 1e-9)
        assertEquals(800 - t / 2, highest, 1e-9)
        val shelf = design.part("shelf-2")!!
        assertEquals(t, shelf.assembledPoint(Point2(0.0, 0.0), spec).x, 1e-9)                 // left side's inner face
        assertEquals(600 - t, shelf.assembledPoint(Point2(shelf.widthMm, 0.0), spec).x, 1e-9)   // right side's inner face
    }

    @Test fun stripSlotsMeetTheStrip() {
        val strip = design.part("strip")!!
        val j = design.joints.first { it.id == "strip-left" }
        val tab = strip.assembledPoint(Point2(0.0, j.tabCenterMm), spec)
        val slot = design.part("left")!!.assembledPoint(j.slotCenter, spec)
        assertEquals(tab.y, slot.y, 1e-9); assertEquals(tab.z, slot.z, 1e-9)
    }

    @Test fun buildsAsSolidsAndExports() {
        val meshes = Mesh3D.build(design)
        assertEquals(design.parts.size, meshes.size)
        val layout = SheetLayoutPlanner.bestLayout(design, StockSheet(1220, 2440))
        assertTrue(layout.fits)
        assertTrue(ProjectJsonExporter.render(design, layout).contains("\"template\": \"BOOKSHELF\""))
        System.getenv("GLB_OUT")?.let { java.io.File(it, "bookshelf.glb").writeBytes(GlbWriter.write(meshes, byteArrayOf(1, 2, 3, 4))) }
    }

    @Test fun rejectsShelvesTooCloseOrMaterialTooThin() {
        assertThrows(IllegalArgumentException::class.java) { OrganizerGenerator.generate(spec.copy(compartments = 6, heightMm = 400)) }
        assertThrows(IllegalArgumentException::class.java) { OrganizerGenerator.generate(spec.copy(materialThicknessMm = 3)) }
        assertThrows(IllegalArgumentException::class.java) { OrganizerGenerator.generate(spec.copy(depthMm = 100)) }
    }
}
