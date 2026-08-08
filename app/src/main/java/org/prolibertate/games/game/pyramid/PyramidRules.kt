package org.prolibertate.games.game.pyramid

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
 * Pyramid as a pure state machine. See RULES-pyramid.md.
 *
 * The odd one out among the patiences here: nothing is built and nothing goes
 * home in order. Cards are taken away in pairs that make thirteen, and the only
 * structure is which of them are still buried.
 */
object PyramidRules : GameRules<PyramidState, PyramidMove> {

    override val gameId: String = GameCatalog.PYRAMID
    const val SOLO_SEAT: Int = 0

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): PyramidState {
        val options = json.decodeFromString<PyramidOptions>(config.optionsJson)
        require(config.seats.size == 1) { "Pyramid seats one, not ${config.seats.size}" }
        return deal(options, config.seed)
    }

    private fun deal(options: PyramidOptions, seed: Long): PyramidState {
        val deck = Decks.standard52().shuffledWith(Random(seed))
        return PyramidState(
            options = options,
            seed = seed,
            pyramid = deck.take(PYRAMID_SIZE),
            stock = deck.drop(PYRAMID_SIZE),
            waste = emptyList(),
            redealsUsed = 0,
            moves = 0,
            log = listOf("Dealt."),
        )
    }

    override fun currentSeat(state: PyramidState): Int? =
        if (isFinished(state)) null else SOLO_SEAT

    /** Every card that can be reached: the exposed pyramid, and the waste's top. */
    fun available(state: PyramidState): List<Pair<PyramidSpot, Card>> {
        val fromPyramid = state.pyramid.indices
            .filter { isExposed(state.pyramid, it) }
            .map { PyramidSpot(PyramidZone.PYRAMID, it) to state.pyramid[it]!! }
        val fromWaste = state.waste.lastOrNull()
            ?.let { listOf(PyramidSpot(PyramidZone.WASTE) to it) }
            .orEmpty()
        return fromPyramid + fromWaste
    }

    override fun legalMoves(state: PyramidState, seat: Int): List<PyramidMove> {
        if (seat != SOLO_SEAT || state.isWon) return emptyList()
        val moves = mutableListOf<PyramidMove>()
        if (state.stock.isNotEmpty()) moves += DrawCard
        if (state.canRedeal) moves += RecycleWaste

        val reachable = available(state)
        reachable.forEach { (spot, card) ->
            if (valueOf(card) == TARGET) moves += TakeKing(spot)
        }
        // Each pair once: the second card is only ever looked for after the
        // first, so a pair cannot be offered both ways round.
        for (i in reachable.indices) {
            for (j in i + 1 until reachable.size) {
                val (spotA, cardA) = reachable[i]
                val (spotB, cardB) = reachable[j]
                if (valueOf(cardA) + valueOf(cardB) == TARGET) {
                    moves += TakePair(spotA, spotB)
                }
            }
        }
        return moves
    }

    override fun applyMove(state: PyramidState, seat: Int, move: PyramidMove): PyramidState {
        require(seat == SOLO_SEAT) { "Pyramid has one seat" }
        require(legalMoves(state, seat).contains(move)) { "$move is not legal here" }
        val next = when (move) {
            DrawCard -> state.copy(
                stock = state.stock.dropLast(1),
                waste = state.waste + state.stock.last(),
            )

            RecycleWaste -> state.copy(
                stock = state.waste.reversed(),
                waste = emptyList(),
                redealsUsed = state.redealsUsed + 1,
                log = state.log + "Waste turned back over.",
            )

            is TakeKing -> remove(state, listOf(move.spot))
            is TakePair -> remove(state, listOf(move.first, move.second))
        }
        return next.copy(
            moves = state.moves + 1,
            log = if (next.isWon) next.log + "Pyramid cleared, in ${state.moves + 1} moves." else next.log,
        )
    }

    /**
     * Takes cards off the table.
     *
     * The pyramid places are blanked rather than compacted, because a place is
     * what tells the two cards above it that they are free — an index that
     * shifted would uncover the wrong cards.
     */
    private fun remove(state: PyramidState, spots: List<PyramidSpot>): PyramidState {
        val pyramid = state.pyramid.toMutableList()
        var waste = state.waste
        spots.forEach { spot ->
            when (spot.zone) {
                PyramidZone.PYRAMID -> pyramid[spot.index] = null
                // Only ever the top card, so dropping the last is enough.
                PyramidZone.WASTE -> waste = waste.dropLast(1)
            }
        }
        return state.copy(pyramid = pyramid.toList(), waste = waste)
    }

    override fun isFinished(state: PyramidState): Boolean =
        state.isWon || legalMoves(state, SOLO_SEAT).isEmpty()

    override fun summary(state: PyramidState): String = when {
        state.isWon -> "Cleared in ${state.moves} moves"
        isFinished(state) -> "Blocked, ${state.pyramid.count { it != null }} cards left"
        else -> "${state.pyramid.count { it != null }} of $PYRAMID_SIZE left"
    }

    override fun viewFor(state: PyramidState, seat: Int): PyramidState = state

    override fun encodeState(state: PyramidState): String = json.encodeToString(state)
    override fun decodeState(json: String): PyramidState = this.json.decodeFromString(json)
    override fun encodeMove(move: PyramidMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): PyramidMove = this.json.decodeFromString(json)

    fun restart(state: PyramidState): PyramidState = deal(state.options, state.seed)
}
