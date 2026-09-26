package ai.maynards.mesh.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** One device on the web. */
data class WebNode(val label: String, val fill: Color, val isHost: Boolean = false, val note: String = "")

private const val SPOKES = 12
private const val RINGS = 5

/**
 * The mesh drawn as a spider web: the Host sits at the centre with the spider,
 * every Helper hangs on a spoke, and a thread pulses from the centre to each Helper.
 */
@Composable
fun MeshWeb(nodes: List<WebNode>, modifier: Modifier = Modifier, lineColor: Color = Ink.copy(alpha = 0.35f)) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "t",
    )
    val text = rememberTextMeasurer()
    Canvas(modifier) {
        val c = center
        val r = min(size.width, size.height) / 2f * 0.92f
        drawWeb(c, r, lineColor)

        val helpers = nodes.filterNot { it.isHost }
        helpers.forEachIndexed { i, n ->
            val a = angleFor(i, helpers.size)
            val p = Offset(c.x + cos(a) * r * 0.78f, c.y + sin(a) * r * 0.78f)
            drawLine(Ink, c, p, strokeWidth = 5f, cap = StrokeCap.Round)
            // a bead of data travelling along the thread
            val bead = Offset(c.x + (p.x - c.x) * pulse, c.y + (p.y - c.y) * pulse)
            drawCircle(Yellow, 9f, bead)
            drawCircle(Ink, 9f, bead, style = Stroke(3f))
            drawNode(p, r * 0.13f, n.fill)
            drawLabel(text, n, Offset(p.x, p.y + r * 0.13f + 8f))
        }
        nodes.firstOrNull { it.isHost }?.let {
            drawNode(c, r * 0.17f, it.fill)
            if (helpers.isNotEmpty()) drawLabel(text, it, Offset(c.x, c.y + r * 0.17f + 8f))
        }
        drawSpider(c, r * 0.09f)
    }
}

/** Just the web, for backgrounds and headers. */
@Composable
fun WebBackdrop(modifier: Modifier = Modifier, color: Color = Ink.copy(alpha = 0.10f)) {
    Canvas(modifier) {
        drawWeb(Offset(size.width * 0.95f, size.height * 0.05f), maxOf(size.width, size.height) * 0.9f, color)
    }
}

/** Device name (and layers held, while running) under its node, in a small white tag. */
private fun DrawScope.drawLabel(tm: TextMeasurer, n: WebNode, top: Offset) {
    val line = if (n.note.isBlank()) n.label.take(14) else "${n.label.take(12)} · ${n.note}"
    val layout = tm.measure(line, TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Black, color = Ink))
    val w = layout.size.width + 16f
    val h = layout.size.height + 8f
    val tl = Offset(top.x - w / 2, top.y)
    drawRoundRect(Ink, Offset(tl.x + 3f, tl.y + 3f), Size(w, h), CornerRadius(8f))
    drawRoundRect(Paper, tl, Size(w, h), CornerRadius(8f))
    drawRoundRect(Ink, tl, Size(w, h), CornerRadius(8f), style = Stroke(3f))
    drawText(layout, topLeft = Offset(tl.x + 8f, tl.y + 4f))
}

private fun angleFor(i: Int, n: Int): Float {
    // spread helpers evenly, starting at the top
    val step = 2 * PI / maxOf(n, 1)
    return (-PI / 2 + i * step + (if (n == 2) PI / 2 else 0.0)).toFloat()
}

private fun DrawScope.drawWeb(c: Offset, r: Float, color: Color) {
    for (s in 0 until SPOKES) {
        val a = (2 * PI * s / SPOKES).toFloat()
        drawLine(color, c, Offset(c.x + cos(a) * r, c.y + sin(a) * r), strokeWidth = 3f)
    }
    // each ring sags slightly between spokes, like a real web
    for (ring in 1..RINGS) {
        val rr = r * ring / RINGS
        val path = Path()
        for (s in 0..SPOKES) {
            val a0 = (2 * PI * (s - 1) / SPOKES).toFloat()
            val a1 = (2 * PI * s / SPOKES).toFloat()
            val p1 = Offset(c.x + cos(a1) * rr, c.y + sin(a1) * rr)
            if (s == 0) { path.moveTo(p1.x, p1.y); continue }
            val mid = (a0 + a1) / 2
            val sag = rr * 0.9f
            path.quadraticTo(c.x + cos(mid) * sag, c.y + sin(mid) * sag, p1.x, p1.y)
        }
        drawPath(path, color, style = Stroke(2.5f))
    }
}

private fun DrawScope.drawNode(p: Offset, radius: Float, fill: Color) {
    drawCircle(Ink, radius, Offset(p.x + 6f, p.y + 6f))   // hard shadow
    drawCircle(fill, radius, p)
    drawCircle(Ink, radius, p, style = Stroke(6f))
}

private fun DrawScope.drawSpider(c: Offset, s: Float) {
    // eight legs, each bent at the knee
    for (side in listOf(-1f, 1f)) for (k in 0 until 4) {
        val a = (-0.9f + k * 0.6f)
        val knee = Offset(c.x + side * s * 1.4f * cos(a), c.y + s * 1.2f * sin(a) - s * 0.6f)
        val foot = Offset(c.x + side * s * 2.3f * cos(a), c.y + s * 1.9f * sin(a) + s * 0.4f)
        drawLine(Ink, c, knee, strokeWidth = s * 0.16f, cap = StrokeCap.Round)
        drawLine(Ink, knee, foot, strokeWidth = s * 0.16f, cap = StrokeCap.Round)
    }
    drawCircle(Ink, s * 0.85f, Offset(c.x, c.y + s * 0.35f))  // abdomen
    drawCircle(Ink, s * 0.5f, Offset(c.x, c.y - s * 0.7f))    // head
    drawCircle(Yellow, s * 0.14f, Offset(c.x - s * 0.18f, c.y - s * 0.78f))
    drawCircle(Yellow, s * 0.14f, Offset(c.x + s * 0.18f, c.y - s * 0.78f))
}
