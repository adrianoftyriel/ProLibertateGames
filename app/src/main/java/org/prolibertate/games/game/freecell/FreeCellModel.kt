package org.prolibertate.games.game.freecell

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.prolibertate.games.game.cards.Card

const val COLUMNS: Int = 8
const val FOUNDATIONS: Int = 4

@Serializable
enum class CellKind { CELL, TABLEAU, FOUNDATION }

@Serializable
data class Place(val kind: CellKind, val index: Int) {
    companion object {
        fun cell(index: Int) = Place(CellKind.CELL, index)
        fun column(index: Int) = Place(CellKind.TABLEAU, index)
        fun foundation(index: Int) = Place(CellKind.FOUNDATION, index)
    }
}

@Serializable
data class FreeCellOptions(
    /**
     * Four cells is the game as everyone knows it. Fewer is the same game made
     * hard, since the cells are the only place a card can wait.
     */
    val freeCells: Int = 4,
    /**
     * Move a run in one gesture rather than a card at a time. The rules do not
     * actually allow it — a run is moved by shuffling cards through the cells —
     * but every computer version does it for you, because doing it by hand is
     * bookkeeping rather than a decision.
     */
    val allowSupermoves: Boolean = true,
) {
    init {
        require(freeCells in 1..6) { "One to six cells" }
    }
}

@Serializable
data class FreeCellState(
    val options: FreeCellOptions,
    val seed: Long,
    /** Null where a cell is empty. */
    val cells: List<Card?>,
    val foundations: List<List<Card>>,
    /** Every card is face up in FreeCell; there is nothing buried to turn. */
    val tableau: List<List<Card>>,
    val moves: Int,
    val log: List<String>,
) {
    val freeCellCount: Int get() = cells.count { it == null }
    val emptyColumns: Int get() = tableau.count { it.isEmpty() }
    val isWon: Boolean get() = foundations.all { it.size == 13 }
}

@Serializable
sealed interface FreeCellMove

@Serializable
@SerialName("move")
data class MoveTo(val from: Place, val to: Place, val count: Int = 1) : FreeCellMove

/**
 * How many cards can travel together.
 *
 * A run is really moved one card at a time through the free cells and the empty
 * columns, so what can be shifted is what those could have held: one per free
 * cell plus the card itself, doubled for every empty column that could be used
 * as a staging pile. Moving *into* an empty column cannot also use it, which is
 * the fiddly part and the reason [intoEmptyColumn] exists.
 */
fun maxRunLength(freeCells: Int, emptyColumns: Int, intoEmptyColumn: Boolean): Int {
    val usable = if (intoEmptyColumn) emptyColumns - 1 else emptyColumns
    if (usable < 0) return 1
    var limit = freeCells + 1
    repeat(usable) { limit *= 2 }
    return limit
}
