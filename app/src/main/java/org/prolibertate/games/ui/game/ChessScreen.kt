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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.prolibertate.games.game.chess.ChessMove
import org.prolibertate.games.game.chess.ChessPhase
import org.prolibertate.games.game.chess.ChessRules
import org.prolibertate.games.game.chess.ChessState
import org.prolibertate.games.game.chess.Piece
import org.prolibertate.games.game.chess.PieceKind
import org.prolibertate.games.game.chess.fileOf
import org.prolibertate.games.game.chess.isLightSquare
import org.prolibertate.games.game.chess.rankOf
import org.prolibertate.games.game.chess.seatIsWhite
import org.prolibertate.games.game.chess.squareName
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold

/** Board colours, kept off the theme so the board reads the same in either mode. */
private val LightSquare = Color(0xFFEEDAB4)
private val DarkSquare = Color(0xFF9A7248)
private val SelectedSquare = Color(0x8830C030)
private val LastMoveSquare = Color(0x55FFC107)
private val CheckSquare = Color(0x99D32F2F)
private val MoveDot = Color(0x66000000)

/**
 * The chess board.
 *
 * Tap a piece, then tap where it goes. The board is always drawn from the local
 * player's side, so a game against the computer as Black is played the way it
 * would be over a real board rather than upside down.
 */
