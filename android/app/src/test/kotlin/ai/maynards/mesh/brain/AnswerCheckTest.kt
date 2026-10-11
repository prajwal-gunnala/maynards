package ai.maynards.mesh.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerCheckTest {
    private fun item(id: String) = AnswerCheck.items.first { it.id == id }

    @Test fun rightAnswersPassInTheirUsualShapes() {
        assertTrue(AnswerCheck.correct(item("multiply"), "391"))
        assertTrue(AnswerCheck.correct(item("multiply"), "17 × 23 = **391**."))
        assertTrue(AnswerCheck.correct(item("capital"), "Canberra"))
        assertTrue(AnswerCheck.correct(item("reverse"), "\"hsem\""))
        assertTrue(AnswerCheck.correct(item("prime"), "Yes, 97 is prime."))
        assertTrue(AnswerCheck.correct(item("compare"), "0.9"))
        assertTrue(AnswerCheck.correct(item("cited"), "The meeting is on Thursday at 3 PM [S01]."))
    }

    @Test fun nearMissesFail() {
        assertFalse(AnswerCheck.correct(item("multiply"), "3910"))
        assertFalse(AnswerCheck.correct(item("multiply"), "39.1"))
        assertFalse(AnswerCheck.correct(item("capital"), "Sydney"))
        assertFalse(AnswerCheck.correct(item("prime"), "No"))
        assertFalse(AnswerCheck.correct(item("compare"), "0.11"))
        assertFalse(AnswerCheck.correct(item("python-len"), "40"))
        assertFalse(AnswerCheck.correct(item("cited"), "Thursday at 3 PM."))          // no citation
        assertFalse(AnswerCheck.correct(item("multiply"), "<think>391</think>I think 390"))
    }

    @Test fun scoreCountsOnlyCorrectReplies() {
        assertEquals(2, AnswerCheck.score(mapOf("multiply" to "391", "capital" to "Canberra", "prime" to "no")))
        assertEquals(8, AnswerCheck.items.size)
    }

    @Test fun aFasterSettingThatGetsFewerRightIsNotAdopted() {
        val speed = mapOf(6 to List(3) { Compiler.Sample(7.0, 0.0) }, 4 to List(3) { Compiler.Sample(9.0, 0.0) })
        val d = Compiler.decide(6, speed, correct = mapOf(6 to 8, 4 to 7))
        assertFalse(d.changed)
        assertTrue(d.why, d.why.contains("fewer correct"))
        assertTrue(Compiler.decide(6, speed, correct = mapOf(6 to 8, 4 to 8)).changed)
    }
}
