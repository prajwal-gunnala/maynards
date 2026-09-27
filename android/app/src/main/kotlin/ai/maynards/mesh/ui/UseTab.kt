package ai.maynards.mesh.ui

import ai.maynards.mesh.brain.Answer
import ai.maynards.mesh.brain.Chat
import ai.maynards.mesh.brain.RunState
import ai.maynards.mesh.brain.Runner
import ai.maynards.mesh.brain.Turn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.maynards.mesh.engine.Net
import ai.maynards.mesh.mesh.MeshHost
import ai.maynards.mesh.mesh.msg
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import androidx.compose.material.icons.outlined.RateReview
import ai.maynards.mesh.brain.Plan
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.material.icons.outlined.Warning

/** One line of the conversation as shown on screen. */
data class Bubble(val mine: Boolean, val text: String, val stats: Answer? = null, val photo: android.graphics.Bitmap? = null)

/**
 * The conversation outlives the tab. ChatView leaves composition whenever the presenter visits
 * Models or Stats, and a plain remember { } meant the chat came back empty: Use -> Stats -> Use
 * is exactly the sequence in a demo. Holding the client here also stops a fresh OkHttpClient,
 * with its pool and threads, being created on every visit.
 */
private const val REVIEW_PROMPT =
    "Review this change. List real bugs first, then risky spots. Be brief; say 'looks fine' if it is."

object ChatState {
    val bubbles = mutableStateListOf<Bubble>()
    private var client: Chat? = null
    private var endpoint: String = ""
    fun chat(url: String): Chat {
        if (client == null || endpoint != url) { client = Chat(url); endpoint = url }
        return client!!
    }
}

@Composable
fun UseTab(runner: Runner, host: MeshHost?, canSee: Boolean, onPickModel: () -> Unit, system: () -> String? = { null }, onAnswer: (Answer) -> Unit = {}) {
    val run by runner.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        when (run.status) {
            RunState.Status.IDLE -> Empty(onPickModel)
            RunState.Status.STARTING, RunState.Status.LOADING -> Loading(run, runner)
            RunState.Status.FAILED -> Failed(run, onPickModel)
            RunState.Status.READY -> ChatView(run, runner, host, canSee, system, onAnswer)
        }
    }
}

@Composable
private fun Empty(onPickModel: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("Use", "Ask the mesh anything, by typing or by speaking. Photos go to a device running a vision model.")
        NBox {
            EmptyState(
                "Nothing is running",
                "Choose a model first. The planner works out which device holds which layers, and this is where you talk to it.",
            ) { NButton("Choose a model", fill = Term, onClick = onPickModel) }
        }
    }
}

@Composable
private fun Loading(run: RunState, runner: Runner) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader(
            "Starting",
            "Each device is being given its layers. A device that has held these layers before keeps them, " +
                "so the second time is far quicker than the first.",
        )
        NBox {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Term, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Title(run.plan?.model?.name ?: "", 16)
                }
                Mono("${run.step} · ${run.loadSeconds} s", 12, Muted)
            }
        }
        run.plan?.let { NBox(pad = 12.dp) { LayerBar(it) } }
        NButton("Cancel", fill = Paper) { runner.stop() }
    }
}

@Composable
private fun Failed(run: RunState, onPickModel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader("Stopped", "The run ended. The reason below is the real one, not a guess.")
        NBox(fill = Danger) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Warning, null, Modifier.size(16.dp), tint = Bad)
                    Spacer(Modifier.width(8.dp))
                    Title("It stopped", 16)
                }
                Text(run.step, fontSize = 13.sp, color = Ink)
            }
        }
        NButton("Back to models", fill = Term, onClick = onPickModel)
    }
}

