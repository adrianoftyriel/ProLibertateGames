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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.wizard.ChooseTrump
import org.prolibertate.games.game.wizard.MakeBid
import org.prolibertate.games.game.wizard.PlayCard
import org.prolibertate.games.game.wizard.WizardMove
import org.prolibertate.games.game.wizard.WizardPhase
import org.prolibertate.games.game.wizard.WizardState
import org.prolibertate.games.game.wizard.isJester
import org.prolibertate.games.game.wizard.isWizardCard
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.PlayingCardView
import org.prolibertate.games.ui.theme.FeltGreenDark

/**
 * The Wizard table.
 *
 * Three to six can play, so seats are placed round a circle rather than at four
 * fixed edges: you are always at the bottom and everyone else is spaced out from
 * there, which keeps the same layout code working at every table size.
 */
@Composable
fun WizardScreen(
    controller: MatchController<WizardState, WizardMove>,
    localSeat: Int,
    trickHoldMillis: Long,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val notice by controller.notice.collectAsState()

    val current = state
    if (current == null) {
        ScreenScaffold(title = "Wizard", onBack = onExit) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Dealing…") }
        }
        return
    }

    ScreenScaffold(title = "Wizard", onBack = onExit) { modifier ->
        BoxWithConstraints(modifier = modifier) {
            val cardWidth = (minOf(maxWidth, maxHeight) * 0.14f).coerceIn(40.dp, 88.dp)

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoundBar(current, localSeat)
                BidTally(current, localSeat)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(FeltGreenDark),
                ) {
                    TrickArea(current, localSeat, cardWidth, trickHoldMillis)
                }

                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                if (current.phase == WizardPhase.BIDDING) {
                    BiddingControls(current, localSeat, legal) { controller.submit(it) }
                }

                HandRow(current, localSeat, legal, cardWidth) { controller.submit(it) }
            }

            if (current.phase == WizardPhase.ROUND_OVER || current.phase == WizardPhase.GAME_OVER) {
                RoundScoreDialog(
                    state = current,
                    localSeat = localSeat,
                    onContinue = { controller.confirmAdvance() },
                    onExit = onExit,
                )
            }
        }
    }
}

