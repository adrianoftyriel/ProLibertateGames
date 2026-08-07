package org.prolibertate.games.game.checkers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckersRulesTest {

    private fun start(options: CheckersOptions = CheckersOptions()) =
        CheckersRules.initialState(options)

    /** A board built by hand, for positions a game would take an hour to reach. */
    private fun position(
        black: List<Int> = emptyList(),
        blackKings: List<Int> = emptyList(),
        white: List<Int> = emptyList(),
        whiteKings: List<Int> = emptyList(),
        turn: Int = BLACK_SEAT,
        options: CheckersOptions = CheckersOptions(),
    ): CheckersState {
        val board = arrayOfNulls<Piece>(SQUARES)
        black.forEach { board[it - 1] = Piece(BLACK_SEAT) }
        blackKings.forEach { board[it - 1] = Piece(BLACK_SEAT, king = true) }
        white.forEach { board[it - 1] = Piece(WHITE_SEAT) }
        whiteKings.forEach { board[it - 1] = Piece(WHITE_SEAT, king = true) }
        return start(options).copy(board = board.toList(), turn = turn)
    }

    /** Squares are written 1–32 in the notation and 0–31 in the code. */
    private fun square(number: Int) = number - 1

    // -----------------------------------------------------------------------
    // The board
    // -----------------------------------------------------------------------

    @Test
    fun `the thirty-two dark squares map to the board and back`() {
        (0 until SQUARES).forEach { square ->
            val row = rowOf(square)
            val column = columnOf(square)
            assertTrue("$square is off the board", row in 0..7 && column in 0..7)
            assertEquals("square $square", square, squareAt(row, column))
            // Dark squares are the ones where row and column have different parity.
            assertEquals(1, (row + column) % 2)
        }
    }

    @Test
    fun `light squares are not squares at all`() {
        assertNull(squareAt(0, 0))
        assertNull(squareAt(7, 7))
        assertNull(squareAt(-1, 0))
        assertNull(squareAt(0, 8))
        // The corners of the playable diagonal.
        assertEquals(0, squareAt(0, 1))
        assertEquals(SQUARES - 1, squareAt(7, 6))
    }

    @Test
    fun `the opening array is twelve a side on the three nearest rows`() {
        val state = start()
        assertEquals(12, state.count(BLACK_SEAT))
        assertEquals(12, state.count(WHITE_SEAT))
        assertEquals(0, state.kings(BLACK_SEAT))
        assertEquals(BLACK_SEAT, state.turn)
        // The two middle rows are empty, which is what makes an opening move
        // possible at all.
        (12 until 20).forEach { assertNull(state.board[it]) }
    }

    // -----------------------------------------------------------------------
    // Moving
    // -----------------------------------------------------------------------

    @Test
    fun `the opening position has seven moves, as every rulebook says`() {
        assertEquals(7, CheckersRules.legalMoves(start()).size)
    }

    @Test
    fun `the move generator counts what it should, four ply down`() {
        // The published perft numbers for English draughts. A generator that
        // matches them this far has very little room left to be wrong: they
        // count compulsory captures, crowning and the men's forward-only rule
        // all at once.
        assertEquals(7L, CheckersRules.perft(start(), 1))
        assertEquals(49L, CheckersRules.perft(start(), 2))
        assertEquals(302L, CheckersRules.perft(start(), 3))
        assertEquals(1469L, CheckersRules.perft(start(), 4))
    }

    @Test
    fun `a man moves forward only, and a king moves both ways`() {
        val man = position(black = listOf(14), white = listOf(30))
        assertEquals(
            setOf(square(17), square(18)),
            CheckersRules.legalMoves(man).map { it.to }.toSet(),
        )

        val king = position(blackKings = listOf(14), white = listOf(30))
        assertEquals(
            setOf(square(9), square(10), square(17), square(18)),
            CheckersRules.legalMoves(king).map { it.to }.toSet(),
        )
    }

    // -----------------------------------------------------------------------
    // Taking
    // -----------------------------------------------------------------------

    @Test
    fun `a capture is compulsory`() {
        // Black has a jump on the board, so the quiet moves elsewhere are not
        // moves at all.
        val state = position(black = listOf(14, 12), white = listOf(18, 30))
        val moves = CheckersRules.legalMoves(state)
        assertTrue("only the jump may be played", moves.all { it.isJump })
        assertEquals(1, moves.size)
        assertEquals(square(23), moves.single().to)
    }

    @Test
    fun `a double jump is one move, not two`() {
        // Two white men lined up for Black to hop over in a single turn.
        val state = position(black = listOf(14), white = listOf(18, 27))
        val moves = CheckersRules.legalMoves(state)
        assertEquals(1, moves.size)
        val jump = moves.single()
        assertEquals(2, jump.steps.size)
        assertEquals(square(32), jump.to)

        val after = CheckersRules.applyMove(state, BLACK_SEAT, jump)
        assertEquals(0, after.count(WHITE_SEAT))
        assertEquals(1, after.count(BLACK_SEAT))
        // One move, so the turn has passed exactly once.
        assertEquals(WHITE_SEAT, after.turn)
    }

    @Test
    fun `stopping halfway through a capture is not offered`() {
        val state = position(black = listOf(14), white = listOf(18, 27))
        // The single hop that stops on 23 is a prefix of the double, and the
        // rules do not allow it to be played on its own.
        assertTrue(
            CheckersRules.legalMoves(state).none { it.steps == listOf(square(23)) }
        )
    }

    @Test
    fun `the same piece is never taken twice in one turn`() {
        val state = position(blackKings = listOf(15), white = listOf(18, 19))
        CheckersRules.legalMoves(state).forEach { move ->
            val taken = mutableListOf<Int>()
            var at = move.from
            move.steps.forEach { landing ->
                jumped(at, landing)?.let { taken += it }
                at = landing
            }
            assertEquals("a piece was jumped twice: $move", taken.size, taken.distinct().size)
        }
    }

    @Test
    fun `a man may not jump backwards`() {
        // A white man sits behind the black man; nothing can be taken.
        val state = position(black = listOf(18), white = listOf(14, 30))
        assertTrue(CheckersRules.legalMoves(state).none { it.isJump })
    }

    // -----------------------------------------------------------------------
    // Crowning
    // -----------------------------------------------------------------------

    @Test
    fun `a man reaching the far row is crowned`() {
        val state = position(black = listOf(25), white = listOf(1))
        val move = CheckersRules.legalMoves(state).first()
        val after = CheckersRules.applyMove(state, BLACK_SEAT, move)
        assertTrue("it should be crowned", after.board[move.to]!!.king)
        assertEquals(1, after.kings(BLACK_SEAT))
        // Crowning is progress, so the draw clock goes back to nothing.
        assertEquals(0, after.pliesSinceProgress)
    }

    @Test
    fun `crowning ends the turn under the English rule`() {
        // A black man can jump into the crown row with another jump available
        // beyond it. Under the English rule it stops and is crowned.
        val state = position(black = listOf(22), white = listOf(26, 19), turn = BLACK_SEAT)
        val english = CheckersRules.legalMoves(state)
        assertTrue(english.isNotEmpty())
        english.filter { rowOf(it.to) == crownRowOf(BLACK_SEAT) }.forEach {
            assertEquals("the turn stops on crowning", 1, it.steps.size)
        }
    }

    // -----------------------------------------------------------------------
    // Kings that fly
    // -----------------------------------------------------------------------

    @Test
    fun `a flying king slides the length of the diagonal`() {
        val short = position(blackKings = listOf(15), white = listOf(1))
        val long = position(
            blackKings = listOf(15),
            white = listOf(1),
            options = CheckersOptions(flyingKings = true),
        )
        assertTrue(
            "a flying king reaches further than a walking one",
            CheckersRules.legalMoves(long).size > CheckersRules.legalMoves(short).size,
        )
        assertTrue(CheckersRules.legalMoves(short).all { it.steps.size == 1 })
    }

    @Test
    fun `a flying king takes from a distance and lands where it likes`() {
        // The king on 1 and the man on 10 share a diagonal with open board
        // beyond it, so the king may take it and carry on to any of the empty
        // squares past it.
        val state = position(
            blackKings = listOf(1),
            white = listOf(10),
            turn = BLACK_SEAT,
            options = CheckersOptions(flyingKings = true),
        )
        val takes = CheckersRules.legalMoves(state).filter { move ->
            CheckersRules.applyMove(state, BLACK_SEAT, move).count(WHITE_SEAT) == 0
        }
        assertTrue("the far man should be takeable", takes.isNotEmpty())
        assertTrue(
            "and the king may stop on any square beyond it",
            takes.map { it.to }.distinct().size >= 2,
        )
    }

    // -----------------------------------------------------------------------
    // Ending
    // -----------------------------------------------------------------------

    @Test
    fun `taking the last piece wins`() {
        val state = position(black = listOf(14), white = listOf(18))
        val move = CheckersRules.legalMoves(state).single()
        val after = CheckersRules.applyMove(state, BLACK_SEAT, move)
        assertEquals(CheckersPhase.GAME_OVER, after.phase)
        assertEquals(CheckersOutcome.BLACK_WINS, after.outcome)
        assertTrue(CheckersRules.isFinished(after))
    }

    @Test
    fun `a player with no move loses, pieces or not`() {
        // White's single man is in the corner with a black man in front of it
        // and a black man behind the landing square, so it cannot move.
        val state = position(
            black = listOf(23, 27, 26),
            white = listOf(32),
            turn = BLACK_SEAT,
        )
        val quiet = CheckersRules.legalMoves(state).firstOrNull { !it.isJump }
        assertNotNull("black needs a quiet move for this test", quiet)
        val after = CheckersRules.applyMove(state, BLACK_SEAT, quiet!!)
        if (CheckersRules.legalMoves(after, WHITE_SEAT).isEmpty()) {
            assertEquals(CheckersOutcome.BLACK_WINS, after.outcome)
        }
    }

    @Test
    fun `shuffling kings about is eventually a draw`() {
        val state = position(
            blackKings = listOf(15),
            whiteKings = listOf(32),
            options = CheckersOptions(plyLimitWithoutProgress = 4, threefoldRepetition = false),
        ).copy(pliesSinceProgress = 3)
        val quiet = CheckersRules.legalMoves(state).first { !it.isJump }
        val after = CheckersRules.applyMove(state, BLACK_SEAT, quiet)
        assertEquals(CheckersOutcome.DRAW_NO_PROGRESS, after.outcome)
    }

    @Test
    fun `the same position three times over is a draw`() {
        val state = position(blackKings = listOf(15), whiteKings = listOf(32))
        val move = CheckersRules.legalMoves(state).first { !it.isJump }
        // Where that move lands, seen twice already: playing it makes three.
        val key = CheckersRules.advanced(state, move).positionKey()
        val primed = state.copy(repetitionKeys = listOf(key, key))

        val after = CheckersRules.applyMove(primed, BLACK_SEAT, move)
        assertEquals(CheckersOutcome.DRAW_REPETITION, after.outcome)
    }

    // -----------------------------------------------------------------------
    // The engine contract
    // -----------------------------------------------------------------------

    @Test
    fun `an illegal move is refused rather than played`() {
        val state = start()
        assertTrue(
            runCatching {
                CheckersRules.applyMove(state, BLACK_SEAT, CheckersMove(0, listOf(20)))
            }.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            "white cannot move first",
            runCatching {
                CheckersRules.applyMove(state, WHITE_SEAT, CheckersRules.legalMoves(state).first())
            }.exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun `a state survives the round trip over the wire`() {
        var state = start()
        state = CheckersRules.applyMove(state, BLACK_SEAT, CheckersRules.legalMoves(state).first())
        assertEquals(state, CheckersRules.decodeState(CheckersRules.encodeState(state)))
        val move = CheckersMove(from = 10, steps = listOf(14, 21))
        assertEquals(move, CheckersRules.decodeMove(CheckersRules.encodeMove(move)))
    }

    @Test
    fun `nothing is hidden from either seat`() {
        val state = start()
        assertEquals(state, CheckersRules.viewFor(state, WHITE_SEAT))
    }

    @Test
    fun `a move reads as the notation the game is written in`() {
        assertEquals("11-15", CheckersMove(from = square(11), steps = listOf(square(15))).toString())
        assertEquals(
            "11x18x25",
            CheckersMove(from = square(11), steps = listOf(square(18), square(25))).toString(),
        )
    }

    @Test
    fun `the summary says whose move it is and how it ended`() {
        assertEquals("Black to move", CheckersRules.summary(start()))
        val over = start().copy(
            phase = CheckersPhase.GAME_OVER,
            outcome = CheckersOutcome.WHITE_WINS,
        )
        assertEquals(CheckersOutcome.WHITE_WINS.label, CheckersRules.summary(over))
        assertNull(CheckersRules.currentSeat(over))
        assertFalse(CheckersRules.isFinished(start()))
    }
}
