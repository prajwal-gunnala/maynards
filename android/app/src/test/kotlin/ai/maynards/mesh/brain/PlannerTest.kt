package ai.maynards.mesh.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private val GB = 1_000_000_000L

    /** Shaped like Qwen3-Coder-30B-A3B Q4_K_M: 48 blocks of ~378 MB, 430 MB shared, 98 KB KV/token. */
    private val coder30b = ModelInfo("coder.gguf", "Qwen3-Coder-30B", "qwen3moe", 18_556_689_568L,
        List(48) { 377_600_000L }, 430_000_000L, 98_304L, 262_144)

    /** Shaped like Qwen3-0.6B Q8_0. */
    private val small = ModelInfo("small.gguf", "Qwen3-0.6B", "qwen3", 639_446_688L,
        List(28) { 16_720_896L }, 165_310_464L, 114_688L, 40_960)

    private fun host(gb: Double) = Device("host", "iQOO A", (gb * GB).toLong(), isHost = true)
    private fun helper(id: String, gb: Double, rtt: Double = 5.0) = Device(id, "iQOO $id", (gb * GB).toLong(), rttMs = rtt)

    @Test fun smallModelNeverSplits() {
        val p = Planner.plan(small, listOf(host(6.0), helper("b", 9.0)))
        assertEquals(Verdict.DOABLE, p.verdict)
        assertFalse(p.split)
        assertEquals("not needed", p.skipped["b"])
    }

    @Test fun bigModelNeedsBothPhones() {
        val p = Planner.plan(coder30b, listOf(host(10.5), helper("b", 10.5)))
        println("${p.verdict} ${p.reason} ${p.slices}")
        assertTrue(p.split)
        assertEquals(Verdict.TIGHT, p.verdict)
        assertEquals(0, p.slices[0].from)
        assertEquals(p.slices[0].to, p.slices[1].from)          // one unbroken run each
        assertEquals(48, p.slices.last().to)
    }

    @Test fun bigModelOnOnePhoneIsNotPossible() {
        val p = Planner.plan(coder30b, listOf(host(10.5)))
        assertEquals(Verdict.NOT_POSSIBLE, p.verdict)
        assertTrue(p.reason, p.reason.startsWith("Short by"))
    }

    @Test fun laptopAsThirdHelperRescuesLowMemoryPhones() {
        val p = Planner.plan(coder30b, listOf(host(7.0), helper("b", 7.0), helper("laptop", 7.0)))
        assertEquals(3, p.slices.size)
        assertEquals(48, p.slices.last().to)
    }

    @Test fun slowHotAndFlatDevicesAreSkipped() {
        val p = Planner.plan(coder30b, listOf(
            host(10.5),
            helper("slow", 12.0, rtt = 120.0),
            helper("hot", 12.0).copy(heat = 0.97f),
            helper("flat", 12.0).copy(battery = 10, charging = false),
        ))
        assertEquals(Verdict.NOT_POSSIBLE, p.verdict)
        assertTrue(p.skipped["slow"]!!.startsWith("link too slow"))
        assertEquals("too hot", p.skipped["hot"])
        assertEquals("battery 10%", p.skipped["flat"])
    }

    @Test fun argsForTwoHelpersMatchTheVerifiedSplit() {
        // 28 layers: host 4, helpers 11 and 13 -> -ngl 25, tensor split 11/25 and (13+1)/25
        val plan = Plan(small, Verdict.DOABLE, "", listOf(
            Slice("host", "a", 0, 4, 0), Slice("b", "b", 4, 15, 0), Slice("c", "c", 15, 28, 0),
        ), 0, emptyMap())
        val a = EngineArgs.host(plan, "/m.gguf", 4096, 6, listOf("10.0.0.2:50052", "10.0.0.3:50052"))
        val s = a.joinToString(" ")
        assertTrue(s, s.contains("--rpc 10.0.0.2:50052,10.0.0.3:50052 -ngl 25"))
        assertTrue(s, s.contains("--tensor-split 0.440000,0.560000"))
        assertTrue(s, s.contains("--override-tensor ${EngineArgs.HEAD_ON_HOST}"))
    }

    @Test fun argsForOneDeviceKeepEverythingLocal() {
        val p = Planner.plan(small, listOf(host(6.0)))
        val s = EngineArgs.host(p, "/m.gguf", 4096, 6, emptyList()).joinToString(" ")
        assertTrue(s, s.endsWith("-ngl 0"))
        assertFalse(s.contains("--rpc"))
    }
}
