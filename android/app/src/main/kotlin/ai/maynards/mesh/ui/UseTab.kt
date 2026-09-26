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

/** One line of the conversation as shown on screen. */
data class Bubble(val mine: Boolean, val text: String, val stats: Answer? = null, val photo: android.graphics.Bitmap? = null)

@Composable
fun UseTab(runner: Runner, canSee: Boolean, onPickModel: () -> Unit, onAnswer: (Answer) -> Unit = {}) {
    val run by runner.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        when (run.status) {
            RunState.Status.IDLE -> Empty(onPickModel)
            RunState.Status.STARTING, RunState.Status.LOADING -> Loading(run, runner)
            RunState.Status.FAILED -> Failed(run, onPickModel)
            RunState.Status.READY -> ChatView(run, runner, canSee, onAnswer)
        }
    }
}

@Composable
private fun Empty(onPickModel: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Title("Nothing running", 24)
        Spacer(Modifier.height(12.dp))
        NButton("Pick a model", onClick = onPickModel)
    }
}

@Composable
private fun Loading(run: RunState, runner: Runner) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        NBox(fill = Yellow) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Ink, strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Title(run.plan?.model?.name ?: "", 18)
                }
                IconLabel(Icons.Outlined.HourglassTop, "${run.step} · ${run.loadSeconds}s")
            }
        }
        run.plan?.let { NBox(pad = 12.dp) { LayerBar(it) } }
        NButton("Cancel", fill = Paper) { runner.stop() }
    }
}

@Composable
private fun Failed(run: RunState, onPickModel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        NBox(fill = Danger) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Title("Stopped", 24)
                Text(run.step, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
        NButton("Back to models", onClick = onPickModel)
    }
}

@Composable
private fun ChatView(run: RunState, runner: Runner, canSee: Boolean, onAnswer: (Answer) -> Unit) {
    val chat = remember { Chat(runner.endpoint) }
    val bubbles = remember { mutableStateListOf<Bubble>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var photo by remember { mutableStateOf<Pair<android.graphics.Bitmap, String>?>(null) }
    val voice = rememberVoice { input = it }
    val camera = rememberCamera { bmp, url -> photo = bmp to url; if (input.isBlank()) input = "What is in this photo?" }
    LaunchedEffect(bubbles.size, bubbles.lastOrNull()?.text?.length) {
        if (bubbles.isNotEmpty()) list.scrollToItem(bubbles.lastIndex)
    }

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || busy) return
        input = ""
        busy = true
        val shot = photo
        photo = null
        bubbles += Bubble(true, q, photo = shot?.first)
        bubbles += Bubble(false, "")
        val history = bubbles.dropLast(1).map { Turn(if (it.mine) "user" else "assistant", it.text) }
        scope.launch {
            val a = runCatching {
                withContext(Dispatchers.IO) {
                    chat.ask(history, onText = { t -> scope.launch { bubbles[bubbles.lastIndex] = Bubble(false, t) } }, image = shot?.second)
                }
            }
            a.onSuccess { bubbles[bubbles.lastIndex] = Bubble(false, it.text, it); onAnswer(it) }
                .onFailure { bubbles[bubbles.lastIndex] = Bubble(false, "Error: ${it.message}") }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Title(run.plan?.model?.name ?: "", 18)
                Mono(run.plan?.reason ?: "", 11, Muted)
            }
            Icon(Icons.Outlined.StopCircle, "Stop", Modifier.size(36.dp).padding(4.dp).let { m ->
                m.then(Modifier.background(Paper, RoundedCornerShape(8.dp)).border(2.dp, Ink, RoundedCornerShape(8.dp)))
            }.clickableNoRipple { runner.stop() }, tint = Ink)
        }
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
            if (canSee) {
                SquareButton(Icons.Outlined.PhotoCamera, if (photo != null) HostGreen else Paper) { camera() }
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

@Composable
private fun BubbleView(b: Bubble) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (b.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 320.dp).background(if (b.mine) Yellow else Paper, shape).border(2.dp, Ink, shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            b.photo?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.size(160.dp).border(2.dp, Ink, RoundedCornerShape(8.dp))) }
            Text(b.text.ifEmpty { "…" }, fontSize = 15.sp, color = Ink)
            b.stats?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Speed, null, Modifier.size(14.dp), tint = Muted)
                    Spacer(Modifier.width(4.dp))
                    Mono("%.1f tok/s · first word %.1fs · %d tokens".format(s.tokPerSec, s.firstTokenMs / 1000.0, s.tokens), 10, Muted)
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
