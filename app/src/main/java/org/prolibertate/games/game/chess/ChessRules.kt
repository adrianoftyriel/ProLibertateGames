package org.prolibertate.games.game.chess

import kotlin.math.abs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig

/**
 * Chess as a pure state machine. See RULES-chess.md.
 *
 * Move generation is pseudo-legal followed by a king-safety filter: every
 * candidate is played onto a copy of the board and thrown away if it leaves the
 * mover in check. That is slower than tracking pins, and it is the reason the
 * perft counts in the tests match published figures exactly — there is no
 * special case to get subtly wrong.
 */
object ChessRules : GameRules<ChessState, ChessMove> {

    override val gameId: String = GameCatalog.CHESS

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): ChessState {
        require(config.seats.size == 2) { "Chess is a two-handed game" }
        val options = json.decodeFromString<ChessOptions>(config.optionsJson)
        return fromPosition(options, options.startingFen.ifBlank { START_FEN })
    }

    /** Builds a state from a FEN string, which is also how a puzzle is loaded. */
    fun fromPosition(options: ChessOptions, fen: String): ChessState {
        val position = parseFen(fen)
        return ChessState(
            options = options,
            board = position.board,
            whiteToMove = position.whiteToMove,
            castling = position.castling,
            enPassant = position.enPassant,
            halfmoveClock = position.halfmoveClock,
            fullmoveNumber = position.fullmoveNumber,
            phase = ChessPhase.PLAYING,
            outcome = null,
            lastMove = null,
            repetitionKeys = listOf(
                positionKey(
                    position.board,
                    position.whiteToMove,
                    position.castling,
                    position.enPassant,
                )
            ),
            moveLog = emptyList(),
        ).withTerminalCheck()
    }

    override fun currentSeat(state: ChessState): Int? =
        if (state.phase == ChessPhase.PLAYING) state.turnSeat else null

    // -----------------------------------------------------------------------
    // Attacks
    // -----------------------------------------------------------------------

    private val KNIGHT_STEPS =
        listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
    private val KING_STEPS =
        listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
    private val ROOK_DIRS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private val BISHOP_DIRS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

    private fun pieceAt(board: List<Piece?>, file: Int, rank: Int): Piece? =
        if (file in 0..7 && rank in 0..7) board[squareOf(file, rank)] else null

    /** Whether [square] is attacked by the side [byWhite], king included. */
    fun isAttacked(board: List<Piece?>, square: Int, byWhite: Boolean): Boolean {
        val file = fileOf(square)
        val rank = rankOf(square)

        // A pawn attacks forwards, so an attacker stands one rank behind.
        val pawnRank = rank - if (byWhite) 1 else -1
        for (side in intArrayOf(-1, 1)) {
            val found = pieceAt(board, file + side, pawnRank)
            if (found != null && found.white == byWhite && found.kind == PieceKind.PAWN) return true
        }

        for ((df, dr) in KNIGHT_STEPS) {
            val found = pieceAt(board, file + df, rank + dr)
            if (found != null && found.white == byWhite && found.kind == PieceKind.KNIGHT) {
                return true
            }
        }

        for ((df, dr) in KING_STEPS) {
            val found = pieceAt(board, file + df, rank + dr)
            if (found != null && found.white == byWhite && found.kind == PieceKind.KING) return true
        }

        if (raysHit(board, file, rank, byWhite, ROOK_DIRS, PieceKind.ROOK)) return true
        if (raysHit(board, file, rank, byWhite, BISHOP_DIRS, PieceKind.BISHOP)) return true
        return false
    }

    /** Walks each direction until something blocks, looking for [kind] or a queen. */
    private fun raysHit(
        board: List<Piece?>,
        file: Int,
        rank: Int,
        byWhite: Boolean,
        directions: List<Pair<Int, Int>>,
        kind: PieceKind,
    ): Boolean {
        for ((df, dr) in directions) {
            var f = file + df
            var r = rank + dr
            while (f in 0..7 && r in 0..7) {
                val found = board[squareOf(f, r)]
                if (found != null) {
                    if (found.white == byWhite &&
                        (found.kind == kind || found.kind == PieceKind.QUEEN)
                    ) {
                        return true
                    }
                    break
                }
                f += df
                r += dr
            }
        }
        return false
    }

    /** Whether the side to move is in check. */
    fun inCheck(state: ChessState): Boolean = inCheck(state.board, state.whiteToMove)

    fun inCheck(board: List<Piece?>, white: Boolean): Boolean {
        val king = board.indexOfFirst { it != null && it.white == white && it.kind == PieceKind.KING }
        // A position with no king cannot be in check. Only reachable from a
        // hand-written FEN, but it must not throw.
        return king >= 0 && isAttacked(board, king, byWhite = !white)
    }

    // -----------------------------------------------------------------------
    // Move generation
    // -----------------------------------------------------------------------

    override fun legalMoves(state: ChessState, seat: Int): List<ChessMove> {
        if (currentSeat(state) != seat) return emptyList()
        return legalMoves(state)
    }

    /** Every move the side to move may actually play. */
    fun legalMoves(state: ChessState): List<ChessMove> {
        if (state.phase != ChessPhase.PLAYING) return emptyList()
        val white = state.whiteToMove
        return pseudoMoves(state).filter { move ->
            !inCheck(make(state, move).board, white)
        }
    }

    /** Moves that obey the way pieces travel, but may leave the king in check. */
    private fun pseudoMoves(state: ChessState): List<ChessMove> {
        val moves = mutableListOf<ChessMove>()
        val white = state.whiteToMove
        for (square in 0 until 64) {
            val piece = state.board[square] ?: continue
            if (piece.white != white) continue
            when (piece.kind) {
                PieceKind.PAWN -> pawnMoves(state, square, white, moves)
                PieceKind.KNIGHT -> stepMoves(state, square, white, KNIGHT_STEPS, moves)
                PieceKind.KING -> {
                    stepMoves(state, square, white, KING_STEPS, moves)
                    castlingMoves(state, square, white, moves)
                }

                PieceKind.BISHOP -> slideMoves(state, square, white, BISHOP_DIRS, moves)
                PieceKind.ROOK -> slideMoves(state, square, white, ROOK_DIRS, moves)
                PieceKind.QUEEN ->
                    slideMoves(state, square, white, ROOK_DIRS + BISHOP_DIRS, moves)
            }
        }
        return moves
    }

    private val PROMOTIONS =
        listOf(PieceKind.QUEEN, PieceKind.ROOK, PieceKind.BISHOP, PieceKind.KNIGHT)

    private fun pawnMoves(
        state: ChessState,
        from: Int,
        white: Boolean,
        moves: MutableList<ChessMove>,
    ) {
        val board = state.board
        val file = fileOf(from)
        val rank = rankOf(from)
        val forward = if (white) 1 else -1
        val startRank = if (white) 1 else 6
        val lastRank = if (white) 7 else 0

        fun addPawnMove(to: Int) {
            if (rankOf(to) == lastRank) {
                PROMOTIONS.forEach { moves += ChessMove(from, to, it) }
            } else {
                moves += ChessMove(from, to)
            }
        }

        val ahead = rank + forward
        if (ahead in 0..7 && board[squareOf(file, ahead)] == null) {
            addPawnMove(squareOf(file, ahead))
            val twoAhead = rank + 2 * forward
            if (rank == startRank && board[squareOf(file, twoAhead)] == null) {
                moves += ChessMove(from, squareOf(file, twoAhead))
            }
        }

        if (ahead !in 0..7) return
        for (side in intArrayOf(-1, 1)) {
            val captureFile = file + side
            if (captureFile !in 0..7) continue
            val to = squareOf(captureFile, ahead)
            val occupant = board[to]
            when {
                occupant != null && occupant.white != white -> addPawnMove(to)
                // The square behind a pawn that has just stepped two.
                occupant == null && to == state.enPassant -> moves += ChessMove(from, to)
            }
        }
    }

    private fun stepMoves(
        state: ChessState,
        from: Int,
        white: Boolean,
        steps: List<Pair<Int, Int>>,
        moves: MutableList<ChessMove>,
    ) {
        val file = fileOf(from)
        val rank = rankOf(from)
        for ((df, dr) in steps) {
            val f = file + df
            val r = rank + dr
            if (f !in 0..7 || r !in 0..7) continue
            val to = squareOf(f, r)
            val occupant = state.board[to]
            if (occupant == null || occupant.white != white) moves += ChessMove(from, to)
        }
    }

    private fun slideMoves(
        state: ChessState,
        from: Int,
        white: Boolean,
        directions: List<Pair<Int, Int>>,
        moves: MutableList<ChessMove>,
    ) {
        val file = fileOf(from)
        val rank = rankOf(from)
        for ((df, dr) in directions) {
            var f = file + df
            var r = rank + dr
            while (f in 0..7 && r in 0..7) {
                val to = squareOf(f, r)
                val occupant = state.board[to]
                if (occupant == null) {
                    moves += ChessMove(from, to)
                } else {
                    if (occupant.white != white) moves += ChessMove(from, to)
                    break
                }
                f += df
                r += dr
            }
        }
    }

    private fun castlingMoves(
        state: ChessState,
        from: Int,
        white: Boolean,
        moves: MutableList<ChessMove>,
    ) {
        val homeRank = if (white) 0 else 7
        // A hand-written FEN can claim rights for a king that has moved, so the
        // king's own square is checked rather than trusted.
        if (from != squareOf(4, homeRank)) return
        // Castling out of check is never allowed.
        if (isAttacked(state.board, from, byWhite = !white)) return

        fun clear(vararg files: Int) = files.all { state.board[squareOf(it, homeRank)] == null }
        fun safe(vararg files: Int) = files.all {
            !isAttacked(state.board, squareOf(it, homeRank), byWhite = !white)
        }

        fun rookAt(file: Int): Boolean {
            val piece = state.board[squareOf(file, homeRank)]
            return piece != null && piece.white == white && piece.kind == PieceKind.ROOK
        }

        if (state.castling.kingSide(white) && rookAt(7) && clear(5, 6) && safe(5, 6)) {
            moves += ChessMove(from, squareOf(6, homeRank))
        }
        // The b-file square must be empty but may be attacked — the king does
        // not pass over it.
        if (state.castling.queenSide(white) && rookAt(0) && clear(1, 2, 3) && safe(2, 3)) {
            moves += ChessMove(from, squareOf(2, homeRank))
        }
    }

    // -----------------------------------------------------------------------
    // Making a move
    // -----------------------------------------------------------------------

    /** The position a move produces, before any terminal test. */
    private data class Made(
        val board: List<Piece?>,
        val castling: CastlingRights,
        val enPassant: Int?,
        val halfmoveClock: Int,
        val captured: Piece?,
        val castled: Boolean,
    )

    private fun make(state: ChessState, move: ChessMove): Made {
        val board = state.board.toMutableList()
        val piece = requireNotNull(board[move.from]) { "No piece on ${squareName(move.from)}" }
        var captured = board[move.to]

        // Taking en passant removes a pawn that is not on the target square.
        if (piece.kind == PieceKind.PAWN && move.to == state.enPassant && captured == null) {
            val victim = squareOf(fileOf(move.to), rankOf(move.from))
            captured = board[victim]
            board[victim] = null
        }

        board[move.from] = null
        board[move.to] = move.promotion?.let { Piece(it, piece.white) } ?: piece

        val castled = piece.kind == PieceKind.KING &&
            abs(fileOf(move.to) - fileOf(move.from)) == 2
        if (castled) {
            val homeRank = rankOf(move.from)
            val rookFrom = squareOf(if (fileOf(move.to) == 6) 7 else 0, homeRank)
            val rookTo = squareOf(if (fileOf(move.to) == 6) 5 else 3, homeRank)
            board[rookTo] = board[rookFrom]
            board[rookFrom] = null
        }

        val doubleStep = piece.kind == PieceKind.PAWN &&
            abs(rankOf(move.to) - rankOf(move.from)) == 2
        val enPassant = if (doubleStep) {
            squareOf(fileOf(move.from), (rankOf(move.from) + rankOf(move.to)) / 2)
        } else {
            null
        }

        var castling = state.castling
        if (piece.kind == PieceKind.KING) {
            castling = if (piece.white) {
                castling.copy(whiteKingSide = false, whiteQueenSide = false)
            } else {
                castling.copy(blackKingSide = false, blackQueenSide = false)
            }
        }
        // A rook that moves loses its right; so does one that is captured on
        // its own corner, which is the case that is easy to forget.
        castling = clearCornerRight(castling, move.from)
        castling = clearCornerRight(castling, move.to)

        val resetsClock = piece.kind == PieceKind.PAWN || captured != null
        return Made(
            board = board,
            castling = castling,
            enPassant = enPassant,
            halfmoveClock = if (resetsClock) 0 else state.halfmoveClock + 1,
            captured = captured,
            castled = castled,
        )
    }

    private fun clearCornerRight(rights: CastlingRights, square: Int): CastlingRights =
        when (square) {
            squareOf(0, 0) -> rights.copy(whiteQueenSide = false)
            squareOf(7, 0) -> rights.copy(whiteKingSide = false)
            squareOf(0, 7) -> rights.copy(blackQueenSide = false)
            squareOf(7, 7) -> rights.copy(blackKingSide = false)
            else -> rights
        }

    override fun applyMove(state: ChessState, seat: Int, move: ChessMove): ChessState {
        require(currentSeat(state) == seat) { "Not seat $seat's turn" }
        require(move in legalMoves(state, seat)) { "Illegal move $move for seat $seat" }

        val notation = baseNotation(state, move)
        val made = make(state, move)
        val key = positionKey(made.board, !state.whiteToMove, made.castling, made.enPassant)

        val next = state.copy(
            board = made.board,
            whiteToMove = !state.whiteToMove,
            castling = made.castling,
            enPassant = made.enPassant,
            halfmoveClock = made.halfmoveClock,
            fullmoveNumber = state.fullmoveNumber + if (state.whiteToMove) 0 else 1,
            lastMove = move,
            repetitionKeys = state.repetitionKeys + key,
        ).withTerminalCheck()

        val suffix = when {
            next.outcome == ChessOutcome.WHITE_WINS || next.outcome == ChessOutcome.BLACK_WINS ->
                "#"

            inCheck(next) -> "+"
            else -> ""
        }
        return next.copy(moveLog = state.moveLog + (notation + suffix))
    }

    /** Applies the terminal tests to a position whose side to move is set. */
    private fun ChessState.withTerminalCheck(): ChessState {
        if (legalMoves(this).isEmpty()) {
            val outcome = when {
                // Whoever is to move has been mated, so the other side wins.
                inCheck(this) -> if (whiteToMove) {
                    ChessOutcome.BLACK_WINS
                } else {
                    ChessOutcome.WHITE_WINS
                }

                else -> ChessOutcome.STALEMATE
            }
            return copy(phase = ChessPhase.GAME_OVER, outcome = outcome)
        }
        if (insufficientMaterial(board)) {
            return copy(phase = ChessPhase.GAME_OVER, outcome = ChessOutcome.INSUFFICIENT_MATERIAL)
        }
        if (options.fiftyMoveRule && halfmoveClock >= 100) {
            return copy(phase = ChessPhase.GAME_OVER, outcome = ChessOutcome.FIFTY_MOVE)
        }
        if (options.threefoldRepetition &&
            repetitionKeys.count { it == repetitionKeys.last() } >= 3
        ) {
            return copy(phase = ChessPhase.GAME_OVER, outcome = ChessOutcome.REPETITION)
        }
        return this
    }

    /** Positions from which no sequence of legal moves can produce mate. */
    fun insufficientMaterial(board: List<Piece?>): Boolean {
        val pieces = board.withIndex().mapNotNull { (square, piece) ->
            piece?.takeIf { it.kind != PieceKind.KING }?.let { square to it }
        }
        if (pieces.any {
                it.second.kind == PieceKind.PAWN ||
                    it.second.kind == PieceKind.ROOK ||
                    it.second.kind == PieceKind.QUEEN
            }
        ) {
            return false
        }
        return when (pieces.size) {
            // Bare kings, or a king and one minor piece.
            0, 1 -> true
            // Two bishops on the same colour square can never mate, whichever
            // sides they belong to.
            2 -> pieces.all { it.second.kind == PieceKind.BISHOP } &&
                isLightSquare(pieces[0].first) == isLightSquare(pieces[1].first)

            else -> false
        }
    }

    // -----------------------------------------------------------------------
    // Notation
    // -----------------------------------------------------------------------

    /** Algebraic notation without the check or mate suffix, which needs the result. */
    private fun baseNotation(state: ChessState, move: ChessMove): String {
        val piece = state.board[move.from] ?: return move.toString()
        if (piece.kind == PieceKind.KING && abs(fileOf(move.to) - fileOf(move.from)) == 2) {
            return if (fileOf(move.to) == 6) "O-O" else "O-O-O"
        }

        val captures = state.board[move.to] != null ||
            (piece.kind == PieceKind.PAWN && move.to == state.enPassant)

        return buildString {
            if (piece.kind == PieceKind.PAWN) {
                if (captures) append('a' + fileOf(move.from))
            } else {
                append(piece.kind.letter)
                append(disambiguation(state, move, piece.kind))
            }
            if (captures) append('x')
            append(squareName(move.to))
            move.promotion?.let { append('=').append(it.letter) }
        }
    }

    /**
     * The least that distinguishes this move from another of the same kind to
     * the same square — the file if that is enough, otherwise the rank, and
     * both when two pieces share a file and a rank with a third.
     */
    private fun disambiguation(state: ChessState, move: ChessMove, kind: PieceKind): String {
        val rivals = legalMoves(state).filter { other ->
            other.to == move.to &&
                other.from != move.from &&
                state.board[other.from]?.kind == kind
        }
        if (rivals.isEmpty()) return ""
        val fileIsUnique = rivals.none { fileOf(it.from) == fileOf(move.from) }
        if (fileIsUnique) return ('a' + fileOf(move.from)).toString()
        val rankIsUnique = rivals.none { rankOf(it.from) == rankOf(move.from) }
        if (rankIsUnique) return (rankOf(move.from) + 1).toString()
        return squareName(move.from)
    }

    // -----------------------------------------------------------------------
    // Engine contract
    // -----------------------------------------------------------------------

    override fun isFinished(state: ChessState): Boolean = state.phase == ChessPhase.GAME_OVER

    override fun summary(state: ChessState): String = state.outcome?.label
        ?: if (state.whiteToMove) "White to move" else "Black to move"

    /** Chess hides nothing: both players see the same board. */
    override fun viewFor(state: ChessState, seat: Int): ChessState = state

    override fun encodeState(state: ChessState): String = json.encodeToString(state)
    override fun decodeState(json: String): ChessState = this.json.decodeFromString(json)
    override fun encodeMove(move: ChessMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): ChessMove = this.json.decodeFromString(json)

    /**
     * Counts the leaf nodes at [depth]. Only used by the tests, but it lives
     * here because it is the single most valuable thing that can be said about
     * a move generator: the published counts for a given position are exact, so
     * a generator that matches them at depth four has essentially no room left
     * for a rule it gets wrong.
     */
    fun perft(state: ChessState, depth: Int): Long {
        if (depth == 0) return 1L
        val moves = legalMoves(state)
        if (depth == 1) return moves.size.toLong()
        return moves.sumOf { perft(advanced(state, it), depth - 1) }
    }

    /**
     * The position after [move] with none of the terminal tests applied.
     *
     * The search walks millions of positions it will never keep, and running
     * the repetition and fifty-move checks on every one of them would cost more
     * than the search itself. Anything that ends up on the board goes through
     * [applyMove] instead.
     */
    fun advanced(state: ChessState, move: ChessMove): ChessState {
        val made = make(state, move)
        return state.copy(
            board = made.board,
            whiteToMove = !state.whiteToMove,
            castling = made.castling,
            enPassant = made.enPassant,
            halfmoveClock = made.halfmoveClock,
            lastMove = move,
        )
    }

    /** Whether [move] takes something — including en passant, which takes nothing on its square. */
    fun isCapture(state: ChessState, move: ChessMove): Boolean =
        state.board[move.to] != null ||
            (state.board[move.from]?.kind == PieceKind.PAWN && move.to == state.enPassant)
}
