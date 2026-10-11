package ai.maynards.origami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueTest {
    @Test fun everyItemGeneratesChecksAndFitsAStandardPlywoodSheet() {
        for (item in Catalogue.items) {
            val design = OrganizerGenerator.generate(item.spec)             // validates and checks every joint
            assertTrue(item.id, design.parts.isNotEmpty())
            assertEquals(item.id, design.parts.size, Mesh3D.build(design).size)
            assertTrue("${item.id} fits 1220×2440", SheetLayoutPlanner.bestLayout(design, StockSheet(1220, 2440)).fits)
        }
    }

    @Test fun idsAreUniqueAndEveryGroupHasItems() {
        assertEquals(Catalogue.items.size, Catalogue.items.map { it.id }.distinct().size)
        assertTrue(Catalogue.groups.containsAll(listOf("Storage", "Tables", "Seating", "Shelves", "Toys")))
    }
}
