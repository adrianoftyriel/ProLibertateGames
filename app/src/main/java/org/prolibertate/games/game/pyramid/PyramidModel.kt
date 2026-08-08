package org.prolibertate.games.game.pyramid

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.solitaire.patienceOrder

/** Seven rows of one to seven cards. */
const val ROWS: Int = 7
const val PYRAMID_SIZE: Int = ROWS * (ROWS + 1) / 2

/** A pair has to make this, and a king makes it alone. */
const val TARGET: Int = 13

@Serializable
enum class PyramidZone { PYRAMID, WASTE }

@Serializable
data class PyramidSpot(val zone: PyramidZone, val index: Int = 0)

@Serializable
data class PyramidOptions(
    /** How many times the waste may be turned back over. */
    val redeals: Int = 2,
) {
    init {
        require(redeals >= 0) { "redeals cannot be negative" }
    }
}

@Serializable
data class PyramidState(
    val options: PyramidOptions,
    val seed: Long,
    /** Twenty-eight places; null where the card has been taken. */
    val pyramid: List<Card?>,
    val stock: List<Card>,
    val waste: List<Card>,
    val redealsUsed: Int,
    val moves: Int,
    val log: List<String>,
) {
    val isWon: Boolean get() = pyramid.all { it == null }

    val canRedeal: Boolean
        get() = stock.isEmpty() && waste.isNotEmpty() && redealsUsed < options.redeals
}

@Serializable
sealed interface PyramidMove

@Serializable
@SerialName("draw")
data object DrawCard : PyramidMove

@Serializable
@SerialName("redeal")
data object RecycleWaste : PyramidMove

/** A king is worth thirteen on its own, so it needs no partner. */
@Serializable
@SerialName("king")
data class TakeKing(val spot: PyramidSpot) : PyramidMove

@Serializable
@SerialName("pair")
data class TakePair(val first: PyramidSpot, val second: PyramidSpot) : PyramidMove

/** Ace one through king thirteen, which is what the pairing is counted in. */
fun valueOf(card: Card): Int = card.rank.patienceOrder

/** Where a row starts in the flattened pyramid. */
fun rowStart(row: Int): Int = row * (row + 1) / 2

/** Which row a place is in. */
fun rowOf(index: Int): Int {
    var row = 0
    while (rowStart(row + 1) <= index) row++
    return row
}

/**
 * The two places resting on [index], if any.
 *
 * A card is only in play once both of them are gone, which is the whole shape of
 * the game: the pyramid has to be taken apart from the bottom.
 */
fun coveredBy(index: Int): List<Int> {
    val row = rowOf(index)
    if (row == ROWS - 1) return emptyList()
    val offset = index - rowStart(row)
    val below = rowStart(row + 1) + offset
    return listOf(below, below + 1)
}

/** Whether the card at [index] has nothing left sitting on it. */
fun isExposed(pyramid: List<Card?>, index: Int): Boolean =
    pyramid[index] != null && coveredBy(index).all { pyramid[it] == null }
