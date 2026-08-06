package org.prolibertate.games.game.golf

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Decks
import org.prolibertate.games.game.cards.shuffledWith
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

/**
 * Golf as a pure state machine. See RULES-golf.md.
 */
object GolfRules : GameRules<GolfState, GolfMove> {

    override val gameId: String = GameCatalog.GOLF

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): GolfState {
        val options = json.decodeFromString<GolfOptions>(config.optionsJson)
        require(config.seats.size == options.playerCount) {
            "Seat count ${config.seats.size} does not match ${options.playerCount} players"
        }
        return deal(
            options = options,
            seed = config.seed,
            hole = 0,
            scores = List(options.playerCount) { 0 },
            log = listOf("Hole 1."),
        )
    }

    private fun deal(
        options: GolfOptions,
        seed: Long,
        hole: Int,
        scores: List<Int>,
        log: List<String>,
    ): GolfState {
        val random = Random(seed + hole * 7919L)
        // A big table needs more than one deck to keep a usable stock.
        val needed = options.playerCount * options.gridSize
        val source = if (needed > 24) Decks.double52() else Decks.standard52()
        var deck = source.shuffledWith(random)

        val grids = mutableListOf<List<Card>>()
        repeat(options.playerCount) {
            grids += deck.take(options.gridSize)
            deck = deck.drop(options.gridSize)
        }

        // The opening reveals are the leftmost cards of the top row, so every
        // player starts knowing the same shape of their own board.
        val revealed = List(options.playerCount) {
            List(options.gridSize) { index -> index < options.startingReveals }
        }

        val firstDiscard = deck.first()
        return GolfState(
            options = options,
            seed = seed,
            hole = hole,
            grids = grids,
            revealed = revealed,
            stock = deck.drop(1),
            discard = listOf(firstDiscard),
            drawn = null,
            drawnFromDiscard = false,
            turn = hole % options.playerCount,
            closedBy = null,
            finalTurnsLeft = 0,
            holeScores = List(options.playerCount) { 0 },
            scores = scores,
            phase = GolfPhase.DRAW,
            log = log,
        )
    }

    /** Advances from [GolfPhase.HOLE_OVER] to the next hole. */
    fun nextHole(state: GolfState): GolfState {
        check(state.phase == GolfPhase.HOLE_OVER) { "Hole is not over" }
        return deal(
            options = state.options,
            seed = state.seed,
            hole = state.hole + 1,
            scores = state.scores,
            log = state.log + "Hole ${state.hole + 2}.",
        )
    }

    override fun currentSeat(state: GolfState): Int? = when (state.phase) {
        GolfPhase.DRAW, GolfPhase.PLACE -> state.turn
        GolfPhase.HOLE_OVER, GolfPhase.GAME_OVER -> null
    }

    // -----------------------------------------------------------------------
    // Legal moves
    // -----------------------------------------------------------------------

    override fun legalMoves(state: GolfState, seat: Int): List<GolfMove> {
        if (currentSeat(state) != seat) return emptyList()
        return when (state.phase) {
            GolfPhase.DRAW -> buildList {
                if (state.stock.isNotEmpty()) add(DrawFromStock)
                if (state.discard.isNotEmpty()) add(DrawFromDiscard)
            }

            GolfPhase.PLACE -> buildList {
                for (index in 0 until state.options.gridSize) add(ReplaceCard(index))
                // A card taken from the discard pile has to be used; only a
                // card off the stock may be thrown away again.
                if (!state.drawnFromDiscard) {
                    for (index in 0 until state.options.gridSize) {
                        if (!state.revealed[seat][index]) add(DiscardAndFlip(index))
                    }
                }
            }

            GolfPhase.HOLE_OVER, GolfPhase.GAME_OVER -> emptyList()
        }
    }

    // -----------------------------------------------------------------------
    // Move application
    // -----------------------------------------------------------------------

    override fun applyMove(state: GolfState, seat: Int, move: GolfMove): GolfState {
        require(currentSeat(state) == seat) { "Not seat $seat's turn" }
        require(move in legalMoves(state, seat)) { "Illegal move $move for seat $seat" }
        return when (move) {
            is DrawFromStock -> state.copy(
                drawn = state.stock.first(),
                drawnFromDiscard = false,
                stock = state.stock.drop(1),
                phase = GolfPhase.PLACE,
            )

            is DrawFromDiscard -> state.copy(
                drawn = state.discard.last(),
                drawnFromDiscard = true,
                discard = state.discard.dropLast(1),
                phase = GolfPhase.PLACE,
            )

            is ReplaceCard -> applyReplace(state, seat, move.index)
            is DiscardAndFlip -> applyDiscardAndFlip(state, seat, move.index)
        }
    }

    private fun applyReplace(state: GolfState, seat: Int, index: Int): GolfState {
        val drawn = requireNotNull(state.drawn) { "Nothing drawn" }
        val grids = state.grids.map { it.toMutableList() }
        val revealed = state.revealed.map { it.toMutableList() }

        val replaced = grids[seat][index]
        grids[seat][index] = drawn
        // Whatever goes into the grid is placed face up.
        revealed[seat][index] = true

        return endTurn(
            state.copy(
                grids = grids,
                revealed = revealed,
                discard = state.discard + replaced,
                drawn = null,
                log = state.log + "Seat $seat takes ${drawn.label}.",
            ),
            seat,
        )
    }

    private fun applyDiscardAndFlip(state: GolfState, seat: Int, index: Int): GolfState {
        val drawn = requireNotNull(state.drawn) { "Nothing drawn" }
        val revealed = state.revealed.map { it.toMutableList() }
        revealed[seat][index] = true

        return endTurn(
            state.copy(
                revealed = revealed,
                discard = state.discard + drawn,
                drawn = null,
                log = state.log + "Seat $seat throws ${drawn.label} and turns one over.",
            ),
            seat,
        )
    }

    /**
     * Closes out a turn: notices a finished grid, counts down the last lap and
     * either moves on or scores the hole.
     */
    private fun endTurn(state: GolfState, seat: Int): GolfState {
        var next = state

        // The first player to turn everything over gives everyone else one
        // more turn, and no more than one.
        if (next.closedBy == null && next.allRevealed(seat)) {
            next = next.copy(
                closedBy = seat,
                finalTurnsLeft = next.options.playerCount - 1,
                log = next.log + "Seat $seat is out — one turn each to answer.",
            )
        } else if (next.closedBy != null) {
            next = next.copy(finalTurnsLeft = (next.finalTurnsLeft - 1).coerceAtLeast(0))
        }

        if (next.closedBy != null && next.finalTurnsLeft <= 0) return scoreHole(next)

        // An empty stock is refilled from the discards, keeping the top card
        // in play. If there is nothing to recycle, the hole ends where it is.
        var stock = next.stock
        var discard = next.discard
        if (stock.isEmpty()) {
            if (discard.size <= 1) return scoreHole(next)
            val top = discard.last()
            val random = Random(next.seed + next.log.size)
            stock = discard.dropLast(1).shuffledWith(random)
            discard = listOf(top)
        }

        return next.copy(
            stock = stock,
            discard = discard,
            turn = (seat + 1) % next.options.playerCount,
            phase = GolfPhase.DRAW,
        )
    }

    private fun scoreHole(state: GolfState): GolfState {
        val holeScores = (0 until state.options.playerCount).map { seat ->
            scoreGrid(state.grids[seat], state.options)
        }
        val scores = state.scores.mapIndexed { seat, total -> total + holeScores[seat] }
        // Everything is turned face up to be counted.
        val revealed = List(state.options.playerCount) { List(state.options.gridSize) { true } }

        val lastHole = state.hole + 1 >= state.options.holes
        return state.copy(
            revealed = revealed,
            holeScores = holeScores,
            scores = scores,
            drawn = null,
            phase = if (lastHole) GolfPhase.GAME_OVER else GolfPhase.HOLE_OVER,
            log = state.log + "Hole ${state.hole + 1}: " +
                holeScores.mapIndexed { seat, s -> "S$seat $s" }.joinToString(", "),
        )
    }

    override fun isFinished(state: GolfState): Boolean = state.phase == GolfPhase.GAME_OVER

    override fun summary(state: GolfState): String = when (state.phase) {
        GolfPhase.GAME_OVER -> {
            val best = state.scores.indices.minByOrNull { state.scores[it] } ?: 0
            "Seat $best wins on ${state.scores[best]}"
        }

        else -> state.scores.mapIndexed { seat, s -> "S$seat:$s" }.joinToString(" ")
    }

    /**
     * Hides every face-down card — including the viewer's own, which is the
     * whole point of the game.
     */
    override fun viewFor(state: GolfState, seat: Int): GolfState = state.copy(
        grids = state.grids.mapIndexed { player, cards ->
            cards.mapIndexed { index, card ->
                if (state.revealed[player][index]) card else HIDDEN_CARD
            }
        },
        stock = emptyList(),
    )

    override fun encodeState(state: GolfState): String = json.encodeToString(state)
    override fun decodeState(json: String): GolfState = this.json.decodeFromString(json)
    override fun encodeMove(move: GolfMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): GolfMove = this.json.decodeFromString(json)
}
