package ai.maynards.mesh.ui

import ai.maynards.mesh.brain.Sense
import ai.maynards.mesh.brain.SenseEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Spider Sense: what the Host has seen since the app started, newest first. */
@Composable
fun SenseFeed() {
    val events by Sense.events.collectAsState()
    var all by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    NBox(fill = Paper, pad = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("SPIDER SENSE")
                Spacer(Modifier.weight(1f))
                if (events.size > SHOWN) Text(if (all) "Less" else "All ${events.size}",
                    Modifier.clickable { all = !all }.padding(4.dp), fontSize = 12.sp, color = Term)
            }
            if (events.isEmpty()) Mono("Nothing yet", 11)
            events.asReversed().let { if (all) it else it.take(SHOWN) }.forEach { e -> SenseRow(e, now) }
        }
    }
}

@Composable
private fun SenseRow(e: SenseEvent, now: Long) {
    val live = e.kind == SenseEvent.Kind.WORK && now - e.at < 2_000
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color(e.kind, live), CircleShape))
        Spacer(Modifier.width(8.dp))
        Mono(ago(now - e.at), 10)
        Spacer(Modifier.width(8.dp))
        Text(
            "${e.who} · ${e.text}" + if (e.kind == SenseEvent.Kind.WORK && e.count > 1) " ×${e.count}" else "",
            Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val SHOWN = 12

@Composable
private fun color(kind: SenseEvent.Kind, live: Boolean) = when (kind) {
    SenseEvent.Kind.LINK -> Term
    SenseEvent.Kind.MODEL -> Warn
    SenseEvent.Kind.WORK -> if (live) androidx.compose.ui.graphics.Color(0xFF7C3AED) else Faint
    SenseEvent.Kind.ASK -> Ink
    SenseEvent.Kind.FAIL -> Bad
}

/** "now", "12 s", "4 min", "2 h"; a clock that moved backwards reads as "now". */
internal fun ago(ms: Long): String = when {
    ms < 1_000 -> "now"
    ms < 60_000 -> "${ms / 1_000} s"
    ms < 3_600_000 -> "${ms / 60_000} min"
    else -> "${ms / 3_600_000} h"
}