@Composable
fun ChessScreen(
    controller: MatchController<ChessState, ChessMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()

    var selected by remember { mutableStateOf<Int?>(null) }
    // A pawn reaching the last rank is one tap that stands for four moves, so
    // the destination is held while the piece is chosen.
    var promotionTo by remember { mutableStateOf<Int?>(null) }

    val current = state
    if (current == null) {
        ScreenScaffold(title = "Chess", onBack = onExit) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Setting up…") }
        }
        return
    }

    val flipped = !seatIsWhite(localSeat)
    val fromSelected = selected?.let { from -> legal.filter { it.from == from } }.orEmpty()

    val onSquareTapped: (Int) -> Unit = { square ->
        val destinations = fromSelected.filter { it.to == square }
        when {
            destinations.any { it.promotion != null } -> promotionTo = square
            destinations.isNotEmpty() -> {
                controller.submit(destinations.first())
                selected = null
            }
            // Tapping another of your own pieces switches the selection rather
            // than clearing it, which is what a player expects after a misclick.
            legal.any { it.from == square } -> selected = square
            else -> selected = null
        }
    }

    ScreenScaffold(title = "Chess", onBack = onExit) { modifier ->
        BoxWithConstraints(modifier = modifier) {
            val wide = maxWidth > maxHeight
            val boardSide = if (wide) {
                minOf(maxHeight, maxWidth * 0.62f)
            } else {
                minOf(maxWidth, maxHeight * 0.72f)
            }

            val board: @Composable () -> Unit = {
                Board(
                    state = current,
                    legalFromSelected = fromSelected,
                    selected = selected,
                    flipped = flipped,
                    side = boardSide,
                    onTap = onSquareTapped,
                )
            }
            val panel: @Composable (Modifier) -> Unit = { panelModifier ->
                Column(
                    modifier = panelModifier,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusLine(current, localSeat)
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

            promotionTo?.let { destination ->
                PromotionDialog(
                    white = current.whiteToMove,
                    choices = fromSelected.filter { it.to == destination },
                    onPick = {
                        controller.submit(it)
                        promotionTo = null
                        selected = null
                    },
                    onDismiss = { promotionTo = null },
                )
            }

            if (current.phase == ChessPhase.GAME_OVER) {
                ResultDialog(current, onExit)
            }
        }
    }
}

@Composable
private fun Board(
    state: ChessState,
    legalFromSelected: List<ChessMove>,
    selected: Int?,
    flipped: Boolean,
    side: Dp,
    onTap: (Int) -> Unit,
) {
    val squareSide = side / 8
    val destinations = legalFromSelected.map { it.to }.toSet()
    val checkedKing = if (ChessRules.inCheck(state)) state.kingSquare(state.whiteToMove) else null

    Column(modifier = Modifier.size(side).border(2.dp, DarkSquare)) {
        for (row in 0 until 8) {
            Row(modifier = Modifier.weight(1f)) {
                for (column in 0 until 8) {
                    val display = row * 8 + column
                    // Turning the board round is one subtraction, so the rest of
                    // the screen never has to think about which side is which.
                    val square = if (flipped) 63 - display else display
                    Square(
                        state = state,
                        square = square,
                        squareSide = squareSide,
                        selected = square == selected,
                        isDestination = square in destinations,
                        inCheck = square == checkedKing,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        onTap = { onTap(square) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Square(
    state: ChessState,
    square: Int,
    squareSide: Dp,
    selected: Boolean,
    isDestination: Boolean,
    inCheck: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val piece = state.board[square]
    val touchedByLastMove =
        state.lastMove?.let { square == it.from || square == it.to } == true

    Box(
        modifier = modifier
            .background(if (isLightSquare(square)) LightSquare else DarkSquare)
            .then(if (touchedByLastMove) Modifier.background(LastMoveSquare) else Modifier)
            .then(if (inCheck) Modifier.background(CheckSquare) else Modifier)
            .then(if (selected) Modifier.background(SelectedSquare) else Modifier)
            .clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        if (piece != null) {
            Text(
                text = piece.glyph,
                fontSize = (squareSide.value * 0.78f).sp,
                // The glyphs carry their own colour, but an outline colour keeps
                // white pieces visible on the light squares.
                color = if (piece.white) Color(0xFFFFFFFF) else Color(0xFF101010),
                modifier = Modifier.padding(bottom = squareSide * 0.08f),
            )
        }
        if (isDestination) {
            // A dot for an empty square, a ring for something to take.
            if (piece == null) {
                Box(
                    modifier = Modifier
                        .size(squareSide * 0.28f)
                        .clip(CircleShape)
                        .background(MoveDot)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(squareSide * 0.04f)
                        .border(squareSide * 0.08f, MoveDot, CircleShape)
                )
            }
        }
        // Coordinates on the outer files and ranks, as a printed board has.
        if (fileOf(square) == 0) {
            Text(
                text = "${rankOf(square) + 1}",
                fontSize = (squareSide.value * 0.2f).sp,
                color = if (isLightSquare(square)) DarkSquare else LightSquare,
                modifier = Modifier.align(Alignment.TopStart).padding(1.dp),
            )
        }
        if (rankOf(square) == 0) {
            Text(
                text = "${'a' + fileOf(square)}",
                fontSize = (squareSide.value * 0.2f).sp,
                color = if (isLightSquare(square)) DarkSquare else LightSquare,
                modifier = Modifier.align(Alignment.BottomEnd).padding(1.dp),
            )
        }
    }
}

@Composable
private fun StatusLine(state: ChessState, localSeat: Int) {
    val yourMove = state.turnSeat == localSeat && state.phase == ChessPhase.PLAYING
    Column {
        Text(
            text = state.outcome?.label ?: when {
                yourMove && ChessRules.inCheck(state) -> "You are in check"
                yourMove -> "Your move"
                ChessRules.inCheck(state) -> "Check"
                else -> "Thinking…"
            },
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "You play ${if (seatIsWhite(localSeat)) "White" else "Black"} · " +
                "move ${state.fullmoveNumber}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The game score, two half-moves to a line, most recent at the bottom. */
@Composable
private fun MoveList(state: ChessState, modifier: Modifier = Modifier) {
    if (state.moveLog.isEmpty()) {
        Text("No moves yet.", style = MaterialTheme.typography.bodySmall, modifier = modifier)
        return
    }
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        state.moveLog.chunked(2).forEachIndexed { index, pair ->
            Text(
                text = "${index + 1}. ${pair.joinToString("  ")}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PromotionDialog(
    white: Boolean,
    choices: List<ChessMove>,
    onPick: (ChessMove) -> Unit,
    onDismiss: () -> Unit,
) {
    val order = listOf(PieceKind.QUEEN, PieceKind.ROOK, PieceKind.BISHOP, PieceKind.KNIGHT)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Promote to") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                order.forEach { kind ->
                    val move = choices.firstOrNull { it.promotion == kind } ?: return@forEach
                    Button(onClick = { onPick(move) }, modifier = Modifier.width(64.dp)) {
                        Text(
                            text = Piece(kind, white).glyph,
                            fontSize = 24.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ResultDialog(state: ChessState, onExit: () -> Unit) {
    var dismissed by remember(state.outcome) { mutableStateOf(false) }
    if (dismissed) return

    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Game over") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.outcome?.label.orEmpty(), fontWeight = FontWeight.Bold)
                state.lastMove?.let {
                    Text(
                        text = "Last move ${state.moveLog.lastOrNull() ?: squareName(it.to)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "${state.moveLog.size} half-moves played.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onExit) { Text("Back to the menu") } },
        dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Look at the board") } },
    )
}
