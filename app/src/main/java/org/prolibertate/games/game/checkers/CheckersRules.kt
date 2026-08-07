package org.prolibertate.games.game.checkers

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig

/**
 * Checkers — English draughts — as a pure state machine. See RULES-checkers.md.
 *
 * Two rules shape everything else here. **Captures are compulsory**, so the
 * move generator produces the jumps and throws the quiet moves away whenever
 * there is a jump to be had; and **a multiple jump is one turn**, so a
 * sequence of hops is one move object rather than several. A player cannot stop
 * halfway through a capture, and building it as one move is what makes that
 * true by construction rather than by a rule somewhere that has to be enforced.
 */
object CheckersRules : GameRules<CheckersState, CheckersMove> {

    override val gameId: String = GameCatalog.CHECKERS

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val DIAGONALS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

    override fun initialState(config: TableConfig): CheckersState {
        require(config.seats.size == 2) { "Checkers is a two-handed game" }
        val options = json.decodeFromString<CheckersOptions>(config.optionsJson)
        return initialState(options)
    }

    fun initialState(options: CheckersOptions = CheckersOptions()): CheckersState = CheckersState(
        options = options,
        board = startingBoard(),
        turn = BLACK_SEAT,
        phase = CheckersPhase.PLAYING,
        outcome = null,
        lastMove = null,
        pliesSinceProgress = 0,
        repetitionKeys = emptyList(),
        moveLog = emptyList(),
    ).let { start -> start.copy(repetitionKeys = listOf(start.positionKey())) }

    override fun currentSeat(state: CheckersState): Int? =
        if (state.phase == CheckersPhase.GAME_OVER) null else state.turn

    // -----------------------------------------------------------------------
    // Moves
    // -----------------------------------------------------------------------

    fun legalMoves(state: CheckersState): List<CheckersMove> = legalMoves(state, state.turn)

    override fun legalMoves(state: CheckersState, seat: Int): List<CheckersMove> {
        if (state.phase == CheckersPhase.GAME_OVER || state.turn != seat) return emptyList()

        val mine = (0 until SQUARES).filter { state.board[it]?.seat == seat }

        val jumps = mine.flatMap { square ->
            val piece = state.board[square]!!
            // The piece is lifted before its own jumps are worked out, so the
            // square it came from counts as empty for the rest of the sequence
            // — a jump can and often does end back where it started.
            val board = state.board.toMutableList()
            board[square] = null
            jumpsFrom(state.options, board, square, piece).map {
                CheckersMove(from = square, steps = it)
            }
        }

        // Compulsory capture: a quiet move is only a move when nothing at all
        // can be taken, anywhere on the board.
        if (jumps.isNotEmpty()) return jumps

        return mine.flatMap { square ->
            slidesFrom(state.options, state.board, square, state.board[square]!!).map {
                CheckersMove(from = square, steps = listOf(it))
            }
        }
    }

    /** Which diagonals a piece may travel along: both ways for a king, forwards for a man. */
    private fun directionsFor(piece: Piece): List<Pair<Int, Int>> =
        if (piece.king) DIAGONALS else DIAGONALS.filter { it.first == forwardOf(piece.seat) }

    /** Quiet moves — one square, or any distance for a flying king. */
    private fun slidesFrom(
        options: CheckersOptions,
        board: List<Piece?>,
        from: Int,
        piece: Piece,
    ): List<Int> = directionsFor(piece).flatMap { (rowStep, columnStep) ->
        val reach = if (piece.king && options.flyingKings) 7 else 1
        val landings = mutableListOf<Int>()
        for (distance in 1..reach) {
            val square = squareAt(
                rowOf(from) + rowStep * distance,
                columnOf(from) + columnStep * distance,
            ) ?: break
            if (board[square] != null) break
            landings += square
        }
        landings
    }

