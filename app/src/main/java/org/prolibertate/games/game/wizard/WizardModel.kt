package org.prolibertate.games.game.wizard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit

@Serializable
data class WizardOptions(
    val playerCount: Int = 4,
    /**
     * Zero plays the full game: sixty cards divided by the table, so four
     * players get fifteen rounds. A smaller number shortens the evening.
     */
    val rounds: Int = 0,
    /**
     * The dealer may not bid the number that would make the bids add up to the
     * tricks available, so somebody always misses. A common house rule.
     */
    val screwTheDealer: Boolean = false,
) {
    init {
        require(playerCount in 3..6) { "Wizard seats three to six" }
        require(rounds >= 0) { "rounds cannot be negative" }
    }

    /** Sixty cards share out into this many rounds. */
    fun totalRounds(): Int {
        val full = 60 / playerCount
        return if (rounds in 1..full) rounds else full
    }
}

fun isWizardCard(card: Card): Boolean = card.rank == Rank.WIZARD
fun isJester(card: Card): Boolean = card.rank == Rank.JESTER

@Serializable
enum class WizardPhase { BIDDING, PLAYING, ROUND_OVER, GAME_OVER }

@Serializable
data class PlayedCard(val seat: Int, val card: Card)

@Serializable
data class WizardState(
    val options: WizardOptions,
    val seed: Long,
    /** Zero-based; round n deals n+1 cards. */
    val round: Int,
    val dealer: Int,
    val phase: WizardPhase,
    val hands: List<List<Card>>,
    val handCounts: List<Int>,
    /** Turned up after the deal to set trump. Null in the last round. */
    val trumpCard: Card?,
    val trump: Suit?,
    /** Null until a seat has bid; one entry per seat. */
    val bids: List<Int?>,
    val tricksWon: List<Int>,
    val turn: Int,
    val trick: List<PlayedCard>,
    /** Held on the table so a finished trick can be read before it is swept. */
    val completedTrick: List<PlayedCard> = emptyList(),
    val leader: Int,
    val lastTrickWinner: Int?,
    val scores: List<Int>,
    val roundScores: List<Int>,
    val log: List<String>,
) {
    val cardsThisRound: Int get() = round + 1
}

@Serializable
sealed interface WizardMove

@Serializable
@SerialName("bid")
data class MakeBid(val tricks: Int) : WizardMove

@Serializable
@SerialName("play")
data class PlayCard(val card: Card) : WizardMove

/** The dealer picks trump when a wizard is turned up. */
@Serializable
@SerialName("choose_trump")
data class ChooseTrump(val suit: Suit) : WizardMove

/**
 * The suit a trick is being played to.
 *
 * A jester leads nothing — if the first card is a jester the suit is set by the
 * next ordinary card instead. A wizard reaching the front of that queue means
 * no suit is led at all and anything may be played.
 */
fun ledSuitOf(trick: List<PlayedCard>): Suit? {
    for (played in trick) {
        if (isWizardCard(played.card)) return null
        if (isJester(played.card)) continue
        return played.card.suit
    }
    return null
}

/**
 * Ranks a card within a trick. The first wizard played takes it outright;
 * jesters never win unless nothing else was played.
 */
fun trickStrength(card: Card, order: Int, trump: Suit?, ledSuit: Suit?): Int = when {
    // Earlier wizards beat later ones, so subtract the position.
    isWizardCard(card) -> 10_000 - order
    // A jester only ever takes a trick of nothing but jesters, and then it is
    // the first one played that wins.
    isJester(card) -> 1
    trump != null && card.suit == trump -> 1_000 + card.rank.order
    ledSuit != null && card.suit == ledSuit -> 100 + card.rank.order
    // Off-suit and not trump: cannot win, but still beats a jester.
    else -> 2
}

/** Exact bids pay twenty plus ten a trick; missing costs ten a trick out. */
fun scoreRound(bid: Int, won: Int): Int =
    if (bid == won) 20 + 10 * won else -10 * kotlin.math.abs(bid - won)
