package ai.maynards.mesh.brain

import org.json.JSONArray
import org.json.JSONObject

data class CapsuleRuntime(
    val backend: String = "cpu",
    val threads: Int = 4,
    val kvType: String = "q8_0",
    val ubatch: Int = 512,
    val cachePrompt: Boolean = true
) {
    fun toJSON() = JSONObject().apply {
        put("backend", backend)
        put("threads", threads)
        put("kv_type", kvType)
        put("ubatch", ubatch)
        put("cache_prompt", cachePrompt)
    }

    companion object {
        fun fromJSON(obj: JSONObject?): CapsuleRuntime {
            if (obj == null) return CapsuleRuntime()
            return CapsuleRuntime(
                backend = obj.optString("backend", "cpu"),
                threads = obj.optInt("threads", 4),
                kvType = obj.optString("kv_type", "q8_0"),
                ubatch = obj.optInt("ubatch", 512),
                cachePrompt = obj.optBoolean("cache_prompt", true)
            )
        }
    }
}

data class CapsuleEvidence(
    val runIds: List<Int> = emptyList(),
    val gate: String = "14/15"
) {
    fun toJSON() = JSONObject().apply {
        put("run_ids", JSONArray(runIds))
        put("gate", gate)
    }

    companion object {
        fun fromJSON(obj: JSONObject?): CapsuleEvidence {
            if (obj == null) return CapsuleEvidence()
            val ids = mutableListOf<Int>()
            obj.optJSONArray("run_ids")?.let { arr ->
                for (i in 0 until arr.length()) ids.add(arr.optInt(i))
            }
            return CapsuleEvidence(
                runIds = ids,
                gate = obj.optString("gate", "14/15")
            )
        }
    }
}

data class NedCapsule(
    val capsuleId: String,
    val issuedAt: Long = System.currentTimeMillis() / 1000,
    val modelName: String,
    val modelFile: String = "",
    val role: String = "helper",
    val layersFrom: Int,
    val layersTo: Int,
    val runtime: CapsuleRuntime = CapsuleRuntime(),
    val limits: Map<String, Any> = emptyMap(),
    val evidence: CapsuleEvidence = CapsuleEvidence(),
    val why: String = ""
) {
    val layersRangeString: String get() = "$layersFrom-$layersTo"

    fun toJSON(): JSONObject = JSONObject().apply {
        put("t", "capsule")
        put("v", 1)
        put("capsule_id", capsuleId)
        put("issued_at", issuedAt)
        put("role", role)
        put("why", why)
        // Dual-compatibility top-level fields
        put("layers", layersRangeString)
        put("model", modelName)
        put("model_info", JSONObject().apply {
            put("name", modelName)
            put("file", modelFile)
        })
        put("layers_slice", JSONObject().apply {
            put("from", layersFrom)
            put("to", layersTo)
        })
        put("runtime", runtime.toJSON())
        put("evidence", evidence.toJSON())
    }

    companion object {
        fun fromJSON(obj: JSONObject): NedCapsule {
            val cid = obj.optString("capsule_id").ifBlank { "legacy-" + System.currentTimeMillis() }
            val issued = obj.optLong("issued_at", System.currentTimeMillis() / 1000)
            val role = obj.optString("role", "helper")
            val why = obj.optString("why", "")

            // Model can be string or object
            val modelName = when {
                obj.has("model_info") -> obj.optJSONObject("model_info")?.optString("name") ?: ""
                obj.has("model") -> {
                    val m = obj.get("model")
                    if (m is JSONObject) m.optString("name") else m.toString()
                }
                else -> ""
            }
            val modelFile = obj.optJSONObject("model_info")?.optString("file")
                ?: (obj.opt("model") as? JSONObject)?.optString("file") ?: ""

            // Layers can be object {from, to} or string "9-27"
            var from = 0
            var to = 0
            if (obj.has("layers_slice")) {
                val ls = obj.getJSONObject("layers_slice")
                from = ls.optInt("from", 0)
                to = ls.optInt("to", 0)
            } else if (obj.has("layers")) {
                val lRaw = obj.opt("layers")
                if (lRaw is JSONObject) {
                    from = lRaw.optInt("from", 0)
                    to = lRaw.optInt("to", 0)
                } else {
                    val parts = lRaw.toString().split("-")
                    from = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    to = parts.getOrNull(1)?.toIntOrNull() ?: from
                }
            }

            val runtime = CapsuleRuntime.fromJSON(obj.optJSONObject("runtime"))
            val evidence = CapsuleEvidence.fromJSON(obj.optJSONObject("evidence"))

            return NedCapsule(
                capsuleId = cid,
                issuedAt = issued,
                modelName = modelName,
                modelFile = modelFile,
                role = role,
                layersFrom = from,
                layersTo = to,
                runtime = runtime,
                evidence = evidence,
                why = why
            )
        }
    }
}