@Composable
private fun ChatView(run: RunState, runner: Runner, host: MeshHost?, canSee: Boolean, system: () -> String?, onAnswer: (Answer) -> Unit) {
    val chat = ChatState.chat(runner.endpoint)
    val bubbles = ChatState.bubbles
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val ctx = LocalContext.current
    var online by remember { mutableStateOf(true) }
    var lastRoute by remember { mutableStateOf<String?>(null) }
    var words by remember { mutableIntStateOf(0) }       // words in the answer being streamed now
    var rate by remember { mutableDoubleStateOf(0.0) }   // words per second, from the last finished answer
    LaunchedEffect(Unit) { while (true) { online = Net.online(ctx); delay(4_000) } }
    var photo by remember { mutableStateOf<Pair<android.graphics.Bitmap, String>?>(null) }
    val voice = rememberVoice { input = it }
    val camera = rememberCamera { bmp, url -> photo = bmp to url; if (input.isBlank()) input = "What is in this photo?" }
    val gallery = rememberGallery { bmp, url -> photo = bmp to url; if (input.isBlank()) input = "What is in this photo?" }
    LaunchedEffect(bubbles.size, bubbles.lastOrNull()?.text?.length) {
        if (bubbles.isNotEmpty()) list.scrollToItem(bubbles.lastIndex)
    }

    fun ask(question: String, shown: String = question, image: Pair<android.graphics.Bitmap, String>? = null,
            fresh: Boolean = false) {
        if (busy) return
        busy = true
        val shot = image
        photo = null
        bubbles += Bubble(true, shown, photo = shot?.first)
        bubbles += Bubble(false, "")
        // the model sees the real question; the bubble may show a shorter version of it.
        // A review carries a patch worth well over a thousand tokens, so it goes on its own: replaying
        // the conversation as well (and a previous patch with it) would not fit the 4096 context.
        val history = if (fresh) listOf(Turn("user", question))
        else bubbles.dropLast(1).map { Turn(if (it.mine) "user" else "assistant", it.text) }
            .toMutableList().also { if (question != shown) it[it.lastIndex] = Turn("user", question) }
        scope.launch {
            val a = runCatching {
                withContext(Dispatchers.IO) {
                    chat.ask(history, onText = { t ->
                        scope.launch {
                            words = t.length / 4      // about four characters to the word: enough to animate
                            bubbles[bubbles.lastIndex] = Bubble(false, t)
                        }
                    }, image = shot?.second, system = system())
                }
            }
            a.onSuccess {
                bubbles[bubbles.lastIndex] = Bubble(false, it.text, it)
                lastRoute = it.route ?: lastRoute
                words = it.tokens
                rate = it.tokPerSec
                onAnswer(it)
            }
                .onFailure { bubbles[bubbles.lastIndex] = Bubble(false, "Error: ${it.message}") }
            busy = false
        }
    }

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || busy) return
        input = ""
        ask(q, q, photo)
    }

    /**
     * The developer action: ask a laptop in the mesh what it has changed and not committed, and review
     * it here. The laptop answers over the control link that is already open, so the phone never needs
     * to see the files.
     */
    fun reviewChanges() {
        if (busy) return
        val laptop = host?.peers?.value?.values?.firstOrNull { it.specs.kind == "laptop" }
        if (host == null || laptop == null) {
            bubbles += Bubble(false, "No laptop in the mesh to ask. Join one with `mesh join`.")
            return
        }
        busy = true
        bubbles += Bubble(false, "Asking ${laptop.specs.name} what you have changed…")
        scope.launch {
            // the laptop forks three git commands and may be mid-probe on its own helper port,
            // so this waits well past the point where a judge would assume it is broken
            val reply = withTimeoutOrNull(25_000) {
                val wanted = host.events.onSubscription { host.send(laptop.id, msg("diff")) }
                wanted.first { (id, m) -> id == laptop.id && m.optString("t") == "diff" }.second
            }
            busy = false
            bubbles.removeAt(bubbles.lastIndex)      // drop the "asking…" line
            val text = reply?.optString("text").orEmpty()
            // git cannot see a file it is not tracking, so a brand new file looks like no change at all
            val newFiles = reply?.optInt("untracked") ?: 0
            val alsoNew = if (newFiles > 0) " ($newFiles new file(s) not tracked yet: git add them to include them)" else ""
            when {
                reply == null -> bubbles += Bubble(false, "${laptop.specs.name} did not answer in time.")
                // an empty patch means one of two very different things, and the difference matters at 4am
                text.isBlank() && reply.optString("repo").isBlank() ->
                    bubbles += Bubble(false, "${laptop.specs.name} is not in a git repository. Run `mesh join` from your project folder.")
                text.isBlank() && newFiles > 0 ->
                    bubbles += Bubble(false, "Nothing tracked has changed, but git is not tracking $newFiles new file(s) yet. Run git add on them and tap again.")
                text.isBlank() -> bubbles += Bubble(false, "Nothing to review: ${laptop.specs.name} has no uncommitted change.")
                else -> {
                    val repo = reply.optString("repo").ifBlank { "the repository" }
                    val lines = text.count { it == '\n' } + 1
                    // the laptop trims a large patch to fit the context: never review half of it silently
                    val cut = if (reply.optBoolean("cut")) ", first part only" else ""
                    ask("$REVIEW_PROMPT\n\n```diff\n$text\n```",
                        "Review my changes in $repo ($lines lines$cut, from ${laptop.specs.name})$alsoNew",
                        fresh = true)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Title(run.plan?.model?.name ?: "", 18)
                Mono(statusLine(run, lastRoute, online), 11, Muted)
            }
            Icon(Icons.Outlined.StopCircle, "Stop", Modifier.size(36.dp).padding(4.dp).let { m ->
                m.then(Modifier.background(Paper, RoundedCornerShape(8.dp)).border(2.dp, Ink, RoundedCornerShape(8.dp)))
            }.clickableNoRipple { runner.stop() }, tint = Ink)
        }
        run.plan?.takeIf { it.split }?.let { plan -> Traffic(plan, busy, words, rate) }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(bubbles) { b -> BubbleView(b) }
        }
        photo?.let { (bmp, _) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(bmp.asImageBitmap(), null, Modifier.size(56.dp).border(2.dp, Ink, RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(8.dp))
                Mono("photo attached · tap to remove", 11, Muted)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.Close, "Remove", Modifier.clickableNoRipple { photo = null }, tint = Ink)
            }
        }
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val shape = RoundedCornerShape(10.dp)
            SquareButton(if (voice.listening) Icons.Outlined.MicOff else Icons.Outlined.Mic, if (voice.listening) HostGreen else Paper) {
                if (voice.listening) voice.stop() else voice.start()
            }
            Spacer(Modifier.width(6.dp))
            SquareButton(Icons.Outlined.RateReview, if (busy) Cream else Paper) { reviewChanges() }
            Spacer(Modifier.width(6.dp))
            if (canSee) {
                SquareButton(Icons.Outlined.Image, if (photo != null) HostGreen else Paper) { gallery() }
                Spacer(Modifier.width(6.dp))
                SquareButton(Icons.Outlined.PhotoCamera, Paper) { camera() }
                Spacer(Modifier.width(6.dp))
            }
            Box(Modifier.weight(1f).background(Paper, shape).border(Border, Ink, shape).padding(12.dp)) {
                if (input.isEmpty()) Text("Ask anything", color = Muted)
                BasicTextField(input, { input = it }, Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 16.sp, color = Ink))
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(52.dp).background(if (busy) Cream else Yellow, shape).border(Border, Ink, shape).clickableNoRipple { send() },
                contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Ink, strokeWidth = 3.dp)
                else Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = Ink)
            }
        }
    }
}

