package org.prolibertate.games.game.kaiser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit

@Serializable
data class KaiserOptions(
    /** Lowest opening bid. Five to eight, depending on the table. */
    val minimumBid: Int = 6,
    /** A no-trump bid outranks the same number bid with a trump suit. */
    val allowNoTrump: Boolean = true,
    val pointsToWin: Int = 52,
    /**
     * Going out the bottom loses. Without a floor a table that keeps bidding
     * and going down never reaches a result at all.
     */
    val losingScore: Int = -52,
) {
    init {
        require(minimumBid in 5..8) { "Kaiser opens between five and eight" }
        require(pointsToWin > 0) { "pointsToWin must be positive" }
        require(losingScore < 0) { "losingScore must be negative" }
    }
}

/**
 * The most a hand can be worth: eight tricks, plus five for the five of hearts,
 * less three for the three of spades. Bidding above it is bidding to fail, so
 * nothing above it is offered.
 */
const val MAX_BID = 10

/** The five of hearts, worth five to whoever takes the trick it falls in. */
val FIVE_OF_HEARTS: Card = Card(Rank.FIVE, Suit.HEARTS)

/** The three of spades, which costs three. */
val THREE_OF_SPADES: Card = Card(Rank.THREE, Suit.SPADES)

/**
 * Points in a trick: one for the trick itself, plus or minus whatever the two
 * counters are worth. Eight tricks and the two counters make ten points a hand.
 */
fun trickValue(cards: List<Card>): Int {
    var value = 1
    if (cards.contains(FIVE_OF_HEARTS)) value += 5
    if (cards.contains(THREE_OF_SPADES)) value -= 3
    return value
}

fun partnerOf(seat: Int): Int = (seat + 2) % 4

fun teamOf(seat: Int): Int = seat % 2

@Serializable
enum class KaiserPhase { BIDDING, PLAYING, HAND_OVER, GAME_OVER }

@Serializable
data class PlayedCard(val seat: Int, val card: Card)

/** A bid of [points] tricks-worth, optionally in no trump. */
@Serializable
data class Bid(val points: Int, val noTrump: Boolean) {
    /** No trump beats the same number with a suit. */
    fun beats(other: Bid?): Boolean = when {
        other == null -> true
        points != other.points -> points > other.points
        else -> noTrump && !other.noTrump
    }
}

@Serializable
data class KaiserState(
    val options: KaiserOptions,
    val seed: Long,
    val handNumber: Int,
    val dealer: Int,
    val phase: KaiserPhase,
    val hands: List<List<Card>>,
    val handCounts: List<Int>,
    val turn: Int,
    val passes: Int,
    val highBid: Bid?,
    val highBidder: Int?,
    val trump: Suit?,
    val trick: List<PlayedCard>,
    /** Held on the table so a finished trick can be read before it is swept. */
    val completedTrick: List<PlayedCard> = emptyList(),
    val leader: Int,
    val lastTrickWinner: Int?,
    /** Points taken this hand, per team. */
    val handPoints: List<Int>,
    /**
     * Tricks taken this hand, per seat. Kaiser scores in points rather than
     * tricks, so nothing in the rules needs this — it is what the table draws
     * in front of each player, and a player who has taken four tricks and no
     * counters has still taken four tricks. Defaulted so a client on an older
     * build can still read a state from the wire.
     */
    val tricksWon: List<Int> = List(4) { 0 },
    val scores: List<Int>,
    val log: List<String>,
)

@Serializable
sealed interface KaiserMove

@Serializable
@SerialName("bid")
data class MakeBid(val bid: Bid) : KaiserMove

@Serializable
@SerialName("pass")
data object PassBid : KaiserMove

/** Only the winning bidder names trump, and only after the bidding. */
@Serializable
@SerialName("name_trump")
data class NameTrump(val suit: Suit?) : KaiserMove

@Serializable
@SerialName("play")
data class PlayCard(val card: Card) : KaiserMove

/**
 * Trick ranking. Trump beats everything else; otherwise only the led suit can
 * win. There are no bowers — Kaiser ranks straight down from the ace.
 */
fun trickStrength(card: Card, trump: Suit?, ledSuit: Suit): Int = when {
    trump != null && card.suit == trump -> 1000 + card.rank.order
    card.suit == ledSuit -> 100 + card.rank.order
    else -> 0
}
