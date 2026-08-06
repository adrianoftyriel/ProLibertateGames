package org.prolibertate.games.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One band of a tartan sett, measured in threads. */
data class TartanStripe(val color: Color, val threads: Int)

val WallaceRed = Color(0xFFB3121B)
val WallaceBlack = Color(0xFF111111)
val WallaceGold = Color(0xFFF2C200)

/**
 * The Wallace sett.
 *
 * NOTE: this is an approximation, not a certified threadcount. It reproduces
 * what the Wallace tartan reads as — a bold red and black check divided by a
 * narrow gold overstripe — but the exact thread counts held by the Scottish
 * Register of Tartans could not be verified from here, so the numbers below
 * are chosen to look right rather than to be authoritative. They are plain
 * data: replacing them with the registered counts changes the whole app's
 * branding and needs no other edit.
 */
val WallaceSett: List<TartanStripe> = listOf(
    TartanStripe(WallaceRed, 40),
    TartanStripe(WallaceBlack, 40),
    TartanStripe(WallaceGold, 4),
)

/**
 * Draws a tartan by laying the sett down vertically as warp, then across as
 * weft at half opacity. The overlap is what produces the blended squares and
 * the sense of a woven cloth rather than a printed grid — red warp under black
 * weft comes out a darker red, exactly as it does on the loom.
 */
fun DrawScope.drawTartan(
    sett: List<TartanStripe> = WallaceSett,
    threadSize: Float,
) {
    if (sett.isEmpty() || threadSize <= 0f) return
    val repeat = sett.sumOf { it.threads } * threadSize
    if (repeat <= 0f) return

    // Warp: full-height bands.
    var x = 0f
    while (x < size.width) {
        var offset = x
        for (stripe in sett) {
            val width = stripe.threads * threadSize
            drawRect(
                color = stripe.color,
                topLeft = Offset(offset, 0f),
                size = Size(width, size.height),
            )
            offset += width
        }
        x += repeat
    }

    // Weft: full-width bands, half opacity so the warp shows through.
    var y = 0f
    while (y < size.height) {
        var offset = y
        for (stripe in sett) {
            val height = stripe.threads * threadSize
            drawRect(
                color = stripe.color.copy(alpha = 0.5f),
                topLeft = Offset(0f, offset),
                size = Size(size.width, height),
            )
            offset += height
        }
        y += repeat
    }
}

/**
 * A tartan fill. [threadWidth] sets the weave scale, so the same cloth can be
 * a fine weave on a phone and a coarse one on a tablet without redrawing it.
 */
@Composable
fun TartanBackground(
    modifier: Modifier = Modifier,
    sett: List<TartanStripe> = WallaceSett,
    threadWidth: Dp = 2.dp,
) {
    Canvas(modifier = modifier) {
        drawTartan(sett = sett, threadSize = threadWidth.toPx())
    }
}
