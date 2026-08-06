package org.prolibertate.games.game.euchre

import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.engine.GameAi

/**
 * A heuristic Euchre opponent.
 *
 * It does not look at anybody else's cards and it does not search — it counts
 * trump, respects its partner, and plays the cheapest card that does the job.
 * That is enough to be a reasonable table opponent without pretending to be a
 * solver.
 */
class EuchreAi(private val difficulty: Difficulty = Difficulty.NORMAL) :
    GameAi<EuchreState, EuchreMove> {

    /**
     * Thresholds are calibrated against the score an average five-card hand
     * earns from [handValue], which sits around 11. A threshold near that mean
     * means every seat bids and the card is never turned down — see
     * EuchreBiddingRateTest, which pins the resulting order-up rate.
     */
    enum class Difficulty(val bidThreshold: Int, val aloneThreshold: Int) {
        /** Bids loosely and plays straightforwardly. */
        EASY(bidThreshold = 21, aloneThreshold = 32),

        NORMAL(bidThreshold = 24, aloneThreshold = 32),

        /** Bids tighter and is harder to sneak a trick past. */
        CAUTIOUS(bidThreshold = 27, aloneThreshold = 31),
    }

    override fun chooseMove(state: EuchreState, seat: Int, legal: List<EuchreMove>): EuchreMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        return when (state.phase) {
            EuchrePhase.BID_ROUND_1 -> chooseBidRound1(state, seat, legal)
            EuchrePhase.BID_ROUND_2 -> chooseBidRound2(state, seat, legal)
            EuchrePhase.DEALER_DISCARD -> chooseDiscard(state, legal)
            EuchrePhase.PLAYING -> choosePlay(state, seat, legal)
            else -> legal.first()
        }
    }

    // -----------------------------------------------------------------------
    // Bidding
    // -----------------------------------------------------------------------

    /**
     * Rates a hand for a candidate trump suit. Bowers and trump length carry
     * the hand; off-suit aces are worth a trick more often than not.
     */
    private fun handValue(hand: List<Card>, trump: Suit): Int {
        var value = 0
        var trumpCount = 0
        for (card in hand) {
            value += when {
                isRightBower(card, trump) -> 10
                isLeftBower(card, trump) -> 8
                isTrumpCard(card, trump) -> 4 + (card.rank.order - Rank.NINE.order) / 2
                card.rank == Rank.ACE -> 3
                card.rank == Rank.KING -> 1
                else -> 0
            }
            if (isTrumpCard(card, trump)) trumpCount++
        }
        // A void is a chance to trump in — but only while there is trump left
        // to do it with. Counting voids on a trumpless hand was inflating
        // exactly the weak hands that should be passing.
        if (trumpCount >= 2) {
            val sideSuits = Suit.entries.filter { it != trump }
            value += sideSuits.count { suit -> hand.none { effectiveSuit(it, trump) == suit } } * 2
        }
        return value
    }

    private fun chooseBidRound1(
        state: EuchreState,
        seat: Int,
        legal: List<EuchreMove>,
    ): EuchreMove {
        val upCard = state.upCard ?: return Pass
        val trump = upCard.suit
        var hand = state.hands[seat]

        // If our partner is the dealer they gain the turn card, and if we are
        // the dealer we gain it ourselves — both make the hand stronger than it
        // looks. Ordering up into an opponent dealer instead hands them a card.
        if (seat == state.dealer) hand = hand + upCard
        var value = handValue(hand, trump)
        if (partnerOf(seat) == state.dealer) value += 2
        if (seat != state.dealer && partnerOf(seat) != state.dealer) value -= 2

        val alone = OrderUp(alone = true)
        if (value >= difficulty.aloneThreshold && legal.contains(alone)) return alone
        val order = OrderUp(alone = false)
        if (value >= difficulty.bidThreshold && legal.contains(order)) return order
        return if (legal.contains(Pass)) Pass else legal.first()
    }

    private fun chooseBidRound2(
        state: EuchreState,
        seat: Int,
        legal: List<EuchreMove>,
    ): EuchreMove {
        val hand = state.hands[seat]
        val callable = legal.filterIsInstance<CallTrump>()
        if (callable.isEmpty()) return legal.first()

        val best = callable
            .filter { !it.alone }
            .maxByOrNull { handValue(hand, it.suit) }
            ?: callable.first()
        val bestValue = handValue(hand, best.suit)

        val alone = callable.firstOrNull { it.alone && it.suit == best.suit }
        if (alone != null && bestValue >= difficulty.aloneThreshold) return alone
        if (bestValue >= difficulty.bidThreshold) return best
        // Stuck as dealer with a bad hand: name the least-bad suit anyway.
        return if (legal.contains(Pass)) Pass else best
    }

    private fun chooseDiscard(state: EuchreState, legal: List<EuchreMove>): EuchreMove {
        val trump = requireNotNull(state.trump)
        val discards = legal.filterIsInstance<Discard>()
        // Prefer to void a side suit entirely; otherwise throw the lowest card.
        val hand = state.hands[state.dealer]
        val suitCounts = hand.groupingBy { effectiveSuit(it, trump) }.eachCount()
        return discards.minByOrNull { discard ->
            val card = discard.card
            val suit = effectiveSuit(card, trump)
            val trumpPenalty = if (suit == trump) 1000 else 0
            val singletonBonus = if (suitCounts[suit] == 1 && card.rank != Rank.ACE) -20 else 0
            trumpPenalty + singletonBonus + card.rank.order
        } ?: discards.first()
    }

    // -----------------------------------------------------------------------
    // Card play
    // -----------------------------------------------------------------------

    private fun choosePlay(
        state: EuchreState,
        seat: Int,
        legal: List<EuchreMove>,
    ): EuchreMove {
        val trump = requireNotNull(state.trump)
        val cards = legal.filterIsInstance<PlayCard>().map { it.card }
        if (cards.size == 1) return PlayCard(cards.first())

        if (state.trick.isEmpty()) return PlayCard(chooseLead(cards, trump))

        val ledSuit = effectiveSuit(state.trick.first().card, trump)
        val bestSoFar = state.trick.maxBy { trickStrength(it.card, trump, ledSuit) }
        val partnerWinning = partnerOf(bestSoFar.seat) == seat
        val lastToPlay = state.trick.size == state.activeSeatCount - 1

        // Partner already has it and we are last: never waste a good card.
        if (partnerWinning && lastToPlay) return PlayCard(lowest(cards, trump, ledSuit))

        val bestStrength = trickStrength(bestSoFar.card, trump, ledSuit)
        val winners = cards.filter { trickStrength(it, trump, ledSuit) > bestStrength }
        return when {
            // Take it as cheaply as we can.
            winners.isNotEmpty() && (!partnerWinning || !lastToPlay) ->
                PlayCard(winners.minBy { trickStrength(it, trump, ledSuit) })

            else -> PlayCard(lowest(cards, trump, ledSuit))
        }
    }

    private fun chooseLead(cards: List<Card>, trump: Suit): Card {
        // Right bower up front pulls trump and protects the left.
        cards.firstOrNull { isRightBower(it, trump) }?.let { return it }
        // Off-suit aces are most likely to survive early.
        cards.filter { !isTrumpCard(it, trump) && it.rank == Rank.ACE }
            .maxByOrNull { it.rank.order }
            ?.let { return it }
        val offSuit = cards.filter { !isTrumpCard(it, trump) }
        if (offSuit.isNotEmpty()) return offSuit.maxBy { it.rank.order }
        return cards.maxBy { trumpStrength(it, trump) }
    }

    private fun lowest(cards: List<Card>, trump: Suit, ledSuit: Suit): Card =
        cards.minBy { trickStrength(it, trump, ledSuit).takeIf { s -> s > 0 } ?: it.rank.order }
}
