package org.prolibertate.games.game.euchre

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
 * Euchre as a pure state machine. See RULES-euchre.md for the ruleset and the
 * variants exposed through [EuchreOptions].
 */
object EuchreRules : GameRules<EuchreState, EuchreMove> {

    override val gameId: String = GameCatalog.EUCHRE

    private const val CARDS_PER_HAND = 5

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    override fun initialState(config: TableConfig): EuchreState {
        require(config.seats.size == 4) { "Euchre is a four-handed game" }
        val options = json.decodeFromString<EuchreOptions>(config.optionsJson)
        return deal(
            options = options,
            seed = config.seed,
            handNumber = 0,
            dealer = 0,
            scores = listOf(0, 0),
            log = listOf("Hand 1 — dealer is seat 0."),
        )
    }

    /** Deals a fresh hand and opens the first round of bidding. */
    private fun deal(
        options: EuchreOptions,
        seed: Long,
        handNumber: Int,
        dealer: Int,
        scores: List<Int>,
        log: List<String>,
    ): EuchreState {
        // Derived per hand so a given (seed, handNumber) always reproduces the
        // same deal — handy for tests and for replaying a networked game.
        val random = Random(seed + handNumber * 7919L)
        val deck = Decks.euchre(options.deckSize).shuffledWith(random)
        val hands = (0 until 4).map { seat ->
            deck.subList(seat * CARDS_PER_HAND, (seat + 1) * CARDS_PER_HAND).toList()
        }
        val upCard = deck[4 * CARDS_PER_HAND]
        return EuchreState(
            options = options,
            seed = seed,
            handNumber = handNumber,
            dealer = dealer,
            phase = EuchrePhase.BID_ROUND_1,
            hands = hands,
            handCounts = hands.map { it.size },
            upCard = upCard,
            turnedDown = null,
            trump = null,
            maker = null,
            aloneSeat = null,
            turn = (dealer + 1) % 4,
            passes = 0,
            trick = emptyList(),
            leader = (dealer + 1) % 4,
            tricksWon = listOf(0, 0, 0, 0),
            scores = scores,
            lastTrickWinner = null,
            log = log,
        )
    }

    /**
     * Advances from [EuchrePhase.HAND_OVER] into the next deal. Driven by the
     * session controller once the finished hand has been on screen long enough
     * to read, rather than by a player move.
     */
    fun nextHand(state: EuchreState): EuchreState {
        check(state.phase == EuchrePhase.HAND_OVER) { "Hand is not over" }
        val dealer = (state.dealer + 1) % 4
        return deal(
            options = state.options,
            seed = state.seed,
            handNumber = state.handNumber + 1,
            dealer = dealer,
            scores = state.scores,
            log = state.log + "Hand ${state.handNumber + 2} — dealer is seat $dealer.",
        )
    }

    // -----------------------------------------------------------------------
    // Turn order
    // -----------------------------------------------------------------------

    private fun nextActive(state: EuchreState, from: Int): Int {
        var seat = (from + 1) % 4
        while (seat == state.sittingOut) seat = (seat + 1) % 4
        return seat
    }

    override fun currentSeat(state: EuchreState): Int? = when (state.phase) {
        EuchrePhase.BID_ROUND_1,
        EuchrePhase.BID_ROUND_2,
        EuchrePhase.DEALER_DISCARD,
        EuchrePhase.PLAYING,
        -> state.turn

        EuchrePhase.HAND_OVER, EuchrePhase.GAME_OVER -> null
    }

    // -----------------------------------------------------------------------
    // Legal moves
    // -----------------------------------------------------------------------

    override fun legalMoves(state: EuchreState, seat: Int): List<EuchreMove> {
        if (currentSeat(state) != seat) return emptyList()
        return when (state.phase) {
            EuchrePhase.BID_ROUND_1 -> buildList {
                add(OrderUp(alone = false))
                if (state.options.allowGoingAlone) add(OrderUp(alone = true))
                add(Pass)
            }

            EuchrePhase.BID_ROUND_2 -> buildList {
                val barred = state.turnedDown?.suit
                for (suit in Suit.entries) {
                    if (suit == barred) continue
                    add(CallTrump(suit, alone = false))
                    if (state.options.allowGoingAlone) add(CallTrump(suit, alone = true))
                }
                // Stick the dealer: with three passes already in, the dealer has
                // to name something rather than throw the hand in.
                val dealerIsStuck =
                    state.options.stickTheDealer && seat == state.dealer && state.passes == 3
                if (!dealerIsStuck) add(Pass)
            }

            EuchrePhase.DEALER_DISCARD -> state.hands[state.dealer].map { Discard(it) }

            EuchrePhase.PLAYING -> playableCards(state, seat).map { PlayCard(it) }

            EuchrePhase.HAND_OVER, EuchrePhase.GAME_OVER -> emptyList()
        }
    }

