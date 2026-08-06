package org.prolibertate.games.game.golf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit

@Serializable
data class GolfOptions(
    val playerCount: Int = 2,
    /** Cards in front of each player: 4 (2x2), 6 (2x3), 8 (2x4) or 9 (3x3). */
    val gridSize: Int = 6,
    val holes: Int = 9,
    /** How many of your own cards you turn over before play starts. */
    val startingReveals: Int = 2,
    /**
     * Lining up the final putt: lets you throw a drawn card away without
     * turning your last face-down card over, so you are not forced to close
     * the hole on a card you have not chosen.
     */
    val lineUpFinalPutt: Boolean = false,
) {
    init {
        require(playerCount in 2..6) { "Golf seats two to six" }
        require(gridSize in setOf(4, 6, 8, 9)) {
            "Golf grids are 4, 6, 8 or 9 cards"
        }
        require(holes >= 1) { "holes must be positive" }
        require(startingReveals in 0..gridSize) { "cannot reveal more than the grid holds" }
    }

    val rows: Int get() = if (gridSize == 9) 3 else 2
    val cols: Int get() = gridSize / rows
}

/**
 * Golf scoring: kings are free, twos are worth less than nothing, and the
 * picture cards hurt.
 */
fun golfValue(card: Card): Int = when (card.rank) {
    Rank.KING -> 0
    Rank.ACE -> 1
    Rank.TWO -> -2
    Rank.JACK, Rank.QUEEN -> 10
    else -> card.rank.order
}

/**
 * Scores one grid.
 *
 * A column of matching ranks cancels to nothing, which is the whole tactical
 * point of the game. On the 3x3 board rows cancel too; on the shallower boards
 * a "row" of three unrelated cards is not a line at all, so only columns count.
 */
fun scoreGrid(cards: List<Card>, options: GolfOptions): Int {
    val rows = options.rows
    val cols = options.cols
    val cancelled = BooleanArray(cards.size)

    for (col in 0 until cols) {
        val line = (0 until rows).map { it * cols + col }
        if (line.map { cards[it].rank }.distinct().size == 1) {
            line.forEach { cancelled[it] = true }
        }
    }
    if (rows == 3) {
        for (row in 0 until rows) {
            val line = (0 until cols).map { row * cols + it }
            if (line.map { cards[it].rank }.distinct().size == 1) {
                line.forEach { cancelled[it] = true }
            }
        }
    }

    return cards.indices.sumOf { if (cancelled[it]) 0 else golfValue(cards[it]) }
}

/**
 * Stands in for a face-down card in a redacted view.
 *
 * Grids always hold a real card so the host can score without null handling;
 * a view for a given seat swaps every unrevealed card for this one. It is never
 * drawn, because the UI only paints a face where `revealed` is true — and
 * substituting a fixed card rather than the real one is what stops a client
 * learning what it has not yet turned over.
 */
val HIDDEN_CARD: Card = Card(Rank.TWO, Suit.SPADES)

@Serializable
enum class GolfPhase {
    /** Each player choosing which of their own cards to turn over first. */
    SETUP,
    DRAW,
    PLACE,
    HOLE_OVER,
    GAME_OVER,
}

@Serializable
data class GolfState(
    val options: GolfOptions,
    val seed: Long,
    val hole: Int,
    val grids: List<List<Card>>,
    val revealed: List<List<Boolean>>,
    val stock: List<Card>,
    val discard: List<Card>,
    /** The card picked up this turn, waiting to be placed or thrown. */
    val drawn: Card?,
    /** Taken from the discard pile, which means it has to be used. */
    val drawnFromDiscard: Boolean,
    val turn: Int,
    /** First seat to turn its whole grid face up. */
    val closedBy: Int?,
    /** Turns left for everyone else once someone has closed. */
    val finalTurnsLeft: Int,
    val holeScores: List<Int>,
    val scores: List<Int>,
    val phase: GolfPhase,
    /**
     * Consecutive turns where nobody did anything but throw a card away.
     * Only reachable with [GolfOptions.lineUpFinalPutt] on, and only there to
     * stop a table of players all declining to close from playing forever.
     */
    val idleDiscards: Int = 0,
    val log: List<String>,
) {
    fun allRevealed(seat: Int): Boolean = revealed[seat].all { it }

    fun faceDownCount(seat: Int): Int = revealed[seat].count { !it }
}

@Serializable
sealed interface GolfMove

@Serializable
@SerialName("draw_stock")
data object DrawFromStock : GolfMove

@Serializable
@SerialName("draw_discard")
data object DrawFromDiscard : GolfMove

/** Put the drawn card into the grid, throwing out what was there. */
@Serializable
@SerialName("replace")
data class ReplaceCard(val index: Int) : GolfMove

/** Throw the drawn card away and turn one of your own cards face up instead. */
@Serializable
@SerialName("discard_flip")
data class DiscardAndFlip(val index: Int) : GolfMove

/**
 * Throw the drawn card away and turn nothing over — lining up the final putt.
 * Only offered when turning a card over would be turning over your last one.
 */
@Serializable
@SerialName("discard_only")
data object DiscardOnly : GolfMove

/** Choosing one of your own cards to see before play starts. */
@Serializable
@SerialName("reveal")
data class RevealCard(val index: Int) : GolfMove
