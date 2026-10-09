package org.prolibertate.games.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.settings.Settings
import org.prolibertate.games.ui.common.CARD_ASPECT
import org.prolibertate.games.ui.common.CARD_RESTING_ELEVATION
import org.prolibertate.games.ui.common.CardBackView
import org.prolibertate.games.ui.common.PlayingCardView

/**
 * How cards move.
 *
 * A card thrown onto a table does three things that a card faded into place
 * does not: it arrives from the side of the person who threw it, it overshoots
 * and rocks back rather than stopping dead, and it comes to rest a little
 * crooked and a little off the spot it was aimed at. This file is those three
 * things, and nothing else — no ragdolls, no collisions. Real cards played by
 * a real hand are only slightly random, and the whole difficulty is in keeping
 * the randomness *small*: a table where every card lands at a jaunty angle
 * stops looking like cards and starts looking like a screensaver.
 *
 * The randomness is therefore bounded and deterministic. Bounded because every
 * scatter is a fraction of a card width or a handful of degrees; deterministic
 * because it is hashed out of the card's own identity rather than drawn from a
 * generator, which is what stops a card twitching to a new angle every time
 * something else on the screen recomposes. The same card in the same place
 * always lies the same way, and two cards never lie the same way as each other.
 */

// ---------------------------------------------------------------------------
// Bounds. Every one of these is deliberately smaller than it wants to be.
// ---------------------------------------------------------------------------

/** Most a settled card is left turned by, in degrees. */
const val REST_TILT_DEGREES = 4.5f

/** Most a settled card misses its spot by, as a fraction of its own width. */
const val REST_DRIFT_FRACTION = 0.055f

/**
 * The same two bounds for a card thrown onto a heap rather than laid in front
 * of a player, which is a good deal less tidy. They are also what makes a heap
 * legible: with the tight bounds a discard pile of five cards looks like one
 * card, because the four underneath are hidden to within a twentieth of a card
 * width of the top one.
 */
const val HEAP_TILT_DEGREES = 8f
const val HEAP_DRIFT_FRACTION = 0.15f

/** Extra turn a card carries while it is still travelling. */
const val THROW_SPIN_DEGREES = 15f

/** Tilt of a card held in a hand, where the pack has been squared up. */
const val HAND_TILT_DEGREES = 1.6f

/** How far off the table a thrown card starts, in card widths. */
const val THROW_DISTANCE = 2.2f

/** How much bigger a card looks while it is above the table rather than on it. */
private const val THROW_SCALE = 1.11f

/**
 * Shadow under a card in flight. What it settles to is the ordinary resting
 * elevation every other card on the screen already has, read from there rather
 * than repeated, so a card that has just landed is indistinguishable from one
 * that has been lying there all along.
 */
private const val THROW_ELEVATION_DP = 9f
private val REST_ELEVATION_DP = CARD_RESTING_ELEVATION.value

/**
 * Bounce in the settle. Below 1 is an underdamped spring, so the card carries
 * past its resting place and comes back — which is the whole point. Much below
 * this and it wobbles like rubber.
 */
private const val SETTLE_DAMPING = 0.62f

/** Stiffness that gives [CARD_THROW_MILLIS] at normal speed. */
private const val SETTLE_STIFFNESS = 420f

/** Nominal flight time of a card, before the animation-speed setting. */
const val CARD_THROW_MILLIS = 380

/** Nominal time a finished trick takes to be gathered up. */
const val CARD_SWEEP_MILLIS = 520

/**
 * How much of the sweep is spent letting the stragglers go. Cards do not all
 * leave at once when a hand scoops them up, but they do all leave quickly.
 */
private const val SWEEP_SPREAD = 0.3f

/**
 * The last stretch of the sweep, over which a gathered card fades out. It has
 * to be late: the cards vanish exactly as the winner's pile grows by one, and
 * that swap is only invisible if they are still whole most of the way there.
 */
private const val SWEEP_FADE = 0.42f

/** How small four cards squared into a bundle end up against one card face. */
private const val GATHERED_SCALE = 0.62f

// ---------------------------------------------------------------------------
// The pure part: where a card lands, and how far through leaving it is.
// ---------------------------------------------------------------------------

