package org.prolibertate.games.ui.game

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.kaiser.FIVE_OF_HEARTS
import org.prolibertate.games.game.kaiser.KaiserMove
import org.prolibertate.games.game.kaiser.KaiserPhase
import org.prolibertate.games.game.kaiser.KaiserState
import org.prolibertate.games.game.kaiser.MakeBid
import org.prolibertate.games.game.kaiser.NameTrump
import org.prolibertate.games.game.kaiser.PassBid
import org.prolibertate.games.game.kaiser.PlayCard
import org.prolibertate.games.game.kaiser.THREE_OF_SPADES
import org.prolibertate.games.game.kaiser.teamOf
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.PrimaryAction
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.PlayingCardView
import org.prolibertate.games.ui.theme.FeltGreenDark

/**
 * The Kaiser table.
 *
 * Laid out like the Euchre table — partners opposite, each card on the side of
 * whoever played it — with the two counters called out wherever they appear,
 * since the whole hand is really played for those two cards.
 */
@Composable
fun KaiserScreen(
    controller: MatchController<KaiserState, KaiserMove>,
    localSeat: Int,
    trickHoldMillis: Long,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val notice by controller.notice.collectAsState()

    val current = state
    if (current == null) {
        ScreenScaffold(title = "Kaiser", onBack = onExit) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Dealing…") }
        }
        return
    }

    ScreenScaffold(title = "Kaiser", onBack = onExit) { modifier ->
        BoxWithConstraints(modifier = modifier) {
            val compact = maxWidth < 600.dp
            val cardWidth = (minOf(maxWidth, maxHeight) * if (compact) 0.15f else 0.11f)
                .coerceIn(44.dp, 96.dp)

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

                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                when (current.phase) {
                    KaiserPhase.BIDDING ->
                        BiddingControls(current, localSeat, legal) { controller.submit(it) }

                    KaiserPhase.HAND_OVER -> Text(
                        text = current.log.lastOrNull().orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                    )

                    KaiserPhase.GAME_OVER -> {
                        Text("Game over.", fontWeight = FontWeight.Bold)
                        PrimaryAction(text = "Back to the menu") { onExit() }
                    }

                    else -> Unit
                }

                HandRow(current, localSeat, legal, cardWidth) { controller.submit(it) }
            }
        }
    }
}

