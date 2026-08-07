package org.prolibertate.games.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import org.prolibertate.games.game.backgammon.BAR
import org.prolibertate.games.game.backgammon.BackgammonMove
import org.prolibertate.games.game.backgammon.BackgammonPhase
import org.prolibertate.games.game.backgammon.BackgammonState
import org.prolibertate.games.game.backgammon.CHECKERS
import org.prolibertate.games.game.backgammon.WHITE_SEAT
import org.prolibertate.games.game.backgammon.other
import org.prolibertate.games.game.backgammon.seatName
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold

private val BoardField = Color(0xFF7A5230)
private val PointLight = Color(0xFFE8CFA9)
private val PointDark = Color(0xFF9C3B2E)
private val WhiteChecker = Color(0xFFF7F2E7)
private val BlackChecker = Color(0xFF23201C)
private val CheckerEdge = Color(0x66000000)
private val SelectedPoint = Color(0x8830C030)
private val TargetPoint = Color(0x66FFC107)

/**
 * The backgammon board, drawn as two banks of twelve points with the bar down
 * the middle.
 *
 * Tap a point to pick a checker up and tap where it goes; if there is only one
 * thing that checker can do, tapping it plays it. The dice left to play are
 * shown above the board and go out as they are used.
 *
 * Points are drawn in the engine's own numbering — 1 to 24 with White running
 * down — rather than being flipped for whoever is sitting there. A backgammon
 * board is symmetrical and both players read the same numbers off it.
 */
@Composable
fun BackgammonScreen(
    controller: MatchController<BackgammonState, BackgammonMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val abandoned by controller.abandoned.collectAsState()

    var selected by remember { mutableStateOf<Int?>(null) }
    var confirmingEnd by remember { mutableStateOf(false) }

    val endGameAction: @Composable () -> Unit = {
        TextButton(onClick = { confirmingEnd = true }) { Text("End game") }
    }

    val current = state
    if (current == null) {
        ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Setting up…") }
            abandoned?.let { LeftBoardDialog(it, onExit, controller::dismissAbandoned) }
            if (confirmingEnd) LeaveDialog(onExit) { confirmingEnd = false }
        }
        return
    }

    val fromSelected = legal.filter { it.from == selected }
    val targets = fromSelected.mapNotNull { move ->
        org.prolibertate.games.game.backgammon.BackgammonRules
            .destinationOf(current.turn, move.from, move.die)
    }.toSet()

    val onPointTapped: (Int) -> Unit = { point ->
        val landing = fromSelected.firstOrNull { move ->
            org.prolibertate.games.game.backgammon.BackgammonRules
                .destinationOf(current.turn, move.from, move.die) == point
        }
        when {
            landing != null -> {
                controller.submit(landing)
                selected = null
            }

            legal.any { it.from == point } -> {
                val fromHere = legal.filter { it.from == point }
                // Nothing to choose between means nothing to ask about.
                if (fromHere.size == 1) {
                    controller.submit(fromHere.single())
                    selected = null
                } else {
                    selected = point
                }
            }

            else -> selected = null
        }
    }

    ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Status(current, localSeat)
            DiceRow(current)

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val boardWidth = minOf(maxWidth, maxHeight * 1.25f)
                Board(
                    state = current,
                    width = boardWidth,
                    height = minOf(maxHeight, boardWidth * 0.8f),
                    selected = selected,
                    targets = targets,
                    onTap = onPointTapped,
                )
            }

            OffAndBar(current, localSeat) { onPointTapped(BAR) }
        }

        val over = abandoned
        when {
            over != null -> LeftBoardDialog(over, onExit, controller::dismissAbandoned)
            confirmingEnd -> LeaveDialog(onExit) { confirmingEnd = false }
            current.phase == BackgammonPhase.GAME_OVER -> ResultDialog(current, onExit)
        }
    }
}

private const val TITLE = "Backgammon"

