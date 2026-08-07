package org.prolibertate.games.game.morris

import kotlin.random.Random
import org.prolibertate.games.game.engine.GameAi

/**
 * A negamax search with alpha-beta pruning and iterative deepening.
 *
 * There is no quiescence tail here, and there does not need to be one: the
 * thing a fixed-depth search gets wrong in chess is stopping halfway through an
 * exchange, and in Morris there are no exchanges. A piece is taken for closing
 * a mill and nothing is taken back, so a position is as quiet as it looks.
 *
 * What does need care is the opening. Placing gives twenty-four choices a ply
 * and they are nearly all equal on material, so the evaluation has to say
 * something about shape — mills half-built, and the mid-edge points that carry
 * the ladders between squares — or the search spends its depth shuffling
 * indistinguishable moves.
 *
 * Strength comes from the table's own options unless a level is passed in.
 */
class MorrisAi(private val level: MorrisLevel? = null) : GameAi<MorrisState, MorrisMove> {

    /**
     * Ceiling on how much thinking any one move gets, so a phone does not stall
     * in the placing phase where the branching factor is at its widest. The
     * search stops where it is and plays the best move from the last depth it
     * managed to finish.
     */
    private var nodeBudget = 0
    private var nodesUsed = 0

    override fun chooseMove(state: MorrisState, seat: Int, legal: List<MorrisMove>): MorrisMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        val setting = level ?: state.options.level
        if (legal.size == 1) return legal.first()

        nodeBudget = NODE_BUDGET
        nodesUsed = 0

        // Deterministic for a given position, so a host and a client replaying
        // the same game land on the same move, but varied enough that two games
        // from an empty board do not run identically.
        val random = Random(state.moveLog.size * 31L + state.positionKey().hashCode())

        var best = legal.first()
        var scored = legal.map { it to 0 }

        for (depth in 1..setting.depth) {
            val results = mutableListOf<Pair<MorrisMove, Int>>()
            val ordered = order(legal, preferred = best)
            for (move in ordered) {
                // A full window for every root move rather than a narrowing
                // one: it prunes less, but it is what makes the scores
                // comparable, and the weaker levels pick from among the
                // near-best rather than from the best alone.
                val score = -negamax(
                    state = MorrisRules.advanced(state, move),
                    depth = depth - 1,
                    alpha = -INFINITY,
                    beta = INFINITY,
                    ply = 1,
                )
                results += move to score
                if (nodesUsed >= nodeBudget) break
            }
            // A half-finished iteration has scored only some of the moves, so
            // it is thrown away rather than compared against a complete one.
            if (nodesUsed >= nodeBudget && results.size < ordered.size) break
            scored = results
            best = results.maxBy { it.second }.first
        }

