package ai.maynards.mesh.ui

import org.json.JSONArray

/**
 * Chain step 2 and 3 for "find conflicts": the model gets only the numbered timeline events (step 1, found by
 * pattern) and returns pairs as JSON; every pair is checked against the events it was given before it is shown.
 */
object CaseConflicts {
    const val MAX_EVENTS = 40

    data class Conflict(val a: CaseEvent, val b: CaseEvent, val aId: String, val bId: String, val why: String)
    data class Result(val conflicts: List<Conflict>, val dropped: Int, val parsed: Boolean)

    val system = """
        You compare dated statements from legal case documents. Each statement has an ID like E01.
        Report pairs of statements that contradict each other about the same thing (for example two different dates for the same delivery).
        Use only the statements given. Reply with JSON only, no other text: [{"a":"E01","b":"E04","why":"one short sentence"}]
        If none contradict, reply: []
    """.trimIndent()

    fun ids(events: List<CaseEvent>): List<Pair<String, CaseEvent>> =
        events.take(MAX_EVENTS).mapIndexed { i, e -> "E${(i + 1).toString().padStart(2, '0')}" to e }

    fun prompt(events: List<CaseEvent>): String = buildString {
        append("STATEMENTS\n")
        ids(events).forEach { (id, e) -> append("[$id] ${e.label} · ${e.fileName}, ${e.location}: ${e.sentence}\n") }
    }

    /** Reads the model's JSON (ignoring any text or code fence around it) and keeps only valid pairs. */
    fun parse(reply: String, events: List<CaseEvent>): Result {
        val byId = ids(events).toMap()
        val text = CaseCitations.visible(reply)
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end < start) return Result(emptyList(), 0, false)
        val array = runCatching { JSONArray(text.substring(start, end + 1)) }.getOrNull() ?: return Result(emptyList(), 0, false)
        val out = ArrayList<Conflict>()
        val seen = HashSet<Set<String>>()
        var dropped = 0
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i)
            val a = o?.optString("a")?.trim()?.uppercase().orEmpty()
            val b = o?.optString("b")?.trim()?.uppercase().orEmpty()
            val why = o?.optString("why")?.trim().orEmpty().take(240)
            val ea = byId[norm(a)]
            val eb = byId[norm(b)]
            if (ea == null || eb == null || norm(a) == norm(b) || why.isBlank() || !seen.add(setOf(norm(a), norm(b)))) { dropped++; continue }
            out += Conflict(ea, eb, norm(a), norm(b), why)
        }
        return Result(out, dropped, true)
    }

    /** "e1", "E01", "E001" all name the first event. */
    private fun norm(id: String): String =
        id.removePrefix("E").toIntOrNull()?.let { "E" + it.toString().padStart(2, '0') } ?: id
}
