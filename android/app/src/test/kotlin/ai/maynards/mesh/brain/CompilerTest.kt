package ai.maynards.mesh.brain

import ai.maynards.mesh.brain.Compiler.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilerTest {
    private fun s(vararg tps: Double) = tps.map { Sample(it, 30.0) }

    @Test fun candidatesStartWithTheCurrentSettingAndStayAtTwoOrMore() {
        assertEquals(listOf(6, 4), Compiler.candidates(8, 6))
        assertEquals(listOf(3, 2), Compiler.candidates(4, 3).take(2))
        assertTrue(Compiler.candidates(2, 2).all { it >= 2 })
    }

    @Test fun clearlyFasterSettingIsChosen() {
        val d = Compiler.decide(6, mapOf(6 to s(7.0, 7.2, 7.1), 4 to s(8.0, 8.1, 7.9)))
        assertTrue(d.changed)
        assertEquals(4, d.threads)
        assertTrue(d.why, d.why.contains("+13%"))
    }

    @Test fun gainInsideTheNoiseKeepsTheBaseline() {
        val d = Compiler.decide(6, mapOf(6 to s(7.0, 7.1, 7.2), 4 to s(7.2, 7.3, 7.3)))
        assertFalse(d.changed)
        assertEquals(6, d.threads)
    }

    @Test fun aCandidateWithAFailureOrTooFewRunsIsNeverChosen() {
        val failed = mapOf(6 to s(7.0, 7.0, 7.0), 4 to listOf(Sample(9.0, 30.0), Sample(9.0, 30.0), Sample(0.0, 0.0, ok = false)))
        assertFalse(Compiler.decide(6, failed).changed)
        val few = mapOf(6 to s(7.0, 7.0, 7.0), 4 to s(9.0))
        assertFalse(Compiler.decide(6, few).changed)
    }

    @Test fun medianIgnoresOneOutlier() {
        assertEquals(7.1, Compiler.median(listOf(7.0, 7.1, 30.0)), 1e-9)
        assertEquals(7.05, Compiler.median(listOf(7.0, 7.1)), 1e-9)
        assertEquals(0.0, Compiler.median(emptyList()), 0.0)
    }

    @Test fun unmeasuredOrFailedBaselineIsKept() {
        assertFalse(Compiler.decide(6, mapOf(4 to s(9.0, 9.0, 9.0))).changed)
        assertFalse(Compiler.decide(6, mapOf(6 to listOf(Sample(0.0, 0.0, false)), 4 to s(9.0, 9.0, 9.0))).changed)
    }

    @Test fun learningSamplesEachSettingBeforeJudging() {
        assertEquals(6, Compiler.nextThreads(emptyMap(), listOf(6, 4), current = 6, baseline = 6))
        assertEquals(6, Compiler.nextThreads(mapOf(6 to listOf(7.0, 7.0)), listOf(6, 4), current = 6, baseline = 6))
        assertEquals(4, Compiler.nextThreads(mapOf(6 to listOf(7.0, 7.0, 7.1)), listOf(6, 4), current = 6, baseline = 6))
    }

    @Test fun learningKeepsTheWinnerOnceEveryoneHasThreeAnswers() {
        val faster4 = mapOf(6 to listOf(7.0, 7.1, 7.0), 4 to listOf(8.0, 7.9, 8.1))
        assertEquals(4, Compiler.nextThreads(faster4, listOf(6, 4), current = 4, baseline = 6))
        assertTrue(Compiler.learn(faster4, 6).changed)
        val noise = mapOf(6 to listOf(7.0, 7.1, 7.0), 4 to listOf(7.2, 7.1, 7.2))
        assertEquals(6, Compiler.nextThreads(noise, listOf(6, 4), current = 4, baseline = 6))
    }
}
