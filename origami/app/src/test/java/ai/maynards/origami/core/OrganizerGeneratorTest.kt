package ai.maynards.origami.core

import org.junit.Assert.*
import org.junit.Test
import com.google.gson.JsonParser
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class OrganizerGeneratorTest {
    @Test fun defaultDesignHasMatingPartAndSlotForEveryJoint() {
        val design = OrganizerGenerator.generate(OrganizerSpec())
        assertEquals(6, design.parts.size)
        assertEquals(16, design.joints.size)
        assertEquals(design.joints.size, design.joints.map { it.id }.distinct().size)
        design.joints.forEach { joint ->
            assertNotNull("tab part ${joint.partA}", design.part(joint.partA))
            assertNotNull("slot part ${joint.partB}", design.part(joint.partB))
            assertTrue(design.part(joint.partA)!!.tabs.any { it.edge == joint.edgeA && it.centerMm == joint.tabCenterMm })
            assertTrue(design.part(joint.partB)!!.cutouts.any {
                joint.slotCenter.x >= it.x - 0.001 && joint.slotCenter.x <= it.x + it.width + 0.001 &&
                    joint.slotCenter.y >= it.y - 0.001 && joint.slotCenter.y <= it.y + it.height + 0.001
            })
            assertEquals(3.2, joint.slotWidthMm, 0.0001)
        }
        design.parts.forEach { part ->
            assertTrue(part.cutouts.all { it.x >= 0 && it.y >= 0 && it.x + it.width <= part.widthMm && it.y + it.height <= part.heightMm })
        }
    }

    @Test fun thicknessAndClearanceDetermineSlotWidth() {
        val design = OrganizerGenerator.generate(OrganizerSpec(materialThicknessMm = 4, clearanceMm = 0.4))
        val baseJoint = design.joints.first { it.partB == "base" }
        assertEquals(4.4, baseJoint.slotWidthMm, 0.0001)
        assertEquals(0.4, baseJoint.clearanceMm, 0.0001)
    }

    @Test fun compartmentsAddExactlyOneDividerEach() {
        for (compartments in 1..4) {
            val design = OrganizerGenerator.generate(OrganizerSpec(compartments = compartments))
            assertEquals(5 + compartments - 1, design.parts.size)
            assertEquals(compartments - 1, design.parts.count { it.plane == PanelPlane.DIVIDER })
            assertTrue(design.joints.all { design.part(it.partA) != null && design.part(it.partB) != null })
        }
    }

    @Test fun rejectsInvalidAndImpossibleInputs() {
        assertThrows(IllegalArgumentException::class.java) { OrganizerGenerator.generate(OrganizerSpec(widthMm = 50)) }
        assertThrows(IllegalArgumentException::class.java) { OrganizerGenerator.generate(OrganizerSpec(materialThicknessMm = 0)) }
        assertThrows(IllegalArgumentException::class.java) { OrganizerGenerator.generate(OrganizerSpec(widthMm = 100, materialThicknessMm = 10, compartments = 4)) }
    }

    @Test fun generatedOutlinesIncludeTheirTabs() {
        val design = OrganizerGenerator.generate(OrganizerSpec())
        val front = design.part("front")!!
        assertTrue(front.outline.minOf { it.x } < 0)
        assertTrue(front.outline.maxOf { it.x } > front.widthMm)
        assertTrue(front.outline.maxOf { it.y } > front.heightMm)
    }
}

class SheetLayoutTest {
    @Test fun defaultOrganizerFitsLargerPresetSheet() {
        val design = OrganizerGenerator.generate(OrganizerSpec())
        val layout = SheetLayoutPlanner.bestLayout(design, StockSheet(600, 600, 5, 2))
        assertTrue(layout.error ?: "Expected the 600 × 600 mm sheet to fit", layout.fits)
        assertTrue(layout.utilizationPercent in 0.0..100.0)
    }

    @Test fun defaultOrganizerReportsSmallSheetAsNonFitting() {
        val design = OrganizerGenerator.generate(OrganizerSpec())
        val layout = SheetLayoutPlanner.bestLayout(design, StockSheet(600, 400, 5, 2))
        assertFalse(layout.fits)
        assertTrue(layout.error!!.contains("600 × 400"))
    }

    @Test fun placementsStayInsideStockAndAreUnique() {
        val design = OrganizerGenerator.generate(OrganizerSpec(widthMm = 220, depthMm = 120, heightMm = 110, compartments = 1))
        val layout = SheetLayoutPlanner.bestLayout(design, StockSheet(600, 400, 5, 2))
        assertTrue(layout.error ?: "Expected the compact organizer to fit", layout.fits)
        assertEquals(design.parts.size, layout.placements.size)
        assertEquals(design.parts.size, layout.placements.map { it.partId }.distinct().size)
        layout.placements.forEach {
            assertTrue(it.xMm >= 5)
            assertTrue(it.yMm >= 5)
            assertTrue(it.xMm + it.boundsWidthMm <= 595.001)
            assertTrue(it.yMm + it.boundsHeightMm <= 395.001)
        }
    }

    @Test fun impossibleStockReturnsActionableFailure() {
        val design = OrganizerGenerator.generate(OrganizerSpec(widthMm = 300, depthMm = 180, heightMm = 160, compartments = 2))
        val layout = SheetLayoutPlanner.bestLayout(design, StockSheet(100, 100, 5, 2))
        assertFalse(layout.fits)
        assertNotNull(layout.error)
        assertTrue(layout.placements.isEmpty())
        val project = JsonParser.parseString(ProjectJsonExporter.render(design, layout)).asJsonObject
        assertEquals("animation-preview", project.getAsJsonObject("flatPreview").get("kind").asString)
        assertTrue(project.getAsJsonArray("parts").all { it.asJsonObject.get("flatPlacement").isJsonObject })
    }

    @Test fun svgAndProjectExportsCarrySameJointData() {
        val design = OrganizerGenerator.generate(OrganizerSpec(widthMm = 220, depthMm = 120, heightMm = 110, compartments = 1))
        val layout = SheetLayoutPlanner.bestLayout(design)
        assertTrue(layout.fits)
        val svg = SvgExporter.render(design, layout)
        val json = ProjectJsonExporter.render(design, layout)
        assertTrue(svg.startsWith("<?xml"))
        assertTrue(svg.contains("width=\"600mm\""))
        assertEquals(design.parts.size, Regex("<path id=").findAll(svg).count())
        assertEquals(design.parts.sumOf { it.cutouts.size }, Regex("<rect id=").findAll(svg).count())
        assertTrue(json.startsWith("{\n"))
        assertTrue(json.contains("maynards.origami.organizer.v1"))
        assertEquals(design.joints.size, Regex("\\\"type\\\":\\\"TAB_SLOT\\\"").findAll(json).count())
        assertFalse(json.contains("NaN"))
        assertFalse(json.contains("Infinity"))
        val project = JsonParser.parseString(json).asJsonObject
        assertEquals(design.parts.size, project.getAsJsonArray("parts").size())
        assertEquals(design.joints.size, project.getAsJsonArray("joints").size())
        val svgDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(svg)))
        assertEquals("svg", svgDoc.documentElement.tagName)
    }
}
