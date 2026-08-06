package org.prolibertate.games.game.tayu

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.cards.shuffledWith
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

/**
 * Ta Yü as a pure state machine. See RULES-tayu.md for the ruleset, and for
 * which parts of it had to be reconstructed from an out-of-print rulebook.
 *
 * The one thing worth knowing before reading on: because every tile after the
 * first has to join a river already down, and because all three mouths of a
 * tile are connected across its face, everything on the board is always a
 * single river system running back to the opening tile. Nothing here ever has
 * to trace connectivity — a mouth at the board's edge is a scoring exit by
 * construction.
 */
object TayuRules : GameRules<TayuState, TayuMove> {

    override val gameId: String = GameCatalog.TAYU

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): TayuState {
        val options = json.decodeFromString<TayuOptions>(config.optionsJson)
        require(config.seats.size == options.playerCount) {
            "Seat count ${config.seats.size} does not match ${options.playerCount} players"
        }

        val bag = TayuTiles.bag(options.tileCopies).shuffledWith(Random(config.seed))
        val teams = (0 until options.playerCount).map { it % 2 }

        val empty = TayuState(
            options = options,
            seed = config.seed,
            teams = teams,
            tileAt = List(BOARD_CELLS) { NO_TILE },
            placed = emptyList(),
            bag = bag,
            drawn = null,
            bagCount = bag.size,
            setAside = emptyList(),
            turn = 0,
            phase = TayuPhase.PLAYING,
            log = listOf(
                "Ta Yü — ${options.tileCount} tiles. " +
                    "Seat 0 runs north and south, seat 1 east and west."
            ),
        )
        return openTurn(empty, seat = 0)
    }

    override fun currentSeat(state: TayuState): Int? =
        if (state.phase == TayuPhase.PLAYING && state.drawn != null) state.turn else null

    // -----------------------------------------------------------------------
    // Legal moves
    // -----------------------------------------------------------------------

    override fun legalMoves(state: TayuState, seat: Int): List<TayuMove> {
        if (currentSeat(state) != seat) return emptyList()
        return placementsFor(state, state.drawn ?: return emptyList())
    }

    /**
     * Every way [tile] can be laid on the board as it stands.
     *
     * Four orientations at every cell. The anchor is the tile's first cell, so
     * laying the same three cells the other way round is a different facing
     * from a different anchor rather than a special case.
     */
    fun placementsFor(state: TayuState, tile: Int): List<TayuMove> {
        val mouths = mouthMap(state)
        val opening = state.placed.isEmpty()
        val moves = mutableListOf<TayuMove>()
        for (row in 0 until BOARD_SIZE) {
            for (col in 0 until BOARD_SIZE) {
                for (facing in Facing.entries) {
                    if (fits(state, mouths, opening, tile, row, col, facing)) {
                        moves += TayuMove(row, col, facing)
                    }
                }
            }
        }
        return moves
    }

    /**
     * Whether a tile can be laid here.
     *
     * The interesting half is the mouth accounting. Walk the tile's eight
     * perimeter slots; for each, look at what lies across it:
     *
     * - off the board — fine either way. A mouth there is a scoring exit, and a
     *   blank there is just shoreline.
     * - an empty cell — fine either way. A river runs on into open ground.
     * - another tile — then the two must agree. A mouth must meet a mouth, and
     *   a blank must meet a blank.
     *
     * That last comparison is doing double duty: it refuses a river that would
     * dead-end against the flank of an existing tile, and in the same breath it
     * refuses a placement whose own blank flank would wall off a river already
     * on the board.
     */
    private fun fits(
        state: TayuState,
        mouths: IntArray,
        opening: Boolean,
        tile: Int,
        row: Int,
        col: Int,
        facing: Facing,
    ): Boolean {
        val rows = IntArray(TILE_LENGTH)
        val cols = IntArray(TILE_LENGTH)
        for (i in 0 until TILE_LENGTH) {
            rows[i] = row + i * facing.dr
            cols[i] = col + i * facing.dc
            if (!TayuBoard.onBoard(rows[i], cols[i])) return false
            if (state.tileAt[TayuBoard.cellAt(rows[i], cols[i])] != NO_TILE) return false
        }

        // The opening tile has no river to join, only the centre to cover.
        if (opening) {
            return (0 until TILE_LENGTH).any {
                TayuBoard.cellAt(rows[it], cols[it]) in TayuBoard.centre
            }
        }

        var joins = 0
        for (slot in 0 until TileSlots.COUNT) {
            val i = TileSlots.cellOf(slot)
            val direction = absoluteFacing(facing, TileSlots.flankOf(slot))
            val neighbourRow = rows[i] + direction.dr
            val neighbourCol = cols[i] + direction.dc
            if (!TayuBoard.onBoard(neighbourRow, neighbourCol)) continue

            val neighbour = TayuBoard.cellAt(neighbourRow, neighbourCol)
            if (state.tileAt[neighbour] == NO_TILE) continue

            val ours = TayuTiles.hasMouth(tile, slot)
            val theirs = hasMouth(mouths, neighbour, direction.opposite)
            if (ours != theirs) return false
            if (ours) joins++
        }
        // Every tile after the first has to join the river already down.
        return joins > 0
    }

    // -----------------------------------------------------------------------
    // Move application
    // -----------------------------------------------------------------------

    override fun applyMove(state: TayuState, seat: Int, move: TayuMove): TayuState {
        require(currentSeat(state) == seat) { "Not seat $seat's turn" }
        require(move in legalMoves(state, seat)) { "Illegal placement $move for seat $seat" }

        val placed = previewPlacement(state, seat, move)
        val gained = exitsOf(placed).product(placed.axisOf(seat)) -
            exitsOf(state).product(state.axisOf(seat))

        val noted = placed.copy(
            log = placed.log + buildString {
                append("Seat $seat lays a tile at ")
                append(coordinate(move.row, move.col))
                if (gained > 0) append(" — ${placed.axisOf(seat).label} now scores")
                append(".")
            },
        )
        return openTurn(noted, seat = (seat + 1) % state.playerCount)
    }

    /**
     * The board with [move] laid on it, and nothing else changed — no draw, no
     * change of turn.
     *
     * Public because the AI leans on it: rating a placement means looking at the
     * board it would produce, and going through [applyMove] for that would also
     * deal the next tile, which costs far more than the rating does.
     */
    fun previewPlacement(state: TayuState, seat: Int, move: TayuMove): TayuState {
        val tile = requireNotNull(state.drawn) { "No tile has been drawn" }
        val placedTile = PlacedTile(
            mask = tile,
            row = move.row,
            col = move.col,
            facing = move.facing,
            seat = seat,
        )
        val index = state.placed.size
        val tileAt = state.tileAt.toMutableList()
        placedTile.cells().forEach { tileAt[it] = index }

        return state.copy(
            tileAt = tileAt,
            placed = state.placed + placedTile,
            drawn = null,
        )
    }

    /**
     * Hands the next tile to [seat].
     *
     * A drawn tile that cannot be laid anywhere is set aside and the turn moves
     * on, so this keeps drawing — and keeps passing the turn — until it finds a
     * tile somebody can actually place. When the bag runs dry the game is over,
     * which means the seat on the clock always has at least one legal move.
     */
    private fun openTurn(state: TayuState, seat: Int): TayuState {
        var bag = state.bag
        var setAside = state.setAside
        var log = state.log
        var turn = seat

        while (bag.isNotEmpty()) {
            val tile = bag.first()
            bag = bag.drop(1)
            val candidate = state.copy(
                bag = bag,
                bagCount = bag.size,
                drawn = tile,
                setAside = setAside,
                turn = turn,
                log = log,
            )
            if (placementsFor(candidate, tile).isNotEmpty()) return candidate

            setAside = setAside + tile
            log = log + "Seat $turn draws a tile that will not fit anywhere. Set aside."
            turn = (turn + 1) % state.playerCount
        }

        return state.copy(
            bag = emptyList(),
            bagCount = 0,
            drawn = null,
            setAside = setAside,
            turn = turn,
            phase = TayuPhase.GAME_OVER,
            log = log + "The bag is empty. ${summaryOf(state)}",
        )
    }

    // -----------------------------------------------------------------------
    // Reading the board
    // -----------------------------------------------------------------------

    /**
     * River mouths per cell, as a bitmask over [Facing.ordinal].
     *
     * Rebuilt from [TayuState.placed] on demand rather than carried in the
     * state, so there is no second copy of the board that could drift out of
     * step with the first.
     */
    fun mouthMap(state: TayuState): IntArray {
        val map = IntArray(BOARD_CELLS)
        for (tile in state.placed) {
            val cells = tile.cells()
            for (slot in TayuTiles.mouthSlots(tile.mask)) {
                val cell = cells[TileSlots.cellOf(slot)]
                val direction = absoluteFacing(tile.facing, TileSlots.flankOf(slot))
                map[cell] = map[cell] or (1 shl direction.ordinal)
            }
        }
        return map
    }

    private fun hasMouth(mouths: IntArray, cell: Int, direction: Facing): Boolean =
        (mouths[cell] shr direction.ordinal) and 1 == 1

    /**
     * Exits reached on each edge, marked ones already counted double.
     *
     * An exit is a mouth pointing off the board. It needs no connectivity check
     * — see the note on [TayuRules].
     */
    fun exitsOf(state: TayuState): EdgeExits {
        val totals = IntArray(4)
        for (tile in state.placed) {
            val cells = tile.cells()
            for (slot in TayuTiles.mouthSlots(tile.mask)) {
                val cell = cells[TileSlots.cellOf(slot)]
                val row = TayuBoard.rowOf(cell)
                val col = TayuBoard.colOf(cell)
                val direction = absoluteFacing(tile.facing, TileSlots.flankOf(slot))
                if (TayuBoard.onBoard(row + direction.dr, col + direction.dc)) continue
                totals[direction.ordinal] += TayuBoard.exitValue(direction, row, col)
            }
        }
        return EdgeExits(
            north = totals[Facing.NORTH.ordinal],
            east = totals[Facing.EAST.ordinal],
            south = totals[Facing.SOUTH.ordinal],
            west = totals[Facing.WEST.ordinal],
        )
    }

    /**
     * Mouths with open ground in front of them: where the river can still grow.
     *
     * Returned as the cell the mouth is on and the way it points. The AI uses
     * this to tell a river heading for its own edge from one heading for the
     * opponent's.
     */
    fun openMouths(state: TayuState): List<Pair<Int, Facing>> {
        val open = mutableListOf<Pair<Int, Facing>>()
        for (tile in state.placed) {
            val cells = tile.cells()
            for (slot in TayuTiles.mouthSlots(tile.mask)) {
                val cell = cells[TileSlots.cellOf(slot)]
                val direction = absoluteFacing(tile.facing, TileSlots.flankOf(slot))
                val row = TayuBoard.rowOf(cell) + direction.dr
                val col = TayuBoard.colOf(cell) + direction.dc
                if (!TayuBoard.onBoard(row, col)) continue
                if (state.tileAt[TayuBoard.cellAt(row, col)] != NO_TILE) continue
                open += cell to direction
            }
        }
        return open
    }

    /** Points scored by [team], which is its two edges multiplied together. */
    fun scoreOf(state: TayuState, team: Int): Int =
        exitsOf(state).product(axisOfTeam(team))

    /** The winning team, or null for a draw. */
    fun winnerOf(state: TayuState): Int? {
        val northSouth = scoreOf(state, 0)
        val eastWest = scoreOf(state, 1)
        return when {
            northSouth > eastWest -> 0
            eastWest > northSouth -> 1
            else -> null
        }
    }

    // -----------------------------------------------------------------------
    // Results, redaction and wire format
    // -----------------------------------------------------------------------

    override fun isFinished(state: TayuState): Boolean = state.phase == TayuPhase.GAME_OVER

    override fun summary(state: TayuState): String = summaryOf(state)

    private fun summaryOf(state: TayuState): String {
        val exits = exitsOf(state)
        return "North–south ${exits.north}×${exits.south} = ${exits.product(Axis.NORTH_SOUTH)}  " +
            "East–west ${exits.east}×${exits.west} = ${exits.product(Axis.EAST_WEST)}"
    }

    /**
     * The bag's order is the only thing anybody is not entitled to see: the
     * board is in front of everyone, and a drawn tile comes out face up.
     */
    override fun viewFor(state: TayuState, seat: Int): TayuState =
        state.copy(bag = emptyList())

    override fun encodeState(state: TayuState): String = json.encodeToString(state)

    override fun decodeState(json: String): TayuState = this.json.decodeFromString(json)

    override fun encodeMove(move: TayuMove): String = json.encodeToString(move)

    override fun decodeMove(json: String): TayuMove = this.json.decodeFromString(json)

    /** Board coordinates the way a player would read them out: `f12`. */
    fun coordinate(row: Int, col: Int): String = "${'a' + col}${row + 1}"
}
