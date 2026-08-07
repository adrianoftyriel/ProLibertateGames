package org.prolibertate.games.game.morris

import kotlinx.serialization.Serializable

/**
 * The board is twenty-four points on three concentric squares, numbered
 * `ring * 8 + index`: ring 0 is the outer square, ring 2 the inner one, and the
 * index runs clockwise from the top-left corner, so odd indices are the four
 * mid-edge points and even indices the four corners.
 *
 * Numbering it that way is what makes the geometry fall out arithmetically
 * rather than being typed in as a table: neighbours around a square are the
 * index either side, the ladders between squares run through the odd points
 * alone, and a mill is three consecutive points centred on an odd one. A table
 * of twenty-four rows would say the same thing and could be wrong in one row.
 */
const val POINTS = 24

const val WHITE_SEAT = 0
const val BLACK_SEAT = 1

fun other(seat: Int): Int = 1 - seat

fun ringOf(point: Int): Int = point / 8

/** 0 is the top-left corner, running clockwise. Odd indices are mid-edge points. */
fun indexOf(point: Int): Int = point % 8

fun pointOf(ring: Int, index: Int): Int = ring * 8 + ((index % 8) + 8) % 8

/** True for the four mid-edge points of a square — the only ones with ladders. */
fun isMidEdge(point: Int): Boolean = indexOf(point) % 2 == 1

/**
 * Where a point sits on the seven-by-seven grid the board is drawn on. The
 * outer square runs along rows and columns 0, 3 and 6; the middle square 1, 3
 * and 5; the inner square 2, 3 and 4.
 */
fun rowOf(point: Int): Int {
    val ring = ringOf(point)
    return when (indexOf(point)) {
        0, 1, 2 -> ring
        3, 7 -> 3
        else -> 6 - ring
    }
}

fun columnOf(point: Int): Int {
    val ring = ringOf(point)
    return when (indexOf(point)) {
        0, 6, 7 -> ring
        1, 5 -> 3
        else -> 6 - ring
    }
}

/**
 * The point's name in the notation the game is normally recorded in: files a–g
 * left to right, ranks 7–1 top to bottom, exactly as the grid is drawn. The
 * twenty-four points are the intersections that exist; the rest of the
 * seven-by-seven grid is blank board.
 */
fun pointName(point: Int): String = "${'a' + columnOf(point)}${7 - rowOf(point)}"

fun pointFromName(name: String): Int? {
    val column = name[0] - 'a'
    val row = 7 - (name[1] - '0')
    return (0 until POINTS).firstOrNull { rowOf(it) == row && columnOf(it) == column }
}

/**
 * Which points a piece may step to. Around a square, the index either side;
 * through the mid-edge points, the same index on the neighbouring squares.
 * Corners have no ladder, which is why the middle of an edge is worth more than
 * the corner beside it.
 */
val ADJACENCY: List<List<Int>> = (0 until POINTS).map { point ->
    val ring = ringOf(point)
    val index = indexOf(point)
    buildList {
        add(pointOf(ring, index + 1))
        add(pointOf(ring, index - 1))
        if (isMidEdge(point)) {
            if (ring > 0) add(pointOf(ring - 1, index))
            if (ring < 2) add(pointOf(ring + 1, index))
        }
    }.sorted()
}

/**
 * The sixteen mills: four to a square, centred on each mid-edge point, and four
 * more running through the mid-edge points across all three squares.
 */
val MILLS: List<List<Int>> = buildList {
    for (ring in 0..2) {
        for (start in intArrayOf(0, 2, 4, 6)) {
            add(listOf(pointOf(ring, start), pointOf(ring, start + 1), pointOf(ring, start + 2)))
        }
    }
    for (index in intArrayOf(1, 3, 5, 7)) {
        add(listOf(pointOf(0, index), pointOf(1, index), pointOf(2, index)))
    }
}

/** The mills each point belongs to. Every point is in exactly two of them. */
val MILLS_THROUGH: List<List<List<Int>>> =
    (0 until POINTS).map { point -> MILLS.filter { point in it } }

/**
 * A move.
 *
 * Placing a piece and moving one are the same shape — [from] is null while
 * there are still pieces in hand — and the piece taken for closing a mill
 * travels with the move that closed it rather than being a move of its own.
 * That keeps a turn atomic: the host validates one message, the search counts
 * one ply, and there is no half-finished turn to be left holding if a device
 * drops off the network mid-mill.
 */
