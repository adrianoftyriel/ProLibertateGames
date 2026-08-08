package org.prolibertate.games.game.spider

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.shuffledWith
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

/**
 * Spider as a pure state machine. See RULES-spider.md.
 */
object SpiderRules : GameRules<SpiderState, SpiderMove> {

    override val gameId: String = GameCatalog.SPIDER
    const val SOLO_SEAT: Int = 0

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): SpiderState {
        val options = json.decodeFromString<SpiderOptions>(config.optionsJson)
        require(config.seats.size == 1) { "Spider seats one, not ${config.seats.size}" }
        return deal(options, config.seed)
    }

    private fun deal(options: SpiderOptions, seed: Long): SpiderState {
        val deck = spiderDeck(options.suits).shuffledWith(Random(seed)).toMutableList()
        // Fifty-four cards go out: six to the first four columns, five to the
        // rest. The remaining fifty are the five rows still to come.
        val piles = (0 until COLUMNS).map { index ->
            val size = if (index < 4) 6 else 5
            val taken = List(size) { deck.removeAt(deck.size - 1) }
            SpiderPile(faceDown = taken.dropLast(1), faceUp = listOf(taken.last()))
        }
        return SpiderState(
            options = options,
            seed = seed,
            stock = deck.toList(),
            tableau = piles,
            completed = 0,
            moves = 0,
            log = listOf("Dealt, ${options.suits} suit${if (options.suits == 1) "" else "s"}."),
        )
    }

    override fun currentSeat(state: SpiderState): Int? =
        if (isFinished(state)) null else SOLO_SEAT

    /** The longest same-suit descending run on the end of a column. */
    fun movableTail(pile: SpiderPile): List<Card> {
        if (pile.faceUp.isEmpty()) return emptyList()
        var take = 1
        while (take < pile.faceUp.size) {
            if (!sameSuitRun(pile.faceUp.takeLast(take + 1))) break
            take++
        }
        return pile.faceUp.takeLast(take)
    }

    override fun legalMoves(state: SpiderState, seat: Int): List<SpiderMove> {
        if (seat != SOLO_SEAT || state.isWon) return emptyList()
        val moves = mutableListOf<SpiderMove>()
        if (state.canDeal) moves += DealRow

        state.tableau.forEachIndexed { from, pile ->
            val tail = movableTail(pile)
            for (count in 1..tail.size) {
                val run = tail.takeLast(count)
                val head = run.first()
                state.tableau.indices.forEach { to ->
                    if (to == from) return@forEach
                    val target = state.tableau[to]
                    val fits = target.top?.let { landsOn(head, it) } ?: true
                    if (!fits) return@forEach
                    // Emptying one column only to fill another gains nothing.
                    if (target.isEmpty && pile.faceDown.isEmpty() && count == pile.faceUp.size) {
                        return@forEach
                    }
                    moves += MoveRun(from, to, count)
                }
            }
        }
        return moves
    }

    override fun applyMove(state: SpiderState, seat: Int, move: SpiderMove): SpiderState {
        require(seat == SOLO_SEAT) { "Spider has one seat" }
        require(legalMoves(state, seat).contains(move)) { "$move is not legal here" }
        val next = when (move) {
            DealRow -> dealRow(state)
            is MoveRun -> moveRun(state, move)
        }
        return next.copy(
            moves = state.moves + 1,
            log = if (next.isWon) next.log + "Out, in ${state.moves + 1} moves." else next.log,
        )
    }

    private fun dealRow(state: SpiderState): SpiderState {
        var stock = state.stock
        val piles = state.tableau.map { pile ->
            val card = stock.lastOrNull() ?: return@map pile
            stock = stock.dropLast(1)
            pile.copy(faceUp = pile.faceUp + card)
        }
        // A dealt row can complete a run in any column it landed on.
        return sweep(state.copy(stock = stock, tableau = piles), piles.indices.toList())
    }

    private fun moveRun(state: SpiderState, move: MoveRun): SpiderState {
        val source = state.tableau[move.from]
        val run = source.faceUp.takeLast(move.count)
        val left = source.faceUp.dropLast(move.count)
        val stripped = if (left.isEmpty() && source.faceDown.isNotEmpty()) {
            SpiderPile(source.faceDown.dropLast(1), listOf(source.faceDown.last()))
        } else {
            source.copy(faceUp = left)
        }
        val target = state.tableau[move.to]
        val piles = state.tableau.toMutableList().also {
            it[move.from] = stripped
            it[move.to] = target.copy(faceUp = target.faceUp + run)
        }
        return sweep(state.copy(tableau = piles), listOf(move.to))
    }

    /**
     * Sends away any king-to-ace run finished by the last move.
     *
     * Only the columns that changed are looked at, and only their ends: a
     * complete run can only ever be sitting on the bottom of a pile, because
     * anything laid on an ace would have to be lower than one.
     */
    private fun sweep(state: SpiderState, touched: List<Int>): SpiderState {
        var piles = state.tableau
        var completed = state.completed
        var notes = state.log
        touched.forEach { index ->
            val pile = piles[index]
            if (pile.faceUp.size < RUN_LENGTH) return@forEach
            if (!isCompleteRun(pile.faceUp.takeLast(RUN_LENGTH))) return@forEach
            val left = pile.faceUp.dropLast(RUN_LENGTH)
            val stripped = if (left.isEmpty() && pile.faceDown.isNotEmpty()) {
                SpiderPile(pile.faceDown.dropLast(1), listOf(pile.faceDown.last()))
            } else {
                pile.copy(faceUp = left)
            }
            piles = piles.toMutableList().also { it[index] = stripped }
            completed++
            notes = notes + "A run went away — $completed of $RUNS_TO_WIN."
        }
        return state.copy(tableau = piles, completed = completed, log = notes)
    }

    override fun isFinished(state: SpiderState): Boolean =
        state.isWon || legalMoves(state, SOLO_SEAT).isEmpty()

    override fun summary(state: SpiderState): String = when {
        state.isWon -> "All eight runs, in ${state.moves} moves"
        isFinished(state) -> "Blocked with ${state.completed} runs away"
        else -> "${state.completed} of $RUNS_TO_WIN runs away"
    }

    override fun viewFor(state: SpiderState, seat: Int): SpiderState = state

    override fun encodeState(state: SpiderState): String = json.encodeToString(state)
    override fun decodeState(json: String): SpiderState = this.json.decodeFromString(json)
    override fun encodeMove(move: SpiderMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): SpiderMove = this.json.decodeFromString(json)

    fun restart(state: SpiderState): SpiderState = deal(state.options, state.seed)
}
