package ai.maynards.mesh.brain

import java.util.Locale

/** Turns a plan into llama-server arguments (the engine build is pinned, so every flag here exists). */
object EngineArgs {
    /** Embeddings, output head and final norm stay on the Host, so one small activation crosses per token. */
    const val HEAD_ON_HOST = "^(output|output_norm|token_embd)\\.(weight|bias)$=CPU"

    /**
     * @param helperAddrs "ip:port" of each helper, in the same order as [Plan.helpers]
     */
    fun host(plan: Plan, modelPath: String, ctx: Int, threads: Int, helperAddrs: List<String>): List<String> {
        require(helperAddrs.size == plan.helpers.size) { "one address per helper" }
        val a = mutableListOf(
            "-m", modelPath, "-c", "$ctx", "-t", "$threads",
            "--jinja", "--fit", "off", "--reasoning", "off",
            "-ctk", "q8_0", "-ctv", "q8_0", "-fa", "on",
        )
        if (!plan.split) {
            a += listOf("-ngl", "0")
            return a
        }
        // llama.cpp offloads the last N layers and counts the output head as one more;
        // offload it with them, then pin it back to the Host.
        val offloaded = plan.helpers.sumOf { it.count }
        a += listOf("--rpc", helperAddrs.joinToString(","), "-ngl", "${offloaded + 1}", "--override-tensor", HEAD_ON_HOST)
        if (plan.helpers.size > 1) {
            val total = (offloaded + 1).toDouble()
            val parts = plan.helpers.mapIndexed { i, s ->
                val n = s.count + if (i == plan.helpers.lastIndex) 1 else 0
                String.format(Locale.US, "%.6f", n / total)
            }
            a += listOf("--tensor-split", parts.joinToString(","))
        }
        return a
    }
}
