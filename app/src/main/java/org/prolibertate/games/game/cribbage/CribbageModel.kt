package org.prolibertate.games.game.cribbage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank

@Serializable
data class CribbageOptions(
    val playerCount: Int = 2,
    val pointsToWin: Int = 121,
    /** Say so at the finish when the loser never got off the second street. */
    val countSkunks: Boolean = true,
) {
    init {
        require(playerCount in 2..4) { "Cribbage seats two to four" }
        require(pointsToWin == 61 || pointsToWin == 121) {
            "Cribbage is played once round the board to 61 or twice to 121"
        }
    }

    /** Six cards each head to head, five otherwise. */
    val dealSize: Int get() = if (playerCount == 2) 6 else 5

    /** Two players lay away two cards each; three or four lay away one. */
    val layAwaySize: Int get() = if (playerCount == 2) 2 else 1

    /**
     * Three hands laying away one card apiece leave the crib a card short, so
     * one is dealt straight into it off the top of the pack.
     */
    val cribFromDeck: Int get() = if (playerCount == 3) 1 else 0

    /** Four play in partnerships across the table; two or three play singly. */
    val teamCount: Int get() = if (playerCount == 4) 2 else playerCount

    fun teamOf(seat: Int): Int = if (playerCount == 4) seat % 2 else seat
}

/** Everyone holds four cards once the crib is laid away, whatever the table. */
const val CRIBBAGE_HAND_SIZE = 4

/** The count never passes this, and reaching it exactly is worth two. */
const val CRIBBAGE_LIMIT = 31

/**
 * What a card is worth to the count and to a fifteen: aces are one, and every
 * court card is ten.
 */
fun pipValue(card: Card): Int = when (card.rank) {
    Rank.ACE -> 1
    Rank.JACK, Rank.QUEEN, Rank.KING -> 10
    else -> card.rank.order
}

/**
 * Where a card sits in a run. The ace is low here — A-2-3 is a run and Q-K-A is
 * not — which is why this cannot be [Rank.order], where the ace is high.
 */
fun runOrder(card: Card): Int = if (card.rank == Rank.ACE) 1 else card.rank.order

/** One card laid on the table during the play, and by whom. */
@Serializable
data class PeggedCard(val seat: Int, val card: Card)

/** One thing a hand scored for, in the words a player would use for it. */
@Serializable
data class ScoreLine(val label: String, val points: Int)

/**
 * A hand or a crib as it was counted at the show.
 *
 * [counted] is false for a hand the game never got to: somebody pegged out
 * ahead of it in the counting order, and the rest of the table shows what it
 * would have been worth rather than pretending it was scored.
 */
@Serializable
data class ShowScore(
    val seat: Int,
    val isCrib: Boolean,
    val cards: List<Card>,
    val lines: List<ScoreLine>,
    val counted: Boolean = true,
) {
    val total: Int get() = lines.sumOf { it.points }
}

@Serializable
enum class CribbagePhase {
    /** Everyone is choosing what to give the crib. */
    DISCARD,

    /** Pegging: cards on the table one at a time, count to thirty-one. */
    PLAY,

    /** The hands are counted and on screen, waiting to be read. */
    SHOW,

    GAME_OVER,
}

@Serializable
data class CribbageState(
    val options: CribbageOptions,
    val seed: Long,
    val handNumber: Int,
    val dealer: Int,
    /**
     * What each seat was dealt, less what it laid away. Cards stay here once
     * they have been pegged — [remaining] is what is still in the hand, and the
     * whole four are wanted again at the show.
     */
    val hands: List<List<Card>>,
    /** Cards still held per seat, which survives redaction where [hands] does not. */
    val handCounts: List<Int>,
    val crib: List<Card>,
    val cribCount: Int,
    /** What is left of the pack. Never sent to anyone. */
    val deck: List<Card>,
    val starter: Card?,
    /** Everything pegged this hand, in order. */
    val played: List<PeggedCard>,
    /** The cards of the series being counted now, which is cleared at thirty-one or a go. */
    val series: List<PeggedCard>,
    val saidGo: Set<Int>,
    val turn: Int,
    val lastToPlay: Int?,
    /** By team. Two or three play singly, so there a team is a seat. */
    val scores: List<Int>,
    /** Where the back peg stands: each side's score before its last award. */
    val previousScores: List<Int>,
    val show: List<ShowScore>,
    val phase: CribbagePhase,
    val winner: Int?,
    val log: List<String>,
) {
    /** The running count of the series in play. Never above [CRIBBAGE_LIMIT]. */
    val count: Int get() = series.sumOf { pipValue(it.card) }

    /** What [seat] has not pegged yet. Empty for a seat whose hand is redacted. */
    fun remaining(seat: Int): List<Card> {
        val gone = played.filter { it.seat == seat }.map { it.card }.toSet()
        return hands[seat].filter { it !in gone }
    }
}

@Serializable
sealed interface CribbageMove

/**
 * Cards given to the crib. Two of them head to head, one otherwise.
 *
 * The cards are held in a fixed order rather than the order they were tapped
 * in, because the host compares a move against the list it generated itself —
 * a client that picked the same two cards the other way round is making the
 * same move and must not be refused for it.
 */
@Serializable
@SerialName("layaway")
data class LayAway(val cards: List<Card>) : CribbageMove {
    companion object {
        fun of(cards: Collection<Card>): LayAway = LayAway(cards.sortedWith(cribbageCardOrder))
    }
}

@Serializable
@SerialName("peg")
data class PegCard(val card: Card) : CribbageMove

/** An arbitrary but stable order, used only to make a lay-away comparable. */
val cribbageCardOrder: Comparator<Card> =
    compareBy({ it.suit.ordinal }, { it.rank.ordinal })
