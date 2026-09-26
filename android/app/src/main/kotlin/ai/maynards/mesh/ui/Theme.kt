package ai.maynards.mesh.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// A developer's console: a dark page, hairline rules, one accent that carries the state.
// The names are unchanged, so every screen keeps reading the same way.
val Ink = Color(0xFFD3DBD7)        // text, and every rule
val Paper = Color(0xFF11171A)      // a card
val Cream = Color(0xFF0A0E10)      // the page, and a recessed panel
val Muted = Color(0xFF7C8B86)
val Term = Color(0xFF8EE6A8)       // the accent: anything you can act on
val Yellow = Color(0xFF3A2E0C)     // attention, without shouting
val HostGreen = Color(0xFF12321F)
val HelperPurple = Color(0xFF201C3A)
val Danger = Color(0xFF3C1618)
val Tight = Color(0xFF33280D)
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
