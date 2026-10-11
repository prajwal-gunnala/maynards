package ai.maynards.mesh.brain

/**
 * A small answer check the compiler runs on a setting before adopting it: fixed questions whose correct
 * answer can be checked exactly. A setting is adopted only if it is faster and gets at least as many right
 * as the current one. Short by design (a phone runs it between restarts); it guards against settings that
 * break answers, it does not grade a model's general quality.
 */
object AnswerCheck {
    data class Item(val id: String, val question: String, val accept: Regex, val system: String? = null)

    private const val SHORT = "Answer with the result only, no explanation."

    val items = listOf(
        Item("multiply", "What is 17 × 23? $SHORT", Regex("(?<![\\d.])391(?![\\d.])")),
        Item("capital", "What is the capital city of Australia? $SHORT", Regex("(?i)\\bcanberra\\b")),
        Item("leap-year", "How many days are in a leap year? $SHORT", Regex("(?<!\\d)366(?!\\d)")),
        Item("reverse", "Write the word \"mesh\" backwards. $SHORT", Regex("(?i)\\bhsem\\b")),
        Item("python-len", "In Python, what does len([4, 5, 6, 7]) return? $SHORT", Regex("(?<![\\d.])4(?![\\d.])")),
        Item("prime", "Is 97 a prime number? Answer yes or no.", Regex("(?i)^\\W*yes\\b")),
        Item("compare", "Which is larger: 0.9 or 0.11? $SHORT", Regex("(?<![\\d.])0\\.9(?!\\d)")),
        Item("cited", "SOURCES\n[S01] note.txt, lines 1–2\nThe meeting moved to Thursday at 3 PM.\n\nQUESTION\nWhen is the meeting?",
            Regex("(?is)thursday.*\\[S0?1]|\\[S0?1].*thursday"),
            system = "Answer using only the sources. Cite the source ID in square brackets, like [S01]."),
    )

    /** True when [reply] (reasoning blocks ignored) contains the item's accepted answer. */
    fun correct(item: Item, reply: String): Boolean = item.accept.containsMatchIn(
        reply.replace(Regex("(?s)<think>.*?(</think>|$)"), "").trim())

    fun score(replies: Map<String, String>): Int = items.count { item -> replies[item.id]?.let { correct(item, it) } == true }
}
