package org.prolibertate.games.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.tayu.Axis
import org.prolibertate.games.game.tayu.BOARD_CELLS
import org.prolibertate.games.game.tayu.BOARD_SIZE
import org.prolibertate.games.game.tayu.Facing
import org.prolibertate.games.game.tayu.TILE_LENGTH
import org.prolibertate.games.game.tayu.TayuBoard
import org.prolibertate.games.game.tayu.TayuMove
import org.prolibertate.games.game.tayu.TayuPhase
import org.prolibertate.games.game.tayu.TayuRules
import org.prolibertate.games.game.tayu.TayuState
import org.prolibertate.games.game.tayu.TayuTiles
import org.prolibertate.games.game.tayu.TileSlots
import org.prolibertate.games.game.tayu.absoluteFacing
import org.prolibertate.games.game.tayu.axisOfTeam
import org.prolibertate.games.game.tayu.cellsOf
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.ui.ScreenScaffold
import org.prolibertate.games.ui.theme.FeltGreenDark
import org.prolibertate.games.ui.theme.TeamColours

/**
 * Board colours, kept off the theme so the board reads the same in either mode.
 *
 * The water is deliberately a pale blue rather than the team blue: the two axes
 * are marked in the team colours, and a river the same colour as a scoring mark
 * would be unreadable at this cell size.
 */
private val TileStone = Color(0xFFD8CDB4)
private val TileEdge = Color(0xFF6E6350)
private val River = Color(0xFF7EC8E3)
private val Indentation = Color(0x2AFFFFFF)
private val GhostAmber = Color(0xFFFFC107)
private val ExitRim = Color(0xFFFFFFFF)

/**
 * The Ta Yü board.
 *
 * 18 × 18 cells is far too fine a grid to tap accurately, so placing a tile is
 * two steps rather than one: tap the board to line a placement up, and a button
 * commits it. Tapping the same cell again cycles through the other legal ways
 * the drawn tile could cover it, which is also how the tile gets turned — the
 * tile has four orientations but only some of them fit, and offering a rotate
 * control that lands on illegal positions would be worse than useless.
 *
 * It is drawn on one canvas rather than as 324 composables. The rivers run
 * between cells rather than inside them, so nested boxes would have had nowhere
 * to put them.
 */
