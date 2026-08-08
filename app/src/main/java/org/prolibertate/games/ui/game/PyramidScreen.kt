package org.prolibertate.games.ui.game

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.pyramid.DrawCard
import org.prolibertate.games.game.pyramid.PYRAMID_SIZE
import org.prolibertate.games.game.pyramid.PyramidMove
import org.prolibertate.games.game.pyramid.PyramidSpot
import org.prolibertate.games.game.pyramid.PyramidState
import org.prolibertate.games.game.pyramid.PyramidZone
import org.prolibertate.games.game.pyramid.RecycleWaste
import org.prolibertate.games.game.pyramid.ROWS
import org.prolibertate.games.game.pyramid.TakeKing
import org.prolibertate.games.game.pyramid.TakePair
import org.prolibertate.games.game.pyramid.rowStart
import org.prolibertate.games.game.pyramid.valueOf
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.CardBackView
import org.prolibertate.games.ui.common.PlayingCardView

private const val TITLE = "Pyramid"

/** Cards are small here: the widest row is seven of them side by side. */
private val PYRAMID_CARD: Dp = 40.dp
private val PYRAMID_CARD_HEIGHT: Dp = PYRAMID_CARD * 7f / 5f

/** Rows overlap vertically so the whole pyramid fits above the pack. */
private val ROW_STEP: Dp = PYRAMID_CARD_HEIGHT * 2f / 3f

/**
 * The pyramid.
 *
 * Drawn as it is dealt: seven rows, each one offset half a card so every card
 * sits on the two below it, and rows overlapping by a third so all seven fit.
 * The shape is the rules — a card is only in play once both the cards resting on
 * it have gone, and being able to see that at a glance is most of playing it.
 *
 * Tapping a king takes it alone. Tapping any other card holds it, and tapping
 * its partner takes the pair; the ones that would pair with what is held are the
 * only cards that light up.
 */
@Composable
fun PyramidScreen(
    controller: MatchController<PyramidState, PyramidMove>,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val abandoned by controller.abandoned.collectAsState()

    var held by remember { mutableStateOf<PyramidSpot?>(null) }
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
                PatienceEndDialog("The pyramid is left as it stands.", onExit) { confirmingEnd = false }
            }
        }
        return
    }

    val kings = legal.filterIsInstance<TakeKing>().map { it.spot }.toSet()
    val pairs = legal.filterIsInstance<TakePair>()
    val canDraw = legal.contains(DrawCard)
    val canRedeal = legal.contains(RecycleWaste)
    val finished = legal.isEmpty()

    // Everything that could pair with what is in hand.
    val partners = held?.let { spot ->
        pairs.mapNotNull {
            when (spot) {
                it.first -> it.second
                it.second -> it.first
                else -> null
            }
        }.toSet()
    }.orEmpty()
    val pairable = pairs.flatMap { listOf(it.first, it.second) }.toSet()

    fun tap(spot: PyramidSpot) {
        held = when {
            kings.contains(spot) && held == null -> {
                controller.submit(TakeKing(spot))
                null
            }

            held != null && partners.contains(spot) -> {
                controller.submit(TakePair(held!!, spot))
                null
            }

            held == spot -> null
            pairable.contains(spot) -> spot
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
                    current.isWon -> "Pyramid cleared, in ${current.moves} moves."
                    finished -> "Blocked — nothing pairs to thirteen."
                    held != null -> "Tap the card that makes it thirteen."
                    else -> "${current.pyramid.count { it != null }} of $PYRAMID_SIZE left."
                },
                fontWeight = FontWeight.Bold,
            )

            PyramidView(
                state = current,
                held = held,
                kings = kings,
                partners = partners,
                pairable = pairable,
                onTap = ::tap,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TableSlot(
                    highlighted = false,
                    width = PYRAMID_CARD,
                    onClick = {
                        if (canDraw) {
                            controller.submit(DrawCard)
                        } else if (canRedeal) {
                            controller.submit(RecycleWaste)
                        }
                    },
                ) {
                    if (current.stock.isNotEmpty()) {
                        CardBackView(width = PYRAMID_CARD)
                    } else {
                        EmptySpace(label = if (canRedeal) "↻" else "", width = PYRAMID_CARD)
                    }
                }
                val wasteSpot = PyramidSpot(PyramidZone.WASTE)
                TableSlot(
                    highlighted = held == wasteSpot ||
                        partners.contains(wasteSpot) ||
                        kings.contains(wasteSpot),
                    width = PYRAMID_CARD,
                    onClick = { tap(wasteSpot) },
                ) {
                    current.waste.lastOrNull()
                        ?.let { PlayingCardView(card = it, width = PYRAMID_CARD) }
                        ?: EmptySpace(width = PYRAMID_CARD)
                }
                Text(
                    text = "${current.stock.size} in hand, " +
                        "${current.options.redeals - current.redealsUsed} turns left",
                    style = MaterialTheme.typography.bodySmall,
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

        abandoned?.let { PatienceLeftDialog(it, onExit, controller::dismissAbandoned) }
        if (confirmingEnd) {
            PatienceEndDialog("The pyramid is left as it stands.", onExit) { confirmingEnd = false }
        }
    }
}

@Composable
private fun PyramidView(
    state: PyramidState,
    held: PyramidSpot?,
    kings: Set<PyramidSpot>,
    partners: Set<PyramidSpot>,
    pairable: Set<PyramidSpot>,
    onTap: (PyramidSpot) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PYRAMID_CARD_HEIGHT + ROW_STEP * (ROWS - 1)),
    ) {
        // Each row is half a card narrower on each side than the one below, so
        // every card sits in the notch between the two under it.
        for (row in 0 until ROWS) {
            val cardsInRow = row + 1
            for (column in 0 until cardsInRow) {
                val index = rowStart(row) + column
                val card = state.pyramid[index] ?: continue
                val spot = PyramidSpot(PyramidZone.PYRAMID, index)
                val playable = pairable.contains(spot) || kings.contains(spot)
                Box(
                    modifier = Modifier.offset(
                        // Centred on the widest row, which is seven wide.
                        x = PYRAMID_CARD * (ROWS - cardsInRow) / 2f + PYRAMID_CARD * column,
                        y = ROW_STEP * row,
                    ),
                ) {
                    TableSlot(
                        highlighted = held == spot || partners.contains(spot),
                        width = PYRAMID_CARD,
                        onClick = { if (playable) onTap(spot) },
                    ) {
                        PlayingCardView(
                            card = card,
                            width = PYRAMID_CARD,
                            enabled = playable,
                            caption = valueOf(card).toString(),
                        )
                    }
                }
            }
        }
    }
}
