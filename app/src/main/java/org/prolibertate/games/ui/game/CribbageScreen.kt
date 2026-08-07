package org.prolibertate.games.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cribbage.CribbageMove
import org.prolibertate.games.game.cribbage.CribbagePhase
import org.prolibertate.games.game.cribbage.CribbageState
import org.prolibertate.games.game.cribbage.LayAway
import org.prolibertate.games.game.cribbage.PegCard
import org.prolibertate.games.game.cribbage.ShowScore
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.CardBackView
import org.prolibertate.games.ui.common.PlayingCardView
import org.prolibertate.games.ui.theme.FeltGreenDark
import org.prolibertate.games.ui.theme.TeamColours

/**
 * The cribbage table.
 *
 * Two things have to be legible at a glance, because the whole game turns on
 * them: the count, which decides what can be played at all, and where the pegs
 * are. The board is drawn as a track per side with both pegs on it — the pale
 * one is where that side stood before its last score, which is exactly what the
 * back peg on a real board is for.
 */
@Composable
fun CribbageScreen(
    controller: MatchController<CribbageState, CribbageMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()

    val current = state
    if (current == null) {
        ScreenScaffold(title = "Cribbage", onBack = onExit) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Dealing…") }
        }
        return
    }

    // Cards picked out for the crib, held here until they are laid away
    // together. Forgotten whenever a new hand is dealt.
    var chosen by remember(current.handNumber) { mutableStateOf(emptySet<Card>()) }

    ScreenScaffold(title = "Cribbage", onBack = onExit) { modifier ->
        BoxWithConstraints(modifier = modifier) {
            val cardWidth = (minOf(maxWidth, maxHeight) * 0.16f).coerceIn(44.dp, 92.dp)

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PegBoard(current, localSeat)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(FeltGreenDark),
                    contentAlignment = Alignment.Center,
                ) {
                    TableArea(current, localSeat, cardWidth)
                }

                StatusLine(current, localSeat)

                if (current.phase == CribbagePhase.DISCARD) {
                    LayAwayControls(
                        state = current,
                        localSeat = localSeat,
                        chosen = chosen,
                        onLayAway = {
                            controller.submit(LayAway.of(chosen))
                            chosen = emptySet()
                        },
                    )
                }

                HandRow(
                    state = current,
                    localSeat = localSeat,
                    legal = legal,
                    cardWidth = cardWidth,
                    chosen = chosen,
                    onTap = { card ->
                        when (current.phase) {
                            CribbagePhase.DISCARD -> {
                                chosen = if (card in chosen) {
                                    chosen - card
                                } else if (chosen.size < current.options.layAwaySize) {
                                    chosen + card
                                } else {
                                    // Already holding a full lay-away: the newest
                                    // tap replaces the oldest rather than being
                                    // ignored, so no card has to be untapped first.
                                    chosen.drop(1).toSet() + card
                                }
                            }

                            CribbagePhase.PLAY -> controller.submit(PegCard(card))

                            else -> Unit
                        }
                    },
                )
            }

            if (current.phase == CribbagePhase.SHOW || current.phase == CribbagePhase.GAME_OVER) {
                ShowDialog(
                    state = current,
                    localSeat = localSeat,
                    onContinue = { controller.confirmAdvance() },
                    onExit = onExit,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The board
// ---------------------------------------------------------------------------

@Composable
private fun PegBoard(state: CribbageState, localSeat: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (0 until state.options.teamCount).forEach { team ->
            val ours = team == state.options.teamOf(localSeat)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = sideLabel(state, team, localSeat),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (ours) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    text = "${state.scores[team]} of ${state.options.pointsToWin}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            PegTrack(
                score = state.scores[team],
                previous = state.previousScores[team],
                target = state.options.pointsToWin,
                colour = TeamColours[team % TeamColours.size],
            )
        }
    }
}

