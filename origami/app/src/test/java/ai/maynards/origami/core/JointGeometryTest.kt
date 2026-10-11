package ai.maynards.origami.core

import org.junit.Assert.assertTrue
import org.junit.Test

/** The basic promise of a tab-and-slot joint, checked in 3D: a tab, followed straight out, goes through its slot. */
class JointGeometryTest {
    private fun misses(design: OrganizerDesign) = jointMisses(design)

    @Test fun tableJointsGoThroughTheirSlots() { val m = misses(OrganizerGenerator.generate(Catalogue.items.first { it.id == "side-table" }.spec)); assertTrue(m.joinToString("\n"), m.isEmpty()) }
    @Test fun bookshelfJointsGoThroughTheirSlots() { val m = misses(OrganizerGenerator.generate(Catalogue.items.first { it.id == "bookshelf" }.spec)); assertTrue(m.joinToString("\n"), m.isEmpty()) }
    @Test fun organizerJointsGoThroughTheirSlots() { val m = misses(OrganizerGenerator.generate(OrganizerSpec())); assertTrue(m.joinToString("\n"), m.isEmpty()) }
}
