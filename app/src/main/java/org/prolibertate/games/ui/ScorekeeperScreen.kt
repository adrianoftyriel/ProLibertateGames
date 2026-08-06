package org.prolibertate.games.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.prolibertate.games.score.ScorePlayer
import org.prolibertate.games.score.ScoreSheet
import org.prolibertate.games.score.ScorekeeperRepository
import kotlin.math.roundToInt

/**
 * A pencil for games the app does not play.
 *
 * Name the people at the table, drag them into the order they are sitting in,
 * then write down what each of them scored every round. The running tally sits
 * at the foot of the screen where it can always be seen, because that is the
 * one number anybody actually looks up.
 */
@Composable
fun ScorekeeperScreen(
    repository: ScorekeeperRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Read once, then held here and written through. Reading the sheet back out
    // of storage on every change would put a DataStore round trip between a
    // keystroke and the letter appearing, which is how a name field loses
    // characters.
    var loaded by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(ScoreSheet()) }
    LaunchedEffect(Unit) {
        sheet = repository.sheet.first()
        loaded = true
    }

    val update: (ScoreSheet) -> Unit = { next ->
        sheet = next
        scope.launch { repository.save(next) }
    }

    var editingPlayers by remember { mutableStateOf(false) }
    var confirmNewGame by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = "Scorekeeper",
        onBack = onBack,
        actions = {
            if (sheet.started) {
                TextButton(onClick = { confirmNewGame = true }) { Text("New game") }
            }
        },
    ) { modifier ->
        // Nothing is drawn until the stored sheet has been read, so a game in
        // progress does not flash the "how many are playing" pane first.
        if (!loaded) {
            Box(modifier = modifier)
        } else if (!sheet.started) {
            SetupPane(modifier = modifier) { count ->
                update(ScoreSheet.of(count))
                // Straight into naming them: a fresh sheet is a row of blanks.
                editingPlayers = true
            }
        } else {
            Scoreboard(
                modifier = modifier,
                sheet = sheet,
                editingPlayers = editingPlayers,
                onEditPlayers = { editingPlayers = it },
                onChange = update,
            )
        }
    }

    if (confirmNewGame) {
        AlertDialog(
            onDismissRequest = { confirmNewGame = false },
            title = { Text("Start a new game?") },
            text = { Text("The players and every round scored so far are rubbed out.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmNewGame = false
                    editingPlayers = false
                    update(ScoreSheet())
                }) { Text("Rub it out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewGame = false }) { Text("Keep scoring") }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Setting the table
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupPane(modifier: Modifier, onStart: (Int) -> Unit) {
    var count by remember { mutableStateOf(ScoreSheet.DEFAULT_PLAYERS) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Keep score for a game the app does not deal — darts, cribbage, " +
                "whatever is on the table. Add or take away points each round and the " +
                "tally is kept for you.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("How many are playing?", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            (ScoreSheet.MIN_PLAYERS..ScoreSheet.MAX_PLAYERS).forEach { value ->
                FilterChip(
                    selected = value == count,
                    onClick = { count = value },
                    label = { Text("$value") },
                )
            }
        }
        Text(
            text = "Names come next, and players can be added, removed or dragged into " +
                "a different order at any point without losing anybody's score.",
            style = MaterialTheme.typography.bodySmall,
        )
        PrimaryAction(text = "Start scoring") { onStart(count) }
    }
}

// ---------------------------------------------------------------------------
// The sheet proper
// ---------------------------------------------------------------------------

@Composable
private fun Scoreboard(
    modifier: Modifier,
    sheet: ScoreSheet,
    editingPlayers: Boolean,
    onEditPlayers: (Boolean) -> Unit,
    onChange: (ScoreSheet) -> Unit,
) {
    // What is being typed into the round that has not been written down yet,
    // keyed by player id so it survives the players being reordered.
    val pending = remember { mutableStateMapOf<Int, String>() }
    var editingRound by remember { mutableStateOf(-1) }

    Column(modifier = modifier) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(
                title = if (editingPlayers) "Players" else "Round ${sheet.rounds.size + 1}",
                actionLabel = if (editingPlayers) "Done" else "Players",
                onAction = { onEditPlayers(!editingPlayers) },
            )

            if (editingPlayers) {
                PlayerEditor(sheet = sheet, onChange = onChange)
            } else {
                RoundEntry(
                    sheet = sheet,
                    amounts = pending,
                    onRecord = {
                        onChange(sheet.withRound(amountsAsPoints(pending)))
                        pending.clear()
                    },
                )
                if (sheet.rounds.isNotEmpty()) {
                    Text(
                        text = "Rounds so far",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // Newest first: the round just written down is the one most
                    // likely to need correcting.
                    for (index in sheet.rounds.indices.reversed()) {
                        RoundRow(
                            sheet = sheet,
                            index = index,
                            onClick = { editingRound = index },
                        )
                    }
                }
            }
        }

        TotalsBar(sheet = sheet)
    }

    if (editingRound in sheet.rounds.indices) {
        RoundEditorDialog(
            sheet = sheet,
            index = editingRound,
            onDismiss = { editingRound = -1 },
            onSave = { amounts ->
                onChange(sheet.withRoundAt(editingRound, amounts))
                editingRound = -1
            },
            onDelete = {
                onChange(sheet.withoutRound(editingRound))
                editingRound = -1
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

// ---------------------------------------------------------------------------
// Players: naming, adding, removing, and dragging into order
// ---------------------------------------------------------------------------

/**
 * One row per player, dragged by the handle on the left.
 *
 * Every row is exactly [PLAYER_ROW_HEIGHT] tall and they are stacked with no
 * gap, so a drag of one row height is a move of exactly one place and the
 * arithmetic needs no measurement of individual items.
 */
@Composable
private fun PlayerEditor(sheet: ScoreSheet, onChange: (ScoreSheet) -> Unit) {
    val rowHeightPx = with(LocalDensity.current) { PLAYER_ROW_HEIGHT.toPx() }

    // Read inside the gesture handlers, which outlive the composition that
    // created them: without this a drag would be applied to whatever the sheet
    // looked like when the row was first drawn, throwing away anything typed
    // since.
    val currentSheet by rememberUpdatedState(sheet)
    val commit by rememberUpdatedState(onChange)

    // The sheet is deliberately left alone until the finger lifts. Rows move on
    // screen as the drag passes them, but the positions the gesture is working
    // from cannot shift underneath it.
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    fun landingIndex(): Int {
        val last = currentSheet.players.lastIndex
        if (draggingIndex < 0 || last < 0) return -1
        val moved = draggingIndex + (dragOffset / rowHeightPx).roundToInt()
        return moved.coerceIn(0, last)
    }

    val target = landingIndex()

    Column(modifier = Modifier.fillMaxWidth()) {
        sheet.players.forEachIndexed { index, player ->
            val dragging = index == draggingIndex
            // The dragged row follows the finger; the rows it has passed step
            // aside by one place to show where it would land.
            val shift = when {
                draggingIndex < 0 -> 0f
                dragging -> dragOffset
                draggingIndex < target && index in (draggingIndex + 1)..target -> -rowHeightPx
                draggingIndex > target && index in target until draggingIndex -> rowHeightPx
                else -> 0f
            }

            PlayerRow(
                sheet = sheet,
                player = player,
                dragging = dragging,
                shift = shift,
                onName = { commit(currentSheet.renamed(player.id, it)) },
                onRemove = { commit(currentSheet.withPlayerRemoved(player.id)) },
                onDragStart = {
                    draggingIndex = currentSheet.players.indexOfFirst { it.id == player.id }
                    dragOffset = 0f
                },
                onDrag = { dy -> dragOffset += dy },
                onDragEnd = {
                    val from = draggingIndex
                    val to = landingIndex()
                    draggingIndex = -1
                    dragOffset = 0f
                    if (from >= 0 && to >= 0) commit(currentSheet.moved(from, to))
                },
                onDragCancel = {
                    draggingIndex = -1
                    dragOffset = 0f
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onChange(sheet.withPlayerAdded()) },
                enabled = sheet.players.size < ScoreSheet.MAX_PLAYERS,
            ) { Text("Add a player") }
        }
        Text(
            text = "Drag the ≡ handle to change the order. Removing a player takes " +
                "their score with them.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PlayerRow(
    sheet: ScoreSheet,
    player: ScorePlayer,
    dragging: Boolean,
    shift: Float,
    onName: (String) -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PLAYER_ROW_HEIGHT)
            // Above its neighbours while it is being dragged, so it passes over
            // them rather than under.
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = shift },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .fillMaxHeight()
                .pointerInput(player.id) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragCancel() },
                        onDrag = { change, delta ->
                            // Consumed here so the surrounding scroll does not
                            // also take the gesture and run away with the page.
                            change.consume()
                            onDrag(delta.y)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "≡",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = player.name,
            onValueChange = onName,
            placeholder = { Text(sheet.displayName(player)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )

        TextButton(
            onClick = onRemove,
            enabled = sheet.players.size > ScoreSheet.MIN_PLAYERS,
        ) { Text("✕") }
    }
}

// ---------------------------------------------------------------------------
// Scoring a round
// ---------------------------------------------------------------------------

@Composable
private fun RoundEntry(
    sheet: ScoreSheet,
    amounts: SnapshotStateMap<Int, String>,
    onRecord: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "What did each player score? Take points away with the minus, or type " +
                "a negative number.",
            style = MaterialTheme.typography.bodySmall,
        )
        sheet.players.forEach { player ->
            AmountRow(name = sheet.displayName(player), id = player.id, amounts = amounts)
        }
        PrimaryAction(
            text = "Record round ${sheet.rounds.size + 1}",
            // A round where nobody scored anything is not worth a row.
            enabled = amountsAsPoints(amounts).isNotEmpty(),
            onClick = onRecord,
        )
    }
}

/** Name, a minus, a number, a plus. The same row scores a round and edits one. */
@Composable
private fun AmountRow(name: String, id: Int, amounts: SnapshotStateMap<Int, String>) {
    val typed = amounts[id].orEmpty()

    fun step(by: Int) {
        val next = (typed.toIntOrNull() ?: 0) + by
        amounts[id] = if (next == 0) "" else next.toString()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { step(-1) }) { Text("−") }
        OutlinedTextField(
            value = typed,
            onValueChange = { amounts[id] = sanitiseAmount(it) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text("0", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
            modifier = Modifier.width(96.dp),
        )
        TextButton(onClick = { step(1) }) { Text("+") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RoundRow(sheet: ScoreSheet, index: Int, onClick: () -> Unit) {
    val round = sheet.rounds[index]
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "Round ${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                sheet.players.forEach { player ->
                    Text(
                        text = "${sheet.displayName(player)} ${signed(round.delta(player.id))}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundEditorDialog(
    sheet: ScoreSheet,
    index: Int,
    onDismiss: () -> Unit,
    onSave: (Map<Int, Int>) -> Unit,
    onDelete: () -> Unit,
) {
    val round = sheet.rounds[index]
    val amounts = remember(index) {
        mutableStateMapOf<Int, String>().apply {
            sheet.players.forEach { player ->
                val delta = round.delta(player.id)
                if (delta != 0) put(player.id, delta.toString())
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Round ${index + 1}") },
        text = {
            Column(
                // Twelve players will not fit on a phone otherwise.
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                sheet.players.forEach { player ->
                    AmountRow(
                        name = sheet.displayName(player),
                        id = player.id,
                        amounts = amounts,
                    )
                }
                TextButton(onClick = onDelete) { Text("Delete this round") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(amountsAsPoints(amounts)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------
// The tally
// ---------------------------------------------------------------------------

@Composable
private fun TotalsBar(sheet: ScoreSheet) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = when (sheet.rounds.size) {
                    0 -> "No rounds scored yet"
                    1 -> "After 1 round"
                    else -> "After ${sheet.rounds.size} rounds"
                },
                style = MaterialTheme.typography.labelSmall,
            )
            // Scrolls sideways rather than squeezing: a twelve-handed game has
            // more columns than a phone has width.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                sheet.players.forEach { player ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = sheet.displayName(player),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                        Text(
                            text = sheet.total(player.id).toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

private val PLAYER_ROW_HEIGHT = 64.dp

/** Keeps a points field to a number, optionally negative, as it is typed. */
private fun sanitiseAmount(raw: String): String {
    val negative = raw.startsWith("-")
    val digits = raw.filter { it.isDigit() }.take(5)
    return if (negative) "-$digits" else digits
}

/** The typed fields as points. Blanks, a lone minus and zeroes all drop out. */
private fun amountsAsPoints(amounts: Map<Int, String>): Map<Int, Int> =
    amounts.mapNotNull { (id, text) -> text.toIntOrNull()?.takeIf { it != 0 }?.let { id to it } }
        .toMap()

private fun signed(points: Int): String = if (points > 0) "+$points" else points.toString()
