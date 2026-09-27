package ai.maynards.mesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A card with a thick border and a hard black shadow offset to the bottom right. */
@Composable
fun NBox(
    modifier: Modifier = Modifier,
    fill: Color = Paper,
    shadow: Dp = 0.dp,          // kept in the signature; the design has no shadows
    radius: Dp = 8.dp,
    pad: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(modifier) {
        Box(Modifier.fillMaxWidth().background(fill, shape).border(Border, Line, shape).padding(pad)) { content() }
    }
}

/** A button that sinks into its shadow when pressed. */
@Composable
fun NButton(
    text: String,
    modifier: Modifier = Modifier,
    fill: Color = Yellow,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(7.dp)
    val primary = fill == Term
    val bg = when {
        !enabled -> Raised
        primary -> if (pressed) Pressed else Ink
        pressed -> Line
        else -> Raised
    }
    Box(modifier) {
        Box(
            Modifier.fillMaxWidth().background(bg, shape)
                .border(Border, if (primary && enabled) bg else Line, shape)
                .clickable(source, indication = null, enabled = enabled, onClick = onClick)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontWeight = FontWeight.Medium, fontSize = 13.5.sp,
                color = if (!enabled) Faint else if (primary) Cream else Ink)
        }
    }
}

/** A small tilted label, like a sticker slapped on the card. */
@Composable
fun Sticker(text: String, fill: Color = Raised, tilt: Float = 0f) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text,
        Modifier.background(Raised, shape).border(Border, Line, shape).padding(horizontal = 9.dp, vertical = 2.dp),
        fontSize = 11.sp, color = when (fill) { HostGreen -> Term; HelperPurple -> Helper; Danger -> Bad; Tight -> Warn; else -> Muted },
        fontFamily = FontFamily.Monospace,
    )
}

/** A square icon tile with a thick border, like an app badge. */
@Composable
fun BigIcon(icon: ImageVector, fill: Color = Raised, size: Dp = 40.dp) {
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.size(size).background(Raised, shape).border(Border, Line, shape), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = when (fill) { HostGreen -> Term; HelperPurple -> Helper; else -> Muted })
    }
}

/** A label with a small icon in front. */
@Composable
fun IconLabel(icon: ImageVector, text: String, color: Color = Ink) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Label(text, color)
    }
}

@Composable
fun Label(text: String, color: Color = Faint) =
    Text(text.uppercase(), fontSize = 10.5.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Monospace, color = color)

@Composable
fun Title(text: String, size: Int = 20) =
    Text(text, fontSize = size.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, color = Ink,
        lineHeight = (size + 5).sp)

@Composable
fun Small(text: String, color: Color = Muted) =
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)

@Composable
fun Mono(text: String, size: Int = 12, color: Color = Muted) =
    Text(text, fontFamily = FontFamily.Monospace, fontSize = size.sp, fontWeight = FontWeight.Normal, color = color)
