package org.prolibertate.games.game.freecell

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Decks
import org.prolibertate.games.game.cards.shuffledWith
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig
import org.prolibertate.games.game.solitaire.acceptsOnFoundation
import org.prolibertate.games.game.solitaire.buildsDownAlternating
import org.prolibertate.games.game.solitaire.isRun
import kotlin.random.Random

/**
 * FreeCell as a pure state machine. See RULES-freecell.md.
 *
 * The whole pack is face up from the first move, so unlike Klondike there is
 * nothing hidden and nothing to be lucky about — very nearly every deal can be
 * won by someone who plays it well enough.
 */
object FreeCellRules : GameRules<FreeCellState, FreeCellMove> {

    override val gameId: String = GameCatalog.FREECELL
    const val SOLO_SEAT: Int = 0

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): FreeCellState {
        val options = json.decodeFromString<FreeCellOptions>(config.optionsJson)
        require(config.seats.size == 1) { "FreeCell seats one, not ${config.seats.size}" }
        return deal(options, config.seed)
    }

    private fun deal(options: FreeCellOptions, seed: Long): FreeCellState {
        val deck = Decks.standard52().shuffledWith(Random(seed))
        // Fifty-two into eight leaves four columns of seven and four of six.
        val columns = mutableListOf<List<Card>>()
        var at = 0
        repeat(COLUMNS) { index ->
            val size = if (index < 4) 7 else 6
            columns += deck.subList(at, at + size).toList()
            at += size
        }
        return FreeCellState(
            options = options,
            seed = seed,
            cells = List(options.freeCells) { null },
            foundations = List(FOUNDATIONS) { emptyList() },
            tableau = columns,
            moves = 0,
            log = listOf("Dealt, with ${options.freeCells} cells."),
        )
    }

    override fun currentSeat(state: FreeCellState): Int? =
        if (isFinished(state)) null else SOLO_SEAT

    /** The longest run sitting on the end of a column, by the build rule. */
    fun movableTail(column: List<Card>): List<Card> {
        if (column.isEmpty()) return emptyList()
        var take = 1
        while (take < column.size) {
            val candidate = column.takeLast(take + 1)
            if (!isRun(candidate, ::buildsDownAlternating)) break
            take++
        }
        return column.takeLast(take)
    }

    override fun legalMoves(state: FreeCellState, seat: Int): List<FreeCellMove> {
        if (seat != SOLO_SEAT || state.isWon) return emptyList()
        val moves = mutableListOf<FreeCellMove>()

        fun offer(from: Place, cards: List<Card>) {
            val head = cards.first()
            if (cards.size == 1) {
                val foundation = head.suit.ordinal
                if (acceptsOnFoundation(state.foundations[foundation], head)) {
                    moves += MoveTo(from, Place.foundation(foundation), 1)
                }
                state.cells.indices.forEach { cell ->
                    if (state.cells[cell] == null && from.kind != CellKind.CELL) {
                        moves += MoveTo(from, Place.cell(cell), 1)
                    }
                }
            }
            state.tableau.indices.forEach { column ->
                if (from.kind == CellKind.TABLEAU && from.index == column) return@forEach
                val target = state.tableau[column]
                val fits = target.lastOrNull()?.let { buildsDownAlternating(head, it) } ?: true
                if (!fits) return@forEach
                // Moving a whole column into an empty one is a no-op that would
                // let a game shuffle for ever.
                if (target.isEmpty() && from.kind == CellKind.TABLEAU &&
                    cards.size == state.tableau[from.index].size
                ) {
                    return@forEach
                }
                val allowed = maxRunLength(
                    freeCells = state.freeCellCount,
                    emptyColumns = state.emptyColumns,
                    intoEmptyColumn = target.isEmpty(),
                ).let { if (state.options.allowSupermoves) it else 1 }
                if (cards.size <= allowed) moves += MoveTo(from, Place.column(column), cards.size)
            }
        }

        state.cells.forEachIndexed { index, card ->
            card?.let { offer(Place.cell(index), listOf(it)) }
        }
        state.tableau.forEachIndexed { index, column ->
            val tail = movableTail(column)
            // Every length of the tail run can travel, not just the whole of it.
            for (count in 1..tail.size) {
                offer(Place.column(index), tail.takeLast(count))
            }
        }
        return moves
    }

    override fun applyMove(state: FreeCellState, seat: Int, move: FreeCellMove): FreeCellState {
        require(seat == SOLO_SEAT) { "FreeCell has one seat" }
        require(legalMoves(state, seat).contains(move)) { "$move is not legal here" }
        move as MoveTo
        val cards = when (move.from.kind) {
            CellKind.CELL -> listOfNotNull(state.cells[move.from.index])
            CellKind.TABLEAU -> state.tableau[move.from.index].takeLast(move.count)
            CellKind.FOUNDATION -> state.foundations[move.from.index].takeLast(move.count)
        }
        var next = when (move.from.kind) {
            CellKind.CELL -> state.copy(cells = state.cells.replacing(move.from.index, null))
            CellKind.TABLEAU -> state.copy(
                tableau = state.tableau.replacing(
                    move.from.index,
                    state.tableau[move.from.index].dropLast(move.count),
                ),
            )

            CellKind.FOUNDATION -> state.copy(
                foundations = state.foundations.replacing(
                    move.from.index,
                    state.foundations[move.from.index].dropLast(move.count),
                ),
            )
        }
        next = when (move.to.kind) {
            CellKind.CELL -> next.copy(cells = next.cells.replacing(move.to.index, cards.single()))
            CellKind.TABLEAU -> next.copy(
                tableau = next.tableau.replacing(
                    move.to.index,
                    next.tableau[move.to.index] + cards,
                ),
            )

            CellKind.FOUNDATION -> next.copy(
                foundations = next.foundations.replacing(
                    move.to.index,
                    next.foundations[move.to.index] + cards,
                ),
            )
        }
        return next.copy(
            moves = state.moves + 1,
            log = if (next.isWon) next.log + "Out, in ${state.moves + 1} moves." else next.log,
        )
    }

    private fun <T> List<T>.replacing(index: Int, value: T): List<T> =
        toMutableList().also { it[index] = value }

    override fun isFinished(state: FreeCellState): Boolean =
        state.isWon || legalMoves(state, SOLO_SEAT).isEmpty()

    override fun summary(state: FreeCellState): String = when {
        state.isWon -> "Out in ${state.moves} moves"
        isFinished(state) -> "Blocked, ${state.foundations.sumOf { it.size }} cards home"
        else -> "${state.foundations.sumOf { it.size }} of 52 home"
    }

    override fun viewFor(state: FreeCellState, seat: Int): FreeCellState = state

    override fun encodeState(state: FreeCellState): String = json.encodeToString(state)
    override fun decodeState(json: String): FreeCellState = this.json.decodeFromString(json)
    override fun encodeMove(move: FreeCellMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): FreeCellMove = this.json.decodeFromString(json)

    fun restart(state: FreeCellState): FreeCellState = deal(state.options, state.seed)
}
