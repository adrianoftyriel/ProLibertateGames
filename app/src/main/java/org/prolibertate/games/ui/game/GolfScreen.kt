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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.golf.DiscardAndFlip
import org.prolibertate.games.game.golf.DrawFromDiscard
import org.prolibertate.games.game.golf.DrawFromStock
import org.prolibertate.games.game.golf.GolfMove
import org.prolibertate.games.game.golf.GolfPhase
import org.prolibertate.games.game.golf.GolfState
import org.prolibertate.games.game.golf.ReplaceCard
import org.prolibertate.games.game.golf.golfValue
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.PrimaryAction
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.CardBackView
import org.prolibertate.games.ui.common.PlayingCardView
import org.prolibertate.games.ui.theme.FeltGreenDark

/** What tapping one of your own cards will do once you're holding a card. */
private enum class PlaceMode(val label: String) {
    SWAP("Swap it in"),
    THROW("Throw it and turn one over"),
}

/**
 * The Golf table.
 *
 * The grid is the game, so it gets the space: your own cards are large and
 * tappable, everyone else's are small. Face-down cards are drawn as backs —
 * including your own, which you genuinely have not seen.
 */
@Composable
fun GolfScreen(
    controller: MatchController<GolfState, GolfMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    var mode by remember { mutableStateOf(PlaceMode.SWAP) }

    val current = state
    if (current == null) {
        ScreenScaffold(title = "Golf", onBack = onExit) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Dealing…") }
        }
        return
    }

    ScreenScaffold(title = "Golf", onBack = onExit) { modifier ->
        BoxWithConstraints(modifier = modifier) {
            val shortest = minOf(maxWidth, maxHeight)
            val ownCard = (shortest * 0.16f).coerceIn(40.dp, 84.dp)
            val otherCard = (shortest * 0.07f).coerceIn(20.dp, 40.dp)

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScoreLine(current, localSeat)

                Box(
                    modifier = Modifier.fillMaxWidth().background(FeltGreenDark).padding(6.dp),
                ) {
                    StockAndDiscard(current, localSeat, legal, ownCard) { controller.submit(it) }
                }

                OpponentGrids(current, localSeat, otherCard)

                if (current.phase == GolfPhase.PLACE && legal.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlaceMode.entries.forEach { option ->
                            val enabled = option == PlaceMode.SWAP ||
                                legal.any { it is DiscardAndFlip }
                            FilterChip(
                                selected = mode == option,
                                enabled = enabled,
                                onClick = { mode = option },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }

                when (current.phase) {
                    GolfPhase.HOLE_OVER -> Text(
                        text = "Hole ${current.hole + 1}: " + current.holeScores
                            .mapIndexed { seat, s -> "seat $seat $s" }.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    GolfPhase.GAME_OVER -> {
                        Text("Game over — lowest score wins.", fontWeight = FontWeight.Bold)
                        PrimaryAction(text = "Back to the menu") { onExit() }
                    }

                    else -> Unit
                }

                OwnGrid(
                    state = current,
                    localSeat = localSeat,
                    cardWidth = ownCard,
                    legal = legal,
                    mode = mode,
                    onMove = { controller.submit(it); mode = PlaceMode.SWAP },
                )
            }
        }
    }
}

@Composable
private fun ScoreLine(state: GolfState, localSeat: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Hole ${state.hole + 1} of ${state.options.holes}", fontWeight = FontWeight.Bold)
            Text(
                text = "Your total: ${state.scores[localSeat]} (lowest wins)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.closedBy?.let {
            Text(
                text = if (it == localSeat) "You closed it out" else "Seat $it is out",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StockAndDiscard(
    state: GolfState,
    localSeat: Int,
    legal: List<GolfMove>,
    cardWidth: Dp,
    onMove: (GolfMove) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Stock", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary)
            CardBackView(width = cardWidth)
            if (legal.contains(DrawFromStock)) {
                Button(onClick = { onMove(DrawFromStock) }) { Text("Draw") }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Discard", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary)
            state.discard.lastOrNull()?.let { PlayingCardView(card = it, width = cardWidth) }
            if (legal.contains(DrawFromDiscard)) {
                Button(onClick = { onMove(DrawFromDiscard) }) { Text("Take") }
            }
        }

        state.drawn?.let { drawn ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Holding", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary)
                PlayingCardView(card = drawn, width = cardWidth, selected = true)
                Text(
                    text = "worth ${golfValue(drawn)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        if (state.phase == GolfPhase.DRAW && state.turn != localSeat) {
            Text(
                text = "Seat ${state.turn} is playing…",
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun OwnGrid(
    state: GolfState,
    localSeat: Int,
    cardWidth: Dp,
    legal: List<GolfMove>,
    mode: PlaceMode,
    onMove: (GolfMove) -> Unit,
) {
    val options = state.options
    val replacements = legal.filterIsInstance<ReplaceCard>().map { it.index }.toSet()
    val flips = legal.filterIsInstance<DiscardAndFlip>().map { it.index }.toSet()

    Column {
        Text(
            text = when {
                state.phase == GolfPhase.PLACE && mode == PlaceMode.SWAP ->
                    "Tap a card to swap it out"

                state.phase == GolfPhase.PLACE ->
                    "Tap a face-down card to turn it over"

                else -> "Your cards"
            },
            style = MaterialTheme.typography.labelLarge,
        )
        for (row in 0 until options.rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until options.cols) {
                    val index = row * options.cols + col
                    val tappable = when (mode) {
                        PlaceMode.SWAP -> index in replacements
                        PlaceMode.THROW -> index in flips
                    }
                    GridCard(
                        card = state.grids[localSeat].getOrNull(index),
                        faceUp = state.revealed[localSeat].getOrElse(index) { false },
                        width = cardWidth,
                        tappable = tappable,
                        onClick = {
                            when (mode) {
                                PlaceMode.SWAP -> onMove(ReplaceCard(index))
                                PlaceMode.THROW -> onMove(DiscardAndFlip(index))
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GridCard(
    card: org.prolibertate.games.game.cards.Card?,
    faceUp: Boolean,
    width: Dp,
    tappable: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(width * 0.12f)
    Box(
        modifier = Modifier
            .clip(shape)
            .border(
                width = if (tappable) 3.dp else 0.dp,
                color = if (tappable) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
                shape = shape,
            )
            .then(if (tappable) Modifier.clickable { onClick() } else Modifier)
            .padding(2.dp),
    ) {
        if (faceUp && card != null) {
            PlayingCardView(card = card, width = width)
        } else {
            CardBackView(width = width)
        }
    }
}

@Composable
private fun OpponentGrids(state: GolfState, localSeat: Int, cardWidth: Dp) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        (0 until state.options.playerCount)
            .filter { it != localSeat }
            .forEach { seat ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Seat $seat",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (seat == state.turn) FontWeight.Bold else FontWeight.Normal,
                    )
                    for (row in 0 until state.options.rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (col in 0 until state.options.cols) {
                                val index = row * state.options.cols + col
                                val faceUp = state.revealed[seat].getOrElse(index) { false }
                                if (faceUp) {
                                    PlayingCardView(
                                        card = state.grids[seat][index],
                                        width = cardWidth,
                                    )
                                } else {
                                    CardBackView(width = cardWidth)
                                }
                            }
                        }
                    }
                }
            }
    }
}
