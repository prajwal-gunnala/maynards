package ai.maynards.mesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseConflictsTest {
    private val events = listOf(
        CaseEvent(2024, 3, 22, "22 March 2024", "The Supplier shall deliver the goods no later than 22 March 2024.", "contract.pdf", "page 2"),
        CaseEvent(2024, 3, 29, "29 March 2024", "We confirm delivery will now take place on 29 March 2024.", "email.txt", "lines 1–6"),
        CaseEvent(2024, 4, 1, "1 April 2024", "Payment is due on 1 April 2024.", "contract.pdf", "page 3"),
    )

    @Test fun promptNumbersEveryEventWithItsSource() {
        val p = CaseConflicts.prompt(events)
        assertTrue(p.contains("[E01] 22 March 2024 · contract.pdf, page 2: The Supplier shall deliver"))
        assertTrue(p.contains("[E03] 1 April 2024 · contract.pdf, page 3"))
    }

    @Test fun validPairsAreKeptWithTheirEvents() {
        val r = CaseConflicts.parse("""Here: ```json
            [{"a":"E01","b":"e2","why":"Two delivery dates."}]
            ```""", events)
        assertTrue(r.parsed)
        assertEquals(1, r.conflicts.size)
        assertEquals("email.txt", r.conflicts[0].b.fileName)
        assertEquals("E02", r.conflicts[0].bId)
        assertEquals(0, r.dropped)
    }

    @Test fun inventedSelfDuplicateOrUnexplainedPairsAreDropped() {
        val r = CaseConflicts.parse("""[{"a":"E01","b":"E09","why":"x"},{"a":"E02","b":"E02","why":"x"},
            {"a":"E01","b":"E02","why":""},{"a":"E01","b":"E02","why":"dates differ"},{"a":"E02","b":"E01","why":"again"}]""", events)
        assertEquals(1, r.conflicts.size)
        assertEquals(4, r.dropped)
    }

    @Test fun emptyListMeansNoConflicts() {
        val r = CaseConflicts.parse("[]", events)
        assertTrue(r.parsed)
        assertTrue(r.conflicts.isEmpty())
    }

    @Test fun nonJsonReplyIsReportedNotGuessed() {
        val r = CaseConflicts.parse("The dates seem different.", events)
        assertFalse(r.parsed)
        assertFalse(CaseConflicts.parse("[not json", events).parsed)
    }

    @Test fun reasoningBlockIsIgnored() {
        val r = CaseConflicts.parse("<think>[{\"a\":\"E01\",\"b\":\"E03\",\"why\":\"no\"}]</think>[]", events)
        assertTrue(r.parsed)
        assertTrue(r.conflicts.isEmpty())
    }
}
