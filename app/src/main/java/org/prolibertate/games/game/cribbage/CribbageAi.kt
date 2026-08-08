package org.prolibertate.games.game.cribbage

import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Decks
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.engine.GameAi
import kotlin.math.abs

/**
 * A cribbage opponent that counts rather than guesses.
 *
 * The lay-away is the decision that decides most hands, so it is the one part
 * that is worked out exactly: every way of keeping four is scored against all
 * forty-six cards that could still be cut, and the average is compared. What
 * goes to the crib is then valued separately and added or subtracted depending
 * on whose crib it is — the same two cards are a gift to your own crib and a
 * present to the other side's.
 *
 * The play is a heuristic: take the points on offer, and try not to leave the
 * count where a ten-card collects fifteen or thirty-one.
 */
class CribbageAi : GameAi<CribbageState, CribbageMove> {

    override fun chooseMove(
        state: CribbageState,
        seat: Int,
        legal: List<CribbageMove>,
    ): CribbageMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        val layAways = legal.filterIsInstance<LayAway>()
        if (layAways.isNotEmpty()) return chooseLayAway(state, seat, layAways)
        return choosePeg(state, legal.filterIsInstance<PegCard>())
    }

    private fun chooseLayAway(
        state: CribbageState,
        seat: Int,
        choices: List<LayAway>,
    ): LayAway {
        val hand = state.hands[seat]
        val held = hand.toSet()
        val unseen = Decks.standard52().filter { it !in held }
        // A partner's crib is your crib. That is the whole of four-handed play.
        val ownCrib = state.options.teamOf(seat) == state.options.teamOf(state.dealer)

        return choices.maxByOrNull { choice ->
            val given = choice.cards.toSet()
            val keep = hand.filter { it !in given }
            val expected = unseen.sumOf { starter ->
                CribbageScoring.show(keep, starter, isCrib = false).sumOf { it.points }
            }.toDouble() / unseen.size
            expected + cribValue(choice.cards) * (if (ownCrib) 1.0 else -1.0)
        } ?: choices.first()
    }

    /**
     * Roughly what a lay-away is worth to whoever owns the crib.
     *
     * A five is the card of the game — every one of the sixteen ten-cards makes
     * fifteen with it — so it is never given away cheaply, and a pair or a
     * fifteen laid away together is two points the crib scores whatever is cut.
     */
    private fun cribValue(cards: List<Card>): Double {
        var value = cards.sumOf { card ->
            when (card.rank) {
                Rank.FIVE -> 2.0
                Rank.JACK -> 0.6
                Rank.TWO, Rank.THREE -> 0.3
                Rank.ACE, Rank.QUEEN, Rank.KING -> 0.1
                else -> 0.0
            }
        }
        if (cards.size == 2) {
            val first = cards[0]
            val second = cards[1]
            if (first.rank == second.rank) value += 2.0
            if (pipValue(first) + pipValue(second) == 15) value += 2.0
            when (abs(runOrder(first) - runOrder(second))) {
                1 -> value += 1.0
                2 -> value += 0.5
            }
        }
        return value
    }

    private fun choosePeg(state: CribbageState, choices: List<PegCard>): PegCard {
        val count = state.count
        val onTheTable = state.series.map { it.card }

        return choices.maxByOrNull { choice ->
            val scored = CribbageScoring.peg(onTheTable + choice.card).sumOf { it.points }
            val after = count + pipValue(choice.card)
            var value = scored * 10.0
            // Five and twenty-one are the two counts to avoid leaving: any of
            // the sixteen ten-cards takes fifteen or thirty-one off them.
            if (after == 5 || after == 21) value -= 3.0
            // Leading a five is the same present, offered first.
            if (count == 0 && choice.card.rank == Rank.FIVE) value -= 4.0
            // Big cards early. Low ones are what let you keep playing at the
            // awkward end of a series, where the last card is worth a point.
            value + pipValue(choice.card) * 0.1
        } ?: choices.first()
    }
}