/**
 * A hash, not a random number.
 *
 * Two rounds of a linear congruential step with a shift between them, which is
 * more than enough to stop neighbouring seeds producing neighbouring angles —
 * the visible failure would be a whole suit landing at the same tilt.
 */
private fun scramble(seed: Int): Int {
    var h = seed
    repeat(2) {
        h = h * 1_664_525 + 1_013_904_223
        h = h xor (h ushr 15)
    }
    return h
}

/**
 * A value in `[-1, 1)` for this [seed] and [salt], stable for the life of the
 * program and identical between runs. [salt] separates the several independent
 * numbers one card needs from a single seed.
 */
fun unitNoise(seed: Int, salt: Int): Float {
    val bits = scramble(seed * 31 + salt * 0x5F5F) ushr 8
    return bits / 8_388_608f - 1f
}

/** A seed for one card, optionally distinguished by where it is lying. */
fun cardSeed(card: Card, place: Int = 0): Int =
    (card.rank.ordinal * 4 + card.suit.ordinal) * 71 + place * 7919

/**
 * Where a card has come to rest, and how it got there.
 *
 * All four scatters come off one seed, so the same card always lies the same
 * way: [tiltDegrees] and the two drifts are where it ended up, [spinDegrees] is
 * the turn it was still carrying on the way in, and [lateness] is its place in
 * the queue when a group of cards moves together.
 */
@Immutable
data class CardRest(
    val tiltDegrees: Float,
    val driftX: Float,
    val driftY: Float,
    val spinDegrees: Float,
    val lateness: Float,
)

/** The resting scatter for [seed], within the bounds set at the top of the file. */
fun cardRest(
    seed: Int,
    tilt: Float = REST_TILT_DEGREES,
    drift: Float = REST_DRIFT_FRACTION,
): CardRest = CardRest(
    tiltDegrees = unitNoise(seed, 1) * tilt,
    driftX = unitNoise(seed, 2) * drift,
    driftY = unitNoise(seed, 3) * drift,
    spinDegrees = unitNoise(seed, 4) * THROW_SPIN_DEGREES,
    lateness = (unitNoise(seed, 5) + 1f) / 2f,
)

/**
 * The same, at the looser bounds a heap gets.
 *
 * A card keeps this scatter for as long as it is on the heap, including once
 * something has been played on top of it. Switching a card to a wider scatter
 * the moment it stopped being the top card would make it shuffle sideways on
 * its own, which is the one thing on a table that never happens.
 */
fun heapRest(seed: Int): CardRest = cardRest(seed, HEAP_TILT_DEGREES, HEAP_DRIFT_FRACTION)

/** The small tilt of a card sitting in a squared-up hand. */
fun handTilt(seed: Int): Float = unitNoise(seed, 6) * HAND_TILT_DEGREES

/**
 * Where a card thrown by a seat comes in from, in card widths, for a four-seat
 * table where relative seat 0 sits at the bottom and the rest run clockwise.
 * A card arrives from beyond its own player's edge, which is the direction it
 * would have been thrown from.
 */
fun seatOrigin(relative: Int, distance: Float = THROW_DISTANCE): Pair<Float, Float> =
    when (relative) {
        0 -> 0f to distance
        1 -> -distance to 0f
        2 -> 0f to -distance
        else -> distance to 0f
    }

/**
 * Where a card lands on a pile from, in card widths, when there is no seat to
 * read a direction off — a discard pile takes cards from everyone. The angle is
 * hashed rather than drawn, so a card always arrives from the same side.
 */
fun pileOrigin(seed: Int, distance: Float = THROW_DISTANCE): Pair<Float, Float> {
    val angle = (unitNoise(seed, 7) + 1f) * PI.toFloat()
    return cos(angle) * distance to sin(angle) * distance
}

/**
 * Where a card that has been played *on* lies, given how much ground the cards
 * now covering it take up — half the width and half the height of it, in
 * pixels.
 *
 * Pushed out towards the edge of that ground rather than scattered evenly
 * across it. A card buried dead centre under a set of four is a card nobody can
 * see, and the entire point of still drawing it is that you can tell the heap
 * has a history. Out at the edge a corner shows, which is what a real heap
 * looks like from above.
 */
