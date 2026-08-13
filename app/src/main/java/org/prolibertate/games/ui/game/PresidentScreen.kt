package org.prolibertate.games.ui.game

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.prolibertate.games.game.president.PassTurn
import org.prolibertate.games.game.president.PlayCards
import org.prolibertate.games.game.president.PresidentMove
import org.prolibertate.games.game.president.PresidentPhase
import org.prolibertate.games.game.president.PresidentState
import org.prolibertate.games.game.president.titleFor
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.PrimaryAction
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.CARD_ASPECT
import org.prolibertate.games.ui.common.CardBackView
import org.prolibertate.games.ui.common.PlayingCardView
import org.prolibertate.games.ui.theme.FeltGreenDark

/**
 * The President table.
 *
 * Plays are offered as whole sets rather than by selecting individual cards.
 * A set is the unit of the game — you never play four cards of different
 * ranks — so picking from the legal sets is both simpler to operate and
 * impossible to get wrong.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresidentScreen(
    controller: MatchController<PresidentState, PresidentMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()

    val current = state
    if (current == null) {
        ScreenScaffold(title = "President", onBack = onExit) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Dealing…") }
        }
        return
    }

    ScreenScaffold(title = "President", onBack = onExit) { modifier ->
        BoxWithConstraints(modifier = modifier) {
            val cardWidth = (minOf(maxWidth, maxHeight) * 0.15f).coerceIn(44.dp, 88.dp)

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StandingsBar(current, localSeat)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(FeltGreenDark),
                    contentAlignment = Alignment.Center,
                ) {
                    PileView(current, cardWidth)
                }

                OpponentRow(current, localSeat)

                when (current.phase) {
                    PresidentPhase.PLAYING -> PlayChoices(current, localSeat, legal) {
                        controller.submit(it)
                    }

                    PresidentPhase.ROUND_OVER -> Text(
                        text = "Round over — " + current.finishedOrder
                            .mapIndexed { position, seat ->
                                "${titleFor(position, current.options.playerCount)}: seat $seat"
                            }
                            .joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    PresidentPhase.GAME_OVER -> {
                        Text("Game over.", fontWeight = FontWeight.Bold)
                        PrimaryAction(text = "Back to the menu") { onExit() }
                    }
                }

                HandView(current, localSeat, cardWidth)
            }
        }
    }
}

@Composable
private fun StandingsBar(state: PresidentState, localSeat: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Round ${state.roundNumber + 1} of ${state.options.roundsToPlay}",
                fontWeight = FontWeight.Bold)
            Text(
                text = "Your score: ${state.scores[localSeat]}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val position = state.finishedOrder.indexOf(localSeat)
            Text(
                text = if (position >= 0) {
                    "You are out — ${titleFor(position, state.options.playerCount)}"
                } else {
                    "${state.hands[localSeat].size} cards in hand"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PileView(state: PresidentState, cardWidth: Dp) {
    if (state.setSize == 0) {
        Text(
            text = "Pile is clear — anything leads",
            color = MaterialTheme.colorScheme.onPrimary,
        )
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "On the pile",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        PileHeap(state, cardWidth)
        Text(
            text = "Beat it with ${state.setSize} card(s)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** How much of what is under the top set is worth drawing. */
private const val BURIED_DEPTH = 5

/**
 * The pile, as a pile.
 *
 * Every card played since the pile was last cleared is still lying there, and a
 * new set is played *onto* them rather than instead of them — which is what the
 * pile is for. Only the top set has to be readable, since that is the one that
 * has to be beaten, so it is laid out square and side by side; everything under
 * it is pushed out towards the edge of the ground the top set covers, so a
 * corner of it shows.
 *
 * The whole pile is drawn in one loop keyed by position, which is what lets a
 * set slide out of the way as the next one lands on it instead of vanishing
 * from the row and reappearing under the heap. Each card animates from wherever
 * it actually is to wherever it now belongs, so being buried needs no
 * bookkeeping about where it used to sit.
 */