    /**
     * Every complete jump sequence from [at], as lists of landing squares.
     *
     * A sequence only ends where no further jump is available: a player may not
     * stop a capture halfway, so a shorter prefix of a longer jump is never
     * offered as a move of its own.
     *
     * [board] is mutated as it recurses and put back afterwards, which is what
     * stops the same piece being taken twice in one turn.
     */
    private fun jumpsFrom(
        options: CheckersOptions,
        board: MutableList<Piece?>,
        at: Int,
        piece: Piece,
    ): List<List<Int>> {
        val sequences = mutableListOf<List<Int>>()

        for ((rowStep, columnStep) in directionsFor(piece)) {
            val hops = if (piece.king && options.flyingKings) {
                longJumps(board, at, piece, rowStep, columnStep)
            } else {
                shortJump(board, at, piece, rowStep, columnStep)
            }

            for ((over, landing) in hops) {
                val taken = board[over]
                board[over] = null

                // A man reaching the crown row stops there under the English
                // rule and is crowned; under the other it carries on as a man
                // and is only crowned if it finishes there.
                val crowning = !piece.king && rowOf(landing) == crownRowOf(piece.seat)
                val continuations = if (crowning && options.crowningEndsTheTurn) {
                    emptyList()
                } else {
                    jumpsFrom(options, board, landing, piece)
                }

                board[over] = taken

                if (continuations.isEmpty()) {
                    sequences += listOf(landing)
                } else {
                    continuations.forEach { sequences += listOf(landing) + it }
                }
            }
        }

        return sequences
    }

    /** The one hop a man or a short king can make in a direction, if any. */
    private fun shortJump(
        board: List<Piece?>,
        at: Int,
        piece: Piece,
        rowStep: Int,
        columnStep: Int,
    ): List<Pair<Int, Int>> {
        val over = squareAt(rowOf(at) + rowStep, columnOf(at) + columnStep) ?: return emptyList()
        val landing = squareAt(rowOf(at) + rowStep * 2, columnOf(at) + columnStep * 2)
            ?: return emptyList()
        val victim = board[over] ?: return emptyList()
        if (victim.seat == piece.seat) return emptyList()
        if (board[landing] != null) return emptyList()
        return listOf(over to landing)
    }

    /**
     * A flying king's captures: the first piece along the diagonal, if it is an
     * enemy, taken by landing on any empty square beyond it.
     *
     * Note that a captured piece is lifted at once rather than left standing
     * until the turn ends. International draughts leaves it there as an
     * obstacle, which occasionally forbids a sequence this allows; the
     * difference is written down in the rules document rather than pretended
     * away.
     */
    private fun longJumps(
        board: List<Piece?>,
        at: Int,
        piece: Piece,
        rowStep: Int,
        columnStep: Int,
    ): List<Pair<Int, Int>> {
        var distance = 1
        val over: Int
        while (true) {
            val square = squareAt(
                rowOf(at) + rowStep * distance,
                columnOf(at) + columnStep * distance,
            ) ?: return emptyList()
            val occupant = board[square]
            if (occupant != null) {
                if (occupant.seat == piece.seat) return emptyList()
                over = square
                break
            }
            distance++
        }

        val landings = mutableListOf<Pair<Int, Int>>()
        var beyond = distance + 1
        while (true) {
            val square = squareAt(
                rowOf(at) + rowStep * beyond,
                columnOf(at) + columnStep * beyond,
            ) ?: break
            if (board[square] != null) break
            landings += over to square
            beyond++
        }
        return landings
    }

    override fun applyMove(state: CheckersState, seat: Int, move: CheckersMove): CheckersState {
        require(state.phase != CheckersPhase.GAME_OVER) { "The game is over" }
        require(state.turn == seat) { "It is not seat $seat's turn" }
        require(move in legalMoves(state, seat)) { "Illegal move $move for seat $seat" }

        val next = make(state, seat, move)
        return next
            .copy(
                repetitionKeys = state.repetitionKeys + next.positionKey(),
                moveLog = state.moveLog + "${seatName(seat)} $move",
            )
            .withTerminalCheck(includeDraws = true)
    }

    /** The position after [move], with the draw bookkeeping left off, for the search. */
    fun advanced(state: CheckersState, move: CheckersMove): CheckersState =
        make(state, state.turn, move).withTerminalCheck(includeDraws = false)

