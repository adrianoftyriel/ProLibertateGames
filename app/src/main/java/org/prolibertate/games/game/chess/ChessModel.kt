package org.prolibertate.games.game.chess

import kotlinx.serialization.Serializable

/**
 * Squares are numbered the way a FEN string reads them: 0 is a8, 7 is h8, and
 * 63 is h1. Keeping the board in reading order means parsing and printing a
 * position are both straight loops, and the screen only has to flip the index
 * when it draws from Black's side.
 */
fun squareOf(file: Int, rank: Int): Int = (7 - rank) * 8 + file

/** 0 is the a-file. */
fun fileOf(square: Int): Int = square % 8

/** 0 is rank one. */
fun rankOf(square: Int): Int = 7 - square / 8

fun squareName(square: Int): String =
    "${'a' + fileOf(square)}${rankOf(square) + 1}"

fun squareFromName(name: String): Int =
    squareOf(name[0] - 'a', name[1] - '1')

/** True for the light squares, which is all the screen needs to colour a board. */
fun isLightSquare(square: Int): Boolean = (fileOf(square) + rankOf(square)) % 2 == 1

@Serializable
enum class PieceKind(val letter: Char, val glyphIndex: Int, val value: Int) {
    PAWN('P', 5, 100),
    KNIGHT('N', 4, 320),
    BISHOP('B', 3, 330),
    ROOK('R', 2, 500),
    QUEEN('Q', 1, 900),

    /**
     * The king has no material value: it is never captured, and a search that
     * could trade it for anything would happily do so.
     */
    KING('K', 0, 0),
    ;

    companion object {
        fun fromLetter(letter: Char): PieceKind? =
            entries.firstOrNull { it.letter == letter.uppercaseChar() }
    }
}

@Serializable
data class Piece(val kind: PieceKind, val white: Boolean) {
    /** FEN notation: white in upper case, black in lower. */
    val letter: Char get() = if (white) kind.letter else kind.letter.lowercaseChar()

    /** The Unicode chess figurine, which is how the board is drawn. */
    val glyph: String get() = (if (white) WHITE_GLYPHS else BLACK_GLYPHS)[kind.glyphIndex].toString()
}

private const val WHITE_GLYPHS = "♔♕♖♗♘♙"
private const val BLACK_GLYPHS = "♚♛♜♝♞♟"

/** Which castles are still available, regardless of whether they are legal now. */
@Serializable
data class CastlingRights(
    val whiteKingSide: Boolean = true,
    val whiteQueenSide: Boolean = true,
    val blackKingSide: Boolean = true,
    val blackQueenSide: Boolean = true,
) {
    fun kingSide(white: Boolean): Boolean = if (white) whiteKingSide else blackKingSide
    fun queenSide(white: Boolean): Boolean = if (white) whiteQueenSide else blackQueenSide

    /** The FEN field, or "-" when nobody may castle. */
    fun toFen(): String = buildString {
        if (whiteKingSide) append('K')
        if (whiteQueenSide) append('Q')
        if (blackKingSide) append('k')
        if (blackQueenSide) append('q')
        if (isEmpty()) append('-')
    }

    companion object {
        fun fromFen(field: String): CastlingRights = CastlingRights(
            whiteKingSide = field.contains('K'),
            whiteQueenSide = field.contains('Q'),
            blackKingSide = field.contains('k'),
            blackQueenSide = field.contains('q'),
        )
    }
}

/**
 * A move is only ever from one square to another, plus what a pawn turns into.
 * Castling is recorded as the king's two-square step and en passant as the
 * pawn's diagonal — the rules work out the rest from the position.
 */
@Serializable
data class ChessMove(
    val from: Int,
    val to: Int,
    val promotion: PieceKind? = null,
) {
    /** Long algebraic, as used by every engine protocol: e2e4, e7e8q. */
    override fun toString(): String =
        squareName(from) + squareName(to) + (promotion?.letter?.lowercaseChar() ?: "")
}

@Serializable
enum class ChessPhase { PLAYING, GAME_OVER }

@Serializable
enum class ChessOutcome(val label: String) {
    // Named for who wins rather than who was mated: every caller wants the
    // result, and "White mated" reads either way round.
    WHITE_WINS("White wins by checkmate"),
    BLACK_WINS("Black wins by checkmate"),
    STALEMATE("Draw by stalemate"),
    FIFTY_MOVE("Draw by the fifty-move rule"),
    REPETITION("Draw by threefold repetition"),
    INSUFFICIENT_MATERIAL("Draw — neither side can mate"),
}

