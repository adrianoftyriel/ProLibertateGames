package org.prolibertate.games.game.chess

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

class ChessRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(options: ChessOptions = ChessOptions()) = TableConfig(
        gameId = "chess",
        seats = (0 until 2).map {
            PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it)
        },
        optionsJson = json.encodeToString(options),
        seed = 1L,
    )

    private fun position(fen: String, options: ChessOptions = ChessOptions()) =
        ChessRules.fromPosition(options, fen)

    private fun ChessState.moveTo(notation: String): ChessState {
        val move = ChessRules.legalMoves(this).firstOrNull { it.toString() == notation }
        assertNotNull("$notation is not legal in ${toFen()}", move)
        return ChessRules.applyMove(this, turnSeat, move!!)
    }

    // -- Squares and FEN ----------------------------------------------------

    @Test
    fun `squares are numbered the way a FEN reads`() {
        assertEquals(0, squareFromName("a8"))
        assertEquals(7, squareFromName("h8"))
        assertEquals(56, squareFromName("a1"))
        assertEquals(63, squareFromName("h1"))
        (0 until 64).forEach { assertEquals(it, squareFromName(squareName(it))) }
    }

    @Test
    fun `a1 is dark and h1 is light`() {
        assertFalse(isLightSquare(squareFromName("a1")))
        assertTrue(isLightSquare(squareFromName("h1")))
        assertTrue(isLightSquare(squareFromName("e4")))
    }

    @Test
    fun `the opening array round trips through FEN`() {
        val state = ChessRules.initialState(config())
        assertEquals(START_FEN, state.toFen())
        assertEquals(32, state.board.count { it != null })
        assertEquals("white starts", 0, state.turnSeat)
    }

    @Test
    fun `every published test position parses back to itself`() {
        PERFT_POSITIONS.forEach { (fen, _) ->
            assertEquals(fen, position(fen).toFen())
        }
    }

    // -- Perft --------------------------------------------------------------
    //
    // These counts are published and exact. A generator that matches them has
    // essentially nowhere left to hide a rule it gets wrong: the positions are
    // chosen to exercise castling through check, en passant that would expose
    // a king, promotion with capture, and pins.

    @Test
    fun `perft matches the published counts from the opening array`() {
        val state = ChessRules.initialState(config())
        assertEquals(20L, ChessRules.perft(state, 1))
        assertEquals(400L, ChessRules.perft(state, 2))
        assertEquals(8_902L, ChessRules.perft(state, 3))
        assertEquals(197_281L, ChessRules.perft(state, 4))
    }

    @Test
    fun `perft matches the published counts for the standard test positions`() {
        PERFT_POSITIONS.forEach { (fen, counts) ->
            val state = position(fen)
            counts.forEachIndexed { index, expected ->
                assertEquals(
                    "perft(${index + 1}) for $fen",
                    expected,
                    ChessRules.perft(state, index + 1),
                )
            }
        }
    }

    // -- Individual rules ---------------------------------------------------

    @Test
    fun `a pawn on its home rank may step one or two`() {
        val state = ChessRules.initialState(config())
        val fromE2 = ChessRules.legalMoves(state).filter { squareName(it.from) == "e2" }
        assertEquals(setOf("e2e3", "e2e4"), fromE2.map { it.toString() }.toSet())
    }

    @Test
    fun `a double step can be answered en passant, and only immediately`() {
        var state = position("4k3/8/8/8/4p3/8/3P4/4K3 w - - 0 1")
        state = state.moveTo("d2d4")
        assertEquals("the skipped square is the target", "d3", squareName(state.enPassant!!))

        val enPassant = ChessRules.legalMoves(state).first { it.toString() == "e4d3" }
        val after = ChessRules.applyMove(state, state.turnSeat, enPassant)
        assertNull("the pawn that stepped two is gone", after.board[squareFromName("d4")])
        assertNotNull("and the capturer stands behind it", after.board[squareFromName("d3")])

        // Waiting a move loses the right.
        var later = position("4k3/8/8/8/4p3/8/3P4/4K3 w - - 0 1").moveTo("d2d4")
        later = later.moveTo("e8d8")
        later = later.moveTo("e1d1")
        assertTrue(ChessRules.legalMoves(later).none { it.toString() == "e4d3" })
    }

    @Test
    fun `en passant is refused when it would expose the king`() {
        // White king on e5, black rook on h5: taking en passant clears two
        // pawns off the fifth rank at once and hangs the king.
        val state = position("8/8/8/K1Pp3r/8/8/8/4k3 w - d6 0 1")
        assertTrue(
            "c5d6 must not be legal",
            ChessRules.legalMoves(state).none { it.toString() == "c5d6" },
        )
    }

    @Test
    fun `a pawn reaching the last rank offers all four promotions`() {
        val state = position("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val promotions = ChessRules.legalMoves(state)
            .filter { squareName(it.from) == "a7" }
            .mapNotNull { it.promotion }
        assertEquals(
            setOf(PieceKind.QUEEN, PieceKind.ROOK, PieceKind.BISHOP, PieceKind.KNIGHT),
            promotions.toSet(),
        )

        val queened = state.moveTo("a7a8q")
        assertEquals(Piece(PieceKind.QUEEN, white = true), queened.board[squareFromName("a8")])
    }

    @Test
    fun `castling moves the rook and gives up both rights`() {
        var state = position("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        state = state.moveTo("e1g1")
        assertEquals(Piece(PieceKind.KING, true), state.board[squareFromName("g1")])
        assertEquals("the rook jumps over", Piece(PieceKind.ROOK, true), state.board[squareFromName("f1")])
        assertNull(state.board[squareFromName("h1")])
        assertFalse(state.castling.whiteKingSide)
        assertFalse(state.castling.whiteQueenSide)
        assertTrue("black keeps its own", state.castling.blackKingSide)

        val queenSide = position("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").moveTo("e1c1")
        assertEquals(Piece(PieceKind.ROOK, true), queenSide.board[squareFromName("d1")])
    }

    @Test
    fun `you may not castle out of, through, or into check`() {
        // Rook on e8 attacks the king's own square.
        assertTrue(
            "out of check",
            ChessRules.legalMoves(position("4r3/8/8/8/8/8/8/R3K2R w KQ - 0 1"))
                .none { it.toString() == "e1g1" || it.toString() == "e1c1" },
        )
        // Rook on f8 attacks the square the king crosses.
        assertTrue(
            "through check",
            ChessRules.legalMoves(position("5r2/8/8/8/8/8/8/R3K2R w KQ - 0 1"))
                .none { it.toString() == "e1g1" },
        )
        // Rook on g8 attacks the square the king lands on.
        assertTrue(
            "into check",
            ChessRules.legalMoves(position("6r1/8/8/8/8/8/8/R3K2R w KQ - 0 1"))
                .none { it.toString() == "e1g1" },
        )
        // The b-file square may be attacked; the king does not cross it.
        assertTrue(
            "b1 attacked is still fine",
            ChessRules.legalMoves(position("1r6/8/8/8/8/8/8/R3K2R w KQ - 0 1"))
                .any { it.toString() == "e1c1" },
        )
    }

    @Test
    fun `castling rights go when a rook moves or is taken on its corner`() {
        val moved = position("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").moveTo("a1b1")
        assertFalse(moved.castling.whiteQueenSide)
        assertTrue(moved.castling.whiteKingSide)

        // White's rook takes on a8, so Black loses the queen's side.
        val taken = position("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
            .moveTo("a1a8")
        assertFalse("captured rook loses its right", taken.castling.blackQueenSide)
        assertTrue(taken.castling.blackKingSide)
    }

    @Test
    fun `a pinned piece may not step off the line`() {
        // The knight on e2 is pinned against the king by the rook on e8.
        val state = position("4r3/8/8/8/8/8/4N3/4K3 w - - 0 1")
        assertTrue(
            ChessRules.legalMoves(state).none { squareName(it.from) == "e2" },
        )
    }

    @Test
    fun `in check you must answer the check`() {
        val state = position("4k3/8/8/8/8/8/4r3/4K3 w - - 0 1")
        assertTrue(ChessRules.inCheck(state))
        val moves = ChessRules.legalMoves(state).map { it.toString() }.toSet()
        // Take the rook, or step off the file. Nothing else.
        assertEquals(setOf("e1e2", "e1d1", "e1f1"), moves)
    }

    // -- Endings ------------------------------------------------------------

    @Test
    fun `the back rank mate ends the game`() {
        val state = position("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1").moveTo("a1a8")
        assertTrue(ChessRules.isFinished(state))
        assertEquals(ChessOutcome.WHITE_WINS, state.outcome)
        assertEquals("recorded as mate", "Ra8#", state.moveLog.last())
        assertNull("nobody is on the clock", ChessRules.currentSeat(state))
    }

    @Test
    fun `fools mate is mate`() {
        var state = ChessRules.initialState(config())
        state = state.moveTo("f2f3")
        state = state.moveTo("e7e5")
        state = state.moveTo("g2g4")
        state = state.moveTo("d8h4")
        assertEquals(ChessOutcome.BLACK_WINS, state.outcome)
        assertEquals("Qh4#", state.moveLog.last())
    }

    @Test
    fun `a king with no move and no check is stalemate`() {
        // Qf7 takes g7, g8 and h7 away without touching h8.
        val state = position("7k/8/6Q1/8/8/8/8/K7 w - - 0 1").moveTo("g6f7")
        assertEquals(ChessOutcome.STALEMATE, state.outcome)
        assertTrue(ChessRules.isFinished(state))
    }

    @Test
    fun `bare kings cannot mate and the game is drawn`() {
        val state = position("4k3/8/8/8/8/8/4r3/4K3 w - - 0 1").moveTo("e1e2")
        assertEquals(ChessOutcome.INSUFFICIENT_MATERIAL, state.outcome)
    }

    @Test
    fun `what counts as too little material`() {
        fun material(fen: String) = ChessRules.insufficientMaterial(parseFen(fen).board)
        assertTrue("bare kings", material("4k3/8/8/8/8/8/8/4K3 w - - 0 1"))
        assertTrue("one knight", material("4k3/8/8/8/8/8/8/3NK3 w - - 0 1"))
        assertTrue("one bishop", material("4k3/8/8/8/8/8/8/3BK3 w - - 0 1"))
        // Both bishops on dark squares: nothing can ever be forced.
        assertTrue("same-colour bishops", material("2b1k3/8/8/8/8/8/8/3BK3 w - - 0 1"))
        assertFalse("opposite-colour bishops", material("3bk3/8/8/8/8/8/8/3BK3 w - - 0 1"))
        assertFalse("a rook is enough", material("4k3/8/8/8/8/8/8/3RK3 w - - 0 1"))
        assertFalse("a pawn can promote", material("4k3/8/8/8/8/8/3P4/4K3 w - - 0 1"))
        assertFalse("two knights are not automatic", material("4k3/8/8/8/8/8/8/2NNK3 w - - 0 1"))
    }

    @Test
    fun `the fifty-move rule ends it, and can be switched off`() {
        val fen = "4k3/8/8/8/8/8/8/R3K3 w - - 99 60"
        val drawn = position(fen).moveTo("a1a2")
        assertEquals(ChessOutcome.FIFTY_MOVE, drawn.outcome)

        val played = position(fen, ChessOptions(fiftyMoveRule = false)).moveTo("a1a2")
        assertNull("the toggle keeps the game alive", played.outcome)
    }

    @Test
    fun `a capture or a pawn move resets the clock`() {
        val state = position("4k3/8/8/8/8/8/3P4/4K3 w - - 40 60").moveTo("d2d4")
        assertEquals(0, state.halfmoveClock)
    }

    @Test
    fun `the same position three times is a draw`() {
        // Both kings walk back and forth; the third repetition ends it.
        var state = position("4k3/8/8/8/8/8/8/R3K3 w - - 0 1")
        var guard = 0
        while (!ChessRules.isFinished(state) && guard++ < 40) {
            val square = if (guard % 4 == 1 || guard % 4 == 2) {
                if (state.whiteToMove) "a1b1" else "e8d8"
            } else {
                if (state.whiteToMove) "b1a1" else "d8e8"
            }
            state = state.moveTo(square)
        }
        assertEquals(ChessOutcome.REPETITION, state.outcome)
    }

    // -- Notation -----------------------------------------------------------

    @Test
    fun `the move log reads as algebraic notation`() {
        var state = ChessRules.initialState(config())
        state = state.moveTo("e2e4")
        state = state.moveTo("e7e5")
        state = state.moveTo("g1f3")
        state = state.moveTo("b8c6")
        state = state.moveTo("f1b5")
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6", "Bb5"), state.moveLog)
    }

    @Test
    fun `captures, castling and promotion are all written out`() {
        val taken = position("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1").moveTo("e4d5")
        assertEquals("exd5", taken.moveLog.last())

        val castled = position("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").moveTo("e1g1")
        assertEquals("O-O", castled.moveLog.last())

        val long = position("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").moveTo("e1c1")
        assertEquals("O-O-O", long.moveLog.last())

        val promoted = position("4k3/P7/8/8/8/8/8/4K3 w - - 0 1").moveTo("a7a8r")
        assertEquals("a8=R+", promoted.moveLog.last())
    }

    @Test
    fun `two pieces reaching the same square are told apart`() {
        // Knights on c3 and g1 both reach e2: the file separates them.
        val byFile = position("4k3/8/8/8/8/2N5/8/4K1N1 w - - 0 1").moveTo("c3e2")
        assertEquals("Nce2", byFile.moveLog.last())

        // Rooks on a1 and a5 both reach a3, sharing a file: the rank separates them.
        val byRank = position("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1").moveTo("a1a3")
        assertEquals("R1a3", byRank.moveLog.last())
    }

    // -- Playing it out -----------------------------------------------------

    @Test
    fun `random legal play always reaches a result`() {
        repeat(12) { iteration ->
            val random = Random(iteration.toLong())
            var state = ChessRules.initialState(config())
            var guard = 0
            while (!ChessRules.isFinished(state) && guard++ < 1_000) {
                val seat = ChessRules.currentSeat(state)!!
                val legal = ChessRules.legalMoves(state, seat)
                assertTrue("no legal move but the game is not over", legal.isNotEmpty())
                state = ChessRules.applyMove(state, seat, legal[random.nextInt(legal.size)])
            }
            assertTrue("game $iteration never ended", ChessRules.isFinished(state))
            assertNotNull(state.outcome)
        }
    }

    @Test
    fun `the wrong seat cannot move`() {
        val state = ChessRules.initialState(config())
        assertTrue(ChessRules.legalMoves(state, BLACK_SEAT).isEmpty())
        val move = ChessRules.legalMoves(state, WHITE_SEAT).first()
        runCatching { ChessRules.applyMove(state, BLACK_SEAT, move) }
            .onSuccess { throw AssertionError("Black was allowed to play White's move") }
    }

    // -- Wire format --------------------------------------------------------

    @Test
    fun `both sides see the same board`() {
        val state = ChessRules.initialState(config())
        assertEquals(state, ChessRules.viewFor(state, WHITE_SEAT))
        assertEquals(state, ChessRules.viewFor(state, BLACK_SEAT))
    }

    @Test
    fun `state and moves survive a round trip`() {
        val state = ChessRules.initialState(config()).moveTo("e2e4")
        assertEquals(state, ChessRules.decodeState(ChessRules.encodeState(state)))
        listOf(
            ChessMove(squareFromName("e2"), squareFromName("e4")),
            ChessMove(squareFromName("a7"), squareFromName("a8"), PieceKind.QUEEN),
        ).forEach { assertEquals(it, ChessRules.decodeMove(ChessRules.encodeMove(it))) }
    }

    private companion object {
        /**
         * The standard perft suite. Depths are kept where they run in a second
         * or two apiece — deep enough that castling, en passant, promotion and
         * pin handling are all exercised many thousands of times over.
         */
        val PERFT_POSITIONS: List<Pair<String, List<Long>>> = listOf(
            // Kiwipete: castling both ways, pins, and a board full of tactics.
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1" to
                listOf(48L, 2_039L, 97_862L),
            // A rook-and-pawn endgame that catches en passant errors.
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1" to
                listOf(14L, 191L, 2_812L, 43_238L),
            // Promotion with capture, from both sides.
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1" to
                listOf(6L, 264L, 9_467L),
            // A cramped position where most moves are illegal.
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8" to
                listOf(44L, 1_486L, 62_379L),
        )
    }
}
