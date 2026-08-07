package org.prolibertate.games.game.morris

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig

/**
 * Nine Men's Morris as a pure state machine. See RULES-morris.md.
 *
 * A turn is one move object: the piece placed or stepped, and — when that
 * closes a mill — the enemy piece taken for it. Generating the removal as part
 * of the move rather than as a turn of its own is what keeps the state machine
 * honest. There is no moment where a player is halfway through a turn, so a
 * client that disconnects between closing a mill and choosing what to take
 * cannot leave the table stuck, and the search counts one ply for what is one
 * decision.
 */
object MorrisRules : GameRules<MorrisState, MorrisMove> {

    override val gameId: String = GameCatalog.MORRIS

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): MorrisState {
        require(config.seats.size == 2) { "Nine Men's Morris is a two-handed game" }
        val options = json.decodeFromString<MorrisOptions>(config.optionsJson)
        return initialState(options)
    }

    fun initialState(options: MorrisOptions = MorrisOptions()): MorrisState = MorrisState(
        options = options,
        board = List(POINTS) { null },
        turn = WHITE_SEAT,
        placed = listOf(0, 0),
        phase = MorrisPhase.PLACING,
        outcome = null,
        lastMove = null,
        pliesSinceMill = 0,
        repetitionKeys = emptyList(),
        moveLog = emptyList(),
    ).let { start -> start.copy(repetitionKeys = listOf(start.positionKey())) }

    override fun currentSeat(state: MorrisState): Int? =
        if (state.phase == MorrisPhase.GAME_OVER) null else state.turn

    // -----------------------------------------------------------------------
    // Moves
    // -----------------------------------------------------------------------

    fun legalMoves(state: MorrisState): List<MorrisMove> = legalMoves(state, state.turn)

    override fun legalMoves(state: MorrisState, seat: Int): List<MorrisMove> {
        if (state.phase == MorrisPhase.GAME_OVER || state.turn != seat) return emptyList()

        val steps: List<Pair<Int?, Int>> = if (state.phase == MorrisPhase.PLACING) {
            if (state.inHand(seat) <= 0) {
                emptyList()
            } else {
                (0 until POINTS).filter { state.board[it] == null }.map { null to it }
            }
        } else {
            // Down to three pieces, a player jumps to any empty point rather
            // than stepping along a line — if the table is playing that way.
            val flying = state.isFlying(seat)
            (0 until POINTS).filter { state.board[it] == seat }.flatMap { from ->
                val reachable: Iterable<Int> = if (flying) 0 until POINTS else ADJACENCY[from]
                reachable.filter { state.board[it] == null }.map { from to it }
            }
        }

        return steps.flatMap { (from, to) ->
            if (!closesMill(state, from, to, seat)) {
                listOf(MorrisMove(to = to, from = from))
            } else {
                val takeable = removablePoints(state, other(seat))
                // Nothing left to take is not a reason to forbid the move; the
                // mill simply goes unpaid.
                if (takeable.isEmpty()) {
                    listOf(MorrisMove(to = to, from = from))
                } else {
                    takeable.map { MorrisMove(to = to, from = from, remove = it) }
                }
            }
        }
    }

    /**
     * Whether landing on [to] — having left [from], if this is a step rather
     * than a placement — completes a line of three.
     *
     * The vacated point is excluded deliberately: sliding a piece along its own
     * mill does not re-close that mill, because the piece it needs is the one
     * that just left.
     */
    fun closesMill(state: MorrisState, from: Int?, to: Int, seat: Int): Boolean =
        MILLS_THROUGH[to].any { mill ->
            mill.all { point -> point == to || (point != from && state.board[point] == seat) }
        }

    /**
     * Which of [victim]'s pieces may be taken: any that is not standing in a
     * mill, or — when every one of them is — any at all. Without that second
     * clause a player whose pieces all sat in mills could never be taken from
     * again, and the game would not end.
     *
     * The mover's own move cannot change the victim's mills, so this reads the
     * board as it stands rather than as it will be.
     */
    fun removablePoints(state: MorrisState, victim: Int): List<Int> {
        val theirs = (0 until POINTS).filter { state.board[it] == victim }
        return theirs.filterNot { state.isInMill(it) }.ifEmpty { theirs }
    }

    override fun applyMove(state: MorrisState, seat: Int, move: MorrisMove): MorrisState {
        require(state.phase != MorrisPhase.GAME_OVER) { "The game is over" }
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

    /**
     * The position after [move], with the draw bookkeeping left off.
     *
     * The search walks far more positions than it keeps, and neither the
     * repetition list nor the game record affects which move is best from here.
     * Wins and losses are still detected: a search that could not see them
     * would play into a loss it had the moves to avoid.
     */
    fun advanced(state: MorrisState, move: MorrisMove): MorrisState =
        make(state, state.turn, move).withTerminalCheck(includeDraws = false)

    /** Applies the move to the board without deciding whether the game is over. */
    private fun make(state: MorrisState, seat: Int, move: MorrisMove): MorrisState {
        val board = state.board.toMutableList()
        move.from?.let { board[it] = null }
        board[move.to] = seat
        move.remove?.let { board[it] = null }

        val placed = state.placed.toMutableList()
        if (move.from == null) placed[seat] = placed[seat] + 1

        val everythingPlaced = placed.sum() >= state.options.piecesEach * 2

        return state.copy(
            board = board,
            turn = other(seat),
            placed = placed,
            phase = if (everythingPlaced) MorrisPhase.MOVING else MorrisPhase.PLACING,
            lastMove = move,
            // A mill closed is progress; anything else is the clock running
            // towards a drawn game.
            pliesSinceMill = if (move.remove != null) 0 else state.pliesSinceMill + 1,
        )
    }

    /**
     * Decides whether the game has just ended, from the point of view of the
     * player who is now to move — they are the only one who can have lost a
     * piece or a move on the ply just played.
     *
     * A win is looked for before a draw: a position that both ends the game and
     * runs out the draw clock is a win, not a draw.
     */
    private fun MorrisState.withTerminalCheck(includeDraws: Boolean): MorrisState {
        val loser = turn

        // Two pieces cannot make a line, so a player reduced to two has lost.
        // Only once their hand is empty: a player mid-placement has more coming.
        if (phase != MorrisPhase.PLACING && onBoard(loser) < 3) {
            return copy(
                phase = MorrisPhase.GAME_OVER,
                outcome = if (loser == BLACK_SEAT) {
                    MorrisOutcome.WHITE_WINS_REDUCED
                } else {
                    MorrisOutcome.BLACK_WINS_REDUCED
                },
            )
        }

        // Penned in with nowhere to go is the other way to lose. There is
        // always somewhere to place, so this can only bite once pieces move.
        if (legalMoves(this, loser).isEmpty()) {
            return copy(
                phase = MorrisPhase.GAME_OVER,
                outcome = if (loser == BLACK_SEAT) {
                    MorrisOutcome.WHITE_WINS_BLOCKED
                } else {
                    MorrisOutcome.BLACK_WINS_BLOCKED
                },
            )
        }

        if (!includeDraws) return this

        if (options.plyLimitWithoutMill > 0 && pliesSinceMill >= options.plyLimitWithoutMill) {
            return copy(phase = MorrisPhase.GAME_OVER, outcome = MorrisOutcome.DRAW_NO_MILL)
        }

        if (options.threefoldRepetition) {
            val here = repetitionKeys.lastOrNull()
            if (here != null && repetitionKeys.count { it == here } >= 3) {
                return copy(phase = MorrisPhase.GAME_OVER, outcome = MorrisOutcome.DRAW_REPETITION)
            }
        }

        return this
    }

    // -----------------------------------------------------------------------
    // Engine contract
    // -----------------------------------------------------------------------

    override fun isFinished(state: MorrisState): Boolean = state.phase == MorrisPhase.GAME_OVER

    override fun summary(state: MorrisState): String = state.outcome?.label ?: when (state.phase) {
        MorrisPhase.PLACING ->
            "${seatName(state.turn)} to place — ${state.inHand(state.turn)} left in hand"

        else -> "${seatName(state.turn)} to move"
    }

    /** Nothing is hidden: both players are looking at the same board. */
    override fun viewFor(state: MorrisState, seat: Int): MorrisState = state

    override fun encodeState(state: MorrisState): String = json.encodeToString(state)
    override fun decodeState(json: String): MorrisState = this.json.decodeFromString(json)
    override fun encodeMove(move: MorrisMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): MorrisMove = this.json.decodeFromString(json)

    /**
     * Counts the leaf nodes at [depth] — the same measure a chess move
     * generator is checked against, and the most useful single thing that can
     * be said about this one. It walks every move to the bottom, so a rule that
     * is wrong anywhere shows up as a different number.
     */
    fun perft(state: MorrisState, depth: Int): Long {
        if (depth == 0) return 1L
        val moves = legalMoves(state)
        if (depth == 1) return moves.size.toLong()
        return moves.sumOf { move ->
            val next = advanced(state, move)
            if (next.phase == MorrisPhase.GAME_OVER) 1L else perft(next, depth - 1)
        }
    }
}