@Serializable
data class MorrisMove(
    val to: Int,
    val from: Int? = null,
    val remove: Int? = null,
) {
    override fun toString(): String = buildString {
        from?.let { append(pointName(it)).append('-') }
        append(pointName(to))
        remove?.let { append('x').append(pointName(it)) }
    }
}

@Serializable
enum class MorrisPhase {
    /** Pieces are still being put on the board from the hand. */
    PLACING,

    /** Every piece is on the board; they move along the lines now. */
    MOVING,
    GAME_OVER,
}

@Serializable
enum class MorrisOutcome(val label: String) {
    WHITE_WINS_REDUCED("White wins — Black is down to two pieces"),
    BLACK_WINS_REDUCED("Black wins — White is down to two pieces"),
    WHITE_WINS_BLOCKED("White wins — Black has no move left"),
    BLACK_WINS_BLOCKED("Black wins — White has no move left"),
    DRAW_REPETITION("Draw by threefold repetition"),
    DRAW_NO_MILL("Draw — no mill closed in a long time"),
}

/** How hard the computer thinks about it. */
@Serializable
enum class MorrisLevel(val label: String, val depth: Int) {
    CASUAL("Casual", depth = 2),
    CLUB("Club", depth = 4),
    STRONG("Strong", depth = 6),
}

@Serializable
data class MorrisOptions(
    val level: MorrisLevel = MorrisLevel.CLUB,
    /** Nine each is the game's name; three and six men are the smaller cousins. */
    val piecesEach: Int = 9,
    /**
     * A player down to their last three pieces may jump anywhere rather than
     * step along a line. It is the usual rule and it is what stops a losing
     * position being a slow squeeze with no counterplay, but it is often
     * dropped to make the game harder to draw.
     */
    val flyingWithThree: Boolean = true,
    /** The same position three times over is a draw. */
    val threefoldRepetition: Boolean = true,
    /**
     * Plies allowed to pass with no mill closed before the game is a draw.
     * Fifty moves each is the usual figure, and something like it is needed:
     * two players who can each hold a position have no reason ever to stop.
     */
    val plyLimitWithoutMill: Int = 100,
)

@Serializable
data class MorrisState(
    val options: MorrisOptions,
    /** Twenty-four points; null is empty, otherwise the seat holding it. */
    val board: List<Int?>,
    val turn: Int,
    /** How many pieces each seat has put on the board, in total, ever. */
    val placed: List<Int>,
    val phase: MorrisPhase,
    val outcome: MorrisOutcome?,
    val lastMove: MorrisMove?,
    /** Plies since a mill was last closed, which is what the draw rule counts. */
    val pliesSinceMill: Int,
    /** One entry per position reached, for the repetition rule. */
    val repetitionKeys: List<String>,
    /** The game record, one entry per ply. */
    val moveLog: List<String>,
) {
    fun inHand(seat: Int): Int = options.piecesEach - placed[seat]

    fun onBoard(seat: Int): Int = board.count { it == seat }

    /** Pieces still to come plus pieces still standing: what a seat has left. */
    fun remaining(seat: Int): Int = inHand(seat) + onBoard(seat)

    /** True once this seat is down to three pieces and allowed to jump. */
    fun isFlying(seat: Int): Boolean =
        options.flyingWithThree && phase != MorrisPhase.PLACING && onBoard(seat) == 3

    /** Whether the three points of [mill] are all held by [seat]. */
    fun holdsMill(mill: List<Int>, seat: Int): Boolean = mill.all { board[it] == seat }

    /** Whether the piece standing on [point] is part of a completed mill. */
    fun isInMill(point: Int): Boolean {
        val seat = board[point] ?: return false
        return MILLS_THROUGH[point].any { holdsMill(it, seat) }
    }

    fun millCount(seat: Int): Int = MILLS.count { holdsMill(it, seat) }

    /** The position, as the repetition rule sees it: the board and who is to move. */
    fun positionKey(): String = buildString {
        for (point in 0 until POINTS) {
            append(
                when (board[point]) {
                    WHITE_SEAT -> 'W'
                    BLACK_SEAT -> 'B'
                    else -> '.'
                }
            )
        }
        append(' ').append(turn)
        // Two positions that look alike but have different numbers of pieces
        // still to come are not the same position.
        append(' ').append(placed[WHITE_SEAT]).append(',').append(placed[BLACK_SEAT])
    }
}

fun seatIsWhite(seat: Int): Boolean = seat == WHITE_SEAT

fun seatName(seat: Int): String = if (seatIsWhite(seat)) "White" else "Black"
