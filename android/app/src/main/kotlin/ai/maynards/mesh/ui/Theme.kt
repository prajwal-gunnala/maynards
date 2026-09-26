package ai.maynards.mesh.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Neobrutalist palette: black ink, thick borders, hard shadows, flat bright fills.
val Ink = Color(0xFF0A0A0A)
val Paper = Color(0xFFFFFFFF)
val Cream = Color(0xFFF3F1EA)
val Muted = Color(0xFF55555C)
val Yellow = Color(0xFFFFD60A)
val HostGreen = Color(0xFFB8F0C6)
val HelperPurple = Color(0xFFCFC3FF)
val Danger = Color(0xFFFF8A80)
val Tight = Color(0xFFFFE08A)

val Border = 3.dp

private val Colors = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    secondary = Yellow,
    onSecondary = Ink,
    background = Cream,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
)

@Composable
fun MeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
