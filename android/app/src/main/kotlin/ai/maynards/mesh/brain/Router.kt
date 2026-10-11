package ai.maynards.mesh.brain

/** What a request is for. The router prefers models suited to it, among those that fit right now. */
enum class Task { CHAT, CASE, CODE, VISION, REASONING, DOCUMENT }

/**
 * Picks the model for a task from the installed models, using the planner's verdict on the devices present.
 * Never downloads, never forces a split, and keeps a running model when it suits the task (a switch is a reload).
 */
object Router {
    data class Choice(val model: ModelInfo, val plan: Plan?, val why: String) {
        /** True when the running model is kept: no plan to start. */
        val keep: Boolean get() = plan == null
    }

    fun suited(task: Task, m: ModelInfo, hasProjector: Boolean): Boolean = when (task) {
        Task.VISION -> hasProjector
        Task.CODE -> isCoder(m)
        Task.CHAT, Task.CASE, Task.DOCUMENT, Task.REASONING -> !isCoder(m)
    }

    /** Within the suited models: reasoning prefers a reasoning model, documents the longest context; then size. */
    private fun rank(task: Task): Comparator<ModelInfo> = when (task) {
        Task.REASONING -> compareByDescending<ModelInfo> { isReasoner(it) }.thenByDescending { it.fileBytes }
        Task.DOCUMENT, Task.CASE -> compareByDescending<ModelInfo> { it.contextMax }.thenByDescending { it.fileBytes }
        else -> compareByDescending { it.fileBytes }
    }

    /**
     * @param running the model already loaded and ready, if any
     * @return the choice, or null when nothing installed fits; [reasonNone] then says why
     */
    fun choose(
        task: Task,
        installed: List<ModelInfo>,
        running: ModelInfo?,
        hasProjector: (ModelInfo) -> Boolean,
        planFor: (ModelInfo) -> Plan,
    ): Choice? {
        val usable = installed.filter { task != Task.VISION || hasProjector(it) }
        val ordered = usable.sortedWith(compareByDescending<ModelInfo> { suited(task, it, hasProjector(it)) }.then(rank(task)))
        val skipped = ArrayList<String>()
        for (m in ordered) {
            val good = suited(task, m, hasProjector(m))
            if (running != null && m.file == running.file)
                return Choice(m, null, "keeps ${m.name} running" + skippedNote(skipped))
            // A running model that suits the task is not replaced by a bigger one of the same kind.
            if (running != null && usable.any { it.file == running.file } && suited(task, running, hasProjector(running)) == good)
                return Choice(running, null, "keeps ${running.name} running" + skippedNote(skipped))
            val plan = planFor(m)
            if (plan.verdict != Verdict.NOT_POSSIBLE && plan.slices.isNotEmpty())
                return Choice(m, plan, "${m.name} for ${task.name.lowercase()}" + skippedNote(skipped))
            skipped += "${m.name}: ${plan.reason.lowercase()}"
        }
        return null
    }

    fun reasonNone(task: Task, installed: List<ModelInfo>, hasProjector: (ModelInfo) -> Boolean): String = when {
        installed.isEmpty() -> "No model installed. Add one in Models."
        task == Task.VISION && installed.none(hasProjector) -> "No vision model with its projector is installed."
        else -> "No installed model fits these devices. Add a device or a smaller model."
    }

    private fun isCoder(m: ModelInfo) = "coder" in (m.name + " " + m.file).lowercase()
    private fun isReasoner(m: ModelInfo) = Regex("r1|qwq|math|thinking|reason").containsMatchIn((m.name + " " + m.file).lowercase())
    private fun skippedNote(s: List<String>) = if (s.isEmpty()) "" else " (${s.joinToString("; ")})"
}
