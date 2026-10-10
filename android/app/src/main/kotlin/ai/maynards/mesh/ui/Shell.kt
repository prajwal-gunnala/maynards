package ai.maynards.mesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The strip that never moves. In ten seconds across a room it should say what is running, how many
 * devices are holding it, which layers this phone itself has, and whether the internet is off.
 */
@Composable
fun StatusStrip(
    state: String,
    stateColor: Color,
    model: String?,
    devices: Int,
    layers: String?,
    offline: Boolean,
    tps: Double?,
) {
    Row(
        Modifier.fillMaxWidth().background(Cream).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(stateColor))
        // one line, cut with … when it is too long: a long model name used to stack the rest letter by letter
        val line = listOfNotNull(state, model?.take(18), if (devices == 1) "1 device" else "$devices devices", layers,
            tps?.let { "%.1f tok/s".format(it) }).joinToString(" · ")
        Text(line, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (offline) Mono("OFFLINE", 11, Term)
    }
    Rule()
}

@Composable
private fun Dot() = Text("·", color = Faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

@Composable
fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

/** Every screen says what it is for, in one sentence, before it shows anything. */
@Composable
fun PageHeader(title: String, description: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink, letterSpacing = (-0.4).sp)
            Spacer(Modifier.height(3.dp))
            Text(description, fontSize = 13.sp, color = Muted, lineHeight = 18.sp)
        }
        if (action != null) { Spacer(Modifier.width(12.dp)); action() }
    }
}

/** A blank panel tells you nothing. This says what will appear here and how to make it appear. */
@Composable
fun EmptyState(title: String, description: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 34.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 14.sp, color = Muted, fontWeight = FontWeight.Medium)
        Text(description, fontSize = 13.sp, color = Faint, lineHeight = 19.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(4.dp)); action() }
    }
}

/** A small caps rule between groups of things. */
@Composable
fun Section(text: String) =
    Text(text.uppercase(), fontSize = 10.5.sp, letterSpacing = 1.sp, color = Faint,
        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))

/** One number, said once, with what it means underneath. */
@Composable
fun StatTile(icon: ImageVector, key: String, value: String, sub: String = "", modifier: Modifier = Modifier) {
    Column(
        modifier.background(Paper, RoundedCornerShape(8.dp)).border(Border, Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(13.dp), tint = Faint)
            Spacer(Modifier.width(6.dp))
            Text(key, fontSize = 11.sp, color = Faint)
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink, fontFamily = FontFamily.Monospace)
            if (sub.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(sub, fontSize = 11.sp, color = Faint, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

/** A pill that carries state, not decoration. */
@Composable
fun Tag(text: String, color: Color = Muted) =
    Text(
        text, fontSize = 11.sp, color = color, fontFamily = FontFamily.Monospace,
        modifier = Modifier.background(Raised, RoundedCornerShape(999.dp))
            .border(Border, if (color == Muted) Line else color.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 2.dp),
    )

/** The bottom navigation: where you are, and where else you can be. */
@Composable
fun NavBar(items: List<Pair<String, ImageVector>>, selected: Int, onSelect: (Int) -> Unit) {
    Rule()
    Row(Modifier.fillMaxWidth().background(Cream).padding(horizontal = 8.dp, vertical = 7.dp)) {
        items.forEachIndexed { i, (label, icon) ->
            val on = i == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onSelect(i) }
                    .background(if (on) Raised else Color.Transparent).padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, null, Modifier.size(19.dp), tint = if (on) Term else Muted)
                Spacer(Modifier.height(3.dp))
                Text(label, fontSize = 10.5.sp, color = if (on) Ink else Muted,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}
