package org.prolibertate.games.ui.game

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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.freecell.FreeCellMove
import org.prolibertate.games.game.freecell.FreeCellState
import org.prolibertate.games.game.freecell.MoveTo
import org.prolibertate.games.game.freecell.Place
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.PlayingCardView

private const val TITLE = "FreeCell"

/**
 * The FreeCell table.
 *
 * Cells on the left, homes on the right, eight columns below — the standard
 * arrangement, and the one that makes the cells read as somewhere to put things
 * rather than somewhere cards come from. Everything is face up from the first
 * move, so the whole game is on the screen and the only thing hidden is what to
 * do about it.
 */
@Composable
fun FreeCellScreen(
    controller: MatchController<FreeCellState, FreeCellMove>,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val abandoned by controller.abandoned.collectAsState()

    var held by remember { mutableStateOf<Pair<Place, Int>?>(null) }
    var confirmingEnd by remember { mutableStateOf(false) }

    val endGameAction: @Composable () -> Unit = {
        TextButton(onClick = { confirmingEnd = true }) { Text("End game") }
    }

    val current = state
    if (current == null) {
        ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Dealing…") }
            abandoned?.let { PatienceLeftDialog(it, onExit, controller::dismissAbandoned) }
            if (confirmingEnd) {
                PatienceEndDialog("The deal is left as it stands.", onExit) { confirmingEnd = false }
            }
        }
        return
    }

    val moves = legal.filterIsInstance<MoveTo>()
    val finished = legal.isEmpty()
    val targets = held?.let { (from, count) ->
        moves.filter { it.from == from && it.count == count }.map { it.to }.toSet()
    }.orEmpty()
    val sources = moves.map { it.from to it.count }.toSet()

    fun take(place: Place, count: Int) {
        val inHand = held
        held = when {
            inHand != null && targets.contains(place) -> {
                controller.submit(MoveTo(inHand.first, place, inHand.second))
                null
            }

            inHand?.first == place -> null
            sources.contains(place to count) -> place to count
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
                    else -> "${current.foundations.sumOf { it.size }} of 52 home, " +
                        "${current.freeCellCount} cells free."
                },
                fontWeight = FontWeight.Bold,
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                current.cells.forEachIndexed { index, card ->
                    TableSlot(
                        highlighted = held?.first == Place.cell(index) ||
                            targets.contains(Place.cell(index)),
                        onClick = { take(Place.cell(index), 1) },
                    ) {
                        card?.let { PlayingCardView(card = it, width = TABLE_CARD_WIDTH) }
                            ?: EmptySpace(label = "·")
                    }
                }
                current.foundations.forEachIndexed { index, pile ->
                    TableSlot(
                        highlighted = targets.contains(Place.foundation(index)),
                        onClick = { take(Place.foundation(index), 1) },
                    ) {
                        pile.lastOrNull()?.let { PlayingCardView(card = it, width = TABLE_CARD_WIDTH) }
                            ?: EmptySpace(label = Suit.entries[index].symbol)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                current.tableau.forEachIndexed { index, column ->
                    if (column.isEmpty()) {
                        TableSlot(
                            highlighted = targets.contains(Place.column(index)),
                            onClick = { take(Place.column(index), 1) },
                        ) { EmptySpace() }
                    } else {
                        Box(
                            modifier = Modifier
                                .width(TABLE_CARD_WIDTH)
                                .height(TABLE_CARD_HEIGHT + TABLE_STACK_STEP * (column.size - 1)),
                        ) {
                            column.forEachIndexed { depth, card ->
                                // Taking a card takes everything resting on it.
                                val count = column.size - depth
                                TableSlot(
                                    modifier = Modifier.offset(y = TABLE_STACK_STEP * depth),
                                    highlighted = held == (Place.column(index) to count) ||
                                        (depth == column.lastIndex && targets.contains(Place.column(index))),
                                    onClick = { take(Place.column(index), count) },
                                ) { PlayingCardView(card = card, width = TABLE_CARD_WIDTH) }
                            }
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

        abandoned?.let { PatienceLeftDialog(it, onExit, controller::dismissAbandoned) }
        if (confirmingEnd) {
            PatienceEndDialog("The deal is left as it stands.", onExit) { confirmingEnd = false }
        }
    }
}
