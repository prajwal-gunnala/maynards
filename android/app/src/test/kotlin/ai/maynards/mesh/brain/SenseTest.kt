package ai.maynards.mesh.brain

import ai.maynards.mesh.brain.SenseEvent.Kind
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SenseTest {
    @Before fun reset() = Sense.clear()

    @Test fun burstOfTheSameEventBecomesOneLineWithACount() {
        repeat(5) { Sense.pulse("Vivo", "computing", Kind.WORK, now = 1_000L + it * 100) }
        val e = Sense.events.value.single()
        assertEquals(5, e.count)
        assertEquals(1_000L, e.firstAt)
        assertEquals(1_400L, e.at)
    }

    @Test fun helperWorkWithPausesUnder15sStaysOneLine() {
        listOf(0L, 4_000L, 13_000L, 20_000L).forEach { Sense.pulse("Vivo", "computing", Kind.WORK, now = it) }
        assertEquals(listOf(4), Sense.events.value.map { it.count })
    }

    @Test fun aPauseOrAnotherEventStartsANewLine() {
        Sense.pulse("Vivo", "computing", Kind.WORK, now = 0)
        Sense.pulse("Vivo", "computing", Kind.WORK, now = 16_000)      // > 15 s later
        Sense.add("Host", "Answer", Kind.ASK, now = 16_100)
        Sense.pulse("Vivo", "computing", Kind.WORK, now = 16_200)      // after a different event
        assertEquals(listOf(1, 1, 1, 1), Sense.events.value.map { it.count })
    }

    @Test fun twoHelpersWorkingTogetherStayTwoLines() {
        repeat(10) {
            Sense.pulse("Phone A", "computing its layers", Kind.WORK, now = it * 50L)
            Sense.pulse("Phone B", "computing its layers", Kind.WORK, now = it * 50L + 10)
        }
        assertEquals(listOf("Phone A" to 10, "Phone B" to 10), Sense.events.value.map { it.who to it.count })
    }

    @Test fun anOutOfOrderClockDoesNotMergeBackwards() {
        Sense.pulse("Vivo", "computing", Kind.WORK, now = 30_000)
        Sense.pulse("Vivo", "computing", Kind.WORK, now = 9_000)      // clock moved back: new line, not merged
        assertEquals(2, Sense.events.value.size)
    }

    @Test fun onlyTheLast80AreKept() {
        repeat(100) { Sense.add("Host", "e$it", Kind.MODEL, now = it.toLong()) }
        assertEquals(80, Sense.events.value.size)
        assertEquals("e99", Sense.events.value.last().text)
    }
}
