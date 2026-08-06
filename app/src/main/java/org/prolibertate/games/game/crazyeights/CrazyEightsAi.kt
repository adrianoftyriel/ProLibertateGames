package org.prolibertate.games.game.crazyeights

import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.engine.GameAi

/**
 * A heuristic Crazy 8s opponent.
 *
 * It sheds expensive cards first, keeps its eights back for when it is stuck,
 * and when it does play one it names the suit it holds most of.
 */
class CrazyEightsAi(private val difficulty: Difficulty = Difficulty.NORMAL) :
    GameAi<CrazyEightsState, CrazyEightsMove> {

    enum class Difficulty(
        /** Play an eight anyway once the hand is down to this size. */
        val eightEndgame: Int,
    ) {
        EASY(eightEndgame = 99),
        NORMAL(eightEndgame = 3),
        CAUTIOUS(eightEndgame = 2),
    }

    override fun chooseMove(
        state: CrazyEightsState,
        seat: Int,
        legal: List<CrazyEightsMove>,
    ): CrazyEightsMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        val plays = legal.filterIsInstance<PlayCard>()
        if (plays.isEmpty()) return legal.first()

        val hand = state.hands[seat]
        val ordinary = plays.filter { !isWild(it.card) }

        // An eight is the only card that always plays, so it is worth more held
        // than spent — unless the hand is nearly gone, or nothing else works.
        val useEight = ordinary.isEmpty() || hand.size <= difficulty.eightEndgame
        val pool = if (useEight) plays else ordinary

        val best = pool
            .filter { !isWild(it.card) }
            .maxByOrNull { crazyEightsPenalty(it.card) }

        if (best != null) return best

        // Playing an eight: switch the game to whatever we hold most of.
        val strongest = Suit.entries.maxByOrNull { suit -> hand.count { it.suit == suit } }
            ?: Suit.SPADES
        return pool.firstOrNull { it.nominatedSuit == strongest } ?: pool.first()
    }
}
