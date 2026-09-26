package ai.maynards.mesh.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// The same surfaces the laptop panel uses, so the two are visibly one product:
// near-black page, one card level, hairline rules, and colour only where it means something.
// The names are unchanged, so every screen keeps reading the way it did.
val Ink = Color(0xFFEDEDED)        // primary text
val Muted = Color(0xFF8B8B93)      // secondary text
val Faint = Color(0xFF63636B)      // the quietest detail
val Paper = Color(0xFF111113)      // a card
val Raised = Color(0xFF18181B)     // a control, or a card on a card
val Cream = Color(0xFF09090B)      // the page
val Line = Color(0xFF26262A)       // every rule, one pixel
val LineSoft = Color(0xFF1C1C20)

val Term = Color(0xFF4ADE80)       // live, good, yours
val Warn = Color(0xFFFBBF24)       // attention
val Bad = Color(0xFFF87171)        // failed
val Helper = Color(0xFFA78BFA)     // the helper side of the mesh
val Info = Color(0xFF60A5FA)

// kept so older screens still compile and read the same
val Yellow = Color(0xFF2A2210)     // an attention surface, not a shout
val HostGreen = Color(0xFF10231A)
val HelperPurple = Color(0xFF1A1730)
val Danger = Color(0xFF2A1416)
val Tight = Color(0xFF2A2210)
val Shadow = Color(0xFF000000)

val Border = 1.dp

private val Colors = darkColorScheme(
    primary = Term,
    onPrimary = Cream,
    secondary = Yellow,
    onSecondary = Ink,
    background = Cream,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
)

@Composable
fun MeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors) {
        // one switch for the whole app: everything is typed in the font a terminal uses
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)) {
            content()
        }
    }
}
