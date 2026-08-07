package org.prolibertate.games.game.pirates

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig

/**
 * Pirates and Bulgars — Asalto — as a pure state machine. See RULES-pirates.md.
 *
 * The two sides do not play the same game, and almost every line here is one
 * side or the other rather than both. Pirates go anywhere along a line and take
 * by jumping, in chains, compulsorily. Bulgars go one step and never backwards,
 * and cannot take anything at all: they win by arriving.
 *
 * That asymmetry is the reason the win conditions are counted rather than
 * shared. Bulgars win by filling the stronghold or by leaving the pirates
 * nowhere to go; pirates win by cutting the Bulgars below the nine it takes to
 * fill it — at which point the attack cannot succeed however long it goes on.
 */
object PiratesRules : GameRules<PiratesState, PiratesMove> {

    override val gameId: String = GameCatalog.PIRATES

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): PiratesState {
        require(config.seats.size == 2) { "Pirates and Bulgars is a two-handed game" }
        val options = json.decodeFromString<PiratesOptions>(config.optionsJson)
        return initialState(options)
    }

    fun initialState(options: PiratesOptions = PiratesOptions()): PiratesState = PiratesState(
        options = options,
        board = startingBoard(),
        // The attackers move first: the pirates are already where they want to be.
        turn = BULGAR_SEAT,
        phase = PiratesPhase.PLAYING,
        outcome = null,
        lastMove = null,
        pliesSinceProgress = 0,
        moveLog = emptyList(),
    )

    override fun currentSeat(state: PiratesState): Int? =
        if (state.phase == PiratesPhase.GAME_OVER) null else state.turn

    // -----------------------------------------------------------------------
    // Moves
    // -----------------------------------------------------------------------

    fun legalMoves(state: PiratesState): List<PiratesMove> = legalMoves(state, state.turn)

    override fun legalMoves(state: PiratesState, seat: Int): List<PiratesMove> {
        if (state.phase == PiratesPhase.GAME_OVER || state.turn != seat) return emptyList()
        val mine = (0 until POINTS).filter { state.board[it] == seat }

        if (seat == PIRATE_SEAT) {
            val jumps = mine.flatMap { from ->
                val board = state.board.toMutableList()
                board[from] = null
                jumpsFrom(board, from).map { PiratesMove(from = from, steps = it) }
            }
            // A pirate who can take must take — the rule huffing exists to
            // enforce, applied directly.
            if (jumps.isNotEmpty() && state.options.captureIsCompulsory) return jumps

            val steps = mine.flatMap { from ->
                ADJACENCY[from].filter { state.board[it] == null }
                    .map { PiratesMove(from = from, steps = listOf(it)) }
            }
            return jumps + steps
        }

        return mine.flatMap { from ->
            ADJACENCY[from]
                .filter { state.board[it] == null && bulgarMayGo(state, from, it) }
                .map { PiratesMove(from = from, steps = listOf(it)) }
        }
    }

    /**
     * Whether a Bulgar may make this step.
     *
     * They press towards the stronghold and never retreat, which is what makes
     * the attack a commitment: a Bulgar that walks past a gap cannot come back
     * for it. The stronghold is at the top of the board, so "forwards" is up
     * the rows and "across" is along one.
     */
    private fun bulgarMayGo(state: PiratesState, from: Int, to: Int): Boolean {
        if (!state.options.bulgarsMayNotRetreat) return true
        return rowOf(to) <= rowOf(from)
    }

    /**
     * Every complete chain of jumps from [at], as lists of landing points.
     *
     * [board] arrives with the jumping pirate already lifted, and is mutated as
     * the search recurses so a Bulgar cannot be jumped twice in one turn. A
     * chain only ends where no further jump exists, so a shorter prefix of a
     * longer capture is never offered on its own.
     */
    private fun jumpsFrom(board: MutableList<Int?>, at: Int): List<List<Int>> {
        val sequences = mutableListOf<List<Int>>()

        for (over in ADJACENCY[at]) {
            if (board[over] != BULGAR_SEAT) continue
            val landing = beyond(at, over) ?: continue
            if (board[landing] != null) continue

            board[over] = null
            val continuations = jumpsFrom(board, landing)
            board[over] = BULGAR_SEAT

            if (continuations.isEmpty()) {
                sequences += listOf(landing)
            } else {
                continuations.forEach { sequences += listOf(landing) + it }
            }
        }

        return sequences
    }

    /** The point directly past [over] as seen from [from], if the line runs on. */
    private fun beyond(from: Int, over: Int): Int? {
        val landing = pointAt(
            rowOf(over) + (rowOf(over) - rowOf(from)),
            columnOf(over) + (columnOf(over) - columnOf(from)),
        ) ?: return null
        return landing.takeIf { it in ADJACENCY[over] }
    }

    override fun applyMove(state: PiratesState, seat: Int, move: PiratesMove): PiratesState {
        require(state.phase != PiratesPhase.GAME_OVER) { "The game is over" }
        require(state.turn == seat) { "It is not seat $seat's turn" }
        require(move in legalMoves(state, seat)) { "Illegal move $move for seat $seat" }

        return make(state, seat, move)
            .copy(moveLog = state.moveLog + "${seatName(seat)}: $move")
            .withTerminalCheck(includeDraws = true)
    }

    /** The position after [move] with the draw clock left off, for the search. */
    fun advanced(state: PiratesState, move: PiratesMove): PiratesState =
        make(state, state.turn, move).withTerminalCheck(includeDraws = false)

    private fun make(state: PiratesState, seat: Int, move: PiratesMove): PiratesState {
        val board = state.board.toMutableList()
        board[move.from] = null

        var taken = 0
        var at = move.from
        for (landing in move.steps) {
            jumpedBetween(at, landing)?.let { over ->
                if (board[over] != null) {
                    board[over] = null
                    taken++
                }
            }
            at = landing
        }
        board[move.to] = seat

        // Progress is a pirate taking somebody, or a Bulgar getting into the
        // stronghold. Everything else is the two sides circling each other.
        val before = state.stronghold()
        val next = state.copy(board = board, turn = other(seat), lastMove = move)
        val gained = next.stronghold() > before

        return next.copy(
            pliesSinceProgress = if (taken > 0 || gained) 0 else state.pliesSinceProgress + 1,
        )
    }

    /**
     * Whether the game has ended.
     *
     * Order matters. Filling the stronghold is checked first because it is an
     * arrival rather than an absence — a board where the Bulgars have just
     * walked into the last square is a Bulgar win even if the pirates have also
     * run out of moves on it.
     */
    private fun PiratesState.withTerminalCheck(includeDraws: Boolean): PiratesState {
        if (stronghold() == STRONGHOLD.size) {
            return copy(phase = PiratesPhase.GAME_OVER, outcome = PiratesOutcome.BULGARS_STORM)
        }

        // Too few Bulgars left to fill it, so the assault can never succeed
        // however long the game runs.
        if (count(BULGAR_SEAT) < STRONGHOLD.size) {
            return copy(phase = PiratesPhase.GAME_OVER, outcome = PiratesOutcome.PIRATES_CUT_DOWN)
        }

        // Whoever is on the clock with nothing to play has lost — which for the
        // pirates is being penned in, and for the Bulgars means they have run
        // themselves out of forward moves.
        if (legalMoves(this, turn).isEmpty()) {
            return copy(
                phase = PiratesPhase.GAME_OVER,
                outcome = if (turn == PIRATE_SEAT) {
                    PiratesOutcome.BULGARS_PEN_IN
                } else {
                    PiratesOutcome.PIRATES_CUT_DOWN
                },
            )
        }

        if (!includeDraws) return this

        if (options.plyLimitWithoutProgress > 0 &&
            pliesSinceProgress >= options.plyLimitWithoutProgress
        ) {
            return copy(phase = PiratesPhase.GAME_OVER, outcome = PiratesOutcome.DRAW_STALEMATE)
        }

        return this
    }

    // -----------------------------------------------------------------------
    // Engine contract
    // -----------------------------------------------------------------------

    override fun isFinished(state: PiratesState): Boolean = state.phase == PiratesPhase.GAME_OVER

    override fun summary(state: PiratesState): String = state.outcome?.label ?: buildString {
        append(if (state.turn == BULGAR_SEAT) "The Bulgars" else "The pirates")
        append(" to move · ")
        append("${state.stronghold()} of ${STRONGHOLD.size} in the stronghold, ")
        append("${state.count(BULGAR_SEAT)} Bulgars left")
    }

    /** Nothing is hidden: the whole board is on the table. */
    override fun viewFor(state: PiratesState, seat: Int): PiratesState = state

    override fun encodeState(state: PiratesState): String = json.encodeToString(state)
    override fun decodeState(json: String): PiratesState = this.json.decodeFromString(json)
    override fun encodeMove(move: PiratesMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): PiratesMove = this.json.decodeFromString(json)

    /** Leaf nodes at [depth], as a check on the move generator. */
    fun perft(state: PiratesState, depth: Int): Long {
        if (depth == 0) return 1L
        val moves = legalMoves(state)
        if (depth == 1) return moves.size.toLong()
        return moves.sumOf { move ->
            val next = advanced(state, move)
            if (next.phase == PiratesPhase.GAME_OVER) 1L else perft(next, depth - 1)
        }
    }
}
