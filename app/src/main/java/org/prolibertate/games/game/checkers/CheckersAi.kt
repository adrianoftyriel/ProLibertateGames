package org.prolibertate.games.game.checkers

import kotlin.random.Random
import org.prolibertate.games.game.engine.GameAi

/**
 * A negamax search with alpha-beta pruning and iterative deepening.
 *
 * Checkers can be searched deeper than chess for the same money: captures are
 * compulsory, so most positions offer a handful of moves rather than thirty,
 * and a forced capture costs nothing to search. That is why the levels here go
 * to seven ply where the chess ones stop at four.
 *
 * There is no quiescence tail. The usual reason for one is a search stopping in
 * the middle of an exchange, and compulsory capture takes care of that by
 * itself: a position where a piece has just been taken almost always has the
 * recapture as its only legal move, so the search plays it whether it wanted to
 * or not.
 */
class CheckersAi(private val level: CheckersLevel? = null) :
    GameAi<CheckersState, CheckersMove> {

    private var nodeBudget = 0
    private var nodesUsed = 0

    override fun chooseMove(
        state: CheckersState,
        seat: Int,
        legal: List<CheckersMove>,
    ): CheckersMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        val setting = level ?: state.options.level
        if (legal.size == 1) return legal.first()

        nodeBudget = NODE_BUDGET
        nodesUsed = 0

        val random = Random(state.moveLog.size * 31L + state.positionKey().hashCode())

        var best = legal.first()
        var scored = legal.map { it to 0 }

        for (depth in 1..setting.depth) {
            val results = mutableListOf<Pair<CheckersMove, Int>>()
            val ordered = order(legal, preferred = best)
            for (move in ordered) {
                val score = -negamax(
                    state = CheckersRules.advanced(state, move),
                    depth = depth - 1,
                    alpha = -INFINITY,
                    beta = INFINITY,
                    ply = 1,
                )
                results += move to score
                if (nodesUsed >= nodeBudget) break
            }
            if (nodesUsed >= nodeBudget && results.size < ordered.size) break
            scored = results
            best = results.maxBy { it.second }.first
        }

        val bestScore = scored.maxOf { it.second }
        val acceptable = scored.filter { it.second >= bestScore - setting.slack() }
        return acceptable[random.nextInt(acceptable.size)].first
    }

    private fun CheckersLevel.slack(): Int = when (this) {
        CheckersLevel.CASUAL -> 80
        CheckersLevel.CLUB -> 20
        CheckersLevel.STRONG -> 0
    }

    private fun negamax(state: CheckersState, depth: Int, alpha: Int, beta: Int, ply: Int): Int {
        nodesUsed++

        if (state.phase == CheckersPhase.GAME_OVER) {
            // The rules leave the loser on the clock, so a finished position is
            // always a loss for the side to move — or a draw.
            return when (state.outcome) {
                CheckersOutcome.DRAW_NO_PROGRESS, CheckersOutcome.DRAW_REPETITION, null -> 0
                else -> -(WIN - ply)
            }
        }
        if (depth <= 0) return evaluate(state)

        val moves = CheckersRules.legalMoves(state)
        if (moves.isEmpty()) return -(WIN - ply)

        var localAlpha = alpha
        for (move in order(moves, preferred = null)) {
            val score = -negamax(
                state = CheckersRules.advanced(state, move),
                depth = depth - 1,
                alpha = -beta,
                beta = -localAlpha,
                ply = ply + 1,
            )
            if (score >= beta) return beta
            if (score > localAlpha) localAlpha = score
            if (nodesUsed >= nodeBudget) break
        }
        return localAlpha
    }

    /** Longest captures first: they take the most and they cut the most. */
    private fun order(moves: List<CheckersMove>, preferred: CheckersMove?): List<CheckersMove> =
        moves.sortedByDescending { move ->
            when {
                move == preferred -> 1_000
                else -> move.steps.size * 10
            }
        }

    /**
     * The position from the point of view of the side to move.
     *
     * Material first, then two things that decide checkers games far more than
     * beginners expect: **kings are worth much more than men**, and **men are
     * worth more the closer they are to being crowned**, so a search with
     * nothing else to choose between will push its men up the board rather than
     * shuffle along the back.
     *
     * The back row is worth holding for the opposite reason — an empty back row
     * is a road to a crown for the other side — so the two pull against each
     * other, which is about right.
     */
    private fun evaluate(state: CheckersState): Int {
        val us = state.turn
        val them = other(us)
        var score = 0

        for (square in 0 until SQUARES) {
            val piece = state.board[square] ?: continue
            val sign = if (piece.seat == us) 1 else -1
            score += sign * if (piece.king) KING else MAN

            if (!piece.king) {
                // How many rows it has come, out of the seven it needs.
                val advanced = if (piece.seat == BLACK_SEAT) rowOf(square) else 7 - rowOf(square)
                score += sign * advanced * ADVANCE
            }

            // Sitting on the edge cannot be jumped, which is worth a little in
            // a game whose entire tactic is being jumped.
            if (columnOf(square) == 0 || columnOf(square) == 7) score += sign * EDGE

            if (rowOf(square) == crownRowOf(other(piece.seat))) score += sign * BACK_ROW
        }

        score += MOBILITY * (mobility(state, us) - mobility(state, them))
        return score
    }

    /**
     * Somewhere to go, counted as piece-to-empty-square steps rather than as
     * legal moves: the legal list is only ever the captures when a capture
     * exists, and a position is not immobile for being obliged to take.
     */
    private fun mobility(state: CheckersState, seat: Int): Int {
        var count = 0
        for (square in 0 until SQUARES) {
            val piece = state.board[square] ?: continue
            if (piece.seat != seat) continue
            val directions = if (piece.king) {
                listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
            } else {
                listOf(forwardOf(seat) to 1, forwardOf(seat) to -1)
            }
            for ((rowStep, columnStep) in directions) {
                val target = squareAt(rowOf(square) + rowStep, columnOf(square) + columnStep)
                if (target != null && state.board[target] == null) count++
            }
        }
        return count
    }

    private companion object {
        const val INFINITY = 1_000_000
        const val WIN = 100_000
        const val NODE_BUDGET = 200_000

        const val MAN = 100
        const val KING = 175
        const val ADVANCE = 6
        const val EDGE = 4
        const val BACK_ROW = 5
        const val MOBILITY = 2
    }
}
