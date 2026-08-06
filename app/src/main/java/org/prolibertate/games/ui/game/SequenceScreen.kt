package org.prolibertate.games.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.sequence.BOARD_SIZE
import org.prolibertate.games.game.sequence.ExchangeDeadCard
import org.prolibertate.games.game.sequence.isOneEyedJack
import org.prolibertate.games.game.sequence.isTwoEyedJack
import org.prolibertate.games.game.sequence.NO_TEAM
import org.prolibertate.games.game.sequence.PlaceChip
import org.prolibertate.games.game.sequence.RemoveChip
import org.prolibertate.games.game.sequence.SequenceBoard
import org.prolibertate.games.game.sequence.SequenceMove
import org.prolibertate.games.game.sequence.SequenceState
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.PrimaryAction
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.theme.FeltGreenDark
import org.prolibertate.games.ui.theme.TeamColours

/**
 * Outline for a square the selected card can be played on. Deliberately not a
 * theme colour: it must contrast with both team chip colours and with the
 * board in either light or dark mode.
 */
private val HighlightAmber = Color(0xFFFFC107)

/**
 * What a jack does, printed on its face. On a real deck this is carried by the
 * artwork — a jack shown in profile has one eye and removes a chip, one shown
 * face-on has two and is wild — which a rank-and-suit card cannot convey.
 */
private fun jackCaption(card: Card): String? = when {
    isTwoEyedJack(card) -> "WILD"
    isOneEyedJack(card) -> "REMOVE"
    else -> null
}

/**
 * The Sequence board.
 *
 * The board is always square and always fits: its side is the smaller of the
 * available width and the height left over after the hand, so rotating the
 * device rescales rather than clipping.
 */
@Composable
fun SequenceScreen(
    controller: MatchController<SequenceState, SequenceMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    var selected by remember { mutableStateOf<Card?>(null) }

    val current = state
    if (current == null) {
        ScreenScaffold(title = "Sequence", onBack = onExit) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Dealing…") }
        }
        return
    }

    // Squares the selected card could legally claim or clear.
    val placements = legal.filterIsInstance<PlaceChip>().filter { it.card == selected }
    val removals = legal.filterIsInstance<RemoveChip>().filter { it.card == selected }
    val highlighted = (placements.map { it.cell } + removals.map { it.cell }).toSet()

    ScreenScaffold(title = "Sequence", onBack = onExit) { modifier ->
        BoxWithConstraints(modifier = modifier) {
            val wide = maxWidth > maxHeight
            val boardSide = if (wide) {
                minOf(maxWidth * 0.62f, maxHeight)
            } else {
                minOf(maxWidth, maxHeight * 0.68f)
            }
            val cell = boardSide / BOARD_SIZE

            val board: @Composable () -> Unit = {
                BoardGrid(
                    state = current,
                    cellSize = cell,
                    highlighted = highlighted,
                    onCellTap = { index ->
                        val placement = placements.firstOrNull { it.cell == index }
                        val removal = removals.firstOrNull { it.cell == index }
                        when {
                            placement != null -> {
                                controller.submit(placement)
                                selected = null
                            }

                            removal != null -> {
                                controller.submit(removal)
                                selected = null
                            }
                        }
                    },
                )
            }

            val side: @Composable () -> Unit = {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusPanel(current, localSeat)
                    HandStrip(
                        state = current,
                        seat = localSeat,
                        legal = legal,
                        selected = selected,
                        cardWidth = (cell * 1.4f).coerceIn(40.dp, 84.dp),
                        onSelect = { card -> selected = if (selected == card) null else card },
                        onExchange = { controller.submit(it); selected = null },
                    )
                    if (controller.finished.collectAsState().value) {
                        PrimaryAction("Back to the menu") { onExit() }
                    }
                }
            }

            if (wide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    board()
                    Box(modifier = Modifier.weight(1f)) { side() }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    board()
                    side()
                }
            }
        }
    }
}