fun buriedOffset(seed: Int, halfWidth: Float, halfHeight: Float): Pair<Float, Float> {
    val angle = (unitNoise(seed, 11) + 1f) * PI.toFloat()
    // Never right at the rim and never far inside it.
    val reach = 0.72f + 0.28f * abs(unitNoise(seed, 12))
    return cos(angle) * halfWidth * reach to sin(angle) * halfHeight * reach
}

/**
 * Acceleration curve for cards being dragged off a table: slow to break away,
 * then gone. Written out rather than taken from an easing class so the sweep
 * arithmetic stays plain Kotlin and can be tested without a composition.
 */
private fun accelerate(x: Float): Float = x * x * (0.35f + 0.65f * x)

/**
 * How far through leaving a single card is, given how far through the sweep as
 * a whole is. Cards start over a window of [spread] rather than together, so a
 * trick gathers up in one motion rather than four cards moving in lockstep.
 */
fun sweepProgress(sweep: Float, rest: CardRest, spread: Float = SWEEP_SPREAD): Float {
    val start = rest.lateness.coerceIn(0f, 1f) * spread.coerceIn(0f, 0.9f)
    val raw = ((sweep - start) / (1f - start)).coerceIn(0f, 1f)
    return accelerate(raw)
}

/** Straight interpolation, [fraction] being free to overshoot past one. */
fun blend(from: Float, to: Float, fraction: Float): Float =
    from + (to - from) * fraction

/**
 * How solid a card being gathered up still is. Whole for most of the journey
 * and gone at the end of it, so it disappears at the moment the winner's pile
 * grows rather than dissolving on the way over.
 */
fun gatherAlpha(leaving: Float): Float = ((1f - leaving) / SWEEP_FADE).coerceIn(0f, 1f)

/** And how far it has been squared down into the bundle it is joining. */
fun gatherScale(leaving: Float): Float = blend(1f, GATHERED_SCALE, leaving.coerceIn(0f, 1f))

// ---------------------------------------------------------------------------
// The Compose part.
// ---------------------------------------------------------------------------

/**
 * The animation-speed setting, so card motion follows the same slider as
 * everything else. A composition local rather than a parameter because it would
 * otherwise have to be threaded through every card screen and down into every
 * pile inside them, to say one number that never changes mid-game.
 */
val LocalCardSpeed = staticCompositionLocalOf { 1f }

/** A nominal duration, adjusted for the speed the player has chosen. */
@Composable
fun motionMillis(nominal: Int): Int {
    val speed = LocalCardSpeed.current.coerceIn(Settings.MIN_SPEED, Settings.MAX_SPEED)
    return (nominal / speed).toInt().coerceAtLeast(1)
}

/**
 * Where a card is, and what it looks like, part way through being thrown.
 *
 * [alpha] is only ever 0 or 1, and only ever 0 for a card waiting its turn in a
 * set that is being played together. Without it a delayed card would sit
 * plainly visible two card widths off the table until its moment came, which
 * reads as a card teleporting out and flying back rather than as one still in
 * somebody's hand.
 */
@Immutable
data class CardLanding(
    val offsetX: Float,
    val offsetY: Float,
    val rotation: Float,
    val scale: Float,
    val elevation: Dp,
    val alpha: Float = 1f,
)

/**
 * Follows one card from the moment it is played until it has stopped moving.
 *
 * The whole flight is one underdamped spring on a single 0-to-1 value, and
 * everything else is read off it. That is why the card overshoots its resting
 * place and rocks back instead of easing to a halt: the spring passes 1 and
 * returns, and the position, the angle and the height above the table all
 * follow it there and back together. Driving them separately was tried and
 * looked like three animations that happened to start at the same time.
 *
 * [key] is what counts as a new card arriving. It must change when the card in
 * this place changes and at no other time, or the card either fails to animate
 * or animates again every recomposition.
 */
