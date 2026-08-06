package org.prolibertate.games.game.kaiser

import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.engine.GameAi

/**
 * A heuristic Kaiser opponent.
 *
 * It rates a hand by its best suit, bids only when it can see the points, and
 * in play chases the five of hearts while trying not to be the one holding the
 * trick the three of spades lands in.
 */
class KaiserAi(private val difficulty: Difficulty = Difficulty.NORMAL) :
    GameAi<KaiserState, KaiserMove> {

    enum class Difficulty(
        /** Hand strength needed before bidding above the floor. */
        val bidThreshold: Int,
    ) {
        EASY(bidThreshold = 12),
        NORMAL(bidThreshold = 16),
        CAUTIOUS(bidThreshold = 19),
    }

    override fun chooseMove(state: KaiserState, seat: Int, legal: List<KaiserMove>): KaiserMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        return when (state.phase) {
            KaiserPhase.BIDDING -> chooseBid(state, seat, legal)
            KaiserPhase.PLAYING -> choosePlay(state, seat, legal)
            else -> legal.first()
        }
    }

    /** Trump length and top cards, plus a nod to holding the counters. */
    private fun suitValue(hand: List<Card>, trump: Suit): Int {
        var value = hand.count { it.suit == trump } * 3
        for (card in hand) {
            value += when {
                card.suit == trump && card.rank == Rank.ACE -> 4
                card.suit == trump && card.rank == Rank.KING -> 3
                card == FIVE_OF_HEARTS -> 3
                card.rank == Rank.ACE -> 2
                else -> 0
            }
        }
        // Being long in spades makes the three easier to bury.
        if (hand.contains(THREE_OF_SPADES)) value -= 2
        return value
    }

    private fun chooseBid(state: KaiserState, seat: Int, legal: List<KaiserMove>): KaiserMove {
        val naming = legal.filterIsInstance<NameTrump>()
        if (naming.isNotEmpty()) {
            val hand = state.hands[seat]
            val best = naming
                .filter { it.suit != null }
                .maxByOrNull { suitValue(hand, it.suit!!) }
            return best ?: naming.first()
        }

        val hand = state.hands[seat]
        val bestSuit = Suit.entries.maxByOrNull { suitValue(hand, it) } ?: Suit.SPADES
        val strength = suitValue(hand, bestSuit)

        val bids = legal.filterIsInstance<MakeBid>().filter { !it.bid.noTrump }
        if (strength >= difficulty.bidThreshold && bids.isNotEmpty()) {
            // Bid the floor rather than reaching; the contract is what matters.
            return bids.minBy { it.bid.points }
        }
        return legal.firstOrNull { it is PassBid } ?: bids.minByOrNull { it.bid.points } ?: legal.first()
    }

    private fun choosePlay(state: KaiserState, seat: Int, legal: List<KaiserMove>): KaiserMove {
        val cards = legal.filterIsInstance<PlayCard>().map { it.card }
        if (cards.size == 1) return PlayCard(cards.first())

        if (state.trick.isEmpty()) {
            // Leading: pull with the top trump, else lead an ace.
            val trump = state.trump
            val topTrump = cards.filter { trump != null && it.suit == trump }
                .maxByOrNull { it.rank.order }
            if (topTrump != null && topTrump.rank.order >= Rank.KING.order) return PlayCard(topTrump)
            val ace = cards.filter { it.rank == Rank.ACE }.maxByOrNull { it.rank.order }
            if (ace != null) return PlayCard(ace)
            return PlayCard(cards.minBy { it.rank.order })
        }

        val ledSuit = state.trick.first().card.suit
        val best = state.trick.maxBy { trickStrength(it.card, state.trump, ledSuit) }
        val partnerWinning = partnerOf(best.seat) == seat
        val bestStrength = trickStrength(best.card, state.trump, ledSuit)
        val winners = cards.filter { trickStrength(it, state.trump, ledSuit) > bestStrength }

        // The five of hearts is worth more than the trick it rides in on.
        val fiveOnTable = state.trick.any { it.card == FIVE_OF_HEARTS }
        val threeOnTable = state.trick.any { it.card == THREE_OF_SPADES }

        return when {
            // Partner has it and the trick is worth taking: let them have it,
            // unless the three of spades is in there.
            partnerWinning && !threeOnTable -> PlayCard(cards.minBy { it.rank.order })

            fiveOnTable && winners.isNotEmpty() ->
                PlayCard(winners.maxBy { trickStrength(it, state.trump, ledSuit) })

            // Nobody wants the three; duck if the trick is already lost.
            threeOnTable && winners.isEmpty() -> PlayCard(cards.minBy { it.rank.order })

            winners.isNotEmpty() && !threeOnTable ->
                PlayCard(winners.minBy { trickStrength(it, state.trump, ledSuit) })

            else -> PlayCard(cards.minBy { it.rank.order })
        }
    }
}
