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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.president.PassTurn
import org.prolibertate.games.game.president.PlayCards
import org.prolibertate.games.game.president.PresidentMove
import org.prolibertate.games.game.president.PresidentPhase
import org.prolibertate.games.game.president.PresidentState
import org.prolibertate.games.game.president.titleFor
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.PrimaryAction
import org.prolibertate.games.ui.ScreenScaffold
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.pile.takeLast(state.setSize).forEach { card ->
                PlayingCardView(card = card, width = cardWidth)
            }
        }
        Text(
            text = "Beat it with ${state.setSize} card(s)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
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
                PlayingCardView(card = card, width = cardWidth * 0.8f)
            }
        }
    }
}
