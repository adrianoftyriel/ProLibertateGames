package org.prolibertate.games.game.sequence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit

const val BOARD_SIZE = 10
const val BOARD_CELLS = BOARD_SIZE * BOARD_SIZE
const val RUN_LENGTH = 5

/** Sentinel stored in [SequenceState.chips] for an unoccupied square. */
const val NO_TEAM = -1

/**
 * The printed board.
 *
 * IMPORTANT: this is a generated layout, not a reproduction of the commercial
 * Sequence board. It satisfies every structural property the rules depend on —
 * each of the 48 non-jack cards appears exactly twice, the four corners are
 * free squares, and no card sits next to its own twin — so play is correct, but
 * square-for-square it will not match a physical board. Replace [LAYOUT] with
 * the real arrangement to match one; nothing else needs to change.
 */
object SequenceBoard {

    private val LAYOUT: List<String> = listOf(
        "FREE  10C   6D   4C   7C   8H   9S  10C  10H FREE",
        "  3H   8S   2H   9C   AH  10S   4S   5H   QH   4H",
        "  3S   AS   KD   KC   AC   KS   3D  10D   6D   KH",
        "  6H   3D   3C   QC   7D   8H   9D   5S   4H   6C",
        "  AD   5D   8S   9S   4S   2D  10H   QD   5D   QC",
        "  7H   9D   QH   2C   4C   AS   AD   8C   6C   QS",
        "  2S   6S   8D   KH   7C   7S   4D   7S   3H   3S",
        " 10S   4D   7H   AC   2H   2C   3C   6S   2S   7D",
        "  8C  10D   KD   5H   9H   5C   QS   8D   9H   9C",
        "FREE   6H   KS   2D   AH   5S   QD   KC   5C FREE",
    )

    /** Card printed on each square, or null for the four free corners. */
    val cells: List<Card?> = LAYOUT.flatMap { row ->
        row.trim().split(Regex("\\s+")).map { token ->
            if (token == "FREE") null else Card.parse(token)
        }
    }.also { parsed ->
        require(parsed.size == BOARD_CELLS) { "Board must have $BOARD_CELLS squares" }
    }

    /** Squares showing a given card. Every non-jack card occupies exactly two. */
    val squaresByCard: Map<Card, List<Int>> = cells
        .withIndex()
        .filter { it.value != null }
        .groupBy({ it.value!! }, { it.index })

    fun isCorner(cell: Int): Boolean = cells[cell] == null

    fun rowOf(cell: Int): Int = cell / BOARD_SIZE
    fun colOf(cell: Int): Int = cell % BOARD_SIZE
    fun cellAt(row: Int, col: Int): Int = row * BOARD_SIZE + col
}

/** Jacks do not appear on the board; they are the two wild actions instead. */
fun isTwoEyedJack(card: Card): Boolean =
    card.rank == Rank.JACK && (card.suit == Suit.DIAMONDS || card.suit == Suit.CLUBS)

fun isOneEyedJack(card: Card): Boolean =
    card.rank == Rank.JACK && (card.suit == Suit.HEARTS || card.suit == Suit.SPADES)

@Serializable
data class SequenceOptions(
    /** Two teams need two sequences; three teams need one. */
    val teamCount: Int = 2,
    val playersPerTeam: Int = 1,
    val sequencesToWin: Int = 2,
    /** Allow swapping a card whose squares are both taken. */
    val deadCardExchange: Boolean = true,
) {
    init {
        require(teamCount in 2..3) { "Sequence supports two or three teams" }
        require(playersPerTeam >= 1) { "Each team needs at least one player" }
        require(sequencesToWin >= 1) { "sequencesToWin must be positive" }
    }

    val playerCount: Int get() = teamCount * playersPerTeam
}

/**
 * Cards dealt per player, which shrinks as the table grows. These are the
 * standard counts; unusual player counts fall back to the nearest band.
 */
fun handSizeFor(players: Int): Int = when {
    players <= 2 -> 7
    players <= 4 -> 6
    players <= 6 -> 5
    players <= 9 -> 4
    else -> 3
}

@Serializable
enum class SequencePhase { PLAYING, GAME_OVER }

@Serializable
data class SequenceState(
    val options: SequenceOptions,
    val seed: Long,
    /** Team index per seat. */
    val teams: List<Int>,
    /** Occupying team per square, or [NO_TEAM]. */
    val chips: List<Int>,
    /** Squares belonging to a completed sequence: safe from one-eyed jacks. */
    val locked: List<Boolean>,
    val hands: List<List<Card>>,
    val handCounts: List<Int>,
    val drawPile: List<Card>,
    val discardPile: List<Card>,
    val turn: Int,
    val sequencesByTeam: List<Int>,
    val winner: Int?,
    val phase: SequencePhase,
    /** Set once per turn when a dead card has already been exchanged. */
    val exchangedThisTurn: Boolean,
    val lastPlacedCell: Int?,
    val log: List<String>,
) {
    val playerCount: Int get() = teams.size

    fun teamOf(seat: Int): Int = teams[seat]
}

@Serializable
sealed interface SequenceMove

@Serializable
@SerialName("place")
data class PlaceChip(val card: Card, val cell: Int) : SequenceMove

@Serializable
@SerialName("remove")
data class RemoveChip(val card: Card, val cell: Int) : SequenceMove

@Serializable
@SerialName("exchange")
data class ExchangeDeadCard(val card: Card) : SequenceMove
