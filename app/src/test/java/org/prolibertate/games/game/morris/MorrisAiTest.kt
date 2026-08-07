package org.prolibertate.games.game.morris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MorrisAiTest {

    private fun point(name: String) = requireNotNull(pointFromName(name)) { "no point $name" }

    private fun boardOf(white: List<String>, black: List<String>): List<Int?> {
        val board = arrayOfNulls<Int>(POINTS)
        white.forEach { board[point(it)] = WHITE_SEAT }
        black.forEach { board[point(it)] = BLACK_SEAT }
        return board.toList()
    }

    private fun position(
        white: List<String>,
        black: List<String>,
        turn: Int = WHITE_SEAT,
        options: MorrisOptions = MorrisOptions(level = MorrisLevel.CLUB),
    ) = MorrisRules.initialState(options).copy(
        phase = MorrisPhase.MOVING,
        placed = listOf(9, 9),
        turn = turn,
        board = boardOf(white, black),
    )

    private fun choose(state: MorrisState, level: MorrisLevel = MorrisLevel.CLUB): MorrisMove =
        MorrisAi(level).chooseMove(state, state.turn, MorrisRules.legalMoves(state))

    @Test
    fun `it always returns a move it is allowed to play`() {
        var state = MorrisRules.initialState(MorrisOptions(level = MorrisLevel.CASUAL))
        // A whole game, both sides played by the computer, which is the
        // cheapest way to find a position the search mishandles.
        var plies = 0
        while (!MorrisRules.isFinished(state) && plies < 300) {
            val legal = MorrisRules.legalMoves(state)
            assertTrue("stuck with no move at ply $plies", legal.isNotEmpty())
            val move = choose(state, MorrisLevel.CASUAL)
            assertTrue("the AI picked a move that is not legal: $move", move in legal)
            state = MorrisRules.applyMove(state, state.turn, move)
            plies++
        }
        // Whatever happened, it has to have been a game rather than a hang.
        assertTrue("the game never got anywhere in $plies plies", plies > 10)
    }

    @Test
    fun `it takes a mill that is there for the taking`() {
        // White holds both ends of the top edge and steps up from d6 to close
        // it. Black is scattered — no two of their pieces share a line — so
        // there is nothing to weigh against a free piece.
        val state = position(
            white = listOf("a7", "g7", "d6", "f6"),
            black = listOf("a4", "b2", "d3", "e4", "g1"),
        )
        val move = choose(state, MorrisLevel.STRONG)
        assertEquals(point("d7"), move.to)
        assertEquals(point("d6"), move.from)
        assertNotNull("closing the mill has to take something", move.remove)
    }

    @Test
    fun `it takes a piece it is allowed to take, not one standing in a mill`() {
        // Black's a-file mill is protected; three loose pieces are not. Black
        // keeps five afterwards, so this does not hand them a flying mill.
        val state = position(
            white = listOf("d7", "d6", "c5", "b6"),
            black = listOf("a7", "a4", "a1", "g1", "b2", "f2"),
        )
        val move = choose(state, MorrisLevel.STRONG)
        assertEquals(point("c5"), move.from)
        assertEquals(point("d5"), move.to)
        assertTrue(
            "a piece in a mill cannot be taken while a loose one is going spare",
            move.remove in setOf(point("g1"), point("b2"), point("f2")),
        )
    }

    @Test
    fun `it will not hand the opponent a flying mill for the sake of a piece`() {
        // Taking here would leave Black on three, flying, with their three
        // pieces already in a line — they would step out and back in for a
        // piece every second move, and White would lose the game for the sake
        // of winning one. The search has to see past the free piece.
        val state = position(
            white = listOf("d7", "d6", "c5", "b6"),
            black = listOf("a7", "a4", "a1", "g1"),
        )
        val move = choose(state, MorrisLevel.STRONG)
        assertTrue(
            "closing the mill here loses; expected anything else, got $move",
            move.remove == null,
        )
    }

    @Test
    fun `it finishes a won game rather than playing on`() {
        // Black is down to three, all of them takeable, and White can close a
        // mill: that is the game, and the search should see it.
        val state = position(
            white = listOf("d7", "d6", "c5", "a7"),
            black = listOf("g7", "g4", "g1"),
        )
        val move = choose(state, MorrisLevel.STRONG)
        val next = MorrisRules.applyMove(state, WHITE_SEAT, move)
        assertEquals(MorrisPhase.GAME_OVER, next.phase)
        assertEquals(MorrisOutcome.WHITE_WINS_REDUCED, next.outcome)
    }

    @Test
    fun `it blocks a mill about to be closed against it`() {
        // Black holds d7 and d5 and will step b6 into d6 for the mill. Neither
        // of the two pieces already on that line can close it themselves, so
        // d6 is the whole game — and f6 is the only White piece that can reach
        // it. White has no mill of its own to play instead.
        val state = position(
            white = listOf("f6", "d2", "a4", "g4"),
            black = listOf("d7", "d5", "b6", "e3", "c3", "g1"),
        )
        val move = choose(state, MorrisLevel.STRONG)
        assertEquals("d6 is the only square that matters", point("d6"), move.to)
    }

    @Test
    fun `the same position gives the same move twice over`() {
        // The host and a client both replay a game; if the AI wandered they
        // would end up looking at different boards.
        val state = MorrisRules.initialState(MorrisOptions(level = MorrisLevel.CLUB))
        val first = choose(state)
        val second = choose(state)
        assertEquals(first, second)
    }

    @Test
    fun `the opening does not take forever to think about`() {
        // Twenty-four choices a ply with nothing to tell them apart is the
        // widest this game ever gets. If the node budget is not doing its job
        // this is where it shows.
        val started = System.nanoTime()
        choose(MorrisRules.initialState(MorrisOptions(level = MorrisLevel.STRONG)))
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("the opening move took ${millis}ms", millis < 20_000)
    }
}
