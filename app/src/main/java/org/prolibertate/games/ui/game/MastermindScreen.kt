package org.prolibertate.games.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.mastermind.Feedback
import org.prolibertate.games.game.mastermind.Guess
import org.prolibertate.games.game.mastermind.MastermindMove
import org.prolibertate.games.game.mastermind.MastermindPhase
import org.prolibertate.games.game.mastermind.MastermindState
import org.prolibertate.games.game.mastermind.other
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.PrimaryAction
import org.prolibertate.games.ui.ScreenScaffold

/**
 * The eight code pegs.
 *
 * Kept well apart in hue *and* in lightness, because a code you cannot read is
 * not a puzzle, it is an eye test — and one player in twelve cannot tell the
 * red from the green.
 */
private val PegColours = listOf(
    Color(0xFFD32F2F),
    Color(0xFF1976D2),
    Color(0xFFFBC02D),
    Color(0xFF388E3C),
    Color(0xFFF57C00),
    Color(0xFF7B1FA2),
    Color(0xFF00ACC1),
    Color(0xFFFAFAFA),
)

private val EmptySlot = Color(0x33808080)
private val ExactPeg = Color(0xFF212121)
private val MisplacedPeg = Color(0xFFFFFFFF)

/**
 * Mastermind, played as a duel: both players are guarding a code and breaking
 * one, a guess each in turn.
 *
 * Tap the colours to build a guess and press the button to commit it. The
 * answer to each guess is the little pegs beside it — a black one for a colour
 * in the right place, a white one for a colour in the wrong place, and no
 * indication whatever of which is which.
 */
@Composable
fun MastermindScreen(
    controller: MatchController<MastermindState, MastermindMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val abandoned by controller.abandoned.collectAsState()

    val building = remember { mutableStateListOf<Int>() }
    var confirmingEnd by remember { mutableStateOf(false) }

    val endGameAction: @Composable () -> Unit = {
        TextButton(onClick = { confirmingEnd = true }) { Text("End game") }
    }

    val current = state
    if (current == null) {
        ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Setting up…") }
            abandoned?.let { LeftDialog(it, onExit, controller::dismissAbandoned) }
            if (confirmingEnd) EndDialog(onExit) { confirmingEnd = false }
        }
        return
    }

    val yourTurn = current.turn == localSeat && current.phase == MastermindPhase.GUESSING

    ScreenScaffold(title = TITLE, onBack = onExit, actions = endGameAction) { modifier ->
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when {
                    current.outcome != null -> current.outcome!!.label
                    yourTurn -> "Your guess — ${current.guessesLeft(localSeat)} left"
                    else -> "Thinking…"
                },
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "A dark peg is a colour in the right place, a light one a colour " +
                    "somewhere else. Which is which is for you to work out.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Your guesses", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            GuessList(current, localSeat)

            if (current.phase == MastermindPhase.GUESSING) {
                Builder(
                    length = current.options.length,
                    colours = current.options.colours,
                    building = building,
                    enabled = yourTurn,
                    onSubmit = {
                        controller.submit(MastermindMove(building.toList()))
                        building.clear()
                    },
                )
            }

            Text(
                text = "Against you",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "${current.guesses[other(localSeat)].size} guesses at your code so far.",
                style = MaterialTheme.typography.bodySmall,
            )
            GuessList(current, other(localSeat))

            // Your own code, which is yours to see all along — you are guarding
            // it, not guessing it.
            if (current.secrets[localSeat].isNotEmpty()) {
                Text(
                    text = "The code you are guarding",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                PegRow(current.secrets[localSeat])
            }
        }

        val over = abandoned
        when {
            over != null -> LeftDialog(over, onExit, controller::dismissAbandoned)
            confirmingEnd -> EndDialog(onExit) { confirmingEnd = false }
            current.phase == MastermindPhase.GAME_OVER -> ResultDialog(current, localSeat, onExit)
        }
    }
}

private const val TITLE = "Mastermind"