/**
 * The mesh working, drawn as what it is: one hidden state leaving this device for each helper and
 * coming back, once per word. Green out, purple back. The numbers are the model's own: the hidden
 * state is `embedding_length` at 16 bits, read from the GGUF header.
 */
@Composable
private fun Traffic(plan: Plan, busy: Boolean, words: Int, rate: Double) {
    val helpers = plan.slices.size - 1
    val perWord = plan.model.hiddenBytes * 2 * helpers      // out and back, to each helper
    val crossed = perWord * words.toLong()
    NBox(pad = 10.dp, shadow = 3.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MeshWeb(
                plan.slices.mapIndexed { i, s ->
                    WebNode(s.name.take(10), if (i == 0) HostGreen else HelperPurple, isHost = i == 0)
                },
                Modifier.size(96.dp), busy = busy, rate = rate,
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label(if (busy) "Thinking across ${plan.slices.size} devices" else "Idle")
                if (perWord > 0) {
                    Mono("%s per word, out and back".format(kb(perWord)), 11, Muted)
                    Mono("%s crossed the link · %d words".format(kb(crossed), words), 11, Muted)
                } else {
                    Mono("%d words".format(words), 11, Muted)
                }
                Mono("the weights never move: only this does", 10, Muted)
            }
        }
    }
}

private fun kb(b: Long): String = when {
    b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
    b >= 1_000 -> "%.1f KB".format(b / 1e3)
    else -> "$b B"
}

/**
 * What a judge cannot otherwise see in ten seconds: how many devices are holding this model, which
 * layers this phone itself holds, whether the internet is off, and who answered last.
 */
private fun statusLine(run: RunState, route: String?, online: Boolean): String {
    val plan = run.plan ?: return run.step
    val n = plan.slices.size
    val mine = plan.slices.firstOrNull()?.takeIf { it.to > it.from }?.let { "layers ${it.from}-${it.to - 1} here" }
    return listOfNotNull(
        if (n > 1) "$n devices" else "1 device",
        mine,
        if (online) null else "OFFLINE",
        route?.substringAfter(':')?.takeIf { it.isNotBlank() }?.let { "answered by $it" },
        plan.slices.drop(1).joinToString(" + ") { it.name.take(10) }.takeIf { it.isNotEmpty() }?.let { "with $it" },
    ).joinToString(" · ")
}

@Composable
private fun BubbleView(b: Bubble) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (b.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 340.dp).background(if (b.mine) Yellow else Paper, shape).border(2.dp, Ink, shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            b.photo?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.size(160.dp).border(2.dp, Ink, RoundedCornerShape(8.dp))) }
            if (b.mine || b.text.isEmpty()) Text(b.text.ifEmpty { "…" }, fontSize = 15.sp, color = Ink) else Markdown(b.text)
            b.stats?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Speed, null, Modifier.size(14.dp), tint = Muted)
                    Spacer(Modifier.width(4.dp))
                    Mono("%.1f tok/s · first word %.1fs · %d tokens%s".format(
                        s.tokPerSec, s.firstTokenMs / 1000.0, s.tokens,
                        // only a request routed by the agent carries this; a local engine does not
                        s.route?.substringAfter(':')?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), 10, Muted)
                }
            }
        }
    }
}

@Composable
private fun SquareButton(icon: androidx.compose.ui.graphics.vector.ImageVector, fill: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.size(48.dp).background(fill, shape).border(Border, Ink, shape).clickableNoRipple(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Ink)
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