    /** Follow the led suit if you can; otherwise anything goes. */
    fun playableCards(state: EuchreState, seat: Int): List<Card> {
        val hand = state.hands[seat]
        val led = state.trick.firstOrNull() ?: return hand
        val ledSuit = effectiveSuit(led.card, state.trump)
        val following = hand.filter { effectiveSuit(it, state.trump) == ledSuit }
        return following.ifEmpty { hand }
    }

    // -----------------------------------------------------------------------
    // Move application
    // -----------------------------------------------------------------------

    override fun applyMove(state: EuchreState, seat: Int, move: EuchreMove): EuchreState {
        require(currentSeat(state) == seat) { "Not seat $seat's turn" }
        require(move in legalMoves(state, seat)) { "Illegal move $move for seat $seat" }
        return when (move) {
            is Pass -> applyPass(state, seat)
            is OrderUp -> applyOrderUp(state, seat, move.alone)
            is CallTrump -> applyCallTrump(state, seat, move.suit, move.alone)
            is Discard -> applyDiscard(state, move.card)
            is PlayCard -> applyPlay(state, seat, move.card)
        }.normalised()
    }

    private fun EuchreState.normalised(): EuchreState = copy(handCounts = hands.map { it.size })

    private fun applyPass(state: EuchreState, seat: Int): EuchreState {
        val passes = state.passes + 1
        val log = state.log + "Seat $seat passes."
        return when (state.phase) {
            EuchrePhase.BID_ROUND_1 -> if (passes >= 4) {
                state.copy(
                    phase = EuchrePhase.BID_ROUND_2,
                    turnedDown = state.upCard,
                    upCard = null,
                    turn = (state.dealer + 1) % 4,
                    passes = 0,
                    log = log + "${state.upCard?.label} turned down.",
                )
            } else {
                state.copy(turn = (seat + 1) % 4, passes = passes, log = log)
            }

            EuchrePhase.BID_ROUND_2 -> if (passes >= 4) {
                // Nobody wanted it and the dealer wasn't stuck: throw the hand in.
                deal(
                    options = state.options,
                    seed = state.seed,
                    handNumber = state.handNumber + 1,
                    dealer = (state.dealer + 1) % 4,
                    scores = state.scores,
                    log = log + "Hand thrown in; redealing.",
                )
            } else {
                state.copy(turn = (seat + 1) % 4, passes = passes, log = log)
            }

            else -> error("Pass is not legal in ${state.phase}")
        }
    }

    private fun applyOrderUp(state: EuchreState, seat: Int, alone: Boolean): EuchreState {
        val upCard = requireNotNull(state.upCard) { "No turn card to order up" }
        // The dealer takes the turn card into hand and will discard one back.
        val hands = state.hands.toMutableList()
        hands[state.dealer] = hands[state.dealer] + upCard
        return state.copy(
            phase = EuchrePhase.DEALER_DISCARD,
            hands = hands,
            trump = upCard.suit,
            maker = seat,
            aloneSeat = if (alone) seat else null,
            upCard = null,
            turn = state.dealer,
            log = state.log + buildString {
                append("Seat $seat orders up ${upCard.suit.symbol}")
                if (alone) append(" and goes alone")
                append(".")
            },
        )
    }

    private fun applyCallTrump(
        state: EuchreState,
        seat: Int,
        suit: Suit,
        alone: Boolean,
    ): EuchreState {
        val withTrump = state.copy(
            trump = suit,
            maker = seat,
            aloneSeat = if (alone) seat else null,
            log = state.log + buildString {
                append("Seat $seat names ${suit.symbol}")
                if (alone) append(" and goes alone")
                append(".")
            },
        )
        return beginPlay(withTrump)
    }

    private fun applyDiscard(state: EuchreState, card: Card): EuchreState {
        val hands = state.hands.toMutableList()
        hands[state.dealer] = hands[state.dealer] - card
        return beginPlay(state.copy(hands = hands, log = state.log + "Dealer discards."))
    }

