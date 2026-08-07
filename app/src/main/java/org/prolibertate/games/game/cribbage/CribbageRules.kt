package org.prolibertate.games.game.cribbage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Decks
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.shuffledWith
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

/**
 * Cribbage as a pure state machine, for two, three or four. See RULES-cribbage.md.
 *
 * One thing shapes the whole of this file: a game of cribbage is over the
 * instant a peg reaches the target, in the middle of a count if that is where
 * it happens. So every award goes through [award], which stops the game there
 * and refuses everything after it, rather than the hand being scored in full
 * and the total compared afterwards.
 */
object CribbageRules : GameRules<CribbageState, CribbageMove> {

    override val gameId: String = GameCatalog.CRIBBAGE

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): CribbageState {
        val options = json.decodeFromString<CribbageOptions>(config.optionsJson)
        require(config.seats.size == options.playerCount) {
            "Seat count ${config.seats.size} does not match ${options.playerCount} players"
        }
        return deal(
            options = options,
            seed = config.seed,
            handNumber = 0,
            dealer = 0,
            scores = List(options.teamCount) { 0 },
            log = listOf("Hand 1. Seat 0 deals."),
        )
    }

    private fun deal(
        options: CribbageOptions,
        seed: Long,
        handNumber: Int,
        dealer: Int,
        scores: List<Int>,
        log: List<String>,
    ): CribbageState {
        val random = Random(seed + handNumber * 7919L)
        var pack = Decks.standard52().shuffledWith(random)

        val hands = MutableList(options.playerCount) { emptyList<Card>() }
        // Dealt round the table from the dealer's left, as they would be.
        for (step in 1..options.playerCount) {
            val seat = (dealer + step) % options.playerCount
            hands[seat] = pack.take(options.dealSize)
            pack = pack.drop(options.dealSize)
        }
        val crib = pack.take(options.cribFromDeck)
        pack = pack.drop(options.cribFromDeck)

        return CribbageState(
            options = options,
            seed = seed,
            handNumber = handNumber,
            dealer = dealer,
            hands = hands,
            handCounts = hands.map { it.size },
            crib = crib,
            cribCount = crib.size,
            deck = pack,
            starter = null,
            played = emptyList(),
            series = emptyList(),
            saidGo = emptySet(),
            turn = (dealer + 1) % options.playerCount,
            lastToPlay = null,
            scores = scores,
            previousScores = scores,
            show = emptyList(),
            phase = CribbagePhase.DISCARD,
            winner = null,
            log = log,
        )
    }

    /** Advances from [CribbagePhase.SHOW] into the next deal, the deal moving on one seat. */
    fun nextHand(state: CribbageState): CribbageState {
        check(state.phase == CribbagePhase.SHOW) { "The hand is not over" }
        val dealer = (state.dealer + 1) % state.options.playerCount
        return deal(
            options = state.options,
            seed = state.seed,
            handNumber = state.handNumber + 1,
            dealer = dealer,
            scores = state.scores,
            log = state.log + "Hand ${state.handNumber + 2}. Seat $dealer deals.",
        )
    }

    override fun currentSeat(state: CribbageState): Int? = when (state.phase) {
        CribbagePhase.DISCARD, CribbagePhase.PLAY -> state.turn
        CribbagePhase.SHOW, CribbagePhase.GAME_OVER -> null
    }

    override fun legalMoves(state: CribbageState, seat: Int): List<CribbageMove> {
        if (currentSeat(state) != seat) return emptyList()
        return when (state.phase) {
            CribbagePhase.DISCARD ->
                combinations(state.hands[seat], state.options.layAwaySize).map { LayAway.of(it) }

            CribbagePhase.PLAY -> state.remaining(seat)
                .filter { pipValue(it) + state.count <= CRIBBAGE_LIMIT }
                .map { PegCard(it) }

            else -> emptyList()
        }
    }

    override fun applyMove(state: CribbageState, seat: Int, move: CribbageMove): CribbageState {
        require(currentSeat(state) == seat) { "Not seat $seat's turn" }
        require(move in legalMoves(state, seat)) { "Illegal move $move for seat $seat" }
        return when (move) {
            is LayAway -> applyLayAway(state, seat, move)
            is PegCard -> applyPeg(state, seat, move)
        }
    }

    // -----------------------------------------------------------------------
    // The crib
    // -----------------------------------------------------------------------

    private fun applyLayAway(
        state: CribbageState,
        seat: Int,
        move: LayAway,
    ): CribbageState {
        val hands = state.hands.toMutableList()
        hands[seat] = hands[seat] - move.cards.toSet()
        val crib = state.crib + move.cards

        val next = state.copy(
            hands = hands,
            handCounts = hands.map { it.size },
            crib = crib,
            cribCount = crib.size,
            turn = (seat + 1) % state.options.playerCount,
            log = state.log + "Seat $seat lays ${move.cards.size} away to seat ${state.dealer}'s crib.",
        )

        val everyoneHasLaidAway = next.hands.all { it.size == CRIBBAGE_HAND_SIZE }
        return if (everyoneHasLaidAway) cutTheStarter(next) else next
    }

    /**
     * The cut, which is the top of what is left of a pack already shuffled — so
     * cutting it anywhere gives the same distribution as cutting it here.
     */
    private fun cutTheStarter(state: CribbageState): CribbageState {
        val starter = state.deck.first()
        val cut = state.copy(
            starter = starter,
            deck = state.deck.drop(1),
            turn = (state.dealer + 1) % state.options.playerCount,
            phase = CribbagePhase.PLAY,
            log = state.log + "The starter is ${starter.label}.",
        )
        // Two for his heels, and it is the dealer's whoever cut it.
        return if (starter.rank == Rank.JACK) {
            award(cut, state.dealer, 2, "his heels")
        } else {
            cut
        }
    }

    // -----------------------------------------------------------------------
    // The play
    // -----------------------------------------------------------------------

    private fun applyPeg(state: CribbageState, seat: Int, move: PegCard): CribbageState {
        val series = state.series + PeggedCard(seat, move.card)
        var next = state.copy(
            played = state.played + PeggedCard(seat, move.card),
            series = series,
            lastToPlay = seat,
        )
        next = next.copy(
            handCounts = handCounts(next),
            log = next.log + "Seat $seat plays ${move.card.label} for ${next.count}.",
        )

        CribbageScoring.peg(series.map { it.card }).forEach { line ->
            next = award(next, seat, line.points, line.label.lowercase())
        }
        if (next.winner != null) return next

        return advancePlay(next, seat)
    }

    /**
     * Hands the play to the next seat that can actually lay a card.
     *
     * A seat that cannot play says go — the count only ever rises within a
     * series, so a seat that cannot play now cannot play later in it either,
     * and this walks past it rather than stopping the table on a move with one
     * possible answer. When nobody at all can play, the series is closed.
     */
    private fun advancePlay(state: CribbageState, justPlayed: Int): CribbageState {
        if (state.count == CRIBBAGE_LIMIT) {
            // Thirty-one has already paid two; there is no point for the go on top.
            return closeSeries(state, justPlayed, payForTheGo = false)
        }

        var next = state
        val seats = next.options.playerCount
        for (step in 1..seats) {
            val seat = (justPlayed + step) % seats
            val held = next.remaining(seat)
            if (held.isEmpty()) continue
            if (held.any { pipValue(it) + next.count <= CRIBBAGE_LIMIT }) {
                return next.copy(turn = seat)
            }
            if (seat !in next.saidGo) {
                next = next.copy(
                    saidGo = next.saidGo + seat,
                    log = next.log + "Seat $seat says go.",
                )
            }
        }
        return closeSeries(next, justPlayed, payForTheGo = true)
    }

    /**
     * Clears the count after a thirty-one or a go, and pays for the last card.
     *
     * The seat after whoever played that last card leads the next series, which
     * is why a hand can turn on being left holding cards nobody can follow.
     */
    private fun closeSeries(
        state: CribbageState,
        lastPlayer: Int,
        payForTheGo: Boolean,
    ): CribbageState {
        var next = state
        if (payForTheGo) next = award(next, lastPlayer, 1, "the last card")
        if (next.winner != null) return next

        val dealt = next.options.playerCount * CRIBBAGE_HAND_SIZE
        if (next.played.size == dealt) return theShow(next)

        val seats = next.options.playerCount
        val lead = (1..seats)
            .map { (lastPlayer + it) % seats }
            .first { next.remaining(it).isNotEmpty() }

        return next.copy(series = emptyList(), saidGo = emptySet(), turn = lead)
    }

    // -----------------------------------------------------------------------
    // The show
    // -----------------------------------------------------------------------

    /**
     * Counts the hands in order — the dealer's left first, round to the dealer,
     * and the crib last of all.
     *
     * The order is the game: a non-dealer who can count out gets there before
     * the dealer's hand and crib are ever counted, and that is what makes being
     * behind on the dealer's turn worse than the numbers alone suggest.
     */
    private fun theShow(state: CribbageState): CribbageState {
        val starter = requireNotNull(state.starter) { "The show without a starter" }
        val seats = state.options.playerCount

        val entries = (1..seats).map { step ->
            val seat = (state.dealer + step) % seats
            ShowScore(
                seat = seat,
                isCrib = false,
                cards = state.hands[seat],
                lines = CribbageScoring.show(state.hands[seat], starter, isCrib = false),
            )
        } + ShowScore(
            seat = state.dealer,
            isCrib = true,
            cards = state.crib,
            lines = CribbageScoring.show(state.crib, starter, isCrib = true),
        )

        var next = state
        val counted = mutableListOf<ShowScore>()
        for (entry in entries) {
            if (next.winner != null) {
                counted += entry.copy(counted = false)
                continue
            }
            counted += entry
            entry.lines.forEach { line ->
                next = award(next, entry.seat, line.points, line.label.lowercase())
            }
        }

        return next.copy(
            show = counted,
            phase = if (next.winner != null) CribbagePhase.GAME_OVER else CribbagePhase.SHOW,
        )
    }

    // -----------------------------------------------------------------------
    // Pegging points
    // -----------------------------------------------------------------------

    /**
     * Moves [seat]'s side up the board, and ends the game there if that reaches
     * the target.
     *
     * Everything scored anywhere in the game comes through here, so the game
     * cannot be carried past its own finish by the rest of a count.
     */
    private fun award(
        state: CribbageState,
        seat: Int,
        points: Int,
        reason: String,
    ): CribbageState {
        if (points <= 0 || state.winner != null) return state
        val team = state.options.teamOf(seat)

        val scores = state.scores.toMutableList()
        val previous = state.previousScores.toMutableList()
        previous[team] = scores[team]
        scores[team] = scores[team] + points

        val won = scores[team] >= state.options.pointsToWin
        return state.copy(
            scores = scores,
            previousScores = previous,
            winner = if (won) team else null,
            phase = if (won) CribbagePhase.GAME_OVER else state.phase,
            log = state.log + "Seat $seat pegs $points for $reason.",
        )
    }

    private fun handCounts(state: CribbageState): List<Int> =
        (0 until state.options.playerCount).map { seat ->
            state.hands[seat].size - state.played.count { it.seat == seat }
        }

    /** Every way of choosing [size] cards, in the order they are held. */
    private fun combinations(cards: List<Card>, size: Int): List<List<Card>> {
        if (size == 0) return listOf(emptyList())
        if (cards.size < size) return emptyList()

        val chosen = mutableListOf<List<Card>>()
        fun take(from: Int, soFar: List<Card>) {
            if (soFar.size == size) {
                chosen += soFar
                return
            }
            for (index in from until cards.size) take(index + 1, soFar + cards[index])
        }
        take(0, emptyList())
        return chosen
    }

    // -----------------------------------------------------------------------
    // Engine plumbing
    // -----------------------------------------------------------------------

    override fun isFinished(state: CribbageState): Boolean =
        state.phase == CribbagePhase.GAME_OVER

    override fun summary(state: CribbageState): String {
        val board = state.scores.mapIndexed { team, score -> "T$team:$score" }.joinToString(" ")
        val winner = state.winner ?: return board
        val trailing = state.scores
            .filterIndexed { team, _ -> team != winner }
            .maxOrNull() ?: 0
        return "Team $winner wins ($board)" + skunk(state.options, trailing)
    }

    /**
     * A loser left on the second street is skunked, and one who never left the
     * first is double skunked. In a game to 121 those lines are 91 and 61.
     */
    private fun skunk(options: CribbageOptions, trailing: Int): String {
        if (!options.countSkunks) return ""
        return when {
            trailing <= options.pointsToWin / 2 -> " — a double skunk"
            trailing < options.pointsToWin - options.pointsToWin / 4 -> " — a skunk"
            else -> ""
        }
    }

    /**
     * Hands and the crib are hidden until the show, and the rest of the pack
     * always: the starter is cut from it, so a client that could see it would
     * know the cut before it happened.
     */
    override fun viewFor(state: CribbageState, seat: Int): CribbageState {
        val revealed = state.phase == CribbagePhase.SHOW || state.phase == CribbagePhase.GAME_OVER
        return state.copy(
            hands = state.hands.mapIndexed { index, hand ->
                if (revealed || index == seat) hand else emptyList()
            },
            crib = if (revealed) state.crib else emptyList(),
            deck = emptyList(),
        )
    }

    override fun encodeState(state: CribbageState): String = json.encodeToString(state)
    override fun decodeState(json: String): CribbageState = this.json.decodeFromString(json)
    override fun encodeMove(move: CribbageMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): CribbageMove = this.json.decodeFromString(json)
}
