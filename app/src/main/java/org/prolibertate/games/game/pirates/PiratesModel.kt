package org.prolibertate.games.game.pirates

import kotlinx.serialization.Serializable

/**
 * Dalmatian Pirates and the Volga Bulgars — Sid Sackson's name for the hunt
 * game usually called **Asalto**, and before that a cousin of Fox and Geese.
 *
 * Two pirates hold a stronghold at the head of a cross-shaped board. Twenty-four
 * Bulgars come at it from the other three arms, and cannot take anything: their
 * whole method is weight of numbers. The pirates cannot be driven off by force
 * either — they are taken only by being penned in — so each side is trying to
 * do something the other cannot do back.
 *
 * See RULES-pirates.md, which is honest about which parts of this are attested
 * and which had to be chosen: the book the theme comes from could not be
 * obtained, so the mechanics here are Asalto's.
 *
 * The board is a seven-by-seven grid with the four corners cut away, leaving
 * thirty-three points. Points are numbered in reading order.
 */
const val GRID = 7

/** True where the cross-shaped board has a point at all. */
fun isOnBoard(row: Int, column: Int): Boolean =
    row in 0 until GRID && column in 0 until GRID && (row in 2..4 || column in 2..4)

/** The thirty-three points, in reading order. */
val CELLS: List<Pair<Int, Int>> = (0 until GRID).flatMap { row ->
    (0 until GRID).mapNotNull { column -> (row to column).takeIf { isOnBoard(row, column) } }
}

val POINTS = CELLS.size

fun rowOf(point: Int): Int = CELLS[point].first

fun columnOf(point: Int): Int = CELLS[point].second

fun pointAt(row: Int, column: Int): Int? =
    if (isOnBoard(row, column)) CELLS.indexOf(row to column).takeIf { it >= 0 } else null

/**
 * The stronghold: the nine points of the top arm.
 *
 * Twenty-four points are left outside it, which is exactly the number of
 * Bulgars — the board and the pieces are cut to each other, and the game opens
 * with every point occupied but the seven the pirates are not standing on.
 */
val STRONGHOLD: List<Int> = (0 until POINTS).filter { rowOf(it) <= 2 && columnOf(it) in 2..4 }

fun isStronghold(point: Int): Boolean = point in STRONGHOLD

/**
 * The lines drawn on the board.
 *
 * Orthogonal neighbours are always joined. Diagonals are drawn only where the
 * row and column add to an even number, which is the pattern printed on every
 * board of this family — it is why the centre of the board has eight lines out
 * of it and the point beside it has four.
 */
val ADJACENCY: List<List<Int>> = (0 until POINTS).map { point ->
    val row = rowOf(point)
    val column = columnOf(point)
    val steps = if ((row + column) % 2 == 0) {
        listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
    } else {
        listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
    }
    steps.mapNotNull { (rowStep, columnStep) -> pointAt(row + rowStep, column + columnStep) }
}

/** Seat 0 is the crowd, seat 1 the pair. */
const val BULGAR_SEAT = 0
const val PIRATE_SEAT = 1

fun other(seat: Int): Int = 1 - seat

fun seatName(seat: Int): String = if (seat == BULGAR_SEAT) "the Bulgars" else "the pirates"

/** The point's name: files a–g left to right, ranks 7–1 top to bottom. */
fun pointName(point: Int): String = "${'a' + columnOf(point)}${GRID - rowOf(point)}"

fun pointFromName(name: String): Int? =
    pointAt(GRID - (name[1] - '0'), name[0] - 'a')

@Serializable
enum class PiratesPhase { PLAYING, GAME_OVER }

@Serializable
enum class PiratesOutcome(val label: String) {
    BULGARS_STORM("The Bulgars win — the stronghold is full of them"),
    BULGARS_PEN_IN("The Bulgars win — the pirates have nowhere left to go"),
    PIRATES_CUT_DOWN("The pirates win — too few Bulgars are left to fill the stronghold"),
    DRAW_STALEMATE("A draw — neither side has got anywhere in a long time"),
}

