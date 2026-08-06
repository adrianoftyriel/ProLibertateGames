package org.prolibertate.games.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.GameDescriptor
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig
import org.prolibertate.games.game.euchre.EuchreOptions
import org.prolibertate.games.game.golf.GolfOptions
import org.prolibertate.games.game.president.PresidentOptions
import org.prolibertate.games.game.sequence.SequenceOptions

private val setupJson = Json { encodeDefaults = true }

/**
 * Choose who is playing and which house rules apply.
 *
 * Rule variations are per game and live here rather than in a global settings
 * screen, because they change what the engine does rather than how it looks.
 */
@Composable
fun GameSetupScreen(
    descriptor: GameDescriptor,
    playerName: String,
    onBack: () -> Unit,
    onPlayOffline: (TableConfig) -> Unit,
    onHostOnline: (String) -> Unit,
) {
    var euchre by remember { mutableStateOf(EuchreOptions()) }
    var sequence by remember { mutableStateOf(SequenceOptions()) }
    var president by remember { mutableStateOf(PresidentOptions()) }
    var golf by remember { mutableStateOf(GolfOptions()) }

    val optionsJson = when (descriptor.id) {
        GameCatalog.EUCHRE -> setupJson.encodeToString(euchre)
        GameCatalog.SEQUENCE -> setupJson.encodeToString(sequence)
        GameCatalog.PRESIDENT -> setupJson.encodeToString(president)
        GameCatalog.GOLF -> setupJson.encodeToString(golf)
        else -> "{}"
    }
    val seatCount = when (descriptor.id) {
        GameCatalog.EUCHRE -> 4
        GameCatalog.SEQUENCE -> sequence.playerCount
        GameCatalog.PRESIDENT -> president.playerCount
        GameCatalog.GOLF -> golf.playerCount
        else -> descriptor.minPlayers
    }

    ScreenScaffold(title = descriptor.title, onBack = onBack) { modifier ->
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(descriptor.blurb, style = MaterialTheme.typography.bodyMedium)

            Divider()
            Text("House rules", fontWeight = FontWeight.Bold)

            when (descriptor.id) {
                GameCatalog.EUCHRE -> EuchreOptionsEditor(euchre) { euchre = it }
                GameCatalog.SEQUENCE -> SequenceOptionsEditor(sequence) { sequence = it }
                GameCatalog.PRESIDENT -> PresidentOptionsEditor(president) { president = it }
                GameCatalog.GOLF -> GolfOptionsEditor(golf) { golf = it }
                else -> Text("No options yet for this game.")
            }

            Divider()
            Text("Opponents", fontWeight = FontWeight.Bold)
            Text(
                text = "Play now and every other seat is taken by the computer. Host a game " +
                    "instead and people can claim those seats as they join — whatever is " +
                    "still empty when you start stays computer-controlled.",
                style = MaterialTheme.typography.bodySmall,
            )

            PrimaryAction(text = "Play against the computer") {
                onPlayOffline(
                    offlineConfig(descriptor.id, optionsJson, seatCount, playerName)
                )
            }
            PrimaryAction(text = "Host a game for others to join") {
                onHostOnline(optionsJson)
            }
        }
    }
}

/** Seat 0 is the local player; the rest start as AI. */
private fun offlineConfig(
    gameId: String,
    optionsJson: String,
    seatCount: Int,
    playerName: String,
): TableConfig = TableConfig(
    gameId = gameId,
    seats = (0 until seatCount).map { seat ->
        PlayerSlot(
            seat = seat,
            name = if (seat == 0) playerName else "Computer $seat",
            kind = if (seat == 0) PlayerKind.HUMAN_LOCAL else PlayerKind.AI,
            team = teamForSeat(gameId, seat),
        )
    },
    optionsJson = optionsJson,
    // Seeded from the clock so consecutive games are not identical.
    seed = System.currentTimeMillis(),
)

fun teamForSeat(gameId: String, seat: Int): Int = when (gameId) {
    // Partners sit opposite each other.
    GameCatalog.EUCHRE, GameCatalog.SEQUENCE -> seat % 2
    // Everyone else plays for themselves.
    else -> seat
}

