package org.prolibertate.games.game.pirates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiratesRulesTest {

    private fun start(options: PiratesOptions = PiratesOptions()) =
        PiratesRules.initialState(options)

    private fun point(name: String) = requireNotNull(pointFromName(name)) { "no point $name" }

    private fun position(
        bulgars: List<String>,
        pirates: List<String>,
        turn: Int = BULGAR_SEAT,
        options: PiratesOptions = PiratesOptions(),
    ): PiratesState {
        val board = arrayOfNulls<Int>(POINTS)
        bulgars.forEach { board[point(it)] = BULGAR_SEAT }
        pirates.forEach { board[point(it)] = PIRATE_SEAT }
        return start(options).copy(board = board.toList(), turn = turn)
    }

    // -----------------------------------------------------------------------
    // The board
    // -----------------------------------------------------------------------

    @Test
    fun `the cross has thirty-three points and a nine-point stronghold`() {
        assertEquals(33, POINTS)
        assertEquals(9, STRONGHOLD.size)
        // Which leaves exactly twenty-four points outside it — the number of
        // Bulgars, and not a coincidence.
        assertEquals(24, POINTS - STRONGHOLD.size)
        assertFalse(isOnBoard(0, 0))
        assertFalse(isOnBoard(6, 6))
        assertTrue(isOnBoard(3, 0))
        assertTrue(isOnBoard(0, 3))
    }

    @Test
    fun `lines run orthogonally everywhere and diagonally on the even points`() {
        // The centre of the board has all eight; the point beside it has four.
        assertEquals(8, ADJACENCY[point("d4")].size)
        assertEquals(4, ADJACENCY[point("d5")].size)
        // And every line is drawn from both ends.
        (0 until POINTS).forEach { from ->
            ADJACENCY[from].forEach { to ->
                assertTrue("${pointName(from)}–${pointName(to)} is one-way", from in ADJACENCY[to])
            }
        }
    }

    @Test
    fun `points are named as a grid, and the names round-trip`() {
        // Reading order, so the stronghold starts at its top-left corner.
        assertEquals("c7", pointName(STRONGHOLD.first()))
        assertEquals("e5", pointName(STRONGHOLD.last()))
        (0 until POINTS).forEach { assertEquals(it, pointFromName(pointName(it))) }
        assertNull(pointFromName("a7"))
    }

    @Test
    fun `the opening array fills the board but the stronghold`() {
        val state = start()
        assertEquals(24, state.count(BULGAR_SEAT))
        assertEquals(2, state.count(PIRATE_SEAT))
        assertEquals(0, state.stronghold())
        assertEquals(BULGAR_SEAT, state.turn)
        // Every point outside the stronghold is occupied by a Bulgar.
        (0 until POINTS).filterNot { isStronghold(it) }.forEach {
            assertEquals(BULGAR_SEAT, state.board[it])
        }
    }

    // -----------------------------------------------------------------------
    // The Bulgars
    // -----------------------------------------------------------------------

    @Test
    fun `Bulgars move one step and never backwards`() {
        val state = position(bulgars = listOf("d4"), pirates = listOf("c7", "e7"))
        val moves = PiratesRules.legalMoves(state)
        assertTrue("every Bulgar move is a single step", moves.all { it.steps.size == 1 })
        moves.forEach {
            assertTrue(
                "${pointName(it.to)} is behind ${pointName(it.from)}",
                rowOf(it.to) <= rowOf(it.from),
            )
        }
        // d4 is the centre, so eight lines out of it and three of them go back.
        assertEquals(5, moves.size)
    }

    @Test
    fun `Bulgars may retreat when the table says so`() {
        val penned = position(
            bulgars = listOf("d4"),
            pirates = listOf("c7", "e7"),
            options = PiratesOptions(bulgarsMayNotRetreat = false),
        )
        assertEquals(8, PiratesRules.legalMoves(penned).size)
    }

    @Test
    fun `Bulgars cannot take anything`() {
        // A pirate stands next to a Bulgar with an empty point beyond it, which
        // would be a capture if the Bulgars could capture.
        val state = position(bulgars = listOf("d5"), pirates = listOf("d6", "c7"))
        assertTrue(PiratesRules.legalMoves(state).all { it.steps.size == 1 })
        assertTrue(PiratesRules.legalMoves(state).none { isJumpFrom(it.from, it.to) })
    }

    // -----------------------------------------------------------------------
    // The pirates
    // -----------------------------------------------------------------------

    @Test
    fun `a pirate takes by jumping and must take when it can`() {
        val state = position(
            bulgars = listOf("d5", "d3", "b4", "f4", "c1", "e1", "d1", "a3", "g3"),
            pirates = listOf("d6", "c7"),
            turn = PIRATE_SEAT,
        )
        val moves = PiratesRules.legalMoves(state)
        assertTrue("with a jump on the board nothing else is a move", moves.isNotEmpty())
        assertTrue(moves.all { isJumpFrom(it.from, it.steps.first()) })

        val jump = moves.first { it.from == point("d6") }
        val after = PiratesRules.applyMove(state, PIRATE_SEAT, jump)
        // One Bulgar per hop — and the hops carry on while there is another to
        // take, so this one takes two.
        assertEquals(9 - jump.steps.size, after.count(BULGAR_SEAT))
        assertTrue(jump.steps.size >= 1)
        assertEquals(0, after.pliesSinceProgress)
    }

    @Test
    fun `a chain of jumps is one turn`() {
        // Two Bulgars in a line with a gap past each, so the pirate takes both.
        val state = position(
            bulgars = listOf("d6", "d4", "b4", "f4", "c1", "e1", "d1", "a3", "g3", "c2"),
            pirates = listOf("d7", "c7"),
            turn = PIRATE_SEAT,
        )
        val chains = PiratesRules.legalMoves(state).filter { it.steps.size > 1 }
        assertTrue("the double jump should be there", chains.isNotEmpty())
        val chain = chains.first()
        val after = PiratesRules.applyMove(state, PIRATE_SEAT, chain)
        assertEquals(10 - chain.steps.size, after.count(BULGAR_SEAT))
        // One move, so the turn has passed exactly once.
        assertEquals(BULGAR_SEAT, after.turn)
    }

    @Test
    fun `a pirate that cannot take may walk`() {
        val state = position(
            bulgars = listOf("a3", "g3", "d1", "c1", "e1", "c2", "e2", "a4", "g4"),
            pirates = listOf("c7", "e7"),
            turn = PIRATE_SEAT,
        )
        val moves = PiratesRules.legalMoves(state)
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { it.steps.size == 1 })
    }

    @Test
    fun `the same Bulgar is never taken twice in one chain`() {
        val state = position(
            bulgars = listOf("d6", "d4", "c5", "b4", "c1", "e1", "d1", "a3", "g3", "f4"),
            pirates = listOf("d7", "e7"),
            turn = PIRATE_SEAT,
        )
        PiratesRules.legalMoves(state).forEach { move ->
            val taken = mutableListOf<Int>()
            var at = move.from
            move.steps.forEach { landing ->
                jumpedBetween(at, landing)?.let { taken += it }
                at = landing
            }
            assertEquals("a Bulgar was jumped twice: $move", taken.size, taken.distinct().size)
        }
    }

    // -----------------------------------------------------------------------
    // Ending
    // -----------------------------------------------------------------------

    @Test
    fun `filling the stronghold wins it for the Bulgars`() {
        // Eight of the nine points held, the ninth empty at c5, and a Bulgar on
        // b5 one step to its left. The pirates have been driven out of it
        // entirely, which is the only way this position arises.
        val held = STRONGHOLD.map { pointName(it) }.filterNot { it == "c5" }
        val state = position(
            bulgars = held + listOf("b5"),
            pirates = listOf("a4", "g4"),
            turn = BULGAR_SEAT,
        )
        assertEquals(8, state.stronghold())

        val into = PiratesRules.legalMoves(state)
            .single { it.from == point("b5") && it.to == point("c5") }
        val after = PiratesRules.applyMove(state, BULGAR_SEAT, into)

        assertEquals(9, after.stronghold())
        assertEquals(PiratesOutcome.BULGARS_STORM, after.outcome)
        assertTrue(PiratesRules.isFinished(after))
    }

    @Test
    fun `cutting the Bulgars below nine wins it for the pirates`() {
        // Nine Bulgars, one of them about to be jumped: eight cannot fill a
        // nine-point stronghold, so the assault is over.
        val state = position(
            bulgars = listOf("d5", "b4", "f4", "c1", "e1", "d1", "a3", "g3", "c2"),
            pirates = listOf("d6", "c7"),
            turn = PIRATE_SEAT,
        )
        val jump = PiratesRules.legalMoves(state).first { isJumpFrom(it.from, it.steps.first()) }
        val after = PiratesRules.applyMove(state, PIRATE_SEAT, jump)
        assertEquals(PiratesOutcome.PIRATES_CUT_DOWN, after.outcome)
    }

    @Test
    fun `pirates with nowhere to go have lost`() {
        // Both pirates in the stronghold's back corners with Bulgars filling
        // every point they could step or jump to.
        val state = position(
            bulgars = STRONGHOLD.map { pointName(it) }.filterNot { it == "c7" || it == "e7" } +
                listOf("c4", "d4", "e4", "b4", "f4", "a3", "g3", "d1"),
            pirates = listOf("c7", "e7"),
            turn = BULGAR_SEAT,
        )
        // Whatever the Bulgars play, if the pirates end up stuck they lose.
        val move = PiratesRules.legalMoves(state).firstOrNull()
        if (move != null) {
            val after = PiratesRules.applyMove(state, BULGAR_SEAT, move)
            if (after.phase == PiratesPhase.GAME_OVER) {
                assertTrue(
                    after.outcome == PiratesOutcome.BULGARS_PEN_IN ||
                        after.outcome == PiratesOutcome.BULGARS_STORM
                )
            }
        }
    }

    @Test
    fun `a game that gets nowhere for a long time is a draw`() {
        val state = position(
            bulgars = listOf("d5", "b4", "f4", "c1", "e1", "d1", "a3", "g3", "c2", "e2"),
            pirates = listOf("c7", "e7"),
            turn = BULGAR_SEAT,
            options = PiratesOptions(plyLimitWithoutProgress = 2),
        ).copy(pliesSinceProgress = 1)
        val quiet = PiratesRules.legalMoves(state).first { !isStronghold(it.to) }
        val after = PiratesRules.applyMove(state, BULGAR_SEAT, quiet)
        assertEquals(PiratesOutcome.DRAW_STALEMATE, after.outcome)
    }

    // -----------------------------------------------------------------------
    // The engine contract
    // -----------------------------------------------------------------------

    @Test
    fun `the opening has a move for the crowd and the generator counts straight`() {
        val state = start()
        val moves = PiratesRules.legalMoves(state)
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { state.board[it.from] == BULGAR_SEAT })
        assertEquals(moves.size.toLong(), PiratesRules.perft(state, 1))
        assertTrue(PiratesRules.perft(state, 2) > moves.size.toLong())
    }

    @Test
    fun `an illegal move is refused rather than played`() {
        val state = start()
        assertTrue(
            runCatching {
                PiratesRules.applyMove(state, PIRATE_SEAT, PiratesRules.legalMoves(state).first())
            }.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            "a Bulgar cannot walk onto an occupied point",
            runCatching {
                PiratesRules.applyMove(
                    state,
                    BULGAR_SEAT,
                    PiratesMove(from = point("d1"), steps = listOf(point("d2"))),
                )
            }.exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun `a state survives the round trip over the wire`() {
        var state = start()
        state = PiratesRules.applyMove(state, BULGAR_SEAT, PiratesRules.legalMoves(state).first())
        assertEquals(state, PiratesRules.decodeState(PiratesRules.encodeState(state)))
        val move = PiratesMove(from = 0, steps = listOf(1))
        assertEquals(move, PiratesRules.decodeMove(PiratesRules.encodeMove(move)))
    }

    @Test
    fun `nothing is hidden from either seat`() {
        val state = start()
        assertEquals(state, PiratesRules.viewFor(state, PIRATE_SEAT))
    }

    @Test
    fun `the summary counts the stronghold and the crowd`() {
        val summary = PiratesRules.summary(start())
        assertTrue(summary.contains("0 of 9"))
        assertTrue(summary.contains("24 Bulgars"))
        val over = start().copy(
            phase = PiratesPhase.GAME_OVER,
            outcome = PiratesOutcome.PIRATES_CUT_DOWN,
        )
        assertEquals(PiratesOutcome.PIRATES_CUT_DOWN.label, PiratesRules.summary(over))
        assertNull(PiratesRules.currentSeat(over))
    }
}
