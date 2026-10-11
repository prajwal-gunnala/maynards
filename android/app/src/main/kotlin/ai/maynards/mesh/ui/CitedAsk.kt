package ai.maynards.mesh.ui

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import ai.maynards.mesh.brain.SenseEvent
import ai.maynards.mesh.brain.Sense
import ai.maynards.mesh.brain.Answer
import ai.maynards.mesh.brain.Chat
import ai.maynards.mesh.brain.ChatProgress
import ai.maynards.mesh.brain.RunState
import ai.maynards.mesh.brain.Runner
import ai.maynards.mesh.brain.Turn
import ai.maynards.mesh.mesh.Peer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One room's Ask, kept outside the screen: switching tabs neither cancels the request nor loses the answer.
 * Compose snapshot state, so the screen redraws when it changes.
 */
class AskSession {
    var asking by mutableStateOf(false); private set
    var status by mutableStateOf("")
    var answer by mutableStateOf("")
    var answeredFrom by mutableStateOf<List<CaseEvidence>>(emptyList())
    var checked by mutableStateOf<CaseCitations.Check?>(null)
    var progress by mutableStateOf<AskProgress?>(null)
    var stats by mutableStateOf<AskStats?>(null)
    /** Helper device ids the user trusted; a different set of Helpers needs a new tick. */
    var trusted by mutableStateOf<Set<String>>(emptySet())
    private var shownFor: List<CaseEvidence>? = null
    private var job: Job? = null
    private var chat: Chat? = null

    /** New search results clear the old answer, unless an answer is still coming. */
    fun onEvidence(evidence: List<CaseEvidence>) {
        if (asking || evidence == shownFor) return
        shownFor = evidence
        answer = ""; checked = null; answeredFrom = emptyList(); progress = null; stats = null; status = ""
    }

    fun stop() {
        chat?.cancel()
        job?.cancel()
    }

    fun ask(runner: Runner, onEnsureModel: suspend () -> String, sources: List<CaseEvidence>, question: String,
            system: String, onAnswer: (Answer) -> Unit) {
        if (asking) return
        asking = true
        answer = ""; checked = null; answeredFrom = emptyList(); stats = null; status = ""
        val t0 = System.currentTimeMillis()
        progress = AskProgress(t0)
        fun at(stage: AskStage) {
            if (progress?.stage != stage && stage != AskStage.DONE)
                Sense.add("Host", "Ask · ${stage.label}" + if (stage == AskStage.SENT) " · ${sources.size} sources" else "", SenseEvent.Kind.ASK)
            progress = (progress ?: AskProgress(t0)).enter(stage)
        }
        job = APP_SCOPE.launch {
            var restartedAfter: String? = null
            try { while (true) try {
                if (restartedAfter == null) Sense.add("Host", "Ask · ${AskStage.MODEL.label}", SenseEvent.Kind.ASK)
                val model = onEnsureModel()
                val plan = runner.state.value.plan
                if (plan?.split == true && plan.helpers.map { it.deviceId }.toSet() != trusted) {
                    progress = null
                    status = (restartedAfter?.let { "$it · " } ?: "") + "Tick the Helpers to trust, then ask again"
                    return@launch
                }
                at(AskStage.SENT)
                val c = Chat(runner.endpoint, runner.apiKey).also { chat = it }
                val reply = withContext(Dispatchers.IO) {
                    c.ask(
                        listOf(Turn("user", CaseCitations.prompt(question, sources))),
                        onText = { answer = CaseCitations.visible(it) },
                        system = system,
                        onProgress = { p ->
                            when (p) {
                                is ChatProgress.Reading -> { at(AskStage.READING); progress = progress?.copy(reading = p) }
                                is ChatProgress.Writing -> { at(AskStage.WRITING); progress = progress?.copy(written = p.tokens) }
                            }
                        },
                    )
                }
                at(AskStage.CHECKING)
                answer = CaseCitations.visible(reply.text)
                answeredFrom = sources
                checked = CaseCitations.check(reply.text, sources)
                at(AskStage.DONE)
                stats = AskStats(model, plan?.slices?.map { it.name } ?: emptyList(), sources.size, reply,
                    progress?.durationOf(AskStage.MODEL) ?: 0)
                restartedAfter?.let { status = "Restarted after: $it · this answer is from a fresh run" }
                // Bookkeeping after the answer is on screen: a failure here must not lose the answer.
                runCatching { runner.record(reply) }
                runCatching { onAnswer(reply) }.onFailure { status = "Answer kept · stats not saved (${it.javaClass.simpleName})" }
                break
            } catch (e: Exception) {
                val stopped = e is kotlinx.coroutines.CancellationException || (e is java.io.IOException && e.message == "Canceled")
                // A Helper leaving mid-answer loses its part of the model state; the answer cannot resume.
                // A question has no side effects, so it is asked again once on the devices left, and said so.
                // The stream can break a moment before the Runner hears that a Helper left: give it 3 s.
                if (!stopped) withTimeoutOrNull(3_000) {
                    runner.state.first { it.status == ai.maynards.mesh.brain.RunState.Status.FAILED }
                }
                val run = runner.state.value
                val lost = run.status == ai.maynards.mesh.brain.RunState.Status.FAILED &&
                    (run.step.endsWith(" left") || run.step.startsWith("model process stopped"))
                if (!stopped && lost && restartedAfter == null) {
                    restartedAfter = run.step
                    Sense.add("Host", "Ask restarting · ${run.step}", SenseEvent.Kind.FAIL)
                    answer = ""
                    progress = AskProgress(System.currentTimeMillis())
                    status = "${run.step} · restarting on the devices left"
                    continue
                }
                progress = progress?.copy(failed = true)
                Sense.add("Host", if (stopped) "Ask stopped" else "Ask failed · ${e.javaClass.simpleName}",
                    if (stopped) SenseEvent.Kind.ASK else SenseEvent.Kind.FAIL)
                status = when {
                    stopped -> "Stopped"
                    lost -> "Could not answer: ${run.step} (already restarted once)"
                    else -> "Could not answer: ${e.message ?: e.javaClass.simpleName}"
                }
                break
            } } finally {
                chat = null
                asking = false
            }
        }
    }