@Composable
private fun GuessList(state: MastermindState, seat: Int) {
    val guesses = state.guesses[seat]
    if (guesses.isEmpty()) {
        Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        guesses.forEachIndexed { index, guess ->
            GuessRow(index + 1, guess, state.options.length)
        }
    }
}

@Composable
private fun GuessRow(number: Int, guess: Guess, length: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.size(width = 22.dp, height = 20.dp),
        )
        PegRow(guess.code)
        Box(modifier = Modifier.padding(start = 10.dp)) {
            FeedbackPegs(guess.feedback, length)
        }
    }
}

@Composable
private fun PegRow(code: List<Int>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        code.forEach { colour -> Peg(colour) }
    }
}

@Composable
private fun Peg(colour: Int?, size: androidx.compose.ui.unit.Dp = 26.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(colour?.let { PegColours[it % PegColours.size] } ?: EmptySlot)
            .border(1.dp, Color(0x55000000), CircleShape)
    )
}

/**
 * The answer, as pegs rather than as numbers.
 *
 * Deliberately not laid out one-per-code-peg: the pegs say how many, never
 * which, and putting them in a tidy row under the code would invite reading a
 * position into them that is not there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackPegs(feedback: Feedback, length: Int) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        maxItemsInEachRow = (length + 1) / 2,
    ) {
        repeat(feedback.exact) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(ExactPeg)
                    .border(1.dp, Color(0x55000000), CircleShape)
            )
        }
        repeat(feedback.misplaced) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MisplacedPeg)
                    .border(1.dp, Color(0x55000000), CircleShape)
            )
        }
    }
}

/** The guess being built: tap a colour to add it, tap a peg to take it back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Builder(
    length: Int,
    colours: Int,
    building: MutableList<Int>,
    enabled: Boolean,
    onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(length) { slot ->
                Box(
                    modifier = Modifier.clickable(enabled = enabled && slot < building.size) {
                        // Taking a peg back removes it and everything after it,
                        // which is what a physical board does when you lift one
                        // out of the middle of a row you are still filling.
                        while (building.size > slot) building.removeAt(building.size - 1)
                    }
                ) {
                    Peg(building.getOrNull(slot), size = 32.dp)
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(colours) { colour ->
                Box(
                    modifier = Modifier.clickable(enabled = enabled && building.size < length) {
                        building.add(colour)
                    }
                ) {
                    Peg(colour, size = 34.dp)
                }
            }
        }

        PrimaryAction(
            text = "Guess",
            enabled = enabled && building.size == length,
            onClick = onSubmit,
        )
    }
}

@Composable
private fun EndDialog(onEnd: () -> Unit, onKeepPlaying: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepPlaying,
        title = { Text("End this game?") },
        text = { Text("The game ends here. Anyone else at the table is told you have left.") },
        confirmButton = { TextButton(onClick = onEnd) { Text("End the game") } },
        dismissButton = { TextButton(onClick = onKeepPlaying) { Text("Keep playing") } },
    )
}

@Composable
private fun LeftDialog(notice: String, onExit: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("The game has ended") },
        text = { Text("$notice There are no more guesses to make.") },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = onStay) { Text("Look at the board") } },
    )
}

@Composable
private fun ResultDialog(state: MastermindState, localSeat: Int, onExit: () -> Unit) {
    var dismissed by remember(state.outcome) { mutableStateOf(false) }
    if (dismissed) return

    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Game over") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.outcome?.label.orEmpty(), fontWeight = FontWeight.Bold)
                // Both codes are shown now: there is nothing left to protect,
                // and everyone wants to see what they were up against.
                Text("The code you were breaking", style = MaterialTheme.typography.bodySmall)
                PegRow(state.secrets[other(localSeat)])
                state.brokeIn(localSeat)?.let {
                    Text("You broke it in $it.", style = MaterialTheme.typography.bodySmall)
                }
                state.brokeIn(other(localSeat))?.let {
                    Text("They broke yours in $it.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Look at the board") } },
    )
}