/** One street of the board: the holes, the back peg and the front peg. */
@Composable
private fun PegTrack(score: Int, previous: Int, target: Int, colour: Color) {
    val holes = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
        val middle = size.height / 2f
        val pegSize = size.height * 0.34f
        val track = size.height * 0.16f
        fun at(points: Int): Float =
            size.width * (points.coerceIn(0, target).toFloat() / target.toFloat())

        drawLine(
            color = holes,
            start = Offset(0f, middle),
            end = Offset(size.width, middle),
            strokeWidth = track,
        )
        // A hole every five, as they are grouped on a real board.
        var mark = 5
        while (mark <= target) {
            drawCircle(color = holes, radius = pegSize * 0.3f, center = Offset(at(mark), middle))
            mark += 5
        }
        drawLine(
            color = colour,
            start = Offset(0f, middle),
            end = Offset(at(score), middle),
            strokeWidth = track,
        )
        drawCircle(
            color = colour.copy(alpha = 0.45f),
            radius = pegSize,
            center = Offset(at(previous), middle),
        )
        drawCircle(color = colour, radius = pegSize, center = Offset(at(score), middle))
    }
}

// ---------------------------------------------------------------------------
// The table
// ---------------------------------------------------------------------------

@Composable
private fun TableArea(state: CribbageState, localSeat: Int, cardWidth: Dp) {
    val ink = MaterialTheme.colorScheme.onPrimary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Starter", style = MaterialTheme.typography.labelSmall, color = ink)
                val starter = state.starter
                if (starter == null) {
                    CardBackView(width = cardWidth)
                } else {
                    PlayingCardView(card = starter, width = cardWidth)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${cribOwner(state, localSeat)} crib",
                    style = MaterialTheme.typography.labelSmall,
                    color = ink,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (state.crib.isEmpty()) {
                        repeat(state.cribCount) { CardBackView(width = cardWidth * 0.45f) }
                    } else {
                        // Face up from the show onwards, when it is counted.
                        state.crib.forEach { PlayingCardView(card = it, width = cardWidth * 0.45f) }
                    }
                }
            }
        }

        if (state.phase == CribbagePhase.PLAY || state.played.isNotEmpty()) {
            Text(
                text = "The count is ${state.count}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ink,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (state.series.isEmpty()) {
                    Text(
                        text = "A fresh count.",
                        style = MaterialTheme.typography.labelSmall,
                        color = ink,
                    )
                }
                state.series.forEach { pegged ->
                    PlayingCardView(card = pegged.card, width = cardWidth * 0.7f)
                }
            }
        }

        OpponentCards(state, localSeat, ink)
    }
}

@Composable
private fun OpponentCards(state: CribbageState, localSeat: Int, ink: Color) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        (0 until state.options.playerCount)
            .filter { it != localSeat }
            .forEach { seat ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = seatLabel(seat, localSeat) +
                            if (seat == state.dealer) " (deals)" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = ink,
                        fontWeight = if (seat == state.turn && !state.saidGo.contains(seat)) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Normal
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        repeat(state.handCounts.getOrElse(seat) { 0 }) {
                            CardBackView(width = 14.dp)
                        }
                    }
                }
            }
    }
}

// ---------------------------------------------------------------------------
// Your side of the table
// ---------------------------------------------------------------------------

