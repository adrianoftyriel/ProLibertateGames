package org.prolibertate.games.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import org.prolibertate.games.game.morris.BLACK_SEAT
import org.prolibertate.games.game.morris.MorrisMove
import org.prolibertate.games.game.morris.MorrisPhase
import org.prolibertate.games.game.morris.MorrisState
import org.prolibertate.games.game.morris.POINTS
import org.prolibertate.games.game.morris.WHITE_SEAT
import org.prolibertate.games.game.morris.columnOf
import org.prolibertate.games.game.morris.pointName
import org.prolibertate.games.game.morris.rowOf
import org.prolibertate.games.game.morris.seatIsWhite
import org.prolibertate.games.game.morris.seatName
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold

// Board colours, kept off the theme so the board reads the same either way up.
private val BoardField = Color(0xFFEEDAB4)
private val BoardLine = Color(0xFF6B4E2E)
private val WhiteStone = Color(0xFFFAF6EC)
private val BlackStone = Color(0xFF23201C)
private val StoneEdge = Color(0x66000000)
private val VacantPoint = Color(0x552B2B2B)
private val SelectedRing = Color(0xFF2E9E2E)
private val TakeableRing = Color(0xFFD32F2F)
private val LastMoveRing = Color(0xFFE0A800)
private val DestinationDot = Color(0x66000000)

/** The seven-by-seven grid the twenty-four points are drawn on. */
private const val GRID = 7

/**
 * Nine Men's Morris.
 *
 * Tap an empty point to place a piece; once every piece is on the board, tap
 * one of your own and then where it goes. Closing a line of three asks which
 * enemy piece to take — unless only one may be taken, in which case there is
 * nothing to ask.
 *
 * The board is not turned round for Black. It is symmetrical, both players see
 * the same lines, and there is no "your side" of it to sit behind.
 */
@Composable
fun MorrisScreen(
    controller: MatchController<MorrisState, MorrisMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val abandoned by controller.abandoned.collectAsState()

    // The piece picked up, once pieces are being moved rather than placed.
    var selected by remember { mutableStateOf<Int?>(null) }
    // A move that has been chosen but not sent, because closing a mill leaves a
    // second decision: which enemy piece it takes.
    var millFrom by remember { mutableStateOf<Int?>(null) }
    var millTo by remember { mutableStateOf<Int?>(null) }
    var confirmingEnd by remember { mutableStateOf(false) }

    val endGameAction: @Composable () -> Unit = {
        TextButton(onClick = { confirmingEnd = true }) { Text("End game") }
    }

    val current = state
    if (current == null) {
        ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Setting up…") }
            abandoned?.let {
                LeftTheBoardDialog(it, onExit = onExit, onStay = controller::dismissAbandoned)
            }
            if (confirmingEnd) {
                LeaveBoardDialog(onEnd = onExit, onKeepPlaying = { confirmingEnd = false })
            }
        }
        return
    }

    // While a mill is waiting to be paid, the only points that do anything are
    // the enemy pieces it may take.
    val takeable = if (millTo == null) {
        emptyList()
    } else {
        legal.filter { it.from == millFrom && it.to == millTo }.mapNotNull { it.remove }
    }

    val placing = current.phase == MorrisPhase.PLACING
    val movingFrom = if (placing) null else selected
    val destinations = if (millTo != null) {
        emptyList()
    } else {
        legal.filter { it.from == movingFrom }.map { it.to }
    }

    val onPointTapped: (Int) -> Unit = { point ->
        if (millTo != null) {
            // Paying for the mill. Anything that is not a piece it may take
            // puts the move back down rather than sending something unmeant.
            val move = legal.firstOrNull {
                it.from == millFrom && it.to == millTo && it.remove == point
            }
            if (move != null) {
                controller.submit(move)
                selected = null
            }
            millFrom = null
            millTo = null
        } else {
            val candidates = legal.filter { it.from == movingFrom && it.to == point }
            when {
                // One way to play it, mill or no mill: no question to ask.
                candidates.size == 1 -> {
                    controller.submit(candidates.single())
                    selected = null
                }

                candidates.size > 1 -> {
                    millFrom = movingFrom
                    millTo = point
                }

                // Tapping another of your own pieces picks that one up instead,
                // which is what a player expects after a misplaced tap.
                !placing && legal.any { it.from == point } -> selected = point

                else -> selected = null
            }
        }
    }

    ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
        BoxWithConstraints(modifier = modifier) {
            val wide = maxWidth > maxHeight
            val boardSide = if (wide) {
                minOf(maxHeight, maxWidth * 0.62f)
            } else {
                minOf(maxWidth, maxHeight * 0.68f)
            }

            val board: @Composable () -> Unit = {
                Board(
                    state = current,
                    side = boardSide,
                    selected = selected,
                    destinations = destinations.toSet(),
                    takeable = takeable.toSet(),
                    onTap = onPointTapped,
                )
            }
            val panel: @Composable (Modifier) -> Unit = { panelModifier ->
                Column(
                    modifier = panelModifier,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusLine(
                        state = current,
                        localSeat = localSeat,
                        choosingWhatToTake = millTo != null,
                    )
                    Hands(current)
                    MoveList(current, modifier = Modifier.weight(1f, fill = false))
                }
            }

            if (wide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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

            // One dialog at a time, most pressing first: a table nobody can
            // finish comes before the result of one that finished.
            val over = abandoned
            when {
                over != null ->
                    LeftTheBoardDialog(over, onExit = onExit, onStay = controller::dismissAbandoned)

                confirmingEnd ->
                    LeaveBoardDialog(onEnd = onExit, onKeepPlaying = { confirmingEnd = false })

                current.phase == MorrisPhase.GAME_OVER -> ResultDialog(current, onExit)
            }
        }
    }
}

