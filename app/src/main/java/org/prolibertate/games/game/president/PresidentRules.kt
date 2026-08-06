package org.prolibertate.games.game.president

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
 * President as a pure state machine. See RULES-president.md.
 */
object PresidentRules : GameRules<PresidentState, PresidentMove> {

    override val gameId: String = GameCatalog.PRESIDENT

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): PresidentState {
        val options = json.decodeFromString<PresidentOptions>(config.optionsJson)
        require(config.seats.size == options.playerCount) {
            "Seat count ${config.seats.size} does not match ${options.playerCount} players"
        }
        return deal(
            options = options,
            seed = config.seed,
            roundNumber = 0,
            previousFinish = List(options.playerCount) { -1 },
            scores = List(options.playerCount) { 0 },
            log = listOf("Round 1."),
        )
    }

    private fun deal(
        options: PresidentOptions,
        seed: Long,
        roundNumber: Int,
        previousFinish: List<Int>,
        scores: List<Int>,
        log: List<String>,
    ): PresidentState {
        val random = Random(seed + roundNumber * 7919L)
        val deck = Decks.standard52().shuffledWith(random)

        // Deal round-robin so hands differ by at most one card.
        val hands = MutableList(options.playerCount) { mutableListOf<Card>() }
        deck.forEachIndexed { index, card -> hands[index % options.playerCount] += card }
        val sorted = hands.map { hand -> hand.sortedBy { presidentRank(it) } }

        val exchanged =
            if (roundNumber > 0 && options.cardExchange) {
                applyExchange(sorted, previousFinish, options)
            } else {
                sorted
            }

        // The lowest card in the game leads the first round; afterwards the
        // Scum leads, which is the usual way of keeping the loser in the game.
        val leader = if (roundNumber == 0) {
            exchanged.indices.minByOrNull { seat ->
                exchanged[seat].minOfOrNull { presidentRank(it) } ?: Int.MAX_VALUE
            } ?: 0
        } else {
            previousFinish.indexOf(options.playerCount - 1).takeIf { it >= 0 } ?: 0
        }

        return PresidentState(
            options = options,
            seed = seed,
            roundNumber = roundNumber,
            hands = exchanged,
            handCounts = exchanged.map { it.size },
            pile = emptyList(),
            setSize = 0,
            setRank = 0,
            turn = leader,
            passed = List(options.playerCount) { false },
            finishedOrder = emptyList(),
            previousFinish = previousFinish,
            scores = scores,
            phase = PresidentPhase.PLAYING,
            log = log,
        )
    }

    /**
     * Scum hands their best cards up, the President hands their worst back.
     *
     * The President choosing which to return is the fuller rule; here it is
     * automatic so the exchange does not need a phase of its own. See
     * RULES-president.md.
     */
    private fun applyExchange(
        hands: List<List<Card>>,
        previousFinish: List<Int>,
        options: PresidentOptions,
    ): List<List<Card>> {
        val president = previousFinish.indexOf(0)
        val scum = previousFinish.indexOf(options.playerCount - 1)
        if (president < 0 || scum < 0 || president == scum) return hands

        val working = hands.map { it.toMutableList() }
        val count = 2

        val fromScum = working[scum].sortedByDescending { presidentRank(it) }.take(count)
        val fromPresident = working[president].sortedBy { presidentRank(it) }.take(count)
        if (fromScum.size < count || fromPresident.size < count) return hands

        fromScum.forEach { working[scum].remove(it) }
        fromPresident.forEach { working[president].remove(it) }
        working[president] += fromScum
        working[scum] += fromPresident

        return working.map { hand -> hand.sortedBy { presidentRank(it) } }
    }

    /** Advances from [PresidentPhase.ROUND_OVER] into the next deal. */
    fun nextRound(state: PresidentState): PresidentState {
        check(state.phase == PresidentPhase.ROUND_OVER) { "Round is not over" }
        val finish = MutableList(state.options.playerCount) { -1 }
        state.finishedOrder.forEachIndexed { position, seat -> finish[seat] = position }
        return deal(
            options = state.options,
            seed = state.seed,
            roundNumber = state.roundNumber + 1,
            previousFinish = finish,
            scores = state.scores,
            log = state.log + "Round ${state.roundNumber + 2}.",
        )
    }

    override fun currentSeat(state: PresidentState): Int? =
        if (state.phase == PresidentPhase.PLAYING) state.turn else null

    // -----------------------------------------------------------------------
    // Legal moves
    // -----------------------------------------------------------------------

    override fun legalMoves(state: PresidentState, seat: Int): List<PresidentMove> {
        if (currentSeat(state) != seat) return emptyList()
        val hand = state.hands[seat]
        if (hand.isEmpty()) return emptyList()

        val byRank = hand.groupBy { presidentRank(it) }.toSortedMap()
        val moves = mutableListOf<PresidentMove>()

        if (state.setSize == 0) {
            // Leading: any number of one rank.
            for ((_, cards) in byRank) {
                for (size in 1..cards.size) moves += PlayCards(cards.take(size))
            }
            return moves
        }

        for ((rank, cards) in byRank) {
            if (cards.size < state.setSize) continue
            val set = cards.take(state.setSize)
            val beatsByRank = rank > state.setRank
            val clears = clearsPile(state.options, set, cards.size)
            if (beatsByRank || clears) moves += PlayCards(set)
        }
        moves += PassTurn
        return moves
    }

    /** Sets that beat the pile outright rather than on rank. */
    private fun clearsPile(
        options: PresidentOptions,
        set: List<Card>,
        availableOfRank: Int,
    ): Boolean {
        if (options.twosClear && set.all { isTwo(it) }) return true
        if (options.fourOfAKindBomb && set.size == 4 && availableOfRank >= 4) return true
        return false
    }

    // -----------------------------------------------------------------------
    // Move application
    // -----------------------------------------------------------------------

    override fun applyMove(
        state: PresidentState,
        seat: Int,
        move: PresidentMove,
    ): PresidentState {
        require(currentSeat(state) == seat) { "Not seat $seat's turn" }
        require(move in legalMoves(state, seat)) { "Illegal move $move for seat $seat" }
        return when (move) {
            is PassTurn -> applyPass(state, seat)
            is PlayCards -> applyPlay(state, seat, move.cards)
        }
    }

    private fun applyPass(state: PresidentState, seat: Int): PresidentState {
        val passed = state.passed.toMutableList()
        passed[seat] = true
        val afterPass = state.copy(passed = passed, log = state.log + "Seat $seat passes.")
        return advance(afterPass, from = seat)
    }

    private fun applyPlay(
        state: PresidentState,
        seat: Int,
        cards: List<Card>,
    ): PresidentState {
        val hand = state.hands[seat].toMutableList()
        cards.forEach { hand.remove(it) }
        val hands = state.hands.toMutableList()
        hands[seat] = hand

        val rank = presidentRank(cards.first())
        val ranksOfHand = state.hands[seat].count { presidentRank(it) == rank }
        val clears = clearsPile(state.options, cards, ranksOfHand)

        var next = state.copy(
            hands = hands,
            handCounts = hands.map { it.size },
            pile = state.pile + cards,
            setSize = cards.size,
            setRank = rank,
            log = state.log + "Seat $seat plays ${cards.joinToString(" ") { it.label }}.",
        )

        // Going out is recorded before anything else, so a player who finishes
        // on a pile-clearing set does not also get to lead the next one.
        if (hand.isEmpty()) {
            val order = next.finishedOrder + seat
            next = next.copy(
                finishedOrder = order,
                log = next.log + "Seat $seat is out (${titleFor(order.size - 1, state.options.playerCount)}).",
            )
            if (roundComplete(next)) return scoreRound(next)
        }

        if (clears) {
            // Pile cleared: the same player leads again unless they just went out.
            val leader = if (next.isOut(seat)) nextActive(next, seat) else seat
            return next.copy(
                pile = emptyList(),
                setSize = 0,
                setRank = 0,
                passed = List(state.options.playerCount) { false },
                turn = leader,
                log = next.log + "Pile cleared.",
            )
        }

        return advance(next, from = seat)
    }

    /**
     * Moves to the next seat, clearing the pile when everyone else has passed.
     */
    private fun advance(state: PresidentState, from: Int): PresidentState {
        val contenders = state.stillInPile()
        // Everyone who could still beat the pile has passed: it is taken down
        // and whoever played last leads again.
        if (state.setSize > 0 && contenders.size <= 1) {
            val leader = contenders.firstOrNull()
                ?: nextActive(state, from)
            return state.copy(
                pile = emptyList(),
                setSize = 0,
                setRank = 0,
                passed = List(state.options.playerCount) { false },
                turn = leader,
                log = state.log + "Pile cleared.",
            )
        }
        return state.copy(turn = nextActive(state, from))
    }

    /** The next seat still holding cards and not passed out of this pile. */
    private fun nextActive(state: PresidentState, from: Int): Int {
        val count = state.options.playerCount
        for (step in 1..count) {
            val seat = (from + step) % count
            if (!state.isOut(seat) && !state.passed[seat]) return seat
        }
        // Nobody is left in the pile; fall back to anyone still holding cards.
        for (step in 1..count) {
            val seat = (from + step) % count
            if (!state.isOut(seat)) return seat
        }
        return from
    }

    private fun roundComplete(state: PresidentState): Boolean =
        state.finishedOrder.size >= state.options.playerCount - 1

    private fun scoreRound(state: PresidentState): PresidentState {
        // The straggler takes last place.
        val remaining = (0 until state.options.playerCount).filter { !state.isOut(it) }
        val order = state.finishedOrder + remaining

        val scores = state.scores.toMutableList()
        order.forEachIndexed { position, seat ->
            scores[seat] = scores[seat] + (state.options.playerCount - 1 - position)
        }

        val finalRound = state.roundNumber + 1 >= state.options.roundsToPlay
        return state.copy(
            finishedOrder = order,
            scores = scores,
            phase = if (finalRound) PresidentPhase.GAME_OVER else PresidentPhase.ROUND_OVER,
            log = state.log + "Round over.",
        )
    }

    override fun isFinished(state: PresidentState): Boolean =
        state.phase == PresidentPhase.GAME_OVER

    override fun summary(state: PresidentState): String = when (state.phase) {
        PresidentPhase.GAME_OVER -> {
            val best = state.scores.indices.maxByOrNull { state.scores[it] } ?: 0
            "Seat $best wins with ${state.scores[best]}"
        }

        else -> state.scores.mapIndexed { seat, s -> "S$seat:$s" }.joinToString(" ")
    }

    override fun viewFor(state: PresidentState, seat: Int): PresidentState = state.copy(
        hands = state.hands.mapIndexed { index, hand -> if (index == seat) hand else emptyList() },
        handCounts = state.handCounts,
    )

    override fun encodeState(state: PresidentState): String = json.encodeToString(state)
    override fun decodeState(json: String): PresidentState = this.json.decodeFromString(json)
    override fun encodeMove(move: PresidentMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): PresidentMove = this.json.decodeFromString(json)
}
