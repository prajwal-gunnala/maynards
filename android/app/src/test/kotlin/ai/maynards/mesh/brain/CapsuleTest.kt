package ai.maynards.mesh.brain

import ai.maynards.mesh.brain.CapsuleCheck.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleTest {
    private val secret = "pair-secret"
    private val c = Capsule("c1", "run1", "helperB", "Qwen3.5 9B", "Qwen3.5-9B-Q4_K_M.gguf", 5_800_000_000L,
        8, 31, 4096, 6, expiresAtMs = 10_000).signed(secret)

    private fun check(cap: Capsule = c, s: String? = secret, me: String = "helperB", now: Long = 1_000, cores: Int = 8, local: Long? = null) =
        CapsuleCheck.check(cap, s, me, now, cores, local)

    @Test fun validCapsuleIsAppliedAsProposed() {
        val a = (check() as Result.Accept).applied
        assertEquals(8 to 31, a.from to a.to)
        assertEquals(6, a.threads)
        assertTrue(a.notes.isEmpty())
    }

    @Test fun survivesTheWire() {
        val back = Capsule.fromJson(org.json.JSONObject(c.toJson().toString()))
        assertEquals(c, back)
        assertTrue(check(back) is Result.Accept)
        val a = (check() as Result.Accept).applied
        assertEquals(a, Applied.fromJson(org.json.JSONObject(a.toJson().toString())))
    }

    @Test fun tamperingWrongSecretOrMissingSignatureIsRejected() {
        assertTrue(check(c.copy(to = 47)) is Result.Reject)               // changed after signing
        assertTrue(check(s = "other-secret") is Result.Reject)
        assertTrue(check(s = null) is Result.Reject)
        assertTrue(check(c.copy(sig = "")) is Result.Reject)
    }

    @Test fun otherDeviceExpiredBadRangeOrBackendIsRejected() {
        assertEquals("capsule is for another device", (check(me = "helperC") as Result.Reject).reason)
        assertEquals("capsule expired", (check(now = 20_000) as Result.Reject).reason)
        assertTrue((check(c.copy(from = 9, to = 3).signed(secret)) as Result.Reject).reason.contains("layer range"))
        assertTrue((check(c.copy(backend = "GPU").signed(secret)) as Result.Reject).reason.contains("GPU"))
        assertTrue((check(c.copy(version = 2).signed(secret)) as Result.Reject).reason.contains("version"))
    }

    @Test fun differencesFromTheProposalAreReported() {
        val a = (check(c.copy(threads = 12).signed(secret), cores = 8, local = 1L) as Result.Accept).applied
        assertEquals(6, a.threads)
        assertEquals("differs", a.localFile)
        assertEquals(2, a.notes.size)
        assertEquals("matches", (check(local = 5_800_000_000L) as Result.Accept).applied.localFile)
        assertEquals(6, (check(c.copy(threads = 0).signed(secret)) as Result.Accept).applied.threads)
    }
}