private const val TITLE = "Nine Men's Morris"

// ---------------------------------------------------------------------------
// The board
// ---------------------------------------------------------------------------

@Composable
private fun Board(
    state: MorrisState,
    side: Dp,
    selected: Int?,
    destinations: Set<Int>,
    takeable: Set<Int>,
    onTap: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(side)
            .background(BoardField)
            .border(2.dp, BoardLine),
    ) {
        Lines(modifier = Modifier.fillMaxSize())

        // The points sit on the grid the lines were drawn from, so a stone
        // lands on the intersection rather than near it.
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
                                    isDestination = point in destinations,
                                    isTakeable = point in takeable,
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
 * The three squares and the four lines joining them.
 *
 * Drawn from the same grid the points are laid out on — a line from the centre
 * of one cell to the centre of another — so the board cannot drift out of step
 * with the stones standing on it.
 */
@Composable
private fun Lines(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val cell = size.width / GRID
        val stroke = (cell * 0.05f).coerceAtLeast(2f)
        fun centre(row: Int, column: Int) =
            Offset(x = (column + 0.5f) * cell, y = (row + 0.5f) * cell)

        for (ring in 0..2) {
            val near = ring
            val far = 6 - ring
            val corners = listOf(
                centre(near, near),
                centre(near, far),
                centre(far, far),
                centre(far, near),
            )
            corners.forEachIndexed { index, corner ->
                drawLine(
                    color = BoardLine,
                    start = corner,
                    end = corners[(index + 1) % corners.size],
                    strokeWidth = stroke,
                )
            }
        }

        // The ladders, out from the inner square to the outer one along the
        // middle of each edge.
        listOf(
            centre(0, 3) to centre(2, 3),
            centre(3, 6) to centre(3, 4),
            centre(6, 3) to centre(4, 3),
            centre(3, 0) to centre(3, 2),
        ).forEach { (from, to) ->
            drawLine(color = BoardLine, start = from, end = to, strokeWidth = stroke)
        }
    }
}

