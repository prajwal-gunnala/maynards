package ai.maynards.mesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseRetrieveTest {
    private val sample = listOf(CaseDocument("mesh_case_sample.pdf", listOf(
        CasePage("page 1", "Synthetic PDF Case File\n\nOn 14 March, the fictional parties signed an agreement.\n\nDelivery was scheduled for 22 March."),
        CasePage("page 2", "Second page\n\nThe fictional witness confirmed the delivery date."),
    ), 0, 160))

    @Test fun questionsFindTheirPassages() {
        listOf("When is delivery due?", "Who signed the agreement?", "When is delivery due, and do the documents agree?").forEach { q ->
            val found = CaseDocuments.retrieve(sample, q)
            println("$q -> ${found.map { "${it.id} ${it.location}" }}")
            assertTrue(q, found.isNotEmpty())
        }
    }

    @Test fun otherWordFormsMatch() {
        assertEquals("page 1", CaseDocuments.retrieve(sample, "Did they agree? Who signs?").first().location)
        assertTrue(CaseDocuments.retrieve(sample, "witnesses").any { it.location == "page 2" })
    }

    @Test fun shortWordsNeedAnExactMatch() {
        // "on" must not match "one" or "only"; stop words and words under 4 letters stay exact
        assertEquals(emptyList<CaseEvidence>(), CaseDocuments.retrieve(sample, "pdfs"))
    }

    @Test fun smallCaseIsSentWholeWhenNothingMatches() {
        val whole = CaseDocuments.wholeCase(sample)
        assertEquals(listOf("S01", "S02"), whole.map { it.id })
        assertEquals(listOf("page 1", "page 2"), whole.map { it.location })
    }

    @Test fun largeCaseIsNotSentWhole() {
        val big = listOf(CaseDocument("big.txt", (1..10).map { CasePage("lines $it", "x".repeat(900)) }, 0, 9000))
        assertTrue(CaseDocuments.wholeCase(big).isEmpty())
    }

    @Test fun unrelatedTermsFindNothing() {
        assertEquals(emptyList<CaseEvidence>(), CaseDocuments.retrieve(sample, "platypus"))
    }
}
