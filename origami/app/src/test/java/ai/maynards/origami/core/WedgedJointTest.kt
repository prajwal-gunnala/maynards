package ai.maynards.origami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WedgedJointTest {
    private fun both(spec: OrganizerSpec) = OrganizerGenerator.generate(spec) to OrganizerGenerator.generate(spec.copy(joint = JointStyle.WEDGED))

    @Test fun everyCatalogueItemCanBeWedgedAndStillChecksOut() {
        for (item in Catalogue.items) {
            val (plain, wedged) = both(item.spec)
            val wedges = wedged.parts.filter { it.plane == PanelPlane.LOOSE }
            assertEquals(item.id, plain.joints.size, wedges.size)                       // one wedge per joint
            assertEquals(item.id, plain.parts.size + wedges.size, wedged.parts.size)
            assertTrue(item.id, SheetLayoutPlanner.bestLayout(wedged, StockSheet(1220, 2440)).fits)
            assertEquals(item.id, plain.parts.size, Mesh3D.build(wedged).size)          // wedges are not placed in 3D
        }
    }

    @Test fun eachWedgeHoleStartsHalfAMillimetreInsideTheFarFaceOfTheBoardItPassesThrough() {
        for (spec in listOf(OrganizerSpec(), Catalogue.items.first { it.id == "side-table" }.spec, Catalogue.items.first { it.id == "bookshelf" }.spec)) {
            val d = OrganizerGenerator.generate(spec.copy(joint = JointStyle.WEDGED))
            val t = spec.materialThicknessMm.toDouble()
            for (j in d.joints) {
                val a = d.part(j.partA)!!; val b = d.part(j.partB)!!
                val tab = a.tabs.first { it.edge == j.edgeA && kotlin.math.abs(it.centerMm - j.tabCenterMm) < 1e-6 }
                // the hole is the cut-out in A that lies outside A's base rectangle, beyond this tab
                val hole = a.cutouts.first { c ->
                    when (tab.edge) {
                        Edge.LEFT -> c.x < 0 && c.y < tab.centerMm && c.y + c.height > tab.centerMm
                        Edge.RIGHT -> c.x >= a.widthMm && c.y < tab.centerMm && c.y + c.height > tab.centerMm
                        Edge.TOP -> c.y < 0 && c.x < tab.centerMm && c.x + c.width > tab.centerMm
                        Edge.BOTTOM -> c.y >= a.heightMm && c.x < tab.centerMm && c.x + c.width > tab.centerMm
                    }
                }
                // inner edge of the hole, in 3D, measured against B's far face along the tab
                val innerEdge2d = when (tab.edge) {
                    Edge.LEFT -> Point2(hole.x + hole.width, tab.centerMm); Edge.RIGHT -> Point2(hole.x, tab.centerMm)
                    Edge.TOP -> Point2(tab.centerMm, hole.y + hole.height); Edge.BOTTOM -> Point2(tab.centerMm, hole.y)
                }
                val root2d = when (tab.edge) {
                    Edge.LEFT -> Point2(0.0, tab.centerMm); Edge.RIGHT -> Point2(a.widthMm, tab.centerMm)
                    Edge.TOP -> Point2(tab.centerMm, 0.0); Edge.BOTTOM -> Point2(tab.centerMm, a.heightMm)
                }
                val p0 = a.assembledPoint(root2d, spec); val pin = a.assembledPoint(innerEdge2d, spec)
                val u = (pin - p0).unit()
                val far = ((b.assembledPoint(j.slotCenter, spec) - p0) dot u) + t / 2
                assertEquals("${spec.template} ${j.id}", far - OrganizerGenerator.WEDGE_BITE, (pin - p0) dot u, 1e-6)
                assertEquals("${j.id} hole is one board thick", t, if (tab.edge == Edge.LEFT || tab.edge == Edge.RIGHT) hole.width else hole.height, 1e-6)
            }
        }
    }

    @Test fun aWedgeIsThinEnoughToStartAndThickEnoughToStop() {
        val d = OrganizerGenerator.generate(OrganizerSpec(joint = JointStyle.WEDGED))
        val holeLen = d.part("back")!!.tabs.first().spanMm * 0.6
        val w = d.parts.first { it.plane == PanelPlane.LOOSE }
        val thin = w.outline[3].y - w.outline[0].y; val thick = w.outline[2].y - w.outline[1].y
        assertTrue(thin < holeLen && thick > holeLen)
    }

    @Test fun tabAndSlotIsStillTheDefault() {
        assertEquals(JointStyle.TAB_SLOT, OrganizerSpec().joint)
        assertTrue(OrganizerGenerator.generate(OrganizerSpec()).parts.none { it.plane == PanelPlane.LOOSE })
    }
}
