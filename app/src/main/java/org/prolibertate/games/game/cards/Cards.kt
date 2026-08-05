package org.prolibertate.games.game.cards

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
enum class Suit(val symbol: String, val isRed: Boolean) {
    CLUBS("♣", false),
    DIAMONDS("♦", true),
    HEARTS("♥", true),
    SPADES("♠", false);

    /** Euchre's left bower lives in the suit of the same colour. */
    fun sameColour(): Suit = when (this) {
        CLUBS -> SPADES
        SPADES -> CLUBS
        DIAMONDS -> HEARTS
        HEARTS -> DIAMONDS
    }
}

@Serializable
enum class Rank(val short: String, val order: Int) {
    TWO("2", 2),
    THREE("3", 3),
    FOUR("4", 4),
    FIVE("5", 5),
    SIX("6", 6),
    SEVEN("7", 7),
    EIGHT("8", 8),
    NINE("9", 9),
    TEN("10", 10),
    JACK("J", 11),
    QUEEN("Q", 12),
    KING("K", 13),
    ACE("A", 14),
}

@Serializable
data class Card(val rank: Rank, val suit: Suit) {
    /** Compact form used in logs and in the Sequence board layout table. */
    val code: String get() = "${rank.short}${suit.name.first()}"

    val label: String get() = "${rank.short}${suit.symbol}"

    override fun toString(): String = label

    companion object {
        /** Parses the [code] form, e.g. "10H", "AS", "9D". */
        fun parse(code: String): Card {
            val suitChar = code.last()
            val rankPart = code.dropLast(1)
            val suit = Suit.entries.first { it.name.first() == suitChar }
            val rank = Rank.entries.first { it.short == rankPart }
            return Card(rank, suit)
        }
    }
}

object Decks {

    /** Standard 52-card deck. */
    fun standard52(): List<Card> =
        Suit.entries.flatMap { suit -> Rank.entries.map { rank -> Card(rank, suit) } }

    /**
     * Euchre decks. 24 cards (9 through ace) is the common game; 32 cards
     * (7 through ace) is a widespread variant that lengthens the hand.
     */
    fun euchre(size: Int): List<Card> {
        val lowest = when (size) {
            24 -> Rank.NINE
            32 -> Rank.SEVEN
            else -> throw IllegalArgumentException("Unsupported Euchre deck size: $size")
        }
        return Suit.entries.flatMap { suit ->
            Rank.entries.filter { it.order >= lowest.order }.map { rank -> Card(rank, suit) }
        }
    }

    /** Two full decks shuffled together, as Sequence uses. */
    fun double52(): List<Card> = standard52() + standard52()
}

/**
 * Deterministic shuffle. Every shuffle in the app runs through a seeded
 * [Random] so a networked hand can be reproduced from its seed and so tests
 * are repeatable — the host generates the seed and ships it with the state.
 */
fun <T> List<T>.shuffledWith(random: Random): List<T> = toMutableList().apply {
    for (i in indices.reversed()) {
        val j = random.nextInt(i + 1)
        val tmp = this[i]
        this[i] = this[j]
        this[j] = tmp
    }
}
