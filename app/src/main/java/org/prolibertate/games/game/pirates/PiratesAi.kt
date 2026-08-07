package org.prolibertate.games.game.pirates

import kotlin.random.Random
import org.prolibertate.games.game.engine.GameAi

/**
 * A negamax search with alpha-beta pruning and iterative deepening, over the
 * same rules engine the game runs on.
 *
 * The interesting part of a hunt game is that the two sides want opposite
 * shapes out of the same board, and one evaluation has to speak for both. The
 * pirates want Bulgars gone and room to move; the Bulgars want to be many, to
 * be forward, and above all not to be jumpable. The terms below are written
 * from the Bulgars' side and negated for the pirates, so the two cannot drift
 * apart and start valuing the same position differently.
 */
class PiratesAi(private val level: PiratesLevel? = null) : GameAi<PiratesState, PiratesMove> {

    private var nodeBudget = 0
    private var nodesUsed = 0

    override fun chooseMove(state: PiratesState, seat: Int, legal: List<PiratesMove>): PiratesMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        val setting = level ?: state.options.level
        if (legal.size == 1) return legal.first()

        nodeBudget = NODE_BUDGET
        nodesUsed = 0

        val random = Random(state.moveLog.size * 31L + state.positionKey().hashCode())

        var best = legal.first()
        var scored = legal.map { it to 0 }

        for (depth in 1..setting.depth) {
            val results = mutableListOf<Pair<PiratesMove, Int>>()
            val ordered = order(legal, preferred = best)
            for (move in ordered) {
                val score = -negamax(
                    state = PiratesRules.advanced(state, move),
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

    private fun PiratesLevel.slack(): Int = when (this) {
        PiratesLevel.CASUAL -> 60
        PiratesLevel.CLUB -> 15
        PiratesLevel.STRONG -> 0
    }

    private fun negamax(state: PiratesState, depth: Int, alpha: Int, beta: Int, ply: Int): Int {
        nodesUsed++

        if (state.phase == PiratesPhase.GAME_OVER) {
            return when (state.outcome) {
                PiratesOutcome.DRAW_STALEMATE, null -> 0
                // A finished game leaves the loser on the clock in two of the
                // three endings; the third — the stronghold filled — is a win
                // for the side that has just moved, so it is read off the
                // outcome rather than assumed.
                PiratesOutcome.BULGARS_STORM ->
                    if (state.turn == BULGAR_SEAT) WIN - ply else -(WIN - ply)

                else -> -(WIN - ply)
            }
        }
        if (depth <= 0) return evaluate(state)

        val moves = PiratesRules.legalMoves(state)
        if (moves.isEmpty()) return -(WIN - ply)

        var localAlpha = alpha
        for (move in order(moves, preferred = null)) {
            val score = -negamax(
                state = PiratesRules.advanced(state, move),
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

    /** Captures first, then moves into the stronghold: the two things that change anything. */
    private fun order(moves: List<PiratesMove>, preferred: PiratesMove?): List<PiratesMove> =
        moves.sortedByDescending { move ->
            when {
                move == preferred -> 1_000
                move.steps.size > 1 -> 200
                isJumpFrom(move.from, move.steps.first()) -> 100
                isStronghold(move.to) -> 20
                else -> 0
            }
        }

    /** The position from the point of view of the side to move. */
    private fun evaluate(state: PiratesState): Int {
        var bulgarView = 0

        bulgarView += state.count(BULGAR_SEAT) * BULGAR
        bulgarView += state.stronghold() * IN_STRONGHOLD

        // How far up the board the crowd has come. The stronghold is at the
        // top, so a low row number is ground gained.
        for (point in 0 until POINTS) {
            if (state.board[point] != BULGAR_SEAT) continue
            bulgarView += (GRID - 1 - rowOf(point)) * ADVANCE
            // A Bulgar with an empty point directly behind it on a line is one
            // a pirate can jump. Being unjumpable is most of staying alive.
            if (isExposed(state, point)) bulgarView -= EXPOSED
        }

        // Room for the pirates to work in, which is what the Bulgars are
        // slowly taking away.
        bulgarView -= pirateMobility(state) * PIRATE_ROOM

        return if (state.turn == BULGAR_SEAT) bulgarView else -bulgarView
    }

    /**
     * Whether a pirate could jump this Bulgar: a pirate on one side of it along
     * a line, and an empty point on the other.
     */
    private fun isExposed(state: PiratesState, point: Int): Boolean {
        for (neighbour in ADJACENCY[point]) {
            if (state.board[neighbour] != PIRATE_SEAT) continue
            val landing = pointAt(
                rowOf(point) + (rowOf(point) - rowOf(neighbour)),
                columnOf(point) + (columnOf(point) - columnOf(neighbour)),
            ) ?: continue
            if (landing in ADJACENCY[point] && state.board[landing] == null) return true
        }
        return false
    }

    private fun pirateMobility(state: PiratesState): Int =
        (0 until POINTS)
            .filter { state.board[it] == PIRATE_SEAT }
            .sumOf { point -> ADJACENCY[point].count { state.board[it] == null } }

    private companion object {
        const val INFINITY = 1_000_000
        const val WIN = 100_000
        const val NODE_BUDGET = 150_000

        const val BULGAR = 100
        const val IN_STRONGHOLD = 55
        const val ADVANCE = 4
        const val EXPOSED = 22
        const val PIRATE_ROOM = 3
    }
}
