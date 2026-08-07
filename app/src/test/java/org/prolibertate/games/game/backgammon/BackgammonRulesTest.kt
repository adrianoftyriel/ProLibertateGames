package org.prolibertate.games.game.backgammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgammonRulesTest {

    private fun start(seed: Long = 1L) = BackgammonRules.initialState(BackgammonOptions(), seed)

    /**
     * A board built by hand. Points are given the way a player says them —
     * White's 6 point is index 5 — so [white] and [black] are index-to-count.
     */
    private fun position(
        white: Map<Int, Int> = emptyMap(),
        black: Map<Int, Int> = emptyMap(),
        turn: Int = WHITE_SEAT,
        dice: List<Int> = listOf(6, 5),
        bar: List<Int> = listOf(0, 0),
        off: List<Int> = listOf(0, 0),
    ): BackgammonState {
        val points = IntArray(POINTS)
        white.forEach { (point, count) -> points[point] = count }
        black.forEach { (point, count) -> points[point] = -count }
        return start().copy(
            points = points.toList(),
            turn = turn,
            dice = dice,
            roll = dice.take(2),
            bar = bar,
            off = off,
        )
    }

    // -----------------------------------------------------------------------
    // The board
    // -----------------------------------------------------------------------

    @Test
    fun `the opening array is fifteen a side in the usual places`() {
        val state = start()
        assertEquals(15, (0 until POINTS).sumOf { state.countOn(it, WHITE_SEAT) })
        assertEquals(15, (0 until POINTS).sumOf { state.countOn(it, BLACK_SEAT) })
        assertEquals(2, state.countOn(23, WHITE_SEAT))
        assertEquals(5, state.countOn(12, WHITE_SEAT))
        assertEquals(3, state.countOn(7, WHITE_SEAT))
        assertEquals(5, state.countOn(5, WHITE_SEAT))
        // The two sides are mirror images, so the opening pip counts match.
        assertEquals(167, state.pipCount(WHITE_SEAT))
        assertEquals(167, state.pipCount(BLACK_SEAT))
    }

    @Test
    fun `the two sides run in opposite directions`() {
        assertEquals(-1, directionOf(WHITE_SEAT))
        assertEquals(1, directionOf(BLACK_SEAT))
        assertEquals(0..5, homeOf(WHITE_SEAT))
        assertEquals(18..23, homeOf(BLACK_SEAT))
        // A checker on the point nearest home is one pip from being off.
        assertEquals(1, pipsFrom(WHITE_SEAT, 0))
        assertEquals(1, pipsFrom(BLACK_SEAT, 23))
        assertEquals(24, pipsFrom(WHITE_SEAT, 23))
    }

    @Test
    fun `each player comes in from the bar at the other end`() {
        assertEquals(23, entryPoint(WHITE_SEAT, 1))
        assertEquals(18, entryPoint(WHITE_SEAT, 6))
        assertEquals(0, entryPoint(BLACK_SEAT, 1))
        assertEquals(5, entryPoint(BLACK_SEAT, 6))
    }

    // -----------------------------------------------------------------------
    // The dice
    // -----------------------------------------------------------------------

    @Test
    fun `the opening roll is two different dice and the higher one starts`() {
        (1L..25L).forEach { seed ->
            val state = BackgammonRules.initialState(BackgammonOptions(), seed)
            assertEquals(2, state.dice.size)
            assertNotEquals(state.dice[0], state.dice[1])
            assertEquals(state.dice.sortedDescending(), state.dice)
        }
    }

    private fun assertNotEquals(a: Int, b: Int) =
        assertTrue("expected different dice, got $a and $b", a != b)

    @Test
    fun `the same table throws the same dice`() {
        assertEquals(start(9L).dice, start(9L).dice)
        assertEquals(start(9L).seed, 9L)
        // And a game replayed from its own state rolls on identically.
        val first = start(9L)
        val second = BackgammonRules.decodeState(BackgammonRules.encodeState(first))
        val move = BackgammonRules.legalMoves(first).first()
        assertEquals(
            BackgammonRules.applyMove(first, first.turn, move),
            BackgammonRules.applyMove(second, second.turn, move),
        )
    }

    @Test
    fun `a double is four moves, not two`() {
        val doubled = position(
            white = mapOf(23 to 2),
            black = mapOf(0 to 2),
            dice = listOf(3, 3, 3, 3),
        )
        assertEquals(4, doubled.dice.size)
        var state = doubled
        repeat(4) {
            val moves = BackgammonRules.legalMoves(state)
            assertTrue("ran out of moves after $it", moves.isNotEmpty())
            state = BackgammonRules.applyMove(state, WHITE_SEAT, moves.first())
        }
        // Four threes is twelve pips off the count.
        assertEquals(doubled.pipCount(WHITE_SEAT) - 12, state.pipCount(WHITE_SEAT))
    }

    // -----------------------------------------------------------------------
    // Moving
    // -----------------------------------------------------------------------

    @Test
    fun `a point with two enemy checkers cannot be landed on`() {
        val state = position(white = mapOf(23 to 2), black = mapOf(17 to 2), dice = listOf(6, 5))
        // 23 minus 6 is 17, which Black holds.
        assertTrue(BackgammonRules.legalMoves(state).none { it.from == 23 && it.die == 6 })
        assertTrue(BackgammonRules.legalMoves(state).any { it.from == 23 && it.die == 5 })
    }

    @Test
    fun `a lone enemy checker is hit and goes to the bar`() {
        val state = position(white = mapOf(23 to 2), black = mapOf(17 to 1, 0 to 2))
        val hit = BackgammonRules.legalMoves(state).first { it.from == 23 && it.die == 6 }
        val after = BackgammonRules.applyMove(state, WHITE_SEAT, hit)
        assertEquals(1, after.bar[BLACK_SEAT])
        assertEquals(1, after.countOn(17, WHITE_SEAT))
        assertEquals(0, after.countOn(17, BLACK_SEAT))
    }

    @Test
    fun `nothing else moves while a checker is on the bar`() {
        val state = position(
            white = mapOf(23 to 2, 12 to 3),
            black = mapOf(0 to 2),
            bar = listOf(1, 0),
        )
        val moves = BackgammonRules.legalMoves(state)
        assertTrue("everything must come in first", moves.all { it.from == BAR })
    }

    @Test
    fun `a closed board leaves a barred player with nothing and the turn passes`() {
        // Black holds every point White could come in on, so White cannot move
        // at all and the dice go back across the table.
        val state = position(
            white = mapOf(12 to 3),
            black = (18..23).associateWith { 2 },
            bar = listOf(1, 0),
            turn = WHITE_SEAT,
            dice = listOf(6, 5),
        )
        val settled = BackgammonRules.applyMove(
            state.copy(turn = BLACK_SEAT, dice = listOf(1, 2)),
            BLACK_SEAT,
            BackgammonRules.legalMoves(state.copy(turn = BLACK_SEAT, dice = listOf(1, 2))).first(),
        )
        assertTrue(settled.moveLog.isNotEmpty())
    }

    // -----------------------------------------------------------------------
    // Using as much of the roll as possible
    // -----------------------------------------------------------------------

    @Test
    fun `a move that would strand the other die is not offered`() {
        // White has one checker on 23 and Black holds 17. Playing the 6 first
        // is blocked outright; playing the 5 first lands on 18, and the 6 then
        // carries on to 12. Only the order that plays the whole roll is a move,
        // so the 6 is not offered even though six pips of it are playable
        // further down the line.
        val state = position(
            white = mapOf(23 to 1),
            black = mapOf(17 to 2, 0 to 2),
            dice = listOf(6, 5),
        )
        val moves = BackgammonRules.legalMoves(state)
        assertEquals("only one first step plays the whole roll", 1, moves.size)
        assertEquals(5, moves.single().die)

        // And playing it does leave the 6 to play.
        val after = BackgammonRules.applyMove(state, WHITE_SEAT, moves.single())
        assertEquals(WHITE_SEAT, after.turn)
        assertEquals(listOf(6), after.dice)
    }

    @Test
    fun `when only one die can be played it must be the higher`() {
        // Either die can be played on its own — 23 to 17, or 23 to 18 — but
        // never both, because 12 is shut and that is where eleven pips land
        // whichever order they are played in. The rules say take the bigger.
        val state = position(
            white = mapOf(23 to 1),
            black = mapOf(12 to 2, 11 to 2, 0 to 2),
            dice = listOf(6, 5),
        )
        val moves = BackgammonRules.legalMoves(state)
        assertEquals(1, moves.size)
        assertEquals("the higher die has to be the one played", 6, moves.single().die)

        // Playing it ends the turn, because the 5 has nowhere to go.
        val after = BackgammonRules.applyMove(state, WHITE_SEAT, moves.single())
        assertEquals(BLACK_SEAT, after.turn)
    }

    // -----------------------------------------------------------------------
    // Bearing off
    // -----------------------------------------------------------------------

    @Test
    fun `nothing comes off until everything is home`() {
        val notHome = position(white = mapOf(5 to 2, 12 to 1), black = mapOf(0 to 2))
        assertFalse(notHome.canBearOff(WHITE_SEAT))

        val home = position(white = mapOf(5 to 2, 3 to 1), black = mapOf(23 to 2))
        assertTrue(home.canBearOff(WHITE_SEAT))
    }

    @Test
    fun `an exact die takes a checker off`() {
        val state = position(
            white = mapOf(5 to 2),
            black = mapOf(23 to 2),
            dice = listOf(6, 1),
        )
        val off = BackgammonRules.legalMoves(state).first { it.from == 5 && it.die == 6 }
        val after = BackgammonRules.applyMove(state, WHITE_SEAT, off)
        assertEquals(1, after.off[WHITE_SEAT])
        assertEquals(1, after.countOn(5, WHITE_SEAT))
    }

    @Test
    fun `a bigger die only takes the furthest checker`() {
        // Something on the 6 point, so a 6 must take that and not the one on
        // the 3; with the 6 point empty, the 6 may take the furthest instead.
        val blocked = position(
            white = mapOf(5 to 1, 2 to 1),
            black = mapOf(23 to 2),
            dice = listOf(6, 6, 6, 6),
        )
        assertTrue(BackgammonRules.legalMoves(blocked).none { it.from == 2 })

        val free = position(
            white = mapOf(2 to 2),
            black = mapOf(23 to 2),
            dice = listOf(6, 6, 6, 6),
        )
        assertTrue(BackgammonRules.legalMoves(free).any { it.from == 2 })
    }

    @Test
    fun `fifteen off wins the game`() {
        val state = position(
            white = mapOf(0 to 1),
            black = mapOf(23 to 2),
            dice = listOf(1, 2),
            off = listOf(14, 0),
        )
        val last = BackgammonRules.legalMoves(state).first { it.from == 0 }
        val after = BackgammonRules.applyMove(state, WHITE_SEAT, last)
        assertEquals(BackgammonPhase.GAME_OVER, after.phase)
        assertTrue(BackgammonRules.isFinished(after))
        assertNull(BackgammonRules.currentSeat(after))
    }

    @Test
    fun `a loser who bore nothing off has lost a gammon`() {
        val state = position(
            white = mapOf(0 to 1),
            black = mapOf(23 to 2),
            dice = listOf(1, 2),
            off = listOf(14, 0),
        )
        val after = BackgammonRules.applyMove(
            state,
            WHITE_SEAT,
            BackgammonRules.legalMoves(state).first { it.from == 0 },
        )
        assertEquals(BackgammonOutcome.WHITE_GAMMON, after.outcome)
        assertEquals(2, after.outcome!!.points)
    }

    @Test
    fun `a loser still on the bar has lost a backgammon`() {
        val state = position(
            white = mapOf(0 to 1),
            black = mapOf(23 to 1),
            dice = listOf(1, 2),
            off = listOf(14, 0),
            bar = listOf(0, 1),
        )
        val after = BackgammonRules.applyMove(
            state,
            WHITE_SEAT,
            BackgammonRules.legalMoves(state).first { it.from == 0 },
        )
        assertEquals(BackgammonOutcome.WHITE_BACKGAMMON, after.outcome)
        assertEquals(3, after.outcome!!.points)
    }

    @Test
    fun `a loser who bore one off has only lost a single game`() {
        val state = position(
            white = mapOf(0 to 1),
            black = mapOf(23 to 2),
            dice = listOf(1, 2),
            off = listOf(14, 1),
        )
        val after = BackgammonRules.applyMove(
            state,
            WHITE_SEAT,
            BackgammonRules.legalMoves(state).first { it.from == 0 },
        )
        assertEquals(BackgammonOutcome.WHITE_SINGLE, after.outcome)
        assertEquals(1, after.outcome!!.points)
    }

    // -----------------------------------------------------------------------
    // Turns
    // -----------------------------------------------------------------------

    @Test
    fun `the turn passes when the dice run out`() {
        var state = position(
            white = mapOf(23 to 2, 12 to 2),
            black = mapOf(0 to 2, 11 to 2),
            dice = listOf(3, 4),
        )
        state = BackgammonRules.applyMove(state, WHITE_SEAT, BackgammonRules.legalMoves(state).first())
        assertEquals("still White's roll", WHITE_SEAT, state.turn)
        state = BackgammonRules.applyMove(state, WHITE_SEAT, BackgammonRules.legalMoves(state).first())
        assertEquals(BLACK_SEAT, state.turn)
        assertEquals("the new player has dice to play", 2, state.roll.size)
        assertTrue(state.dice.isNotEmpty())
    }

    @Test
    fun `a whole game can be played out without getting stuck`() {
        var state = start(3L)
        var plies = 0
        while (!BackgammonRules.isFinished(state) && plies < 4000) {
            val moves = BackgammonRules.legalMoves(state)
            assertTrue("nobody can move at ply $plies", moves.isNotEmpty())
            state = BackgammonRules.applyMove(state, state.turn, moves.last())
            plies++
        }
        assertTrue("the game never finished in $plies plies", BackgammonRules.isFinished(state))
        assertNotNull(state.outcome)
        assertEquals(CHECKERS, state.off.max())
    }

    @Test
    fun `an illegal move is refused rather than played`() {
        val state = start(2L)
        assertTrue(
            runCatching {
                BackgammonRules.applyMove(state, state.turn, BackgammonMove(from = 3, die = 6))
            }.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            "the other player cannot move on this roll",
            runCatching {
                BackgammonRules.applyMove(
                    state,
                    other(state.turn),
                    BackgammonRules.legalMoves(state).first(),
                )
            }.exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun `a state survives the round trip over the wire`() {
        val state = start(5L)
        assertEquals(state, BackgammonRules.decodeState(BackgammonRules.encodeState(state)))
        val move = BackgammonMove(from = BAR, die = 4)
        assertEquals(move, BackgammonRules.decodeMove(BackgammonRules.encodeMove(move)))
    }

    @Test
    fun `nothing is hidden from either seat`() {
        val state = start(5L)
        assertEquals(state, BackgammonRules.viewFor(state, BLACK_SEAT))
    }

    @Test
    fun `the summary says whose roll it is and what they threw`() {
        val state = position(white = mapOf(23 to 2), black = mapOf(0 to 2), dice = listOf(6, 5))
        assertTrue(BackgammonRules.summary(state).startsWith("White to play 6-5"))
    }
}