    private fun make(state: CheckersState, seat: Int, move: CheckersMove): CheckersState {
        val board = state.board.toMutableList()
        val piece = requireNotNull(board[move.from]) { "no piece on ${squareName(move.from)}" }
        board[move.from] = null

        var taken = 0
        var at = move.from
        for (landing in move.steps) {
            jumped(at, landing)?.let { over ->
                if (board[over] != null) {
                    board[over] = null
                    taken++
                }
            } ?: run {
                // A flying king's capture is further than a single hop, so the
                // square it took is found by walking the line rather than by
                // halving the distance.
                if (state.options.flyingKings && piece.king) {
                    victimBetween(board, at, landing)?.let { over ->
                        board[over] = null
                        taken++
                    }
                }
            }
            at = landing
        }

        val crowned = !piece.king && rowOf(move.to) == crownRowOf(seat)
        board[move.to] = if (crowned) piece.copy(king = true) else piece

        return state.copy(
            board = board,
            turn = other(seat),
            lastMove = move,
            // Taking a piece or crowning one is progress; shuffling kings
            // around is what the draw rule is there to stop.
            pliesSinceProgress = if (taken > 0 || crowned) 0 else state.pliesSinceProgress + 1,
        )
    }

    /** The single enemy piece standing between two squares on a diagonal. */
    private fun victimBetween(board: List<Piece?>, from: Int, to: Int): Int? {
        val rowStep = (rowOf(to) - rowOf(from)).let { if (it > 0) 1 else -1 }
        val columnStep = (columnOf(to) - columnOf(from)).let { if (it > 0) 1 else -1 }
        var distance = 1
        while (true) {
            val square = squareAt(
                rowOf(from) + rowStep * distance,
                columnOf(from) + columnStep * distance,
            ) ?: return null
            if (square == to) return null
            if (board[square] != null) return square
            distance++
        }
    }

    /**
     * Whether the game has just ended, from the point of view of the player who
     * is now to move: they are the only one who can have lost pieces or run out
     * of squares on the ply just played.
     */
    private fun CheckersState.withTerminalCheck(includeDraws: Boolean): CheckersState {
        val loser = turn

        // No pieces, or no move: both are a loss, and they are the same
        // condition as far as the rules are concerned.
        if (legalMoves(this, loser).isEmpty()) {
            return copy(
                phase = CheckersPhase.GAME_OVER,
                outcome = if (loser == BLACK_SEAT) {
                    CheckersOutcome.WHITE_WINS
                } else {
                    CheckersOutcome.BLACK_WINS
                },
            )
        }

        if (!includeDraws) return this

        if (options.plyLimitWithoutProgress > 0 &&
            pliesSinceProgress >= options.plyLimitWithoutProgress
        ) {
            return copy(
                phase = CheckersPhase.GAME_OVER,
                outcome = CheckersOutcome.DRAW_NO_PROGRESS,
            )
        }

        if (options.threefoldRepetition) {
            val here = repetitionKeys.lastOrNull()
            if (here != null && repetitionKeys.count { it == here } >= 3) {
                return copy(
                    phase = CheckersPhase.GAME_OVER,
                    outcome = CheckersOutcome.DRAW_REPETITION,
                )
            }
        }

        return this
    }

    // -----------------------------------------------------------------------
    // Engine contract
    // -----------------------------------------------------------------------

    override fun isFinished(state: CheckersState): Boolean =
        state.phase == CheckersPhase.GAME_OVER

    override fun summary(state: CheckersState): String = state.outcome?.label
        ?: "${seatName(state.turn)} to move"

    /** Checkers hides nothing: both players see the same board. */
    override fun viewFor(state: CheckersState, seat: Int): CheckersState = state

    override fun encodeState(state: CheckersState): String = json.encodeToString(state)
    override fun decodeState(json: String): CheckersState = this.json.decodeFromString(json)
    override fun encodeMove(move: CheckersMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): CheckersMove = this.json.decodeFromString(json)

    /** Leaf nodes at [depth]. The single most useful check on a move generator. */
    fun perft(state: CheckersState, depth: Int): Long {
        if (depth == 0) return 1L
        val moves = legalMoves(state)
        if (depth == 1) return moves.size.toLong()
        return moves.sumOf { move ->
            val next = advanced(state, move)
            if (next.phase == CheckersPhase.GAME_OVER) 1L else perft(next, depth - 1)
        }
    }
}
