package org.prolibertate.games.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Card-table green, which the board and table surfaces are drawn on. */
val FeltGreen = Color(0xFF1B5E20)
val FeltGreenDark = Color(0xFF0B3D12)
val Parchment = Color(0xFFF4EFE4)
val CardRed = Color(0xFFB3261E)
val CardBlack = Color(0xFF1C1B1F)

val TeamColours: List<Color> = listOf(
    Color(0xFF1565C0), // blue
    Color(0xFFC62828), // red
    Color(0xFF2E7D32), // green, only used with three teams
)

private val DarkColours = darkColorScheme(
    primary = Color(0xFF7BC67E),
    onPrimary = Color(0xFF00390A),
    secondary = Color(0xFFB9CCB4),
    background = Color(0xFF10140F),
    surface = Color(0xFF1A1F18),
    surfaceVariant = Color(0xFF2A2F27),
)

private val LightColours = lightColorScheme(
    primary = FeltGreen,
    onPrimary = Color.White,
    secondary = Color(0xFF52634F),
    background = Parchment,
    surface = Color(0xFFFBF8F1),
    surfaceVariant = Color(0xFFE0E4D8),
)

@Composable
fun ProLibertateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColours else LightColours,
        content = content,
    )
}
