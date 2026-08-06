package org.prolibertate.games.game.crazyeights

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Decks
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.cards.shuffledWith
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

/**
 * Crazy 8s as a pure state machine. See RULES-crazy8s.md.
 */
object CrazyEightsRules : GameRules<CrazyEightsState, CrazyEightsMove> {

    override val gameId: String = GameCatalog.CRAZY_EIGHTS

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): CrazyEightsState {
        val options = json.decodeFromString<CrazyEightsOptions>(config.optionsJson)
        require(config.seats.size == options.playerCount) {
            "Seat count ${config.seats.size} does not match ${options.playerCount} players"
        }
        return deal(options, config.seed, 0, List(options.playerCount) { 0 }, listOf("Round 1."))
    }

    private fun deal(
        options: CrazyEightsOptions,
        seed: Long,
        roundNumber: Int,
        scores: List<Int>,
        log: List<String>,
    ): CrazyEightsState {
        val random = Random(seed + roundNumber * 7919L)
        var deck = Decks.standard52().shuffledWith(random)

        val hands = mutableListOf<List<Card>>()
        repeat(options.playerCount) {
            hands += deck.take(options.handSize())
            deck = deck.drop(options.handSize())
        }

        // An eight on top at the start would leave nobody having named a suit,
        // so the first upcard is taken from further down if need be.
        val firstIndex = deck.indexOfFirst { !isWild(it) }.takeIf { it >= 0 } ?: 0
        val upCard = deck[firstIndex]
        val stock = deck.filterIndexed { index, _ -> index != firstIndex }

        return CrazyEightsState(
            options = options,
            seed = seed,
            roundNumber = roundNumber,
            hands = hands,
            handCounts = hands.map { it.size },
            stock = stock,
            discard = listOf(upCard),
            suitInForce = upCard.suit,
            turn = roundNumber % options.playerCount,
            drawnThisTurn = 0,
            consecutivePasses = 0,
            scores = scores,
            roundWinner = null,
            phase = CrazyEightsPhase.PLAYING,
            log = log,
        )
    }

    /** Advances from [CrazyEightsPhase.ROUND_OVER] into the next deal. */
    fun nextRound(state: CrazyEightsState): CrazyEightsState {
        check(state.phase == CrazyEightsPhase.ROUND_OVER) { "Round is not over" }
        return deal(
            options = state.options,
            seed = state.seed,
            roundNumber = state.roundNumber + 1,
            scores = state.scores,
            log = state.log + "Round ${state.roundNumber + 2}.",
        )
    }

    override fun currentSeat(state: CrazyEightsState): Int? =
        if (state.phase == CrazyEightsPhase.PLAYING) state.turn else null

    override fun legalMoves(state: CrazyEightsState, seat: Int): List<CrazyEightsMove> {
        if (currentSeat(state) != seat) return emptyList()
        val hand = state.hands[seat]
        val playable = hand.filter { canPlay(it, state.topCard, state.suitInForce) }

        if (playable.isNotEmpty()) {
            return playable.flatMap { card ->
                if (isWild(card)) {
                    // Playing an eight is really four different moves: the card
                    // plus the suit it turns the game to.
                    Suit.entries.map { PlayCard(card, it) }
                } else {
                    listOf(PlayCard(card))
                }
            }
        }

        // Nothing to play. There is something to draw as long as the stock has
        // a card or the discards can be recycled into one — the pile in play
        // does not count, since it stays put.
        val somethingToDraw = state.stock.isNotEmpty() || state.discard.size > 1
        val mayDraw = somethingToDraw &&
            (state.options.drawUntilPlayable || state.drawnThisTurn == 0)
        return if (mayDraw) listOf(DrawCard) else listOf(PassTurn)
    }

    override fun applyMove(
        state: CrazyEightsState,
        seat: Int,
        move: CrazyEightsMove,
    ): CrazyEightsState {
        require(currentSeat(state) == seat) { "Not seat $seat's turn" }
        require(move in legalMoves(state, seat)) { "Illegal move $move for seat $seat" }
        return when (move) {
            is PlayCard -> applyPlay(state, seat, move)
            is DrawCard -> applyDraw(state, seat)
            is PassTurn -> applyPass(state, seat)
        }
    }

    private fun applyPlay(
        state: CrazyEightsState,
        seat: Int,
        move: PlayCard,
    ): CrazyEightsState {
        val hands = state.hands.toMutableList()
        hands[seat] = hands[seat] - move.card

        val suit = if (isWild(move.card)) {
            requireNotNull(move.nominatedSuit) { "An eight must name a suit" }
        } else {
            move.card.suit
        }

        val played = state.copy(
            hands = hands,
            handCounts = hands.map { it.size },
            discard = state.discard + move.card,
            suitInForce = suit,
            drawnThisTurn = 0,
            consecutivePasses = 0,
            log = state.log + buildString {
                append("Seat $seat plays ${move.card.label}")
                if (isWild(move.card)) append(" and calls ${suit.symbol}")
                append(".")
            },
        )

        if (hands[seat].isEmpty()) return scoreRound(played, seat)
        return advance(played, seat)
    }

    private fun applyDraw(state: CrazyEightsState, seat: Int): CrazyEightsState {
        var stock = state.stock
        var discard = state.discard

        if (stock.isEmpty()) {
            // Recycle everything but the card in play.
            if (discard.size <= 1) return advance(state, seat)
            val top = discard.last()
            stock = discard.dropLast(1).shuffledWith(Random(state.seed + state.log.size))
            discard = listOf(top)
        }

        val hands = state.hands.toMutableList()
        hands[seat] = hands[seat] + stock.first()

        return state.copy(
            hands = hands,
            handCounts = hands.map { it.size },
            stock = stock.drop(1),
            discard = discard,
            drawnThisTurn = state.drawnThisTurn + 1,
            consecutivePasses = 0,
            log = state.log + "Seat $seat draws.",
        )
    }

    /**
     * Everyone passing in succession means nobody can move and the deck is
     * spent. The round is blocked: it ends there and the smallest hand takes it.
     */
    private fun applyPass(state: CrazyEightsState, seat: Int): CrazyEightsState {
        val passes = state.consecutivePasses + 1
        val passed = state.copy(
            consecutivePasses = passes,
            log = state.log + "Seat $seat passes.",
        )
        if (passes >= state.options.playerCount) {
            val smallest = passed.hands.indices
                .minByOrNull { passed.hands[it].sumOf { card -> crazyEightsPenalty(card) } }
            return scoreRound(
                passed.copy(log = passed.log + "Nobody can move — the round is blocked."),
                smallest,
            )
        }
        return advance(passed, seat)
    }

    private fun advance(state: CrazyEightsState, seat: Int): CrazyEightsState = state.copy(
        turn = (seat + 1) % state.options.playerCount,
        drawnThisTurn = 0,
    )

    /**
     * The player who went out scores nothing; everyone else is charged for what
     * they are still holding.
     */
    private fun scoreRound(state: CrazyEightsState, winner: Int?): CrazyEightsState {
        val scores = state.scores.mapIndexed { seat, total ->
            total + state.hands[seat].sumOf { crazyEightsPenalty(it) }
        }
        val lastRound = state.roundNumber + 1 >= state.options.roundsToPlay
        return state.copy(
            scores = scores,
            roundWinner = winner,
            phase = if (lastRound) CrazyEightsPhase.GAME_OVER else CrazyEightsPhase.ROUND_OVER,
            log = state.log + if (winner != null) "Seat $winner goes out." else "Round ends.",
        )
    }

    override fun isFinished(state: CrazyEightsState): Boolean =
        state.phase == CrazyEightsPhase.GAME_OVER

    override fun summary(state: CrazyEightsState): String = when (state.phase) {
        CrazyEightsPhase.GAME_OVER -> {
            val best = state.scores.indices.minByOrNull { state.scores[it] } ?: 0
            "Seat $best wins on ${state.scores[best]}"
        }

        else -> state.scores.mapIndexed { seat, s -> "S$seat:$s" }.joinToString(" ")
    }

    override fun viewFor(state: CrazyEightsState, seat: Int): CrazyEightsState = state.copy(
        hands = state.hands.mapIndexed { index, hand -> if (index == seat) hand else emptyList() },
        handCounts = state.handCounts,
        stock = emptyList(),
    )

    override fun encodeState(state: CrazyEightsState): String = json.encodeToString(state)
    override fun decodeState(json: String): CrazyEightsState = this.json.decodeFromString(json)
    override fun encodeMove(move: CrazyEightsMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): CrazyEightsMove = this.json.decodeFromString(json)
}
