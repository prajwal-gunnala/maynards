package ai.maynards.mesh.ui

import ai.maynards.mesh.brain.Chat
import ai.maynards.mesh.brain.Compiler
import ai.maynards.mesh.brain.Plan
import ai.maynards.mesh.brain.RunState
import ai.maynards.mesh.brain.Runner
import ai.maynards.mesh.brain.Sense
import ai.maynards.mesh.brain.SenseEvent
import ai.maynards.mesh.brain.Turn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Runs the Mesh Compiler on the current plan: restart per candidate, time a fixed prompt, keep the best. */
object TuneSession {
    var running by mutableStateOf(false); private set
    var step by mutableStateOf("")
    var decision by mutableStateOf<Compiler.Decision?>(null)
    private var job: Job? = null

    /** Short and fixed, so every candidate does exactly the same work. */
    private const val PROMPT = "List the planets of the solar system in order from the Sun, one per line."

    fun stop() { job?.cancel() }

    fun run(runner: Runner, plan: Plan, profiles: Compiler.Profiles) {
        if (running) return
        running = true; decision = null
        val baseline = runner.threadsUsed.takeIf { it > 0 } ?: (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)
        val cands = Compiler.candidates(Runtime.getRuntime().availableProcessors(), baseline)
        val key = Compiler.key(android.os.Build.MODEL, plan, 4096)
        job = AskSession.APP_SCOPE.launch {
            var chosen = baseline
            try {
                Sense.add("Host", "Compiler · trying ${cands.joinToString("/")} threads", SenseEvent.Kind.MODEL)
                val results = LinkedHashMap<Int, List<Compiler.Sample>>()
                for ((i, t) in cands.withIndex()) {
                    step = "${i + 1}/${cands.size} · $t threads · loading"
                    runner.run(plan, threads = t)
                    val st = withTimeoutOrNull(10 * 60_000L) {
                        runner.state.first { it.status == RunState.Status.READY || it.status == RunState.Status.FAILED }
                    }
                    if (st?.status != RunState.Status.READY) { results[t] = listOf(Compiler.Sample(0.0, 0.0, false)); continue }
                    val samples = ArrayList<Compiler.Sample>()
                    repeat(Compiler.REPEATS) { r ->
                        step = "${i + 1}/${cands.size} · $t threads · run ${r + 1}/${Compiler.REPEATS}"
                        samples += runCatching {
                            val a = withContext(Dispatchers.IO) {
                                Chat(runner.endpoint, runner.apiKey).ask(listOf(Turn("user", PROMPT)), onText = {}, maxTokens = 48,
                                    extra = JSONObject().put("temperature", 0).put("seed", 1).put("cache_prompt", false))
                            }
                            Compiler.Sample(a.tokPerSec, a.promptPerSec, a.tokens > 0)
                        }.getOrElse { Compiler.Sample(0.0, 0.0, false) }
                    }
                    results[t] = samples
                }
                val d = Compiler.decide(baseline, results)
                decision = d
                chosen = d.threads
                if (d.changed) profiles.save(key, d.threads)
                Sense.add("Host", "Compiler · ${d.why}", SenseEvent.Kind.MODEL)
                step = "Done · ${d.why}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                step = "Stopped · back to $baseline threads"
                Sense.add("Host", "Compiler stopped", SenseEvent.Kind.MODEL)
            } finally {
                // leave the mesh running with the chosen (or original) setting
                runner.run(plan, threads = chosen)
                running = false
            }
        }
    }
}

@Composable
fun CompilerCard(runner: Runner) {
    val run by runner.state.collectAsState()
    val ctx = LocalContext.current
    val plan = run.plan
    val t = TuneSession
    if (plan == null || (run.status != RunState.Status.READY && !t.running)) return
    val profiles = Compiler.Profiles(ctx)
    val key = Compiler.key(android.os.Build.MODEL, plan, 4096)
    val busy = ChatState.busy || AskSession.of("case").asking || AskSession.of("code").asking
    NBox(fill = Paper, pad = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Label("MESH COMPILER")
            Mono("Host threads ${runner.threadsUsed} · " + if (runner.threadsFromProfile) "measured profile" else "default", 11, Ink)
            val cc by runner.compilerCapsule.collectAsState()
            when (val u = cc) {
                is ai.maynards.mesh.brain.CompilerCapsule.Use.Apply -> {
                    Mono("Capsule · ${u.capsule.task} · applied · quality ${u.capsule.quality ?: "?"} · runs ${u.capsule.runIds.take(6).joinToString(",")}", 10, Term)
                    Mono("  " + listOfNotNull(u.tuning.threads?.let { "$it threads" }, u.tuning.ctk?.let { "cache $it" },
                        u.tuning.ub?.let { "ub $it" }, u.tuning.b?.let { "b $it" }, u.tuning.fa?.let { "flash $it" }).joinToString(" · "), 10)
                    if (u.capsule.isBaseline) Mono("  no measured setting beat MeshAI's defaults for this task", 10)
                }
                is ai.maynards.mesh.brain.CompilerCapsule.Use.Refuse ->
                    Mono("Capsule · ${u.capsule.task} · refused · ${u.reason} (measured %.0f s vs %.0f s)".format(java.util.Locale.US,
                        u.capsule.taskSeconds, u.capsule.baselineSeconds), 10, Warn)
                null -> Mono("Capsule · none for ${plan.model.name} on this phone model · ${runner.capsuleTask}", 10)
            }
            if (t.running) NButton("Stop", fill = Paper) { t.stop() }
            else {
                NButton("Tune", fill = Term, enabled = !busy) { t.run(runner, plan, profiles) }
                Mono("Restarts the model ${Compiler.candidates(Runtime.getRuntime().availableProcessors(), runner.threadsUsed).size + 1} times · a few minutes", 10)
                if (runner.threadsFromProfile) NButton("Reset to default", fill = Paper) { profiles.clear(key); runner.run(plan) }
            }
            if (t.step.isNotBlank()) Mono(t.step, 11, Term)
            t.decision?.table?.forEach { r ->
                Mono("${r.threads} threads · %.1f tok/s · read %.1f tok/s · ${r.runs - r.failed}/${r.runs} ok".format(java.util.Locale.US, r.medianDecode, r.medianPrompt),
                    10, if (r.threads == t.decision?.threads) Term else Muted)
            }
        }
    }
}
