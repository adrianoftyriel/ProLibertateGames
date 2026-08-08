package org.prolibertate.games.game.yahtzee

import org.prolibertate.games.game.engine.GameAi

/**
 * Plays the dice by eye rather than by arithmetic.
 *
 * It deliberately does not try each way of keeping and look at what came back.
 * The throw is a pure function of the state and what is held, so an AI that
 * enumerated the thirty-two subsets and applied them would be reading the roll
 * before making it — beating the player by seeing the future rather than by
 * playing well. Everything below decides from the dice already on the table.
 */
object YahtzeeAi : GameAi<YahtzeeState, YahtzeeMove> {

    override fun chooseMove(state: YahtzeeState, seat: Int, legal: List<YahtzeeMove>): YahtzeeMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        val rolls = legal.filterIsInstance<RollDice>()
        val writes = legal.filterIsInstance<ScoreIn>()

        // Nothing on the table yet, so there is only one thing to do.
        if (!state.hasRolled) return rolls.firstOrNull() ?: writes.first()

        val best = writes.maxByOrNull { scoreOf(it.category, state.dice) }
        val bestScore = best?.let { scoreOf(it.category, state.dice) } ?: 0

        // A hand already worth taking, or no rolls left, ends the turn.
        if (rolls.isEmpty()) return best ?: writes.first()
        if (bestScore >= WORTH_KEEPING) return best!!

        val keep = whatToKeep(state.dice).toSet()
        return rolls.firstOrNull { it.keep == keep } ?: rolls.first()
    }

    /**
     * Good enough to stop throwing. Set at a full house, which is the point
     * where a hand is worth more than an average reroll of it.
     */
    private const val WORTH_KEEPING = 25

    /**
     * What to hold on to: a run if one is nearly there, otherwise the face there
     * is most of. Holding the biggest group is what turns three of a kind into
     * four, and it is the whole of ordinary dice play.
     */
    private fun whatToKeep(dice: List<Int>): List<Int> {
        val run = runIndices(dice)
        if (run.size >= 4) return run

        val counts = countsOf(dice)
        val most = counts.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
            .first()
        if (most.value >= 2) {
            return dice.indices.filter { dice[it] == most.key }
        }
        // Five different faces and no run worth keeping: hold the big ones and
        // throw the rest.
        return dice.indices.filter { dice[it] >= 5 }
    }

    /** Positions making up the longest run of consecutive faces, one die per face. */
    private fun runIndices(dice: List<Int>): List<Int> {
        val faces = dice.toSortedSet().toList()
        var bestStart = 0
        var bestLength = 0
        var start = 0
        for (i in faces.indices) {
            if (i > 0 && faces[i] != faces[i - 1] + 1) start = i
            val length = i - start + 1
            if (length > bestLength) {
                bestLength = length
                bestStart = start
            }
        }
        if (bestLength < 4) return emptyList()
        val wanted = faces.subList(bestStart, bestStart + bestLength).toSet()
        // One die per face: a second five adds nothing to a straight.
        val taken = mutableSetOf<Int>()
        return dice.indices.filter { index ->
            val face = dice[index]
            face in wanted && taken.add(face)
        }
    }
}
