package org.prolibertate.games.game.president

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank

/**
 * Rule toggles. President is a house-rules game more than most, so the
 * variations that change play are surfaced rather than baked in.
 */
@Serializable
data class PresidentOptions(
    val playerCount: Int = 4,
    /** A set of twos beats anything and clears the pile. */
    val twosClear: Boolean = true,
    /** Four of a kind beats anything and clears the pile. */
    val fourOfAKindBomb: Boolean = false,
    /** After the first round, Scum hands their best cards to the President. */
    val cardExchange: Boolean = true,
    val roundsToPlay: Int = 5,
) {
    init {
        require(playerCount in 3..7) { "President seats three to seven" }
        require(roundsToPlay >= 1) { "roundsToPlay must be positive" }
    }
}

/**
 * President ranks threes low and twos high, which is not the natural order of
 * [Rank]. Everything that compares cards goes through here.
 */
fun presidentRank(card: Card): Int = when (card.rank) {
    Rank.TWO -> 15
    Rank.ACE -> 14
    else -> card.rank.order
}

fun isTwo(card: Card): Boolean = card.rank == Rank.TWO

@Serializable
enum class PresidentPhase { PLAYING, ROUND_OVER, GAME_OVER }

@Serializable
data class PresidentState(
    val options: PresidentOptions,
    val seed: Long,
    val roundNumber: Int,
    val hands: List<List<Card>>,
    val handCounts: List<Int>,
    /** Cards played to the current pile, most recent set last. */
    val pile: List<Card>,
    /** How many cards the current set is; 0 when the pile is clear. */
    val setSize: Int,
    /** Rank of the set on top of the pile. */
    val setRank: Int,
    val turn: Int,
    /** Seats that have passed since the pile was last cleared. */
    val passed: List<Boolean>,
    /** Seats in the order they went out — first is President. */
    val finishedOrder: List<Int>,
    /** Finishing position from the previous round, or -1 on the first. */
    val previousFinish: List<Int>,
    val scores: List<Int>,
    val phase: PresidentPhase,
    val log: List<String>,
) {
    fun isOut(seat: Int): Boolean = seat in finishedOrder

    /** Seats still holding cards and not yet passed out of this pile. */
    fun stillInPile(): List<Int> =
        (0 until options.playerCount).filter { !isOut(it) && !passed[it] }
}

@Serializable
sealed interface PresidentMove

/** A set of equal-ranked cards. */
@Serializable
@SerialName("play")
data class PlayCards(val cards: List<Card>) : PresidentMove

@Serializable
@SerialName("pass")
data object PassTurn : PresidentMove

/** Title for a finishing position, for display. */
fun titleFor(position: Int, playerCount: Int): String = when {
    position == 0 -> "President"
    position == 1 && playerCount > 3 -> "Vice President"
    position == playerCount - 1 -> "Scum"
    position == playerCount - 2 && playerCount > 3 -> "Vice Scum"
    else -> "Citizen"
}
