package org.prolibertate.games.game.morris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MorrisRulesTest {

    private fun start(options: MorrisOptions = MorrisOptions()) = MorrisRules.initialState(options)

    /** Plays the given moves in turn, requiring each to be legal. */
    private fun play(state: MorrisState, vararg moves: MorrisMove): MorrisState =
        moves.fold(state) { current, move -> MorrisRules.applyMove(current, current.turn, move) }

    private fun place(name: String) = MorrisMove(to = point(name))

    private fun place(name: String, takes: String) =
        MorrisMove(to = point(name), remove = point(takes))

    private fun step(from: String, to: String) =
        MorrisMove(to = point(to), from = point(from))

    private fun step(from: String, to: String, takes: String) =
        MorrisMove(to = point(to), from = point(from), remove = point(takes))

    private fun point(name: String) = requireNotNull(pointFromName(name)) { "no point $name" }

    // -----------------------------------------------------------------------
    // The board
    // -----------------------------------------------------------------------

    @Test
    fun `the board is twenty-four points and sixteen mills`() {
        assertEquals(24, POINTS)
        assertEquals(16, MILLS.size)
        assertEquals(24, ADJACENCY.size)
        // Every mill is three distinct points, and every point is in two mills.
        MILLS.forEach { assertEquals(3, it.distinct().size) }
        (0 until POINTS).forEach { assertEquals("point $it", 2, MILLS_THROUGH[it].size) }
    }

    @Test
    fun `corners have two neighbours and mid-edge points three or four`() {
        (0 until POINTS).forEach { point ->
            val expected = when {
                !isMidEdge(point) -> 2
                // The middle square's mid-edge points have a ladder each way.
                ringOf(point) == 1 -> 4
                else -> 3
            }
            assertEquals(pointName(point), expected, ADJACENCY[point].size)
        }
    }

    @Test
    fun `adjacency is mutual`() {
        (0 until POINTS).forEach { point ->
            ADJACENCY[point].forEach { neighbour ->
                assertTrue(
                    "${pointName(point)} sees ${pointName(neighbour)} but not the other way",
                    point in ADJACENCY[neighbour],
                )
            }
        }
    }

    @Test
    fun `points are named as the game is written down`() {
        // The four corners of the outer square, and the centre of the top edge.
        assertEquals("a7", pointName(pointOf(0, 0)))
        assertEquals("d7", pointName(pointOf(0, 1)))
        assertEquals("g7", pointName(pointOf(0, 2)))
        assertEquals("g1", pointName(pointOf(0, 4)))
        assertEquals("a1", pointName(pointOf(0, 6)))
        // The middle of the board is a gap, not a point: the inner square's
        // mid-edge points are the nearest thing to it.
        assertEquals("d6", pointName(pointOf(1, 1)))
        assertEquals("d5", pointName(pointOf(2, 1)))
        assertNull(pointFromName("d4"))
        (0 until POINTS).forEach { assertEquals(it, pointFromName(pointName(it))) }
    }

    // -----------------------------------------------------------------------
    // Placing
    // -----------------------------------------------------------------------

    @Test
    fun `the game opens with twenty-four placements and white to move`() {
        val state = start()
        assertEquals(MorrisPhase.PLACING, state.phase)
        assertEquals(WHITE_SEAT, state.turn)
        assertEquals(24, MorrisRules.legalMoves(state).size)
        assertEquals(9, state.inHand(WHITE_SEAT))
        assertEquals(0, state.onBoard(WHITE_SEAT))
    }

    @Test
    fun `placing takes a piece from the hand and passes the turn`() {
        val state = play(start(), place("a7"))
        assertEquals(BLACK_SEAT, state.turn)
        assertEquals(8, state.inHand(WHITE_SEAT))
        assertEquals(1, state.onBoard(WHITE_SEAT))
        assertEquals(23, MorrisRules.legalMoves(state).size)
    }

    @Test
    fun `placing continues until every piece is on the board`() {
        // Nine each, placed alternately down opposite edges of the board.
        val white = listOf("a7", "d7", "g4", "g1", "d3", "b6", "f6", "c5", "e5")
        val black = listOf("a4", "a1", "d1", "g7", "b4", "f4", "d2", "c3", "e3")
        var state = start()
        for (index in 0 until 9) {
            state = play(state, place(white[index]))
            state = play(state, place(black[index]))
        }
        assertEquals(MorrisPhase.MOVING, state.phase)
        assertEquals(0, state.inHand(WHITE_SEAT))
        assertEquals(0, state.inHand(BLACK_SEAT))
        // Nobody built a mill, so all eighteen are still standing.
        assertEquals(9, state.onBoard(WHITE_SEAT))
        assertEquals(9, state.onBoard(BLACK_SEAT))
        assertTrue(MorrisRules.legalMoves(state).all { it.from != null })
    }

    // -----------------------------------------------------------------------
    // Mills
    // -----------------------------------------------------------------------

    @Test
    fun `closing a mill takes an enemy piece`() {
        val state = play(
            start(),
            place("a7"), place("a4"),
            place("d7"), place("a1"),
            // a7-d7-g7 is a mill, so this placement carries a removal.
            place("g7", takes = "a4"),
        )
        assertEquals(3, state.onBoard(WHITE_SEAT))
        assertEquals(1, state.onBoard(BLACK_SEAT))
        assertEquals(0, state.pliesSinceMill)
        assertTrue(state.isInMill(point("d7")))
    }

    @Test
    fun `a mill must take something when there is anything to take`() {
        val opening = play(
            start(),
            place("a7"), place("a4"),
            place("d7"), place("a1"),
        )
        val millMoves = MorrisRules.legalMoves(opening).filter { it.to == point("g7") }
        assertEquals(2, millMoves.size)
        assertTrue("the mill has to be paid for", millMoves.all { it.remove != null })
        assertEquals(setOf(point("a4"), point("a1")), millMoves.mapNotNull { it.remove }.toSet())
    }

    @Test
    fun `a piece standing in a mill is spared while another is available`() {
        // Black holds a mill down the a-file and one loose piece on g1. White
        // steps c5 to d5 to close d7-d6-d5, and only the loose piece may be
        // taken for it.
        val state = start().copy(
            phase = MorrisPhase.MOVING,
            placed = listOf(9, 9),
            board = boardOf(
                white = listOf("d7", "d6", "c5", "b6"),
                black = listOf("a7", "a4", "a1", "g1"),
            ),
        )
        // Only the step from c5 closes the mill; d6 can reach d5 too, but the
        // piece it would need is the one making the move.
        val choices = MorrisRules.legalMoves(state)
            .filter { it.from == point("c5") && it.to == point("d5") }
        assertEquals(1, choices.size)
        assertEquals(point("g1"), choices.single().remove)
    }

    @Test
    fun `when every enemy piece is in a mill any of them may be taken`() {
        // Black's only three pieces are the a-file mill, so the rule that
        // spares a milled piece has to give way — otherwise nothing could ever
        // be taken and the game could not end.
        val state = start().copy(
            phase = MorrisPhase.MOVING,
            placed = listOf(9, 9),
            board = boardOf(
                white = listOf("d7", "d6", "c5", "b6"),
                black = listOf("a7", "a4", "a1"),
            ),
        )
        val choices = MorrisRules.legalMoves(state)
            .filter { it.from == point("c5") && it.to == point("d5") }
        assertEquals(3, choices.size)
        assertEquals(
            setOf(point("a7"), point("a4"), point("a1")),
            choices.mapNotNull { it.remove }.toSet(),
        )
    }

    @Test
    fun `a mill needs three pieces, and the one that just left is not one of them`() {
        // White holds two of the top edge and slides one of them along that
        // same line. a7 to d7 leaves a7 empty, so it is two in a row, not a
        // mill — which is the whole reason the vacated point is excluded.
        val state = start().copy(
            phase = MorrisPhase.MOVING,
            placed = listOf(9, 9),
            board = boardOf(
                white = listOf("a7", "g7", "b6"),
                black = listOf("g4", "g1", "d1"),
            ),
        )
        val slide = MorrisRules.legalMoves(state).single {
            it.from == point("a7") && it.to == point("d7")
        }
        assertNull(slide.remove)
    }

    @Test
    fun `stepping out of a mill takes nothing, and stepping back in pays again`() {
        var state = start().copy(
            phase = MorrisPhase.MOVING,
            placed = listOf(9, 9),
            board = boardOf(
                white = listOf("a7", "d7", "g7", "b6"),
                black = listOf("g4", "g1", "d1", "b2"),
            ),
        )

        val out = MorrisRules.legalMoves(state).filter { it.from == point("d7") }
        assertTrue(out.isNotEmpty())
        assertTrue("breaking your own mill is not a mill", out.all { it.remove == null })

        state = play(state, step("d7", "d6"))
        state = play(state, step("b2", "b4"))

        val back = MorrisRules.legalMoves(state).filter {
            it.from == point("d6") && it.to == point("d7")
        }
        // Re-forming the same mill pays again — that is the standard rule, and
        // the piece that closes it is one that was not there a moment ago.
        assertTrue(back.isNotEmpty())
        assertTrue(back.all { it.remove != null })
        assertEquals(
            "any of Black's four, none of which is in a mill",
            4,
            back.size,
        )
    }

    // -----------------------------------------------------------------------
    // Moving, flying, and the end of the game
    // -----------------------------------------------------------------------

    @Test
    fun `a piece steps along a line and not across the board`() {
        val state = start().copy(
            phase = MorrisPhase.MOVING,
            placed = listOf(9, 9),
            board = boardOf(
                white = listOf("a7"),
                black = listOf("g7", "g4", "g1", "d1"),
            ),
        )
        val moves = MorrisRules.legalMoves(state)
        // a7 touches d7 and a4 only.
        assertEquals(setOf(point("d7"), point("a4")), moves.map { it.to }.toSet())
    }

    @Test
    fun `three pieces may fly, unless the table says otherwise`() {
        val board = boardOf(white = listOf("a7", "a4", "a1"), black = listOf("g7", "g4", "g1"))
        val flying = start().copy(phase = MorrisPhase.MOVING, placed = listOf(9, 9), board = board)
        // Eighteen empty points, three pieces, and no line to follow.
        assertEquals(18 * 3, MorrisRules.legalMoves(flying).size)

        val grounded = flying.copy(options = flying.options.copy(flyingWithThree = false))
        assertTrue(MorrisRules.legalMoves(grounded).size < 18 * 3)
        assertTrue(
            "without flying a piece still only steps to a neighbour",
            MorrisRules.legalMoves(grounded).all { it.to in ADJACENCY[it.from!!] },
        )
    }

    @Test
    fun `taking a third piece ends the game`() {
        // White closes a mill and takes Black's fourth piece, leaving two.
        val state = start().copy(
            phase = MorrisPhase.MOVING,
            placed = listOf(9, 9),
            board = boardOf(
                white = listOf("d7", "d6", "c5", "a7"),
                black = listOf("g7", "g4", "g1"),
            ),
        )
        val mill = MorrisRules.legalMoves(state).first { it.remove != null }
        val ended = MorrisRules.applyMove(state, WHITE_SEAT, mill)
        assertEquals(MorrisPhase.GAME_OVER, ended.phase)
        assertEquals(MorrisOutcome.WHITE_WINS_REDUCED, ended.outcome)
        assertTrue(MorrisRules.isFinished(ended))
        assertTrue(MorrisRules.legalMoves(ended).isEmpty())
    }

    @Test
    fun `a player with nowhere to go loses`() {
        // Black holds the three top-left corners, one to a square, and White
        // stands on every point they touch. Flying is off, so Black has no
        // move at all once the turn comes round.
        val state = start().copy(
            options = MorrisOptions(flyingWithThree = false),
            phase = MorrisPhase.MOVING,
            placed = listOf(9, 9),
            turn = WHITE_SEAT,
            board = boardOf(
                white = listOf("d7", "a4", "d6", "b4", "d5", "c4", "g1"),
                black = listOf("a7", "b6", "c5"),
            ),
        )
        // White plays a quiet move away on the far side; Black is then on the
        // clock with nothing to play.
        val quiet = MorrisRules.legalMoves(state).first { it.from == point("g1") }
        val ended = MorrisRules.applyMove(state, WHITE_SEAT, quiet)
        assertEquals(MorrisOutcome.WHITE_WINS_BLOCKED, ended.outcome)
    }

    @Test
    fun `the game is drawn when no mill has been closed for a long time`() {
        val state = start().copy(
            options = MorrisOptions(plyLimitWithoutMill = 4, flyingWithThree = false),
            phase = MorrisPhase.MOVING,
            placed = listOf(9, 9),
            pliesSinceMill = 3,
            board = boardOf(white = listOf("a7", "a4", "g4"), black = listOf("g7", "g1", "d1")),
        )
        val shuffle = MorrisRules.legalMoves(state).first { it.remove == null }
        val ended = MorrisRules.applyMove(state, WHITE_SEAT, shuffle)
        assertEquals(MorrisOutcome.DRAW_NO_MILL, ended.outcome)
    }

    @Test
    fun `the same position three times over is a draw`() {
        var state = start().copy(
            phase = MorrisPhase.MOVING,
            placed = listOf(9, 9),
            board = boardOf(
                white = listOf("a7", "a4", "g4", "f4"),
                black = listOf("g7", "g1", "d1", "b2"),
            ),
        )
        state = state.copy(repetitionKeys = listOf(state.positionKey()))

        // Both sides walk a piece out and back twice over, which returns the
        // position to the board it started from for the third time.
        val cycle = listOf(
            step("f4", "f6"), step("b2", "b4"),
            step("f6", "f4"), step("b4", "b2"),
        )
        var moves = 0
        for (round in 0 until 2) {
            for (move in cycle) {
                if (state.phase == MorrisPhase.GAME_OVER) break
                state = MorrisRules.applyMove(state, state.turn, move)
                moves++
            }
        }
        assertEquals(MorrisOutcome.DRAW_REPETITION, state.outcome)
        assertTrue("it should not take more than two full cycles", moves <= 8)
    }

    // -----------------------------------------------------------------------
    // The engine contract
    // -----------------------------------------------------------------------

    @Test
    fun `an illegal move is refused rather than played`() {
        val state = play(start(), place("a7"))
        // Black tries to place on a point White has taken.
        val onTop = runCatching { MorrisRules.applyMove(state, BLACK_SEAT, place("a7")) }
        assertTrue(onTop.exceptionOrNull() is IllegalArgumentException)
        // White tries to move out of turn.
        val outOfTurn = runCatching { MorrisRules.applyMove(state, WHITE_SEAT, place("d7")) }
        assertTrue(outOfTurn.exceptionOrNull() is IllegalArgumentException)
        // A mill claimed where there is none.
        val unearned = runCatching {
            MorrisRules.applyMove(state, BLACK_SEAT, place("d7", takes = "a7"))
        }
        assertTrue(unearned.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a state survives the round trip over the wire`() {
        val state = play(
            start(),
            place("a7"), place("a4"),
            place("d7"), place("a1"),
            place("g7", takes = "a4"),
        )
        val restored = MorrisRules.decodeState(MorrisRules.encodeState(state))
        assertEquals(state, restored)

        val move = step("d7", "d6", takes = "a1")
        assertEquals(move, MorrisRules.decodeMove(MorrisRules.encodeMove(move)))
    }

    @Test
    fun `nothing is hidden from either seat`() {
        val state = play(start(), place("a7"))
        assertEquals(state, MorrisRules.viewFor(state, BLACK_SEAT))
        assertEquals(state, MorrisRules.viewFor(state, WHITE_SEAT))
    }

    @Test
    fun `the summary says whose turn it is and how the game ended`() {
        assertTrue(MorrisRules.summary(start()).startsWith("White to place"))
        val moved = start().copy(phase = MorrisPhase.MOVING, placed = listOf(9, 9))
        assertEquals("White to move", MorrisRules.summary(moved))
        val over = moved.copy(
            phase = MorrisPhase.GAME_OVER,
            outcome = MorrisOutcome.BLACK_WINS_BLOCKED,
        )
        assertEquals(MorrisOutcome.BLACK_WINS_BLOCKED.label, MorrisRules.summary(over))
        assertNull(MorrisRules.currentSeat(over))
        assertNotNull(MorrisRules.currentSeat(moved))
    }

    @Test
    fun `every generated move is legal and every legal move can be played`() {
        // Two plies of the placing phase, exhaustively: the opening is where
        // the move count is widest and a mistake would hide the longest.
        var checked = 0
        val first = start()
        for (opening in MorrisRules.legalMoves(first)) {
            val afterFirst = MorrisRules.applyMove(first, WHITE_SEAT, opening)
            assertEquals(23, MorrisRules.legalMoves(afterFirst).size)
            for (reply in MorrisRules.legalMoves(afterFirst)) {
                MorrisRules.applyMove(afterFirst, BLACK_SEAT, reply)
                checked++
            }
        }
        assertEquals(24 * 23, checked)
    }

    @Test
    fun `the move generator counts what it should three plies down`() {
        // 24 openings, 23 replies each, and 22 for the third — no mill can be
        // closed inside three plies, so every count is exact and independent of
        // the rest of the rules.
        assertEquals(24L, MorrisRules.perft(start(), 1))
        assertEquals(24L * 23, MorrisRules.perft(start(), 2))
        assertEquals(24L * 23 * 22, MorrisRules.perft(start(), 3))
    }

    /** Builds a board from point names, for positions no sane game would reach quickly. */
    private fun boardOf(white: List<String>, black: List<String>): List<Int?> {
        val board = arrayOfNulls<Int>(POINTS)
        white.forEach { board[point(it)] = WHITE_SEAT }
        black.forEach { board[point(it)] = BLACK_SEAT }
        return board.toList()
    }

    @Test
    fun `a shorter game can be dealt out with fewer pieces`() {
        var state = start(MorrisOptions(piecesEach = 3))
        assertEquals(3, state.inHand(WHITE_SEAT))
        state = play(state, place("a7"), place("g7"), place("a4"), place("g4"))
        assertEquals(MorrisPhase.PLACING, state.phase)
        state = play(state, place("b6"))
        assertEquals(MorrisPhase.PLACING, state.phase)
        state = play(state, place("f6"))
        assertEquals(MorrisPhase.MOVING, state.phase)
        assertFalse(MorrisRules.legalMoves(state).any { it.from == null })
    }
}