@Composable
private fun BoardGrid(
    state: SequenceState,
    cellSize: Dp,
    highlighted: Set<Int>,
    onCellTap: (Int) -> Unit,
) {
    Column(modifier = Modifier.background(FeltGreenDark)) {
        for (row in 0 until BOARD_SIZE) {
            Row {
                for (col in 0 until BOARD_SIZE) {
                    val index = SequenceBoard.cellAt(row, col)
                    BoardCell(
                        card = SequenceBoard.cells[index],
                        team = state.chips[index],
                        locked = state.locked[index],
                        highlighted = index in highlighted,
                        size = cellSize,
                        onClick = { onCellTap(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardCell(
    card: Card?,
    team: Int,
    locked: Boolean,
    highlighted: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .padding(0.5.dp)
            .clip(RoundedCornerShape(size * 0.12f))
            .background(if (card == null) MaterialTheme.colorScheme.primary else Color.White)
            .border(
                // Playable squares are outlined heavily rather than tinted: the
                // outline has to read against a white card, a green corner and
                // a chip sitting on top of it. Scaled off the cell so it stays
                // visible on a small screen without swamping a large one.
                width = if (highlighted) {
                    (size * 0.10f).coerceIn(3.dp, 6.dp)
                } else {
                    0.5.dp
                },
                color = if (highlighted) HighlightAmber else Color(0x33000000),
                shape = RoundedCornerShape(size * 0.12f),
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (card == null) {
            // A free corner, wild for every team.
            Text("★", fontSize = (size.value * 0.45f).sp, color = Color.White)
        } else {
            Text(
                text = card.label,
                fontSize = (size.value * 0.26f).sp,
                color = if (card.suit.isRed) Color(0xFFB3261E) else Color.Black,
            )
        }
        if (team != NO_TEAM) {
            Box(
                modifier = Modifier
                    .size(size * 0.62f)
                    .clip(CircleShape)
                    .background(TeamColours.getOrElse(team) { Color.Gray })
                    .border(
                        width = if (locked) 2.dp else 0.dp,
                        color = if (locked) Color.White else Color.Transparent,
                        shape = CircleShape,
                    )
            )
        }
    }
}

@Composable
private fun StatusPanel(state: SequenceState, localSeat: Int) {
    val myTeam = state.teamOf(localSeat)
    Column {
        Text("You are team $myTeam", fontWeight = FontWeight.Bold)
        state.sequencesByTeam.forEachIndexed { team, count ->
            Text(
                text = "Team $team: $count / ${state.options.sequencesToWin} sequences",
                style = MaterialTheme.typography.bodySmall,
                color = TeamColours.getOrElse(team) { Color.Gray },
            )
        }
        state.winner?.let {
            Text("Team $it wins!", fontWeight = FontWeight.Bold)
        } ?: Text(
            text = if (state.turn == localSeat) "Your turn" else "Seat ${state.turn} is playing…",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun HandStrip(
    state: SequenceState,
    seat: Int,
    legal: List<SequenceMove>,
    selected: Card?,
    cardWidth: Dp,
    onSelect: (Card) -> Unit,
    onExchange: (ExchangeDeadCard) -> Unit,
) {
    val hand = state.hands.getOrNull(seat).orEmpty()
    val playable = legal.mapNotNull {
        when (it) {
            is PlaceChip -> it.card
            is RemoveChip -> it.card
            is ExchangeDeadCard -> it.card
        }
    }.toSet()
    val exchanges = legal.filterIsInstance<ExchangeDeadCard>().associateBy { it.card }

    Column {
        Text("Your hand", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            hand.forEach { card ->
                org.prolibertate.games.ui.common.PlayingCardView(
                    card = card,
                    width = cardWidth,
                    selected = card == selected,
                    enabled = card in playable,
                    // A jack's power comes from its artwork, which these cards
                    // do not have, so spell it out on the face instead.
                    caption = jackCaption(card),
                    onClick = {
                        val dead = exchanges[card]
                        if (dead != null) onExchange(dead) else onSelect(card)
                    },
                )
            }
        }
        selected?.let { card ->
            Text(
                text = when {
                    isTwoEyedJack(card) ->
                        "${card.label} is wild — tap any empty square."

                    isOneEyedJack(card) ->
                        "${card.label} removes a chip — tap an opponent's, " +
                            "as long as it isn't part of a finished sequence."

                    else -> "Tap a highlighted square to play ${card.label}."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