    companion object {
        /** Lives as long as the app process, not a screen. */
        private val APP_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val rooms = HashMap<String, AskSession>()
        fun of(room: String): AskSession = synchronized(rooms) { rooms.getOrPut(room) { AskSession() } }
    }
}

/**
 * Asks the running model about passages found on this Host and checks every [Sxx] citation against them.
 * Shows the route (and asks consent when Helpers hold layers), live stages, the answer and its stats.
 */
@Composable
fun CitedAsk(
    runner: Runner,
    onEnsureModel: suspend () -> String,
    evidence: List<CaseEvidence>,
    question: String,
    system: String,
    busy: Boolean = false,
    room: String = "case",
    onAnswer: (Answer) -> Unit = {},
) {
    val session = remember(room) { AskSession.of(room) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val run by runner.state.collectAsState()
    LaunchedEffect(evidence) { session.onEvidence(evidence) }
    LaunchedEffect(session.asking) { while (session.asking) { now = System.currentTimeMillis(); delay(200) } }
    val helperIds = run.plan?.takeIf { it.split }?.helpers?.map { it.deviceId }?.toSet().orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        if (evidence.isNotEmpty()) {
            AskRoute(run, helperIds.isNotEmpty() && session.trusted == helperIds) { on ->
                session.trusted = if (on) helperIds else emptySet()
            }
            val needsConsent = helperIds.isNotEmpty() && session.trusted != helperIds
            if (session.asking) NButton("Stop", fill = Paper) { session.stop() }
            else NButton(
                "Ask · ${evidence.size} sources",
                fill = Term,
                enabled = !busy && question.isNotBlank() && !needsConsent,
            ) { session.ask(runner, onEnsureModel, evidence, question.trim(), system, onAnswer) }
        }
        session.progress?.let { AskProgressView(it, run, now) }
        if (session.answer.isNotBlank()) CitedAnswer(session.answer, session.checked, session.answeredFrom)
        session.stats?.let { AskStatsView(it) }
        if (session.status.isNotBlank()) Mono(session.status, 11, if (session.status.startsWith("Could not")) Bad else Term)
    }
}

/** Where a question will go: the running model and every device that computes on it. */
@Composable
private fun AskRoute(run: RunState, consent: Boolean, onConsent: (Boolean) -> Unit) {
    val plan = run.plan
    NBox(fill = if (plan?.split == true) Yellow else HostGreen, pad = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("ROUTE")
            if (plan == null || run.status != RunState.Status.READY) {
                Mono("No model running · Ask starts one", 11)
            } else {
                Mono(plan.model.name, 12, Ink)
                plan.slices.forEachIndexed { i, s ->
                    Mono("${if (i == 0) "Host" else "Helper"} · ${s.name} · layers ${s.from}–${s.to - 1}", 11)
                }
                if (plan.split) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = consent, onCheckedChange = onConsent)
                        Text(
                            "Trust ${plan.helpers.joinToString { it.name }}",
                            fontSize = 12.sp, lineHeight = 16.sp, color = Ink,
                        )
                    }
                } else Mono("This phone only", 11)
            }
        }
    }
}

@Composable
private fun CitedAnswer(answer: String, check: CaseCitations.Check?, sources: List<CaseEvidence>) {
    NBox(fill = Paper, pad = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Label("ANSWER")
            Markdown(answer)
            if (check != null) {
                val (line, tone) = when (check.verdict) {
                    CaseCitations.Verdict.CITED -> "✓ Sources verified" +
                        (if (check.uncited > 0) " · ${check.uncited} line(s) without a source" else "") to (if (check.uncited > 0) Warn else Term)
                    CaseCitations.Verdict.NOT_FOUND -> "Not in these files" to Term
                    CaseCitations.Verdict.NO_CITATIONS -> "✗ No sources · don't rely on it" to Bad
                    CaseCitations.Verdict.UNKNOWN_SOURCE -> "✗ Cites ${check.unknown.joinToString()}, which was not given" to Bad
                }
                Mono(line, 11, tone)
                sources.filter { it.id in check.cited }.forEach { Mono("${it.id} · ${it.fileName} · ${it.location}", 10) }
            }
        }
    }
}

