package org.prolibertate.games.game.hearts

import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.engine.GameAi

/**
 * A cautious Hearts player.
 *
 * It plays to avoid points rather than to shoot the moon: it sheds the queen of
 * spades and the guards above her at the first opportunity, ducks under whoever
 * is winning a trick when it can, and takes a trick as cheaply as possible when
 * it cannot get out of the way. That is enough to make it an opponent worth
 * beating without pretending to read the table.
 */
object HeartsAi : GameAi<HeartsState, HeartsMove> {

    override fun chooseMove(state: HeartsState, seat: Int, legal: List<HeartsMove>): HeartsMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        return when (state.phase) {
            HeartsPhase.PASSING -> choosePass(state, seat, legal)
            else -> choosePlay(state, seat, legal)
        }
    }

    private fun choosePass(state: HeartsState, seat: Int, legal: List<HeartsMove>): HeartsMove {
        val hand = state.hands[seat]
        val wanted = hand.sortedByDescending { passDanger(it, hand) }.take(PASS_SIZE).toSet()
        // Prefer the exact bundle the heuristic asked for, but fall back rather
        // than fail: the caller's list is what the host will validate against.
        return legal.firstOrNull { it is PassCards && it.cards.toSet() == wanted } ?: legal.first()
    }

    /**
     * How much a card wants to be somebody else's problem.
     *
     * The queen is the whole game, and the ace and king of spades are only
     * dangerous because they can be forced to capture her — so a long spade
     * holding, which gives room to duck, makes all three far less urgent.
     */
    private fun passDanger(card: Card, hand: List<Card>): Int {
        val spades = hand.count { it.suit == Suit.SPADES }
        val guarded = spades >= 5
        return when {
            card == QUEEN_OF_SPADES -> if (guarded) 45 else 100
            card.suit == Suit.SPADES && card.rank.order > Rank.QUEEN.order ->
                if (guarded) 30 else 90

            card.suit == Suit.HEARTS -> 40 + card.rank.order
            else -> card.rank.order
        }
    }

    private fun choosePlay(state: HeartsState, seat: Int, legal: List<HeartsMove>): HeartsMove {
        val cards = legal.filterIsInstance<PlayCard>().map { it.card }
        if (cards.isEmpty()) return legal.first()
        val chosen = when {
            state.trick.isEmpty() -> lead(cards)
            else -> follow(state, cards)
        }
        return PlayCard(chosen)
    }

    /**
     * Leads low, and keeps off spades while the queen is still unaccounted for —
     * leading a spade under her is how a seat ends up holding her.
     */
    private fun lead(cards: List<Card>): Card {
        val offSpades = cards.filter { it.suit != Suit.SPADES }
        val pool = if (offSpades.isNotEmpty()) offSpades else cards
        return pool.minBy { it.rank.order }
    }

    private fun follow(state: HeartsState, cards: List<Card>): Card {
        val led = state.trick.first().card.suit
        val best = state.trick.maxOf { trickStrength(it.card, led) }
        val pot = pointsIn(state.trick.map { it.card })
        val last = state.trick.size == state.options.playerCount - 1

        val ducking = cards.filter { trickStrength(it, led) < best }
        if (ducking.isNotEmpty()) {
            // Safe under the current winner, so shed the most expensive card
            // that still loses.
            return ducking.maxBy { it.rank.order + pointsOf(it) * 2 }
        }

        val void = cards.none { it.suit == led }
        if (void) return discard(cards)

        // Every card left would take the trick. Playing last with nothing in the
        // pot means it costs nothing, so there is no reason to waste a high card
        // on it; otherwise take it as cheaply as possible.
        return if (last && pot == 0) cards.maxBy { it.rank.order } else cards.minBy { it.rank.order }
    }

    /** Void in the led suit and free to throw anything, so throw the worst of it. */
    private fun discard(cards: List<Card>): Card {
        cards.firstOrNull { it == QUEEN_OF_SPADES }?.let { return it }
        cards.filter { it.suit == Suit.SPADES && it.rank.order > Rank.QUEEN.order }
            .maxByOrNull { it.rank.order }
            ?.let { return it }
        cards.filter { it.suit == Suit.HEARTS }
            .maxByOrNull { it.rank.order }
            ?.let { return it }
        return cards.maxBy { it.rank.order }
    }
}
