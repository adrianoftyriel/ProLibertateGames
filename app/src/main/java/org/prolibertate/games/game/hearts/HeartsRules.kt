package org.prolibertate.games.game.hearts

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
 * Hearts as a pure state machine. See RULES-hearts.md.
 */
object HeartsRules : GameRules<HeartsState, HeartsMove> {

    override val gameId: String = GameCatalog.HEARTS

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): HeartsState {
        val options = json.decodeFromString<HeartsOptions>(config.optionsJson)
        require(config.seats.size == options.playerCount) {
            "Seat count ${config.seats.size} does not match ${options.playerCount} players"
        }
        return deal(options, config.seed, 0, List(options.playerCount) { 0 }, listOf("Round 1."))
    }

    private fun deal(
        options: HeartsOptions,
        seed: Long,
        round: Int,
        scores: List<Int>,
        log: List<String>,
    ): HeartsState {
        // A fresh generator per round, keyed on the round, so the whole game is
        // reproducible from the one seed the host shipped with the table.
        val random = Random(seed + round)
        val deck = Decks.standard52().shuffledWith(random)
        val perHand = deck.size / options.playerCount
        val hands = (0 until options.playerCount).map { seat ->
            deck.subList(seat * perHand, (seat + 1) * perHand).sortedWith(handOrder)
        }
        val direction = PassDirection.forRound(round)
        val passing = direction != PassDirection.HOLD
        // A hold round has nothing to swap, so it opens straight onto the lead.
        val opener = if (passing) 0 else holderOfTwoOfClubs(hands)
        return HeartsState(
            options = options,
            seed = seed,
            round = round,
            phase = if (passing) HeartsPhase.PASSING else HeartsPhase.PLAYING,
            hands = hands,
            handCounts = hands.map { it.size },
            passSelections = List(options.playerCount) { emptyList() },
            turn = opener,
            leader = opener,
            trickNumber = 0,
            trick = emptyList(),
            completedTrick = emptyList(),
            taken = List(options.playerCount) { emptyList() },
            heartsBroken = false,
            scores = scores,
            roundScores = List(options.playerCount) { 0 },
            log = log + if (passing) "Passing ${direction.label}." else "No passing this round.",
        )
    }

    /** Sorted by suit then rank, which is how a hand is held rather than dealt. */
    private val handOrder = compareBy<Card>({ it.suit.ordinal }, { it.rank.order })

    private fun holderOfTwoOfClubs(hands: List<List<Card>>): Int =
        hands.indexOfFirst { it.contains(TWO_OF_CLUBS) }

    override fun currentSeat(state: HeartsState): Int? = when (state.phase) {
        // Passing is simultaneous at the table, but the engine hands out one
        // seat at a time. The lowest seat that has not chosen goes next, and
        // the swap happens once the last one has.
        HeartsPhase.PASSING -> state.passSelections.indexOfFirst { it.isEmpty() }.takeIf { it >= 0 }
        HeartsPhase.PLAYING -> state.turn
        HeartsPhase.ROUND_OVER, HeartsPhase.GAME_OVER -> null
    }

    override fun legalMoves(state: HeartsState, seat: Int): List<HeartsMove> =
        when (state.phase) {
            HeartsPhase.PASSING ->
                if (state.passSelections[seat].isNotEmpty()) {
                    emptyList()
                } else {
                    combinations(state.hands[seat], PASS_SIZE).map { PassCards(it) }
                }

            HeartsPhase.PLAYING ->
                if (state.turn != seat) emptyList() else playableCards(state, seat).map { PlayCard(it) }

            HeartsPhase.ROUND_OVER, HeartsPhase.GAME_OVER -> emptyList()
        }

    /**
     * The cards [seat] may legally play into the trick as it stands.
     *
     * Four restrictions, in the order they bite: the two of clubs opens the
     * round, a seat that can follow the led suit must, no point card may be
     * discarded on the first trick, and hearts cannot be led until one has been
     * played. Each of the last three lifts when a seat holds nothing else.
     */
    fun playableCards(state: HeartsState, seat: Int): List<Card> {
        val hand = state.hands[seat]
        if (hand.isEmpty()) return emptyList()
        val firstTrick = state.trickNumber == 0

        if (state.trick.isEmpty()) {
            if (firstTrick) return listOf(TWO_OF_CLUBS).filter { hand.contains(it) }
            val offHearts = hand.filter { it.suit != Suit.HEARTS }
            // Hearts may only be led once broken — unless that is all there is
            // left to lead, in which case the restriction cannot be honoured.
            return if (state.heartsBroken || offHearts.isEmpty()) hand else offHearts
        }

        val led = state.trick.first().card.suit
        val following = hand.filter { it.suit == led }
        if (following.isNotEmpty()) return following

        // Void in the led suit, so anything goes — except that the first trick
        // takes no points while the seat has a card that costs none.
        if (firstTrick) {
            val harmless = hand.filter { pointsOf(it) == 0 }
            if (harmless.isNotEmpty()) return harmless
        }
        return hand
    }

    override fun applyMove(state: HeartsState, seat: Int, move: HeartsMove): HeartsState =
        when (move) {
            is PassCards -> applyPass(state, seat, move)
            is PlayCard -> applyPlay(state, seat, move)
        }

    private fun applyPass(state: HeartsState, seat: Int, move: PassCards): HeartsState {
        require(state.phase == HeartsPhase.PASSING) { "Not passing" }
        require(state.passSelections[seat].isEmpty()) { "Seat $seat has already passed" }
        require(move.cards.size == PASS_SIZE) { "A pass is $PASS_SIZE cards" }
        require(move.cards.distinct().size == PASS_SIZE) { "Cannot pass the same card twice" }
        val hand = state.hands[seat]
        require(move.cards.all { hand.contains(it) }) { "Cannot pass a card not held" }

        val selections = state.passSelections.toMutableList()
        selections[seat] = move.cards
        if (selections.any { it.isEmpty() }) {
            return state.copy(passSelections = selections)
        }
        return completePass(state.copy(passSelections = selections))
    }

    /** Everyone has chosen, so the three-card bundles change hands at once. */
    private fun completePass(state: HeartsState): HeartsState {
        val direction = state.passDirection
        val count = state.options.playerCount
        val incoming = MutableList(count) { emptyList<Card>() }
        for (from in 0 until count) {
            incoming[passTarget(from, count, direction)] = state.passSelections[from]
        }
        val hands = state.hands.mapIndexed { seat, hand ->
            (hand - state.passSelections[seat].toSet() + incoming[seat]).sortedWith(handOrder)
        }
        val opener = holderOfTwoOfClubs(hands)
        return state.copy(
            phase = HeartsPhase.PLAYING,
            hands = hands,
            handCounts = hands.map { it.size },
            turn = opener,
            leader = opener,
            log = state.log + "Cards passed ${direction.label}.",
        )
    }

    private fun applyPlay(state: HeartsState, seat: Int, move: PlayCard): HeartsState {
        require(state.phase == HeartsPhase.PLAYING) { "Not playing" }
        require(state.turn == seat) { "Seat $seat is not on the clock" }
        require(playableCards(state, seat).contains(move.card)) { "${move.card} is not playable" }

        val hands = state.hands.toMutableList()
        hands[seat] = hands[seat] - move.card
        val trick = state.trick + PlayedCard(seat, move.card)
        val broken = state.heartsBroken || move.card.suit == Suit.HEARTS

        if (trick.size < state.options.playerCount) {
            return state.copy(
                hands = hands,
                handCounts = hands.map { it.size },
                trick = trick,
                completedTrick = emptyList(),
                turn = (seat + 1) % state.options.playerCount,
                heartsBroken = broken,
            )
        }

        val led = trick.first().card.suit
        val winner = trick.maxBy { trickStrength(it.card, led) }.seat
        val taken = state.taken.toMutableList()
        taken[winner] = taken[winner] + trick.map { it.card }
        val swept = state.copy(
            hands = hands,
            handCounts = hands.map { it.size },
            trick = emptyList(),
            completedTrick = trick,
            taken = taken,
            heartsBroken = broken,
            turn = winner,
            leader = winner,
            trickNumber = state.trickNumber + 1,
        )
        return if (hands.all { it.isEmpty() }) finishRound(swept) else swept
    }

    private fun finishRound(state: HeartsState): HeartsState {
        val gained = scoreRound(state.taken, state.options.allowShootTheMoon)
        val scores = state.scores.mapIndexed { seat, score -> score + gained[seat] }
        val moon = state.taken.indexOfFirst { pointsIn(it) == ALL_POINTS }
        val over = scores.any { it >= state.options.targetScore }
        val notes = buildList {
            if (moon >= 0 && state.options.allowShootTheMoon) add("Seat $moon shot the moon.")
            add("Round ${state.round + 1} scored.")
            if (over) add("Game over — lowest score wins.")
        }
        return state.copy(
            phase = if (over) HeartsPhase.GAME_OVER else HeartsPhase.ROUND_OVER,
            scores = scores,
            roundScores = gained,
            log = state.log + notes,
        )
    }

    /** Advances from [HeartsPhase.ROUND_OVER] into the next deal. */
    fun startNextRound(state: HeartsState): HeartsState {
        check(state.phase == HeartsPhase.ROUND_OVER) { "Round is not over" }
        val next = state.round + 1
        return deal(state.options, state.seed, next, state.scores, state.log + "Round ${next + 1}.")
    }

    override fun isFinished(state: HeartsState): Boolean = state.phase == HeartsPhase.GAME_OVER

    override fun summary(state: HeartsState): String = when (state.phase) {
        HeartsPhase.PASSING -> "Passing ${state.passDirection.label}"
        HeartsPhase.PLAYING -> "Trick ${state.trickNumber + 1} of ${state.tricksPerRound}"
        HeartsPhase.ROUND_OVER -> "Round ${state.round + 1}: " + state.roundScores.joinToString(", ")
        HeartsPhase.GAME_OVER -> {
            val best = state.scores.min()
            "Winner: seat ${state.scores.indexOf(best)} on $best"
        }
    }

    /**
     * Other hands go, and so do the cards they have chosen to pass — a pass is
     * secret until it lands, and a client that could read the selections would
     * know exactly what is coming.
     */
    override fun viewFor(state: HeartsState, seat: Int): HeartsState = state.copy(
        hands = state.hands.mapIndexed { index, hand -> if (index == seat) hand else emptyList() },
        handCounts = state.handCounts,
        passSelections = state.passSelections.mapIndexed { index, cards ->
            if (index == seat) cards else emptyList()
        },
    )

    override fun encodeState(state: HeartsState): String = json.encodeToString(state)
    override fun decodeState(json: String): HeartsState = this.json.decodeFromString(json)
    override fun encodeMove(move: HeartsMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): HeartsMove = this.json.decodeFromString(json)

    /** Every distinct [size]-card selection from [cards], order ignored. */
    private fun combinations(cards: List<Card>, size: Int): List<List<Card>> {
        if (size == 0) return listOf(emptyList())
        if (cards.size < size) return emptyList()
        val result = mutableListOf<List<Card>>()
        fun walk(start: Int, chosen: List<Card>) {
            if (chosen.size == size) {
                result.add(chosen)
                return
            }
            // Stop early once too few cards remain to finish the selection.
            for (i in start..cards.size - (size - chosen.size)) {
                walk(i + 1, chosen + cards[i])
            }
        }
        walk(0, emptyList())
        return result
    }
}