@Composable
private fun RoundBar(state: WizardState, localSeat: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Round ${state.round + 1} of ${state.options.totalRounds()}" +
                    " — ${state.cardsThisRound} card(s)",
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Your score: ${state.scores[localSeat]}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = state.trump?.let { "Trump ${it.symbol}" } ?: "No trump",
                fontWeight = FontWeight.Bold,
            )
            state.trumpCard?.let {
                Text("Turned ${it.label}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Everyone's bid against what they have actually taken — the whole game in a row. */
@Composable
private fun BidTally(state: WizardState, localSeat: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        (0 until state.options.playerCount).forEach { seat ->
            val bid = state.bids[seat]
            val won = state.tricksWon[seat]
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (seat == localSeat) "You" else seatLabel(seat, localSeat),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (seat == state.turn) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    text = bid?.let { "$won / $it" } ?: "—",
                    style = MaterialTheme.typography.labelLarge,
                    // Green while the bid is still on, red once it cannot be made.
                    color = when {
                        bid == null -> MaterialTheme.colorScheme.onSurface
                        won == bid -> MaterialTheme.colorScheme.primary
                        won > bid -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/** Seats are named by how far round the table they sit from you. */
private fun seatLabel(seat: Int, localSeat: Int): String =
    if (seat == localSeat) "You" else "Seat $seat"

/** Duration of the sweep towards the winner. */
private const val SWEEP_MILLIS = 450

@Composable
private fun TrickArea(
    state: WizardState,
    localSeat: Int,
    cardWidth: Dp,
    trickHoldMillis: Long,
) {
    val players = state.options.playerCount
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
        animationSpec = tween(durationMillis = SWEEP_MILLIS),
        label = "wizardSweep",
    )

    val winnerRelative = state.lastTrickWinner
        ?.let { ((it - localSeat) % players + players) % players }
        ?: 0
    val winnerAngle = 2.0 * PI * winnerRelative / players

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(4.dp)) {
        // Cards sit on a circle that fits inside whatever space the table has.
        val radius = (minOf(maxWidth, maxHeight) / 2 - cardWidth * 0.7f).coerceAtLeast(0.dp)
        val radiusPx = with(LocalDensity.current) { radius.toPx() }
        val travelPx = with(LocalDensity.current) { (cardWidth * 2f).toPx() }

        if (cardsOnTable.isEmpty()) {
            Text(
                text = when {
                    state.phase == WizardPhase.BIDDING -> "Bidding"
                    state.turn == localSeat -> "Lead a card"
                    else -> "Waiting…"
                },
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        cardsOnTable.forEach { played ->
            val relative = ((played.seat - localSeat) % players + players) % players
            val angle = 2.0 * PI * relative / players
            // Relative seat 0 is you, at the bottom; the rest run clockwise.
            val x = -sin(angle) * radiusPx
            val y = cos(angle) * radiusPx
            // Turn each card to face whoever played it.
            val rotation = (relative * 360f / players)

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset {
                        IntOffset(
                            x = (x + -sin(winnerAngle) * travelPx * sweep).roundToInt(),
                            y = (y + cos(winnerAngle) * travelPx * sweep).roundToInt(),
                        )
                    }
                    .alpha(1f - sweep),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PlayingCardView(
                    card = played.card,
                    width = cardWidth,
                    caption = specialCaption(played.card),
                    modifier = Modifier.rotate(rotation),
                )
                Text(
                    text = seatLabel(played.seat, localSeat),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        if (sweeping && state.lastTrickWinner != null) {
            Text(
                text = "${seatLabel(state.lastTrickWinner!!, localSeat)} takes it",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.Center).alpha(1f - sweep),
            )
        }
    }
}

/**
 * Wizards and jesters carry no rank or suit anyone can read off the face, so
 * they say what they do instead.
 */
private fun specialCaption(card: Card): String? = when {
    isWizardCard(card) -> "WIZARD"
    isJester(card) -> "JESTER"
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BiddingControls(
    state: WizardState,
    localSeat: Int,
    legal: List<WizardMove>,
    onMove: (WizardMove) -> Unit,
) {
    if (legal.isEmpty()) {
        Text(
            text = "Waiting for ${seatLabel(state.turn, localSeat).lowercase()} to bid…",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val trumpChoices = legal.filterIsInstance<ChooseTrump>()
    if (trumpChoices.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("A wizard was turned — you name trump", fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                trumpChoices.forEach { move ->
                    Button(onClick = { onMove(move) }) {
                        Text("${move.suit.symbol} ${move.suit.name.lowercase()}")
                    }
                }
            }
        }
        return
    }

    val bids = legal.filterIsInstance<MakeBid>()
    val bidSoFar = state.bids.filterNotNull().sum()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "How many of the ${state.cardsThisRound} tricks will you take?",
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Bid so far: $bidSoFar. Hit your bid exactly for 20 plus 10 a trick; " +
                "miss it and it costs 10 for every trick either way.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.options.screwTheDealer && state.turn == state.dealer) {
            Text(
                text = "Screw the dealer: you may not make the bids add up.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            bids.forEach { move ->
                Button(onClick = { onMove(move) }) { Text("${move.tricks}") }
            }
        }
    }
}

@Composable
private fun HandRow(
    state: WizardState,
    seat: Int,
    legal: List<WizardMove>,
    cardWidth: Dp,
    onPlay: (WizardMove) -> Unit,
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
                    caption = specialCaption(card),
                    onClick = { if (canPlay) onPlay(PlayCard(card)) },
                )
            }
        }
    }
}

/** Bids against tricks taken, and what that was worth, before the next deal. */
@Composable
private fun RoundScoreDialog(
    state: WizardState,
    localSeat: Int,
    onContinue: () -> Unit,
    onExit: () -> Unit,
) {
    var dismissed by remember(state.round, state.phase) { mutableStateOf(false) }
    if (dismissed) return

    val finalRound = state.phase == WizardPhase.GAME_OVER
    val standings = (0 until state.options.playerCount).sortedByDescending { state.scores[it] }

    AlertDialog(
        onDismissRequest = { },
        title = {
            Text(
                if (finalRound) {
                    "Final score"
                } else {
                    "Round ${state.round + 1} of ${state.options.totalRounds()}"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Player", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text("Bid", fontWeight = FontWeight.Bold)
                    Text("  Took", fontWeight = FontWeight.Bold)
                    Text("  Round", fontWeight = FontWeight.Bold)
                    Text("  Total", fontWeight = FontWeight.Bold)
                }
                standings.forEach { seat ->
                    val you = seat == localSeat
                    val weight = if (you) FontWeight.Bold else FontWeight.Normal
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (you) "You" else "Seat $seat",
                            modifier = Modifier.weight(1f),
                            fontWeight = weight,
                        )
                        Text("${state.bids[seat] ?: 0}")
                        Text("  ${state.tricksWon[seat]}")
                        Text("  ${state.roundScores[seat]}")
                        Text("  ${state.scores[seat]}", fontWeight = weight)
                    }
                }
                Text(
                    text = if (finalRound) {
                        "Highest score wins — seat ${standings.first()} takes it."
                    } else {
                        "Highest score wins."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismissed = true
                if (finalRound) onExit() else onContinue()
            }) {
                Text(if (finalRound) "Back to the menu" else "Deal the next round")
            }
        },
    )
}
