package org.prolibertate.games.game.hearts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit

@Serializable
data class HeartsOptions(
    /**
     * Four hands of thirteen, which is the game as it is normally played. Three
     * and five handed Hearts exist, but they need cards struck from the deck to
     * divide evenly, and a seat that holds a different number of cards from its
     * neighbours changes which discards are safe — a different game to bid and
     * play, not a seat count.
     */
    val playerCount: Int = 4,
    /** The round in which somebody crosses this ends the game. */
    val targetScore: Int = 100,
    /**
     * Taking all twenty-six points scores nothing and gives everyone else the
     * lot. Switching this off scores a moon like any other hand, which is to say
     * it becomes the worst result on the table rather than the best.
     */
    val allowShootTheMoon: Boolean = true,
) {
    init {
        require(playerCount == 4) { "Hearts is dealt four handed" }
        require(targetScore > 0) { "targetScore must be positive" }
    }
}

/**
 * Where the three passed cards go. The cycle repeats every fourth round, and
 * the hold round is what stops a seat from planning around a known direction
 * forever.
 */
@Serializable
enum class PassDirection(val label: String) {
    LEFT("left"),
    RIGHT("right"),
    ACROSS("across"),
    HOLD("nobody"),
    ;

    companion object {
        /** Rounds run left, right, across, hold, and back to left. */
        fun forRound(round: Int): PassDirection = entries[round % entries.size]
    }
}

@Serializable
enum class HeartsPhase { PASSING, PLAYING, ROUND_OVER, GAME_OVER }

@Serializable
data class PlayedCard(val seat: Int, val card: Card)

@Serializable
data class HeartsState(
    val options: HeartsOptions,
    val seed: Long,
    /** Zero-based. Only used to pick the pass direction and to number the log. */
    val round: Int,
    val phase: HeartsPhase,
    val hands: List<List<Card>>,
    /** Survives redaction so a client can draw the backs of other hands. */
    val handCounts: List<Int>,
    /** What each seat has chosen to pass, before the swap happens. */
    val passSelections: List<List<Card>>,
    val turn: Int,
    val leader: Int,
    /**
     * Which trick of the round is being played, counting from zero. Carried
     * rather than derived because the first trick is the one that bans point
     * cards, and a rule that important should not depend on reading it back out
     * of how many cards happen to have been taken.
     */
    val trickNumber: Int,
    val trick: List<PlayedCard>,
    /** Held on the table so a finished trick can be read before it is swept. */
    val completedTrick: List<PlayedCard> = emptyList(),
    /**
     * Every card each seat has taken in tricks this round. Kept whole rather
     * than as a running count because shooting the moon is decided on the
     * twenty-six penalty cards together, not on a total that cannot tell an
     * unbroken moon from thirteen hearts and a lucky queen elsewhere.
     */
    val taken: List<List<Card>>,
    val heartsBroken: Boolean,
    val scores: List<Int>,
    val roundScores: List<Int>,
    val log: List<String>,
) {
    val passDirection: PassDirection get() = PassDirection.forRound(round)

    /** Thirteen, four handed: every card dealt is a card played. */
    val tricksPerRound: Int get() = DECK_SIZE / options.playerCount
}

/** An ordinary pack, which Hearts uses whole. */
const val DECK_SIZE: Int = 52

@Serializable
sealed interface HeartsMove

/** The three cards this seat is giving away. Order does not matter. */
@Serializable
@SerialName("pass")
data class PassCards(val cards: List<Card>) : HeartsMove

@Serializable
@SerialName("play")
data class PlayCard(val card: Card) : HeartsMove

/** How many cards change hands in a pass. */
const val PASS_SIZE: Int = 3

val QUEEN_OF_SPADES: Card = Card(Rank.QUEEN, Suit.SPADES)

/** The two of clubs always leads the first trick of a round. */
val TWO_OF_CLUBS: Card = Card(Rank.TWO, Suit.CLUBS)

/** Every heart costs a point; the queen of spades costs thirteen. */
fun pointsOf(card: Card): Int = when {
    card == QUEEN_OF_SPADES -> 13
    card.suit == Suit.HEARTS -> 1
    else -> 0
}

/** The whole twenty-six, which is what a moon has to consist of. */
const val ALL_POINTS: Int = 26

fun pointsIn(cards: List<Card>): Int = cards.sumOf { pointsOf(it) }

/** Which seat receives what [from] passes, given the round's direction. */
fun passTarget(from: Int, playerCount: Int, direction: PassDirection): Int = when (direction) {
    PassDirection.LEFT -> (from + 1) % playerCount
    PassDirection.RIGHT -> (from + playerCount - 1) % playerCount
    PassDirection.ACROSS -> (from + playerCount / 2) % playerCount
    PassDirection.HOLD -> from
}

/**
 * Ranks a card within a trick. Nothing trumps in Hearts, so a card that did not
 * follow the led suit cannot win however high it is.
 */
fun trickStrength(card: Card, ledSuit: Suit): Int =
    if (card.suit == ledSuit) card.rank.order else -1

/**
 * Turns a round's takings into the points each seat adds to its score.
 *
 * A seat that took all twenty-six has shot the moon: it scores nothing and
 * every other seat takes the lot. That is the one case where a round's scores
 * do not sum to twenty-six.
 */
fun scoreRound(taken: List<List<Card>>, allowShootTheMoon: Boolean): List<Int> {
    val raw = taken.map { pointsIn(it) }
    if (!allowShootTheMoon) return raw
    val shooter = raw.indexOfFirst { it == ALL_POINTS }
    if (shooter < 0) return raw
    return raw.indices.map { if (it == shooter) 0 else ALL_POINTS }
}
