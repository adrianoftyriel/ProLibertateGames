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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.klondike.Draw
import org.prolibertate.games.game.klondike.KlondikeMove
import org.prolibertate.games.game.klondike.KlondikeState
import org.prolibertate.games.game.klondike.MoveCards
import org.prolibertate.games.game.klondike.Redeal
import org.prolibertate.games.game.klondike.Spot
import org.prolibertate.games.game.klondike.TableauPile
import org.prolibertate.games.game.klondike.foundationSuit
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.CARD_ASPECT
import org.prolibertate.games.ui.common.CardBackView
import org.prolibertate.games.ui.common.PlayingCardView
import org.prolibertate.games.ui.theme.FeltGreenDark
import org.prolibertate.games.ui.theme.Parchment
import org.prolibertate.games.ui.theme.WallaceGold

private const val TITLE = "Klondike"

private val CARD_WIDTH: Dp = 44.dp
private val CARD_HEIGHT: Dp = CARD_WIDTH / CARD_ASPECT

/**
 * How far down each card in a column sits from the one above it.
 *
 * Two thirds of a card, so each is overlapped by a third. That is the most that
 * can be hidden while every card still shows its rank and suit, and it is what
 * keeps a column of thirteen on the screen instead of running off the bottom.
 */
private val STACK_STEP: Dp = CARD_HEIGHT * 2f / 3f

/** The same overlap sideways, for the three cards turned off the stock. */
private val FAN_STEP: Dp = CARD_WIDTH * 2f / 3f

/** At most three are ever turned at once, so at most three are ever fanned. */
private const val MAX_FANNED = 3

/**
 * The patience table.
 *
 * Laid out the way a real one is: the homes along the top where cards are going,
 * the columns filling the middle, and the pack in the bottom corner under your
 * hand. Columns overlap by a third so a long one still fits, and the cards
 * turned off the stock fan sideways by the same third so all three can be seen
 * rather than only the one on top.
 *
 * Play is tap-to-take then tap-to-put rather than dragging: across seven columns
 * of overlapping cards on a phone, a drag is mostly a test of aim. Tapping a
 * face-up card takes it and everything resting on it, so a run travels the way
 * it would on a table. Which taps do anything is read off the legal moves the
 * rules offer, so the screen never has to know why a move is allowed.
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
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

            // The homes, along the top where they are being filled towards.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                verticalAlignment = Alignment.Top,
            ) {
                current.tableau.forEachIndexed { index, pile ->
                    ColumnOfCards(
                        pile = pile,
                        held = held,
                        index = index,
                        isTarget = targets.contains(Spot.tableau(index)),
                        onTap = { count -> take(Spot.tableau(index), count) },
                    )
                }
            }

            // The pack sits at the bottom left, under the hand that turns it.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Slot(
                    highlighted = false,
                    onClick = {
                        if (canDraw) {
                            controller.submit(Draw)
                        } else if (canRedeal) {
                            controller.submit(Redeal)
                        }
                    },
                ) {
                    if (current.stock.isNotEmpty()) {
                        CardBackView(width = CARD_WIDTH)
                    } else {
                        Empty(label = if (canRedeal) "↻" else "")
                    }
                }
                WasteFan(
                    waste = current.waste,
                    fanned = minOf(current.options.drawCount, MAX_FANNED),
                    highlighted = held?.first == Spot.waste,
                    onTap = { take(Spot.waste, 1) },
                )
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

/**
 * One column, overlapped by a third.
 *
 * Absolute offsets rather than negative spacing: the height has to be worked out
 * anyway so the column reserves the right room, and once that is known placing
 * each card is the simpler half.
 */
@Composable
private fun ColumnOfCards(
    pile: TableauPile,
    held: Pair<Spot, Int>?,
    index: Int,
    isTarget: Boolean,
    onTap: (Int) -> Unit,
) {
    val total = pile.faceDown.size + pile.faceUp.size
    if (total == 0) {
        Slot(highlighted = isTarget, onClick = { onTap(1) }) { Empty() }
        return
    }
    Box(
        modifier = Modifier
            .width(CARD_WIDTH)
            .height(CARD_HEIGHT + STACK_STEP * (total - 1)),
    ) {
        pile.faceDown.forEachIndexed { depth, _ ->
            CardBackView(
                width = CARD_WIDTH,
                modifier = Modifier.offset(y = STACK_STEP * depth),
            )
        }
        pile.faceUp.forEachIndexed { depth, card ->
            // Taking a card takes everything resting on it.
            val count = pile.faceUp.size - depth
            val row = pile.faceDown.size + depth
            Slot(
                modifier = Modifier.offset(y = STACK_STEP * row),
                highlighted = held == (Spot.tableau(index) to count) ||
                    (depth == pile.faceUp.lastIndex && isTarget),
                onClick = { onTap(count) },
            ) { PlayingCardView(card = card, width = CARD_WIDTH) }
        }
    }
}

/**
 * The cards turned off the stock, fanned sideways.
 *
 * Drawing three and showing one would hide two cards the player is entitled to
 * see — they are face up on the table. Only the last is playable, which the
 * rules already enforce; the other two are there to be read.
 */
@Composable
private fun WasteFan(
    waste: List<Card>,
    fanned: Int,
    highlighted: Boolean,
    onTap: () -> Unit,
) {
    val shown = waste.takeLast(fanned)
    if (shown.isEmpty()) {
        Slot(highlighted = false, onClick = onTap) { Empty() }
        return
    }
    Box(modifier = Modifier.width(CARD_WIDTH + FAN_STEP * (shown.size - 1))) {
        shown.forEachIndexed { position, card ->
            val isTop = position == shown.lastIndex
            Slot(
                modifier = Modifier.offset(x = FAN_STEP * position),
                highlighted = isTop && highlighted,
                onClick = { if (isTop) onTap() },
            ) { PlayingCardView(card = card, width = CARD_WIDTH) }
        }
    }
}

@Composable
private fun Slot(
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
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
            .height(CARD_HEIGHT)
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