@Composable
private fun Status(state: BackgammonState, localSeat: Int) {
    val yours = state.turn == localSeat && state.phase == BackgammonPhase.PLAYING
    Column {
        Text(
            text = state.outcome?.label ?: when {
                yours && state.bar[localSeat] > 0 -> "Come in off the bar"
                yours -> "Your roll to play"
                else -> "Thinking…"
            },
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "You play ${seatName(localSeat)} · " +
                "${state.pipCount(localSeat)} pips against ${state.pipCount(other(localSeat))}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The dice, with the ones already played greyed out rather than removed. */
@Composable
private fun DiceRow(state: BackgammonState) {
    val left = state.dice.toMutableList()
    val shown = if (state.roll.size == 2 && state.roll[0] == state.roll[1]) {
        List(4) { state.roll[0] }
    } else {
        state.roll
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { die ->
            val unplayed = left.remove(die)
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(if (unplayed) PointLight else Color(0x33808080))
                    .border(1.dp, CheckerEdge),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$die",
                    color = Color(0xFF23201C),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Board(
    state: BackgammonState,
    width: Dp,
    height: Dp,
    selected: Int?,
    targets: Set<Int>,
    onTap: (Int) -> Unit,
) {
    val columnWidth = width / 13
    val bankHeight = height / 2

    Column(modifier = Modifier.width(width).height(height).background(BoardField)) {
        // The top bank runs 13 to 24 left to right, the bottom 12 down to 1 —
        // the standard layout, with White's home at the bottom right.
        Bank(
            points = (12..23).toList(),
            state = state,
            columnWidth = columnWidth,
            bankHeight = bankHeight,
            pointingDown = true,
            selected = selected,
            targets = targets,
            onTap = onTap,
        )
        Bank(
            points = (11 downTo 0).toList(),
            state = state,
            columnWidth = columnWidth,
            bankHeight = bankHeight,
            pointingDown = false,
            selected = selected,
            targets = targets,
            onTap = onTap,
        )
    }
}

@Composable
private fun Bank(
    points: List<Int>,
    state: BackgammonState,
    columnWidth: Dp,
    bankHeight: Dp,
    pointingDown: Boolean,
    selected: Int?,
    targets: Set<Int>,
    onTap: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().height(bankHeight)) {
        points.forEachIndexed { index, point ->
            // The bar sits between the two halves of each bank.
            if (index == 6) {
                Box(
                    modifier = Modifier
                        .width(columnWidth)
                        .fillMaxHeight()
                        .background(BoardField)
                        .border(1.dp, Color(0x33000000))
                )
            }
            PointColumn(
                point = point,
                state = state,
                width = columnWidth,
                height = bankHeight,
                light = index % 2 == 0,
                pointingDown = pointingDown,
                selected = point == selected,
                target = point in targets,
                onTap = { onTap(point) },
            )
        }
    }
}

@Composable
private fun PointColumn(
    point: Int,
    state: BackgammonState,
    width: Dp,
    height: Dp,
    light: Boolean,
    pointingDown: Boolean,
    selected: Boolean,
    target: Boolean,
    onTap: () -> Unit,
) {
    val white = state.countOn(point, WHITE_SEAT)
    val black = state.countOn(point, 1 - WHITE_SEAT)
    val count = white + black

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(if (light) PointLight.copy(alpha = 0.35f) else PointDark.copy(alpha = 0.5f))
            .then(if (selected) Modifier.background(SelectedPoint) else Modifier)
            .then(if (target) Modifier.background(TargetPoint) else Modifier)
            .border(1.dp, Color(0x22000000))
            .clickable { onTap() },
        contentAlignment = if (pointingDown) Alignment.TopCenter else Alignment.BottomCenter,
    ) {
        Column(
            verticalArrangement = if (pointingDown) Arrangement.Top else Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Five checkers is as many as fit; anything beyond that is written
            // on the last one, which is what a crowded board does anyway.
            val drawn = minOf(count, 5)
            repeat(drawn) { index ->
                val isLast = index == drawn - 1
                Box(
                    modifier = Modifier
                        .size(width * 0.82f)
                        .clip(CircleShape)
                        .background(if (white > 0) WhiteChecker else BlackChecker)
                        .border(1.dp, CheckerEdge, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLast && count > 5) {
                        Text(
                            text = "$count",
                            color = if (white > 0) Color(0xFF23201C) else Color(0xFFF7F2E7),
                            fontSize = (width.value * 0.36f).sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        Text(
            text = "${point + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0x99000000),
            modifier = Modifier
                .align(if (pointingDown) Alignment.BottomCenter else Alignment.TopCenter)
                .padding(1.dp),
        )
    }
}

/** What is on the bar and what is safely off, with the bar tappable to come in. */
@Composable
private fun OffAndBar(state: BackgammonState, localSeat: Int, onBarTapped: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBarTapped, enabled = state.bar[localSeat] > 0) {
            Text("Bar: ${state.bar[localSeat]}")
        }
        Text(
            text = "Borne off — you ${state.off[localSeat]} of $CHECKERS, " +
                "them ${state.off[other(localSeat)]}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LeaveDialog(onEnd: () -> Unit, onKeepPlaying: () -> Unit) {
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
private fun LeftBoardDialog(notice: String, onExit: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("The game has ended") },
        text = { Text("$notice There are no more moves to play.") },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = onStay) { Text("Look at the board") } },
    )
}

@Composable
private fun ResultDialog(state: BackgammonState, onExit: () -> Unit) {
    var dismissed by remember(state.outcome) { mutableStateOf(false) }
    if (dismissed) return

    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Game over") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.outcome?.label.orEmpty(), fontWeight = FontWeight.Bold)
                state.outcome?.let {
                    Text(
                        text = "Worth ${it.points} point${if (it.points == 1) "" else "s"}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Look at the board") } },
    )
}
