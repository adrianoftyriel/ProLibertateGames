package org.prolibertate.games.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.hearts.HeartsMove
import org.prolibertate.games.game.hearts.HeartsPhase
import org.prolibertate.games.game.hearts.HeartsState
import org.prolibertate.games.game.hearts.PASS_SIZE
import org.prolibertate.games.game.hearts.PassCards
import org.prolibertate.games.game.hearts.PlayCard
import org.prolibertate.games.game.hearts.pointsOf
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.PlayingCardView

private const val TITLE = "Hearts"

/**
 * The Hearts table.
 *
 * Two quite different things happen at this screen, so it reads the phase and
 * shows one or the other: choosing three cards to give away, which is a
 * multiple selection nothing else in the app does, and then playing one card at
 * a time, which every trick-taking screen here does the same way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeartsScreen(
    controller: MatchController<HeartsState, HeartsMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val abandoned by controller.abandoned.collectAsState()
    val awaiting by controller.awaitingConfirmation.collectAsState()

    val chosen = remember { mutableStateListOf<Card>() }
    var confirmingEnd by remember { mutableStateOf(false) }

    val endGameAction: @Composable () -> Unit = {
        TextButton(onClick = { confirmingEnd = true }) { Text("End game") }
    }

    val current = state
    if (current == null) {
        ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Dealing…") }
            abandoned?.let { LeftHeartsDialog(it, onExit, controller::dismissAbandoned) }
            if (confirmingEnd) EndHeartsDialog(onExit) { confirmingEnd = false }
        }
        return
    }

    val hand = current.hands.getOrElse(localSeat) { emptyList() }
    val passing = current.phase == HeartsPhase.PASSING
    val alreadyPassed = current.passSelections.getOrElse(localSeat) { emptyList() }.isNotEmpty()
    val playable = legal.filterIsInstance<PlayCard>().map { it.card }.toSet()

    ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when {
                    current.phase == HeartsPhase.GAME_OVER -> "Game over — lowest score wins."
                    current.phase == HeartsPhase.ROUND_OVER -> "Round scored."
                    passing && alreadyPassed -> "Waiting for the others to choose…"
                    passing -> "Choose three to pass ${current.passDirection.label}"
                    playable.isEmpty() -> "Waiting…"
                    else -> "Your turn"
                },
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Every heart costs a point and the queen of spades costs thirteen. " +
                    "Take none of them.",
                style = MaterialTheme.typography.bodySmall,
            )

            TrickView(current)
            ScoreRow(current, localSeat)
            Divider()

            Text(
                text = if (passing && !alreadyPassed) {
                    "Passing ${current.passDirection.label} — ${chosen.size} of $PASS_SIZE chosen"
                } else {
                    "Your hand"
                },
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                hand.forEach { card ->
                    val selectable = passing && !alreadyPassed
                    PlayingCardView(
                        card = card,
                        width = 52.dp,
                        selected = chosen.contains(card),
                        enabled = if (selectable) true else playable.contains(card),
                        caption = pointsOf(card).takeIf { it > 0 }?.let { "−$it" },
                        modifier = Modifier.rotate(handTilt(cardSeed(card))),
                        onClick = {
                            if (selectable) {
                                toggle(chosen, card)
                            } else if (playable.contains(card)) {
                                controller.submit(PlayCard(card))
                            }
                        },
                    )
                }
            }

            if (passing && !alreadyPassed) {
                Button(
                    enabled = chosen.size == PASS_SIZE,
                    onClick = {
                        controller.submit(PassCards(chosen.toList()))
                        chosen.clear()
                    },
                ) { Text("Pass these three") }
            }

            if (awaiting) {
                Button(onClick = controller::confirmAdvance) { Text("Next round") }
            }

            current.log.takeLast(3).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        abandoned?.let { LeftHeartsDialog(it, onExit, controller::dismissAbandoned) }
        if (confirmingEnd) EndHeartsDialog(onExit) { confirmingEnd = false }
    }
}

/** Adds or removes [card], never letting the selection run past three. */
private fun toggle(chosen: SnapshotStateList<Card>, card: Card) {
    if (chosen.contains(card)) {
        chosen.remove(card)
    } else if (chosen.size < PASS_SIZE) {
        chosen.add(card)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrickView(state: HeartsState) {
    // The trick just swept is held on the table for a moment, so show that when
    // there is nothing in front of the players yet.
    val showing = state.trick.ifEmpty { state.completedTrick }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (state.trick.isEmpty() && showing.isNotEmpty()) {
                "Last trick — taken by seat ${state.leader}"
            } else {
                "Trick ${state.trickNumber + 1} of ${state.tricksPerRound}"
            },
            style = MaterialTheme.typography.labelLarge,
        )
        if (showing.isEmpty()) {
            Text("Nothing played yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            // Hearts lays its trick out in a row rather than round a table, so
            // there is no seat direction to throw a card in from — each one
            // comes in from its own side, which the seed decides and keeps.
            val width = 46.dp
            val widthPx = with(LocalDensity.current) { width.toPx() }
            val throwMillis = motionMillis(CARD_THROW_MILLIS)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                showing.forEach { played ->
                    key(played.seat) {
                        val seed = cardSeed(played.card, played.seat)
                        val rest = cardRest(seed)
                        val (fromX, fromY) = pileOrigin(seed)
                        val landing = rememberCardLanding(
                            key = played.card,
                            rest = rest,
                            facingDegrees = 0f,
                            fromX = fromX * widthPx,
                            fromY = fromY * widthPx,
                            cardWidthPx = widthPx,
                            durationMillis = throwMillis,
                        )
                        PlayingCardView(
                            card = played.card,
                            width = width,
                            caption = "seat ${played.seat}",
                            elevation = landing.elevation,
                            modifier = Modifier.landed(landing),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreRow(state: HeartsState, localSeat: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        state.scores.forEachIndexed { seat, score ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (seat == localSeat) "You" else "Seat $seat",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (seat == localSeat) FontWeight.Bold else FontWeight.Normal,
                )
                // The tricks this player has taken, face down in front of them.
                // Hearts keeps the cards themselves rather than a count, since
                // it is the cards that are scored at the end of the round, so
                // the number of tricks is however many players-worth of them
                // there are.
                WonTrickStack(
                    tricks = state.taken.getOrElse(seat) { emptyList() }.size /
                        state.options.playerCount,
                    width = 26.dp,
                )
                Text("$score", fontWeight = FontWeight.Bold)
                // What this round has cost so far, which is the number anybody
                // actually watches while a hand is being played.
                Text(
                    text = "+${state.roundScores.getOrElse(seat) { 0 }}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun EndHeartsDialog(onEnd: () -> Unit, onKeepPlaying: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepPlaying,
        title = { Text("End this game?") },
        text = { Text("The game ends here. Anyone else at the table is told you have left.") },
        confirmButton = { TextButton(onClick = onEnd) { Text("End the game") } },
        dismissButton = { TextButton(onClick = onKeepPlaying) { Text("Keep playing") } },
    )
}

@Composable
private fun LeftHeartsDialog(notice: String, onExit: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("The game has ended") },
        text = { Text(notice) },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = onStay) { Text("Look at the table") } },
    )
}
