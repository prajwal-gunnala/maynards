package ai.maynards.mesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
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
    shadow: Dp = 6.dp,
    radius: Dp = 12.dp,
    pad: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.padding(end = shadow, bottom = shadow)) {
        Box(Modifier.matchParentSize().offset(shadow, shadow).background(Ink, shape))
        Box(Modifier.fillMaxWidth().background(fill, shape).border(Border, Ink, shape).padding(pad)) { content() }
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
    val lift = if (pressed || !enabled) 0.dp else 4.dp
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.padding(end = 4.dp, bottom = 4.dp)) {
        Box(Modifier.matchParentSize().offset(4.dp, 4.dp).background(if (enabled) Ink else Color.Transparent, shape))
        Box(
            Modifier.offset(4.dp - lift, 4.dp - lift).fillMaxWidth()
                .background(if (enabled) fill else Cream, shape)
                .border(Border, if (enabled) Ink else Muted, shape)
                .clickable(source, indication = null, enabled = enabled, onClick = onClick)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text.uppercase(), fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 1.sp,
                color = if (enabled) Ink else Muted)
        }
    }
}

/** A small tilted label, like a sticker slapped on the card. */
@Composable
fun Sticker(text: String, fill: Color = Yellow, tilt: Float = -3f) {
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.rotate(tilt).padding(end = 2.dp, bottom = 2.dp)) {
        Box(Modifier.matchParentSize().offset(2.dp, 2.dp).background(Ink, shape))
        Text(
            text.uppercase(),
            Modifier.background(fill, shape).border(2.dp, Ink, shape).padding(horizontal = 9.dp, vertical = 3.dp),
            fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.5.sp, color = Ink,
        )
    }
}

@Composable
fun Label(text: String, color: Color = Ink) =
    Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Black, color = color)

@Composable
fun Title(text: String, size: Int = 30) =
    Text(text.uppercase(), fontSize = size.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp, color = Ink,
        lineHeight = (size + 2).sp)

@Composable
fun Small(text: String, color: Color = Muted) =
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)

@Composable
fun Mono(text: String, size: Int = 12, color: Color = Ink) =
    Text(text, fontFamily = FontFamily.Monospace, fontSize = size.sp, fontWeight = FontWeight.Bold, color = color)
