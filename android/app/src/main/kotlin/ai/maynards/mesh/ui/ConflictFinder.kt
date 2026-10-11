package ai.maynards.mesh.ui

import ai.maynards.mesh.brain.Chat
import ai.maynards.mesh.brain.RunState
import ai.maynards.mesh.brain.Runner
import ai.maynards.mesh.brain.Sense
import ai.maynards.mesh.brain.SenseEvent
import ai.maynards.mesh.brain.Turn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "find conflicts" chain: timeline (local) → model pairs (JSON) → check (local). Lives outside the screen. */
object ConflictSession {
    var running by mutableStateOf(false); private set
    var step by mutableStateOf("")
    var result by mutableStateOf<CaseConflicts.Result?>(null)
    var error by mutableStateOf("")
    private var forEvents: List<CaseEvent>? = null
    private var job: Job? = null
    private var chat: Chat? = null

    fun onEvents(events: List<CaseEvent>) {
        if (running || events == forEvents) return
        forEvents = events; result = null; error = ""; step = ""
    }

    fun stop() { chat?.cancel(); job?.cancel() }

    fun run(runner: Runner, onEnsureModel: suspend () -> String, events: List<CaseEvent>) {
        if (running) return
        running = true; result = null; error = ""
        job = AskSession.APP_SCOPE.launch {
            try {
                step = "1 · Timeline · ${minOf(events.size, CaseConflicts.MAX_EVENTS)} dates"
                Sense.add("Host", "Conflicts · step 2 · model", SenseEvent.Kind.ASK)
                step = "2 · Model · starting"
                val model = onEnsureModel()
                val plan = runner.state.value.plan
                if (plan?.split == true && plan.helpers.map { it.deviceId }.toSet() != AskSession.of("case").trusted) {
                    error = "Tick the Helpers to trust, then try again"; return@launch
                }
                step = "2 · Model · $model comparing"
                val c = Chat(runner.endpoint, runner.apiKey).also { chat = it }
                val reply = withContext(Dispatchers.IO) {
                    c.ask(listOf(Turn("user", CaseConflicts.prompt(events))), onText = { t -> step = "2 · Model · writing ${t.length} chars" },
                        system = CaseConflicts.system)
                }
                step = "3 · Check"
                val r = CaseConflicts.parse(reply.text, events)
                result = r
                step = if (r.parsed) "Done · ${r.conflicts.size} kept" + (if (r.dropped > 0) " · ${r.dropped} dropped" else "")
                    else "Done · the model did not reply with a list"
                Sense.add("Host", "Conflicts · ${r.conflicts.size} kept · ${r.dropped} dropped", SenseEvent.Kind.ASK)
                runCatching { runner.record(reply) }
            } catch (e: Exception) {
                val stopped = e is kotlinx.coroutines.CancellationException || (e is java.io.IOException && e.message == "Canceled")
                error = if (stopped) "Stopped" else "Could not finish: ${e.message ?: e.javaClass.simpleName}"
                Sense.add("Host", if (stopped) "Conflicts stopped" else "Conflicts failed · ${e.javaClass.simpleName}",
                    if (stopped) SenseEvent.Kind.ASK else SenseEvent.Kind.FAIL)
            } finally {
                chat = null; running = false
            }
        }
    }
}

@Composable
fun ConflictFinder(runner: Runner, onEnsureModel: suspend () -> String, events: List<CaseEvent>) {
    val s = ConflictSession
    val run by runner.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(events) { s.onEvents(events) }
    val helperIds = run.plan?.takeIf { it.split }?.helpers?.map { it.deviceId }?.toSet().orEmpty()
    val ask = AskSession.of("case")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("CONFLICTS")
        if (events.size < 2) { Mono("Needs 2 or more dates", 11); return@Column }
        if (events.size > CaseConflicts.MAX_EVENTS) Mono("Compares the first ${CaseConflicts.MAX_EVENTS} dates", 10, Warn)
        if (helperIds.isNotEmpty()) AskRoute(run, ask.trusted == helperIds) { on -> ask.trusted = if (on) helperIds else emptySet() }
        if (s.running) NButton("Stop", fill = Paper) { s.stop() }
        else NButton("Find conflicts", fill = Term, enabled = helperIds.isEmpty() || ask.trusted == helperIds) {
            s.run(runner, onEnsureModel, events)
        }
        if (s.step.isNotBlank()) Mono(s.step, 11, Ink)
        if (s.error.isNotBlank()) Mono(s.error, 11, if (s.error.startsWith("Could not")) Bad else Term)
        s.result?.takeIf { it.parsed && it.conflicts.isEmpty() }?.let { NBox(fill = HostGreen, pad = 10.dp) { Mono("No conflicts found among these dates", 11, Ink) } }
        s.result?.conflicts?.forEach { c ->
            NBox(fill = Paper, pad = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.why, fontSize = 13.sp, lineHeight = 18.sp, color = Ink, fontWeight = FontWeight.Bold)
                    listOf(c.aId to c.a, c.bId to c.b).forEach { (id, e) ->
                        Text("$id · ${e.label} · ${e.fileName} · ${e.location}", fontSize = 11.sp, color = Term)
                        Text(e.sentence, fontSize = 12.sp, lineHeight = 17.sp, color = Ink)
                    }
                }
            }
        }
        if (run.status == RunState.Status.IDLE && !s.running) Mono("No model running · this starts one", 10)
    }
}
