package org.prolibertate.games.game.crazyeights

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit

@Serializable
data class CrazyEightsOptions(
    val playerCount: Int = 4,
    /** Zero means the usual count: seven for two players, five otherwise. */
    val startingHand: Int = 0,
    /** Keep drawing until something is playable, rather than drawing one and passing. */
    val drawUntilPlayable: Boolean = true,
    val roundsToPlay: Int = 3,
) {
    init {
        require(playerCount in 2..6) { "Crazy 8s seats two to six" }
        require(roundsToPlay >= 1) { "roundsToPlay must be positive" }
    }

    fun handSize(): Int = if (startingHand > 0) startingHand else if (playerCount == 2) 7 else 5
}

/** Eights are wild and expensive; pictures cost ten; everything else is face value. */
fun crazyEightsPenalty(card: Card): Int = when (card.rank) {
    Rank.EIGHT -> 50
    Rank.KING, Rank.QUEEN, Rank.JACK, Rank.TEN -> 10
    Rank.ACE -> 1
    else -> card.rank.order
}

fun isWild(card: Card): Boolean = card.rank == Rank.EIGHT

@Serializable
enum class CrazyEightsPhase { PLAYING, ROUND_OVER, GAME_OVER }

@Serializable
data class CrazyEightsState(
    val options: CrazyEightsOptions,
    val seed: Long,
    val roundNumber: Int,
    val hands: List<List<Card>>,
    val handCounts: List<Int>,
    val stock: List<Card>,
    val discard: List<Card>,
    /**
     * The suit that must be followed. Usually the top card's suit, but an eight
     * is wild and its player names this instead — so it cannot be read off the
     * discard pile.
     */
    val suitInForce: Suit,
    val turn: Int,
    /** Cards drawn so far this turn, which limits how much more may be drawn. */
    val drawnThisTurn: Int,
    /** Passes in a row. Reaching the player count means the round is blocked. */
    val consecutivePasses: Int = 0,
    val scores: List<Int>,
    val roundWinner: Int?,
    val phase: CrazyEightsPhase,
    val log: List<String>,
) {
    val topCard: Card? get() = discard.lastOrNull()
}

@Serializable
sealed interface CrazyEightsMove

/** [nominatedSuit] is set only when playing an eight. */
@Serializable
@SerialName("play")
data class PlayCard(val card: Card, val nominatedSuit: Suit? = null) : CrazyEightsMove

@Serializable
@SerialName("draw")
data object DrawCard : CrazyEightsMove

@Serializable
@SerialName("pass")
data object PassTurn : CrazyEightsMove

/** Whether [card] may be played on a pile showing [top] with [suitInForce]. */
fun canPlay(card: Card, top: Card?, suitInForce: Suit): Boolean = when {
    isWild(card) -> true
    top == null -> true
    card.suit == suitInForce -> true
    card.rank == top.rank -> true
    else -> false
}
