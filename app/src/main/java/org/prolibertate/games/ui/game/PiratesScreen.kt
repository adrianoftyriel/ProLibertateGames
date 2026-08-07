package org.prolibertate.games.ui.game

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.pirates.ADJACENCY
import org.prolibertate.games.game.pirates.BULGAR_SEAT
import org.prolibertate.games.game.pirates.GRID
import org.prolibertate.games.game.pirates.PIRATE_SEAT
import org.prolibertate.games.game.pirates.POINTS
import org.prolibertate.games.game.pirates.PiratesMove
import org.prolibertate.games.game.pirates.PiratesPhase
import org.prolibertate.games.game.pirates.PiratesState
import org.prolibertate.games.game.pirates.STRONGHOLD
import org.prolibertate.games.game.pirates.columnOf
import org.prolibertate.games.game.pirates.isOnBoard
import org.prolibertate.games.game.pirates.isStronghold
import org.prolibertate.games.game.pirates.pointAt
import org.prolibertate.games.game.pirates.rowOf
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold

private val BoardField = Color(0xFFE7D6B8)
private val StrongholdField = Color(0xFFCBA96B)
private val BoardLine = Color(0xFF5C4326)
private val BulgarStone = Color(0xFF3C6E47)
private val PirateStone = Color(0xFF7A1E1E)
private val StoneEdge = Color(0x66000000)
private val VacantPoint = Color(0x442B2B2B)
private val SelectedRing = Color(0xFF2E9E2E)
private val PathRing = Color(0xFFE0A800)
private val DestinationDot = Color(0x66000000)

/**
 * Pirates and Bulgars, on the cross-shaped board.
 *
 * Tap one of your pieces and then where it goes. A pirate's chain of jumps is
 * one turn, so it is tapped out hop by hop and sent when there is nothing left
 * to take — the capture cannot be abandoned halfway, and the board will not let
 * a player try.
 *
 * The stronghold is the shaded arm at the top. Both sides look at it the same
 * way up: the board is not turned round, because what each side is trying to do
 * with it is not the same and swapping ends would only disguise that.
 */
