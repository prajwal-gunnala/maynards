package ai.maynards.mesh.brain

/**
 * Works out what a request needs from the request itself, so any question can be routed to a fitting model.
 * Local rules, no model call: instant, and the reason can always be shown.
 */
object TaskDetector {
    data class Detected(val task: Task, val why: String)

    private val CODE_WORDS = Regex("(?i)\\b(code|function|method|class|compile[sd]?|compiler|bug|stack ?trace|exception|refactor|regex|sql|query|api|endpoint|kotlin|java|python|rust|javascript|typescript|golang|c\\+\\+|unit tests?|git|merge conflict|null ?pointer|segfault)\\b")
    private val CODE_SHAPES = listOf(
        Regex("```") to "code block",
        Regex("(?m)^\\s*(def|fun|class|import|package|public|private|func|fn|const|let|var|#include)\\b") to "source lines",
        Regex("(?m)\\bat [\\w.$]+\\([\\w]+\\.(kt|java|scala):\\d+\\)|Traceback \\(most recent call last\\)|\\berror\\[E\\d+]") to "stack trace",
        Regex("\\b(java|javax|kotlin|kotlinx|android|org|com|io)\\.[a-z]\\w*\\.[\\w.]*[A-Z]\\w*|\\b[A-Z]\\w*(Exception|Error)\\b") to "error name",
        Regex("[{};]\\s*$", RegexOption.MULTILINE) to "code punctuation",
    )
    private val MATH_WORDS = Regex("(?i)\\b(solve|prove|proof|equation|integral|derivative|probability|calculate|compute|how many|percentage|theorem|logic puzzle|step by step)\\b")
    private val MATH_SHAPE = Regex("\\d+\\s*[-+*/^=]\\s*\\d+|[∫∑√π≤≥]|\\bx\\s*[=^]")
    private val DOC_WORDS = Regex("(?i)\\b(summari[sz]e|summary|contract|clause|agreement|policy|report|document|extract|key points|tl;?dr|minutes|transcript|compare these)\\b")
    private const val LONG_INPUT = 2_500

    fun detect(text: String, hasImage: Boolean = false): Detected {
        if (hasImage) return Detected(Task.VISION, "a photo is attached")
        val shapes = CODE_SHAPES.filter { (r, _) -> r.containsMatchIn(text) }.map { it.second }
        val codeWords = CODE_WORDS.findAll(text).map { it.value.lowercase() }.distinct().take(2).toList()
        // Shapes are strong evidence; words alone need two, so "what is the code of conduct" stays general.
        if (shapes.any { it != "code punctuation" } || (shapes.isNotEmpty() && codeWords.isNotEmpty()) || codeWords.size >= 2)
            return Detected(Task.CODE, "code: " + (shapes + codeWords.map { "\"$it\"" }).take(3).joinToString(" + "))
        if (text.length >= LONG_INPUT) return Detected(Task.DOCUMENT, "long text (${text.length} chars)")
        val doc = DOC_WORDS.find(text)?.value
        if (doc != null) return Detected(Task.DOCUMENT, "document: \"${doc.lowercase()}\"")
        val math = MATH_WORDS.find(text)?.value
        if (math != null || MATH_SHAPE.containsMatchIn(text))
            return Detected(Task.REASONING, "reasoning: " + (math?.let { "\"${it.lowercase()}\"" } ?: "a calculation"))
        return Detected(Task.CHAT, "general question")
    }
}
