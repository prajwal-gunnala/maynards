package ai.maynards.mesh.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF7CF29C)
val Warn = Color(0xFFF2C94C)
val Bad = Color(0xFFFF6B6B)
val Muted = Color(0xFF8A94A6)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF08140C),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE8EDF2),
    surface = Color(0xFF151B23),
    onSurface = Color(0xFFE8EDF2),
    surfaceVariant = Color(0xFF1C2430),
    onSurfaceVariant = Muted,
)

@Composable
fun MeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
