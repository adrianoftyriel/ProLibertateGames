package org.prolibertate.games.ui.game

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import org.prolibertate.games.game.euchre.CallTrump
import org.prolibertate.games.game.euchre.Discard
import org.prolibertate.games.game.euchre.EuchreMove
import org.prolibertate.games.game.euchre.EuchrePhase
import org.prolibertate.games.game.euchre.EuchreState
import org.prolibertate.games.game.euchre.OrderUp
import org.prolibertate.games.game.euchre.Pass
import org.prolibertate.games.game.euchre.PlayCard
import org.prolibertate.games.game.euchre.teamOf
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.PrimaryAction
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.PlayingCardView
import org.prolibertate.games.ui.theme.FeltGreenDark

/**
 * The Euchre table.
 *
 * Layout is driven by the space available rather than by orientation checks:
 * card width is a fraction of the narrower dimension, so the same code lays out
 * sensibly on a phone held either way and on a tablet.
 */
@Composable
fun EuchreScreen(
    controller: MatchController<EuchreState, EuchreMove>,
    localSeat: Int,
    trickHoldMillis: Long,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val notice by controller.notice.collectAsState()

    val current = state
    if (current == null) {
        ScreenScaffold(title = "Euchre", onBack = onExit) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Dealing…") }
        }
        return
    }

    ScreenScaffold(title = "Euchre", onBack = onExit) { modifier ->
        BoxWithConstraints(modifier = modifier) {
            val compact = maxWidth < 600.dp
            val cardWidth = (minOf(maxWidth, maxHeight) * if (compact) 0.17f else 0.12f)
                .coerceIn(48.dp, 104.dp)

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScoreBar(current, localSeat)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(FeltGreenDark),
                    contentAlignment = Alignment.Center,
                ) {
                    TrickArea(current, localSeat, cardWidth, trickHoldMillis)
                }

                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                when (current.phase) {
                    EuchrePhase.BID_ROUND_1,
                    EuchrePhase.BID_ROUND_2,
                    -> BiddingControls(current, localSeat, legal) { controller.submit(it) }

                    EuchrePhase.HAND_OVER -> Text(
                        text = current.log.lastOrNull().orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                    )

                    EuchrePhase.GAME_OVER -> {
                        Text("Game over.", fontWeight = FontWeight.Bold)
                        PrimaryAction(text = "Back to the menu") { onExit() }
                    }

                    else -> Unit
                }

                HandRow(
                    state = current,
                    seat = localSeat,
                    legal = legal,
                    cardWidth = cardWidth,
                    onPlay = { controller.submit(it) },
                )
            }
        }
    }
}

