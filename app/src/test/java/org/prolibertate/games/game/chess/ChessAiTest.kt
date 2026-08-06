package org.prolibertate.games.game.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessAiTest {

    private fun position(fen: String, level: ChessLevel = ChessLevel.CLUB) =
        ChessRules.fromPosition(ChessOptions(level = level), fen)

    private fun ChessAi.moveIn(state: ChessState): ChessMove =
        chooseMove(state, state.turnSeat, ChessRules.legalMoves(state))

    @Test
    fun `it only ever returns a legal move`() {
        val ai = ChessAi()
        var state = ChessRules.fromPosition(ChessOptions(), START_FEN)
        repeat(30) {
            if (ChessRules.isFinished(state)) return
            val legal = ChessRules.legalMoves(state)
            val move = ai.moveIn(state)
            assertTrue("$move is not legal in ${state.toFen()}", legal.contains(move))
            state = ChessRules.applyMove(state, state.turnSeat, move)
        }
    }

    @Test
    fun `it takes a piece that is hanging`() {
        // The black queen on d5 is undefended and the rook on d1 sees it.
        val state = position("4k3/8/8/3q4/8/8/8/3RK3 w - - 0 1")
        assertEquals("d1d5", ChessAi(ChessLevel.STRONG).moveIn(state).toString())
    }

    @Test
    fun `it does not give a piece away for nothing`() {
        // Rb1 and Rb7 are both available; Rb7 hangs the rook to the king.
        val state = position("8/1k6/8/8/8/8/8/1R2K3 w - - 0 1")
        val move = ChessAi(ChessLevel.STRONG).moveIn(state)
        assertTrue("played $move into the king", move.toString() != "b1b7")
    }

    @Test
    fun `it finds mate in one`() {
        val state = position("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
        assertEquals("a1a8", ChessAi(ChessLevel.STRONG).moveIn(state).toString())
    }

    @Test
    fun `it gets out of check rather than ignoring it`() {
        val state = position("4k3/8/8/8/8/8/4r3/4K3 w - - 0 1")
        val move = ChessAi().moveIn(state)
        val after = ChessRules.applyMove(state, state.turnSeat, move)
        assertTrue("still in check after $move", !ChessRules.inCheck(after.board, white = true))
    }

    @Test
    fun `it promotes to a queen when there is nothing to fear`() {
        val state = position("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val move = ChessAi(ChessLevel.STRONG).moveIn(state)
        assertEquals(PieceKind.QUEEN, move.promotion)
    }

    /** The same position with the colours swapped and the board turned round. */
    private fun mirrored(state: ChessState): ChessState {
        val board = arrayOfNulls<Piece>(64)
        for (square in 0 until 64) {
            state.board[square]?.let { board[square xor 56] = it.copy(white = !it.white) }
        }
        return state.copy(
            board = board.toList(),
            whiteToMove = !state.whiteToMove,
            castling = CastlingRights(
                whiteKingSide = state.castling.blackKingSide,
                whiteQueenSide = state.castling.blackQueenSide,
                blackKingSide = state.castling.whiteKingSide,
                blackQueenSide = state.castling.whiteQueenSide,
            ),
            enPassant = state.enPassant?.let { it xor 56 },
        )
    }

    @Test
    fun `the evaluation is symmetric`() {
        // A position and its mirror image must score identically from the side
        // to move, or the search believes one colour is better simply for being
        // that colour — and it will then play for it.
        listOf(
            "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "4k3/8/8/8/8/8/8/3RK3 w - - 0 1",
        ).forEach { fen ->
            val state = position(fen)
            assertEquals(fen, ChessAi().evaluate(state), ChessAi().evaluate(mirrored(state)))
        }
    }

    @Test
    fun `two engines play a whole game without an illegal move`() {
        val ai = ChessAi(ChessLevel.CASUAL)
        var state = ChessRules.fromPosition(ChessOptions(level = ChessLevel.CASUAL), START_FEN)
        var plies = 0
        while (!ChessRules.isFinished(state) && plies++ < 220) {
            val legal = ChessRules.legalMoves(state)
            val move = ai.moveIn(state)
            assertTrue("illegal $move", legal.contains(move))
            state = ChessRules.applyMove(state, state.turnSeat, move)
        }
        // Either somebody was mated or the position ran into one of the draw
        // rules; both are results. What matters is that it never got stuck.
        assertTrue(
            "no result and no legal continuation",
            ChessRules.isFinished(state) || ChessRules.legalMoves(state).isNotEmpty(),
        )
    }

    @Test
    fun `a move takes a reasonable amount of time at every level`() {
        // Rough guard rather than a benchmark: a phone should not sit thinking
        // for a quarter of a minute, and the node budget is what enforces it.
        val opening = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"
        ChessLevel.entries.forEach { level ->
            val state = position(opening, level)
            val started = System.nanoTime()
            ChessAi(level).moveIn(state)
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue("$level took ${millis}ms", millis < 15_000)
        }
    }
}
