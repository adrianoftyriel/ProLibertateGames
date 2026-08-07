package org.prolibertate.games.game.checkers

import kotlinx.serialization.Serializable

/**
 * The board is the thirty-two dark squares, numbered 1–32 the way checkers has
 * always been written down: square 1 at Black's left-hand end of the top row,
 * running left to right and down the board to 32.
 *
 * Only dark squares are ever played on, so numbering them alone means a move is
 * two small numbers rather than two coordinates, the notation the game is
 * recorded in falls out for free, and there is no such thing as an illegal
 * light square to have to rule out.
 *
 * Internally the squares are 0-based; [squareName] adds the one.
 */
const val SQUARES = 32

/** The row a square sits on, 0 at the top of the board. */
fun rowOf(square: Int): Int = square / 4

/**
 * The column, 0 at the left. Dark squares alternate which half of a pair of
 * columns they fall in, which is what the odd-row offset is doing.
 */
fun columnOf(square: Int): Int {
    val row = rowOf(square)
    val within = square % 4
    return within * 2 + if (row % 2 == 0) 1 else 0
}

/** The dark square at a row and column, or null if that square is light. */
fun squareAt(row: Int, column: Int): Int? {
    if (row !in 0..7 || column !in 0..7) return null
    val offset = if (row % 2 == 0) 1 else 0
    if ((column - offset) % 2 != 0) return null
    return row * 4 + (column - offset) / 2
}

fun squareName(square: Int): String = "${square + 1}"

@Serializable
enum class CheckersPhase { PLAYING, GAME_OVER }

@Serializable
enum class CheckersOutcome(val label: String) {
    BLACK_WINS("Black wins"),
    WHITE_WINS("White wins"),
    DRAW_NO_PROGRESS("A draw — nothing has been taken or crowned for a long time"),
    DRAW_REPETITION("A draw by threefold repetition"),
}

/** How hard the computer thinks about it. */
@Serializable
enum class CheckersLevel(val label: String, val depth: Int) {
    CASUAL("Casual", depth = 3),
    CLUB("Club", depth = 5),
    STRONG("Strong", depth = 7),
}

@Serializable
data class CheckersOptions(
    val level: CheckersLevel = CheckersLevel.CLUB,
    /**
     * A king slides any distance along a diagonal and may take from a
     * distance, as in international draughts. Off by default: the English game
     * this board and notation come from has kings that move one square like
     * anybody else, only in both directions.
     */
    val flyingKings: Boolean = false,
    /**
     * A man that reaches the far row is crowned and the turn ends there, even
     * with another jump available. This is the English rule; the international
     * one lets the jump continue and crowns only if the piece finishes there.
     */
    val crowningEndsTheTurn: Boolean = true,
    val threefoldRepetition: Boolean = true,
    /** Plies with no capture and no crowning before the game is called a draw. */
    val plyLimitWithoutProgress: Int = 80,
)

/**
 * Black moves down the board from row 0, White moves up from row 7. Black plays
 * first, as in every published checkers rulebook.
 */
const val BLACK_SEAT = 0
const val WHITE_SEAT = 1

fun other(seat: Int): Int = 1 - seat

fun seatName(seat: Int): String = if (seat == BLACK_SEAT) "Black" else "White"

/** Which way a man of this seat advances: down the rows for Black, up for White. */
fun forwardOf(seat: Int): Int = if (seat == BLACK_SEAT) 1 else -1

/** The row a man of this seat is crowned on. */
fun crownRowOf(seat: Int): Int = if (seat == BLACK_SEAT) 7 else 0

@Serializable
data class Piece(val seat: Int, val king: Boolean = false) {
    val glyph: String get() = when {
        seat == BLACK_SEAT && king -> "BK"
        seat == BLACK_SEAT -> "B"
        king -> "WK"
        else -> "W"
    }
}

/**
 * A move: where the piece started, and the squares it landed on in order.
 *
 * A quiet move has one landing square. A jump has one per hop, so a triple
 * capture is one move with three of them rather than three moves — a
 * multiple jump is one turn, and splitting it up would let a player stop
 * halfway, which the rules do not allow.
 */
@Serializable
data class CheckersMove(val from: Int, val steps: List<Int>) {
    val to: Int get() = steps.last()

    val isJump: Boolean get() = steps.isNotEmpty() && jumped(from, steps.first()) != null

    override fun toString(): String {
        val separator = if (isJump) "x" else "-"
        return (listOf(from) + steps).joinToString(separator) { squareName(it) }
    }
}

/**
 * The square hopped over going from [from] to [to], or null when the two are
 * not two squares apart on a diagonal.
 */
fun jumped(from: Int, to: Int): Int? {
    val rowStep = rowOf(to) - rowOf(from)
    val columnStep = columnOf(to) - columnOf(from)
    if (kotlin.math.abs(rowStep) != 2 || kotlin.math.abs(columnStep) != 2) return null
    return squareAt(rowOf(from) + rowStep / 2, columnOf(from) + columnStep / 2)
}

@Serializable
data class CheckersState(
    val options: CheckersOptions,
    /** Thirty-two dark squares; null is empty. */
    val board: List<Piece?>,
    val turn: Int,
    val phase: CheckersPhase,
    val outcome: CheckersOutcome?,
    val lastMove: CheckersMove?,
    /** Plies since anything was taken or crowned. */
    val pliesSinceProgress: Int,
    val repetitionKeys: List<String>,
    val moveLog: List<String>,
) {
    fun count(seat: Int): Int = board.count { it?.seat == seat }

    fun kings(seat: Int): Int = board.count { it?.seat == seat && it.king }

    fun positionKey(): String = buildString {
        for (square in 0 until SQUARES) {
            append(
                when (val piece = board[square]) {
                    null -> '.'
                    else -> when {
                        piece.seat == BLACK_SEAT && piece.king -> 'K'
                        piece.seat == BLACK_SEAT -> 'b'
                        piece.king -> 'Q'
                        else -> 'w'
                    }
                }
            )
        }
        append(' ').append(turn)
    }
}

/** The opening array: twelve a side on the three rows nearest each player. */
fun startingBoard(): List<Piece?> = (0 until SQUARES).map { square ->
    when (rowOf(square)) {
        0, 1, 2 -> Piece(BLACK_SEAT)
        5, 6, 7 -> Piece(WHITE_SEAT)
        else -> null
    }
}
