package org.prolibertate.games.game.pegsolitaire

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig

/**
 * Peg solitaire as a pure state machine. See RULES-pegsolitaire.md.
 *
 * The first game here played by one person, which the engine takes in its
 * stride: there is one seat, it is always on the clock until the board runs out
 * of jumps, and [viewFor] has nothing to hide because there is nobody to hide it
 * from.
 */
object PegSolitaireRules : GameRules<PegSolitaireState, PegSolitaireMove> {

    override val gameId: String = GameCatalog.PEG_SOLITAIRE

    /** The one seat. Named rather than written as a bare 0 at each use. */
    const val SOLO_SEAT: Int = 0

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): PegSolitaireState {
        val options = json.decodeFromString<PegSolitaireOptions>(config.optionsJson)
        require(config.seats.size == 1) {
            "Peg solitaire seats one, not ${config.seats.size}"
        }
        val start = options.start()
        return PegSolitaireState(
            options = options,
            pegs = options.board.holes() - start,
            jumps = 0,
            lastJump = null,
            log = listOf("${options.board.label}, opening at row ${start.row + 1}."),
        )
    }

    /**
     * Every jump available on the board as it stands.
     *
     * A jump needs three holes in a line: a peg to move, a peg beside it to take,
     * and an empty hole beyond. Checking that the landing hole is on the board is
     * what keeps a jump from running off a triangle's short row or out through a
     * cross's missing corner.
     */
    fun legalJumps(state: PegSolitaireState): List<PegJump> {
        val holes = state.board.holes()
        val directions = state.board.directions()
        return state.pegs.flatMap { from ->
            directions.mapNotNull { (dr, dc) ->
                val over = Hole(from.row + dr, from.col + dc)
                val to = Hole(from.row + 2 * dr, from.col + 2 * dc)
                // `over` holding a peg already implies it is a hole, since a peg
                // can only ever sit in one.
                val ok = state.pegs.contains(over) &&
                    holes.contains(to) &&
                    !state.pegs.contains(to)
                if (ok) PegJump(from, to) else null
            }
        }
    }

    override fun currentSeat(state: PegSolitaireState): Int? =
        if (isFinished(state)) null else SOLO_SEAT

    override fun legalMoves(state: PegSolitaireState, seat: Int): List<PegSolitaireMove> =
        if (seat != SOLO_SEAT) emptyList() else legalJumps(state)

    override fun applyMove(
        state: PegSolitaireState,
        seat: Int,
        move: PegSolitaireMove,
    ): PegSolitaireState {
        require(seat == SOLO_SEAT) { "Peg solitaire has one seat" }
        val jump = move as PegJump
        require(legalJumps(state).contains(jump)) { "$jump is not a legal jump" }
        val pegs = state.pegs - jump.from - jump.over + jump.to
        val next = state.copy(
            pegs = pegs,
            jumps = state.jumps + 1,
            lastJump = jump,
        )
        val note = when {
            next.solved -> "Solved in ${next.jumps} jumps."
            legalJumps(next).isEmpty() -> "No jumps left, ${next.remaining} pegs standing."
            else -> null
        }
        return if (note == null) next else next.copy(log = next.log + note)
    }

    /** The board is finished when nothing can jump, whether or not it was solved. */
    override fun isFinished(state: PegSolitaireState): Boolean = legalJumps(state).isEmpty()

    override fun summary(state: PegSolitaireState): String = when {
        state.solved -> "Solved in ${state.jumps} jumps"
        isFinished(state) -> "Stuck with ${state.remaining} pegs"
        else -> "${state.remaining} pegs, ${legalJumps(state).size} jumps available"
    }

    /**
     * Nothing to strip. One person plays, the whole board is face up in front of
     * them, and there is no second seat that could be told less than the truth.
     */
    override fun viewFor(state: PegSolitaireState, seat: Int): PegSolitaireState = state

    override fun encodeState(state: PegSolitaireState): String = json.encodeToString(state)
    override fun decodeState(json: String): PegSolitaireState = this.json.decodeFromString(json)
    override fun encodeMove(move: PegSolitaireMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): PegSolitaireMove = this.json.decodeFromString(json)

    /** Deals the same board again, so a lost game can be retried from the start. */
    fun restart(state: PegSolitaireState): PegSolitaireState {
        val start = state.options.start()
        return state.copy(
            pegs = state.options.board.holes() - start,
            jumps = 0,
            lastJump = null,
            log = state.log + "Board reset.",
        )
    }
}
