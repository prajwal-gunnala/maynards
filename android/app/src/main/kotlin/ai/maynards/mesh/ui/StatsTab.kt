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
    val meter by stats.meter.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader("Stats & Metering", "Every answer this phone has produced: speed, latency, residency earnings, " +
            "and cost arbitrage vs cloud GPUs.")

        Label("Residency & Mesh Earnings")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Icons.Outlined.Bolt, "Earnings", "$%.4f".format(meter.earningsUsd), Paper, Modifier.weight(1f))
            Tile(Icons.Outlined.Speed, "Arbitrage", "%.1f%%".format(meter.savingsPct), Yellow, Modifier.weight(1f))
        }
        NBox(pad = 12.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("RAM sitting warm: $0.168/GB-mo vs AWS GPU $2.34/GB-mo (14× savings)", fontSize = 12.sp, color = Ink.copy(alpha = 0.7f))
                Text("Holding %.1f GB for %dh %dm · %d tokens served".format(
                    meter.gbHeld,
                    meter.residencySeconds / 3600,
                    (meter.residencySeconds % 3600) / 60,
                    meter.tokensServed
                ), fontSize = 12.sp, color = Ink.copy(alpha = 0.7f))
            }
        }

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
            Mono("${TIME.format(Date(r.at))} · ${r.layout} · first word %.1fs · ${r.tokens} tok".format(r.firstTokenMs / 1000.0), 10, Muted)
        }
    }
}

private val TIME = SimpleDateFormat("HH:mm", Locale.US)