/** How hard the computer thinks about it. */
@Serializable
enum class PiratesLevel(val label: String, val depth: Int) {
    CASUAL("Casual", depth = 2),
    CLUB("Club", depth = 4),
    STRONG("Strong", depth = 6),
}

@Serializable
data class PiratesOptions(
    val level: PiratesLevel = PiratesLevel.CLUB,
    /**
     * A pirate who can take must take. The traditional way of enforcing this is
     * *huffing* — the other player removes a pirate that failed to capture —
     * which is the same rule with an extra step and a way to forget to use it.
     */
    val captureIsCompulsory: Boolean = true,
    /**
     * Bulgars may not retreat: they move towards the stronghold or across, and
     * never back down the board. Turning this off makes them far harder to
     * pin, and much less like the game.
     */
    val bulgarsMayNotRetreat: Boolean = true,
    /** Plies with nothing taken and no ground gained before it is called a draw. */
    val plyLimitWithoutProgress: Int = 60,
)

/**
 * A move: where a piece started and where it landed, one entry per hop.
 *
 * A Bulgar's move is always a single step. A pirate's may be a chain of jumps,
 * which is one turn and one move object — a capture cannot be abandoned
 * halfway.
 */
@Serializable
data class PiratesMove(val from: Int, val steps: List<Int>) {
    val to: Int get() = steps.last()

    override fun toString(): String {
        val separator = if (steps.size > 1 || isJumpFrom(from, steps.first())) "x" else "-"
        return (listOf(from) + steps).joinToString(separator) { pointName(it) }
    }
}

/** Whether two points are a jump apart: two steps along one line. */
fun isJumpFrom(from: Int, to: Int): Boolean = jumpedBetween(from, to) != null

/** The point hopped over, or null when the two are not two steps apart on a line. */
fun jumpedBetween(from: Int, to: Int): Int? {
    val rowStep = rowOf(to) - rowOf(from)
    val columnStep = columnOf(to) - columnOf(from)
    if (kotlin.math.abs(rowStep) > 2 || kotlin.math.abs(columnStep) > 2) return null
    if (kotlin.math.abs(rowStep) != 2 && kotlin.math.abs(columnStep) != 2) return null
    // A jump must be along a line, so the two halves have to be steps of one.
    if (rowStep % 2 != 0 || columnStep % 2 != 0) return null
    val middle = pointAt(rowOf(from) + rowStep / 2, columnOf(from) + columnStep / 2) ?: return null
    return middle.takeIf { middle in ADJACENCY[from] && to in ADJACENCY[middle] }
}

@Serializable
data class PiratesState(
    val options: PiratesOptions,
    /** Thirty-three points; null is empty, otherwise the seat standing there. */
    val board: List<Int?>,
    val turn: Int,
    val phase: PiratesPhase,
    val outcome: PiratesOutcome?,
    val lastMove: PiratesMove?,
    /** Plies since a pirate took something or a Bulgar entered the stronghold. */
    val pliesSinceProgress: Int,
    val moveLog: List<String>,
) {
    fun count(seat: Int): Int = board.count { it == seat }

    /** How many of the stronghold's nine points the Bulgars are standing on. */
    fun stronghold(): Int = STRONGHOLD.count { board[it] == BULGAR_SEAT }

    fun positionKey(): String = buildString {
        for (point in 0 until POINTS) {
            append(
                when (board[point]) {
                    BULGAR_SEAT -> 'b'
                    PIRATE_SEAT -> 'P'
                    else -> '.'
                }
            )
        }
        append(' ').append(turn)
    }
}

/**
 * The opening array: Bulgars on every point outside the stronghold, and the two
 * pirates on the stronghold's back corners — the deepest points they have, and
 * the ones a Bulgar has to come furthest to reach.
 */
fun startingBoard(): List<Int?> {
    val board = arrayOfNulls<Int>(POINTS)
    (0 until POINTS).forEach { point ->
        if (!isStronghold(point)) board[point] = BULGAR_SEAT
    }
    val corners = STRONGHOLD.filter { rowOf(it) == 0 && columnOf(it) != 3 }
    corners.forEach { board[it] = PIRATE_SEAT }
    return board.toList()
}
