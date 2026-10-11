package ai.maynards.mesh.ui

import ai.maynards.mesh.brain.Record
import ai.maynards.mesh.brain.Stats
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatsTab(stats: Stats) {
    val rs by stats.records.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader("Stats", "Every answer this phone has produced: how fast it came, how long the first word took, " +
            "and whether one device or several were holding the model.")
        if (rs.isEmpty()) {
            NBox {
                EmptyState(
                    "No answers yet",
                    "Run a model and ask it something. Each answer is timed here, so you can see what a split costs and what it buys.",
                )
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Icons.Outlined.ChatBubbleOutline, "Answers", "${rs.size}", Paper, Modifier.weight(1f))
            Tile(Icons.Outlined.Bolt, "Best", "%.1f".format(rs.maxOf { it.tokPerSec }), Yellow, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Icons.Outlined.Speed, "Avg tok/s", "%.1f".format(rs.map { it.tokPerSec }.average()), HostGreen, Modifier.weight(1f))
            Tile(Icons.Outlined.Timer, "First word", "%.1fs".format(rs.map { it.firstTokenMs }.average() / 1000), HelperPurple, Modifier.weight(1f))
        }

        NBox(fill = HostGreen, pad = 13.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("SPIDER SENSE · RUN COMPARISON")
                Text("Compare this phone's recorded single-device and split runs. Differences are observations, not proof that the network alone caused them.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = Ink)
                rs.groupBy { it.model }.toSortedMap().forEach { (model, runs) ->
                    val solo = runs.filter { it.devices == 1 }
                    val split = runs.filter { it.devices > 1 }
                    if (solo.isNotEmpty() || split.isNotEmpty()) {
                        val soloAvg = solo.map { it.tokPerSec }.average().takeIf { solo.isNotEmpty() }
                        val splitAvg = split.map { it.tokPerSec }.average().takeIf { split.isNotEmpty() }
                        Text(model, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Ink)
                        Mono("Single ${soloAvg?.let { "%.2f tok/s · n=${solo.size}".format(it) } ?: "not measured"}  |  " +
                            "Split ${splitAvg?.let { "%.2f tok/s · n=${split.size}".format(it) } ?: "not measured"}", 10)
                        if (soloAvg != null && splitAvg != null) {
                            val pct = (splitAvg / soloAvg - 1.0) * 100.0
                            Mono("Observed split change ${if (pct >= 0) "+" else ""}%.1f%% decode · compare prompt rate and route below".format(pct), 10, Muted)
                        }
                    }
                }
                Mono("KV estimate: q8 cache budget for 4,096 context. It is an estimate; runtime allocation is not currently sampled.", 10, Muted)
            }
        }

        NBox(pad = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Speed, last ${minOf(rs.size, 16)} answers")
                SpeedBars(rs.takeLast(16), Modifier.fillMaxWidth().height(150.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Legend(HostGreen, "one device")
                    Legend(HelperPurple, "split")
                }
            }
        }

        Label("Recent")
        rs.takeLast(12).reversed().forEach { Row(it) }
        NButton("Clear", fill = Paper) { stats.clear() }
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, value: String, fill: Color, modifier: Modifier) {
    NBox(modifier, fill = fill, shadow = 4.dp, pad = 12.dp) {
        Column {
            IconLabel(icon, label)
            Text(value, fontWeight = FontWeight.Black, fontSize = 26.sp, color = Ink)
        }
    }
}

@Composable
private fun SpeedBars(rs: List<Record>, modifier: Modifier) {
    val top = (rs.maxOfOrNull { it.tokPerSec } ?: 1.0).coerceAtLeast(1.0)
    Canvas(modifier) {
        val gap = 8f
        val w = (size.width - gap * (rs.size - 1)) / rs.size.coerceAtLeast(1)
        rs.forEachIndexed { i, r ->
            val h = (size.height * (r.tokPerSec / top)).toFloat().coerceAtLeast(6f)
            val x = i * (w + gap)
            val tl = Offset(x, size.height - h)
            drawRoundRect(Ink, Offset(x + 4f, size.height - h + 4f), Size(w, h - 4f), CornerRadius(6f))
            drawRoundRect(if (r.devices > 1) HelperPurple else HostGreen, tl, Size(w, h), CornerRadius(6f))
            drawRoundRect(Ink, tl, Size(w, h), CornerRadius(6f), style = Stroke(4f))
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(14.dp).height(14.dp).background(color, RoundedCornerShape(3.dp)).border(2.dp, Ink, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(6.dp))
        Mono(text, 11)
    }
}

@Composable
private fun Row(r: Record) {
    NBox(fill = if (r.devices > 1) HelperPurple else Paper, shadow = 3.dp, pad = 10.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.model, fontWeight = FontWeight.Black, fontSize = 14.sp, color = Ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("%.1f tok/s".format(r.tokPerSec), fontWeight = FontWeight.Black, fontSize = 14.sp, color = Ink)
            }
            Mono("${TIME.format(Date(r.at))} · ${r.layout} · first %.1fs · ${r.tokens} tok".format(r.firstTokenMs / 1000.0), 10, Muted)
            Mono("prefill ${if (r.promptTokPerSec > 0) "%.1f tok/s".format(r.promptTokPerSec) else "unknown"} · ${r.link} · estimated KV ${if (r.kvBytes > 0) "%.2f GB".format(r.kvBytes / 1e9) else "unknown"}", 9, Muted)
        }
    }
}

private val TIME = SimpleDateFormat("HH:mm", Locale.US)
