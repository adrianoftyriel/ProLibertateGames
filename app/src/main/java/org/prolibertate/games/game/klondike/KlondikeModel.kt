package org.prolibertate.games.game.klondike

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.solitaire.buildsDownAlternating

const val TABLEAU_PILES: Int = 7
const val FOUNDATIONS: Int = 4

/**
 * Where a card can be, and which one of them.
 *
 * A kind and an index rather than a sealed hierarchy: every spot in this game is
 * one of four things and at most one number identifies it, so a pair says
 * everything a subclass per kind would, and serialises as two plain fields.
 */
@Serializable
enum class SpotKind { STOCK, WASTE, TABLEAU, FOUNDATION }

@Serializable
data class Spot(val kind: SpotKind, val index: Int = 0) {
    companion object {
        val stock = Spot(SpotKind.STOCK)
        val waste = Spot(SpotKind.WASTE)
        fun tableau(index: Int) = Spot(SpotKind.TABLEAU, index)
        fun foundation(index: Int) = Spot(SpotKind.FOUNDATION, index)
    }
}

/**
 * A tableau pile: what is still turned down, and the run turned up on top of it.
 *
 * Kept as two lists because the split is the whole tension of the game. A pile
 * is only worth what has been turned up, and the buried part is the thing being
 * played for.
 */
@Serializable
data class TableauPile(
    val faceDown: List<Card> = emptyList(),
    val faceUp: List<Card> = emptyList(),
) {
    val isEmpty: Boolean get() = faceDown.isEmpty() && faceUp.isEmpty()
    val top: Card? get() = faceUp.lastOrNull()
}

@Serializable
data class KlondikeOptions(
    /**
     * One card off the stock at a time, or three. Three is the harder game and
     * the older one; one is what most people mean by patience now.
     */
    val drawCount: Int = 1,
    /**
     * How many times the waste may be turned back into the stock. Null is as
     * often as you like; zero is a single pass through the pack, which is the
     * gambling version.
     */
    val redealLimit: Int? = null,
    /**
     * Only a king may be moved into an empty column. Turning this off is a
     * common kindness that makes far more deals winnable.
     */
    val kingsOnlyInSpaces: Boolean = true,
) {
    init {
        require(drawCount in 1..3) { "Draw one to three" }
        require(redealLimit == null || redealLimit >= 0) { "redealLimit cannot be negative" }
    }
}

@Serializable
data class KlondikeState(
    val options: KlondikeOptions,
    val seed: Long,
    /** Face down, dealt from the end. */
    val stock: List<Card>,
    /** Face up; the last card is the one in play. */
    val waste: List<Card>,
    /** One per suit, in [Suit] order, each running ace upwards. */
    val foundations: List<List<Card>>,
    val tableau: List<TableauPile>,
    val redealsUsed: Int,
    val moves: Int,
    val log: List<String>,
) {
    /** Every foundation full is the game won. */
    val isWon: Boolean get() = foundations.all { it.size == Rank.standard.size }

    val canRedeal: Boolean
        get() = stock.isEmpty() &&
            waste.isNotEmpty() &&
            (options.redealLimit == null || redealsUsed < options.redealLimit)
}

@Serializable
sealed interface KlondikeMove

/** Turns the next card or three off the stock and onto the waste. */
@Serializable
@SerialName("draw")
data object Draw : KlondikeMove

/** Turns the waste back over to make a new stock, once the stock is out. */
@Serializable
@SerialName("redeal")
data object Redeal : KlondikeMove

/**
 * Moves [count] cards from one spot to another.
 *
 * [count] is only ever more than one when moving between tableau columns, where
 * a whole descending run travels together.
 */
@Serializable
@SerialName("move")
data class MoveCards(val from: Spot, val to: Spot, val count: Int = 1) : KlondikeMove

/** Foundations are indexed by suit, so a card always knows which one it wants. */
fun foundationFor(card: Card): Int = card.suit.ordinal

/**
 * Down by one and in the other colour.
 *
 * Named here because it is what this game builds with, but the rule itself is
 * shared: FreeCell builds the same way, Spider builds in suit, and all of them
 * need the same ace-low ordering underneath.
 */
fun buildsDown(lower: Card, onto: Card): Boolean = buildsDownAlternating(lower, onto)

/**
 * Whether [cards] is a run that can travel as one: each card a step below the
 * one before it and in the opposite colour.
 */
fun isMovableRun(cards: List<Card>): Boolean =
    cards.isNotEmpty() && cards.zipWithNext().all { (upper, lower) -> buildsDown(lower, upper) }

/** What an empty column will take. */
fun acceptsInSpace(card: Card, kingsOnly: Boolean): Boolean =
    !kingsOnly || card.rank == Rank.KING

/** Suit order is the foundation order, so this names them for the screen. */
fun foundationSuit(index: Int): Suit = Suit.entries[index]
