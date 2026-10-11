package ai.maynards.mesh.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDetectorTest {
    private fun task(s: String, img: Boolean = false) = TaskDetector.detect(s, img).task

    @Test fun photoIsVision() = assertEquals(Task.VISION, task("what is this?", img = true))

    @Test fun codeIsFoundByShapeOrTwoWords() {
        assertEquals(Task.CODE, task("why does this fail?\n```kotlin\nval x = 1\n```"))
        assertEquals(Task.CODE, task("java.lang.NullPointerException\n    at ai.maynards.Main.run(Main.kt:42)"))
        assertEquals(Task.CODE, task("fun main() {\n  println(1)\n}"))
        assertEquals(Task.CODE, task("refactor this python function"))
        val d = TaskDetector.detect("Traceback (most recent call last):\n  File \"a.py\"")
        assertTrue(d.why, d.why.contains("stack trace"))
    }

    @Test fun aBareErrorNameIsCode() {
        assertEquals(Task.CODE, task("java.lang.NullPointerException"))
        assertEquals(Task.CODE, task("I get IndexOutOfBoundsException when I scroll"))
        assertEquals(Task.CODE, task("kotlinx.coroutines.JobCancellationException"))
    }

    @Test fun oneCodeishWordAloneStaysGeneral() {
        assertEquals(Task.CHAT, task("what does the code of conduct say about breaks"))
        assertEquals(Task.CHAT, task("tell me a joke"))
    }

    @Test fun documentsAndLongTextAreDocumentWork() {
        assertEquals(Task.DOCUMENT, task("summarize the meeting notes below"))
        assertEquals(Task.DOCUMENT, task("does this clause allow early termination?"))
        assertEquals(Task.DOCUMENT, task("word ".repeat(600)))
    }

    @Test fun mathIsReasoning() {
        assertEquals(Task.REASONING, task("solve 3x + 4 = 19"))
        assertEquals(Task.REASONING, task("what is 17 * 23"))
        assertEquals(Task.REASONING, task("what's the probability of two sixes"))
    }

    @Test fun everyResultSaysWhy() {
        listOf("hi", "summarize this", "solve x^2 = 4", "fix this bug in my kotlin code").forEach {
            assertTrue(it, TaskDetector.detect(it).why.isNotBlank())
        }
    }
}
