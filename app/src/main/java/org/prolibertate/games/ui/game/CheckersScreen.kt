package org.prolibertate.games.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.prolibertate.games.game.checkers.BLACK_SEAT
import org.prolibertate.games.game.checkers.CheckersMove
import org.prolibertate.games.game.checkers.CheckersPhase
import org.prolibertate.games.game.checkers.CheckersState
import org.prolibertate.games.game.checkers.seatName
import org.prolibertate.games.game.checkers.squareAt
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold

private val LightSquare = Color(0xFFEEDAB4)
private val DarkSquare = Color(0xFF9A7248)
private val BlackPiece = Color(0xFF1E1B18)
private val WhitePiece = Color(0xFFF7F2E7)
private val PieceEdge = Color(0x66000000)
private val SelectedSquare = Color(0x8830C030)
private val PathSquare = Color(0x66FFC107)
private val MoveDot = Color(0x66000000)
private val CrownInk = Color(0xFFFFC107)

/**
 * The checkers board.
 *
 * Tap a piece, then tap where it goes. A multiple jump is one move as far as
 * the rules are concerned, so it is tapped out one hop at a time and sent when
 * the chain is complete — a player cannot stop in the middle of it, and the
 * board will not let them try.
 *
 * The board is drawn from the local player's side, so a game as White is played
 * up the screen the way it would be across a real table.
 */
@Composable
fun CheckersScreen(
    controller: MatchController<CheckersState, CheckersMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val abandoned by controller.abandoned.collectAsState()

    var selected by remember { mutableStateOf<Int?>(null) }
    // The hops tapped out so far of a jump that is not finished.
    var path by remember { mutableStateOf(emptyList<Int>()) }
    var confirmingEnd by remember { mutableStateOf(false) }

    val endGameAction: @Composable () -> Unit = {
        TextButton(onClick = { confirmingEnd = true }) { Text("End game") }
    }

    val current = state
    if (current == null) {
        ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Setting up…") }
            abandoned?.let { LeftTableDialog(it, onExit, controller::dismissAbandoned) }
            if (confirmingEnd) EndGameDialog(onExit) { confirmingEnd = false }
        }
        return
    }

    // Every move still possible given the piece picked up and the hops tapped.
    val following = legal.filter { move ->
        move.from == selected && move.steps.take(path.size) == path
    }
    val nextHops = following.mapNotNull { it.steps.getOrNull(path.size) }.toSet()

    val onSquareTapped: (Int) -> Unit = { square ->
        when {
            square in nextHops -> {
                val extended = path + square
                val done = following.firstOrNull { it.steps == extended }
                if (done != null) {
                    controller.submit(done)
                    selected = null
                    path = emptyList()
                } else {
                    path = extended
                }
            }

            // Picking up a different piece, which is what a misplaced tap means.
            legal.any { it.from == square } -> {
                selected = square
                path = emptyList()
            }

            else -> {
                selected = null
                path = emptyList()
            }
        }
    }

    ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
        BoxWithConstraints(modifier = modifier) {
            val wide = maxWidth > maxHeight
            val side = if (wide) minOf(maxHeight, maxWidth * 0.62f) else minOf(maxWidth, maxHeight * 0.72f)

            val board: @Composable () -> Unit = {
                Board(
                    state = current,
                    flipped = localSeat != BLACK_SEAT,
                    side = side,
                    selected = selected,
                    path = path,
                    destinations = nextHops,
                    onTap = onSquareTapped,
                )
            }
            val panel: @Composable (Modifier) -> Unit = { panelModifier ->
                Column(modifier = panelModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Status(current, localSeat, path.isNotEmpty())
                    MoveLog(current, modifier = Modifier.weight(1f, fill = false))
                }
            }

            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    board()
                    panel(Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    board()
                    panel(Modifier.fillMaxWidth().weight(1f))
                }
            }

            val over = abandoned
            when {
                over != null -> LeftTableDialog(over, onExit, controller::dismissAbandoned)
                confirmingEnd -> EndGameDialog(onExit) { confirmingEnd = false }
                current.phase == CheckersPhase.GAME_OVER -> ResultDialog(current, onExit)
            }
        }
    }
}

