package org.prolibertate.games.game.pegsolitaire

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig

class PegSolitaireRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(options: PegSolitaireOptions = PegSolitaireOptions()) = TableConfig(
        gameId = "pegsolitaire",
        seats = listOf(PlayerSlot(seat = 0, name = "P0", kind = PlayerKind.HUMAN_LOCAL, team = 0)),
        optionsJson = json.encodeToString(options),
        seed = 1L,
    )

    @Test
    fun `every board has the number of holes it claims`() {
        PegBoard.entries.forEach { board ->
            assertEquals(board.label, board.holeCount, board.holes().size)
        }
    }

    @Test
    fun `a triangle staggers its rows and a cross does not`() {
        // Six ways off a hole on a triangle, four on a square grid. This is the
        // only thing that differs between the two shapes.
        assertEquals(6, PegBoard.TRIANGLE_15.directions().size)
        assertEquals(6, PegBoard.TRIANGLE_21.directions().size)
        assertEquals(4, PegBoard.ENGLISH_CROSS.directions().size)
        assertEquals(4, PegBoard.FRENCH_CROSS.directions().size)
        assertEquals(4, PegBoard.DIAMOND_41.directions().size)
        // No diagonal creeps into a cross board.
        assertTrue(PegBoard.ENGLISH_CROSS.directions().all { (dr, dc) -> dr == 0 || dc == 0 })
    }

    @Test
    fun `the cross boards are cut where they should be`() {
        val english = PegBoard.ENGLISH_CROSS.holes()
        assertFalse("a two-by-two corner is gone", english.contains(Hole(0, 0)))
        assertFalse(english.contains(Hole(1, 1)))
        assertTrue(english.contains(Hole(0, 3)))
        assertTrue(english.contains(Hole(3, 3)))

        val french = PegBoard.FRENCH_CROSS.holes()
        // The shallower cut takes three holes per corner, so (1,1) survives.
        assertFalse(french.contains(Hole(0, 0)))
        assertFalse(french.contains(Hole(0, 1)))
        assertFalse(french.contains(Hole(1, 0)))
        assertTrue("the French cut leaves this one", french.contains(Hole(1, 1)))

        val diamond = PegBoard.DIAMOND_41.holes()
        assertTrue(diamond.contains(Hole(0, 4)))
        assertTrue(diamond.contains(Hole(4, 0)))
        assertFalse(diamond.contains(Hole(0, 0)))
    }

    @Test
    fun `a board opens one peg short`() {
        val state = PegSolitaireRules.initialState(config())
        assertEquals(PegBoard.TRIANGLE_15.holeCount - 1, state.remaining)
        assertFalse("the opening hole is empty", state.pegs.contains(Hole(0, 0)))
        assertEquals(0, state.jumps)
        assertNull(state.lastJump)
    }

    @Test
    fun `the tee game opens with exactly two jumps, both into the apex`() {
        val state = PegSolitaireRules.initialState(config())
        val jumps = PegSolitaireRules.legalJumps(state)
        assertEquals(2, jumps.size)
        assertTrue("every opening jump lands in the empty hole", jumps.all { it.to == Hole(0, 0) })
        assertEquals(setOf(Hole(2, 0), Hole(2, 2)), jumps.map { it.from }.toSet())
        // The jumped peg is the one between the ends, and it is never invented.
        assertEquals(setOf(Hole(1, 0), Hole(1, 1)), jumps.map { it.over }.toSet())
    }

    @Test
    fun `a jump moves one peg and takes the one it passed`() {
        val state = PegSolitaireRules.initialState(config())
        val jump = PegJump(from = Hole(2, 0), to = Hole(0, 0))
        val after = PegSolitaireRules.applyMove(state, 0, jump)
        assertFalse("the mover left", after.pegs.contains(Hole(2, 0)))
        assertFalse("the jumped peg was taken", after.pegs.contains(Hole(1, 0)))
        assertTrue("the mover arrived", after.pegs.contains(Hole(0, 0)))
        assertEquals(state.remaining - 1, after.remaining)
        assertEquals(1, after.jumps)
        assertEquals(jump, after.lastJump)
    }

    @Test
    fun `a jump needs a peg to pass and an empty hole to land in`() {
        val state = PegSolitaireRules.initialState(config())
        // Landing hole already occupied.
        assertThrows { PegSolitaireRules.applyMove(state, 0, PegJump(Hole(4, 0), Hole(2, 0))) }
        // Nothing to jump over: (1,0) is empty only after a jump, but (0,0) is
        // the opening hole, so a jump starting there has no peg to move.
        assertThrows { PegSolitaireRules.applyMove(state, 0, PegJump(Hole(0, 0), Hole(2, 0))) }
        // Straight off the board.
        assertThrows { PegSolitaireRules.applyMove(state, 0, PegJump(Hole(2, 0), Hole(2, 4))) }
    }

    @Test
    fun `only the one seat may play`() {
        val state = PegSolitaireRules.initialState(config())
        assertEquals(0, PegSolitaireRules.currentSeat(state))
        assertTrue(PegSolitaireRules.legalMoves(state, 1).isEmpty())
        assertThrows { PegSolitaireRules.applyMove(state, 1, PegJump(Hole(2, 0), Hole(0, 0))) }
    }

    @Test
    fun `a table for anything but one seat is refused`() {
        val twoSeats = TableConfig(
            gameId = "pegsolitaire",
            seats = (0..1).map {
                PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it)
            },
            optionsJson = json.encodeToString(PegSolitaireOptions()),
            seed = 1L,
        )
        assertThrows { PegSolitaireRules.initialState(twoSeats) }
    }

    @Test
    fun `one peg left is solved, and the stricter goal asks where`() {
        val loose = PegSolitaireOptions(goal = PegGoal.ONE_PEG)
        val strict = PegSolitaireOptions(goal = PegGoal.ONE_PEG_IN_START)
        val base = PegSolitaireRules.initialState(config(loose))

        val elsewhere = base.copy(pegs = setOf(Hole(4, 2)))
        assertTrue(elsewhere.solved)
        assertFalse("the strict goal wants it back where it started", elsewhere.copy(options = strict).solved)

        val atStart = base.copy(options = strict, pegs = setOf(Hole(0, 0)))
        assertTrue("the survivor is in the opening hole", atStart.solved)
    }

    @Test
    fun `a board with no jumps left is finished, solved or not`() {
        val state = PegSolitaireRules.initialState(config())
        // Two pegs far apart cannot reach each other, so nothing can jump.
        val stuck = state.copy(pegs = setOf(Hole(4, 0), Hole(4, 4)))
        assertTrue(PegSolitaireRules.isFinished(stuck))
        assertFalse(stuck.solved)
        assertNull(PegSolitaireRules.currentSeat(stuck))
        assertTrue(PegSolitaireRules.summary(stuck).contains("Stuck"))

        val won = state.copy(pegs = setOf(Hole(2, 1)))
        assertTrue(PegSolitaireRules.isFinished(won))
        assertTrue(won.solved)
    }

    @Test
    fun `an opening hole has to be a hole on the board`() {
        assertThrows { PegSolitaireOptions(board = PegBoard.TRIANGLE_15, startEmpty = Hole(9, 9)) }
        // A hole that exists on one board but not another.
        assertThrows {
            PegSolitaireOptions(board = PegBoard.ENGLISH_CROSS, startEmpty = Hole(0, 0))
        }
    }

    @Test
    fun `every board can be opened and played`() {
        // Each board deals, offers jumps, and runs to a finish without the rules
        // throwing. This is what catches a geometry that produces a board nobody
        // can move on at all.
        PegBoard.entries.forEach { board ->
            val state = PegSolitaireRules.initialState(
                config(PegSolitaireOptions(board = board)),
            )
            assertEquals(board.holeCount - 1, state.remaining)
            assertTrue(
                "${board.label} should have an opening jump",
                PegSolitaireRules.legalJumps(state).isNotEmpty(),
            )
            val end = playOut(state)
            assertTrue(PegSolitaireRules.isFinished(end))
            assertTrue("${board.label} should take pegs off", end.remaining < state.remaining)
        }
    }

    @Test
    fun `restart puts every peg back`() {
        val state = PegSolitaireRules.initialState(config())
        val played = PegSolitaireRules.applyMove(state, 0, PegJump(Hole(2, 0), Hole(0, 0)))
        val again = PegSolitaireRules.restart(played)
        assertEquals(state.pegs, again.pegs)
        assertEquals(0, again.jumps)
        assertNull(again.lastJump)
    }

    @Test
    fun `nothing is hidden from the only player`() {
        val state = PegSolitaireRules.initialState(config())
        assertEquals(state, PegSolitaireRules.viewFor(state, 0))
    }

    @Test
    fun `state and moves survive a round trip`() {
        PegBoard.entries.forEach { board ->
            val state = PegSolitaireRules.initialState(
                config(PegSolitaireOptions(board = board, goal = PegGoal.ONE_PEG_IN_START)),
            )
            assertEquals(state, PegSolitaireRules.decodeState(PegSolitaireRules.encodeState(state)))
        }
        val move: PegSolitaireMove = PegJump(Hole(2, 0), Hole(0, 0))
        assertEquals(move, PegSolitaireRules.decodeMove(PegSolitaireRules.encodeMove(move)))
    }

    /** Runs a board to a standstill using the AI, which never has to guess. */
    private fun playOut(from: PegSolitaireState): PegSolitaireState {
        var state = from
        // Each jump takes exactly one peg off, so the board cannot outlast its
        // own peg count — a loop that did would be a bug in applyMove.
        var guard = from.remaining + 1
        while (!PegSolitaireRules.isFinished(state) && guard-- > 0) {
            val legal = PegSolitaireRules.legalMoves(state, 0)
            state = PegSolitaireRules.applyMove(state, 0, PegSolitaireAi.chooseMove(state, 0, legal))
        }
        assertTrue("playing out should terminate", guard > 0)
        return state
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected the rules to reject that")
        } catch (expected: IllegalArgumentException) {
            // Rejected, which is the point.
        }
    }
}
