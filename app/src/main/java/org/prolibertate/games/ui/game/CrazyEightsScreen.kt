package org.prolibertate.games.ui.game

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.crazyeights.CrazyEightsMove
import org.prolibertate.games.game.crazyeights.CrazyEightsPhase
import org.prolibertate.games.game.crazyeights.CrazyEightsState
import org.prolibertate.games.game.crazyeights.DrawCard
import org.prolibertate.games.game.crazyeights.PassTurn
import org.prolibertate.games.game.crazyeights.PlayCard
import org.prolibertate.games.game.crazyeights.crazyEightsPenalty
import org.prolibertate.games.game.crazyeights.isWild
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.CardBackView
import org.prolibertate.games.ui.common.PlayingCardView
import org.prolibertate.games.ui.theme.FeltGreenDark

/**
 * The Crazy 8s table.
 *
 * The one thing this screen has to get right is that the suit being followed is
 * not always the suit on top of the pile — an eight changes it — so the suit in
 * force is stated in words rather than left to be read off the card.
 */
@Composable
fun CrazyEightsScreen(
    controller: MatchController<CrazyEightsState, CrazyEightsMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()

    // Playing an eight is two decisions: the card, then the suit it turns the
    // game to. The card is held here while the suit is chosen.
    var pendingWild by remember { mutableStateOf<Card?>(null) }

    val current = state
    if (current == null) {
        ScreenScaffold(title = "Crazy 8s", onBack = onExit) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Dealing…") }
        }
        return
    }

    ScreenScaffold(title = "Crazy 8s", onBack = onExit) { modifier ->
        BoxWithConstraints(modifier = modifier) {
            val cardWidth = (minOf(maxWidth, maxHeight) * 0.16f).coerceIn(44.dp, 92.dp)

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScoreLine(current, localSeat)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(FeltGreenDark),
                    contentAlignment = Alignment.Center,
                ) {
                    PileArea(current, cardWidth)
                }

                OpponentCounts(current, localSeat)

                if (current.phase == CrazyEightsPhase.PLAYING) {
                    TurnControls(
                        state = current,
                        localSeat = localSeat,
                        legal = legal,
                        pendingWild = pendingWild,
                        onCancelWild = { pendingWild = null },
                        onMove = { pendingWild = null; controller.submit(it) },
                    )
                }

                HandRow(
                    state = current,
                    localSeat = localSeat,
                    legal = legal,
                    cardWidth = cardWidth,
                    pendingWild = pendingWild,
                    onTap = { card ->
                        if (isWild(card)) {
                            pendingWild = if (pendingWild == card) null else card
                        } else {
                            pendingWild = null
                            controller.submit(PlayCard(card))
                        }
                    },
                )
            }

            if (current.phase != CrazyEightsPhase.PLAYING) {
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
private fun ScoreLine(state: CrazyEightsState, localSeat: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "Round ${state.roundNumber + 1} of ${state.options.roundsToPlay}",
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Your penalty so far: ${state.scores[localSeat]}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Play ${state.suitInForce.symbol}", fontWeight = FontWeight.Bold)
            Text(
                text = "${state.stock.size} left in the stock",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PileArea(state: CrazyEightsState, cardWidth: Dp) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Stock",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                if (state.stock.isEmpty()) {
                    Text(
                        text = "empty",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    CardBackView(width = cardWidth)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Pile",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                state.topCard?.let { PlayingCardView(card = it, width = cardWidth) }
            }
        }
        // An eight on top says nothing about what may be played next, so the
        // suit in force is spelled out under the pile.
        Text(
            text = "Following ${state.suitInForce.symbol} ${state.suitInForce.name.lowercase()}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun OpponentCounts(state: CrazyEightsState, localSeat: Int) {
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
                        repeat(count.coerceAtMost(8)) { CardBackView(width = 12.dp) }
                    }
                    Text("$count", style = MaterialTheme.typography.labelSmall)
                }
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TurnControls(
    state: CrazyEightsState,
    localSeat: Int,
    legal: List<CrazyEightsMove>,
    pendingWild: Card?,
    onCancelWild: () -> Unit,
    onMove: (CrazyEightsMove) -> Unit,
) {
    if (legal.isEmpty()) {
        Text("Seat ${state.turn} to play…", style = MaterialTheme.typography.bodySmall)
        return
    }

    if (pendingWild != null) {
        val suits = legal.filterIsInstance<PlayCard>()
            .filter { it.card == pendingWild }
            .mapNotNull { move -> move.nominatedSuit?.let { it to move } }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Playing ${pendingWild.label} — name the suit", fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                suits.forEach { (suit, move) ->
                    Button(onClick = { onMove(move) }) {
                        Text("${suit.symbol} ${suit.name.lowercase()}")
                    }
                }
            }
            OutlinedButton(onClick = onCancelWild, modifier = Modifier.fillMaxWidth()) {
                Text("Pick a different card")
            }
        }
        return
    }

    val canDraw = legal.contains(DrawCard)
    val canPass = legal.contains(PassTurn)
    val playable = legal.filterIsInstance<PlayCard>().isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = when {
                playable -> "Your turn — tap a card"
                canDraw -> "Nothing to play"
                else -> "Nothing to play and nothing to draw"
            },
            fontWeight = FontWeight.Bold,
        )
        if (canDraw) {
            Button(onClick = { onMove(DrawCard) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (state.options.drawUntilPlayable) {
                        "Draw until something fits"
                    } else {
                        "Draw a card"
                    }
                )
            }
        }
        if (canPass) {
            OutlinedButton(onClick = { onMove(PassTurn) }, modifier = Modifier.fillMaxWidth()) {
                Text("Pass")
            }
        }
    }
}