private const val TITLE = "Checkers"

@Composable
private fun Board(
    state: CheckersState,
    flipped: Boolean,
    side: Dp,
    selected: Int?,
    path: List<Int>,
    destinations: Set<Int>,
    onTap: (Int) -> Unit,
) {
    val cell = side / 8
    Column(modifier = Modifier.size(side).border(2.dp, DarkSquare)) {
        for (displayRow in 0 until 8) {
            Row(modifier = Modifier.weight(1f)) {
                for (displayColumn in 0 until 8) {
                    // Turning the board round is one subtraction, so nothing
                    // below this has to know which way up it is drawn.
                    val row = if (flipped) 7 - displayRow else displayRow
                    val column = if (flipped) 7 - displayColumn else displayColumn
                    val square = squareAt(row, column)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(if (square == null) LightSquare else DarkSquare)
                            .then(
                                if (square != null && square in path) {
                                    Modifier.background(PathSquare)
                                } else {
                                    Modifier
                                }
                            )
                            .then(
                                if (square != null && square == selected) {
                                    Modifier.background(SelectedSquare)
                                } else {
                                    Modifier
                                }
                            )
                            .then(
                                if (square != null) Modifier.clickable { onTap(square) } else Modifier
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        val piece = square?.let { state.board[it] }
                        if (piece != null) {
                            Box(
                                modifier = Modifier
                                    .size(cell * 0.74f)
                                    .clip(CircleShape)
                                    .background(
                                        if (piece.seat == BLACK_SEAT) BlackPiece else WhitePiece
                                    )
                                    .border(1.dp, PieceEdge, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                // A crown rather than a second disc: two discs
                                // stacked is what a wooden set does and what a
                                // small screen cannot show.
                                if (piece.king) {
                                    Text(
                                        text = "♛",
                                        color = CrownInk,
                                        fontSize = (cell.value * 0.42f).sp,
                                    )
                                }
                            }
                        }
                        if (square != null && square in destinations) {
                            Box(
                                modifier = Modifier
                                    .size(cell * 0.28f)
                                    .clip(CircleShape)
                                    .background(MoveDot)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Status(state: CheckersState, localSeat: Int, midJump: Boolean) {
    val yours = state.turn == localSeat && state.phase == CheckersPhase.PLAYING
    Column {
        Text(
            text = state.outcome?.label ?: when {
                midJump -> "Keep jumping — the capture has to be finished"
                yours -> "Your move"
                else -> "Thinking…"
            },
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "You play ${seatName(localSeat)} · " +
                "${state.count(localSeat)} left (${state.kings(localSeat)} crowned) " +
                "against ${state.count(1 - localSeat)}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.phase == CheckersPhase.PLAYING) {
            Text(
                text = "A capture must be taken when one is on the board.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MoveLog(state: CheckersState, modifier: Modifier = Modifier) {
    if (state.moveLog.isEmpty()) {
        Text("No moves yet.", style = MaterialTheme.typography.bodySmall, modifier = modifier)
        return
    }
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        state.moveLog.chunked(2).forEachIndexed { index, pair ->
            Text(
                text = "${index + 1}. ${pair.joinToString("   ")}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EndGameDialog(onEnd: () -> Unit, onKeepPlaying: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepPlaying,
        title = { Text("End this game?") },
        text = {
            Text(
                "This game ends here and the position is not saved. Anyone else " +
                    "at the table is told you have left."
            )
        },
        confirmButton = { TextButton(onClick = onEnd) { Text("End the game") } },
        dismissButton = { TextButton(onClick = onKeepPlaying) { Text("Keep playing") } },
    )
}

@Composable
private fun LeftTableDialog(notice: String, onExit: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("The game has ended") },
        text = { Text("$notice There are no more moves to play.") },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = onStay) { Text("Look at the board") } },
    )
}

@Composable
private fun ResultDialog(state: CheckersState, onExit: () -> Unit) {
    var dismissed by remember(state.outcome) { mutableStateOf(false) }
    if (dismissed) return

    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Game over") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.outcome?.label.orEmpty(), fontWeight = FontWeight.Bold)
                Text(
                    text = "${state.moveLog.size} moves played.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Look at the board") } },
    )
}