        val bestScore = scored.maxOf { it.second }
        val acceptable = scored.filter { it.second >= bestScore - setting.slack() }
        return acceptable[random.nextInt(acceptable.size)].first
    }

    /** How far below best a move may score and still be considered. */
    private fun MorrisLevel.slack(): Int = when (this) {
        MorrisLevel.CASUAL -> 60
        MorrisLevel.CLUB -> 15
        MorrisLevel.STRONG -> 0
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    private fun negamax(state: MorrisState, depth: Int, alpha: Int, beta: Int, ply: Int): Int {
        nodesUsed++

        terminalScore(state, ply)?.let { return it }
        if (depth <= 0) return evaluate(state)

        val moves = MorrisRules.legalMoves(state)
        // The terminal check above has already caught a player with no move, so
        // an empty list here would mean the rules and the search disagree.
        if (moves.isEmpty()) return evaluate(state)

        var localAlpha = alpha
        for (move in order(moves, preferred = null)) {
            val score = -negamax(
                state = MorrisRules.advanced(state, move),
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

    /**
     * A finished game, scored from the side to move.
     *
     * The rules leave the loser on the clock when they end the game, so in a
     * finished position the side to move is always the one that lost. Distance
     * is subtracted so the search prefers winning sooner and, when it is
     * losing, holding out as long as it can.
     */
    private fun terminalScore(state: MorrisState, ply: Int): Int? {
        if (state.phase != MorrisPhase.GAME_OVER) return null
        return when (state.outcome) {
            MorrisOutcome.DRAW_REPETITION, MorrisOutcome.DRAW_NO_MILL, null -> 0
            else -> -(WIN - ply)
        }
    }

    /**
     * Mills first, then moves that leave a mill half-built.
     *
     * Ordering is worth more here than a cleverer evaluation would be: a mill
     * takes a piece, and searching the move that takes one first gives every
     * quieter move below it a bound to be cut against.
     */
    private fun order(moves: List<MorrisMove>, preferred: MorrisMove?): List<MorrisMove> =
        moves.sortedByDescending { move ->
            when {
                move == preferred -> 1_000
                move.remove != null -> 100
                // A move onto a mid-edge point has three ways off it rather
                // than two, which is worth looking at before a corner.
                isMidEdge(move.to) -> 10
                else -> 0
            }
        }

    // -----------------------------------------------------------------------
    // Evaluation
    // -----------------------------------------------------------------------

    /** The position from the point of view of the side to move. */
    private fun evaluate(state: MorrisState): Int {
        val us = state.turn
        val them = other(us)

        var score = PIECE * (state.remaining(us) - state.remaining(them))
        score += MILL * (state.millCount(us) - state.millCount(them))
        score += ALMOST * (almostMills(state, us) - almostMills(state, them))
        score += MOBILITY * (mobility(state, us) - mobility(state, them))
        score += LADDER * (midEdgeCount(state, us) - midEdgeCount(state, them))
        score -= DANGER * (onThree(state, us) - onThree(state, them))
        return score
    }

    /**
     * Being down to three is a bad place to be, whatever the freedom to fly is
     * worth: one more piece and the game is over. Saying so costs a term, and
     * not saying it is worse than it sounds — with flying on, the search would
     * otherwise notice that three pieces can go anywhere and start treating
     * losing one as a way of getting there.
     */
    private fun onThree(state: MorrisState, seat: Int): Int =
        if (state.phase != MorrisPhase.PLACING && state.onBoard(seat) == 3) 1 else 0

    /**
     * Lines holding two of a player's pieces with the third point empty — the
     * mills that are one move away. Two of them sharing that empty point is
     * what a fork looks like a move before it happens.
     */
    private fun almostMills(state: MorrisState, seat: Int): Int = MILLS.count { mill ->
        mill.count { state.board[it] == seat } == 2 && mill.any { state.board[it] == null }
    }

    /**
     * Somewhere to go, counted as piece-to-empty-point steps.
     *
     * Deliberately not the legal move list: that would multiply out every mill
     * by every piece it could take, and a position is not more mobile for
     * having a choice of which enemy piece to remove. While pieces are still
     * being placed everyone can go anywhere, so it says nothing and is skipped.
     */
    private fun mobility(state: MorrisState, seat: Int): Int {
        if (state.phase == MorrisPhase.PLACING) return 0
        // A player who can fly is never short of somewhere to go, so they get
        // the cap rather than a count of the empty board. Counting it would
        // have the search reading "fewer pieces" as "more freedom", which is
        // how an engine talks itself into being reduced to three.
        if (state.isFlying(seat)) return MOBILITY_CAP
        return (0 until POINTS)
            .filter { state.board[it] == seat }
            .sumOf { point -> ADJACENCY[point].count { state.board[it] == null } }
            .coerceAtMost(MOBILITY_CAP)
    }

    /** Pieces on the mid-edge points, which are the junctions worth holding. */
    private fun midEdgeCount(state: MorrisState, seat: Int): Int =
        (0 until POINTS).count { isMidEdge(it) && state.board[it] == seat }

    private companion object {
        const val INFINITY = 1_000_000
        const val WIN = 100_000
        const val NODE_BUDGET = 120_000

        const val PIECE = 120
        const val MILL = 26
        const val ALMOST = 9
        const val MOBILITY = 3
        const val LADDER = 3
        const val DANGER = 40

        /**
         * Mobility saturates: the difference between nowhere to go and one
         * square is the game, and the difference between eight squares and
         * twelve is nothing. Capping it also keeps it well under the value of
         * a piece, which is what stops shape talking over material.
         */
        const val MOBILITY_CAP = 12
    }
}