/** How hard the computer thinks about it. */
@Serializable
enum class ChessLevel(val label: String, val depth: Int) {
    CASUAL("Casual", depth = 2),
    CLUB("Club", depth = 3),
    STRONG("Strong", depth = 4),
}

@Serializable
data class ChessOptions(
    val level: ChessLevel = ChessLevel.CLUB,
    /** A hundred half-moves with no capture and no pawn move is a draw. */
    val fiftyMoveRule: Boolean = true,
    /** The same position three times over is a draw. */
    val threefoldRepetition: Boolean = true,
    /**
     * Where the game starts, in FEN. Blank means the ordinary opening array;
     * anything else lets a table set up a puzzle or resume an adjourned game.
     */
    val startingFen: String = "",
)

@Serializable
data class ChessState(
    val options: ChessOptions,
    /** Sixty-four squares in FEN reading order; null is an empty square. */
    val board: List<Piece?>,
    val whiteToMove: Boolean,
    val castling: CastlingRights,
    /** The square a pawn just skipped over, which is where it may be taken. */
    val enPassant: Int?,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
    val phase: ChessPhase,
    val outcome: ChessOutcome?,
    val lastMove: ChessMove?,
    /**
     * One entry per position reached, in the four-field form that decides
     * repetition — the clocks are deliberately left out, since a position
     * repeats whether or not the same number of moves has been played.
     */
    val repetitionKeys: List<String>,
    /** The game score in algebraic notation, one entry per half-move. */
    val moveLog: List<String>,
) {
    /** Seat 0 is White, seat 1 is Black. */
    val turnSeat: Int get() = if (whiteToMove) WHITE_SEAT else BLACK_SEAT

    fun kingSquare(white: Boolean): Int? =
        board.indexOfFirst { it != null && it.white == white && it.kind == PieceKind.KING }
            .takeIf { it >= 0 }
}

const val WHITE_SEAT = 0
const val BLACK_SEAT = 1

fun seatIsWhite(seat: Int): Boolean = seat == WHITE_SEAT

const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

/** A position with everything needed to resume it: board, side, rights, clocks. */
data class FenPosition(
    val board: List<Piece?>,
    val whiteToMove: Boolean,
    val castling: CastlingRights,
    val enPassant: Int?,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
)

/**
 * Parses a FEN string.
 *
 * This is what the tests are built on — the standard perft positions are all
 * published as FEN — and it is also what lets a game start from something other
 * than the opening array.
 */
fun parseFen(fen: String): FenPosition {
    val fields = fen.trim().split(" ")
    require(fields.size >= 4) { "FEN needs at least four fields: $fen" }

    val board = arrayOfNulls<Piece>(64)
    var index = 0
    for (character in fields[0]) {
        when {
            character == '/' -> Unit
            character.isDigit() -> index += character - '0'
            else -> {
                val kind = requireNotNull(PieceKind.fromLetter(character)) {
                    "Unknown piece '$character' in $fen"
                }
                require(index < 64) { "Too many squares in $fen" }
                board[index] = Piece(kind, white = character.isUpperCase())
                index++
            }
        }
    }
    require(index == 64) { "FEN describes $index squares, not 64: $fen" }

    return FenPosition(
        board = board.toList(),
        whiteToMove = fields[1] == "w",
        castling = CastlingRights.fromFen(fields[2]),
        enPassant = fields[3].takeIf { it != "-" }?.let { squareFromName(it) },
        halfmoveClock = fields.getOrNull(4)?.toIntOrNull() ?: 0,
        fullmoveNumber = fields.getOrNull(5)?.toIntOrNull() ?: 1,
    )
}

/** The first four FEN fields — the part that decides whether a position repeats. */
fun positionKey(
    board: List<Piece?>,
    whiteToMove: Boolean,
    castling: CastlingRights,
    enPassant: Int?,
): String = buildString {
    for (rank in 0 until 8) {
        var empty = 0
        for (file in 0 until 8) {
            val piece = board[rank * 8 + file]
            if (piece == null) {
                empty++
            } else {
                if (empty > 0) { append(empty); empty = 0 }
                append(piece.letter)
            }
        }
        if (empty > 0) append(empty)
        if (rank < 7) append('/')
    }
    append(' ').append(if (whiteToMove) 'w' else 'b')
    append(' ').append(castling.toFen())
    append(' ').append(enPassant?.let { squareName(it) } ?: "-")
}

fun ChessState.toFen(): String =
    positionKey(board, whiteToMove, castling, enPassant) + " $halfmoveClock $fullmoveNumber"
