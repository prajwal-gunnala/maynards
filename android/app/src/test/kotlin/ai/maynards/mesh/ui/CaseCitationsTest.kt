package ai.maynards.mesh.ui

import ai.maynards.mesh.ui.CaseCitations.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseCitationsTest {
    private val sources = listOf(
        CaseEvidence("S01", "contract.pdf", "page 1", "Delivery is due on 3 March."),
        CaseEvidence("S02", "email.txt", "lines 1–4", "The supplier asked to move delivery to 10 March."),
    )

    @Test fun citedAnswerPasses() {
        val c = CaseCitations.check("Delivery was due on 3 March [S01]. The supplier asked for 10 March [S02].", sources)
        assertEquals(Verdict.CITED, c.verdict)
        assertEquals(listOf("S01", "S02"), c.cited)
        assertEquals(0, c.uncited)
    }

    @Test fun groupedAndUnpaddedIdsAreRead() {
        val c = CaseCitations.check("The two documents disagree about the delivery date [S1, s02].", sources)
        assertEquals(Verdict.CITED, c.verdict)
        assertEquals(listOf("S01", "S02"), c.cited)
    }

    @Test fun inventedSourceIsRejected() {
        val c = CaseCitations.check("Payment was late [S07].", sources)
        assertEquals(Verdict.UNKNOWN_SOURCE, c.verdict)
        assertEquals(listOf("S07"), c.unknown)
    }

    @Test fun answerWithoutCitationsIsFlagged() {
        assertEquals(Verdict.NO_CITATIONS, CaseCitations.check("Delivery was due on 3 March.", sources).verdict)
    }

    @Test fun uncitedSentencesAreCounted() {
        val c = CaseCitations.check("Delivery was due on 3 March [S01]. The supplier was therefore in breach.", sources)
        assertEquals(Verdict.CITED, c.verdict)
        assertEquals(1, c.uncited)
    }

    @Test fun notFoundReplyIsAccepted() {
        assertEquals(Verdict.NOT_FOUND, CaseCitations.check("Not found in the supplied sources.", sources).verdict)
    }

    @Test fun reasoningBlockIsIgnored() {
        val c = CaseCitations.check("<think>maybe [S09]</think>Delivery was due on 3 March [S01].", sources)
        assertEquals(Verdict.CITED, c.verdict)
        assertEquals(listOf("S01"), c.cited)
    }

    @Test fun promptCarriesEverySourceId() {
        val p = CaseCitations.prompt("When is delivery due?", sources)
        assertTrue(p.contains("[S01] contract.pdf, page 1"))
        assertTrue(p.contains("[S02] email.txt, lines 1–4"))
        assertTrue(p.endsWith("When is delivery due?"))
    }
}
