package ai.maynards.mesh.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// The same surfaces the laptop panel uses, so the two are visibly one product:
// one page colour, one card level, hairline rules, and colour only where it means something.
// Two themes. Each name reads the current one, so every screen follows a switch without being touched.
// Light keeps every text colour at 4.5:1 or better on its surface, and the accents are one shade darker
// so green, amber and red stay readable on white.
object Themes {
    var light by mutableStateOf(false)
        private set

    fun load(ctx: Context) {
        light = ctx.getSharedPreferences("mesh", Context.MODE_PRIVATE).getBoolean("light", false)
    }

    fun set(ctx: Context, on: Boolean) {
        light = on
        ctx.getSharedPreferences("mesh", Context.MODE_PRIVATE).edit().putBoolean("light", on).apply()
    }
}

private fun pick(dark: Long, light: Long) = Color(if (Themes.light) light else dark)

val Ink: Color get() = pick(0xFFEDEDED, 0xFF18181B)            // primary text
val Muted: Color get() = pick(0xFF8B8B93, 0xFF52525B)          // secondary text
val Faint: Color get() = pick(0xFF63636B, 0xFF6B6B73)          // the quietest detail
val Paper: Color get() = pick(0xFF111113, 0xFFFFFFFF)          // a card
val Raised: Color get() = pick(0xFF18181B, 0xFFF1F1F3)         // a control, or a card on a card
val Cream: Color get() = pick(0xFF09090B, 0xFFF7F7F8)          // the page
val Line: Color get() = pick(0xFF26262A, 0xFFE2E2E6)           // every rule, one pixel
val LineSoft: Color get() = pick(0xFF1C1C20, 0xFFECECEF)
val Pressed: Color get() = pick(0xFFFFFFFF, 0xFF3F3F46)        // a primary button while it is held

val Term: Color get() = pick(0xFF4ADE80, 0xFF15803D)           // live, good, yours
val Warn: Color get() = pick(0xFFFBBF24, 0xFFB45309)           // attention
val Bad: Color get() = pick(0xFFF87171, 0xFFB91C1C)            // failed
val Helper: Color get() = pick(0xFFA78BFA, 0xFF6D28D9)         // the helper side of the mesh
val Info: Color get() = pick(0xFF60A5FA, 0xFF1D4ED8)

// kept so older screens still compile and read the same
val Yellow: Color get() = pick(0xFF2A2210, 0xFFFEF3C7)         // an attention surface, not a shout
val HostGreen: Color get() = pick(0xFF10231A, 0xFFDCFCE7)
val HelperPurple: Color get() = pick(0xFF1A1730, 0xFFEDE9FE)
val Danger: Color get() = pick(0xFF2A1416, 0xFFFEE2E2)
val Tight: Color get() = pick(0xFF2A2210, 0xFFFEF3C7)
val Shadow: Color get() = pick(0xFF000000, 0x22000000)

val Border = 1.dp

@Composable
fun MeshTheme(content: @Composable () -> Unit) {
    // Prose in the system sans, and mono only where the content is a number, an id or a path.
    // Everything in mono reads as a terminal toy; everything in sans reads as a document. The mix is
    // what makes a developer tool look like one, so Mono(), Label() and the status strip ask for it
    // explicitly and nothing else gets it.
    val scheme = if (Themes.light)
        lightColorScheme(primary = Term, onPrimary = Cream, secondary = Yellow, onSecondary = Ink,
            background = Cream, onBackground = Ink, surface = Paper, onSurface = Ink)
    else
        darkColorScheme(primary = Term, onPrimary = Cream, secondary = Yellow, onSecondary = Ink,
            background = Cream, onBackground = Ink, surface = Paper, onSurface = Ink)
    MaterialTheme(colorScheme = scheme, content = content)
}
