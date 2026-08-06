package org.prolibertate.games.game.euchre

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit

/**
 * Rule toggles. Euchre is played differently in almost every kitchen, so the
 * variations that actually change play are surfaced in the game setup screen
 * rather than baked in. Defaults are the common North American game.
 */
@Serializable
data class EuchreOptions(
    /** 24 cards (9–A) is standard; 32 (7–A) is a widespread longer variant. */
    val deckSize: Int = 24,
    /** Dealer must name trump rather than pass the hand out. Also called screw the dealer. */
    val stickTheDealer: Boolean = true,
    /** Allow a maker to play the hand without their partner for a bigger score. */
    val allowGoingAlone: Boolean = true,
    /** First team to this many points wins. 10 is standard; 11 and 15 are common. */
    val pointsToWin: Int = 10,
) {
    init {
        require(deckSize == 24 || deckSize == 32) { "Euchre deck must be 24 or 32 cards" }
        require(pointsToWin > 0) { "pointsToWin must be positive" }
    }
}

@Serializable
enum class EuchrePhase {
    /** Players may order up the turned card, in turn, starting left of the dealer. */
    BID_ROUND_1,

    /** The turn card is down; players may name any other suit. */
    BID_ROUND_2,

    /** The dealer has picked up the turn card and must discard one. */
    DEALER_DISCARD,

    PLAYING,

    /** Five tricks played and scored; waiting to deal the next hand. */
    HAND_OVER,

    GAME_OVER,
}

@Serializable
data class PlayedCard(val seat: Int, val card: Card)

@Serializable
data class EuchreState(
    val options: EuchreOptions,
    val seed: Long,
    val handNumber: Int,
    val dealer: Int,
    val phase: EuchrePhase,
    /** Per-seat hands. Redacted to empty for seats other than the viewer. */
    val hands: List<List<Card>>,
    /** Card counts survive redaction so the table can still be drawn. */
    val handCounts: List<Int>,
    val upCard: Card?,
    /** The turned-down card in round two; its suit may not be named. */
    val turnedDown: Card?,
    val trump: Suit?,
    val maker: Int?,
    val aloneSeat: Int?,
    val turn: Int,
    val passes: Int,
    val trick: List<PlayedCard>,
    /**
     * The trick that has just been won, kept on the table so it can be read and
     * then animated away. Cleared as soon as the next card is played. Winner is
     * [lastTrickWinner].
     */
    val completedTrick: List<PlayedCard> = emptyList(),
    val leader: Int,
    val tricksWon: List<Int>,
    /** Two entries: team 0 is seats 0 and 2, team 1 is seats 1 and 3. */
    val scores: List<Int>,
    val lastTrickWinner: Int?,
    val log: List<String>,
) {
    val sittingOut: Int? get() = aloneSeat?.let { partnerOf(it) }

    fun isActive(seat: Int): Boolean = seat != sittingOut

    val activeSeatCount: Int get() = if (aloneSeat != null) 3 else 4
}

@Serializable
sealed interface EuchreMove

@Serializable
@SerialName("pass")
data object Pass : EuchreMove

@Serializable
@SerialName("order_up")
data class OrderUp(val alone: Boolean) : EuchreMove

@Serializable
@SerialName("call_trump")
data class CallTrump(val suit: Suit, val alone: Boolean) : EuchreMove

@Serializable
@SerialName("discard")
data class Discard(val card: Card) : EuchreMove

@Serializable
@SerialName("play")
data class PlayCard(val card: Card) : EuchreMove

// ---------------------------------------------------------------------------
// Seating and card ranking
// ---------------------------------------------------------------------------

fun partnerOf(seat: Int): Int = (seat + 2) % 4

fun teamOf(seat: Int): Int = seat % 2

/** The jack of trump: the highest card in the game. */
fun isRightBower(card: Card, trump: Suit): Boolean =
    card.rank == Rank.JACK && card.suit == trump

/**
 * The jack of the same colour as trump. It counts as a trump card and outranks
 * every trump except the right bower — the rule that catches every new player.
 */
fun isLeftBower(card: Card, trump: Suit): Boolean =
    card.rank == Rank.JACK && card.suit == trump.sameColour()

/** The suit a card counts as, which is trump for the left bower. */
fun effectiveSuit(card: Card, trump: Suit?): Suit =
    if (trump != null && isLeftBower(card, trump)) trump else card.suit

fun isTrumpCard(card: Card, trump: Suit?): Boolean =
    trump != null && effectiveSuit(card, trump) == trump

/**
 * Ranks a card within a trick. Higher wins. Cards that neither follow the led
 * suit nor trump score zero and can never take the trick.
 */
fun trickStrength(card: Card, trump: Suit, ledSuit: Suit): Int = when {
    isRightBower(card, trump) -> 1000
    isLeftBower(card, trump) -> 900
    isTrumpCard(card, trump) -> 800 + card.rank.order
    effectiveSuit(card, trump) == ledSuit -> 100 + card.rank.order
    else -> 0
}

/** Ranks a card in isolation, for AI hand evaluation. */
fun trumpStrength(card: Card, trump: Suit): Int = when {
    isRightBower(card, trump) -> 1000
    isLeftBower(card, trump) -> 900
    isTrumpCard(card, trump) -> 800 + card.rank.order
    else -> card.rank.order
}
