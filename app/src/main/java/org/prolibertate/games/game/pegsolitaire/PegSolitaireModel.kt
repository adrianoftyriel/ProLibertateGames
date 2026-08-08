package org.prolibertate.games.game.pegsolitaire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A hole in the board, by row and column.
 *
 * Both geometries the boards come in are indexed this way, which is what lets
 * one jump rule serve them all: a triangular row is simply shorter than the one
 * below it, and a cross is a square with pieces bitten out of the corners.
 */
@Serializable
data class Hole(val row: Int, val col: Int)

/**
 * The boards this plays on.
 *
 * Two shapes of grid, and the difference between them is only how many
 * directions a peg can jump in. On a triangle a hole has six neighbours, because
 * the rows are staggered; on a square board it has four, because they are not.
 * Everything else — the jump, the goal, the dead end — is the same game.
 */
@Serializable
enum class PegBoard(val label: String, val holeCount: Int) {
    /** The tee game found on a restaurant table: five rows, fifteen holes. */
    TRIANGLE_15("Triangle of 15", 15),
    TRIANGLE_21("Triangle of 21", 21),

    /** The classic cross: a seven by seven square with two-by-two corners cut. */
    ENGLISH_CROSS("English cross", 33),

    /** The continental board, with a shallower cut that leaves four more holes. */
    FRENCH_CROSS("French cross", 37),
    DIAMOND_41("Diamond of 41", 41),
    ;

    val isTriangular: Boolean get() = this == TRIANGLE_15 || this == TRIANGLE_21

    /** Every hole on this board. */
    fun holes(): Set<Hole> = when (this) {
        TRIANGLE_15 -> triangle(5)
        TRIANGLE_21 -> triangle(6)
        // 49 less the four two-by-two corners.
        ENGLISH_CROSS -> square(7) { r, c -> r in 2..4 || c in 2..4 }
        // 49 less a three-hole triangle at each corner.
        FRENCH_CROSS -> square(7) { r, c ->
            val fromEdgeRow = minOf(r, 6 - r)
            val fromEdgeCol = minOf(c, 6 - c)
            fromEdgeRow + fromEdgeCol >= 2
        }

        DIAMOND_41 -> square(9) { r, c -> kotlin.math.abs(r - 4) + kotlin.math.abs(c - 4) <= 4 }
    }

    /**
     * The directions a jump may run in, as row and column steps.
     *
     * Diagonals are not among the four on a square board: a peg moves along a
     * rank or a file and nowhere else, which is what makes the cross boards hard.
     */
    fun directions(): List<Pair<Int, Int>> = if (isTriangular) {
        listOf(0 to -1, 0 to 1, -1 to -1, -1 to 0, 1 to 0, 1 to 1)
    } else {
        listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
    }

    /** Where the one empty hole sits when nothing else is chosen. */
    fun defaultStart(): Hole = when {
        // The apex, which is how the tee game is set out on the table.
        isTriangular -> Hole(0, 0)
        this == DIAMOND_41 -> Hole(4, 4)
        else -> Hole(3, 3)
    }

    private fun triangle(rows: Int): Set<Hole> =
        (0 until rows).flatMap { r -> (0..r).map { c -> Hole(r, c) } }.toSet()

    private fun square(size: Int, keep: (Int, Int) -> Boolean): Set<Hole> =
        (0 until size).flatMap { r ->
            (0 until size).mapNotNull { c -> Hole(r, c).takeIf { keep(r, c) } }
        }.toSet()
}

/**
 * What counts as having won.
 *
 * Leaving one peg anywhere is the game as it is usually played. Leaving it in
 * the hole the board started empty is the harder problem, and on the English
 * cross it is the one the puzzle is famous for.
 */
@Serializable
enum class PegGoal(val label: String) {
    ONE_PEG("Leave one peg"),
    ONE_PEG_IN_START("Leave one peg, in the starting hole"),
}

@Serializable
data class PegSolitaireOptions(
    val board: PegBoard = PegBoard.TRIANGLE_15,
    val goal: PegGoal = PegGoal.ONE_PEG,
    /**
     * The hole left empty at the start. Null takes the board's own default,
     * which keeps a saved table valid if the default ever moves.
     */
    val startEmpty: Hole? = null,
) {
    /** The empty hole this table actually opens with. */
    fun start(): Hole = startEmpty ?: board.defaultStart()

    init {
        require(board.holes().contains(start())) {
            "${start()} is not a hole on the ${board.label}"
        }
    }
}

@Serializable
data class PegSolitaireState(
    val options: PegSolitaireOptions,
    /** Which holes still hold a peg. Everything else on the board is empty. */
    val pegs: Set<Hole>,
    val jumps: Int,
    /** Held so the screen can show what just happened. */
    val lastJump: PegJump? = null,
    val log: List<String>,
) {
    val board: PegBoard get() = options.board

    val remaining: Int get() = pegs.size

    /** True once the board matches what the chosen goal asked for. */
    val solved: Boolean
        get() = remaining == 1 && when (options.goal) {
            PegGoal.ONE_PEG -> true
            PegGoal.ONE_PEG_IN_START -> pegs.first() == options.start()
        }
}

@Serializable
sealed interface PegSolitaireMove

/**
 * A peg jumps a neighbour and lands two holes away, and the peg it jumped is
 * taken off. [over] is not carried because a jump is always exactly two steps in
 * one direction, so the hole between the ends is the only one it can be —
 * storing it would be a second copy of the same fact, free to disagree.
 */
@Serializable
@SerialName("jump")
data class PegJump(val from: Hole, val to: Hole) : PegSolitaireMove {
    val over: Hole get() = Hole((from.row + to.row) / 2, (from.col + to.col) / 2)
}
