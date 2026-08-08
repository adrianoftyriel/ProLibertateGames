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
import org.prolibertate.games.game.spider.DealRow
import org.prolibertate.games.game.spider.MoveRun
import org.prolibertate.games.game.spider.RUNS_TO_WIN
import org.prolibertate.games.game.spider.SpiderMove
import org.prolibertate.games.game.spider.SpiderState
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.CardBackView
import org.prolibertate.games.ui.common.PlayingCardView

private const val TITLE = "Spider"

/**
 * The Spider table.
 *
 * Ten columns of a hundred and four cards, so it scrolls sideways and the cards
 * are the same third-overlapped stacks as everywhere else. What is worth showing
 * above them is the two numbers that decide the game: how many runs have gone
 * away, and how many rows are still in the stock waiting to bury everything.
 */
@Composable
fun SpiderScreen(
    controller: MatchController<SpiderState, SpiderMove>,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val abandoned by controller.abandoned.collectAsState()

    var held by remember { mutableStateOf<Pair<Int, Int>?>(null) }
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

    val runs = legal.filterIsInstance<MoveRun>()
    val canDeal = legal.contains(DealRow)
    val finished = legal.isEmpty()
    val targets = held?.let { (from, count) ->
        runs.filter { it.from == from && it.count == count }.map { it.to }.toSet()
    }.orEmpty()
    val sources = runs.map { it.from to it.count }.toSet()

    fun take(column: Int, count: Int) {
        val inHand = held
        held = when {
            inHand != null && targets.contains(column) -> {
                controller.submit(MoveRun(inHand.first, column, inHand.second))
                null
            }

            inHand?.first == column && inHand.second == count -> null
            sources.contains(column to count) -> column to count
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
                    current.isWon -> "All eight runs, in ${current.moves} moves."
                    finished -> "Blocked — nothing left to try."
                    held != null -> "Tap the column it goes to."
                    else -> "${current.completed} of $RUNS_TO_WIN runs away."
                },
                fontWeight = FontWeight.Bold,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TableSlot(
                    highlighted = false,
                    onClick = { if (canDeal) controller.submit(DealRow) },
                ) {
                    if (current.stock.isNotEmpty()) {
                        CardBackView(width = TABLE_CARD_WIDTH)
                    } else {
                        EmptySpace()
                    }
                }
                Text(
                    text = "${current.stock.size / 10} rows left" +
                        if (!canDeal && current.stock.isNotEmpty()) " — fill every column first" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                current.tableau.forEachIndexed { index, pile ->
                    val total = pile.faceDown.size + pile.faceUp.size
                    if (total == 0) {
                        TableSlot(
                            highlighted = targets.contains(index),
                            onClick = { take(index, 1) },
                        ) { EmptySpace() }
                    } else {
                        Box(
                            modifier = Modifier
                                .width(TABLE_CARD_WIDTH)
                                .height(TABLE_CARD_HEIGHT + TABLE_STACK_STEP * (total - 1)),
                        ) {
                            pile.faceDown.forEachIndexed { depth, _ ->
                                CardBackView(
                                    width = TABLE_CARD_WIDTH,
                                    modifier = Modifier.offset(y = TABLE_STACK_STEP * depth),
                                )
                            }
                            pile.faceUp.forEachIndexed { depth, card ->
                                val count = pile.faceUp.size - depth
                                val row = pile.faceDown.size + depth
                                TableSlot(
                                    modifier = Modifier.offset(y = TABLE_STACK_STEP * row),
                                    highlighted = held == (index to count) ||
                                        (depth == pile.faceUp.lastIndex && targets.contains(index)),
                                    onClick = { take(index, count) },
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
