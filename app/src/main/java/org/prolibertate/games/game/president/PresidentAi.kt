package org.prolibertate.games.game.president

import org.prolibertate.games.game.engine.GameAi

/**
 * A heuristic President opponent.
 *
 * It sheds low cards early, spends the cheapest set that takes the pile, and
 * holds twos back for when they are worth something. It does not count cards.
 */
class PresidentAi(private val difficulty: Difficulty = Difficulty.NORMAL) :
    GameAi<PresidentState, PresidentMove> {

    enum class Difficulty(
        /** Rank above which a card is hoarded rather than spent to lead. */
        val hoardAbove: Int,
    ) {
        /** Plays whatever is cheapest, whenever it can. */
        EASY(hoardAbove = 99),

        NORMAL(hoardAbove = 13),

        /** Sits on high cards until late, then dumps them. */
        CAUTIOUS(hoardAbove = 11),
    }

    override fun chooseMove(
        state: PresidentState,
        seat: Int,
        legal: List<PresidentMove>,
    ): PresidentMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        val plays = legal.filterIsInstance<PlayCards>()
        if (plays.isEmpty()) return legal.first()

        return if (state.setSize == 0) chooseLead(state, seat, plays) else chooseFollow(state, plays, legal)
    }

    /**
     * Leading is a chance to shed. Prefer the biggest set of the lowest rank,
     * which clears junk fastest without spending anything valuable.
     */
    private fun chooseLead(
        state: PresidentState,
        seat: Int,
        plays: List<PlayCards>,
    ): PresidentMove {
        val endgame = state.hands[seat].size <= 4
        val candidates = plays.filter { play ->
            endgame || presidentRank(play.cards.first()) <= difficulty.hoardAbove
        }.ifEmpty { plays }

        return candidates
            .sortedWith(
                compareBy(
                    { presidentRank(it.cards.first()) },
                    { -it.cards.size },
                )
            )
            .first()
    }

    /**
     * Following costs something, so take the pile with the cheapest set that
     * does it — and don't break up a two unless nothing else works.
     */
    private fun chooseFollow(
        state: PresidentState,
        plays: List<PlayCards>,
        legal: List<PresidentMove>,
    ): PresidentMove {
        val withoutTwos = plays.filter { play -> play.cards.none { isTwo(it) } }
        val pool = withoutTwos.ifEmpty { plays }

        val cheapest = pool.minByOrNull { presidentRank(it.cards.first()) } ?: plays.first()

        // Spending a two to take a single low card is a waste while there is
        // still a game left to play.
        val spendingTwo = cheapest.cards.any { isTwo(it) }
        val worthIt = state.setRank >= 13 || state.pile.size >= 4
        if (spendingTwo && !worthIt && legal.contains(PassTurn)) return PassTurn

        return cheapest
    }
}
