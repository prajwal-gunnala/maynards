package ai.maynards.mesh.brain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** One thing the Host saw happen. Only events a process reported are logged; nothing is inferred. */
data class SenseEvent(val at: Long, val who: String, val text: String, val kind: Kind, val count: Int = 1, val firstAt: Long = at) {
    enum class Kind { LINK, MODEL, WORK, ASK, FAIL }
}

/**
 * Spider Sense: the Host's live log of links, model steps, Helper work and answers (last 80).
 * It holds names, counts and timings only; never prompt, document or answer text.
 */
object Sense {
    private const val KEEP = 80
    private const val BURST_MS = 3_000L
    private const val LOOK_BACK = 4
    /** Helper work arrives in bursts with pauses while the Host reads the prompt; one answer stays one line. */
    private const val WORK_BURST_MS = 15_000L

    private val _events = MutableStateFlow<List<SenseEvent>>(emptyList())
    val events: StateFlow<List<SenseEvent>> = _events

    fun add(who: String, text: String, kind: SenseEvent.Kind, now: Long = System.currentTimeMillis()) =
        _events.update { (it + SenseEvent(now, who, text, kind)).takeLast(KEEP) }

    /**
     * Like add, but a matching who+text among the last few lines, seen within 3 s, only raises its count.
     * Looking back a few lines keeps two Helpers working at once as two lines, not an interleaved stream.
     */
    fun pulse(who: String, text: String, kind: SenseEvent.Kind, now: Long = System.currentTimeMillis()) = _events.update { list ->
        // Only look past lines of the same kind: a different event in between keeps the order honest.
        val window = (list.lastIndex downTo maxOf(0, list.size - LOOK_BACK)).takeWhile { list[it].kind == kind }
        val i = window.firstOrNull { list[it].who == who && list[it].text == text &&
            now - list[it].at in 0..(if (kind == SenseEvent.Kind.WORK) WORK_BURST_MS else BURST_MS) }
        if (i != null) list.toMutableList().also { it[i] = list[i].copy(at = now, count = list[i].count + 1) }
        else (list + SenseEvent(now, who, text, kind)).takeLast(KEEP)
    }

    fun clear() { _events.value = emptyList() }
}
