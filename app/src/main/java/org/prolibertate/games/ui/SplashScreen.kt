package org.prolibertate.games.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.prolibertate.games.R
import org.prolibertate.games.ui.theme.TartanBackground
import org.prolibertate.games.ui.theme.threadWidthFor

/** How long the splash holds at full opacity before fading. */
private const val SPLASH_HOLD_MILLIS = 2_000L

private const val SPLASH_FADE_MILLIS = 600

/**
 * Uncial Antiqua — a Celtic uncial face, bundled under the SIL Open Font
 * License. See docs/licenses/UncialAntiqua-OFL.txt.
 */
val CelticFontFamily = FontFamily(Font(R.font.uncial_antiqua))

/**
 * The launch screen: Wallace tartan under the name of the house.
 *
 * Drawn as an overlay rather than a separate destination, so the menu is
 * already composed and ready behind it and the fade reveals a live screen
 * instead of a blank one.
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var fading by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (fading) 0f else 1f,
        animationSpec = tween(durationMillis = SPLASH_FADE_MILLIS),
        label = "splashFade",
    )

    LaunchedEffect(Unit) {
        delay(SPLASH_HOLD_MILLIS)
        fading = true
        delay(SPLASH_FADE_MILLIS.toLong())
        onFinished()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().alpha(alpha)) {
        // Read the constraints here and hold them in locals: inside Column the
        // implicit receiver becomes ColumnScope, and maxWidth/maxHeight are no
        // longer in scope.
        val shortestSide = minOf(maxWidth, maxHeight).value

        // Weave scale is derived from the sett, not a fixed fraction, so a
        // couple of full repeats are always on screen whatever the threadcount
        // says and whatever the display is.
        val threadWidth = threadWidthFor(across = shortestSide.dp, repeats = 2f)
        val titleSize = (shortestSide * 0.13f).sp
        TartanBackground(
            modifier = Modifier.fillMaxSize(),
            threadWidth = threadWidth,
            // On the bias, matching the icon.
            rotationDegrees = 45f,
        )

        // A scrim keeps the lettering legible over the busiest part of the sett.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Pro Libertate",
                style = TextStyle(
                    fontFamily = CelticFontFamily,
                    fontSize = titleSize,
                    color = Color(0xFFF7EFD8),
                    textAlign = TextAlign.Center,
                    shadow = Shadow(
                        color = Color.Black,
                        offset = Offset(0f, 3f),
                        blurRadius = 10f,
                    ),
                ),
            )
            Text(
                text = "Games",
                style = TextStyle(
                    fontFamily = CelticFontFamily,
                    fontSize = titleSize,
                    color = Color(0xFFF2C200),
                    textAlign = TextAlign.Center,
                    shadow = Shadow(
                        color = Color.Black,
                        offset = Offset(0f, 3f),
                        blurRadius = 10f,
                    ),
                ),
            )
        }
    }
}
