package org.prolibertate.games.game.wizard

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
 * Wizard as a pure state machine. See RULES-wizard.md.
 */
object WizardRules : GameRules<WizardState, WizardMove> {

    override val gameId: String = GameCatalog.WIZARD

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): WizardState {
        val options = json.decodeFromString<WizardOptions>(config.optionsJson)
        require(config.seats.size == options.playerCount) {
            "Seat count ${config.seats.size} does not match ${options.playerCount} players"
        }
        return deal(options, config.seed, 0, 0, List(options.playerCount) { 0 }, listOf("Round 1."))
    }

    private fun deal(
        options: WizardOptions,
        seed: Long,
        round: Int,
        dealer: Int,
        scores: List<Int>,
        log: List<String>,
    ): WizardState {
        val random = Random(seed + round * 7919L)
        var deck = Decks.wizard().shuffledWith(random)
        val cards = round + 1

        val hands = mutableListOf<List<Card>>()
        repeat(options.playerCount) {
            hands += deck.take(cards)
            deck = deck.drop(cards)
        }

        // Trump comes off the top of what is left. In the last round the whole
        // deck has been dealt, so there is nothing to turn and no trump.
        val trumpCard = deck.firstOrNull()
        val trump = when {
            trumpCard == null -> null
            isWizardCard(trumpCard) -> null // dealer chooses; see BIDDING below
            isJester(trumpCard) -> null
            else -> trumpCard.suit
        }

        return WizardState(
            options = options,
            seed = seed,
            round = round,
            dealer = dealer,
            phase = WizardPhase.BIDDING,
            hands = hands,
            handCounts = hands.map { it.size },
            trumpCard = trumpCard,
            trump = trump,
            bids = List(options.playerCount) { null },
            tricksWon = List(options.playerCount) { 0 },
            // A turned wizard means the dealer picks trump before anyone bids.
            turn = if (trumpCard != null && isWizardCard(trumpCard)) {
                dealer
            } else {
                (dealer + 1) % options.playerCount
            },
            trick = emptyList(),
            completedTrick = emptyList(),
            leader = (dealer + 1) % options.playerCount,
            lastTrickWinner = null,
            scores = scores,
            roundScores = List(options.playerCount) { 0 },
            log = log,
        )
    }

    /** Advances from [WizardPhase.ROUND_OVER] into the next deal. */
    fun nextRound(state: WizardState): WizardState {
        check(state.phase == WizardPhase.ROUND_OVER) { "Round is not over" }
        val dealer = (state.dealer + 1) % state.options.playerCount
        return deal(
            options = state.options,
            seed = state.seed,
            round = state.round + 1,
            dealer = dealer,
            scores = state.scores,
            log = state.log + "Round ${state.round + 2}.",
        )
    }

    override fun currentSeat(state: WizardState): Int? = when (state.phase) {
        WizardPhase.BIDDING, WizardPhase.PLAYING -> state.turn
        WizardPhase.ROUND_OVER, WizardPhase.GAME_OVER -> null
    }

    // -----------------------------------------------------------------------
    // Legal moves
    // -----------------------------------------------------------------------

    override fun legalMoves(state: WizardState, seat: Int): List<WizardMove> {
        if (currentSeat(state) != seat) return emptyList()
        return when (state.phase) {
            WizardPhase.BIDDING -> biddingMoves(state, seat)
            WizardPhase.PLAYING -> playableCards(state, seat).map { PlayCard(it) }
            WizardPhase.ROUND_OVER, WizardPhase.GAME_OVER -> emptyList()
        }
    }

    private fun biddingMoves(state: WizardState, seat: Int): List<WizardMove> {
        if (needsTrumpChoice(state)) return Suit.entries.map { ChooseTrump(it) }

        val available = state.cardsThisRound
        val bids = (0..available).toMutableList()

        // Screw the dealer: the last bid may not make the bids balance, so at
        // least one player is always going to be wrong.
        if (state.options.screwTheDealer && seat == state.dealer) {
            val alreadyBid = state.bids.filterNotNull().sum()
            val forbidden = available - alreadyBid
            bids.remove(forbidden)
        }
        return bids.map { MakeBid(it) }
    }

    /** A wizard on top means the dealer names trump before bidding starts. */
    private fun needsTrumpChoice(state: WizardState): Boolean =
        state.trumpCard != null &&
            isWizardCard(state.trumpCard) &&
            state.trump == null &&
            state.bids.all { it == null }

    /**
     * Follow the led suit if you hold it. Wizards and jesters are always legal
     * — playing one is how you get out of following.
     */
    fun playableCards(state: WizardState, seat: Int): List<Card> {
        val hand = state.hands[seat]
        val led = ledSuitOf(state.trick) ?: return hand
        val following = hand.filter { it.suit == led && !isWizardCard(it) && !isJester(it) }
        if (following.isEmpty()) return hand
        // Keep the hand's own order so the UI does not reshuffle under the player.
        return hand.filter { it.suit == led || isWizardCard(it) || isJester(it) }
    }

    // -----------------------------------------------------------------------
    // Move application
    // -----------------------------------------------------------------------

    override fun applyMove(state: WizardState, seat: Int, move: WizardMove): WizardState {
        require(currentSeat(state) == seat) { "Not seat $seat's turn" }
        require(move in legalMoves(state, seat)) { "Illegal move $move for seat $seat" }
        return when (move) {
            is ChooseTrump -> state.copy(
                trump = move.suit,
                turn = (state.dealer + 1) % state.options.playerCount,
                log = state.log + "Dealer names ${move.suit.symbol} trump.",
            )

            is MakeBid -> applyBid(state, seat, move.tricks)
            is PlayCard -> applyPlay(state, seat, move.card)
        }.normalised()
    }

    private fun WizardState.normalised(): WizardState = copy(handCounts = hands.map { it.size })

    private fun applyBid(state: WizardState, seat: Int, tricks: Int): WizardState {
        val bids = state.bids.toMutableList()
        bids[seat] = tricks
        val next = state.copy(
            bids = bids,
            log = state.log + "Seat $seat bids $tricks.",
        )

        if (bids.all { it != null }) {
            val leader = (state.dealer + 1) % state.options.playerCount
            return next.copy(phase = WizardPhase.PLAYING, turn = leader, leader = leader)
        }
        return next.copy(turn = (seat + 1) % state.options.playerCount)
    }

    private fun applyPlay(state: WizardState, seat: Int, card: Card): WizardState {
        val hands = state.hands.toMutableList()
        hands[seat] = hands[seat] - card
        val trick = state.trick + PlayedCard(seat, card)
        val played = state.copy(hands = hands, trick = trick, completedTrick = emptyList())

        if (trick.size < state.options.playerCount) {
            return played.copy(turn = (seat + 1) % state.options.playerCount)
        }

        val ledSuit = ledSuitOf(trick)
        val winner = trick
            .withIndex()
            .maxBy { (index, played) -> trickStrength(played.card, index, state.trump, ledSuit) }
            .value.seat

        val tricksWon = played.tricksWon.toMutableList()
        tricksWon[winner] = tricksWon[winner] + 1

        val afterTrick = played.copy(
            trick = emptyList(),
            completedTrick = trick,
            tricksWon = tricksWon,
            leader = winner,
            turn = winner,
            lastTrickWinner = winner,
            log = state.log + "Seat $winner takes the trick.",
        )

        return if (afterTrick.hands.all { it.isEmpty() }) scoreRoundEnd(afterTrick) else afterTrick
    }

    private fun scoreRoundEnd(state: WizardState): WizardState {
        val roundScores = state.bids.mapIndexed { seat, bid ->
            scoreRound(bid ?: 0, state.tricksWon[seat])
        }
        val scores = state.scores.mapIndexed { seat, total -> total + roundScores[seat] }
        val lastRound = state.round + 1 >= state.options.totalRounds()

        return state.copy(
            phase = if (lastRound) WizardPhase.GAME_OVER else WizardPhase.ROUND_OVER,
            roundScores = roundScores,
            scores = scores,
            log = state.log + roundScores.mapIndexed { seat, points ->
                "Seat $seat bid ${state.bids[seat]}, took ${state.tricksWon[seat]}: $points"
            },
        )
    }

    override fun isFinished(state: WizardState): Boolean = state.phase == WizardPhase.GAME_OVER

    override fun summary(state: WizardState): String = when (state.phase) {
        WizardPhase.GAME_OVER -> {
            val best = state.scores.indices.maxByOrNull { state.scores[it] } ?: 0
            "Seat $best wins with ${state.scores[best]}"
        }

        else -> state.scores.mapIndexed { seat, s -> "S$seat:$s" }.joinToString(" ")
    }

    override fun viewFor(state: WizardState, seat: Int): WizardState = state.copy(
        hands = state.hands.mapIndexed { index, hand -> if (index == seat) hand else emptyList() },
        handCounts = state.handCounts,
    )

    override fun encodeState(state: WizardState): String = json.encodeToString(state)
    override fun decodeState(json: String): WizardState = this.json.decodeFromString(json)
    override fun encodeMove(move: WizardMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): WizardMove = this.json.decodeFromString(json)
}