@Composable
private fun EuchreOptionsEditor(options: EuchreOptions, onChange: (EuchreOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Deck",
            values = listOf(24, 32),
            selected = options.deckSize,
            display = { "$it cards" },
            onSelect = { onChange(options.copy(deckSize = it)) },
        )
        ChipRow(
            label = "Game to",
            values = listOf(10, 11, 15),
            selected = options.pointsToWin,
            display = { "$it points" },
            onSelect = { onChange(options.copy(pointsToWin = it)) },
        )
        ToggleRow(
            title = "Stick the dealer",
            subtitle = "If everyone passes twice, the dealer must name trump",
            checked = options.stickTheDealer,
            onChange = { onChange(options.copy(stickTheDealer = it)) },
        )
        ToggleRow(
            title = "Allow going alone",
            subtitle = "A maker may sit their partner out for a bigger score",
            checked = options.allowGoingAlone,
            onChange = { onChange(options.copy(allowGoingAlone = it)) },
        )
    }
}

@Composable
private fun SequenceOptionsEditor(options: SequenceOptions, onChange: (SequenceOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Teams",
            values = listOf(2, 3),
            selected = options.teamCount,
            display = { "$it teams" },
            onSelect = { teams ->
                // Three teams race to a single sequence; two teams need two.
                onChange(options.copy(teamCount = teams, sequencesToWin = if (teams == 3) 1 else 2))
            },
        )
        ChipRow(
            label = "Players per team",
            values = listOf(1, 2, 3, 4),
            selected = options.playersPerTeam,
            display = { "$it" },
            onSelect = { onChange(options.copy(playersPerTeam = it)) },
        )
        ChipRow(
            label = "Sequences to win",
            values = listOf(1, 2, 3),
            selected = options.sequencesToWin,
            display = { "$it" },
            onSelect = { onChange(options.copy(sequencesToWin = it)) },
        )
        ToggleRow(
            title = "Dead card exchange",
            subtitle = "Swap a card whose squares are both already taken",
            checked = options.deadCardExchange,
            onChange = { onChange(options.copy(deadCardExchange = it)) },
        )
        Text(
            text = "${options.playerCount} players in total.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PresidentOptionsEditor(
    options: PresidentOptions,
    onChange: (PresidentOptions) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Players",
            values = listOf(3, 4, 5, 6, 7),
            selected = options.playerCount,
            display = { "$it" },
            onSelect = { onChange(options.copy(playerCount = it)) },
        )
        ChipRow(
            label = "Rounds",
            values = listOf(3, 5, 7),
            selected = options.roundsToPlay,
            display = { "$it" },
            onSelect = { onChange(options.copy(roundsToPlay = it)) },
        )
        ToggleRow(
            title = "Twos clear the pile",
            subtitle = "A two beats anything and takes the pile down",
            checked = options.twosClear,
            onChange = { onChange(options.copy(twosClear = it)) },
        )
        ToggleRow(
            title = "Four of a kind bombs",
            subtitle = "Four matching cards beat anything, whatever the pile",
            checked = options.fourOfAKindBomb,
            onChange = { onChange(options.copy(fourOfAKindBomb = it)) },
        )
        ToggleRow(
            title = "Card exchange",
            subtitle = "After each round the Scum hands their best cards to the President",
            checked = options.cardExchange,
            onChange = { onChange(options.copy(cardExchange = it)) },
        )
    }
}

@Composable
private fun GolfOptionsEditor(options: GolfOptions, onChange: (GolfOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Players",
            values = listOf(2, 3, 4, 5, 6),
            selected = options.playerCount,
            display = { "$it" },
            onSelect = { onChange(options.copy(playerCount = it)) },
        )
        ChipRow(
            label = "Cards each",
            values = listOf(4, 6, 9),
            selected = options.gridSize,
            display = { "$it" },
            onSelect = { size ->
                // Keep the opening reveals inside the new grid.
                onChange(
                    options.copy(
                        gridSize = size,
                        startingReveals = options.startingReveals.coerceAtMost(size),
                    )
                )
            },
        )
        ChipRow(
            label = "Holes",
            values = listOf(3, 6, 9),
            selected = options.holes,
            display = { "$it" },
            onSelect = { onChange(options.copy(holes = it)) },
        )
        ChipRow(
            label = "Seen at the start",
            values = (0..options.gridSize).toList(),
            selected = options.startingReveals,
            display = { "$it" },
            onSelect = { onChange(options.copy(startingReveals = it)) },
        )
        Text(
            text = "${options.rows} rows of ${options.cols}. Matching columns cancel out" +
                if (options.gridSize == 9) ", and so do matching rows." else ".",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    label: String,
    values: List<T>,
    selected: T,
    display: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        // Wraps rather than running off the edge: some of these rows carry ten
        // chips, and a chip you cannot see is a setting you cannot change.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(display(value)) },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