@Composable
private fun Point(
    state: MorrisState,
    point: Int,
    cell: Dp,
    selected: Boolean,
    isDestination: Boolean,
    isTakeable: Boolean,
    onTap: () -> Unit,
) {
    val occupant = state.board[point]
    val touchedByLastMove = state.lastMove?.let {
        point == it.to || point == it.from || point == it.remove
    } == true

    Box(
        // The whole cell takes the tap, not just the stone: an intersection on
        // a phone is smaller than a fingertip.
        modifier = Modifier.fillMaxSize().clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        when {
            occupant != null -> Box(
                modifier = Modifier
                    .size(cell * 0.72f)
                    .clip(CircleShape)
                    .background(if (occupant == WHITE_SEAT) WhiteStone else BlackStone)
                    .border(
                        width = when {
                            selected || isTakeable -> cell * 0.07f
                            touchedByLastMove -> cell * 0.05f
                            else -> 1.dp
                        },
                        color = when {
                            selected -> SelectedRing
                            isTakeable -> TakeableRing
                            touchedByLastMove -> LastMoveRing
                            else -> StoneEdge
                        },
                        shape = CircleShape,
                    )
            )

            else -> Box(
                modifier = Modifier
                    .size(if (isDestination) cell * 0.34f else cell * 0.16f)
                    .clip(CircleShape)
                    .background(if (isDestination) DestinationDot else VacantPoint)
            )
        }
    }
}

/** The point drawn at a grid square, or null where the board is blank. */
private fun pointAt(row: Int, column: Int): Int? =
    (0 until POINTS).firstOrNull { rowOf(it) == row && columnOf(it) == column }

// ---------------------------------------------------------------------------
// The panel beside it
// ---------------------------------------------------------------------------

@Composable
private fun StatusLine(state: MorrisState, localSeat: Int, choosingWhatToTake: Boolean) {
    val yours = state.turn == localSeat && state.phase != MorrisPhase.GAME_OVER
    Column {
        Text(
            text = state.outcome?.label ?: when {
                choosingWhatToTake -> "A mill — take one of their pieces"
                yours && state.phase == MorrisPhase.PLACING ->
                    "Your turn — place a piece (${state.inHand(localSeat)} left)"

                yours -> "Your move"
                else -> "Thinking…"
            },
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "You play ${seatName(localSeat)} · " +
                "${state.onBoard(localSeat)} on the board, " +
                "${state.onBoard(1 - localSeat)} against you",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.isFlying(state.turn)) {
            Text(
                text = "${seatName(state.turn)} is down to three and may jump anywhere.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** What is still waiting to be put on the board, for each side. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hands(state: MorrisState) {
    if (state.phase != MorrisPhase.PLACING) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(WHITE_SEAT, BLACK_SEAT).forEach { seat ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${seatName(seat)}: ",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(state.inHand(seat)) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (seatIsWhite(seat)) WhiteStone else BlackStone)
                                .border(1.dp, StoneEdge, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

/** The game record, most recent at the bottom. */
@Composable
private fun MoveList(state: MorrisState, modifier: Modifier = Modifier) {
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

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun LeaveBoardDialog(onEnd: () -> Unit, onKeepPlaying: () -> Unit) {
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
private fun LeftTheBoardDialog(notice: String, onExit: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("The game has ended") },
        text = { Text("$notice There are no more moves to play.") },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = onStay) { Text("Look at the board") } },
    )
}

@Composable
private fun ResultDialog(state: MorrisState, onExit: () -> Unit) {
    var dismissed by remember(state.outcome) { mutableStateOf(false) }
    if (dismissed) return

    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Game over") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.outcome?.label.orEmpty(), fontWeight = FontWeight.Bold)
                state.lastMove?.let { move ->
                    Text(
                        text = "Last move ${move.from?.let { "${pointName(it)} to " }.orEmpty()}" +
                            pointName(move.to) +
                            (move.remove?.let { ", taking ${pointName(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
