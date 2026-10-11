package ai.maynards.origami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FingerBoxTest {
    private val storage = Catalogue.items.filter { it.spec.template == Template.ORGANIZER }

    @Test fun everyStorageItemBuildsAsAFingerBoxAndEveryJointLandsInItsSlotOrNotch() {
        for (item in storage) {
            val d = OrganizerGenerator.generate(item.spec.copy(joint = JointStyle.FINGER))
            assertTrue(item.id, d.joints.count { it.type == JointType.FINGER } >= 8)
            assertEquals(item.id, emptyList<String>(), jointMisses(d))
            assertEquals(item.id, d.parts.size, Mesh3D.build(d).size)
            val layout = SheetLayoutPlanner.bestLayout(d, StockSheet(1220, 2440))
            assertTrue(item.id, layout.fits)
            assertTrue(SvgExporter.render(d, layout, 0.15).contains("<path"))
        }
    }

    @Test fun fingersAndNotchesTradeTheSameArea() {
        val spec = OrganizerSpec(300, 160, 180, 6, 2, joint = JointStyle.FINGER)
        val d = OrganizerGenerator.generate(spec)
        val t = 6.0; val fit = spec.clearanceMm
        val back = d.part("back")!!
        val fingers = back.tabs.filter { it.depthMm > 0 }.sumOf { it.spanMm * it.depthMm }
        val notches = back.tabs.filter { it.depthMm < 0 }.sumOf { it.spanMm * -it.depthMm }
        assertEquals(back.widthMm * back.heightMm + fingers - notches, abs(Mesh3D.signedArea(back.outline)), 1e-6)
        // each finger is one board deep; each notch is a board deep plus half the clearance, and wider by the clearance
        assertTrue(back.tabs.filter { it.depthMm > 0 && it.edge != Edge.BOTTOM }.all { abs(it.depthMm - t) < 1e-9 })
        val side = d.part("left")!!
        assertTrue(side.tabs.all { it.depthMm < 0 && abs(-it.depthMm - (t + fit / 2)) < 1e-9 })
    }

    @Test fun theBoxIsClosedAtEveryCorner() {
        // the walls' outer faces meet: sides span the full depth, front/back plus their fingers the full width
        val spec = OrganizerSpec(300, 160, 180, 6, 2, joint = JointStyle.FINGER)
        val d = OrganizerGenerator.generate(spec)
        val left = d.part("left")!!; val back = d.part("back")!!
        assertEquals(0.0, left.assembledPoint(Point2(0.0, 0.0), spec).y, 1e-9)
        assertEquals(180.0, left.assembledPoint(Point2(left.widthMm, 0.0), spec).y, 1e-9)
        assertEquals(0.0, back.assembledPoint(Point2(back.minX, 50.0), spec).x, 1e-9)     // a finger reaches the outside
        assertEquals(300.0, back.assembledPoint(Point2(back.maxX, 50.0), spec).x, 1e-9)
    }

    @Test fun fingerJointsAreOnlyForBoxes() {
        assertThrows(IllegalArgumentException::class.java) {
            OrganizerGenerator.generate(Catalogue.items.first { it.id == "side-table" }.spec.copy(joint = JointStyle.FINGER))
        }
    }
}
