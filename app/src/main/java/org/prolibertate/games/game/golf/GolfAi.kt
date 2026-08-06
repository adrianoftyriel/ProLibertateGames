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
        /** Only close the hole out when the visible score is at or under this. */
        val closeOutBelow: Int,
    ) {
        EASY(discardAppetite = 2, closeOutBelow = 30),
        NORMAL(discardAppetite = 5, closeOutBelow = 16),
        CAUTIOUS(discardAppetite = 7, closeOutBelow = 12),
    }

    override fun chooseMove(state: GolfState, seat: Int, legal: List<GolfMove>): GolfMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        return when (state.phase) {
            GolfPhase.SETUP -> chooseReveal(state, seat, legal)
            GolfPhase.DRAW -> chooseDraw(state, seat, legal)
            GolfPhase.PLACE -> choosePlace(state, seat, legal)
            else -> legal.first()
        }
    }

    /**
     * Opening reveals are spread across columns. Two cards in the same column
     * tell you whether that column can be cancelled but nothing about the rest
     * of the board; one in each column leaves more of the grid legible.
     */
    private fun chooseReveal(state: GolfState, seat: Int, legal: List<GolfMove>): GolfMove {
        val reveals = legal.filterIsInstance<RevealCard>()
        if (reveals.isEmpty()) return legal.first()
        val options = state.options

        val untouchedColumn = reveals.firstOrNull { move ->
            val column = move.index % options.cols
            (0 until options.rows).none { row ->
                state.revealed[seat][row * options.cols + column]
            }
        }
        return untouchedColumn ?: reveals.first()
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

        // On the last face-down card, turning it over ends the hole for
        // everyone. Only do that from a position worth defending; otherwise
        // line up the putt and wait for a better card.
        if (legal.contains(DiscardOnly)) {
            val showing = state.grids[seat].indices
                .filter { state.revealed[seat][it] }
                .sumOf { golfValue(state.grids[seat][it]) }
            if (showing > difficulty.closeOutBelow) return DiscardOnly
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