@Composable
fun rememberCardLanding(
    key: Any?,
    rest: CardRest,
    facingDegrees: Float,
    fromX: Float,
    fromY: Float,
    cardWidthPx: Float,
    durationMillis: Int,
    delayMillis: Int = 0,
): CardLanding {
    val settle = remember(key) { Animatable(0f) }
    var launched by remember(key) { mutableStateOf(delayMillis <= 0) }
    LaunchedEffect(key) {
        if (delayMillis > 0) {
            delay(delayMillis.toLong())
            launched = true
        }
        settle.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = SETTLE_DAMPING,
                stiffness = SETTLE_STIFFNESS * CARD_THROW_MILLIS / durationMillis,
            ),
        )
    }

    val travelled = settle.value
    // Height above the table is the one thing that must not overshoot: a card
    // that has landed cannot rise again, whatever the spring is still doing.
    val landed = travelled.coerceIn(0f, 1f)
    return CardLanding(
        offsetX = blend(fromX, rest.driftX * cardWidthPx, travelled),
        offsetY = blend(fromY, rest.driftY * cardWidthPx, travelled),
        rotation = blend(
            facingDegrees + rest.spinDegrees,
            facingDegrees + rest.tiltDegrees,
            travelled,
        ),
        scale = blend(THROW_SCALE, 1f, landed),
        elevation = blend(THROW_ELEVATION_DP, REST_ELEVATION_DP, landed).dp,
        alpha = if (launched) 1f else 0f,
    )
}

/** Puts a card where its [landing] says it is. For cards drawn on their own. */
fun Modifier.landed(landing: CardLanding): Modifier = this
    .offset { IntOffset(landing.offsetX.roundToInt(), landing.offsetY.roundToInt()) }
    .graphicsLayer {
        scaleX = landing.scale
        scaleY = landing.scale
        rotationZ = landing.rotation
        alpha = landing.alpha
    }

/** How many cards of a pile are worth drawing. Below this it is not a heap. */
private const val PILE_DEPTH = 5

/**
 * How many taken tricks are worth drawing separately. Past this the pile stops
 * growing rather than climbing off the table — Wizard's last round is twenty
 * tricks, and twenty bundles of stagger is not a pile, it is a staircase.
 */
private const val WON_STACK_DEPTH = 8

/** How far each taken trick sits off the one under it, in card widths. */
private const val WON_STACK_STEP = 0.045f

/**
 * The tricks a player has taken, squared up and turned face down in front of
 * them.
 *
 * One card back per trick rather than four, which is what a real table looks
 * like: a trick is gathered, squared and put down as a single bundle, and from
 * above you see the top card of each bundle and the edges of the rest. The
 * count is the pile — it is the thing a player actually glances at to see how
 * the hand is going, and it is why cards are kept rather than binned.
 */
@Composable
fun WonTrickStack(
    tricks: Int,
    width: Dp,
    modifier: Modifier = Modifier,
    facingDegrees: Float = 0f,
    /** Where on the table these were gathered from, in pixels. */
    fromX: Float = 0f,
    fromY: Float = 0f,
) {
    if (tricks <= 0) return

    val shown = tricks.coerceAtMost(WON_STACK_DEPTH)
    val step = width * WON_STACK_STEP
    val widthPx = with(LocalDensity.current) { width.toPx() }
    val throwMillis = motionMillis(CARD_THROW_MILLIS)

    Box(
        modifier = modifier
            .padding(end = step * shown, bottom = step * shown)
            .width(width)
            .height(width / CARD_ASPECT),
    ) {
        for (bundle in 0 until shown) {
            key(bundle) {
                // Each bundle is squared up, not thrown down, so it gets a
                // fraction of the scatter a played card gets.
                val rest = cardRest(bundle * 613, tilt = REST_TILT_DEGREES * 0.5f)
                val top = bundle == shown - 1
                val landing = if (!top) {
                    CardLanding(
                        offsetX = 0f,
                        offsetY = 0f,
                        rotation = facingDegrees + rest.tiltDegrees,
                        scale = 1f,
                        elevation = REST_ELEVATION_DP.dp,
                    )
                } else {
                    // Only the newest bundle arrives; the ones under it have
                    // been sitting there since earlier in the hand.
                    rememberCardLanding(
                        key = tricks,
                        rest = rest.copy(driftX = 0f, driftY = 0f),
                        facingDegrees = facingDegrees,
                        fromX = fromX,
                        fromY = fromY,
                        cardWidthPx = widthPx,
                        durationMillis = throwMillis,
                    )
                }
                CardBackView(
                    width = width,
                    elevation = landing.elevation,
                    modifier = Modifier
                        .offset(x = step * bundle, y = step * bundle)
                        .landed(landing),
                )
            }
        }
    }
}

