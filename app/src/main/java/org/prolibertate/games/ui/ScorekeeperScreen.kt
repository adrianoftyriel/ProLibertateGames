package org.prolibertate.games.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.prolibertate.games.net.ScorekeeperSession
import org.prolibertate.games.net.ScorekeeperSession.Role
import org.prolibertate.games.score.AddPlayer
import org.prolibertate.games.score.AddRound
import org.prolibertate.games.score.ClearSheet
import org.prolibertate.games.score.DeleteRound
import org.prolibertate.games.score.EditRound
import org.prolibertate.games.score.MovePlayer
import org.prolibertate.games.score.RemovePlayer
import org.prolibertate.games.score.RenamePlayer
import org.prolibertate.games.score.ScorePlayer
import org.prolibertate.games.score.ScoreSheet
import org.prolibertate.games.score.ScorekeeperRepository
import org.prolibertate.games.score.SheetOp
import org.prolibertate.games.score.StartSheet
import kotlin.math.roundToInt

/**
 * A pencil for games the app does not deal, laid out as the paper it replaces:
 * a column per player with their name at the head, a row per round running down
 * the page, and the running total ruled off at the foot.
 *
 * The bottom row is always the one being filled in. Finish it and it is written
 * down, and a fresh empty row opens above the totals for the next round.
 *
 * The sheet can be shared. Sharing makes this device the host of a
 * [ScorekeeperSession]; joining makes it a guest of somebody else's. Either way
 * every change is a [SheetOp] — applied here when the sheet is only this
 * device's, and handed to the session when it is not — so the grid below does
 * not need to know which of the three it is drawing.
 */
