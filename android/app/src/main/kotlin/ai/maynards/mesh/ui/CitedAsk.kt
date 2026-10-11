package ai.maynards.mesh.ui

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
 * Asks the running model about passages found on this Host and checks every [Sxx] citation against them.
 * Shows the route (and asks consent when Helpers hold layers), live stages, the answer and its stats.
 * Answer state resets whenever [evidence] changes.
 */
@Composable
fun CitedAsk(
    runner: Runner,
    onEnsureModel: suspend () -> String,
    evidence: List<CaseEvidence>,
    question: String,
    system: String,
    busy: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var helperConsent by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var status by remember(evidence) { mutableStateOf("") }
    var answer by remember(evidence) { mutableStateOf("") }
    var answeredFrom by remember(evidence) { mutableStateOf<List<CaseEvidence>>(emptyList()) }
    var checked by remember(evidence) { mutableStateOf<CaseCitations.Check?>(null) }
    var progress by remember(evidence) { mutableStateOf<AskProgress?>(null) }
    var stats by remember(evidence) { mutableStateOf<AskStats?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val run by runner.state.collectAsState()
    fun clearAnswer() { answer = ""; checked = null; answeredFrom = emptyList(); progress = null; stats = null; status = "" }
    LaunchedEffect(asking) { while (asking) { now = System.currentTimeMillis(); delay(200) } }

    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        if (evidence.isNotEmpty()) {
            AskRoute(run, helperConsent) { helperConsent = it }
            val needsConsent = run.plan?.split == true && !helperConsent
            NButton(
                if (asking) "Asking…" else "Ask · ${evidence.size} sources",
                fill = Term,
                enabled = !asking && !busy && question.isNotBlank() && !needsConsent,
            ) {
                asking = true
                clearAnswer()
                val sources = evidence
                val q = question.trim()
                scope.launch {
                    val t0 = System.currentTimeMillis()
                    fun at(stage: AskStage) { progress = (progress ?: AskProgress(t0)).enter(stage) }
                    try {
                        status = ""
                        at(AskStage.MODEL)
                        val model = onEnsureModel()
                        val plan = runner.state.value.plan
                        if (plan?.split == true && !helperConsent) {
                            progress = null
                            status = "Tick the Helpers to trust, then ask again"
                            return@launch
                        }
                        at(AskStage.SENT)
                        val reply = withContext(Dispatchers.IO) {
                            Chat(runner.endpoint, runner.apiKey).ask(
                                listOf(Turn("user", CaseCitations.prompt(q, sources))),
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
                        runner.record(reply)
                        answer = CaseCitations.visible(reply.text)
                        answeredFrom = sources
                        checked = CaseCitations.check(reply.text, sources)
                        at(AskStage.DONE)
                        stats = AskStats(model, plan?.slices?.map { it.name } ?: emptyList(), sources.size, reply,
                            progress?.durationOf(AskStage.MODEL) ?: 0)
                    } catch (e: Exception) {
                        progress = progress?.copy(failed = true)
                        status = "Could not answer: ${e.message ?: e.javaClass.simpleName}"
                    } finally {
                        asking = false
                    }
                }
            }
        }
        progress?.let { AskProgressView(it, run, now) }
        if (answer.isNotBlank()) CitedAnswer(answer, checked, answeredFrom)
        stats?.let { AskStatsView(it) }
        if (status.isNotBlank()) Mono(status, 11, if (status.startsWith("Could not")) Bad else Term)
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
