package org.prolibertate.games.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.yahtzee.DICE_COUNT
import org.prolibertate.games.game.yahtzee.RollDice
import org.prolibertate.games.game.yahtzee.ScoreIn
import org.prolibertate.games.game.yahtzee.YahtzeeCategory
import org.prolibertate.games.game.yahtzee.YahtzeeMove
import org.prolibertate.games.game.yahtzee.YahtzeeSection
import org.prolibertate.games.game.yahtzee.YahtzeeState
import org.prolibertate.games.game.yahtzee.scoreOf
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.theme.Parchment
import org.prolibertate.games.ui.theme.WallaceGold
import org.prolibertate.games.ui.theme.WallaceRed

private const val TITLE = "Yahtzee"

/**
 * The Yahtzee card.
 *
 * A turn is two decisions and the screen shows them together: which dice to keep
 * before throwing again, and which box to write in when the throwing stops. Each
 * open box shows what the dice on the table would score in it, because that
 * comparison is the whole of the choice — and doing the arithmetic by eye is not
 * what anyone wants from a phone.
 */
@Composable
fun YahtzeeScreen(
    controller: MatchController<YahtzeeState, YahtzeeMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val abandoned by controller.abandoned.collectAsState()

    val kept = remember { mutableStateListOf<Int>() }
    var confirmingEnd by remember { mutableStateOf(false) }

    val endGameAction: @Composable () -> Unit = {
        TextButton(onClick = { confirmingEnd = true }) { Text("End game") }
    }

    val current = state
    if (current == null) {
        ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Setting up…") }
            abandoned?.let { LeftCardDialog(it, onExit, controller::dismissAbandoned) }
            if (confirmingEnd) EndCardDialog(onExit) { confirmingEnd = false }
        }
        return
    }

    val yours = current.turn == localSeat && !current.isOver
    val canRoll = legal.any { it is RollDice }
    val writable = legal.filterIsInstance<ScoreIn>().map { it.category }.toSet()

    ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when {
                    current.isOver -> "Cards full."
                    !yours -> "Seat ${current.turn} is throwing…"
                    !current.hasRolled -> "Your turn — throw the dice."
                    else -> "${current.rollsLeft} throws left, or write it down."
                },
                fontWeight = FontWeight.Bold,
            )

            DiceRow(
                dice = current.dice,
                kept = kept,
                enabled = yours && current.hasRolled && canRoll,
                onToggle = { index ->
                    if (kept.contains(index)) kept.remove(index) else kept.add(index)
                },
            )

            if (yours && canRoll) {
                Button(
                    onClick = {
                        controller.submit(RollDice(kept.toSet()))
                        // What was kept applied to the throw just made; the next
                        // one starts from whatever is on the table now.
                        kept.clear()
                    },
                ) {
                    Text(if (current.hasRolled) "Throw the rest" else "Throw")
                }
            }

            Divider()
            ScoreCard(
                state = current,
                localSeat = localSeat,
                writable = if (yours) writable else emptySet(),
                onWrite = {
                    controller.submit(ScoreIn(it))
                    kept.clear()
                },
            )

            current.log.takeLast(3).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        abandoned?.let { LeftCardDialog(it, onExit, controller::dismissAbandoned) }
        if (confirmingEnd) EndCardDialog(onExit) { confirmingEnd = false }
    }
}

@Composable
private fun DiceRow(
    dice: List<Int>,
    kept: List<Int>,
    enabled: Boolean,
    onToggle: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dice.isEmpty()) {
            repeat(DICE_COUNT) { Die(face = null, held = false, enabled = false) {} }
        } else {
            dice.forEachIndexed { index, face ->
                Die(
                    face = face,
                    held = kept.contains(index),
                    enabled = enabled,
                    onClick = { onToggle(index) },
                )
            }
        }
    }
}

@Composable
private fun Die(face: Int?, held: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(if (face == null) Parchment.copy(alpha = 0.3f) else Parchment, RoundedCornerShape(8.dp))
            .border(
                width = if (held) 3.dp else 1.dp,
                color = if (held) WallaceGold else WallaceRed.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = face?.toString() ?: "·",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = WallaceRed,
        )
    }
}

@Composable
private fun ScoreCard(
    state: YahtzeeState,
    localSeat: Int,
    writable: Set<YahtzeeCategory>,
    onWrite: (YahtzeeCategory) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.weight(2f), style = MaterialTheme.typography.labelSmall)
            state.cards.indices.forEach { seat ->
                Text(
                    text = if (seat == localSeat) "You" else "S$seat",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        YahtzeeSection.entries.forEach { section ->
            YahtzeeCategory.entries.filter { it.section == section }.forEach { category ->
                CardRow(state, localSeat, category, writable, onWrite)
            }
            if (section == YahtzeeSection.UPPER) {
                TotalRow("Bonus at 63", state.cards.map { it.upperBonus })
            }
        }
        Divider()
        TotalRow("Total", state.cards.indices.map { state.totalFor(it) }, bold = true)
    }
}

@Composable
private fun CardRow(
    state: YahtzeeState,
    localSeat: Int,
    category: YahtzeeCategory,
    writable: Set<YahtzeeCategory>,
    onWrite: (YahtzeeCategory) -> Unit,
) {
    val open = writable.contains(category)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = open) { onWrite(category) }
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = category.label,
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (open) FontWeight.Bold else FontWeight.Normal,
        )
        state.cards.forEachIndexed { seat, card ->
            val written = card[category]
            Text(
                text = when {
                    written != null -> "$written"
                    // What this throw would be worth here, which is the only
                    // number the decision actually turns on.
                    seat == localSeat && open -> "(${scoreOf(category, state.dice)})"
                    else -> "—"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (written == null && open) WallaceRed else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TotalRow(label: String, values: List<Int>, bold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
        values.forEach {
            Text(
                text = "$it",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun EndCardDialog(onEnd: () -> Unit, onKeepPlaying: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepPlaying,
        title = { Text("End this game?") },
        text = { Text("The game ends here. Anyone else at the table is told you have left.") },
        confirmButton = { TextButton(onClick = onEnd) { Text("End the game") } },
        dismissButton = { TextButton(onClick = onKeepPlaying) { Text("Keep playing") } },
    )
}

@Composable
private fun LeftCardDialog(notice: String, onExit: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("The game has ended") },
        text = { Text(notice) },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = onStay) { Text("Look at the card") } },
    )
}
