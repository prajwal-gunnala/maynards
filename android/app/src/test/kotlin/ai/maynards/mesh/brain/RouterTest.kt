package ai.maynards.mesh.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {
    private val GB = 1_000_000_000L
    private fun model(name: String, gb: Double) = ModelInfo("$name.gguf", name, "qwen3", (gb * GB).toLong(),
        List(32) { (gb * GB / 33).toLong() }, (gb * GB / 33).toLong(), 100_000L, 32_768)
    private val coder30 = model("Qwen3-Coder-30B", 18.6)
    private val coder7 = model("Qwen2.5-Coder-7B", 4.7)
    private val qwen9 = model("Qwen3.5-9B", 5.8)
    private val qwen06 = model("Qwen3-0.6B", 0.6)

    /** Plans with a fixed memory pool: a model fits when it is no larger than [pool] GB. */
    private fun planner(pool: Double) = { m: ModelInfo ->
        if (m.fileBytes <= pool * GB) Plan(m, Verdict.DOABLE, "Runs on this phone alone",
            listOf(Slice("host", "This phone", 0, 32, m.fileBytes)), m.fileBytes, emptyMap())
        else Plan(m, Verdict.NOT_POSSIBLE, "Short by %.1f GB".format(java.util.Locale.US, m.fileBytes / 1e9 - pool), emptyList(), m.fileBytes, emptyMap())
    }
    private val noProjector = { _: ModelInfo -> false }

    @Test fun codePrefersACoderThatFitsAndSaysWhyTheBigOneWasSkipped() {
        val c = Router.choose(Task.CODE, listOf(coder30, coder7, qwen9), null, noProjector, planner(10.0))!!
        assertEquals("Qwen2.5-Coder-7B", c.model.name)
        assertTrue(c.why, c.why.contains("Qwen3-Coder-30B: short by 8.6 gb"))
    }

    @Test fun codeFallsBackToAGeneralModelWhenNoCoderFits() {
        val c = Router.choose(Task.CODE, listOf(coder30, qwen9), null, noProjector, planner(10.0))!!
        assertEquals("Qwen3.5-9B", c.model.name)
    }

    @Test fun caseAndChatPreferGeneralModelsOverCoders() {
        val c = Router.choose(Task.CASE, listOf(coder7, qwen9), null, noProjector, planner(10.0))!!
        assertEquals("Qwen3.5-9B", c.model.name)
    }

    @Test fun biggestSuitedModelThatFitsWins() {
        val c = Router.choose(Task.CHAT, listOf(qwen06, qwen9), null, noProjector, planner(10.0))!!
        assertEquals("Qwen3.5-9B", c.model.name)
    }

    @Test fun visionOnlyConsidersModelsWithAProjector() {
        val withProj = { m: ModelInfo -> m.name == "Qwen3.5-9B" }
        assertEquals("Qwen3.5-9B", Router.choose(Task.VISION, listOf(coder7, qwen9, qwen06), null, withProj, planner(10.0))!!.model.name)
        assertNull(Router.choose(Task.VISION, listOf(coder7, qwen06), null, noProjector, planner(10.0)))
        assertTrue(Router.reasonNone(Task.VISION, listOf(coder7), noProjector).contains("projector"))
    }

    @Test fun aSuitedRunningModelIsKeptNotReloaded() {
        val c = Router.choose(Task.CHAT, listOf(qwen06, qwen9), qwen06, noProjector, planner(10.0))!!
        assertTrue(c.keep)
        assertEquals("Qwen3-0.6B", c.model.name)
    }

    @Test fun aRunningModelOfTheWrongKindIsReplacedWhenABetterOneFits() {
        val c = Router.choose(Task.CODE, listOf(coder7, qwen9), qwen9, noProjector, planner(10.0))!!
        assertEquals("Qwen2.5-Coder-7B", c.model.name)
        assertTrue(!c.keep)
    }

    @Test fun aRunningModelOfTheWrongKindIsKeptWhenNothingBetterFits() {
        val c = Router.choose(Task.CODE, listOf(coder30, qwen9), qwen9, noProjector, planner(10.0))!!
        assertTrue(c.keep)
        assertTrue(c.why, c.why.contains("Qwen3-Coder-30B: short by"))
    }

    @Test fun reasoningPrefersAReasoningModelAndDocumentsTheLongestContext() {
        val r1 = model("DeepSeek-R1-Distill-7B", 4.7)
        assertEquals("DeepSeek-R1-Distill-7B", Router.choose(Task.REASONING, listOf(qwen9, r1), null, noProjector, planner(10.0))!!.model.name)
        val longCtx = ModelInfo("long.gguf", "Long-4B", "qwen3", 2_500_000_000L, List(32) { 70_000_000L }, 60_000_000L, 100_000L, 262_144)
        assertEquals("Long-4B", Router.choose(Task.DOCUMENT, listOf(qwen9, longCtx), null, noProjector, planner(10.0))!!.model.name)
    }

    @Test fun nothingFitsGivesNullAndAReason() {
        assertNull(Router.choose(Task.CHAT, listOf(coder30), null, noProjector, planner(4.0)))
        assertTrue(Router.reasonNone(Task.CHAT, listOf(coder30), noProjector).contains("fits"))
        assertTrue(Router.reasonNone(Task.CHAT, emptyList(), noProjector).contains("No model installed"))
    }
}