@Composable
private fun PileHeap(state: PresidentState, cardWidth: Dp) {
    val density = LocalDensity.current
    val cardWidthPx = with(density) { cardWidth.toPx() }
    val cardHeightPx = cardWidthPx / CARD_ASPECT
    val gapPx = with(density) { 6.dp.toPx() }
    val throwMillis = motionMillis(CARD_THROW_MILLIS)

    // Where the cards of the top set sit: centred, side by side.
    val step = cardWidthPx + gapPx
    val slotOffset = (state.setSize - 1) / 2f

    // Half the ground the top set covers, which is what the cards underneath
    // have to get out from under.
    val halfWidth = (step * state.setSize) / 2f
    val halfHeight = cardHeightPx * 0.34f

    val lowest = (state.pile.size - state.setSize - BURIED_DEPTH).coerceAtLeast(0)

    Box(
        modifier = Modifier.size(
            width = with(density) { (halfWidth * 2f + cardWidthPx * 0.9f).toDp() },
            height = with(density) { (cardHeightPx + halfHeight * 2f).toDp() },
        ),
        contentAlignment = Alignment.Center,
    ) {
        for (place in lowest until state.pile.size) {
            key(place) {
                val card = state.pile[place]
                val seed = cardSeed(card, place)
                val depth = state.pile.size - 1 - place
                val onTop = depth < state.setSize

                val rest = heapRest(seed)
                val (buriedX, buriedY) = buriedOffset(seed, halfWidth, halfHeight)
                val (targetX, targetY) = if (onTop) {
                    // Slot 0 is the leftmost card of the set, which is the
                    // deepest of them — they were dealt onto the pile in order.
                    val slot = state.setSize - 1 - depth
                    (slot - slotOffset) * step to 0f
                } else {
                    buriedX to buriedY
                }

                val slideSpec = tween<Float>(durationMillis = throwMillis)
                val x by animateFloatAsState(targetX, slideSpec, label = "pileX")
                val y by animateFloatAsState(targetY, slideSpec, label = "pileY")
                // Buried cards are knocked further askew than the tidy set on
                // top of them, which is the other half of reading as a heap.
                val tilt by animateFloatAsState(
                    targetValue = if (onTop) rest.tiltDegrees * 0.4f else rest.tiltDegrees,
                    animationSpec = slideSpec,
                    label = "pileTilt",
                )

                // The throw is relative to wherever the card belongs, so the two
                // compose: a card lands in its slot, and is later shoved aside
                // from it without the landing having to know.
                val (fromX, fromY) = pileOrigin(seed)
                val landing = rememberCardLanding(
                    key = place,
                    rest = rest,
                    facingDegrees = 0f,
                    fromX = fromX * cardWidthPx,
                    fromY = fromY * cardWidthPx,
                    cardWidthPx = cardWidthPx,
                    durationMillis = throwMillis,
                    // A set leaves the hand together and lands one after
                    // another rather than as a block.
                    delayMillis = (rest.lateness * throwMillis * 0.3f).toInt(),
                )

                PlayingCardView(
                    card = card,
                    width = cardWidth,
                    elevation = landing.elevation,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (x + landing.offsetX).roundToInt(),
                                (y + landing.offsetY).roundToInt(),
                            )
                        }
                        .graphicsLayer {
                            scaleX = landing.scale
                            scaleY = landing.scale
                            rotationZ = tilt + (landing.rotation - rest.tiltDegrees)
                            alpha = landing.alpha
                        },
                )
            }
        }
    }
}

/** Everyone else, as card counts — their hands are never sent to this device. */
@Composable
private fun OpponentRow(state: PresidentState, localSeat: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        (0 until state.options.playerCount)
            .filter { it != localSeat }
            .forEach { seat ->
                val count = state.handCounts.getOrElse(seat) { 0 }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Seat $seat",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (seat == state.turn) FontWeight.Bold else FontWeight.Normal,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        repeat(count.coerceAtMost(6)) { CardBackView(width = 14.dp) }
                    }
                    Text(
                        text = if (count == 0) "out" else "$count",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayChoices(
    state: PresidentState,
    localSeat: Int,
    legal: List<PresidentMove>,
    onMove: (PresidentMove) -> Unit,
) {
    if (legal.isEmpty()) {
        Text(
            text = if (state.isOut(localSeat)) "You're out — watching." else "Seat ${state.turn} to play…",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val plays = legal.filterIsInstance<PlayCards>()
    val canPass = legal.contains(PassTurn)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (state.setSize == 0) "Lead a set" else "Play or pass",
            fontWeight = FontWeight.Bold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            plays.forEach { play ->
                Button(onClick = { onMove(play) }) {
                    Text(play.cards.joinToString(" ") { it.label })
                }
            }
        }
        if (canPass) {
            OutlinedButton(
                onClick = { onMove(PassTurn) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Pass")
            }
        }
    }
}

@Composable
private fun HandView(state: PresidentState, localSeat: Int, cardWidth: Dp) {
    val hand = state.hands.getOrNull(localSeat).orEmpty()
    Column {
        Text("Your hand", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (hand.isEmpty()) {
                Text("—", style = MaterialTheme.typography.bodySmall)
            }
            hand.forEach { card ->
                PlayingCardView(
                    card = card,
                    width = cardWidth * 0.8f,
                    modifier = Modifier.rotate(handTilt(cardSeed(card))),
                )
            }
        }
    }
}