/** How wide a taken-trick pile is drawn against a card on the table. */
private const val WON_STACK_SCALE = 0.5f

/**
 * Everyone's taken tricks, at a table of four with partners opposite.
 *
 * Each pile sits beside its own player's card, one place clockwise along their
 * edge, which is both where a real player would put them and the one spot on
 * each edge that nothing else on the table is using.
 */
@Composable
fun BoxScope.TakenTricks(tricksWon: List<Int>, localSeat: Int, cardWidth: Dp) {
    val stackWidth = cardWidth * WON_STACK_SCALE
    val stackWidthPx = with(LocalDensity.current) { stackWidth.toPx() }

    for (seat in 0 until 4) {
        val relative = ((seat - localSeat) % 4 + 4) % 4
        val alignment = when (relative) {
            0 -> Alignment.BottomCenter
            1 -> Alignment.CenterStart
            2 -> Alignment.TopCenter
            else -> Alignment.CenterEnd
        }
        val (nudgeX, nudgeY) = when (relative) {
            0 -> cardWidth * 1.6f to 0.dp
            1 -> 0.dp to cardWidth * 1.4f
            2 -> -(cardWidth * 1.6f) to 0.dp
            else -> 0.dp to -(cardWidth * 1.4f)
        }
        // Gathered in from the middle of the table, which is the opposite way
        // from the edge this player sits at.
        val (outX, outY) = seatOrigin(relative, 2.5f)

        WonTrickStack(
            tricks = tricksWon.getOrElse(seat) { 0 },
            width = stackWidth,
            facingDegrees = relative * 90f,
            fromX = -outX * stackWidthPx,
            fromY = -outY * stackWidthPx,
            modifier = Modifier.align(alignment).offset(x = nudgeX, y = nudgeY),
        )
    }
}

/**
 * The same, round a table of any size: each pile sits half a seat clockwise
 * from its player's card, just inside the ring the cards are played on.
 */
@Composable
fun BoxScope.TakenTricksAround(
    tricksWon: List<Int>,
    localSeat: Int,
    players: Int,
    cardWidth: Dp,
    radiusPx: Float,
) {
    val stackWidth = cardWidth * WON_STACK_SCALE
    val stackWidthPx = with(LocalDensity.current) { stackWidth.toPx() }

    for (seat in 0 until players) {
        val relative = ((seat - localSeat) % players + players) % players
        val angle = 2.0 * PI * relative / players + PI / players
        val x = -sin(angle) * radiusPx
        val y = cos(angle) * radiusPx

        WonTrickStack(
            tricks = tricksWon.getOrElse(seat) { 0 },
            width = stackWidth,
            facingDegrees = relative * 360f / players,
            fromX = (sin(angle) * stackWidthPx * 2.5f).toFloat(),
            fromY = (-cos(angle) * stackWidthPx * 2.5f).toFloat(),
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) },
        )
    }
}

/**
 * Trick counts, held back until the cards have actually got there.
 *
 * The rules count a trick the instant its last card is played, which is a beat
 * before the cards are gathered and two before they arrive. Reading the count
 * straight from the state would grow the winner's pile while the trick was
 * still lying in the middle of the table, and the cards would then slide over
 * and land on a pile that had already counted them.
 */
@Composable
fun rememberArrivedTricks(
    tricksWon: List<Int>,
    sweeping: Boolean,
    sweepStarted: Boolean,
    sweepMillis: Int,
): List<Int> {
    var arrived by remember { mutableStateOf(tricksWon) }
    LaunchedEffect(tricksWon, sweeping, sweepStarted) {
        // Still on the table waiting to be read: the pile has not won it yet.
        if (sweeping && !sweepStarted) return@LaunchedEffect
        if (sweeping) delay(sweepMillis.toLong())
        arrived = tricksWon
    }
    return arrived
}

/** Holder for [rememberPileWatermark]. Deliberately not snapshot state. */
private class PileWatermark(var value: Int)