@Composable
fun PiratesScreen(
    controller: MatchController<PiratesState, PiratesMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val abandoned by controller.abandoned.collectAsState()

    var selected by remember { mutableStateOf<Int?>(null) }
    var path by remember { mutableStateOf(emptyList<Int>()) }
    var confirmingEnd by remember { mutableStateOf(false) }

    val endGameAction: @Composable () -> Unit = {
        TextButton(onClick = { confirmingEnd = true }) { Text("End game") }
    }

    val current = state
    if (current == null) {
        ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Setting up…") }
            abandoned?.let { LeftFieldDialog(it, onExit, controller::dismissAbandoned) }
            if (confirmingEnd) LeaveFieldDialog(onExit) { confirmingEnd = false }
        }
        return
    }

    val following = legal.filter { it.from == selected && it.steps.take(path.size) == path }
    val nextHops = following.mapNotNull { it.steps.getOrNull(path.size) }.toSet()

    val onPointTapped: (Int) -> Unit = { point ->
        when {
            point in nextHops -> {
                val extended = path + point
                val done = following.firstOrNull { it.steps == extended }
                if (done != null) {
                    controller.submit(done)
                    selected = null
                    path = emptyList()
                } else {
                    path = extended
                }
            }

            legal.any { it.from == point } -> {
                selected = point
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
            val side = if (wide) {
                minOf(maxHeight, maxWidth * 0.6f)
            } else {
                minOf(maxWidth, maxHeight * 0.68f)
            }

            val board: @Composable () -> Unit = {
                Board(
                    state = current,
                    side = side,
                    selected = selected,
                    path = path.toSet(),
                    destinations = nextHops,
                    onTap = onPointTapped,
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
                over != null -> LeftFieldDialog(over, onExit, controller::dismissAbandoned)
                confirmingEnd -> LeaveFieldDialog(onExit) { confirmingEnd = false }
                current.phase == PiratesPhase.GAME_OVER -> ResultDialog(current, onExit)
            }
        }
    }
}

private const val TITLE = "Pirates and Bulgars"

@Composable
private fun Board(
    state: PiratesState,
    side: Dp,
    selected: Int?,
    path: Set<Int>,
    destinations: Set<Int>,
    onTap: (Int) -> Unit,
) {
    Box(modifier = Modifier.size(side)) {
        Lines(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            for (row in 0 until GRID) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    for (column in 0 until GRID) {
                        val point = pointAt(row, column)
                        Box(
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (point != null) {
                                Point(
                                    state = state,
                                    point = point,
                                    cell = side / GRID,
                                    selected = point == selected,
                                    onPath = point in path,
                                    isDestination = point in destinations,
                                    onTap = { onTap(point) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The board itself: the cross, the stronghold shaded, and a line between every
 * pair of points that are joined.
 *
 * The lines are drawn from the adjacency table rather than from a picture of a
 * board, so what a player can see is exactly what the rules allow — there is no
 * way for the drawing and the moves to disagree about where the diagonals are.
 */
@Composable
private fun Lines(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val cell = size.width / GRID
        val stroke = (cell * 0.045f).coerceAtLeast(2f)
        fun centre(point: Int) = Offset(
            x = (columnOf(point) + 0.5f) * cell,
            y = (rowOf(point) + 0.5f) * cell,
        )

        // The cross, filled square by square so the cut corners come out right.
        for (row in 0 until GRID) {
            for (column in 0 until GRID) {
                if (!isOnBoard(row, column)) continue
                val inStronghold = pointAt(row, column)?.let { isStronghold(it) } == true
                drawRect(
                    color = if (inStronghold) StrongholdField else BoardField,
                    topLeft = Offset(column * cell, row * cell),
                    size = androidx.compose.ui.geometry.Size(cell, cell),
                )
            }
        }

        for (from in 0 until POINTS) {
            for (to in ADJACENCY[from]) {
                // Each line once rather than twice over.
                if (to <= from) continue
                drawLine(
                    color = BoardLine,
                    start = centre(from),
                    end = centre(to),
                    strokeWidth = stroke,
                )
            }
        }
    }
}

@Composable
private fun Point(
    state: PiratesState,
    point: Int,
    cell: Dp,
    selected: Boolean,
    onPath: Boolean,
    isDestination: Boolean,
    onTap: () -> Unit,
) {
    val occupant = state.board[point]
    Box(
        modifier = Modifier.fillMaxSize().clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        when {
            occupant != null -> Box(
                modifier = Modifier
                    .size(cell * 0.7f)
                    .clip(CircleShape)
                    .background(if (occupant == PIRATE_SEAT) PirateStone else BulgarStone)
                    .border(
                        width = when {
                            selected -> cell * 0.07f
                            onPath -> cell * 0.05f
                            else -> 1.dp
                        },
                        color = when {
                            selected -> SelectedRing
                            onPath -> PathRing
                            else -> StoneEdge
                        },
                        shape = CircleShape,
                    )
            )

            else -> Box(
                modifier = Modifier
                    .size(if (isDestination) cell * 0.32f else cell * 0.14f)
                    .clip(CircleShape)
                    .background(if (isDestination) DestinationDot else VacantPoint)
            )
        }
    }
}

@Composable
private fun Status(state: PiratesState, localSeat: Int, midJump: Boolean) {
    val yours = state.turn == localSeat && state.phase == PiratesPhase.PLAYING
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
            text = "You play ${if (localSeat == PIRATE_SEAT) "the pirates" else "the Bulgars"} · " +
                "${state.stronghold()} of ${STRONGHOLD.size} points held, " +
                "${state.count(BULGAR_SEAT)} Bulgars left",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = if (localSeat == PIRATE_SEAT) {
                "Cut them below ${STRONGHOLD.size} and the assault is finished. " +
                    "You must take when you can."
            } else {
                "Fill the stronghold or pen the pirates in. You cannot take anything, " +
                    "and you cannot go back."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MoveLog(state: PiratesState, modifier: Modifier = Modifier) {
    if (state.moveLog.isEmpty()) {
        Text("No moves yet.", style = MaterialTheme.typography.bodySmall, modifier = modifier)
        return
    }
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        state.moveLog.takeLast(30).forEach {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LeaveFieldDialog(onEnd: () -> Unit, onKeepPlaying: () -> Unit) {
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
private fun LeftFieldDialog(notice: String, onExit: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("The game has ended") },
        text = { Text("$notice There are no more moves to play.") },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = onStay) { Text("Look at the board") } },
    )
}

@Composable
private fun ResultDialog(state: PiratesState, onExit: () -> Unit) {
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
