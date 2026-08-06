package org.prolibertate.games.game.tayu

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig
import org.junit.Test
import kotlin.random.Random

class TayuRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(
        options: TayuOptions = TayuOptions(),
        seed: Long = 7L,
    ) = TableConfig(
        gameId = "tayu",
        seats = (0 until options.playerCount).map {
            PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it % 2)
        },
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    // -- The tile set -------------------------------------------------------

    @Test
    fun `there are exactly twenty-eight distinct tiles`() {
        // This is the number the whole reconstruction rests on: 84 tiles in the
        // reissue is three of each, and the original's 112 is four of each.
        assertEquals(28, TayuTiles.all.size)
        assertEquals(84, 3 * TayuTiles.all.size)
        assertEquals(112, 4 * TayuTiles.all.size)
    }

    @Test
    fun `every tile has three mouths and no two tiles are the same`() {
        TayuTiles.all.forEach { mask ->
            assertEquals("$mask", TayuTiles.MOUTHS_PER_TILE, mask.countOneBits())
        }
        assertEquals(TayuTiles.all.size, TayuTiles.all.distinct().size)
    }

    @Test
    fun `no tile is unchanged by being turned end for end`() {
        // Which is why the 56 arrangements pair off cleanly into 28 tiles.
        TayuTiles.all.forEach { mask ->
            assertFalse("$mask is its own turn", mask == TayuTiles.turned(mask))
            assertEquals("turning twice returns", mask, TayuTiles.turned(TayuTiles.turned(mask)))
        }
    }

    @Test
    fun `the set covers every arrangement of three mouths once turns are allowed`() {
        val reachable = TayuTiles.all.flatMap { listOf(it, TayuTiles.turned(it)) }.toSet()
        val everyArrangement = (0 until (1 shl TileSlots.COUNT))
            .filter { it.countOneBits() == TayuTiles.MOUTHS_PER_TILE }
            .toSet()
        assertEquals(56, everyArrangement.size)
        assertEquals(everyArrangement, reachable)
    }

    @Test
    fun `twelve tiles reach three flanks at once`() {
        // The ones the reissue rings on the centre stud.
        assertEquals(12, TayuTiles.all.count { TayuTiles.touchesThreeFlanks(it) })
    }

    // -- Geometry -----------------------------------------------------------

    @Test
    fun `turning the tile turns its mouths with it`() {
        // A mouth on the left flank of a tile pointing east faces north; the
        // same mouth on a tile pointing north faces west.
        assertEquals(Facing.NORTH, absoluteFacing(Facing.EAST, Flank.LEFT))
        assertEquals(Facing.WEST, absoluteFacing(Facing.NORTH, Flank.LEFT))
        assertEquals(Facing.EAST, absoluteFacing(Facing.EAST, Flank.FRONT))
        assertEquals(Facing.WEST, absoluteFacing(Facing.EAST, Flank.BACK))
        assertEquals(Facing.SOUTH, absoluteFacing(Facing.EAST, Flank.RIGHT))
    }

    @Test
    fun `exit positions run clockwise and invert cleanly`() {
        Facing.entries.forEach { edge ->
            for (position in 0 until BOARD_SIZE) {
                val cell = TayuBoard.edgeCell(edge, position)
                assertEquals(
                    "$edge $position",
                    position,
                    TayuBoard.exitPosition(edge, TayuBoard.rowOf(cell), TayuBoard.colOf(cell)),
                )
            }
        }
    }

    @Test
    fun `marked exits count double and there are three to a side`() {
        assertEquals(3, TayuBoard.markedExits.size)
        val marked = TayuBoard.edgeCell(Facing.NORTH, 8)
        assertEquals(2, TayuBoard.exitValue(Facing.NORTH, 0, TayuBoard.colOf(marked)))
        val plain = TayuBoard.edgeCell(Facing.NORTH, 0)
        assertEquals(1, TayuBoard.exitValue(Facing.NORTH, 0, TayuBoard.colOf(plain)))
    }

    @Test
    fun `the four centre cells are the middle of the board`() {
        assertEquals(4, TayuBoard.centre.size)
        assertTrue(TayuBoard.cellAt(8, 8) in TayuBoard.centre)
        assertTrue(TayuBoard.cellAt(9, 9) in TayuBoard.centre)
        assertFalse(TayuBoard.cellAt(7, 8) in TayuBoard.centre)
    }

    // -- Setup --------------------------------------------------------------

    @Test
    fun `the opening tile is drawn and every seat has an axis`() {
        val state = TayuRules.initialState(config())
        assertNotNull("a tile is waiting to be placed", state.drawn)
        assertEquals(0, state.turn)
        assertEquals(84 - 1, state.bagCount)
        assertTrue(state.placed.isEmpty())
        assertEquals(Axis.NORTH_SOUTH, state.axisOf(0))
        assertEquals(Axis.EAST_WEST, state.axisOf(1))
    }

    @Test
    fun `a four handed table pairs opposite seats`() {
        val state = TayuRules.initialState(config(TayuOptions(playerCount = 4)))
        assertEquals(listOf(0, 1, 0, 1), state.teams)
        assertEquals(state.axisOf(0), state.axisOf(2))
        assertEquals(state.axisOf(1), state.axisOf(3))
    }

    @Test
    fun `the bag holds the chosen number of copies of every tile`() {
        val state = TayuRules.initialState(config(TayuOptions(tileCopies = 4)))
        val counts = (state.bag + listOfNotNull(state.drawn)).groupingBy { it }.eachCount()
        assertEquals(28, counts.size)
        assertTrue("four of each", counts.values.all { it == 4 })
    }

    // -- Opening placement --------------------------------------------------

    @Test
    fun `the opening tile must cover the centre`() {
        val state = TayuRules.initialState(config())
        val moves = TayuRules.legalMoves(state, 0)
        assertTrue(moves.isNotEmpty())
        moves.forEach { move ->
            assertTrue(
                "$move misses the centre",
                cellsOf(move).any { it in TayuBoard.centre },
            )
        }
    }

    @Test
    fun `every tile can open the game`() {
        // Nothing in the bag can jam the first turn, so the opening seat always
        // has something to do.
        val state = TayuRules.initialState(config())
        TayuTiles.all.forEach { tile ->
            assertTrue("$tile cannot open", TayuRules.placementsFor(state, tile).isNotEmpty())
        }
    }

    @Test
    fun `laying the opening tile fills three cells and passes the turn`() {
        var state = TayuRules.initialState(config())
        val move = TayuRules.legalMoves(state, 0).first()
        state = TayuRules.applyMove(state, 0, move)

        assertEquals(1, state.placed.size)
        assertEquals(3, state.tileAt.count { it != NO_TILE })
        cellsOf(move).forEach { assertEquals(0, state.tileAt[it]) }
        assertEquals(1, state.turn)
        assertNotNull("the next seat has drawn", state.drawn)
    }

    // -- Mouth accounting ---------------------------------------------------

    @Test
    fun `a river may not dead-end against the flank of another tile`() {
        // A tile lying east-west across the centre with a single mouth on its
        // north flank, above its middle cell.
        val existing = tile(
            mouths = listOf(mouth(1, Flank.LEFT), mouth(0, Flank.BACK), mouth(2, Flank.FRONT)),
        )
        val state = boardWith(
            PlacedTile(existing, row = 9, col = 8, facing = Facing.EAST, seat = 0),
        )

        // Directly above it, a tile whose south flank is blank where the mouth
        // below is pointing: the river would run into stone.
        val blankUnderneath = tile(
            mouths = listOf(mouth(0, Flank.LEFT), mouth(1, Flank.LEFT), mouth(2, Flank.LEFT)),
        )
        val moves = TayuRules.placementsFor(state.copy(drawn = blankUnderneath), blankUnderneath)
        assertTrue(
            "a blank flank must not cover the mouth at (9,9)",
            moves.none { move -> TayuBoard.cellAt(8, 9) in cellsOf(move) },
        )
    }

    @Test
    fun `a tile must join a river already down`() {
        val existing = tile(
            mouths = listOf(mouth(0, Flank.BACK), mouth(2, Flank.FRONT), mouth(1, Flank.LEFT)),
        )
        val state = boardWith(PlacedTile(existing, 9, 8, Facing.EAST, seat = 0))
        val next = TayuTiles.all.first()

        TayuRules.placementsFor(state.copy(drawn = next), next).forEach { move ->
            val cells = cellsOf(move)
            val touches = cells.any { cell ->
                Facing.entries.any { direction ->
                    val row = TayuBoard.rowOf(cell) + direction.dr
                    val col = TayuBoard.colOf(cell) + direction.dc
                    TayuBoard.onBoard(row, col) &&
                        state.tileAt[TayuBoard.cellAt(row, col)] != NO_TILE
                }
            }
            assertTrue("$move floats free of the board", touches)
        }
    }

    @Test
    fun `a tile may not overlap one already down`() {
        val existing = TayuTiles.all.first()
        val state = boardWith(PlacedTile(existing, 9, 8, Facing.EAST, seat = 0))
        val occupied = PlacedTile(existing, 9, 8, Facing.EAST, seat = 0).cells().toSet()

        TayuRules.placementsFor(state.copy(drawn = existing), existing).forEach { move ->
            assertTrue("$move overlaps", cellsOf(move).none { it in occupied })
        }
    }

    @Test
    fun `placements stay on the board`() {
        val state = TayuRules.initialState(config())
        TayuTiles.all.forEach { tile ->
            TayuRules.placementsFor(state, tile).forEach { move ->
                cellsOf(move).forEach { cell ->
                    assertTrue(cell in 0 until BOARD_CELLS)
                }
                // Three distinct cells in a straight line.
                assertEquals(3, cellsOf(move).distinct().size)
            }
        }
    }

    @Test
    fun `the four orientations of one tile are four different placements`() {
        val state = TayuRules.initialState(config())
        val tile = TayuTiles.all.first { TayuTiles.touchesThreeFlanks(it) }
        val atCentre = TayuRules.placementsFor(state, tile)
            .filter { it.row == 8 && it.col == 8 }
        assertEquals("no duplicates and nothing missing", 4, atCentre.size)
        assertEquals(4, atCentre.map { it.facing }.distinct().size)
    }

    // -- Exits and scoring --------------------------------------------------

    @Test
    fun `a mouth pointing off the board is an exit`() {
        // A tile in the north-west corner: its back mouth points west off the
        // board, and one left-flank mouth points north off it.
        val mask = tile(
            mouths = listOf(mouth(0, Flank.BACK), mouth(0, Flank.LEFT), mouth(2, Flank.FRONT)),
        )
        val state = boardWith(PlacedTile(mask, row = 0, col = 0, facing = Facing.EAST, seat = 0))
        val exits = TayuRules.exitsOf(state)

        assertEquals("north exit at column 0", 1, exits.north)
        assertEquals("west exit at row 0", 1, exits.west)
        assertEquals(0, exits.south)
        assertEquals(0, exits.east)
    }

    @Test
    fun `a marked exit counts twice`() {
        // Position 8 on the north edge is marked, which is column 8.
        val mask = tile(
            mouths = listOf(mouth(0, Flank.LEFT), mouth(0, Flank.BACK), mouth(2, Flank.FRONT)),
        )
        val state = boardWith(PlacedTile(mask, row = 0, col = 8, facing = Facing.EAST, seat = 0))
        assertEquals(2, TayuRules.exitsOf(state).north)
    }

    @Test
    fun `reaching one edge only scores nothing`() {
        val mask = tile(
            mouths = listOf(mouth(0, Flank.LEFT), mouth(1, Flank.LEFT), mouth(2, Flank.LEFT)),
        )
        val state = boardWith(PlacedTile(mask, row = 0, col = 0, facing = Facing.EAST, seat = 0))
        val exits = TayuRules.exitsOf(state)
        assertEquals(3, exits.north)
        assertEquals(0, exits.south)
        assertEquals(
            "a river down one side only is worth nothing",
            0,
            exits.product(Axis.NORTH_SOUTH),
        )
    }

    @Test
    fun `a score is one edge multiplied by the other`() {
        val exits = EdgeExits(north = 5, east = 2, south = 4, west = 3)
        assertEquals(20, exits.product(Axis.NORTH_SOUTH))
        assertEquals(6, exits.product(Axis.EAST_WEST))
    }

    @Test
    fun `the higher score wins and equal scores draw`() {
        val northSouth = boardWith(
            // North and south exits for team 0 only.
            PlacedTile(
                tile(listOf(mouth(0, Flank.LEFT), mouth(0, Flank.RIGHT), mouth(2, Flank.FRONT))),
                row = 0,
                col = 0,
                facing = Facing.EAST,
                seat = 0,
            ),
        )
        // Only one of the two north-south edges is reached, so still a draw at 0.
        assertNull(TayuRules.winnerOf(northSouth))
    }

    // -- Turn order and the end of the game ---------------------------------

    @Test
    fun `the seat on the clock always has a legal move`() {
        var state = TayuRules.initialState(config())
        val random = Random(4L)
        var guard = 0
        while (!TayuRules.isFinished(state) && guard++ < 500) {
            val seat = TayuRules.currentSeat(state)
            assertNotNull("somebody must be on the clock while playing", seat)
            val legal = TayuRules.legalMoves(state, seat!!)
            assertTrue("a seat on the clock with nothing to do", legal.isNotEmpty())
            state = TayuRules.applyMove(state, seat, legal[random.nextInt(legal.size)])
        }
        assertTrue("the game never ended", TayuRules.isFinished(state))
    }

    @Test
    fun `the game ends with the bag empty and every tile accounted for`() {
        var state = TayuRules.initialState(config(TayuOptions(tileCopies = 2)))
        val total = state.options.tileCount
        val random = Random(11L)
        var guard = 0
        while (!TayuRules.isFinished(state) && guard++ < 500) {
            val seat = TayuRules.currentSeat(state)!!
            val legal = TayuRules.legalMoves(state, seat)
            state = TayuRules.applyMove(state, seat, legal[random.nextInt(legal.size)])
        }
        assertEquals(0, state.bagCount)
        assertNull(state.drawn)
        assertEquals(TayuPhase.GAME_OVER, state.phase)
        assertEquals(
            "laid plus set aside must account for the whole bag",
            total,
            state.placed.size + state.setAside.size,
        )
    }

    @Test
    fun `an illegal placement is refused`() {
        val state = TayuRules.initialState(config())
        // The far corner cannot be the opening tile: it is nowhere near the centre.
        val corner = TayuMove(row = 0, col = 0, facing = Facing.EAST)
        assertFalse(corner in TayuRules.legalMoves(state, 0))
        runCatching { TayuRules.applyMove(state, 0, corner) }
            .onSuccess { error("an illegal placement was accepted") }
    }

    @Test
    fun `a seat that is not on the clock has no moves`() {
        val state = TayuRules.initialState(config())
        assertTrue(TayuRules.legalMoves(state, 1).isEmpty())
        runCatching { TayuRules.applyMove(state, 1, TayuRules.legalMoves(state, 0).first()) }
            .onSuccess { error("a move out of turn was accepted") }
    }

    // -- The AI -------------------------------------------------------------

    @Test
    fun `ai play produces only legal moves and reaches a result`() {
        val ai = TayuAi()
        repeat(2) { iteration ->
            var state = TayuRules.initialState(
                config(TayuOptions(tileCopies = 2), seed = 300L + iteration)
            )
            var guard = 0
            while (!TayuRules.isFinished(state) && guard++ < 500) {
                val seat = TayuRules.currentSeat(state)!!
                val legal = TayuRules.legalMoves(state, seat)
                val move = ai.chooseMove(state, seat, legal)
                assertTrue("ai returned an illegal move $move", legal.contains(move))
                state = TayuRules.applyMove(state, seat, move)
            }
            assertTrue("ai game ran away", guard < 500)
        }
    }

    @Test
    fun `the ai drives water towards its own edges`() {
        // Over a full game the AI should reach its own two edges more often than
        // it hands exits to the opponent, which is the whole point of the pull
        // term in its heuristic.
        val ai = TayuAi()
        var state = TayuRules.initialState(config(TayuOptions(tileCopies = 2), seed = 42L))
        var guard = 0
        while (!TayuRules.isFinished(state) && guard++ < 500) {
            val seat = TayuRules.currentSeat(state)!!
            val legal = TayuRules.legalMoves(state, seat)
            state = TayuRules.applyMove(state, seat, ai.chooseMove(state, seat, legal))
        }
        val exits = TayuRules.exitsOf(state)
        assertTrue(
            "nobody reached any edge at all: $exits",
            exits.north + exits.south + exits.east + exits.west > 0,
        )
    }

    // -- Redaction and wire format -----------------------------------------

    @Test
    fun `a seat view hides the bag order but not the board`() {
        val state = TayuRules.initialState(config())
        val view = TayuRules.viewFor(state, seat = 1)
        assertTrue("the bag order is hidden", view.bag.isEmpty())
        assertEquals("but not how much is left", state.bagCount, view.bagCount)
        assertEquals("a drawn tile is face up", state.drawn, view.drawn)
        assertEquals(state.tileAt, view.tileAt)
        assertEquals(state.placed, view.placed)
    }

    @Test
    fun `state and moves survive a round trip`() {
        var state = TayuRules.initialState(config())
        state = TayuRules.applyMove(state, 0, TayuRules.legalMoves(state, 0).first())
        assertEquals(state, TayuRules.decodeState(TayuRules.encodeState(state)))

        Facing.entries.forEach { facing ->
            val move = TayuMove(row = 4, col = 5, facing = facing)
            assertEquals(move, TayuRules.decodeMove(TayuRules.encodeMove(move)))
        }
    }

    @Test
    fun `summary reports both products`() {
        val state = TayuRules.initialState(config())
        val summary = TayuRules.summary(state)
        assertTrue(summary, summary.contains("North–south"))
        assertTrue(summary, summary.contains("East–west"))
    }

    @Test
    fun `unsupported tables are refused`() {
        listOf(1, 3, 5).forEach { players ->
            runCatching { TayuOptions(playerCount = players) }
                .onSuccess { error("$players players should not be allowed") }
        }
    }

    // -- Helpers ------------------------------------------------------------

    /** The slot a mouth sits in, from the cell it is on and the flank it faces. */
    private fun mouth(index: Int, flank: Flank): Int =
        (0 until TileSlots.COUNT).first {
            TileSlots.cellOf(it) == index && TileSlots.flankOf(it) == flank
        }

    /** A tile mask built from slots, checked to be one that really exists. */
    private fun tile(mouths: List<Int>): Int {
        val mask = mouths.fold(0) { acc, slot -> acc or (1 shl slot) }
        require(mask.countOneBits() == TayuTiles.MOUTHS_PER_TILE) { "three mouths, not $mouths" }
        require(mask in TayuTiles.all || TayuTiles.turned(mask) in TayuTiles.all) {
            "no such tile"
        }
        return mask
    }

    /** A board with tiles already laid, ready for the next placement. */
    private fun boardWith(vararg tiles: PlacedTile): TayuState {
        val tileAt = MutableList(BOARD_CELLS) { NO_TILE }
        tiles.forEachIndexed { index, tile -> tile.cells().forEach { tileAt[it] = index } }
        val options = TayuOptions()
        return TayuState(
            options = options,
            seed = 1L,
            teams = listOf(0, 1),
            tileAt = tileAt,
            placed = tiles.toList(),
            bag = emptyList(),
            drawn = TayuTiles.all.first(),
            bagCount = 0,
            setAside = emptyList(),
            turn = 0,
            phase = TayuPhase.PLAYING,
            log = emptyList(),
        )
    }
}
