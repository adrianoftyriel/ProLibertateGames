package org.prolibertate.games.game.chess

import kotlin.math.abs
import kotlin.random.Random
import org.prolibertate.games.game.engine.GameAi

/**
 * A negamax search with alpha-beta pruning, iterative deepening and a capture-
 * only quiescence tail.
 *
 * The quiescence tail is what stops it hanging pieces: a fixed-depth search that
 * stops in the middle of an exchange scores the position as though the recapture
 * never happens, and every such search plays like it is being robbed.
 *
 * Search depth comes from the table's own options unless a level is passed in,
 * so the setup screen sets the strength without the caller having to thread it
 * through.
 */
class ChessAi(private val level: ChessLevel? = null) : GameAi<ChessState, ChessMove> {

    /**
     * Ceiling on how much thinking any one move gets. A phone should not stall
     * on a sharp middlegame, so the search stops where it is and plays the best
     * move from the last depth it finished.
     */
    private var nodeBudget = 0
    private var nodesUsed = 0

    override fun chooseMove(state: ChessState, seat: Int, legal: List<ChessMove>): ChessMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        val setting = level ?: state.options.level
        if (legal.size == 1) return legal.first()

        nodeBudget = NODE_BUDGET
        nodesUsed = 0

        // Deterministic per position, so a host and a client replaying the same
        // game reach the same move, but varied enough that two games from the
        // opening array do not run identically.
        val random = Random(state.repetitionKeys.size * 31L + state.toFen().hashCode())

        var best = legal.first()
        var scored = legal.map { it to 0 }

        for (depth in 1..setting.depth) {
            val results = mutableListOf<Pair<ChessMove, Int>>()
            // Search the previous iteration's best first, so the sub-searches
            // below it have a decent bound to prune against.
            val ordered = orderMoves(state, legal, preferred = best)
            for (move in ordered) {
                // Every root move gets a full window rather than being cut off
                // against the best so far. It costs some pruning, but it is
                // what makes the scores comparable — the weaker levels pick
                // from among the near-best moves, and a move that returned only
                // an upper bound cannot be compared with one that did not.
                val score = -negamax(
                    state = ChessRules.advanced(state, move),
                    depth = depth - 1,
                    alpha = -INFINITY,
                    beta = INFINITY,
                    ply = 1,
                )
                results += move to score
                if (nodesUsed >= nodeBudget) break
            }
            // A half-finished iteration has scored only some of the moves, so
            // it is thrown away rather than compared against a full one.
            if (nodesUsed >= nodeBudget && results.size < ordered.size) break
            scored = results
            best = results.maxBy { it.second }.first
        }

