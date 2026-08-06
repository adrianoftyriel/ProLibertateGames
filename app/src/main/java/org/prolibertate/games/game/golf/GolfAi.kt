package org.prolibertate.games.game.golf

import org.prolibertate.games.game.engine.GameAi

/**
 * A heuristic Golf opponent.
 *
 * It takes a card off the discard pile when it is cheap or completes a column,
 * replaces its worst known card, and turns cards over when it has nothing
 * better to do. It never peeks at a face-down card — including its own, which
 * it genuinely does not know.
 */
class GolfAi(private val difficulty: Difficulty = Difficulty.NORMAL) :
    GameAi<GolfState, GolfMove> {

    enum class Difficulty(
        /** Take a discard worth this or less rather than gambling on the stock. */
        val discardAppetite: Int,
    ) {
        EASY(discardAppetite = 2),
        NORMAL(discardAppetite = 5),
        CAUTIOUS(discardAppetite = 7),
    }

    override fun chooseMove(state: GolfState, seat: Int, legal: List<GolfMove>): GolfMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        return when (state.phase) {
            GolfPhase.DRAW -> chooseDraw(state, seat, legal)
            GolfPhase.PLACE -> choosePlace(state, seat, legal)
            else -> legal.first()
        }
    }

    private fun chooseDraw(state: GolfState, seat: Int, legal: List<GolfMove>): GolfMove {
        val top = state.discard.lastOrNull()
        if (top != null && legal.contains(DrawFromDiscard)) {
            val worthTaking = golfValue(top) <= difficulty.discardAppetite ||
                completesColumn(state, seat, top) != null
            if (worthTaking) return DrawFromDiscard
        }
        return if (legal.contains(DrawFromStock)) DrawFromStock else legal.first()
    }

    private fun choosePlace(state: GolfState, seat: Int, legal: List<GolfMove>): GolfMove {
        val drawn = state.drawn ?: return legal.first()
        val replacements = legal.filterIsInstance<ReplaceCard>()
        val flips = legal.filterIsInstance<DiscardAndFlip>()

        // Completing a column wipes out both cards, which beats any swap on value.
        completesColumn(state, seat, drawn)?.let { index ->
            replacements.firstOrNull { it.index == index }?.let { return it }
        }

        // Otherwise trade out the worst card we can actually see.
        val knownWorst = replacements
            .filter { state.revealed[seat][it.index] }
            .maxByOrNull { golfValue(state.grids[seat][it.index]) }

        if (knownWorst != null) {
            val currentValue = golfValue(state.grids[seat][knownWorst.index])
            if (golfValue(drawn) < currentValue) return knownWorst
        }

        // Nothing worth swapping: throw it and turn something over instead,
        // which at least buys information.
        if (flips.isNotEmpty()) return flips.first()

        // Forced to place it — cover a face-down card rather than a good one.
        val hidden = replacements.firstOrNull { !state.revealed[seat][it.index] }
        return hidden ?: knownWorst ?: replacements.first()
    }

    /** A grid slot where this card would complete a matching column. */
    private fun completesColumn(
        state: GolfState,
        seat: Int,
        card: org.prolibertate.games.game.cards.Card,
    ): Int? {
        val options = state.options
        for (col in 0 until options.cols) {
            val line = (0 until options.rows).map { it * options.cols + col }
            // Every other cell in the column must already show this rank.
            for (target in line) {
                val others = line.filter { it != target }
                val allMatch = others.all { index ->
                    state.revealed[seat][index] && state.grids[seat][index].rank == card.rank
                }
                if (allMatch && others.isNotEmpty()) return target
            }
        }
        return null
    }
}