@Composable
private fun HandRow(
    state: CrazyEightsState,
    localSeat: Int,
    legal: List<CrazyEightsMove>,
    cardWidth: Dp,
    pendingWild: Card?,
    onTap: (Card) -> Unit,
) {
    val hand = state.hands.getOrNull(localSeat).orEmpty()
    val playable = legal.filterIsInstance<PlayCard>().map { it.card }.toSet()

    Column {
        Text(
            text = "Your hand — ${hand.sumOf { crazyEightsPenalty(it) }} in penalties",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (hand.isEmpty()) Text("—", style = MaterialTheme.typography.bodySmall)
            hand.forEach { card ->
                val canPlay = card in playable
                PlayingCardView(
                    card = card,
                    width = cardWidth,
                    enabled = canPlay,
                    selected = card == pendingWild,
                    caption = if (isWild(card)) "WILD" else null,
                    onClick = { onTap(card) },
                )
            }
        }
    }
}

/** The card at the end of a round: what everyone was caught holding. */
@Composable
private fun RoundScoreDialog(
    state: CrazyEightsState,
    localSeat: Int,
    onContinue: () -> Unit,
    onExit: () -> Unit,
) {
    var dismissed by remember(state.roundNumber, state.phase) { mutableStateOf(false) }
    if (dismissed) return

    val finalRound = state.phase == CrazyEightsPhase.GAME_OVER
    val standings = (0 until state.options.playerCount).sortedBy { state.scores[it] }

    AlertDialog(
        onDismissRequest = { },
        title = {
            Text(
                if (finalRound) {
                    "Final score"
                } else {
                    "Round ${state.roundNumber + 1} of ${state.options.roundsToPlay}"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = state.roundWinner?.let {
                        if (it == localSeat) "You went out." else "Seat $it went out."
                    } ?: "The round was blocked — nobody went out.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                standings.forEach { seat ->
                    val you = seat == localSeat
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (you) "You" else "Seat $seat",
                            modifier = Modifier.weight(1f),
                            fontWeight = if (you) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text("${state.scores[seat]}")
                    }
                }
                Text("Lowest penalty wins.", style = MaterialTheme.typography.bodySmall)
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
