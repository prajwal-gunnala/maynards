package ai.maynards.mesh.brain

import ai.maynards.mesh.brain.Preflight.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightTest {
    private val GB = 1_000_000_000L
    private val qwen35 = ModelInfo("qwen35.gguf", "Qwen3.5-9B", "qwen35", 6_700_000_000L,
        List(32) { 170_000_000L }, 450_000_000L,
        8L * 8 * (256 + 256) * 2, 262_144,
        recurrentLayers = List(32) { it % 4 != 3 }, recurrentStateBytesPerLayer = 2_000_000L)

    private fun host(gb: Double) = Device("host", "This phone", (gb * GB).toLong(), isHost = true)
    private fun helper(gb: Double, rtt: Double = 5.0, battery: Int = 80, charging: Boolean = true) =
        Device("b", "Vivo", (gb * GB).toLong(), rttMs = rtt, battery = battery, charging = charging)

    private val splitPlan = Planner.plan(qwen35, listOf(host(3.0), helper(7.0)))

    @Test fun planStillValidGoesUnchanged() {
        assertTrue(splitPlan.split)
        val r = Preflight.check(splitPlan, listOf(host(3.0), helper(7.0)), qwen35.fileBytes)
        assertEquals(Result.Go(splitPlan), r)
        assertNull((r as Result.Go).note)
    }

    @Test fun missingOrChangedFileStops() {
        assertTrue((Preflight.check(splitPlan, listOf(host(3.0), helper(7.0)), null) as Result.Stop).reason.contains("missing"))
        val changed = Preflight.check(splitPlan, listOf(host(3.0), helper(7.0)), qwen35.fileBytes - 1)
        assertTrue((changed as Result.Stop).reason.contains("changed"))
    }

    @Test fun helperGoneAndModelCannotFitStopsWithReason() {
        val r = Preflight.check(splitPlan, listOf(host(3.0)), qwen35.fileBytes)
        val reason = (r as Result.Stop).reason
        assertTrue(reason, reason.contains("Vivo left"))
        assertTrue(reason, reason.contains("Short by"))
    }

    @Test fun helperGoneButHostNowFitsReplansToOnePhone() {
        val r = Preflight.check(splitPlan, listOf(host(9.0)), qwen35.fileBytes) as Result.Go
        assertFalse(r.plan.split)
        assertTrue(r.note!!, r.note!!.contains("Vivo left"))
    }

    @Test fun moreFreeMemoryOnHostMeansNoSplit() {
        val r = Preflight.check(splitPlan, listOf(host(9.0), helper(7.0)), qwen35.fileBytes) as Result.Go
        assertFalse(r.plan.split)
        assertTrue(r.note!!, r.note!!.contains("fits on this phone alone now"))
    }

    @Test fun slowLinkOrFlatBatteryIsCaught() {
        val slow = Preflight.check(splitPlan, listOf(host(3.0), helper(7.0, rtt = 120.0)), qwen35.fileBytes)
        assertTrue((slow as Result.Stop).reason.contains("link slow (120 ms)"))
        val flat = Preflight.check(splitPlan, listOf(host(3.0), helper(7.0, battery = 10, charging = false)), qwen35.fileBytes)
        assertTrue((flat as Result.Stop).reason.contains("battery 10%"))
    }

    @Test fun helperMemoryShrankIsReplanned() {
        val share = splitPlan.helpers.single().bytes + Planner.HELPER_RESERVE
        val r = Preflight.check(splitPlan, listOf(host(3.0), helper((share - 200_000_000L) / 1e9)), qwen35.fileBytes)
        when (r) {
            is Result.Go -> { assertTrue(r.note!!, r.note!!.contains("Vivo has")); assertEquals(32, r.plan.slices.last().to) }
            is Result.Stop -> assertTrue(r.reason, r.reason.contains("Vivo has"))
        }
    }
}
