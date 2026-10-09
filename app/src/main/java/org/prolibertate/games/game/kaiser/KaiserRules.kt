package org.prolibertate.games.game.kaiser

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
 * Kaiser as a pure state machine. See RULES-kaiser.md.
 */
object KaiserRules : GameRules<KaiserState, KaiserMove> {

    override val gameId: String = GameCatalog.KAISER

    private const val CARDS_PER_HAND = 8

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): KaiserState {
        require(config.seats.size == 4) { "Kaiser is a four-handed game" }
        val options = json.decodeFromString<KaiserOptions>(config.optionsJson)
        return deal(options, config.seed, 0, 0, listOf(0, 0), listOf("Hand 1."))
    }

    private fun deal(
        options: KaiserOptions,
        seed: Long,
        handNumber: Int,
        dealer: Int,
        scores: List<Int>,
        log: List<String>,
    ): KaiserState {
        val random = Random(seed + handNumber * 7919L)
        val deck = Decks.kaiser().shuffledWith(random)
        val hands = (0 until 4).map { seat ->
            deck.subList(seat * CARDS_PER_HAND, (seat + 1) * CARDS_PER_HAND).toList()
        }
        return KaiserState(
            options = options,
            seed = seed,
            handNumber = handNumber,
            dealer = dealer,
            phase = KaiserPhase.BIDDING,
            hands = hands,
            handCounts = hands.map { it.size },
            turn = (dealer + 1) % 4,
            passes = 0,
            highBid = null,
            highBidder = null,
            trump = null,
            trick = emptyList(),
            completedTrick = emptyList(),
            leader = (dealer + 1) % 4,
            lastTrickWinner = null,
            handPoints = listOf(0, 0),
            tricksWon = List(4) { 0 },
            scores = scores,
            log = log,
        )
    }

    /** Advances from [KaiserPhase.HAND_OVER] into the next deal. */
    fun nextHand(state: KaiserState): KaiserState {
        check(state.phase == KaiserPhase.HAND_OVER) { "Hand is not over" }
        val dealer = (state.dealer + 1) % 4
        return deal(
            options = state.options,
            seed = state.seed,
            handNumber = state.handNumber + 1,
            dealer = dealer,
            scores = state.scores,
            log = state.log + "Hand ${state.handNumber + 2}.",
        )
    }

    override fun currentSeat(state: KaiserState): Int? = when (state.phase) {
        KaiserPhase.BIDDING, KaiserPhase.PLAYING -> state.turn
        KaiserPhase.HAND_OVER, KaiserPhase.GAME_OVER -> null
    }

    // -----------------------------------------------------------------------
    // Legal moves
    // -----------------------------------------------------------------------

    override fun legalMoves(state: KaiserState, seat: Int): List<KaiserMove> {
        if (currentSeat(state) != seat) return emptyList()
        return when (state.phase) {
            KaiserPhase.BIDDING -> biddingMoves(state, seat)
            KaiserPhase.PLAYING -> playableCards(state, seat).map { PlayCard(it) }
            KaiserPhase.HAND_OVER, KaiserPhase.GAME_OVER -> emptyList()
        }
    }

    private fun biddingMoves(state: KaiserState, seat: Int): List<KaiserMove> {
        // Trump is named as a separate move once the bidding is settled.
        if (state.highBidder == seat && state.trump == null && biddingClosed(state)) {
            return buildList {
                Suit.entries.forEach { add(NameTrump(it)) }
                if (state.highBid?.noTrump == true) {
                    // A no-trump bid has already committed to none.
                    clear()
                    add(NameTrump(null))
                }
            }
        }

        val moves = mutableListOf<KaiserMove>()
        val floor = state.options.minimumBid
        val ceiling = MAX_BID
        for (points in floor..ceiling) {
            val plain = Bid(points, noTrump = false)
            if (plain.beats(state.highBid)) moves += MakeBid(plain)
            if (state.options.allowNoTrump) {
                val noTrump = Bid(points, noTrump = true)
                if (noTrump.beats(state.highBid)) moves += MakeBid(noTrump)
            }
        }

        // The dealer is stuck with the minimum if the bidding would die out.
        val dealerMustBid = seat == state.dealer && state.highBid == null && state.passes == 3
        if (!dealerMustBid) moves += PassBid
        return moves
    }

    private fun biddingClosed(state: KaiserState): Boolean =
        state.highBid != null && state.passes >= 3

    /** Follow the led suit if you hold it. */
    fun playableCards(state: KaiserState, seat: Int): List<Card> {
        val hand = state.hands[seat]
        val led = state.trick.firstOrNull() ?: return hand
        val following = hand.filter { it.suit == led.card.suit }
        return following.ifEmpty { hand }
    }

    // -----------------------------------------------------------------------
    // Move application
    // -----------------------------------------------------------------------

    override fun applyMove(state: KaiserState, seat: Int, move: KaiserMove): KaiserState {
        require(currentSeat(state) == seat) { "Not seat $seat's turn" }
        require(move in legalMoves(state, seat)) { "Illegal move $move for seat $seat" }
        return when (move) {
            is MakeBid -> applyBid(state, seat, move.bid)
            is PassBid -> applyPass(state, seat)
            is NameTrump -> applyNameTrump(state, move.suit)
            is PlayCard -> applyPlay(state, seat, move.card)
        }.normalised()
    }

    private fun KaiserState.normalised(): KaiserState = copy(handCounts = hands.map { it.size })

    private fun applyBid(state: KaiserState, seat: Int, bid: Bid): KaiserState {
        val next = state.copy(
            highBid = bid,
            highBidder = seat,
            // A fresh bid restarts the count of players willing to give way.
            passes = 0,
            turn = (seat + 1) % 4,
            log = state.log + "Seat $seat bids ${bid.points}${if (bid.noTrump) " no trump" else ""}.",
        )
        return if (biddingClosed(next)) next.copy(turn = seat) else next
    }

    private fun applyPass(state: KaiserState, seat: Int): KaiserState {
        val passes = state.passes + 1
        val passed = state.copy(passes = passes, log = state.log + "Seat $seat passes.")

        if (passes >= 4 && passed.highBid == null) {
            // Nobody wanted it: throw the hand in.
            return deal(
                options = state.options,
                seed = state.seed,
                handNumber = state.handNumber + 1,
                dealer = (state.dealer + 1) % 4,
                scores = state.scores,
                log = passed.log + "Hand thrown in; redealing.",
            )
        }
        // Bidding is settled once everyone else has given way.
        if (passed.highBid != null && passes >= 3) {
            return passed.copy(turn = passed.highBidder!!)
        }
        return passed.copy(turn = (seat + 1) % 4)
    }

    private fun applyNameTrump(state: KaiserState, suit: Suit?): KaiserState {
        val leader = state.highBidder!!
        return state.copy(
            phase = KaiserPhase.PLAYING,
            trump = suit,
            turn = leader,
            leader = leader,
            trick = emptyList(),
            completedTrick = emptyList(),
            log = state.log + if (suit == null) {
                "No trump."
            } else {
                "${suit.symbol} are trump."
            },
        )
    }

    private fun applyPlay(state: KaiserState, seat: Int, card: Card): KaiserState {
        val hands = state.hands.toMutableList()
        hands[seat] = hands[seat] - card
        val trick = state.trick + PlayedCard(seat, card)
        val played = state.copy(hands = hands, trick = trick, completedTrick = emptyList())

        if (trick.size < 4) return played.copy(turn = (seat + 1) % 4)

        val ledSuit = trick.first().card.suit
        val winner = trick.maxBy { trickStrength(it.card, state.trump, ledSuit) }.seat
        val points = trickValue(trick.map { it.card })

        val handPoints = played.handPoints.toMutableList()
        handPoints[teamOf(winner)] = handPoints[teamOf(winner)] + points

        val tricksWon = played.tricksWon.toMutableList()
        tricksWon[winner] = tricksWon[winner] + 1

        val afterTrick = played.copy(
            trick = emptyList(),
            completedTrick = trick,
            handPoints = handPoints,
            tricksWon = tricksWon,
            leader = winner,
            turn = winner,
            lastTrickWinner = winner,
            log = state.log + "Seat $winner takes the trick ($points).",
        )

        return if (afterTrick.hands.all { it.isEmpty() }) scoreHand(afterTrick) else afterTrick
    }

    // -----------------------------------------------------------------------
    // Scoring
    // -----------------------------------------------------------------------

    private fun scoreHand(state: KaiserState): KaiserState {
        val bidder = requireNotNull(state.highBidder)
        val bid = requireNotNull(state.highBid)
        val bidderTeam = teamOf(bidder)
        val defenders = 1 - bidderTeam
        val taken = state.handPoints[bidderTeam]

        val scores = state.scores.toMutableList()
        val note: String
        if (taken >= bid.points) {
            // No-trump contracts pay double, which is what makes them worth bidding.
            val gained = if (bid.noTrump) taken * 2 else taken
            scores[bidderTeam] = scores[bidderTeam] + gained
            note = "Team $bidderTeam made ${bid.points} with $taken."
        } else {
            val lost = if (bid.noTrump) bid.points * 2 else bid.points
            scores[bidderTeam] = scores[bidderTeam] - lost
            note = "Team $bidderTeam went down ${bid.points}, taking only $taken."
        }
        // Defenders always keep what they took.
        scores[defenders] = scores[defenders] + state.handPoints[defenders]

        // A game ends either by climbing to the target or by falling through
        // the floor, and the floor is what guarantees it ends at all.
        val reachedTarget = scores.indexOfFirst { it >= state.options.pointsToWin }
        val fellThrough = scores.indexOfFirst { it <= state.options.losingScore }
        val over = reachedTarget >= 0 || fellThrough >= 0
        val winner = when {
            reachedTarget >= 0 -> reachedTarget
            fellThrough >= 0 -> 1 - fellThrough
            else -> -1
        }

        return state.copy(
            phase = if (over) KaiserPhase.GAME_OVER else KaiserPhase.HAND_OVER,
            scores = scores,
            log = state.log + note + if (over) listOf("Team $winner wins.") else emptyList(),
        )
    }

    override fun isFinished(state: KaiserState): Boolean = state.phase == KaiserPhase.GAME_OVER

    override fun summary(state: KaiserState): String = when (state.phase) {
        KaiserPhase.GAME_OVER -> {
            val winner = state.scores.indices.maxByOrNull { state.scores[it] } ?: 0
            "Team $winner wins ${state.scores[0]}–${state.scores[1]}"
        }

        else -> "We ${state.scores[0]} — They ${state.scores[1]}"
    }

    override fun viewFor(state: KaiserState, seat: Int): KaiserState = state.copy(
        hands = state.hands.mapIndexed { index, hand -> if (index == seat) hand else emptyList() },
        handCounts = state.handCounts,
    )

    override fun encodeState(state: KaiserState): String = json.encodeToString(state)
    override fun decodeState(json: String): KaiserState = this.json.decodeFromString(json)
    override fun encodeMove(move: KaiserMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): KaiserMove = this.json.decodeFromString(json)
}
