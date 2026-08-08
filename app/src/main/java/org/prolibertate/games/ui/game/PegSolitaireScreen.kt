package org.prolibertate.games.ui.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.pegsolitaire.Hole
import org.prolibertate.games.game.pegsolitaire.PegBoard
import org.prolibertate.games.game.pegsolitaire.PegJump
import org.prolibertate.games.game.pegsolitaire.PegSolitaireMove
import org.prolibertate.games.game.pegsolitaire.PegSolitaireState
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.theme.FeltGreenDark
import org.prolibertate.games.ui.theme.Parchment
import org.prolibertate.games.ui.theme.WallaceGold
import org.prolibertate.games.ui.theme.WallaceRed

private const val TITLE = "Peg Solitaire"

/**
 * The peg board.
 *
 * One layout serves both board shapes. Each hole is placed at a position in
 * hole-widths rather than pixels, and a triangle simply offsets its rows by half
 * a hole so they stagger — which is the same thing that gives a triangular hole
 * six neighbours instead of four. Scaling that grid to whatever width the phone
 * has is then the only sizing decision left.
 */
@Composable
fun PegSolitaireScreen(
    controller: MatchController<PegSolitaireState, PegSolitaireMove>,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val abandoned by controller.abandoned.collectAsState()

    var selected by remember { mutableStateOf<Hole?>(null) }
    var confirmingEnd by remember { mutableStateOf(false) }

    val endGameAction: @Composable () -> Unit = {
        TextButton(onClick = { confirmingEnd = true }) { Text("End game") }
    }

    val current = state
    if (current == null) {
        ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Setting up…") }
            abandoned?.let { LeftBoardDialog(it, onExit, controller::dismissAbandoned) }
            if (confirmingEnd) EndBoardDialog(onExit) { confirmingEnd = false }
        }
        return
    }

    val jumps = legal.filterIsInstance<PegJump>()
    val movable = jumps.map { it.from }.toSet()
    val landings = selected?.let { from -> jumps.filter { it.from == from } }.orEmpty()
    val destinations = landings.map { it.to }.toSet()
    val finished = jumps.isEmpty()

    ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when {
                    current.solved -> "Solved, in ${current.jumps} jumps."
                    finished -> "Stuck with ${current.remaining} pegs."
                    selected != null -> "Tap a ringed hole to jump into it."
                    else -> "Tap a peg that can jump."
                },
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${current.board.label}. ${current.remaining} pegs left, " +
                    "${current.jumps} jumps made.",
                style = MaterialTheme.typography.bodySmall,
            )

            Board(
                state = current,
                selected = selected,
                movable = movable,
                destinations = destinations,
                onTap = { hole ->
                    selected = when {
                        // Completing a jump takes priority: the tapped hole is
                        // one the selected peg was offered.
                        selected != null && destinations.contains(hole) -> {
                            controller.submit(PegJump(from = selected!!, to = hole))
                            null
                        }

                        movable.contains(hole) -> hole
                        else -> null
                    }
                },
            )

            if (finished) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRestart) { Text("Play again") }
                    TextButton(onClick = onExit) { Text("Leave") }
                }
            }

            current.log.takeLast(3).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        abandoned?.let { LeftBoardDialog(it, onExit, controller::dismissAbandoned) }
        if (confirmingEnd) EndBoardDialog(onExit) { confirmingEnd = false }
    }
}

@Composable
private fun Board(
    state: PegSolitaireState,
    selected: Hole?,
    movable: Set<Hole>,
    destinations: Set<Hole>,
    onTap: (Hole) -> Unit,
) {
    val board = state.board
    val holes = remember(board) { board.holes().toList() }
    val places = remember(board) { holes.associateWith { placeOf(board, it) } }
    val xs = places.values.map { it.first }
    val ys = places.values.map { it.second }
    val minX = xs.min()
    val minY = ys.min()
    val spanX = xs.max() - minX
    val spanY = ys.max() - minY

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // One extra hole-width so the last column has somewhere to sit.
        val cell: Dp = maxWidth / (spanX + 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cell * (spanY + 1f))
                .background(FeltGreenDark),
        ) {
            holes.forEach { hole ->
                val (x, y) = places.getValue(hole)
                HoleView(
                    filled = state.pegs.contains(hole),
                    selected = hole == selected,
                    canMove = movable.contains(hole),
                    isDestination = destinations.contains(hole),
                    size = cell,
                    modifier = Modifier.offset(x = cell * (x - minX), y = cell * (y - minY)),
                    onTap = { onTap(hole) },
                )
            }
        }
    }
}

/**
 * Where a hole sits, in hole-widths from the board's top left.
 *
 * The half-hole shift on a triangle is the whole difference between the two
 * geometries as far as drawing is concerned.
 */
private fun placeOf(board: PegBoard, hole: Hole): Pair<Float, Float> =
    if (board.isTriangular) {
        (hole.col - hole.row / 2f) to hole.row.toFloat()
    } else {
        hole.col.toFloat() to hole.row.toFloat()
    }

@Composable
private fun HoleView(
    filled: Boolean,
    selected: Boolean,
    canMove: Boolean,
    isDestination: Boolean,
    size: Dp,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        val diameter = size * 0.72f
        val ring = when {
            selected -> WallaceGold
            isDestination -> WallaceGold
            else -> Parchment.copy(alpha = 0.35f)
        }
        Box(
            modifier = Modifier
                .size(diameter)
                .background(
                    color = if (filled) WallaceRed else FeltGreenDark,
                    shape = CircleShape,
                )
                .border(
                    // A destination is drawn as an empty ring rather than filled,
                    // so it never reads as a peg already sitting there.
                    border = BorderStroke(if (selected || isDestination) 3.dp else 1.dp, ring),
                    shape = CircleShape,
                )
                .clickable(enabled = filled && canMove || isDestination) { onTap() },
        )
    }
}

@Composable
private fun EndBoardDialog(onEnd: () -> Unit, onKeepPlaying: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepPlaying,
        title = { Text("End this game?") },
        text = { Text("The board is left as it stands.") },
        confirmButton = { TextButton(onClick = onEnd) { Text("End the game") } },
        dismissButton = { TextButton(onClick = onKeepPlaying) { Text("Keep playing") } },
    )
}

@Composable
private fun LeftBoardDialog(notice: String, onExit: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("The game has ended") },
        text = { Text(notice) },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = onStay) { Text("Look at the board") } },
    )
}
