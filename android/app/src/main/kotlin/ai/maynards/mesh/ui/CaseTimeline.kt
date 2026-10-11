package ai.maynards.mesh.ui

/** One dated sentence, exactly as written in the source, with where it came from. */
data class CaseEvent(
    val year: Int?, val month: Int, val day: Int,
    val dateText: String, val sentence: String, val fileName: String, val location: String,
) {
    val label: String get() = "$day ${MONTHS[month - 1].replaceFirstChar { it.uppercase() }}" + (year?.let { " $it" } ?: "")
}

private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july",
    "august", "september", "october", "november", "december")

/** Finds dates by pattern, never by a model, so every entry is a sentence that is really in the file. */
object CaseTimeline {
    private const val MAX_EVENTS = 80
    private const val MAX_SENTENCE = 320

    private val month = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?"
    private val dayMonth = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+of)?\\s+$month(?:,?\\s+(\\d{4}))?\\b", RegexOption.IGNORE_CASE)
    private val monthDay = Regex("\\b$month\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b", RegexOption.IGNORE_CASE)
    private val iso = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")

    fun extract(documents: List<CaseDocument>): List<CaseEvent> {
        val events = ArrayList<CaseEvent>()
        for (document in documents) for (page in document.pages) for (sentence in sentences(page.text)) {
            val seen = HashSet<Triple<Int?, Int, Int>>()
            dates(sentence).forEach { f ->
                if (seen.add(Triple(f.year, f.month, f.day)))
                    events += CaseEvent(f.year, f.month, f.day, f.raw, sentence.take(MAX_SENTENCE), document.name, page.location)
            }
        }
        // A missing year sorts with the stated years' first year, so "14 March" and "22 March 2024" stay together.
        val baseYear = events.mapNotNull { it.year }.minOrNull() ?: 0
        return events.withIndex()
            .sortedWith(compareBy({ it.value.year ?: baseYear }, { it.value.month }, { it.value.day }, { it.index }))
            .map { it.value }
            .take(MAX_EVENTS)
    }

    private data class Found(val at: Int, val year: Int?, val month: Int, val day: Int, val raw: String)

    /** Dates in the order they appear in the sentence. */
    private fun dates(text: String): List<Found> = buildList {
        dayMonth.findAll(text).forEach { m -> addIfValid(m, m.groupValues[3], monthIndex(m.groupValues[2]), m.groupValues[1]) }
        monthDay.findAll(text).forEach { m -> addIfValid(m, m.groupValues[3], monthIndex(m.groupValues[1]), m.groupValues[2]) }
        iso.findAll(text).forEach { m -> addIfValid(m, m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3]) }
    }.sortedBy { it.at }

    private fun MutableList<Found>.addIfValid(m: MatchResult, year: String, month: Int, day: String) {
        val d = day.toIntOrNull() ?: return
        if (month !in 1..12 || d !in 1..DAYS[month - 1]) return
        add(Found(m.range.first, year.toIntOrNull()?.takeIf { it in 1900..2100 }, month, d, m.value.trim()))
    }

    private fun monthIndex(name: String): Int = MONTHS.indexOfFirst { it.startsWith(name.lowercase().trimEnd('.').take(3)) } + 1

    private fun sentences(text: String): List<String> = text
        .split(Regex("(?<=[.!?])\\s+|\\n{2,}"))
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }

    private val DAYS = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
}