@Composable
private fun StatusLine(state: CribbageState, localSeat: Int) {
    val yours = state.turn == localSeat &&
        (state.phase == CribbagePhase.DISCARD || state.phase == CribbagePhase.PLAY)
    val headline = when {
        state.phase == CribbagePhase.DISCARD && yours ->
            "Choose ${state.options.layAwaySize} for ${cribOwner(state, localSeat).lowercase()} crib"

        state.phase == CribbagePhase.DISCARD ->
            "${seatLabel(state.turn, localSeat)} is choosing for the crib…"

        state.phase == CribbagePhase.PLAY && yours -> "Your turn — the count is ${state.count}"
        state.phase == CribbagePhase.PLAY -> "${seatLabel(state.turn, localSeat)} to play…"
        else -> "The show"
    }

    Column {
        Text(headline, fontWeight = FontWeight.Bold)
        state.log.lastOrNull()?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LayAwayControls(
    state: CribbageState,
    localSeat: Int,
    chosen: Set<Card>,
    onLayAway: () -> Unit,
) {
    if (state.turn != localSeat) return
    val wanted = state.options.layAwaySize
    Button(
        onClick = onLayAway,
        enabled = chosen.size == wanted,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (chosen.size == wanted) {
                "Lay away ${chosen.joinToString(" ") { it.label }}"
            } else {
                "Pick ${wanted - chosen.size} more"
            }
        )
    }
}

@Composable
private fun HandRow(
    state: CribbageState,
    localSeat: Int,
    legal: List<CribbageMove>,
    cardWidth: Dp,
    chosen: Set<Card>,
    onTap: (Card) -> Unit,
) {
    val hand = when {
        localSeat !in state.hands.indices -> emptyList()
        state.phase == CribbagePhase.DISCARD -> state.hands[localSeat]
        // Cards stay in the hand once pegged so they can be counted again at
        // the show, so what is still holdable has to be asked for.
        else -> state.remaining(localSeat)
    }
    val playable = legal.filterIsInstance<PegCard>().map { it.card }.toSet()

    Column {
        Text(
            text = if (state.dealer == localSeat) "Your hand — your crib" else "Your hand",
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
                val enabled = when (state.phase) {
                    CribbagePhase.DISCARD -> state.turn == localSeat
                    CribbagePhase.PLAY -> card in playable
                    else -> false
                }
                PlayingCardView(
                    card = card,
                    width = cardWidth,
                    enabled = enabled,
                    selected = card in chosen,
                    onClick = { onTap(card) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The show
// ---------------------------------------------------------------------------

@Composable
private fun ShowDialog(
    state: CribbageState,
    localSeat: Int,
    onContinue: () -> Unit,
    onExit: () -> Unit,
) {
    var dismissed by remember(state.handNumber, state.phase) { mutableStateOf(false) }
    if (dismissed) return

    val over = state.phase == CribbagePhase.GAME_OVER

    AlertDialog(
        onDismissRequest = { },
        title = {
            Text(
                text = when {
                    over && state.winner == state.options.teamOf(localSeat) -> "You win"
                    over -> "${sideLabel(state, state.winner ?: 0, localSeat)} wins"
                    else -> "The show"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.starter?.let {
                    Text("Starter ${it.label}", style = MaterialTheme.typography.bodySmall)
                }
                if (state.show.isEmpty()) {
                    Text(
                        text = "Pegged out during the play — the hands were never counted.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.show.forEach { entry -> ShowEntry(entry, localSeat) }
                (0 until state.options.teamCount).forEach { team ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = sideLabel(state, team, localSeat),
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold,
                        )
                        Text("${state.scores[team]}", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismissed = true
                if (over) onExit() else onContinue()
            }) {
                Text(if (over) "Back to the menu" else "Deal the next hand")
            }
        },
    )
}

@Composable
private fun ShowEntry(entry: ShowScore, localSeat: Int) {
    val who = when {
        entry.isCrib && entry.seat == localSeat -> "Your crib"
        entry.isCrib -> "Seat ${entry.seat}'s crib"
        else -> seatLabel(entry.seat, localSeat)
    }

    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$who — ${entry.cards.joinToString(" ") { it.label }}",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
            )
            Text(if (entry.counted) "${entry.total}" else "—", fontWeight = FontWeight.Bold)
        }
        if (!entry.counted) {
            Text(
                text = "Never counted — the game was already won. Would have been " +
                    "${entry.total}.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (entry.lines.isEmpty()) {
            Text("Nineteen — nothing at all.", style = MaterialTheme.typography.bodySmall)
        }
        entry.lines.forEach { line ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = line.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("${line.points}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---------------------------------------------------------------------------

private fun seatLabel(seat: Int, localSeat: Int): String =
    if (seat == localSeat) "You" else "Seat $seat"

/** Whose crib this hand is, in the second person where that is the answer. */
private fun cribOwner(state: CribbageState, localSeat: Int): String =
    if (state.dealer == localSeat) "Your" else "Seat ${state.dealer}'s"

private fun sideLabel(state: CribbageState, team: Int, localSeat: Int): String = when {
    state.options.playerCount == 4 ->
        if (team == state.options.teamOf(localSeat)) "Your side" else "The other side"

    team == localSeat -> "You"
    else -> "Seat $team"
}
