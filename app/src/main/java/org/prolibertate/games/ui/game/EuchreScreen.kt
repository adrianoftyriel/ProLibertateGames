package org.prolibertate.games.ui.game

import androidx.compose.foundation.background
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.euchre.CallTrump
import org.prolibertate.games.game.euchre.Discard
import org.prolibertate.games.game.euchre.EuchreMove
import org.prolibertate.games.game.euchre.EuchrePhase
import org.prolibertate.games.game.euchre.EuchreState
import org.prolibertate.games.game.euchre.OrderUp
import org.prolibertate.games.game.euchre.Pass
import org.prolibertate.games.game.euchre.PlayCard
import org.prolibertate.games.game.euchre.teamOf
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.PrimaryAction
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.common.CardBackView
import org.prolibertate.games.ui.common.PlayingCardView
import org.prolibertate.games.ui.theme.FeltGreenDark

/**
 * The Euchre table.
 *
 * Layout is driven by the space available rather than by orientation checks:
 * card width is a fraction of the narrower dimension, so the same code lays out
 * sensibly on a phone held either way and on a tablet.
 */
@Composable
fun EuchreScreen(
    controller: MatchController<EuchreState, EuchreMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val notice by controller.notice.collectAsState()

    val current = state
    if (current == null) {
        ScreenScaffold(title = "Euchre", onBack = onExit) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Dealing…") }
        }
        return
    }

    ScreenScaffold(title = "Euchre", onBack = onExit) { modifier ->
        BoxWithConstraints(modifier = modifier) {
            val compact = maxWidth < 600.dp
            val cardWidth = (minOf(maxWidth, maxHeight) * if (compact) 0.17f else 0.12f)
                .coerceIn(48.dp, 104.dp)

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScoreBar(current, localSeat)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(FeltGreenDark),
                    contentAlignment = Alignment.Center,
                ) {
                    TrickArea(current, cardWidth)
                }

                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                when (current.phase) {
                    EuchrePhase.BID_ROUND_1,
                    EuchrePhase.BID_ROUND_2,
                    -> BiddingControls(current, legal) { controller.submit(it) }

                    EuchrePhase.HAND_OVER -> Text(
                        text = current.log.lastOrNull().orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                    )

                    EuchrePhase.GAME_OVER -> {
                        Text("Game over.", fontWeight = FontWeight.Bold)
                        PrimaryAction(text = "Back to the menu") { onExit() }
                    }

                    else -> Unit
                }

                HandRow(
                    state = current,
                    seat = localSeat,
                    legal = legal,
                    cardWidth = cardWidth,
                    onPlay = { controller.submit(it) },
                )
            }
        }
    }
}

@Composable
private fun ScoreBar(state: EuchreState, localSeat: Int) {
    val myTeam = teamOf(localSeat)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Us ${state.scores[myTeam]} — Them ${state.scores[1 - myTeam]}",
                fontWeight = FontWeight.Bold)
            val tricks = state.tricksWon.filterIndexed { seat, _ -> teamOf(seat) == myTeam }.sum()
            Text("Tricks this hand: $tricks", style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = state.trump?.let { "Trump ${it.symbol}" } ?: "Bidding",
                fontWeight = FontWeight.Bold,
            )
            state.aloneSeat?.let {
                Text("Seat $it is alone", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TrickArea(state: EuchreState, cardWidth: androidx.compose.ui.unit.Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        state.upCard?.let { up ->
            Text("Turned up", color = MaterialTheme.colorScheme.onPrimary)
            PlayingCardView(card = up, width = cardWidth)
        }
        if (state.trick.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.trick.forEach { played ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Seat ${played.seat}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        PlayingCardView(card = played.card, width = cardWidth)
                    }
                }
            }
        } else if (state.upCard == null && state.phase == EuchrePhase.PLAYING) {
            Text("Lead a card", color = MaterialTheme.colorScheme.onPrimary)
        }

        // Opponents' hands are only ever card counts on this device.
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.handCounts.forEachIndexed { seat, count ->
                if (seat != state.turn) return@forEachIndexed
                repeat(count.coerceAtMost(5)) {
                    CardBackView(width = cardWidth * 0.4f)
                }
            }
        }
    }
}

@Composable
private fun BiddingControls(
    state: EuchreState,
    legal: List<EuchreMove>,
    onMove: (EuchreMove) -> Unit,
) {
    if (legal.isEmpty()) {
        Text("Waiting for seat ${state.turn}…")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (state.phase == EuchrePhase.BID_ROUND_1) {
                "Order up ${state.upCard?.label.orEmpty()}?"
            } else {
                "Name a suit"
            },
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            legal.forEach { move ->
                val label = when (move) {
                    is Pass -> "Pass"
                    is OrderUp -> if (move.alone) "Order up alone" else "Order up"
                    is CallTrump -> {
                        (if (move.alone) "Alone " else "") + move.suit.symbol
                    }

                    is Discard -> "Discard ${move.card.label}"
                    is PlayCard -> move.card.label
                }
                if (move is Pass) {
                    OutlinedButton(onClick = { onMove(move) }) { Text(label) }
                } else {
                    Button(onClick = { onMove(move) }) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun HandRow(
    state: EuchreState,
    seat: Int,
    legal: List<EuchreMove>,
    cardWidth: androidx.compose.ui.unit.Dp,
    onPlay: (EuchreMove) -> Unit,
) {
    val hand = state.hands.getOrNull(seat).orEmpty()
    val playable = legal.filterIsInstance<PlayCard>().map { it.card }.toSet()
    val discardable = legal.filterIsInstance<Discard>().map { it.card }.toSet()

    Column {
        Text(
            text = when {
                discardable.isNotEmpty() -> "Discard a card"
                playable.isNotEmpty() -> "Your turn"
                else -> "Your hand"
            },
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (hand.isEmpty()) {
                Box(modifier = Modifier.size(cardWidth)) {
                    Text("—", style = MaterialTheme.typography.bodySmall)
                }
            }
            hand.forEach { card ->
                val canPlay = card in playable
                val canDiscard = card in discardable
                PlayingCardView(
                    card = card,
                    width = cardWidth,
                    enabled = canPlay || canDiscard,
                    onClick = {
                        when {
                            canDiscard -> onPlay(Discard(card))
                            canPlay -> onPlay(PlayCard(card))
                        }
                    },
                )
            }
        }
    }
}
