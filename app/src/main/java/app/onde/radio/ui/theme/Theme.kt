package app.onde.radio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Couleurs de la maquette. */
object Onde {
    val Ink = Color(0xFF0E1116)
    val Surface = Color(0xFF161A21)
    val Surface2 = Color(0xFF1B2029)
    val Line = Color(0xFF262C36)
    val Text = Color(0xFFF3F4F6)
    val TextSoft = Color(0xFFC7CDD6)
    val Muted = Color(0xFF9AA3AF)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFF87171)

    /** Couleurs des pochettes générées quand une station n'a pas de logo. */
    val ArtPalette = listOf(
        Color(0xFF7C9CFF), Color(0xFFF28B66), Color(0xFF8BD3B7), Color(0xFFB9A4FF),
        Color(0xFF5EC8F2), Color(0xFFF2C25E), Color(0xFFE879A6),
    )

    fun artColor(key: String): Color = ArtPalette[Math.floorMod(key.hashCode(), ArtPalette.size)]
}

@Composable
fun OndeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Onde.Amber,
            onPrimary = Onde.Ink,
            background = Onde.Ink,
            onBackground = Onde.Text,
            surface = Onde.Ink,
            onSurface = Onde.Text,
            surfaceVariant = Onde.Surface2,
            onSurfaceVariant = Onde.Muted,
            surfaceContainerHigh = Onde.Surface2,
            inverseSurface = Onde.Text,
            inverseOnSurface = Onde.Ink,
            outline = Onde.Line,
            error = Onde.Red,
        ),
        typography = Typography(),
        content = content,
    )
}
