package org.prolibertate.games.game.spider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.solitaire.patienceOrder

const val COLUMNS: Int = 10

/** Eight complete runs, king down to ace, is the whole pack cleared. */
const val RUNS_TO_WIN: Int = 8
const val RUN_LENGTH: Int = 13

@Serializable
data class SpiderOptions(
    /**
     * How many suits the two packs are made of. One suit is the game people
     * actually finish; four is the real thing and is brutal. Two sits between
     * them. The pack is always a hundred and four cards either way — only how
     * many different suits those cards are drawn from changes.
     */
    val suits: Int = 1,
) {
    init {
        require(suits == 1 || suits == 2 || suits == 4) { "Spider is played with one, two or four suits" }
    }
}

@Serializable
data class SpiderPile(
    val faceDown: List<Card> = emptyList(),
    val faceUp: List<Card> = emptyList(),
) {
    val isEmpty: Boolean get() = faceDown.isEmpty() && faceUp.isEmpty()
    val top: Card? get() = faceUp.lastOrNull()
}

@Serializable
data class SpiderState(
    val options: SpiderOptions,
    val seed: Long,
    val stock: List<Card>,
    val tableau: List<SpiderPile>,
    /** Runs sent away complete. Eight of them wins. */
    val completed: Int,
    val moves: Int,
    val log: List<String>,
) {
    val isWon: Boolean get() = completed == RUNS_TO_WIN

    /**
     * A row cannot be dealt onto an empty column — the rule that stops a deal
     * from being used to dodge a position with nowhere to put anything.
     */
    val canDeal: Boolean get() = stock.isNotEmpty() && tableau.none { it.isEmpty }
}

@Serializable
sealed interface SpiderMove

/** One card face up onto every column at once. */
@Serializable
@SerialName("deal")
data object DealRow : SpiderMove

@Serializable
@SerialName("move")
data class MoveRun(val from: Int, val to: Int, val count: Int) : SpiderMove

/**
 * Down by one in the same suit.
 *
 * Spider lets a card *land* on any suit a rank higher, but only a same-suit run
 * may travel as a unit — which is the whole difficulty of the four-suit game.
 */
fun sameSuitRun(cards: List<Card>): Boolean =
    cards.isNotEmpty() && cards.zipWithNext().all { (upper, lower) ->
        lower.suit == upper.suit && lower.rank.patienceOrder == upper.rank.patienceOrder - 1
    }

/** What a column will take on top: anything one rank higher, of any suit. */
fun landsOn(card: Card, onto: Card): Boolean =
    card.rank.patienceOrder == onto.rank.patienceOrder - 1

/** King down to ace in one suit, which is a run ready to go away. */
fun isCompleteRun(cards: List<Card>): Boolean =
    cards.size == RUN_LENGTH &&
        cards.first().rank == Rank.KING &&
        cards.last().rank == Rank.ACE &&
        sameSuitRun(cards)

/**
 * A hundred and four cards drawn from [suits] suits.
 *
 * One suit means eight identical spade packs; four means two of each ordinary
 * suit. The count never changes, so the deal is the same shape whatever the
 * table chose.
 */
fun spiderDeck(suits: Int): List<Card> {
    val used = Suit.entries.take(suits)
    val copies = 8 / suits
    return used.flatMap { suit ->
        List(copies) { Rank.standard.map { Card(it, suit) } }.flatten()
    }
}
