package org.prolibertate.games.ui.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import org.prolibertate.games.game.klondike.Draw
import org.prolibertate.games.game.klondike.KlondikeMove
import org.prolibertate.games.game.klondike.KlondikeState
import org.prolibertate.games.game.klondike.MoveCards
import org.prolibertate.games.game.klondike.Redeal
import org.prolibertate.games.game.klondike.Spot
import org.prolibertate.games.game.klondike.foundationSuit
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.CardBackView
import org.prolibertate.games.ui.common.PlayingCardView
import org.prolibertate.games.ui.theme.FeltGreenDark
import org.prolibertate.games.ui.theme.Parchment
import org.prolibertate.games.ui.theme.WallaceGold

private const val TITLE = "Klondike"
private val CARD_WIDTH: Dp = 40.dp

/**
 * The patience table.
 *
 * Play is tap-to-take then tap-to-put, rather than dragging: on a phone a drag
 * over seven columns of overlapping cards is mostly a test of aim. Tapping a
 * face-up card takes it and everything sitting on it, so a run travels the way
 * it would on a table.
 *
 * Which taps do anything is read straight off the legal moves the rules offer,
 * so the screen never has to know why a move is allowed — only that it is.
 */
@Composable
fun KlondikeScreen(
    controller: MatchController<KlondikeState, KlondikeMove>,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val abandoned by controller.abandoned.collectAsState()

    var held by remember { mutableStateOf<Pair<Spot, Int>?>(null) }
    var confirmingEnd by remember { mutableStateOf(false) }

    val endGameAction: @Composable () -> Unit = {
        TextButton(onClick = { confirmingEnd = true }) { Text("End game") }
    }

    val current = state
    if (current == null) {
        ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Dealing…") }
            abandoned?.let { LeftTableDialog(it, onExit, controller::dismissAbandoned) }
            if (confirmingEnd) EndTableDialog(onExit) { confirmingEnd = false }
        }
        return
    }

    val moves = legal.filterIsInstance<MoveCards>()
    val canDraw = legal.contains(Draw)
    val canRedeal = legal.contains(Redeal)
    val finished = legal.isEmpty()

    // Where the cards in hand could go, and which spots could be picked up.
    val targets = held?.let { (from, count) ->
        moves.filter { it.from == from && it.count == count }.map { it.to }.toSet()
    }.orEmpty()
    val sources = moves.map { it.from to it.count }.toSet()

    fun take(spot: Spot, count: Int) {
        val inHand = held
        held = when {
            inHand != null && targets.contains(spot) -> {
                controller.submit(MoveCards(inHand.first, spot, inHand.second))
                null
            }

            inHand?.first == spot -> null
            sources.contains(spot to count) -> spot to count
            else -> null
        }
    }

    ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when {
                    current.isWon -> "Out, in ${current.moves} moves."
                    finished -> "Blocked — nothing left to try."
                    held != null -> "Tap where it goes."
                    else -> "${current.foundations.sumOf { it.size }} of 52 home."
                },
                fontWeight = FontWeight.Bold,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Stock, waste, and the four homes across the top.
                Slot(
                    highlighted = false,
                    onClick = { if (canDraw) controller.submit(Draw) else if (canRedeal) controller.submit(Redeal) },
                ) {
                    if (current.stock.isNotEmpty()) {
                        CardBackView(width = CARD_WIDTH)
                    } else {
                        Empty(label = if (canRedeal) "↻" else "")
                    }
                }
                Slot(
                    highlighted = held?.first == Spot.waste,
                    onClick = { take(Spot.waste, 1) },
                ) {
                    current.waste.lastOrNull()
                        ?.let { PlayingCardView(card = it, width = CARD_WIDTH) }
                        ?: Empty()
                }
                current.foundations.forEachIndexed { index, pile ->
                    Slot(
                        highlighted = targets.contains(Spot.foundation(index)),
                        onClick = { take(Spot.foundation(index), 1) },
                    ) {
                        pile.lastOrNull()
                            ?.let { PlayingCardView(card = it, width = CARD_WIDTH) }
                            ?: Empty(label = foundationSuit(index).symbol)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                current.tableau.forEachIndexed { index, pile ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (pile.isEmpty) {
                            Slot(
                                highlighted = targets.contains(Spot.tableau(index)),
                                onClick = { take(Spot.tableau(index), 1) },
                            ) { Empty() }
                        }
                        pile.faceDown.forEach { CardBackView(width = CARD_WIDTH) }
                        pile.faceUp.forEachIndexed { depth, card ->
                            // Taking a card takes everything resting on it, so
                            // the count is however many are below it in the run.
                            val count = pile.faceUp.size - depth
                            Slot(
                                highlighted = held == (Spot.tableau(index) to count) ||
                                    (depth == pile.faceUp.lastIndex && targets.contains(Spot.tableau(index))),
                                onClick = { take(Spot.tableau(index), count) },
                            ) { PlayingCardView(card = card, width = CARD_WIDTH) }
                        }
                    }
                }
            }

            if (finished) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRestart) { Text("Deal again") }
                    TextButton(onClick = onExit) { Text("Leave") }
                }
            }

            current.log.takeLast(2).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        abandoned?.let { LeftTableDialog(it, onExit, controller::dismissAbandoned) }
        if (confirmingEnd) EndTableDialog(onExit) { confirmingEnd = false }
    }
}

@Composable
private fun Slot(highlighted: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .width(CARD_WIDTH)
            .border(
                border = BorderStroke(if (highlighted) 3.dp else 0.dp, WallaceGold),
                shape = RoundedCornerShape(4.dp),
            )
            .clickable { onClick() },
    ) {
        content()
    }
}

/** An empty place on the table, drawn so it can still be tapped into. */
@Composable
private fun Empty(label: String = "") {
    Box(
        modifier = Modifier
            .width(CARD_WIDTH)
            .aspectRatio(0.7f)
            .background(FeltGreenDark, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (label.isNotEmpty()) {
            Text(label, color = Parchment.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun EndTableDialog(onEnd: () -> Unit, onKeepPlaying: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepPlaying,
        title = { Text("End this game?") },
        text = { Text("The deal is left as it stands.") },
        confirmButton = { TextButton(onClick = onEnd) { Text("End the game") } },
        dismissButton = { TextButton(onClick = onKeepPlaying) { Text("Keep playing") } },
    )
}

@Composable
private fun LeftTableDialog(notice: String, onExit: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("The game has ended") },
        text = { Text(notice) },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = onStay) { Text("Look at the table") } },
    )
}