        // Weaker levels take any move that is nearly as good, which is what
        // makes them beatable without making them play nonsense.
        val bestScore = scored.maxOf { it.second }
        val acceptable = scored.filter { it.second >= bestScore - setting.slack() }
        return acceptable[random.nextInt(acceptable.size)].first
    }

    private fun ChessLevel.slack(): Int = when (this) {
        ChessLevel.CASUAL -> 70
        ChessLevel.CLUB -> 20
        ChessLevel.STRONG -> 0
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    private fun negamax(state: ChessState, depth: Int, alpha: Int, beta: Int, ply: Int): Int {
        nodesUsed++
        val moves = ChessRules.legalMoves(state)
        if (moves.isEmpty()) {
            // Mate is scored by distance, so the search prefers mating sooner
            // and, when it is losing, delays as long as it can.
            return if (ChessRules.inCheck(state)) -(MATE - ply) else 0
        }
        if (state.halfmoveClock >= 100) return 0
        if (depth <= 0) return quiescence(state, alpha, beta, ply)

        var localAlpha = alpha
        for (move in orderMoves(state, moves, preferred = null)) {
            val score = -negamax(ChessRules.advanced(state, move), depth - 1, -beta, -localAlpha, ply + 1)
            if (score >= beta) return beta
            if (score > localAlpha) localAlpha = score
            if (nodesUsed >= nodeBudget) break
        }
        return localAlpha
    }

    /**
     * Keeps searching while pieces are still being taken.
     *
     * Standing pat is allowed because the side to move is never obliged to
     * capture — the position as it stands is a lower bound on what it can get.
     */
    private fun quiescence(state: ChessState, alpha: Int, beta: Int, ply: Int): Int {
        nodesUsed++
        val standPat = evaluate(state)
        if (standPat >= beta) return beta
        var localAlpha = maxOf(alpha, standPat)
        if (ply >= MAX_QUIESCENCE_PLY) return localAlpha

        val captures = ChessRules.legalMoves(state).filter {
            ChessRules.isCapture(state, it) || it.promotion != null
        }
        for (move in orderMoves(state, captures, preferred = null)) {
            val score = -quiescence(ChessRules.advanced(state, move), -beta, -localAlpha, ply + 1)
            if (score >= beta) return beta
            if (score > localAlpha) localAlpha = score
            if (nodesUsed >= nodeBudget) break
        }
        return localAlpha
    }

    /** Captures first, biggest victim by smallest attacker — cheap and effective. */
    private fun orderMoves(
        state: ChessState,
        moves: List<ChessMove>,
        preferred: ChessMove?,
    ): List<ChessMove> = moves.sortedByDescending { move ->
        var score = 0
        if (move == preferred) score += 1_000_000
        val victim = state.board[move.to]
        if (victim != null) {
            score += 10_000 + victim.kind.value - (state.board[move.from]?.kind?.value ?: 0) / 10
        }
        move.promotion?.let { score += 9_000 + it.value }
        score
    }

    // -----------------------------------------------------------------------
    // Evaluation
    // -----------------------------------------------------------------------

    /** Centipawns, from the point of view of whoever is to move. */
    fun evaluate(state: ChessState): Int {
        var white = 0
        var black = 0
        var whitePieces = 0
        var blackPieces = 0

        for (square in 0 until 64) {
            val piece = state.board[square] ?: continue
            val table = tableFor(piece.kind)
            // Black reads the same table from the other end of the board.
            val placement = table[if (piece.white) square else square xor 56]
            if (piece.white) {
                white += piece.kind.value + placement
                if (piece.kind != PieceKind.PAWN && piece.kind != PieceKind.KING) whitePieces++
            } else {
                black += piece.kind.value + placement
                if (piece.kind != PieceKind.PAWN && piece.kind != PieceKind.KING) blackPieces++
            }
        }

        // In a bare endgame the tables stop meaning much and driving the enemy
        // king to the edge starts to; without this a won endgame shuffles.
        if (whitePieces + blackPieces <= 2) {
            state.kingSquare(white = false)?.let { white += edgeDistance(it) * 8 }
            state.kingSquare(white = true)?.let { black += edgeDistance(it) * 8 }
        }

        val score = white - black
        return if (state.whiteToMove) score else -score
    }

    /** How far a king has been pushed towards the rim, 0 in the centre. */
    private fun edgeDistance(square: Int): Int {
        val file = fileOf(square)
        val rank = rankOf(square)
        return maxOf(abs(file * 2 - 7), abs(rank * 2 - 7))
    }

    private fun tableFor(kind: PieceKind): IntArray = when (kind) {
        PieceKind.PAWN -> PAWN_TABLE
        PieceKind.KNIGHT -> KNIGHT_TABLE
        PieceKind.BISHOP -> BISHOP_TABLE
        PieceKind.ROOK -> ROOK_TABLE
        PieceKind.QUEEN -> QUEEN_TABLE
        PieceKind.KING -> KING_TABLE
    }

    private companion object {
        const val INFINITY = 1_000_000
        const val MATE = 100_000
        const val NODE_BUDGET = 120_000
        const val MAX_QUIESCENCE_PLY = 24

        /**
         * Placement bonuses in centipawns, written from White's side with rank
         * eight on the top row — the same reading order the board uses, so the
         * table can be laid out to look like a board.
         */
        val PAWN_TABLE = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
            5, 5, 10, 25, 25, 10, 5, 5,
            0, 0, 0, 20, 20, 0, 0, 0,
            5, -5, -10, 0, 0, -10, -5, 5,
            5, 10, 10, -20, -20, 10, 10, 5,
            0, 0, 0, 0, 0, 0, 0, 0,
        )

        val KNIGHT_TABLE = intArrayOf(
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20, 0, 0, 0, 0, -20, -40,
            -30, 0, 10, 15, 15, 10, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30,
            -30, 5, 10, 15, 15, 10, 5, -30,
            -40, -20, 0, 5, 5, 0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50,
        )

        val BISHOP_TABLE = intArrayOf(
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 10, 10, 5, 0, -10,
            -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -20, -10, -10, -10, -10, -10, -10, -20,
        )

        val ROOK_TABLE = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            5, 10, 10, 10, 10, 10, 10, 5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            0, 0, 0, 5, 5, 0, 0, 0,
        )

        val QUEEN_TABLE = intArrayOf(
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 5, 5, 5, 0, -10,
            -5, 0, 5, 5, 5, 5, 0, -5,
            0, 0, 5, 5, 5, 5, 0, -5,
            -10, 5, 5, 5, 5, 5, 0, -10,
            -10, 0, 5, 0, 0, 0, 0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20,
        )

        val KING_TABLE = intArrayOf(
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -10, -20, -20, -20, -20, -20, -20, -10,
            20, 20, 0, 0, 0, 20, 20, 20,
            20, 30, 10, 0, 0, 10, 30, 20,
        )
    }
}
