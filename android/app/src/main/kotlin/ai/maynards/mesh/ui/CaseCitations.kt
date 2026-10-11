package ai.maynards.mesh.ui

/** Builds the cited-answer request and checks every citation against the source IDs that were supplied. */
object CaseCitations {
    const val NOT_FOUND = "Not found in the supplied sources."

    val system = """
        You answer questions about legal case documents using ONLY the numbered sources the user supplies.
        Cite every factual sentence with the source IDs it relies on, in square brackets, for example [S01] or [S02, S03].
        Never cite an ID that was not supplied. Never use outside knowledge.
        If the sources do not contain the answer, reply exactly: $NOT_FOUND
        Keep the answer short. This is a review aid, not legal advice.
    """.trimIndent()

    fun prompt(question: String, evidence: List<CaseEvidence>): String = buildString {
        append("SOURCES\n")
        evidence.forEach { append("[${it.id}] ${it.fileName}, ${it.location}\n${it.text}\n\n") }
        append("QUESTION\n").append(question.trim())
    }

    enum class Verdict { CITED, NOT_FOUND, NO_CITATIONS, UNKNOWN_SOURCE }

    data class Check(val verdict: Verdict, val cited: List<String>, val unknown: List<String>, val uncited: Int)

    fun check(answer: String, evidence: List<CaseEvidence>): Check {
        val text = visible(answer)
        val supplied = evidence.map { it.id }.toSet()
        val cited = Regex("\\[([^\\[\\]]{1,60})]").findAll(text)
            .flatMap { m -> m.groupValues[1].split(',', ';').map { it.trim().uppercase() } }
            .filter { Regex("S\\d{1,3}").matches(it) }
            .map { "S" + it.drop(1).toInt().toString().padStart(2, '0') }
            .distinct().toList()
        val unknown = cited.filterNot { it in supplied }
        val uncited = sentences(text).count { !Regex("\\[[^\\[\\]]*S\\d").containsMatchIn(it) }
        val verdict = when {
            unknown.isNotEmpty() -> Verdict.UNKNOWN_SOURCE
            cited.isEmpty() && text.trim().trimEnd('.').equals(NOT_FOUND.trimEnd('.'), ignoreCase = true) -> Verdict.NOT_FOUND
            cited.isEmpty() -> Verdict.NO_CITATIONS
            else -> Verdict.CITED
        }
        return Check(verdict, cited, unknown, if (verdict == Verdict.NOT_FOUND) 0 else uncited)
    }

    /** The answer without a reasoning block, which some models emit before the reply. */
    fun visible(answer: String): String = answer.replace(Regex("(?s)<think>.*?(</think>|$)"), "").trim()

    private fun sentences(text: String): List<String> = text
        .split(Regex("(?<=[.!?])\\s+|\\n+"))
        .map { it.trim() }
        .filter { s -> s.count { it.isLetter() } >= 12 }
}
