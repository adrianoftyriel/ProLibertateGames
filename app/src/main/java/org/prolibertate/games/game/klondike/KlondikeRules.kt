package org.prolibertate.games.game.klondike

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Decks
import org.prolibertate.games.game.cards.shuffledWith
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig
import org.prolibertate.games.game.solitaire.acceptsOnFoundation
import kotlin.random.Random

/**
 * Klondike as a pure state machine. See RULES-klondike.md.
 *
 * One seat, like peg solitaire: the player is not playing against anybody, only
 * against the deal.
 */
object KlondikeRules : GameRules<KlondikeState, KlondikeMove> {

    override val gameId: String = GameCatalog.KLONDIKE

    const val SOLO_SEAT: Int = 0

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): KlondikeState {
        val options = json.decodeFromString<KlondikeOptions>(config.optionsJson)
        require(config.seats.size == 1) { "Klondike seats one, not ${config.seats.size}" }
        return deal(options, config.seed)
    }

    private fun deal(options: KlondikeOptions, seed: Long): KlondikeState {
        val deck = Decks.standard52().shuffledWith(Random(seed)).toMutableList()
        val piles = (0 until TABLEAU_PILES).map { index ->
            // Column n gets n+1 cards, and only the last of them is turned up.
            val taken = List(index + 1) { deck.removeAt(deck.size - 1) }
            TableauPile(faceDown = taken.dropLast(1), faceUp = listOf(taken.last()))
        }
        return KlondikeState(
            options = options,
            seed = seed,
            stock = deck.toList(),
            waste = emptyList(),
            foundations = List(FOUNDATIONS) { emptyList() },
            tableau = piles,
            redealsUsed = 0,
            moves = 0,
            log = listOf("Dealt. Drawing ${options.drawCount} at a time."),
        )
    }

    override fun currentSeat(state: KlondikeState): Int? =
        if (isFinished(state)) null else SOLO_SEAT

    override fun legalMoves(state: KlondikeState, seat: Int): List<KlondikeMove> {
        if (seat != SOLO_SEAT || state.isWon) return emptyList()
        val moves = mutableListOf<KlondikeMove>()
        if (state.stock.isNotEmpty()) moves += Draw
        if (state.canRedeal) moves += Redeal

        state.waste.lastOrNull()?.let { card ->
            val foundation = foundationFor(card)
            if (acceptsOnFoundation(state.foundations[foundation], card)) {
                moves += MoveCards(Spot.waste, Spot.foundation(foundation))
            }
            state.tableau.indices.forEach { pile ->
                if (tableauAccepts(state, pile, card)) {
                    moves += MoveCards(Spot.waste, Spot.tableau(pile))
                }
            }
        }

        state.tableau.forEachIndexed { index, pile ->
            pile.top?.let { card ->
                val foundation = foundationFor(card)
                if (acceptsOnFoundation(state.foundations[foundation], card)) {
                    moves += MoveCards(Spot.tableau(index), Spot.foundation(foundation))
                }
            }
            for (count in 1..pile.faceUp.size) {
                val run = pile.faceUp.takeLast(count)
                if (!isMovableRun(run)) continue
                val head = run.first()
                state.tableau.indices.forEach { target ->
                    if (target == index || !tableauAccepts(state, target, head)) return@forEach
                    // Shifting a whole column into an empty one gains nothing and
                    // would let a game shuffle back and forth for ever, so it is
                    // not offered as a move at all.
                    val wholeColumn = pile.faceDown.isEmpty() && count == pile.faceUp.size
                    if (wholeColumn && state.tableau[target].isEmpty) return@forEach
                    moves += MoveCards(Spot.tableau(index), Spot.tableau(target), count)
                }
            }
        }

        state.foundations.forEachIndexed { index, foundation ->
            foundation.lastOrNull()?.let { card ->
                state.tableau.indices.forEach { pile ->
                    if (tableauAccepts(state, pile, card)) {
                        moves += MoveCards(Spot.foundation(index), Spot.tableau(pile))
                    }
                }
            }
        }
        return moves
    }

    /** Whether column [index] will take [card] on top of it. */
    fun tableauAccepts(state: KlondikeState, index: Int, card: Card): Boolean {
        val pile = state.tableau[index]
        val top = pile.top ?: return acceptsInSpace(card, state.options.kingsOnlyInSpaces)
        return buildsDown(card, top)
    }

    override fun applyMove(state: KlondikeState, seat: Int, move: KlondikeMove): KlondikeState {
        require(seat == SOLO_SEAT) { "Klondike has one seat" }
        require(legalMoves(state, seat).contains(move)) { "$move is not legal here" }
        val next = when (move) {
            Draw -> draw(state)
            Redeal -> redeal(state)
            is MoveCards -> moveCards(state, move)
        }
        val won = next.isWon
        return next.copy(
            moves = state.moves + 1,
            log = if (won) next.log + "Out, in ${next.moves + 1} moves." else next.log,
        )
    }

    private fun draw(state: KlondikeState): KlondikeState {
        val count = minOf(state.options.drawCount, state.stock.size)
        val taken = state.stock.takeLast(count)
        return state.copy(
            stock = state.stock.dropLast(count),
            // Turning a packet over reverses it, so the card that was deepest of
            // the three is the one left showing.
            waste = state.waste + taken.reversed(),
        )
    }

    private fun redeal(state: KlondikeState): KlondikeState = state.copy(
        // The waste is turned face down as one, so its top card ends up at the
        // bottom of the new stock.
        stock = state.waste.reversed(),
        waste = emptyList(),
        redealsUsed = state.redealsUsed + 1,
        log = state.log + "Waste turned back over.",
    )

    private fun moveCards(state: KlondikeState, move: MoveCards): KlondikeState {
        val cards = cardsAt(state, move.from, move.count)
        val without = removeFrom(state, move.from, move.count)
        return addTo(without, move.to, cards)
    }

    private fun cardsAt(state: KlondikeState, from: Spot, count: Int): List<Card> = when (from.kind) {
        SpotKind.WASTE -> state.waste.takeLast(count)
        SpotKind.TABLEAU -> state.tableau[from.index].faceUp.takeLast(count)
        SpotKind.FOUNDATION -> state.foundations[from.index].takeLast(count)
        SpotKind.STOCK -> throw IllegalArgumentException("Cards are not taken from the stock by hand")
    }

    private fun removeFrom(state: KlondikeState, from: Spot, count: Int): KlondikeState =
        when (from.kind) {
            SpotKind.WASTE -> state.copy(waste = state.waste.dropLast(count))

            SpotKind.TABLEAU -> {
                val pile = state.tableau[from.index]
                val left = pile.faceUp.dropLast(count)
                // Emptying the turned-up run exposes whatever was under it, and
                // turning that card is the point of most of the game.
                val flipped = if (left.isEmpty() && pile.faceDown.isNotEmpty()) {
                    TableauPile(
                        faceDown = pile.faceDown.dropLast(1),
                        faceUp = listOf(pile.faceDown.last()),
                    )
                } else {
                    pile.copy(faceUp = left)
                }
                state.copy(tableau = state.tableau.replacing(from.index, flipped))
            }

            SpotKind.FOUNDATION -> state.copy(
                foundations = state.foundations.replacing(
                    from.index,
                    state.foundations[from.index].dropLast(count),
                ),
            )

            SpotKind.STOCK -> throw IllegalArgumentException("Nothing is taken off the stock by hand")
        }

    private fun addTo(state: KlondikeState, to: Spot, cards: List<Card>): KlondikeState =
        when (to.kind) {
            SpotKind.TABLEAU -> {
                val pile = state.tableau[to.index]
                state.copy(
                    tableau = state.tableau.replacing(
                        to.index,
                        pile.copy(faceUp = pile.faceUp + cards),
                    ),
                )
            }

            SpotKind.FOUNDATION -> state.copy(
                foundations = state.foundations.replacing(
                    to.index,
                    state.foundations[to.index] + cards,
                ),
            )

            SpotKind.WASTE, SpotKind.STOCK ->
                throw IllegalArgumentException("Nothing is put back on the ${to.kind}")
        }

    private fun <T> List<T>.replacing(index: Int, value: T): List<T> =
        toMutableList().also { it[index] = value }

    /**
     * Won, or with nothing left to try.
     *
     * A game can dry up entirely: no stock, no redeal left, and not a card that
     * will go anywhere. That is a finish as much as winning is, and the screen
     * needs to be able to say so rather than leaving the player hunting.
     */
    override fun isFinished(state: KlondikeState): Boolean =
        state.isWon || legalMoves(state, SOLO_SEAT).isEmpty()

    override fun summary(state: KlondikeState): String = when {
        state.isWon -> "Out in ${state.moves} moves"
        isFinished(state) -> "Blocked, ${state.foundations.sumOf { it.size }} cards home"
        else -> "${state.foundations.sumOf { it.size }} of 52 home"
    }

    /** One seat, and it is the player's, so there is nothing to strip. */
    override fun viewFor(state: KlondikeState, seat: Int): KlondikeState = state

    override fun encodeState(state: KlondikeState): String = json.encodeToString(state)
    override fun decodeState(json: String): KlondikeState = this.json.decodeFromString(json)
    override fun encodeMove(move: KlondikeMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): KlondikeMove = this.json.decodeFromString(json)

    /** Deals the same pack again, so a lost game can be replayed from the start. */
    fun restart(state: KlondikeState): KlondikeState = deal(state.options, state.seed)
}