@Composable
private fun ScoreBar(state: KaiserState, localSeat: Int) {
    val myTeam = teamOf(localSeat)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Us ${state.scores[myTeam]} — Them ${state.scores[1 - myTeam]}",
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "This hand: ${state.handPoints[myTeam]} to us, " +
                    "${state.handPoints[1 - myTeam]} to them",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val bid = state.highBid
            Text(
                text = when {
                    bid == null -> "Bidding"
                    state.trump == null && state.phase != KaiserPhase.BIDDING -> "No trump"
                    state.trump != null -> "Trump ${state.trump!!.symbol}"
                    else -> "Bid ${bid.points}"
                },
                fontWeight = FontWeight.Bold,
            )
            if (bid != null) {
                val bidder = state.highBidder
                Text(
                    text = "Contract ${bid.points}${if (bid.noTrump) " no trump" else ""}" +
                        (bidder?.let { " by ${seatLabel(relativePosition(it, localSeat))}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Where a seat sits relative to you. Partners sit opposite, so 2 is yours. */
private fun relativePosition(seat: Int, localSeat: Int): Int = ((seat - localSeat) % 4 + 4) % 4

private fun seatLabel(relative: Int): String = when (relative) {
    0 -> "You"
    1 -> "Left"
    2 -> "Partner"
    else -> "Right"
}

@Composable
private fun TrickArea(
    state: KaiserState,
    localSeat: Int,
    cardWidth: Dp,
    trickHoldMillis: Long,
) {
    val sweeping = state.trick.isEmpty() && state.completedTrick.isNotEmpty()
    val cardsOnTable = if (sweeping) state.completedTrick else state.trick

    var sweepStarted by remember(state.completedTrick) { mutableStateOf(false) }
    LaunchedEffect(state.completedTrick) {
        if (state.completedTrick.isNotEmpty()) {
            delay(trickHoldMillis)
            sweepStarted = true
        }
    }
    val sweep by animateFloatAsState(
        targetValue = if (sweeping && sweepStarted) 1f else 0f,
        animationSpec = tween(durationMillis = motionMillis(CARD_SWEEP_MILLIS), easing = LinearEasing),
        label = "kaiserSweep",
    )

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

    // Kaiser scores in points rather than tricks, but the cards are still won
    // and still kept, and how many each player is sitting on is worth seeing.
    val arrived = rememberArrivedTricks(
        tricksWon = state.tricksWon,
        sweeping = sweeping,
        sweepStarted = sweepStarted,
        sweepMillis = motionMillis(CARD_SWEEP_MILLIS),
    )

    Box(modifier = Modifier.fillMaxSize().padding(4.dp)) {

        // Drawn first, so a trick still being gathered passes over the pile it
        // is on its way to joining.
        TakenTricks(arrived, localSeat, cardWidth)

        if (cardsOnTable.isEmpty()) {
            Text(
                text = when {
                    state.phase == KaiserPhase.BIDDING -> "Bidding"
                    state.turn == localSeat -> "Lead a card"
                    else -> "Waiting…"
                },
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        cardsOnTable.forEach { played ->
            key(played.seat) {
                val relative = relativePosition(played.seat, localSeat)
                val alignment = when (relative) {
                    0 -> Alignment.BottomCenter
                    1 -> Alignment.CenterStart
                    2 -> Alignment.TopCenter
                    else -> Alignment.CenterEnd
                }
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
                        caption = counterCaption(played.card),
                        elevation = landing.elevation,
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

        if (sweeping && state.lastTrickWinner != null) {
            Text(
                text = "${seatLabel(winnerRelative)} takes it",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.Center).alpha(1f - sweep),
            )
        }

        if (state.phase == KaiserPhase.PLAYING && state.trick.size < 4) {
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

/** The two cards that decide the hand, named on their faces. */
private fun counterCaption(card: Card): String? = when (card) {
    FIVE_OF_HEARTS -> "+5"
    THREE_OF_SPADES -> "−3"
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BiddingControls(
    state: KaiserState,
    localSeat: Int,
    legal: List<KaiserMove>,
    onMove: (KaiserMove) -> Unit,
) {
    if (legal.isEmpty()) {
        val waitingOn = relativePosition(state.turn, localSeat)
        Text("Waiting for ${seatLabel(waitingOn).lowercase()} to bid…")
        return
    }

    val naming = legal.filterIsInstance<NameTrump>()
    if (naming.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("You won the bidding — name trump", fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                naming.forEach { move ->
                    Button(onClick = { onMove(move) }) {
                        Text(move.suit?.let { "${it.symbol} ${it.name.lowercase()}" } ?: "No trump")
                    }
                }
            }
        }
        return
    }

    val bids = legal.filterIsInstance<MakeBid>()
    val canPass = legal.contains(PassBid)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = state.highBid?.let { high ->
                "Bidding stands at ${high.points}${if (high.noTrump) " no trump" else ""}"
            } ?: "Open the bidding",
            fontWeight = FontWeight.Bold,
        )
        if (!canPass) {
            Text(
                text = "Everyone passed, so as dealer you have to take it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Wraps rather than scrolls, so no bid can be pushed off the edge.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            bids.forEach { move ->
                Button(onClick = { onMove(move) }) {
                    Text("${move.bid.points}${if (move.bid.noTrump) " NT" else ""}")
                }
            }
        }
        Text(
            text = "A no-trump contract pays double, and costs double if it goes down.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (canPass) {
            OutlinedButton(onClick = { onMove(PassBid) }, modifier = Modifier.fillMaxWidth()) {
                Text("Pass")
            }
        }
    }
}

@Composable
private fun HandRow(
    state: KaiserState,
    seat: Int,
    legal: List<KaiserMove>,
    cardWidth: Dp,
    onPlay: (KaiserMove) -> Unit,
) {
    val hand = state.hands.getOrNull(seat).orEmpty()
    val playable = legal.filterIsInstance<PlayCard>().map { it.card }.toSet()

    Column {
        Text(
            text = if (playable.isNotEmpty()) "Your turn" else "Your hand",
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
                PlayingCardView(
                    card = card,
                    width = cardWidth,
                    enabled = canPlay,
                    caption = counterCaption(card),
                    modifier = Modifier.rotate(handTilt(cardSeed(card))),
                    onClick = { if (canPlay) onPlay(PlayCard(card)) },
                )
            }
        }
    }
}
