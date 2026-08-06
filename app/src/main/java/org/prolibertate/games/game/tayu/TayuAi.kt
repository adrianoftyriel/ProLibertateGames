package org.prolibertate.games.game.tayu

import org.prolibertate.games.game.engine.GameAi

/**
 * A heuristic Ta Yü opponent.
 *
 * It rates every legal placement on the board that placement would produce,
 * weighing three things: what its own axis would score, what the opponent's
 * would score, and which way the still-open river mouths are pointing.
 *
 * That last term is what makes it play sensibly at all. For most of a game both
 * scores are flatly zero — a product needs *both* edges — so an opponent that
 * looked only at the score would have nothing to choose between its first fifty
 * placements. Counting open mouths, and how close each one already is to the
 * edge it points at, gives it a reason to drive water towards its own sides and
 * to turn the opponent's rivers away from theirs.
 *
 * [level] overrides the table's own setting, which is what the tests use;
 * ordinarily it is left null and read from [TayuOptions.level].
 */
class TayuAi(private val level: TayuLevel? = null) : GameAi<TayuState, TayuMove> {

    override fun chooseMove(state: TayuState, seat: Int, legal: List<TayuMove>): TayuMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        val setting = level ?: state.options.level
        if (legal.size == 1) return legal.first()
        return legal.maxByOrNull { rate(state, seat, it, setting) } ?: legal.first()
    }

    private fun rate(state: TayuState, seat: Int, move: TayuMove, setting: TayuLevel): Int {
        val after = TayuRules.previewPlacement(state, seat, move)
        val mine = state.axisOf(seat)
        val theirs = if (mine == Axis.NORTH_SOUTH) Axis.EAST_WEST else Axis.NORTH_SOUTH

        // Both edges get a +1 so that the first exit onto an empty edge is worth
        // something. Without it every placement rates zero until an axis has
        // broken through on both sides, and by then the game is decided.
        val exits = TayuRules.exitsOf(after)
        val ours = (exits.on(mine.first) + 1) * (exits.on(mine.second) + 1)
        val opposing = (exits.on(theirs.first) + 1) * (exits.on(theirs.second) + 1)

        // Skipped rather than multiplied by zero: the gentle setting is the whole
        // reason to avoid walking the open mouths at all, and this runs for every
        // one of a thousand-odd candidate placements.
        val reach = if (setting.reachWeight == 0) {
            0
        } else {
            (pull(after, mine) - pull(after, theirs)) / REACH_SCALE
        }

        return setting.ownWeight * ours -
            setting.blockWeight * opposing +
            setting.reachWeight * reach
    }

    /**
     * How much of the open river is aimed at [axis]: every mouth with empty
     * ground in front of it, counted more heavily the closer it already is to
     * the edge it points at.
     */
    private fun pull(state: TayuState, axis: Axis): Int =
        TayuRules.openMouths(state).sumOf { (cell, direction) ->
            if (direction != axis.first && direction != axis.second) {
                0
            } else {
                BOARD_SIZE - distanceToEdge(cell, direction)
            }
        }

    private fun distanceToEdge(cell: Int, direction: Facing): Int {
        val row = TayuBoard.rowOf(cell)
        val col = TayuBoard.colOf(cell)
        return when (direction) {
            Facing.NORTH -> row
            Facing.SOUTH -> BOARD_SIZE - 1 - row
            Facing.WEST -> col
            Facing.EAST -> BOARD_SIZE - 1 - col
        }
    }

    private companion object {
        /** Brings the open-mouth term onto the same scale as the score term. */
        const val REACH_SCALE = 8
    }
}
