package org.prolibertate.games.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.prolibertate.games.BuildConfig

/** One band of a tartan sett, measured in threads. */
data class TartanStripe(val color: Color, val threads: Int)

val WallaceRed = Color(0xFFE01B24)
val WallaceBlack = Color(0xFF0C0C0C)
val WallaceGold = Color(0xFFF2C518)

/**
 * The green the hunting sett puts where the clan sett puts red.
 *
 * Must match `tartan_field` for a dev build in app/build.gradle.kts, which is
 * what the launcher icon is woven from — otherwise the icon and the splash it
 * opens on would be visibly different cloth.
 */
val WallaceHuntingGreen = Color(0xFF1E7A3C)

/**
 * Expands a half-sett into a full repeat by reflecting it.
 *
 * Tartans are specified as a half-sett between two pivots and woven out to
 * both sides, so `A B C` becomes `A B C B` — the pivot stripes are not
 * doubled. Writing the data this way keeps it in the same form a threadcount
 * is published in, instead of a flat list that has to be kept symmetric by
 * hand.
 */
fun List<TartanStripe>.pivoted(): List<TartanStripe> =
    if (size < 3) this else this + drop(1).dropLast(1).reversed()

/**
 * The Wallace sett, from its published threadcount: **K/4 R32 K32 Y/4**.
 *
 * The slashes mark the pivots, so this is the half-sett and it reflects out to
 * `K4 R32 K32 Y4 K32 R32` — 136 threads, roughly half black, half red, with a
 * yellow line worth 3%. Two consequences that are easy to get backwards and
 * that define the pattern:
 *
 *  - the yellow overstripe runs down the **centre of a wide black band**, not
 *    between red and black;
 *  - the narrow black guard sits **between two red blocks**, splitting the red
 *    into pairs.
 *
 * The threadcount itself lives in [wallaceSett], because the hunting colourway
 * below is the same cloth in a different colour and must not drift from it.
 */
val WallaceSett: List<TartanStripe> = wallaceSett(WallaceRed)

/**
 * Wallace Hunting: the same threadcount woven in green.
 *
 * Hunting setts are the clan cloth in muted, outdoor colours, and for Wallace
 * that is the one substitution — green for the red — with the black ground and
 * the yellow overstripe untouched. Deriving it from the same builder rather
 * than writing the numbers out twice is what keeps that true: a change to the
 * threadcount cannot land in one colourway and miss the other.
 */
val WallaceHuntingSett: List<TartanStripe> = wallaceSett(WallaceHuntingGreen)

/** The Wallace threadcount woven with [field] in place of the red. */
private fun wallaceSett(field: Color): List<TartanStripe> = listOf(
    TartanStripe(WallaceBlack, 4),
    TartanStripe(field, 32),
    TartanStripe(WallaceBlack, 32),
    TartanStripe(WallaceGold, 4),
).pivoted()

/**
 * The cloth this build wears: the clan sett on production, the hunting sett on
 * dev.
 *
 * A dev build installs as its own app beside the production one, so it has to
 * be recognisable at a glance in the app drawer and on the splash — the green
 * says which of the two copies just opened without anything having to be read.
 */
val AppSett: List<TartanStripe> =
    if (BuildConfig.DEV_BUILD) WallaceHuntingSett else WallaceSett

/** Threads in one full repeat of a sett. */
val List<TartanStripe>.threadsPerRepeat: Int get() = sumOf { it.threads }

/**
 * The thread width that fits [repeats] repeats of [sett] across [across].
 *
 * Scale has to be derived from the sett rather than fixed, because changing a
 * threadcount changes how many threads a repeat contains — a fixed width and a
 * larger sett silently zooms in until the cloth is a couple of enormous blocks
 * and stops reading as tartan at all.
 */
fun threadWidthFor(
    sett: List<TartanStripe> = AppSett,
    across: Dp,
    repeats: Float = 2f,
): Dp = across / (sett.threadsPerRepeat * repeats)

/**
 * Draws a tartan by laying the sett down vertically as warp, then across as
 * weft at half opacity. The overlap is what produces the blended squares and
 * the sense of a woven cloth rather than a printed grid — red warp under black
 * weft comes out a darker red, exactly as it does on the loom.
 *
 * [rotationDegrees] turns the cloth on the bias. The stripes are drawn well
 * past the edges of the canvas so the corners stay covered once it is turned.
 */
fun DrawScope.drawTartan(
    sett: List<TartanStripe> = AppSett,
    threadSize: Float,
    rotationDegrees: Float = 0f,
) {
    if (sett.isEmpty() || threadSize <= 0f) return
    val repeat = sett.sumOf { it.threads } * threadSize
    if (repeat <= 0f) return

    // Turning a square by 45 degrees needs about 0.21 of its size spare on
    // every edge; three quarters is generous enough for any angle.
    val pad = if (rotationDegrees == 0f) 0f else size.maxDimension * 0.75f
    val left = -pad
    val top = -pad
    val right = size.width + pad
    val bottom = size.height + pad

    rotate(degrees = rotationDegrees) {
        // Warp: full-height bands.
        var x = left - (left.mod(repeat))
        while (x < right) {
            var offset = x
            for (stripe in sett) {
                val width = stripe.threads * threadSize
                drawRect(
                    color = stripe.color,
                    topLeft = Offset(offset, top),
                    size = Size(width, bottom - top),
                )
                offset += width
            }
            x += repeat
        }

        // Weft: full-width bands, half opacity so the warp shows through.
        var y = top - (top.mod(repeat))
        while (y < bottom) {
            var offset = y
            for (stripe in sett) {
                val height = stripe.threads * threadSize
                drawRect(
                    color = stripe.color.copy(alpha = 0.5f),
                    topLeft = Offset(left, offset),
                    size = Size(right - left, height),
                )
                offset += height
            }
            y += repeat
        }
    }
}

/**
 * A tartan fill. [threadWidth] sets the weave scale, so the same cloth can be
 * a fine weave on a phone and a coarse one on a tablet without redrawing it.
 */
@Composable
fun TartanBackground(
    modifier: Modifier = Modifier,
    sett: List<TartanStripe> = AppSett,
    threadWidth: Dp = 2.dp,
    rotationDegrees: Float = 0f,
) {
    Canvas(modifier = modifier) {
        drawTartan(
            sett = sett,
            threadSize = threadWidth.toPx(),
            rotationDegrees = rotationDegrees,
        )
    }
}
