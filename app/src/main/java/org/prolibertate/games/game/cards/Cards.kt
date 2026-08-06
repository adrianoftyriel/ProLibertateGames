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

/**
 * [standard] marks the fifty-two cards every ordinary deck holds. The two
 * extras belong to Wizard alone, so anything building a normal deck must filter
 * on this rather than walking the whole enum — otherwise every other game
 * quietly gains eight cards it has no rules for.
 */
@Serializable
enum class Rank(val short: String, val order: Int, val standard: Boolean = true) {
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

    /** Wizard only: beats everything, including trump. */
    WIZARD("Wz", 100, standard = false),

    /** Wizard only: loses to everything, and leads no suit. */
    JESTER("Je", 0, standard = false),
    ;

    companion object {
        /** The thirteen ranks of an ordinary deck. */
        val standard: List<Rank> get() = entries.filter { it.standard }
    }
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
        Suit.entries.flatMap { suit -> Rank.standard.map { rank -> Card(rank, suit) } }

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
            Rank.standard.filter { it.order >= lowest.order }.map { rank -> Card(rank, suit) }
        }
    }

    /**
     * The 60-card Wizard deck: an ordinary pack plus four wizards and four
     * jesters, one of each per suit so they fit the same card model.
     */
    fun wizard(): List<Card> = standard52() +
        Suit.entries.map { Card(Rank.WIZARD, it) } +
        Suit.entries.map { Card(Rank.JESTER, it) }

    /** Two full decks shuffled together, as Sequence uses. */
    fun double52(): List<Card> = standard52() + standard52()

    /**
     * The 32-card Kaiser deck: eight to ace in every suit, plus the seven of
     * clubs and the seven of diamonds — and, in place of the other two sevens,
     * the five of hearts and the three of spades, which are what the whole game
     * is played for.
     */
    fun kaiser(): List<Card> {
        val high = Suit.entries.flatMap { suit ->
            Rank.standard.filter { it.order >= Rank.EIGHT.order }.map { Card(it, suit) }
        }
        return high + listOf(
            Card(Rank.SEVEN, Suit.CLUBS),
            Card(Rank.SEVEN, Suit.DIAMONDS),
            Card(Rank.FIVE, Suit.HEARTS),
            Card(Rank.THREE, Suit.SPADES),
        )
    }
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