@Composable
fun ScorekeeperScreen(
    repository: ScorekeeperRepository,
    session: ScorekeeperSession,
    playerName: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Read once, then held here and written through. Reading the sheet back out
    // of storage on every change would put a DataStore round trip between a
    // keystroke and the digit appearing, which is how a field loses characters.
    var loaded by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf(ScoreSheet()) }
    LaunchedEffect(Unit) {
        local = repository.sheet.first()
        loaded = true
    }

    val shared by session.state.collectAsState()
    val role = shared.role
    var joining by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var confirmNewGame by remember { mutableStateOf(false) }

    // A session lives exactly as long as this screen does: leaving it ends the
    // sharing, and with it the links.
    DisposableEffect(Unit) { onDispose { session.stop() } }

    // What the host shares is the host's own sheet, so it is the one kept. A
    // phone killed mid-game comes back to the sheet as the guests last saw it.
    LaunchedEffect(shared.sheet, role) {
        if (role == Role.HOST) repository.save(shared.sheet)
    }

    // Coming back to the screen after the phone was put down. A guest has missed
    // whatever the host did in the meantime, and may have lost its link while the
    // radio slept, so it asks again — the same thing a game table does.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) session.sync()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val sheet = if (role == Role.NONE) local else shared.sheet

    val change: (SheetOp) -> Unit = { op ->
        if (role == Role.NONE) {
            val next = op.applyTo(local)
            local = next
            scope.launch { repository.save(next) }
        } else {
            session.edit(op)
        }
    }

    // Stops whatever is going on with the network and goes back to this
    // device's own sheet — which, for a host, is the one it has been keeping.
    val leaveSharing: () -> Unit = {
        if (role == Role.HOST) local = shared.sheet
        session.stop()
        joining = false
        showShare = false
    }

    BackHandler(enabled = role == Role.GUEST || joining) { leaveSharing() }

    ScreenScaffold(
        title = when (role) {
            Role.GUEST -> "Scorekeeper · ${shared.hostName}"
            Role.HOST -> "Scorekeeper · sharing"
            Role.NONE -> "Scorekeeper"
        },
        onBack = { if (role == Role.GUEST || joining) leaveSharing() else onBack() },
        actions = {
            when (role) {
                Role.NONE -> if (sheet.started && !joining) {
                    TextButton(onClick = {
                        session.startHosting(playerName, local)
                        showShare = true
                    }) { Text("Share") }
                    TextButton(onClick = { confirmNewGame = true }) { Text("New game") }
                }

                Role.HOST -> {
                    TextButton(onClick = { showShare = true }) {
                        Text(if (shared.guests.isEmpty()) "Sharing" else "Sharing (${shared.guests.size})")
                    }
                    if (sheet.started) {
                        TextButton(onClick = { confirmNewGame = true }) { Text("New game") }
                    }
                }

                // Rubbing out the sheet is the host's to do; a guest can leave
                // and nothing more drastic.
                Role.GUEST -> {
                    TextButton(onClick = { session.sync() }) { Text("Sync") }
                    TextButton(onClick = leaveSharing) { Text("Leave") }
                }
            }
        },
    ) { modifier ->
        // Nothing is drawn until the stored sheet has been read, so a game in
        // progress does not flash the "how many are playing" pane first.
        when {
            !loaded -> Box(modifier = modifier)

            role == Role.GUEST && (shared.awaiting || shared.refused || !sheet.started) ->
                WaitingPane(modifier = modifier, shared = shared, onLeave = leaveSharing)

            role == Role.GUEST -> Column(modifier = modifier) {
                ConnectionBanner(shared = shared, onSync = { session.sync() })
                Sheet(modifier = Modifier.weight(1f), sheet = sheet, onOp = change)
            }

            joining && role == Role.NONE ->
                JoinPane(modifier = modifier, session = session, shared = shared, playerName = playerName)

            !sheet.started -> SetupPane(
                modifier = modifier,
                onStart = { count -> change(StartSheet(count)) },
                onJoin = { joining = true },
            )

            else -> Sheet(modifier = modifier, sheet = sheet, onOp = change)
        }
    }

    if (confirmNewGame) {
        AlertDialog(
            onDismissRequest = { confirmNewGame = false },
            title = { Text("Start a new game?") },
            text = {
                Text(
                    if (role == Role.HOST) {
                        "The players and every round scored so far are rubbed out, " +
                            "for everyone on the sheet."
                    } else {
                        "The players and every round scored so far are rubbed out."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmNewGame = false
                    change(ClearSheet)
                }) { Text("Rub it out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewGame = false }) { Text("Keep scoring") }
            },
        )
    }

    // Asked over whatever else is on screen, one at a time.
    if (role == Role.HOST) {
        shared.requests.firstOrNull()?.let { request ->
            JoinRequestDialog(
                request = request,
                onAllow = { session.allow(request.id) },
                onDeny = { session.deny(request.id) },
            )
        }
        if (showShare) {
            ShareDialog(
                shared = shared,
                onRemove = { session.remove(it) },
                onStop = leaveSharing,
                onClose = { showShare = false },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Setting the table
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupPane(modifier: Modifier, onStart: (Int) -> Unit, onJoin: () -> Unit) {
    var count by remember { mutableStateOf(ScoreSheet.DEFAULT_PLAYERS) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Keep score for a game the app does not deal — darts, cribbage, " +
                "whatever is on the table. A column each, a row per round, and the " +
                "tally kept for you at the foot.",
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
            text = "Names come next: tap a column head to name it. Players can be " +
                "added, removed or dragged into a different order at any point " +
                "without losing anybody's score.",
            style = MaterialTheme.typography.bodySmall,
        )
        PrimaryAction(text = "Start scoring") { onStart(count) }
        OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
            Text("Join a shared sheet nearby")
        }
    }
}

// ---------------------------------------------------------------------------
// The sheet: a column per player, a row per round, totals ruled off at the foot
// ---------------------------------------------------------------------------

@Composable
private fun Sheet(modifier: Modifier, sheet: ScoreSheet, onOp: (SheetOp) -> Unit) {
    // One scroll state shared by the head, the rows and the totals, so all three
    // move together: a column and the name over it must never come apart. Every
    // row is built the same way and is therefore exactly the same width, which
    // is what lets them share it.
    val columns = rememberScrollState()
    val rows = rememberScrollState()

    // The row being filled in, and — when a round is being corrected instead —
    // the one under correction. Points are held as the text that was typed
    // until the row is finished, so a half-typed "-" is not a number yet.
    val pending = remember { mutableStateMapOf<Int, String>() }
    val correction = remember { mutableStateMapOf<Int, String>() }
    var correcting by remember { mutableStateOf(-1) }
    var renaming by remember { mutableStateOf(-1) }
    // Which cell the ± button flips: the last one typed into.
    var lastTyped by remember { mutableStateOf(-1) }
    // The cell to put the cursor in when a round is opened by tapping it.
    var focusPlayer by remember { mutableStateOf(-1) }

    val editing = if (correcting >= 0) correction else pending

    // Opens a round for correction. Both the round number and any of its cells
    // come through here, so tapping a score edits that score directly.
    val startCorrection: (Int, Int) -> Unit = { index, focus ->
        correcting = index
        correction.clear()
        sheet.rounds[index].deltas.forEach { (id, points) -> correction[id] = points.toString() }
        lastTyped = -1
        focusPlayer = focus
    }

    // Keep the row being filled in in view when a round is written down.
    LaunchedEffect(sheet.rounds.size) { rows.animateScrollTo(rows.maxValue) }

    // On a shared sheet somebody else can delete the round being corrected from
    // under it. There is then nothing to correct, and the row it was in is gone.
    LaunchedEffect(sheet.rounds.size) {
        if (correcting >= sheet.rounds.size) {
            correcting = -1
            correction.clear()
        }
    }

    val drag = rememberColumnDrag(sheet = sheet, onOp = onOp)

    Column(modifier = modifier) {
        Text(
            text = "Tap a name to rename it, or drag it to move the column.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        // The head: names, and a column to add another player.
        GridRow(
            sheet = sheet,
            scroll = columns,
            height = HEAD_HEIGHT,
            drag = drag,
            leading = {
                HeadCell(text = "", modifier = Modifier.width(ROUND_COLUMN).height(HEAD_HEIGHT))
            },
            trailing = {
                AddColumnCell(
                    enabled = sheet.players.size < ScoreSheet.MAX_PLAYERS,
                    onClick = { onOp(AddPlayer) },
                )
            },
        ) { index, player ->
            HeadCell(
                text = sheet.displayName(player),
                dragging = index == drag.index,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { renaming = player.id }
                    .pointerInput(player.id) {
                        detectDragGestures(
                            onDragStart = { drag.start(player.id) },
                            onDragEnd = { drag.end() },
                            onDragCancel = { drag.cancel() },
                            onDrag = { change, delta ->
                                // Consumed here so the sheet does not scroll
                                // sideways under the finger as well.
                                change.consume()
                                drag.move(delta.x)
                            },
                        )
                    },
            )
        }

        // The rounds, and the row being filled in at the bottom of them.
        Box(modifier = Modifier.weight(1f).verticalScroll(rows)) {
            Column(modifier = Modifier.horizontalScroll(columns)) {
                sheet.rounds.forEachIndexed { index, round ->
                    val underCorrection = index == correcting
                    GridRow(
                        sheet = sheet,
                        scroll = null,
                        height = ROW_HEIGHT,
                        drag = drag,
                        leading = {
                            RoundLabel(
                                text = "${index + 1}",
                                selected = underCorrection,
                                onClick = {
                                    if (underCorrection) {
                                        correcting = -1
                                    } else {
                                        startCorrection(index, -1)
                                    }
                                },
                            )
                        },
                        trailing = { EmptyCell(width = ADD_COLUMN, height = ROW_HEIGHT) },
                    ) { _, player ->
                        if (underCorrection) {
                            PointsField(
                                text = correction[player.id].orEmpty(),
                                autoFocus = focusPlayer == player.id,
                                onText = {
                                    correction[player.id] = it
                                    lastTyped = player.id
                                },
                            )
                        } else {
                            PointsCell(
                                text = signed(round.deltas[player.id]),
                                onClick = { startCorrection(index, player.id) },
                            )
                        }
                    }
                }

                // Always the last row: what has not been written down yet.
                GridRow(
                    sheet = sheet,
                    scroll = null,
                    height = ROW_HEIGHT,
                    drag = drag,
                    leading = {
                        RoundLabel(
                            text = "${sheet.rounds.size + 1}",
                            selected = correcting < 0,
                            onClick = { correcting = -1 },
                        )
                    },
                    trailing = { EmptyCell(width = ADD_COLUMN, height = ROW_HEIGHT) },
                ) { _, player ->
                    if (correcting >= 0) {
                        // A round is being corrected above; what is typed here
                        // is kept, but only one row is edited at a time.
                        PointsCell(text = pending[player.id].orEmpty(), muted = true)
                    } else {
                        PointsField(
                            text = pending[player.id].orEmpty(),
                            onText = {
                                pending[player.id] = it
                                lastTyped = player.id
                            },
                        )
                    }
                }
            }
        }

        // Ruled off at the foot, where the one number anybody looks up lives.
        GridRow(
            sheet = sheet,
            scroll = columns,
            height = TOTAL_HEIGHT,
            drag = drag,
            leading = {
                HeadCell(
                    text = "Σ",
                    modifier = Modifier.width(ROUND_COLUMN).height(TOTAL_HEIGHT),
                )
            },
            trailing = { EmptyCell(width = ADD_COLUMN, height = TOTAL_HEIGHT) },
        ) { _, player ->
            TotalCell(total = sheet.total(player.id))
        }

        ActionBar(
            roundNumber = if (correcting >= 0) correcting + 1 else sheet.rounds.size + 1,
            correcting = correcting >= 0,
            canFinish = amountsAsPoints(editing).isNotEmpty(),
            canFlip = editing[lastTyped].orEmpty().toIntOrNull() != null,
            onFlip = { editing[lastTyped] = flipSign(editing[lastTyped].orEmpty()) },
            onFinish = {
                if (correcting >= 0) {
                    onOp(EditRound(correcting, amountsAsPoints(correction)))
                    correcting = -1
                    correction.clear()
                } else {
                    onOp(AddRound(amountsAsPoints(pending)))
                    pending.clear()
                }
                lastTyped = -1
                focusPlayer = -1
            },
            onDelete = {
                onOp(DeleteRound(correcting))
                correcting = -1
                correction.clear()
                lastTyped = -1
                focusPlayer = -1
            },
            onCancel = {
                correcting = -1
                correction.clear()
                lastTyped = -1
                focusPlayer = -1
            },
        )
    }

    val renamed = sheet.players.firstOrNull { it.id == renaming }
    if (renamed != null) {
        RenameDialog(
            sheet = sheet,
            player = renamed,
            onName = { onOp(RenamePlayer(renamed.id, it)) },
            onRemove = {
                onOp(RemovePlayer(renamed.id))
                renaming = -1
            },
            onDismiss = { renaming = -1 },
        )
    }
}

// ---------------------------------------------------------------------------
// Dragging a column into a different place
// ---------------------------------------------------------------------------

/**
 * A column being dragged along the head of the sheet.
 *
 * The sheet is deliberately left alone until the finger lifts: the columns move
 * on screen as the drag passes them, but the positions the gesture is working
 * from cannot shift underneath it. The sheet and the callback are read through
 * [rememberUpdatedState] because the gesture handlers outlive the composition
 * that created them — without that, a drag would be applied to whatever the
 * sheet looked like when the head was first drawn, throwing away a name typed
 * since.
 */
@Composable
private fun rememberColumnDrag(sheet: ScoreSheet, onOp: (SheetOp) -> Unit): ColumnDrag {
    val widthPx = with(LocalDensity.current) { PLAYER_COLUMN.toPx() }
    val currentSheet by rememberUpdatedState(sheet)
    val commit by rememberUpdatedState(onOp)
    return remember { ColumnDrag(widthPx, { currentSheet }, { commit(it) }) }
}

private class ColumnDrag(
    private val widthPx: Float,
    private val sheet: () -> ScoreSheet,
    private val commit: (SheetOp) -> Unit,
) {
    /** Which column is under the finger, or -1 when nothing is being dragged. */
    var index by mutableStateOf(-1)
        private set
    private var offset by mutableStateOf(0f)

    private fun landing(): Int {
        val last = sheet().players.lastIndex
        if (index < 0 || last < 0) return -1
        return (index + (offset / widthPx).roundToInt()).coerceIn(0, last)
    }

    fun start(playerId: Int) {
        index = sheet().players.indexOfFirst { it.id == playerId }
        offset = 0f
    }

    fun move(dx: Float) {
        offset += dx
    }

    fun end() {
        val from = index
        val to = landing()
        index = -1
        offset = 0f
        val moved = sheet().players.getOrNull(from)
        if (moved != null && to >= 0 && to != from) commit(MovePlayer(moved.id, to))
    }

    fun cancel() {
        index = -1
        offset = 0f
    }

    /**
     * How far a column sits from where it belongs. The dragged one follows the
     * finger; the ones it has passed step aside to show where it would land.
     */
    fun shiftOf(column: Int): Float {
        if (index < 0) return 0f
        if (column == index) return offset
        val to = landing()
        return when {
            index < to && column in (index + 1)..to -> -widthPx
            index > to && column in to until index -> widthPx
            else -> 0f
        }
    }
}

// ---------------------------------------------------------------------------
// The grid itself
// ---------------------------------------------------------------------------

/**
 * One line of the sheet: a leading cell, a cell per player, a trailing cell.
 *
 * Head, rounds and totals all come through here, which is what guarantees they
 * are the same width — they share a scroll state, and a row that measured
 * differently would slide out of step with the rest.
 *
 * [scroll] is null for rows that already sit inside a scrolling column.
 */
@Composable
private fun GridRow(
    sheet: ScoreSheet,
    scroll: ScrollState?,
    height: Dp,
    drag: ColumnDrag,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    cell: @Composable (index: Int, player: ScorePlayer) -> Unit,
) {
    Row(
        modifier = Modifier
            .height(height)
            .then(if (scroll != null) Modifier.horizontalScroll(scroll) else Modifier),
    ) {
        leading()
        sheet.players.forEachIndexed { index, player ->
            Box(
                modifier = Modifier
                    .width(PLAYER_COLUMN)
                    .height(height)
                    // Over its neighbours while it is being dragged, so it
                    // passes across them rather than under.
                    .zIndex(if (index == drag.index) 1f else 0f)
                    .graphicsLayer { translationX = drag.shiftOf(index) },
            ) {
                cell(index, player)
            }
        }
        trailing()
    }
}

@Composable
private fun HeadCell(text: String, modifier: Modifier = Modifier, dragging: Boolean = false) {
    Box(
        modifier = modifier
            .background(
                if (dragging) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .border(Dp.Hairline, MaterialTheme.colorScheme.outline)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AddColumnCell(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(ADD_COLUMN)
            .height(HEAD_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(Dp.Hairline, MaterialTheme.colorScheme.outline)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+",
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            },
        )
    }
}

/** The round number down the left edge. Tapping it opens that round for correction. */
@Composable
private fun RoundLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(ROUND_COLUMN)
            .height(ROW_HEIGHT)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .border(Dp.Hairline, MaterialTheme.colorScheme.outline)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PointsCell(text: String, muted: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .border(Dp.Hairline, MaterialTheme.colorScheme.outlineVariant)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (muted) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}

/** A cell being typed into. Blank means nothing entered; a typed 0 is kept and shown. */
@Composable
private fun PointsField(text: String, onText: (String) -> Unit, autoFocus: Boolean = false) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) focus.requestFocus() }
    BasicTextField(
        value = text,
        onValueChange = { onText(sanitiseAmount(it)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxSize().focusRequester(focus),
        decorationBox = { field ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "0",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    )
                }
                field()
            }
        },
    )
}

@Composable
private fun TotalCell(total: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(Dp.Hairline, MaterialTheme.colorScheme.outline),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = total.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyCell(width: Dp, height: Dp) {
    Box(modifier = Modifier.width(width).height(height).background(Color.Transparent))
}

// ---------------------------------------------------------------------------
// Below the sheet
// ---------------------------------------------------------------------------

@Composable
private fun ActionBar(
    roundNumber: Int,
    correcting: Boolean,
    canFinish: Boolean,
    canFlip: Boolean,
    onFlip: () -> Unit,
    onFinish: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Not every keyboard offers a minus on its number pad, and taking
            // points away has to work on all of them.
            OutlinedButton(onClick = onFlip, enabled = canFlip) { Text("±") }
            Button(
                onClick = onFinish,
                enabled = canFinish,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (correcting) "Save round $roundNumber" else "Finish round $roundNumber")
            }
        }
        if (correcting) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onDelete) { Text("Delete round $roundNumber") }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    sheet: ScoreSheet,
    player: ScorePlayer,
    onName: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this column") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = player.name,
                    onValueChange = onName,
                    placeholder = { Text(sheet.displayName(player)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = onRemove,
                    // The last two columns stay: a sheet needs somebody on it.
                    enabled = sheet.players.size > ScoreSheet.MIN_PLAYERS,
                ) { Text("Remove this player and their score") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

// ---------------------------------------------------------------------------

private val ROUND_COLUMN = 40.dp
private val PLAYER_COLUMN = 88.dp
private val ADD_COLUMN = 44.dp
private val HEAD_HEIGHT = 48.dp
private val ROW_HEIGHT = 44.dp
private val TOTAL_HEIGHT = 56.dp

/** Keeps a points field to a number, optionally negative, as it is typed. */
private fun sanitiseAmount(raw: String): String {
    val negative = raw.startsWith("-")
    val digits = raw.filter { it.isDigit() }.take(5)
    return if (negative) "-$digits" else digits
}

private fun flipSign(text: String): String {
    val value = text.toIntOrNull() ?: return text
    return (-value).toString()
}

/** The typed cells as points. Blanks and a lone minus drop out; a typed 0 stays. */
private fun amountsAsPoints(amounts: Map<Int, String>): Map<Int, Int> =
    amounts.mapNotNull { (id, text) -> text.toIntOrNull()?.let { id to it } }.toMap()

/** Not entered shows as an empty cell; an entered 0 shows as 0. */
private fun signed(points: Int?): String = when {
    points == null -> ""
    points > 0 -> "+$points"
    else -> points.toString()
}
