package org.prolibertate.games.game.solitaire

import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank

/**
 * Where a rank sits when the ace is low.
 *
 * [Rank.order] puts the ace at fourteen, which is right for the games this deck
 * was built for: in Euchre, Kaiser, Wizard and the rest it is the highest card
 * there is. Every patience in this app counts it the other way — foundations run
 * ace upwards, columns run down to an ace, and Pyramid needs it to be worth one
 * when a pair has to make thirteen.
 *
 * It lives here rather than on [Rank] because ace high is correct everywhere
 * outside these games, and it is shared rather than copied because four
 * separate definitions of "the ace is low" would be four chances to disagree.
 */
val Rank.patienceOrder: Int get() = if (this == Rank.ACE) 1 else order

/** Down by one, and in the other colour: the ordinary tableau build. */
fun buildsDownAlternating(lower: Card, onto: Card): Boolean =
    lower.rank.patienceOrder == onto.rank.patienceOrder - 1 &&
        lower.suit.isRed != onto.suit.isRed

/** Down by one in the same suit, which is what Spider pays for. */
fun buildsDownInSuit(lower: Card, onto: Card): Boolean =
    lower.rank.patienceOrder == onto.rank.patienceOrder - 1 && lower.suit == onto.suit

/** Ace first, then strictly upwards in the same suit. */
fun acceptsOnFoundation(foundation: List<Card>, card: Card): Boolean {
    val top = foundation.lastOrNull() ?: return card.rank == Rank.ACE
    return card.suit == top.suit && card.rank.patienceOrder == top.rank.patienceOrder + 1
}

/**
 * Whether [cards] is a run that travels as one, by whatever rule the game
 * builds with.
 */
fun isRun(cards: List<Card>, builds: (Card, Card) -> Boolean): Boolean =
    cards.isNotEmpty() && cards.zipWithNext().all { (upper, lower) -> builds(lower, upper) }
