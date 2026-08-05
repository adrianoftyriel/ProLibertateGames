package org.prolibertate.games.game.sequence

import org.prolibertate.games.game.engine.GameAi

/**
 * A heuristic Sequence opponent.
 *
 * It scores every legal placement by how much it advances one of its own runs
 * and how badly it spoils an opponent's, holds its jacks back until they are
 * worth spending, and never looks at anyone's hand.
 */
class SequenceAi(private val difficulty: Difficulty = Difficulty.NORMAL) :
    GameAi<SequenceState, SequenceMove> {

    enum class Difficulty(val blockWeight: Int, val jackPatience: Int) {
        /** Builds its own runs and mostly ignores yours. */
        EASY(blockWeight = 1, jackPatience = 0),

        NORMAL(blockWeight = 3, jackPatience = 3),

        /** Blocks aggressively and saves jacks for real threats. */
        CAUTIOUS(blockWeight = 5, jackPatience = 4),
    }

    override fun chooseMove(
        state: SequenceState,
        seat: Int,
        legal: List<SequenceMove>,
    ): SequenceMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        val team = state.teamOf(seat)

        // Clearing a dead card is free value when nothing better is on offer.
        val exchange = legal.filterIsInstance<ExchangeDeadCard>().firstOrNull()
        if (exchange != null && legal.none { it is PlaceChip }) return exchange

        val best = legal
            .filterIsInstance<PlaceChip>()
            .maxByOrNull { scorePlacement(state, it, team) }

        val removal = legal
            .filterIsInstance<RemoveChip>()
            .maxByOrNull { threatAt(state, it.cell, state.chips[it.cell]) }

        // Spend a one-eyed jack only on a genuinely dangerous chip.
        if (removal != null) {
            val threat = threatAt(state, removal.cell, state.chips[removal.cell])
            if (threat >= difficulty.jackPatience) return removal
        }

        return best ?: removal ?: exchange ?: legal.first()
    }

    /**
     * Rates placing a chip: how long a run it joins for us, plus how much of an
     * opponent's run it interrupts. Wild jacks are discounted so they are not
     * burned on an ordinary square.
     */
    private fun scorePlacement(state: SequenceState, move: PlaceChip, team: Int): Int {
        val ours = runPotential(state, move.cell, team)
        val theirs = (0 until state.options.teamCount)
            .filter { it != team }
            .maxOfOrNull { runPotential(state, move.cell, it) }
            ?: 0

        var score = ours * 4 + theirs * difficulty.blockWeight
        // Central squares sit on more possible lines than edge squares.
        val row = SequenceBoard.rowOf(move.cell)
        val col = SequenceBoard.colOf(move.cell)
        score += 4 - (kotlin.math.abs(row - 4) + kotlin.math.abs(col - 4)) / 2
        if (isTwoEyedJack(move.card)) score -= 12
        return score
    }

    /**
     * The best run this team could still build through [cell]: the highest
     * count of friendly-or-free squares in any five-window containing it, once
     * windows blocked by an opponent are discarded.
     */
    private fun runPotential(state: SequenceState, cell: Int, team: Int): Int {
        val row = SequenceBoard.rowOf(cell)
        val col = SequenceBoard.colOf(cell)
        var best = 0

        for ((dr, dc) in DIRECTIONS) {
            for (offset in -(RUN_LENGTH - 1)..0) {
                var count = 0
                var blocked = false
                for (step in 0 until RUN_LENGTH) {
                    val r = row + (offset + step) * dr
                    val c = col + (offset + step) * dc
                    if (r !in 0 until BOARD_SIZE || c !in 0 until BOARD_SIZE) {
                        blocked = true
                        break
                    }
                    val target = SequenceBoard.cellAt(r, c)
                    val occupant = state.chips[target]
                    when {
                        SequenceBoard.isCorner(target) -> count++
                        target == cell -> count++
                        occupant == team -> count++
                        occupant == NO_TEAM -> Unit
                        else -> {
                            blocked = true
                        }
                    }
                    if (blocked) break
                }
                if (!blocked && count > best) best = count
            }
        }
        return best
    }

    /** How close [team] is to completing a run through [cell]. */
    private fun threatAt(state: SequenceState, cell: Int, team: Int): Int =
        if (team == NO_TEAM) 0 else runPotential(state, cell, team)

    private companion object {
        val DIRECTIONS = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
    }
}