@Composable
fun TayuScreen(
    controller: MatchController<TayuState, TayuMove>,
    localSeat: Int,
    onExit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val legal by controller.legalMoves.collectAsState()
    val abandoned by controller.abandoned.collectAsState()

    // The cell last tapped, and the placement currently lined up on it.
    var tapped by remember { mutableStateOf<Int?>(null) }
    var lined by remember { mutableStateOf<TayuMove?>(null) }
    var confirmingEnd by remember { mutableStateOf(false) }

    val endGameAction: @Composable () -> Unit = {
        TextButton(onClick = { confirmingEnd = true }) { Text("End game") }
    }

    val current = state
    if (current == null) {
        ScreenScaffold(title = "Ta Yü", onBack = onExit, actions = endGameAction) { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) { Text("Setting up…") }
            // A table that never arrives — a host that went away mid-handshake —
            // still has to be leavable.
            abandoned?.let {
                TayuLeftTheTableDialog(it, onExit = onExit, onStay = controller::dismissAbandoned)
            }
            if (confirmingEnd) {
                TayuConfirmEndDialog(onEnd = onExit, onKeepPlaying = { confirmingEnd = false })
            }
        }
        return
    }

    // A tile laid, or a new one drawn, makes last turn's selection meaningless.
    LaunchedEffect(current.placed.size, current.drawn) {
        tapped = null
        lined = null
    }

    val candidatesAt: (Int) -> List<TayuMove> = { cell ->
        legal.filter { cell in cellsOf(it) }
    }

    // Lines up the next legal placement covering the tapped cell. Tapping the
    // same cell again moves on to the next way the tile could cover it, which is
    // also what the turn button does.
    val onCellTapped: (Int) -> Unit = { cell ->
        val options = candidatesAt(cell)
        // A tap on a cell the tile cannot cover is ignored rather than treated as
        // a deselection. Cells are under 20dp across on a phone, so a fingertip
        // that lands one cell wide of the mark would otherwise throw away a
        // placement that took several taps to line up.
        if (options.isNotEmpty()) {
            val position = options.indexOf(lined)
            lined = if (tapped == cell && position >= 0) {
                options[(position + 1) % options.size]
            } else {
                options.first()
            }
            tapped = cell
        }
    }

    // Checked against the live list rather than trusted: the selection is held
    // across recompositions and a remote player may have moved in between.
    val ready = lined?.takeIf { it in legal }
    val alternatives = tapped?.let { candidatesAt(it).size } ?: 0

    ScreenScaffold(title = "Ta Yü", onBack = onExit, actions = endGameAction) { modifier ->
        BoxWithConstraints(modifier = modifier) {
            val wide = maxWidth > maxHeight
            val boardSide = if (wide) {
                minOf(maxHeight, maxWidth * 0.60f)
            } else {
                minOf(maxWidth, maxHeight * 0.66f)
            }

            val board: @Composable () -> Unit = {
                Board(
                    state = current,
                    legal = legal,
                    lined = ready,
                    side = boardSide,
                    onTap = onCellTapped,
                )
            }

            val panel: @Composable (Modifier) -> Unit = { panelModifier ->
                Column(
                    modifier = panelModifier
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusPanel(current, localSeat)
                    DrawnTilePanel(
                        state = current,
                        yourTurn = current.turn == localSeat && legal.isNotEmpty(),
                        ready = ready != null,
                        alternatives = alternatives,
                        onTurn = { tapped?.let(onCellTapped) },
                        onPlace = { ready?.let { controller.submit(it) } },
                    )
                }
            }

            if (wide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    board()
                    panel(Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    board()
                    panel(Modifier.fillMaxWidth().weight(1f))
                }
            }

            // One dialog at a time, most pressing first: a table nobody can
            // finish comes before the result of one that did.
            val over = abandoned
            when {
                over != null ->
                    TayuLeftTheTableDialog(
                        notice = over,
                        onExit = onExit,
                        onStay = controller::dismissAbandoned,
                    )

                confirmingEnd ->
                    TayuConfirmEndDialog(onEnd = onExit, onKeepPlaying = { confirmingEnd = false })

                current.phase == TayuPhase.GAME_OVER -> ResultDialog(current, localSeat, onExit)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The board
// ---------------------------------------------------------------------------

/**
 * Where everything sits on the canvas.
 *
 * The tap handler and the drawing both go through this, so a tap always lands
 * on the cell it looks like it landed on.
 */
private class BoardGeometry(
    /** Room outside the grid for the edge marks and the exits. */
    val margin: Float,
    val cellSize: Float,
) {

    /** A whole board filling a square canvas [sidePx] across. */
    constructor(sidePx: Float) : this(
        margin = sidePx * MARGIN_FRACTION,
        cellSize = (sidePx - 2 * sidePx * MARGIN_FRACTION) / BOARD_SIZE,
    )

    fun centreOf(cell: Int): Offset = Offset(
        x = margin + (TayuBoard.colOf(cell) + 0.5f) * cellSize,
        y = margin + (TayuBoard.rowOf(cell) + 0.5f) * cellSize,
    )

    /** Steps [distance] cells from [from] in [direction], in canvas units. */
    fun step(from: Offset, direction: Facing, distance: Float): Offset = Offset(
        x = from.x + direction.dc * cellSize * distance,
        y = from.y + direction.dr * cellSize * distance,
    )

    fun cellAt(offset: Offset): Int? {
        // Rejected explicitly rather than left to the conversion: truncation
        // rounds towards zero, so a tap in the margin above the grid would
        // otherwise land on the first row instead of on nothing.
        val x = offset.x - margin
        val y = offset.y - margin
        if (x < 0f || y < 0f) return null
        val col = (x / cellSize).toInt()
        val row = (y / cellSize).toInt()
        return if (TayuBoard.onBoard(row, col)) TayuBoard.cellAt(row, col) else null
    }

    private companion object {
        const val MARGIN_FRACTION = 0.045f
    }
}

@Composable
private fun Board(
    state: TayuState,
    legal: List<TayuMove>,
    lined: TayuMove?,
    side: Dp,
    onTap: (Int) -> Unit,
) {
    val sidePx = with(LocalDensity.current) { side.toPx() }
    val geometry = remember(sidePx) { BoardGeometry(sidePx) }
    val handler by rememberUpdatedState(onTap)

    // Only hinted while nothing is lined up: once a placement is chosen the
    // hints would compete with the thing the player is looking at.
    val hints = remember(legal, lined) {
        if (lined != null) emptySet() else legal.flatMap { cellsOf(it) }.toSet()
    }

    Canvas(
        modifier = Modifier
            .size(side)
            .pointerInput(geometry) {
                detectTapGestures { offset -> geometry.cellAt(offset)?.let(handler) }
            }
    ) {
        drawRect(color = FeltGreenDark)
        drawEdges(geometry)
        for (cell in 0 until BOARD_CELLS) {
            drawCircle(Indentation, geometry.cellSize * 0.10f, geometry.centreOf(cell))
        }
        drawMarkedExits(geometry)

        for (tile in state.placed) {
            drawTile(
                mask = tile.mask,
                cells = tile.cells(),
                facing = tile.facing,
                geometry = geometry,
                body = TileStone,
                edge = TileEdge,
                water = River,
            )
        }
        drawExits(state, geometry)

        hints.forEach { cell ->
            drawCircle(
                color = GhostAmber.copy(alpha = 0.35f),
                radius = geometry.cellSize * 0.16f,
                center = geometry.centreOf(cell),
            )
        }

        lined?.let { move ->
            drawTile(
                mask = state.drawn ?: return@let,
                cells = cellsOf(move),
                facing = move.facing,
                geometry = geometry,
                body = GhostAmber.copy(alpha = 0.30f),
                edge = GhostAmber,
                water = GhostAmber,
            )
        }
    }
}

/** The four edges, tinted with the colour of the side trying to reach them. */
private fun DrawScope.drawEdges(geometry: BoardGeometry) {
    val northSouth = TeamColours[0].copy(alpha = 0.55f)
    val eastWest = TeamColours[1].copy(alpha = 0.55f)
    val thickness = geometry.cellSize * 0.14f
    val start = geometry.margin
    val end = geometry.margin + BOARD_SIZE * geometry.cellSize

    drawLine(northSouth, Offset(start, start), Offset(end, start), thickness)
    drawLine(northSouth, Offset(start, end), Offset(end, end), thickness)
    drawLine(eastWest, Offset(end, start), Offset(end, end), thickness)
    drawLine(eastWest, Offset(start, start), Offset(start, end), thickness)
}

/** Rings on the exits that count double. */
private fun DrawScope.drawMarkedExits(geometry: BoardGeometry) {
    for (edge in Facing.entries) {
        val colour = if (edge == Facing.NORTH || edge == Facing.SOUTH) {
            TeamColours[0]
        } else {
            TeamColours[1]
        }
        for (position in TayuBoard.markedExits) {
            val at = geometry.step(
                from = geometry.centreOf(TayuBoard.edgeCell(edge, position)),
                direction = edge,
                distance = 0.78f,
            )
            drawCircle(
                color = colour,
                radius = geometry.cellSize * 0.30f,
                center = at,
                style = Stroke(width = geometry.cellSize * 0.12f),
            )
        }
    }
}

/** Water already off the board: one dot per exit, in the colour of the edge. */
private fun DrawScope.drawExits(state: TayuState, geometry: BoardGeometry) {
    for (tile in state.placed) {
        val cells = tile.cells()
        for (slot in TayuTiles.mouthSlots(tile.mask)) {
            val cell = cells[TileSlots.cellOf(slot)]
            val direction = absoluteFacing(tile.facing, TileSlots.flankOf(slot))
            val row = TayuBoard.rowOf(cell) + direction.dr
            val col = TayuBoard.colOf(cell) + direction.dc
            if (TayuBoard.onBoard(row, col)) continue

            val centre = geometry.centreOf(cell)
            val outside = geometry.step(centre, direction, 0.78f)
            drawLine(
                color = River,
                start = geometry.step(centre, direction, 0.5f),
                end = outside,
                strokeWidth = geometry.cellSize * 0.20f,
                cap = StrokeCap.Round,
            )
            val colour = if (direction == Facing.NORTH || direction == Facing.SOUTH) {
                TeamColours[0]
            } else {
                TeamColours[1]
            }
            drawCircle(ExitRim, geometry.cellSize * 0.22f, outside)
            drawCircle(colour, geometry.cellSize * 0.16f, outside)
        }
    }
}

/**
 * One tile: its body, then a channel from each of its three mouths in to the
 * pool at its middle.
 *
 * Each channel is drawn as two straight runs — mouth to the centre of its own
 * cell, then on to the middle of the tile — so the water turns square corners
 * instead of cutting diagonally across the stone.
 */
private fun DrawScope.drawTile(
    mask: Int,
    cells: List<Int>,
    facing: Facing,
    geometry: BoardGeometry,
    body: Color,
    edge: Color,
    water: Color,
) {
    val centres = cells.map { geometry.centreOf(it) }
    val inset = geometry.cellSize * 0.09f
    val half = geometry.cellSize / 2

    val left = centres.minOf { it.x } - half + inset
    val top = centres.minOf { it.y } - half + inset
    val right = centres.maxOf { it.x } + half - inset
    val bottom = centres.maxOf { it.y } + half - inset

    drawRoundRect(
        color = body,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = CornerRadius(geometry.cellSize * 0.22f),
    )
    drawRoundRect(
        color = edge,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = CornerRadius(geometry.cellSize * 0.22f),
        style = Stroke(width = geometry.cellSize * 0.07f),
    )

    val channel = geometry.cellSize * 0.20f
    val middle = centres[1]
    for (slot in TayuTiles.mouthSlots(mask)) {
        val index = TileSlots.cellOf(slot)
        val direction = absoluteFacing(facing, TileSlots.flankOf(slot))
        val own = centres[index]
        drawLine(
            color = water,
            start = geometry.step(own, direction, 0.5f),
            end = own,
            strokeWidth = channel,
            cap = StrokeCap.Round,
        )
        if (index != 1) {
            drawLine(water, own, middle, strokeWidth = channel, cap = StrokeCap.Round)
        }
    }
    drawCircle(water, geometry.cellSize * 0.17f, middle)
    if (TayuTiles.touchesThreeFlanks(mask)) {
        // The reissued tiles ring the centre stud on exactly these, so the ring
        // is here too — it marks a tile that can reach three sides at once.
        drawCircle(
            color = edge,
            radius = geometry.cellSize * 0.30f,
            center = middle,
            style = Stroke(width = geometry.cellSize * 0.05f),
        )
    }
}

// ---------------------------------------------------------------------------
// The panel
// ---------------------------------------------------------------------------

@Composable
private fun StatusPanel(state: TayuState, localSeat: Int) {
    val exits = TayuRules.exitsOf(state)
    val yours = state.axisOf(localSeat)
    val theirs = if (yours == Axis.NORTH_SOUTH) Axis.EAST_WEST else Axis.NORTH_SOUTH

    Column {
        Text("You run ${yours.label}", fontWeight = FontWeight.Bold)
        listOf(yours, theirs).forEach { axis ->
            val team = if (axis == Axis.NORTH_SOUTH) 0 else 1
            Text(
                text = "${axis.label.replaceFirstChar { it.uppercase() }}: " +
                    "${exits.on(axis.first)} × ${exits.on(axis.second)} = " +
                    "${exits.product(axis)}",
                style = MaterialTheme.typography.bodyMedium,
                color = TeamColours.getOrElse(team) { Color.Gray },
            )
        }
        Text(
            text = when {
                state.phase == TayuPhase.GAME_OVER -> "The bag is empty."
                state.turn == localSeat -> "Your turn"
                else -> "Seat ${state.turn} is playing…"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "${state.bagCount} tiles left" +
                if (state.setAside.isEmpty()) {
                    "."
                } else {
                    ", ${state.setAside.size} set aside as unplaceable."
                },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The tile just drawn, plus the two controls that lay it down. */
@Composable
private fun DrawnTilePanel(
    state: TayuState,
    yourTurn: Boolean,
    ready: Boolean,
    alternatives: Int,
    onTurn: () -> Unit,
    onPlace: () -> Unit,
) {
    val drawn = state.drawn
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Drawn from the bag", style = MaterialTheme.typography.labelLarge)
        if (drawn == null) {
            Text("Nothing left to draw.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        TilePreview(mask = drawn, cellSize = 30.dp)
        Text(
            text = when {
                !yourTurn -> "Waiting for the other player."
                !ready -> "Tap a highlighted cell to line the tile up there."
                alternatives > 1 ->
                    "Tap the same cell again, or turn the tile, to try another way round."

                else -> "Only one way this tile will fit there."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (yourTurn) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onTurn, enabled = alternatives > 1) {
                    Text("Turn the tile")
                }
                Button(onClick = onPlace, enabled = ready) { Text("Lay the tile") }
            }
        }
    }
}

/**
 * The drawn tile, lying east–west as it came out of the bag.
 *
 * Deliberately not turned to match the placement being lined up: the board
 * already shows that, and a preview that kept spinning would be harder to read
 * than one that holds still.
 */
@Composable
private fun TilePreview(mask: Int, cellSize: Dp) {
    val cellPx = with(LocalDensity.current) { cellSize.toPx() }
    // No margin: the three cells start at the canvas origin, so the board's own
    // drawing code lays the tile out here without knowing it is off the board.
    val geometry = remember(cellPx) { BoardGeometry(margin = 0f, cellSize = cellPx) }
    val cells = remember {
        (0 until TILE_LENGTH).map { TayuBoard.cellAt(0, it) }
    }

    Canvas(modifier = Modifier.width(cellSize * TILE_LENGTH).height(cellSize)) {
        drawTile(
            mask = mask,
            cells = cells,
            facing = Facing.EAST,
            geometry = geometry,
            body = TileStone,
            edge = TileEdge,
            water = River,
        )
    }
}

@Composable
private fun ResultDialog(state: TayuState, localSeat: Int, onExit: () -> Unit) {
    var dismissed by remember(state.phase) { mutableStateOf(false) }
    if (dismissed) return

    val exits = TayuRules.exitsOf(state)
    val winner = TayuRules.winnerOf(state)
    val headline = when {
        winner == null -> "A draw"
        winner == state.teamOf(localSeat) -> "You win"
        else -> "${axisOfTeam(winner).label.replaceFirstChar { it.uppercase() }} wins"
    }

    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Game over") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(headline, fontWeight = FontWeight.Bold)
                Text(
                    text = "North and south: ${exits.north} × ${exits.south} = " +
                        "${exits.product(Axis.NORTH_SOUTH)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "East and west: ${exits.east} × ${exits.west} = " +
                        "${exits.product(Axis.EAST_WEST)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "${state.placed.size} tiles laid" +
                        if (state.setAside.isEmpty()) {
                            "."
                        } else {
                            ", ${state.setAside.size} that would not fit anywhere."
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = {
            TextButton(onClick = { dismissed = true }) { Text("Look at the board") }
        },
    )
}

/**
 * Leaving on purpose. Ta Yü runs to the whole bag, so a table that has stopped
 * being fun needs a way out that does not mean force-quitting the app.
 */
@Composable
private fun TayuConfirmEndDialog(onEnd: () -> Unit, onKeepPlaying: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepPlaying,
        title = { Text("End this game?") },
        text = {
            Text(
                "This game ends here and the board is not saved. Anyone else at the " +
                    "table is told you have left."
            )
        },
        confirmButton = { TextButton(onClick = onEnd) { Text("End the game") } },
        dismissButton = { TextButton(onClick = onKeepPlaying) { Text("Keep playing") } },
    )
}

/** The other end has gone, so no further tile is ever going to arrive. */
@Composable
private fun TayuLeftTheTableDialog(notice: String, onExit: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("The game has ended") },
        text = { Text("$notice There are no more tiles to lay.") },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = onStay) { Text("Look at the board") } },
    )
}
