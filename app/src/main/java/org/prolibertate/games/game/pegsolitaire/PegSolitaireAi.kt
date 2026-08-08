package org.prolibertate.games.game.pegsolitaire

import org.prolibertate.games.game.engine.GameAi

/**
 * Plays the board for the one seat.
 *
 * Nothing normally asks it to: the single seat belongs to the person holding the
 * phone, so this never runs during an ordinary game. It exists because a seat
 * that could be left empty has to be playable by something, and because a
 * one-move-deep chooser is exactly what a "suggest a jump" button would want.
 *
 * The heuristic is the one every good peg player uses by hand: keep the pegs
 * together. A jump that leaves more jumps behind it has not painted the board
 * into a corner, and pulling toward the middle stops a stranded peg being left
 * out on an arm where nothing can ever reach it.
 */
object PegSolitaireAi : GameAi<PegSolitaireState, PegSolitaireMove> {

    override fun chooseMove(
        state: PegSolitaireState,
        seat: Int,
        legal: List<PegSolitaireMove>,
    ): PegSolitaireMove {
        require(legal.isNotEmpty()) { "No jump available" }
        val jumps = legal.filterIsInstance<PegJump>()
        if (jumps.isEmpty()) return legal.first()
        return jumps.maxBy { score(state, it) }
    }

    private fun score(state: PegSolitaireState, jump: PegJump): Int {
        val after = state.copy(pegs = state.pegs - jump.from - jump.over + jump.to)
        // Solving outright beats every other consideration.
        if (after.solved) return Int.MAX_VALUE
        val followUps = PegSolitaireRules.legalJumps(after).size
        return followUps * 10 - spread(after)
    }

    /**
     * How far the pegs sit from their own centre, in whole holes.
     *
     * A smaller number is a tighter cluster. Manhattan distance is the right
     * measure on both board shapes here, because a jump moves two steps along one
     * of the grid's own directions either way.
     */
    private fun spread(state: PegSolitaireState): Int {
        if (state.pegs.isEmpty()) return 0
        val meanRow = state.pegs.sumOf { it.row } / state.pegs.size
        val meanCol = state.pegs.sumOf { it.col } / state.pegs.size
        return state.pegs.sumOf {
            kotlin.math.abs(it.row - meanRow) + kotlin.math.abs(it.col - meanCol)
        }
    }
}