enum class AskStage(val label: String) {
    MODEL("Model"), SENT("Sent"), READING("Reading"),
    WRITING("Writing"), CHECKING("Checking sources"), DONE("Done"),
}

/** What the Ask button is doing now, with when each stage began. Times are wall clock on this Host. */
data class AskProgress(
    val startedMs: Long,
    val stage: AskStage = AskStage.MODEL,
    val enteredMs: Map<AskStage, Long> = mapOf(AskStage.MODEL to startedMs),
    val reading: ChatProgress.Reading? = null,
    val written: Int = 0,
    val failed: Boolean = false,
) {
    fun enter(next: AskStage): AskProgress =
        if (next.ordinal <= stage.ordinal) this
        else copy(stage = next, enteredMs = enteredMs + AskStage.entries.filter { it.ordinal in stage.ordinal + 1..next.ordinal }
            .associateWith { System.currentTimeMillis() })

    fun durationOf(s: AskStage): Long {
        val from = enteredMs[s] ?: return 0
        val to = AskStage.entries.drop(s.ordinal + 1).firstNotNullOfOrNull { enteredMs[it] } ?: System.currentTimeMillis()
        return to - from
    }
}

data class AskStats(val model: String, val devices: List<String>, val sources: Int, val answer: Answer, val modelMs: Long)

@Composable
private fun AskProgressView(p: AskProgress, run: RunState, now: Long) {
    NBox(fill = Paper, pad = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(if (p.failed) "STOPPED" else if (p.stage == AskStage.DONE) "DONE" else "WORKING")
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Mono(secs(((if (p.stage == AskStage.DONE) p.enteredMs[AskStage.DONE] ?: now else now) - p.startedMs)), 11, Ink)
            }
            AskStage.entries.filter { it != AskStage.DONE }.forEach { s ->
                val done = s.ordinal < p.stage.ordinal
                val current = s == p.stage && !p.failed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        done -> Text("✓", fontSize = 13.sp, color = Term)
                        current -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = Term)
                        else -> Text("·", fontSize = 13.sp, color = Faint)
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.width(9.dp))
                    Text(s.label, fontSize = 13.sp, color = if (done || current) Ink else Faint, modifier = Modifier.weight(1f))
                    val detail = when {
                        current && s == AskStage.MODEL && run.status != RunState.Status.READY && run.step.isNotBlank() ->
                            "${run.step} · ${run.loadSeconds}s"
                        current && s == AskStage.READING -> p.reading?.let { "${it.processed}/${it.total} tokens" } ?: ""
                        current && s == AskStage.WRITING -> "${p.written} tokens"
                        done -> secs(p.durationOf(s))
                        else -> ""
                    }
                    Mono(detail, 10)
                }
            }
            when {
                p.stage == AskStage.READING && p.reading != null && p.reading.total > 0 -> {
                    val f = p.reading.processed.toFloat() / p.reading.total
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = Term)
                    Mono("${(f * 100).toInt()}%", 10)
                }
                p.stage == AskStage.WRITING -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Term)
                    val ms = now - (p.enteredMs[AskStage.WRITING] ?: now)
                    if (ms > 500) Mono("${"%.1f".format(p.written * 1000.0 / ms)} tok/s", 10)
                }
                p.stage < AskStage.CHECKING && !p.failed -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Term)
            }
        }
    }
}

@Composable
private fun AskStatsView(s: AskStats) {
    val a = s.answer
    NBox(fill = Paper, pad = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Label("STATS")
            StatLine("Model", s.model)
            StatLine("Devices", if (s.devices.size <= 1) "Host only" else s.devices.joinToString(" + "))
            StatLine("Sources", "${s.sources}")
            if (s.modelMs >= 1000) StatLine("Model start", secs(s.modelMs))
            StatLine("Read", "${a.promptTokens} tokens · ${secs(a.promptMs.toLong())} · ${"%.1f".format(a.promptPerSec)} tok/s")
            StatLine("First word", if (a.firstTokenMs >= 0) secs(a.firstTokenMs) else "—")
            StatLine("Wrote", "${a.tokens} tokens · ${secs(a.predictedMs.toLong())} · ${"%.1f".format(a.tokPerSec)} tok/s")
            StatLine("Total", secs(a.totalMs))
        }
    }
}

@Composable
private fun StatLine(k: String, v: String) = Row {
    Mono(k, 11)
    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
    Mono(v, 11, Ink)
}

private fun secs(ms: Long) = if (ms < 10_000) "%.1f s".format(ms / 1000.0) else "${ms / 1000} s"

/** A row of equal buttons; the selected one is filled. */
@Composable
fun ModeSwitch(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            NButton(label, Modifier.weight(1f), fill = if (i == selected) Term else Paper) { onSelect(i) }
        }
    }
}
