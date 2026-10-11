package ai.maynards.mesh.brain

import org.json.JSONObject
import java.io.File

/** Engine settings a capsule may set on llama-server. Anything else in a capsule is evidence or a check. */
data class Tuning(val threads: Int? = null, val ctk: String? = null, val ctv: String? = null, val ub: Int? = null,
                  val b: Int? = null, val fa: String? = null)

/**
 * A Ned Capsule file written by the Mesh Compiler (scripts/export-capsules.py writes JSON files into the app folder files/capsules): the
 * measured best settings for one phone model, model file and task, with the evidence behind them.
 */
data class CompilerCapsule(
    val file: String, val device: String, val modelFile: String, val modelBytes: Long, val task: String, val scope: String,
    val engine: String, val enact: JSONObject, val runIds: List<Int>, val quality: String?, val taskSeconds: Double,
    val baselineSeconds: Double, val isBaseline: Boolean, val engineBuild: String,
) {
    sealed class Use {
        data class Apply(val capsule: CompilerCapsule, val tuning: Tuning) : Use()
        data class Refuse(val capsule: CompilerCapsule, val reason: String) : Use()
    }

    companion object {
        const val ENGINE_BUILD = "66fba63"
        private val CACHE = setOf("f16", "q8_0", "q4_0")

        fun parse(name: String, j: JSONObject): CompilerCapsule? = runCatching {
            if (j.optString("kind") != "ned-capsule" || j.optInt("version") != 1) return null
            val ev = j.optJSONObject("evidence") ?: JSONObject()
            val enact = j.getJSONObject("enact")
            CompilerCapsule(name, j.getString("device"), j.getString("model_file"), j.optLong("model_bytes"),
                j.getString("task"), j.optString("scope", "any"), enact.optString("engine", "cpu"), enact,
                ev.optJSONArray("run_ids")?.let { a -> List(a.length()) { a.getInt(it) } } ?: emptyList(),
                ev.optString("quality").takeIf { it.isNotBlank() && it != "null" },
                ev.optDouble("task_seconds", 0.0), ev.optDouble("baseline_task_seconds", 0.0),
                ev.optBoolean("is_baseline"), j.optString("engine_build"))
        }.getOrNull()

        fun load(dir: File): List<CompilerCapsule> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { parse(f.name, JSONObject(f.readText())) }.getOrNull() }

        /** Which capsule task a request belongs to: long-input work is "agent", short turns are "chat". */
        fun taskFor(t: Task?): String = when (t) {
            Task.CODE, Task.DOCUMENT, Task.CASE -> "agent"
            else -> "chat"
        }

        /**
         * The capsule for this phone, model and task. Prefers one this CPU-only build can run; if only a
         * capsule it cannot run exists, returns that as Refuse with the reason, so the screen can say why.
         */
        fun choose(all: List<CompilerCapsule>, device: String, modelFile: String, modelBytes: Long, task: String): Use? {
            val mine = all.filter { it.device.equals(device, true) && it.modelFile == modelFile && it.task == task }
            if (mine.isEmpty()) return null
            val uses = mine.map { check(it, modelBytes) }
            return uses.firstOrNull { it is Use.Apply } ?: uses.first()
        }

        fun check(c: CompilerCapsule, modelBytes: Long): Use {
            if (c.engine != "cpu") return Use.Refuse(c, "needs the ${c.engine} engine; this app runs on CPU only")
            if (c.modelBytes > 0 && c.modelBytes != modelBytes) return Use.Refuse(c, "measured on a different copy of the model file")
            if (c.engineBuild.isNotBlank() && c.engineBuild != ENGINE_BUILD) return Use.Refuse(c, "measured on engine ${c.engineBuild}, this app has $ENGINE_BUILD")
            if (c.enact.optInt("ngl", 0) > 0) return Use.Refuse(c, "offloads layers to a GPU")
            val e = c.enact
            val t = Tuning(
                threads = e.optInt("threads", 0).takeIf { it in 1..16 },
                ctk = e.optString("ctk").takeIf { it in CACHE },
                ctv = e.optString("ctv").takeIf { it in CACHE },
                ub = e.optInt("ub", 0).takeIf { it in 32..4096 },
                b = e.optInt("b", 0).takeIf { it in 32..8192 },
                fa = e.optString("fa").takeIf { it == "on" || it == "off" },
            )
            return Use.Apply(c, t)
        }
    }
}
