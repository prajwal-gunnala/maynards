package ai.maynards.mesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseTimelineTest {
    private fun doc(name: String, vararg pages: String) =
        CaseDocument(name, pages.mapIndexed { i, t -> CasePage("page ${i + 1}", t) }, 0, pages.sumOf { it.length })

    @Test fun sampleCaseGivesTwoEventsInOrder() {
        val events = CaseTimeline.extract(listOf(doc("mesh_case_sample.pdf",
            "Synthetic Case File\n\nOn 14 March, the parties met at 10:30 AM.\nThe fictional agreement lists delivery as 22 March.")))
        assertEquals(listOf("14 March", "22 March"), events.map { it.label })
        assertTrue(events[0].sentence.contains("parties met"))
        assertEquals("page 1", events[1].location)
    }

    @Test fun formatsAreReadAndSortedAcrossFiles() {
        val events = CaseTimeline.extract(listOf(
            doc("email.txt", "The supplier wrote on March 5, 2024 asking for more time."),
            doc("contract.pdf", "Signed 2024-02-01.", "Delivery is due on the 3rd of April 2024."),
        ))
        assertEquals(listOf("1 February 2024", "5 March 2024", "3 April 2024"), events.map { it.label })
        assertEquals(listOf("contract.pdf", "email.txt", "contract.pdf"), events.map { it.fileName })
        assertEquals("page 2", events[2].location)
    }

    @Test fun impossibleDatesAndPlainNumbersAreIgnored() {
        val events = CaseTimeline.extract(listOf(doc("a.txt", "Clause 31 February applies. Pay 12 units. Item 40 May.")))
        assertTrue(events.isEmpty())
    }

    @Test fun oneSentenceWithTwoDatesGivesTwoEvents() {
        val events = CaseTimeline.extract(listOf(doc("a.txt", "Delivery moved from 22 March 2024 to 10 April 2024.")))
        assertEquals(listOf("22 March 2024", "10 April 2024"), events.map { it.label })
        assertEquals(events[0].sentence, events[1].sentence)
    }

    @Test fun theSameDateWrittenTwiceInOneSentenceIsOneEvent() {
        val events = CaseTimeline.extract(listOf(doc("a.txt", "On 2024-03-22 (22 March 2024) goods arrived.")))
        assertEquals(1, events.size)
    }
}