/**
 * How much of a pile was already lying there rather than played onto it.
 *
 * Cards above this line get thrown on; cards below it are simply drawn where
 * they lie. Two things have to be true and only one of them is obvious. The
 * obvious one is that a pile you have just opened the screen on was not built
 * in front of you, so none of it should fly in. The other is that a pile gets
 * *shorter* between rounds — a new deal replaces a twenty-card heap with a
 * single turned card — and a mark left at twenty would then sit above every
 * card of the new round and quietly switch the animation off for the rest of
 * the game. So the mark follows the pile down, and only ever down.
 *
 * A plain holder rather than snapshot state on purpose: this is written and
 * read within one composition, and it must not be the reason anything
 * recomposes.
 */
@Composable
private fun rememberPileWatermark(size: Int): Int {
    val mark = remember { PileWatermark(size) }
    if (size < mark.value) mark.value = size
    return mark.value
}

/**
 * A pile of cards seen from above.
 *
 * Only the top few are drawn, each lying where it fell, which is enough to read
 * as a heap without painting fifty cards nobody can see. Cards already on the
 * pile when the screen opens are drawn settled — the pile was built before you
 * got here — and only cards played after that are thrown on.
 */
@Composable
fun CardPile(
    cards: List<Card>,
    width: Dp,
    modifier: Modifier = Modifier,
    depth: Int = PILE_DEPTH,
) {
    if (cards.isEmpty()) return

    // Position in the pile, not position in the window, so a card keeps its
    // identity — and so its resting angle — as the window slides past it.
    val bottom = (cards.size - depth).coerceAtLeast(0)
    val alreadyDown = rememberPileWatermark(cards.size)
    val cardWidthPx = with(LocalDensity.current) { width.toPx() }
    val throwMillis = motionMillis(CARD_THROW_MILLIS)

    // Room for the scatter, so the outermost card is not clipped by whatever
    // this pile has been placed inside.
    val margin = width * HEAP_DRIFT_FRACTION
    Box(
        modifier = modifier
            .padding(horizontal = margin, vertical = margin)
            .width(width)
            .height(width / CARD_ASPECT),
    ) {
        for (index in bottom until cards.size) {
            key(index) {
                val card = cards[index]
                val rest = heapRest(cardSeed(card, index))
                val landing = if (index < alreadyDown) {
                    CardLanding(
                        offsetX = rest.driftX * cardWidthPx,
                        offsetY = rest.driftY * cardWidthPx,
                        rotation = rest.tiltDegrees,
                        scale = 1f,
                        elevation = REST_ELEVATION_DP.dp,
                    )
                } else {
                    val (fromX, fromY) = pileOrigin(cardSeed(card, index))
                    rememberCardLanding(
                        key = index,
                        rest = rest,
                        facingDegrees = 0f,
                        fromX = fromX * cardWidthPx,
                        fromY = fromY * cardWidthPx,
                        cardWidthPx = cardWidthPx,
                        durationMillis = throwMillis,
                    )
                }
                PlayingCardView(
                    card = card,
                    width = width,
                    elevation = landing.elevation,
                    modifier = Modifier.landed(landing),
                )
            }
        }
    }
}

/**
 * A stock of face-down cards, drawn with enough of the pack under the top card
 * to show there is something left in it. The offsets are a squared-up deck's
 * worth — a couple of percent — rather than the scatter a played card gets.
 */
@Composable
fun CardBackStack(
    count: Int,
    width: Dp,
    modifier: Modifier = Modifier,
    depth: Int = 3,
) {
    if (count <= 0) return

    val shown = count.coerceAtMost(depth)
    val step = width * 0.018f
    Box(
        modifier = modifier
            .padding(end = step * shown, bottom = step * shown)
            .width(width)
            .height(width / CARD_ASPECT),
    ) {
        // Bottom of the pack first, so the top card is drawn last and sits on top.
        for (below in (shown - 1) downTo 0) {
            CardBackView(
                width = width,
                elevation = if (below == 0) REST_ELEVATION_DP.dp else 0.dp,
                modifier = Modifier
                    .offset(x = step * below, y = step * below)
                    .rotate(unitNoise(below, 8) * 0.8f),
            )
        }
    }
}