@Composable
private fun ScoreBar(state: EuchreState, localSeat: Int) {
    val myTeam = teamOf(localSeat)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Us ${state.scores[myTeam]} — Them ${state.scores[1 - myTeam]}",
                fontWeight = FontWeight.Bold)
            val tricks = state.tricksWon.filterIndexed { seat, _ -> teamOf(seat) == myTeam }.sum()
            Text("Tricks this hand: $tricks", style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = state.trump?.let { "Trump ${it.symbol}" } ?: "Bidding",
                fontWeight = FontWeight.Bold,
            )
            state.aloneSeat?.let {
                Text("Seat $it is alone", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Where a seat sits relative to you: 0 is you, then clockwise round the table.
 * Euchre seats are numbered clockwise and partners sit opposite, so 2 is always
 * your partner.
 */
private fun relativePosition(seat: Int, localSeat: Int): Int = ((seat - localSeat) % 4 + 4) % 4

private fun seatLabel(relative: Int): String = when (relative) {
    0 -> "You"
    1 -> "Left"
    2 -> "Partner"
    else -> "Right"
}

/**
 * The table. Each card sits on the side of the player who played it and is
 * turned to face them, so you can read a trick at a glance instead of matching
 * seat numbers to cards.
 */
/** How long a completed trick rests on the table before being swept away. */
const val TRICK_HOLD_MILLIS = 1_000L

@Composable
private fun TrickArea(
    state: EuchreState,
    localSeat: Int,
    cardWidth: Dp,
    trickHoldMillis: Long,
) {
    // A finished trick stays put for a beat so it can be read, then slides off
    // towards whoever won it.
    val sweeping = state.trick.isEmpty() && state.completedTrick.isNotEmpty()
    val cardsOnTable = if (sweeping) state.completedTrick else state.trick

    var sweepStarted by remember(state.completedTrick) { mutableStateOf(false) }
    LaunchedEffect(state.completedTrick) {
        if (state.completedTrick.isNotEmpty()) {
            delay(trickHoldMillis)
            sweepStarted = true
        }
    }
    // Driven linearly, because the shape of the sweep belongs to each card
    // rather than to the clock: sweepProgress gives them their own start and
    // their own acceleration off this one value.
    val sweep by animateFloatAsState(
        targetValue = if (sweeping && sweepStarted) 1f else 0f,
        animationSpec = tween(durationMillis = motionMillis(CARD_SWEEP_MILLIS), easing = LinearEasing),
        label = "trickSweep",
    )

    // Everything travels towards the winner's edge of the table together.
    val winnerRelative = state.lastTrickWinner?.let { relativePosition(it, localSeat) } ?: 0
    val (dirX, dirY) = when (winnerRelative) {
        0 -> 0f to 1f
        1 -> -1f to 0f
        2 -> 0f to -1f
        else -> 1f to 0f
    }
    val cardWidthPx = with(LocalDensity.current) { cardWidth.toPx() }
    val travelPx = cardWidthPx * 2.5f
    val throwMillis = motionMillis(CARD_THROW_MILLIS)

    // The count follows the cards rather than the rules, so a pile grows as the
    // trick reaches it and not the moment the last card was played.
    val arrived = rememberArrivedTricks(
        tricksWon = state.tricksWon,
        sweeping = sweeping,
        sweepStarted = sweepStarted,
        sweepMillis = motionMillis(CARD_SWEEP_MILLIS),
    )

    Box(modifier = Modifier.fillMaxSize().padding(4.dp)) {

        // What each player has taken, face down in front of them. Drawn first
        // so a trick still being gathered passes over the pile it is joining.
        TakenTricks(arrived, localSeat, cardWidth)

        // The turn card sits in the middle of the table during bidding.
        state.upCard?.let { up ->
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Turned up",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                PlayingCardView(card = up, width = cardWidth)
            }
        }

        if (state.upCard == null && cardsOnTable.isEmpty() &&
            state.phase == EuchrePhase.PLAYING
        ) {
            Text(
                text = if (state.turn == localSeat) "Lead a card" else "Waiting…",
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Keyed by seat: one card per seat per trick, so this holds a card's
        // flight steady while the trick fills up and while the finished trick
        // is swapped for the copy that gets swept away. Without it the list
        // position would decide identity and every card would jump back into
        // the air whenever another one landed.
        cardsOnTable.forEach { played ->
            key(played.seat) {
                val relative = relativePosition(played.seat, localSeat)
                val alignment = when (relative) {
                    0 -> Alignment.BottomCenter
                    1 -> Alignment.CenterStart
                    2 -> Alignment.TopCenter
                    else -> Alignment.CenterEnd
                }
                // Turned to face the player who played it: the side seats end
                // up landscape, the partner's card upside down from your side.
                val facing = when (relative) {
                    1 -> 90f
                    2 -> 180f
                    3 -> -90f
                    else -> 0f
                }

                val rest = cardRest(cardSeed(played.card, played.seat))
                val (fromX, fromY) = seatOrigin(relative)
                val landing = rememberCardLanding(
                    key = played.card,
                    rest = rest,
                    facingDegrees = facing,
                    fromX = fromX * cardWidthPx,
                    fromY = fromY * cardWidthPx,
                    cardWidthPx = cardWidthPx,
                    durationMillis = throwMillis,
                )
                val leaving = sweepProgress(sweep, rest)

                Column(
                    modifier = Modifier
                        .align(alignment)
                        .offset {
                            IntOffset(
                                x = (landing.offsetX + dirX * travelPx * leaving).roundToInt(),
                                y = (landing.offsetY + dirY * travelPx * leaving).roundToInt(),
                            )
                        }
                        .graphicsLayer {
                            // Squared down into the bundle it is joining, and
                            // still whole until it gets there.
                            val gathered = landing.scale * gatherScale(leaving)
                            scaleX = gathered
                            scaleY = gathered
                            alpha = landing.alpha * gatherAlpha(leaving)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PlayingCardView(
                        card = played.card,
                        width = cardWidth,
                        elevation = landing.elevation,
                        // The turn it picks up on the way out is the same turn
                        // it carried on the way in, so a card leaves the table
                        // the way it arrived.
                        modifier = Modifier
                            .rotate(landing.rotation + rest.spinDegrees * leaving),
                    )
                    Text(
                        text = seatLabel(relative),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        // Name the winner while their cards are still on the table.
        if (sweeping && state.lastTrickWinner != null) {
            Text(
                text = "${seatLabel(winnerRelative)} takes it",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.Center).alpha(1f - sweep),
            )
        }

        // Who is on the clock, shown where they sit rather than as a seat number.
        if (state.phase == EuchrePhase.PLAYING && state.trick.size < state.activeSeatCount) {
            val waitingOn = relativePosition(state.turn, localSeat)
            if (waitingOn != 0 && state.trick.none { it.seat == state.turn }) {
                Text(
                    text = "${seatLabel(waitingOn)} to play",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BiddingControls(
    state: EuchreState,
    localSeat: Int,
    legal: List<EuchreMove>,
    onMove: (EuchreMove) -> Unit,
) {
    if (legal.isEmpty()) {
        val waitingOn = relativePosition(state.turn, localSeat)
        Text("Waiting for ${seatLabel(waitingOn).lowercase()} to bid…")
        return
    }

    // Controls only render on your turn, so the seat on the clock is you —
    // which means being the dealer is what decides whether ordering up hands
    // the turn card to somebody else or to yourself.
    val youAreDealer = state.turn == state.dealer
    val upCard = state.upCard
    val canPass = Pass in legal
    val choices = legal.filter { it != Pass }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = when {
                state.phase != EuchrePhase.BID_ROUND_1 -> "Name a suit for trump"
                youAreDealer -> "Pick up ${upCard?.label.orEmpty()} for trump, or pass?"
                else -> "Order up ${upCard?.label.orEmpty()} for trump, or pass?"
            },
            fontWeight = FontWeight.Bold,
        )
        if (state.phase == EuchrePhase.BID_ROUND_1 && upCard != null) {
            Text(
                text = if (youAreDealer) {
                    "Taking it makes ${upCard.suit.symbol} trump and puts that card " +
                        "in your hand — you'll then discard one."
                } else {
                    "Ordering it up makes ${upCard.suit.symbol} trump and gives that " +
                        "card to the dealer."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Stick the dealer is the one case where there is genuinely no way out,
        // so say so rather than leaving it looking like a missing button.
        if (!canPass) {
            Text(
                text = "Everyone has passed, so as dealer you must name a suit — " +
                    "that's stick the dealer. Turn it off in game setup if you'd " +
                    "rather the hand be thrown in.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Wraps rather than scrolls. A scrolling row pushed Pass off the right
        // edge of a phone screen, which made bidding look compulsory.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            choices.forEach { move ->
                val label = when (move) {
                    is OrderUp -> when {
                        youAreDealer && move.alone -> "Pick it up, alone"
                        youAreDealer -> "Pick it up"
                        move.alone -> "Order up alone"
                        else -> "Order up ${upCard?.suit?.symbol.orEmpty()}"
                    }

                    is CallTrump ->
                        if (move.alone) "${move.suit.symbol} alone" else "Make it ${move.suit.symbol}"

                    is Discard -> "Discard ${move.card.label}"
                    is PlayCard -> move.card.label
                    is Pass -> "Pass"
                }
                Button(onClick = { onMove(move) }) { Text(label) }
            }
        }

        // Kept out of the wrapping group and full width so it can never be
        // clipped, scrolled away or mistaken for one of the bids.
        if (canPass) {
            OutlinedButton(
                onClick = { onMove(Pass) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Pass")
            }
        }
    }
}

@Composable
private fun HandRow(
    state: EuchreState,
    seat: Int,
    legal: List<EuchreMove>,
    cardWidth: Dp,
    onPlay: (EuchreMove) -> Unit,
) {
    val hand = state.hands.getOrNull(seat).orEmpty()
    val playable = legal.filterIsInstance<PlayCard>().map { it.card }.toSet()
    val discardable = legal.filterIsInstance<Discard>().map { it.card }.toSet()

    Column {
        Text(
            text = when {
                discardable.isNotEmpty() -> "Discard a card"
                playable.isNotEmpty() -> "Your turn"
                else -> "Your hand"
            },
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (hand.isEmpty()) {
                Box(modifier = Modifier.size(cardWidth)) {
                    Text("—", style = MaterialTheme.typography.bodySmall)
                }
            }
            hand.forEach { card ->
                val canPlay = card in playable
                val canDiscard = card in discardable
                PlayingCardView(
                    card = card,
                    width = cardWidth,
                    enabled = canPlay || canDiscard,
                    // A hand that has been squared up is still not square. Two
                    // degrees is below the threshold at which anyone notices a
                    // tilt and above the one at which a row of cards stops
                    // looking printed.
                    modifier = Modifier.rotate(handTilt(cardSeed(card))),
                    onClick = {
                        when {
                            canDiscard -> onPlay(Discard(card))
                            canPlay -> onPlay(PlayCard(card))
                        }
                    },
                )
            }
        }
    }
}