    /** Trump is settled: clear any lone hand's partner and lead to the first trick. */
    private fun beginPlay(state: EuchreState): EuchreState {
        val sittingOut = state.sittingOut
        val hands = state.hands.toMutableList()
        if (sittingOut != null) hands[sittingOut] = emptyList()
        val leader = nextActive(state, state.dealer)
        return state.copy(
            phase = EuchrePhase.PLAYING,
            hands = hands,
            leader = leader,
            turn = leader,
            trick = emptyList(),
            completedTrick = emptyList(),
            passes = 0,
        )
    }

    private fun applyPlay(state: EuchreState, seat: Int, card: Card): EuchreState {
        val hands = state.hands.toMutableList()
        hands[seat] = hands[seat] - card
        val trick = state.trick + PlayedCard(seat, card)
        // Playing to a new trick sweeps the previous one off the table.
        val played = state.copy(hands = hands, trick = trick, completedTrick = emptyList())

        if (trick.size < state.activeSeatCount) {
            return played.copy(turn = nextActive(state, seat))
        }

        // Trick complete: resolve it.
        val trump = requireNotNull(state.trump)
        val ledSuit = effectiveSuit(trick.first().card, trump)
        val winner = trick.maxBy { trickStrength(it.card, trump, ledSuit) }.seat
        val tricksWon = state.tricksWon.toMutableList()
        tricksWon[winner] = tricksWon[winner] + 1

        val afterTrick = played.copy(
            trick = emptyList(),
            // Held so the finished trick can be seen before it is cleared away,
            // rather than the fourth card vanishing the instant it lands.
            completedTrick = trick,
            tricksWon = tricksWon,
            leader = winner,
            turn = winner,
            lastTrickWinner = winner,
            log = state.log + "Seat $winner takes the trick.",
        )

        val handFinished = afterTrick.hands.all { it.isEmpty() }
        return if (handFinished) scoreHand(afterTrick) else afterTrick
    }

    // -----------------------------------------------------------------------
    // Scoring
    // -----------------------------------------------------------------------

    private fun scoreHand(state: EuchreState): EuchreState {
        val maker = requireNotNull(state.maker)
        val makerTeam = teamOf(maker)
        val makerTricks = state.tricksWon.filterIndexed { seat, _ -> teamOf(seat) == makerTeam }.sum()
        val scores = state.scores.toMutableList()

        val note: String
        if (makerTricks >= 3) {
            val points = when {
                makerTricks == 5 && state.aloneSeat != null -> 4
                makerTricks == 5 -> 2
                else -> 1
            }
            scores[makerTeam] = scores[makerTeam] + points
            note = "Team $makerTeam took $makerTricks tricks for $points point(s)."
        } else {
            // Euchred: the defenders score two regardless of how it fell apart.
            val defenders = 1 - makerTeam
            scores[defenders] = scores[defenders] + 2
            note = "Team $makerTeam is euchred — team $defenders scores 2."
        }

        val winner = scores.indexOfFirst { it >= state.options.pointsToWin }
        return state.copy(
            phase = if (winner >= 0) EuchrePhase.GAME_OVER else EuchrePhase.HAND_OVER,
            scores = scores,
            log = state.log + note + if (winner >= 0) listOf("Team $winner wins.") else emptyList(),
        )
    }

    override fun isFinished(state: EuchreState): Boolean = state.phase == EuchrePhase.GAME_OVER

    override fun summary(state: EuchreState): String = when (state.phase) {
        EuchrePhase.GAME_OVER -> {
            val winner = state.scores.indexOfFirst { it >= state.options.pointsToWin }
            "Team $winner wins ${state.scores[0]}–${state.scores[1]}"
        }

        else -> "We ${state.scores[0]} — They ${state.scores[1]}"
    }

    // -----------------------------------------------------------------------
    // Redaction and wire format
    // -----------------------------------------------------------------------

    override fun viewFor(state: EuchreState, seat: Int): EuchreState = state.copy(
        hands = state.hands.mapIndexed { index, hand -> if (index == seat) hand else emptyList() },
        handCounts = state.handCounts,
    )

    override fun encodeState(state: EuchreState): String = json.encodeToString(state)

    override fun decodeState(json: String): EuchreState =
        this.json.decodeFromString(json)

    override fun encodeMove(move: EuchreMove): String = json.encodeToString(move)

    override fun decodeMove(json: String): EuchreMove =
        this.json.decodeFromString(json)
}
