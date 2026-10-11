package ai.maynards.mesh.brain

import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Ned Capsule: the Host's signed, versioned work order for one Helper in one run. It carries settings, never
 * weights or prompts. The Helper checks it, applies what it can, and reports what it actually applied, so the
 * Host can show proposed vs applied per device. Signed with the pair's secret: a capsule copied from another
 * mesh, Helper or run does not verify.
 */
data class Capsule(
    val id: String,
    val run: String,          // one id per Runner.run, shared by the run's capsules
    val helper: String,       // the Helper's device id
    val model: String,
    val modelFile: String,
    val modelBytes: Long,
    val from: Int,            // first layer, inclusive
    val to: Int,              // last layer, inclusive
    val ctx: Int,
    val threads: Int,         // proposed; 0 = Helper decides
    val backend: String = "CPU",
    val expiresAtMs: Long,
    val version: Int = VERSION,
    val sig: String = "",
) {
    fun body(): String = listOf(version, id, run, helper, model, modelFile, modelBytes, from, to, ctx, threads, backend, expiresAtMs)
        .joinToString("|")

    fun signed(secret: String) = copy(sig = hmac(secret, body()))

    fun toJson(): JSONObject = JSONObject().put("v", version).put("id", id).put("run", run).put("helper", helper)
        .put("model", model).put("model_file", modelFile).put("model_bytes", modelBytes).put("from", from).put("to", to)
        .put("ctx", ctx).put("threads", threads).put("backend", backend).put("expires", expiresAtMs).put("sig", sig)

    companion object {
        const val VERSION = 1
        const val LIFETIME_MS = 10 * 60_000L

        fun fromJson(j: JSONObject) = Capsule(j.getString("id"), j.getString("run"), j.getString("helper"),
            j.optString("model"), j.optString("model_file"), j.optLong("model_bytes"), j.getInt("from"), j.getInt("to"),
            j.optInt("ctx"), j.optInt("threads"), j.optString("backend", "CPU"), j.getLong("expires"), j.optInt("v"), j.optString("sig"))

        internal fun hmac(secret: String, body: String): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

/** What a Helper actually did with a capsule. */
data class Applied(val capsule: String, val from: Int, val to: Int, val threads: Int, val backend: String,
                   val localFile: String, val notes: List<String> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("capsule", capsule).put("from", from).put("to", to).put("threads", threads)
        .put("backend", backend).put("local_file", localFile).put("notes", org.json.JSONArray(notes))

    companion object {
        fun fromJson(j: JSONObject) = Applied(j.optString("capsule"), j.optInt("from"), j.optInt("to"), j.optInt("threads"),
            j.optString("backend"), j.optString("local_file"),
            j.optJSONArray("notes")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList())
    }
}

object CapsuleCheck {
    sealed class Result {
        data class Accept(val applied: Applied) : Result()
        data class Reject(val reason: String) : Result()
    }

    /**
     * Helper side. Rejects a capsule that is unsigned or forged, for another device, expired, of an unknown
     * version, or with an impossible layer range. Otherwise applies it, clamping threads to this phone's cores,
     * and records every difference from what was proposed.
     */
    fun check(c: Capsule, secret: String?, me: String, now: Long, cores: Int, localFileBytes: Long?): Result {
        if (secret.isNullOrBlank() || c.sig.isBlank() || !same(c.sig, Capsule.hmac(secret, c.body())))
            return Result.Reject("capsule signature does not match this pairing")
        if (c.version != Capsule.VERSION) return Result.Reject("capsule version ${c.version} not supported")
        if (c.helper != me) return Result.Reject("capsule is for another device")
        if (now > c.expiresAtMs) return Result.Reject("capsule expired")
        if (c.from < 0 || c.to < c.from) return Result.Reject("invalid layer range ${c.from}–${c.to}")
        if (c.backend != "CPU") return Result.Reject("backend ${c.backend} not available")
        val notes = ArrayList<String>()
        val most = (cores - 2).coerceAtLeast(2)
        val threads = when {
            c.threads <= 0 -> most
            c.threads > most -> most.also { notes += "threads ${c.threads} → $most (this phone has $cores cores)" }
            else -> c.threads
        }
        val local = when {
            localFileBytes == null -> "none"
            localFileBytes == c.modelBytes -> "matches"
            else -> "differs".also { notes += "local model file differs in size; layers come from the Host" }
        }
        return Result.Accept(Applied(c.id, c.from, c.to, threads, "CPU", local, notes))
    }

    private fun same(a: String, b: String): Boolean =
        a.length == b.length && a.indices.fold(0) { acc, i -> acc or (a[i].code xor b[i].code) } == 0
}
