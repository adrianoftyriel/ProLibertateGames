package org.prolibertate.games.game.wizard

import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.engine.GameAi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A heuristic Wizard opponent.
 *
 * Bidding counts the tricks the hand can reasonably expect: a wizard is one,
 * a jester is none, and everything else is worth a fraction depending on how
 * high it is and whether it is trump. In play it chases tricks while it is
 * short of its bid and ducks once it has them, which is the whole game.
 */
class WizardAi(private val difficulty: Difficulty = Difficulty.NORMAL) :
    GameAi<WizardState, WizardMove> {

    enum class Difficulty(
        /**
         * Added to the expected-trick count before rounding. Optimists overbid
         * and go down; pessimists underbid and get caught taking tricks they
         * did not want.
         */
        val bidBias: Double,
    ) {
        EASY(bidBias = 0.35),
        NORMAL(bidBias = 0.0),
        CAUTIOUS(bidBias = -0.25),
    }

    override fun chooseMove(state: WizardState, seat: Int, legal: List<WizardMove>): WizardMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        return when (state.phase) {
            WizardPhase.BIDDING -> chooseBid(state, seat, legal)
            WizardPhase.PLAYING -> choosePlay(state, seat, legal)
            else -> legal.first()
        }
    }

    // -----------------------------------------------------------------------
    // Bidding
    // -----------------------------------------------------------------------

    private fun chooseBid(state: WizardState, seat: Int, legal: List<WizardMove>): WizardMove {
        val naming = legal.filterIsInstance<ChooseTrump>()
        if (naming.isNotEmpty()) {
            val hand = state.hands[seat]
            val best = naming.maxByOrNull { suitStrength(hand, it.suit) }
            return best ?: naming.first()
        }

        val bids = legal.filterIsInstance<MakeBid>()
        if (bids.isEmpty()) return legal.first()

        val wanted = (expectedTricks(state.hands[seat], state.trump) + difficulty.bidBias)
            .roundToInt()
            .coerceIn(0, state.cardsThisRound)
        // Screw-the-dealer may have taken the number we wanted away, so settle
        // for the nearest one still on offer.
        return bids.minBy { abs(it.tricks - wanted) }
    }

    /** How much of a hand a given trump suit would be worth. */
    private fun suitStrength(hand: List<Card>, suit: Suit): Double =
        expectedTricks(hand, suit)

    /**
     * Roughly how many tricks a hand should take. Deliberately blunt: it does
     * not know how many cards are out, only how good its own are.
     */
    private fun expectedTricks(hand: List<Card>, trump: Suit?): Double {
        var expected = 0.0
        for (card in hand) {
            expected += when {
                isWizardCard(card) -> 1.0
                isJester(card) -> 0.0
                trump != null && card.suit == trump -> when {
                    card.rank.order >= Rank.KING.order -> 0.9
                    card.rank.order >= Rank.JACK.order -> 0.6
                    else -> 0.3
                }

                card.rank == Rank.ACE -> 0.75
                card.rank == Rank.KING -> 0.45
                card.rank == Rank.QUEEN -> 0.2
                else -> 0.0
            }
        }
        return expected
    }

    // -----------------------------------------------------------------------
    // Play
    // -----------------------------------------------------------------------

    private fun choosePlay(state: WizardState, seat: Int, legal: List<WizardMove>): WizardMove {
        val cards = legal.filterIsInstance<PlayCard>().map { it.card }
        if (cards.size == 1) return PlayCard(cards.first())

        val bid = state.bids[seat] ?: 0
        val stillNeeded = bid - state.tricksWon[seat]
        val tricksLeft = state.hands[seat].size
        // Chase while we are short, and only while there are enough tricks left
        // to be worth chasing.
        val wantsTrick = stillNeeded > 0 && stillNeeded <= tricksLeft

        if (state.trick.isEmpty()) return PlayCard(chooseLead(cards, state.trump, wantsTrick))

        val ledSuit = ledSuitOf(state.trick)
        val order = state.trick.size
        val bestOnTable = state.trick
            .withIndex()
            .maxOf { (index, played) -> trickStrength(played.card, index, state.trump, ledSuit) }

        // A card played now takes the position after everything already down.
        val strengthOf = { card: Card -> trickStrength(card, order, state.trump, ledSuit) }
        val winners = cards.filter { strengthOf(it) > bestOnTable }
        val losers = cards.filter { strengthOf(it) <= bestOnTable }

        return when {
            // Take it as cheaply as we can.
            wantsTrick && winners.isNotEmpty() -> PlayCard(winners.minBy { strengthOf(it) })
            // Cannot take it, or do not want it: throw the least useful card.
            losers.isNotEmpty() -> PlayCard(losers.minBy { strengthOf(it) })
            // Everything we hold wins, and we did not want the trick. Take it
            // with the weakest winner so the good cards stay for later.
            else -> PlayCard(cards.minBy { strengthOf(it) })
        }
    }

    private fun chooseLead(cards: List<Card>, trump: Suit?, wantsTrick: Boolean): Card {
        if (wantsTrick) {
            // A led wizard is an unloseable trick.
            cards.firstOrNull { isWizardCard(it) }?.let { return it }
            val topTrump = cards.filter { trump != null && it.suit == trump }
                .maxByOrNull { it.rank.order }
            if (topTrump != null && topTrump.rank.order >= Rank.QUEEN.order) return topTrump
            cards.firstOrNull { it.rank == Rank.ACE && !isJester(it) }?.let { return it }
            return cards.filterNot { isJester(it) }.maxByOrNull { it.rank.order } ?: cards.first()
        }

        // Ducking: a led jester cannot take anything at all.
        cards.firstOrNull { isJester(it) }?.let { return it }
        return cards.filterNot { isWizardCard(it) }
            .minByOrNull { it.rank.order + if (trump != null && it.suit == trump) 20 else 0 }
            ?: cards.first()
    }
}
