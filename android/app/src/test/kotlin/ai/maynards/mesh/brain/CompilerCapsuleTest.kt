package ai.maynards.mesh.brain

import ai.maynards.mesh.brain.CompilerCapsule.Use
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilerCapsuleTest {
    private fun cap(task: String, scope: String, engine: String, ctk: String = "q8_0", ngl: Int = 0, bytes: Long = 2_497_281_120L) =
        CompilerCapsule.parse("$task-$scope.json", JSONObject("""{"kind":"ned-capsule","version":1,"device":"I2501",
            "model_file":"Qwen3-4B.gguf","model_bytes":$bytes,"task":"$task","scope":"$scope","engine_build":"66fba63",
            "enact":{"engine":"$engine","threads":6,"ctk":"$ctk","ctv":"$ctk","ub":512,"b":2048,"fa":"on","ngl":$ngl},
            "evidence":{"run_ids":[11,12,50],"quality":"14/15","task_seconds":40.47,"baseline_task_seconds":95.7,"is_baseline":false}}"""))!!

    private val gpuAgent = cap("agent", "any", "opencl", ctk = "f16", ngl = 36)
    private val cpuAgent = cap("agent", "cpu", "cpu")
    private val cpuChat = cap("chat", "any", "cpu")

    @Test fun parsesTheExporterFormat() {
        assertEquals("opencl", gpuAgent.engine)
        assertEquals(listOf(11, 12, 50), gpuAgent.runIds)
        assertEquals("14/15", gpuAgent.quality)
    }

    @Test fun aCpuCapsuleIsPreferredOverOneThisBuildCannotRun() {
        val u = CompilerCapsule.choose(listOf(gpuAgent, cpuAgent), "i2501", "Qwen3-4B.gguf", 2_497_281_120L, "agent")
        assertTrue(u is Use.Apply)
        assertEquals(Tuning(6, "q8_0", "q8_0", 512, 2048, "on"), (u as Use.Apply).tuning)
    }

    @Test fun onlyAGpuCapsuleIsRefusedWithTheReason() {
        val u = CompilerCapsule.choose(listOf(gpuAgent), "I2501", "Qwen3-4B.gguf", 2_497_281_120L, "agent")
        assertTrue((u as Use.Refuse).reason.contains("CPU only"))
    }

    @Test fun otherPhoneModelOrTaskHasNoCapsule() {
        assertNull(CompilerCapsule.choose(listOf(cpuChat), "SM-S918B", "Qwen3-4B.gguf", 2_497_281_120L, "chat"))
        assertNull(CompilerCapsule.choose(listOf(cpuChat), "I2501", "Qwen3.5-9B.gguf", 1L, "chat"))
        assertNull(CompilerCapsule.choose(listOf(cpuChat), "I2501", "Qwen3-4B.gguf", 2_497_281_120L, "agent"))
    }

    @Test fun aDifferentFileCopyOrUnknownValuesAreNotApplied() {
        assertTrue((CompilerCapsule.check(cap("chat", "any", "cpu", bytes = 5), 2_497_281_120L) as Use.Refuse).reason.contains("different copy"))
        val odd = CompilerCapsule.check(cap("chat", "any", "cpu", ctk = "bf99"), 2_497_281_120L) as Use.Apply
        assertNull(odd.tuning.ctk)
    }

    @Test fun tasksMapToCapsuleTasks() {
        assertEquals("agent", CompilerCapsule.taskFor(Task.CODE))
        assertEquals("agent", CompilerCapsule.taskFor(Task.CASE))
        assertEquals("chat", CompilerCapsule.taskFor(Task.CHAT))
        assertEquals("chat", CompilerCapsule.taskFor(null))
    }
}
