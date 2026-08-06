package org.prolibertate.games.game.kaiser

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Decks
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

class KaiserRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(options: KaiserOptions = KaiserOptions(), seed: Long = 5L) = TableConfig(
        gameId = "kaiser",
        seats = (0 until 4).map {
            PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it % 2)
        },
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    // -- The deck -----------------------------------------------------------

    @Test
    fun `the deck is thirty-two cards with the two counters in it`() {
        val deck = Decks.kaiser()
        assertEquals(32, deck.size)
        assertEquals("no duplicates", 32, deck.distinct().size)
        assertTrue("five of hearts", deck.contains(FIVE_OF_HEARTS))
        assertTrue("three of spades", deck.contains(THREE_OF_SPADES))
        assertTrue("seven of clubs stays", deck.contains(Card(Rank.SEVEN, Suit.CLUBS)))
        assertTrue("seven of diamonds stays", deck.contains(Card(Rank.SEVEN, Suit.DIAMONDS)))
        assertFalse("but not the seven of hearts", deck.contains(Card(Rank.SEVEN, Suit.HEARTS)))
        assertFalse("nor the seven of spades", deck.contains(Card(Rank.SEVEN, Suit.SPADES)))
        assertTrue("nothing below the counters", deck.none {
            it.rank.order < Rank.SEVEN.order && it != FIVE_OF_HEARTS && it != THREE_OF_SPADES
        })
    }

    @Test
    fun `a hand is worth ten points in total`() {
        // Eight tricks at one each, plus five, less three.
        val deck = Decks.kaiser()
        val allTricks = deck.chunked(4)
        assertEquals(8, allTricks.size)
        assertEquals(10, allTricks.sumOf { trickValue(it) })
    }

    @Test
    fun `the counters move a tricks value`() {
        val plain = listOf(Card(Rank.ACE, Suit.CLUBS), Card(Rank.KING, Suit.CLUBS))
        assertEquals(1, trickValue(plain))
        assertEquals(6, trickValue(plain + FIVE_OF_HEARTS))
        assertEquals(-2, trickValue(plain + THREE_OF_SPADES))
        assertEquals(3, trickValue(plain + FIVE_OF_HEARTS + THREE_OF_SPADES))
    }

    // -- Bidding ------------------------------------------------------------

    @Test
    fun `everyone gets eight cards and bidding opens left of the dealer`() {
        val state = KaiserRules.initialState(config())
        assertEquals(KaiserPhase.BIDDING, state.phase)
        state.hands.forEach { assertEquals(8, it.size) }
        assertEquals((state.dealer + 1) % 4, state.turn)
    }

    @Test
    fun `a bid must beat the one before it`() {
        var state = KaiserRules.initialState(config(KaiserOptions(minimumBid = 6)))
        val opener = state.turn
        state = KaiserRules.applyMove(state, opener, MakeBid(Bid(7, noTrump = false)))

        val moves = KaiserRules.legalMoves(state, state.turn).filterIsInstance<MakeBid>()
        assertTrue("nothing at or below seven in suit",
            moves.none { !it.bid.noTrump && it.bid.points <= 7 })
        assertTrue("but seven no trump beats seven",
            moves.any { it.bid.noTrump && it.bid.points == 7 })
    }

    @Test
    fun `no trump outranks the same number`() {
        assertTrue(Bid(7, noTrump = true).beats(Bid(7, noTrump = false)))
        assertFalse(Bid(7, noTrump = false).beats(Bid(7, noTrump = true)))
        assertTrue(Bid(8, noTrump = false).beats(Bid(7, noTrump = true)))
    }

    @Test
    fun `the bidding floor is respected`() {
        val state = KaiserRules.initialState(config(KaiserOptions(minimumBid = 8)))
        val moves = KaiserRules.legalMoves(state, state.turn).filterIsInstance<MakeBid>()
        assertTrue(moves.all { it.bid.points >= 8 })
    }

    @Test
    fun `the dealer is stuck when everyone passes`() {
        var state = KaiserRules.initialState(config())
        repeat(3) { state = KaiserRules.applyMove(state, state.turn, PassBid) }
        assertEquals(state.dealer, state.turn)
        val moves = KaiserRules.legalMoves(state, state.dealer)
        assertFalse("the dealer has to bid", moves.contains(PassBid))
        assertTrue(moves.all { it is MakeBid })
    }

    @Test
    fun `the winning bidder names trump and leads`() {
        var state = KaiserRules.initialState(config())
        val bidder = state.turn
        state = KaiserRules.applyMove(state, bidder, MakeBid(Bid(6, noTrump = false)))
        repeat(3) { state = KaiserRules.applyMove(state, state.turn, PassBid) }

        assertEquals("back to the bidder", bidder, state.turn)
        val naming = KaiserRules.legalMoves(state, bidder).filterIsInstance<NameTrump>()
        assertEquals("a choice of four suits", 4, naming.size)

        state = KaiserRules.applyMove(state, bidder, NameTrump(Suit.SPADES))
        assertEquals(KaiserPhase.PLAYING, state.phase)
        assertEquals(Suit.SPADES, state.trump)
        assertEquals("and leads", bidder, state.turn)
    }

    @Test
    fun `a no trump bid commits to no trump`() {
        var state = KaiserRules.initialState(config())
        val bidder = state.turn
        state = KaiserRules.applyMove(state, bidder, MakeBid(Bid(6, noTrump = true)))
        repeat(3) { state = KaiserRules.applyMove(state, state.turn, PassBid) }

        val naming = KaiserRules.legalMoves(state, bidder).filterIsInstance<NameTrump>()
        assertEquals(listOf(NameTrump(null)), naming)
        state = KaiserRules.applyMove(state, bidder, NameTrump(null))
        assertEquals(null, state.trump)
        assertEquals(KaiserPhase.PLAYING, state.phase)
    }

    // -- Play ---------------------------------------------------------------

    private fun playingState(
        hands: List<List<Card>>,
        trump: Suit?,
        bid: Bid = Bid(6, noTrump = false),
        bidder: Int = 0,
        turn: Int = 0,
    ) = KaiserState(
        options = KaiserOptions(),
        seed = 1L,
        handNumber = 0,
        dealer = 3,
        phase = KaiserPhase.PLAYING,
        hands = hands,
        handCounts = hands.map { it.size },
        turn = turn,
        passes = 3,
        highBid = bid,
        highBidder = bidder,
        trump = trump,
        trick = emptyList(),
        completedTrick = emptyList(),
        leader = turn,
        lastTrickWinner = null,
        handPoints = listOf(0, 0),
        scores = listOf(0, 0),
        log = emptyList(),
    )

    @Test
    fun `you must follow suit`() {
        val hand = listOf(Card(Rank.NINE, Suit.HEARTS), Card(Rank.ACE, Suit.CLUBS))
        val state = playingState(
            hands = listOf(emptyList(), hand, emptyList(), emptyList()),
            trump = Suit.SPADES,
            turn = 1,
        ).copy(trick = listOf(PlayedCard(0, Card(Rank.KING, Suit.HEARTS))))
        assertEquals(listOf(Card(Rank.NINE, Suit.HEARTS)), KaiserRules.playableCards(state, 1))
    }

    @Test
    fun `trump beats the led suit and there are no bowers`() {
        val trump = Suit.SPADES
        assertTrue(
            trickStrength(Card(Rank.EIGHT, trump), trump, Suit.HEARTS) >
                trickStrength(Card(Rank.ACE, Suit.HEARTS), trump, Suit.HEARTS)
        )
        // The jack is just a jack here.
        assertTrue(
            trickStrength(Card(Rank.ACE, trump), trump, trump) >
                trickStrength(Card(Rank.JACK, trump), trump, trump)
        )
    }

    @Test
    fun `taking the five of hearts is worth five`() {
        val hands = listOf(
            listOf(Card(Rank.ACE, Suit.CLUBS)),
            listOf(FIVE_OF_HEARTS),
            listOf(Card(Rank.EIGHT, Suit.CLUBS)),
            listOf(Card(Rank.NINE, Suit.CLUBS)),
        )
        var state = playingState(hands, trump = Suit.SPADES)
        state = KaiserRules.applyMove(state, 0, PlayCard(Card(Rank.ACE, Suit.CLUBS)))
        state = KaiserRules.applyMove(state, 1, PlayCard(FIVE_OF_HEARTS))
        state = KaiserRules.applyMove(state, 2, PlayCard(Card(Rank.EIGHT, Suit.CLUBS)))
        state = KaiserRules.applyMove(state, 3, PlayCard(Card(Rank.NINE, Suit.CLUBS)))

        assertEquals("seat 0 took it", 0, state.lastTrickWinner)
        assertEquals("one for the trick plus five", 6, state.handPoints[0])
    }

    @Test
    fun `taking the three of spades costs three`() {
        val hands = listOf(
            listOf(Card(Rank.ACE, Suit.CLUBS)),
            listOf(THREE_OF_SPADES),
            listOf(Card(Rank.EIGHT, Suit.CLUBS)),
            listOf(Card(Rank.NINE, Suit.CLUBS)),
        )
        var state = playingState(hands, trump = Suit.HEARTS)
        state = KaiserRules.applyMove(state, 0, PlayCard(Card(Rank.ACE, Suit.CLUBS)))
        state = KaiserRules.applyMove(state, 1, PlayCard(THREE_OF_SPADES))
        state = KaiserRules.applyMove(state, 2, PlayCard(Card(Rank.EIGHT, Suit.CLUBS)))
        state = KaiserRules.applyMove(state, 3, PlayCard(Card(Rank.NINE, Suit.CLUBS)))

        assertEquals(0, state.lastTrickWinner)
        assertEquals("one for the trick less three", -2, state.handPoints[0])
    }

    @Test
    fun `a finished trick is held on the table`() {
        val hands = listOf(
            listOf(Card(Rank.ACE, Suit.CLUBS), Card(Rank.KING, Suit.CLUBS)),
            listOf(Card(Rank.EIGHT, Suit.CLUBS), Card(Rank.NINE, Suit.CLUBS)),
            listOf(Card(Rank.TEN, Suit.CLUBS), Card(Rank.QUEEN, Suit.CLUBS)),
            listOf(Card(Rank.JACK, Suit.CLUBS), Card(Rank.EIGHT, Suit.HEARTS)),
        )
        var state = playingState(hands, trump = Suit.SPADES)
        repeat(4) {
            val seat = KaiserRules.currentSeat(state)!!
            state = KaiserRules.applyMove(state, seat, PlayCard(state.hands[seat].first()))
        }
        assertTrue(state.trick.isEmpty())
        assertEquals("all four held for display", 4, state.completedTrick.size)
    }

    // -- Scoring ------------------------------------------------------------

    @Test
    fun `making the bid scores what was taken and going down costs the bid`() {
        val made = scoreOneHand(bid = Bid(6, noTrump = false), bidderTakesAll = true)
        assertTrue("made contract scores", made.scores[0] > 0)

        val down = scoreOneHand(bid = Bid(10, noTrump = false), bidderTakesAll = false)
        assertEquals("going down costs the bid", -10, down.scores[0])
    }

    /** Plays a whole hand where one team either wins every trick or none. */
    private fun scoreOneHand(bid: Bid, bidderTakesAll: Boolean): KaiserState {
        val strong = Suit.SPADES
        // Team 0 holds every spade; team 1 holds none.
        val hands = listOf(
            (0 until 8).map { Card(Rank.entries.filter { r -> r.order >= Rank.EIGHT.order }[it % 7], strong) },
            List(8) { Card(Rank.EIGHT, Suit.HEARTS) },
            List(8) { Card(Rank.NINE, Suit.HEARTS) },
            List(8) { Card(Rank.TEN, Suit.HEARTS) },
        )
        var state = playingState(hands, trump = strong, bid = bid, bidder = if (bidderTakesAll) 0 else 0)
        if (!bidderTakesAll) {
            // Give the spades to the other side instead.
            state = state.copy(
                hands = listOf(hands[1], hands[0], hands[2], hands[3]),
                handCounts = hands.map { it.size },
            )
        }
        var guard = 0
        while (state.phase == KaiserPhase.PLAYING && guard++ < 100) {
            val seat = KaiserRules.currentSeat(state)!!
            val legal = KaiserRules.legalMoves(state, seat)
            state = KaiserRules.applyMove(state, seat, legal.first())
        }
        return state
    }

    @Test
    fun `defenders keep what they take`() {
        var state = KaiserRules.initialState(config())
        var guard = 0
        while (state.phase != KaiserPhase.HAND_OVER && !KaiserRules.isFinished(state) && guard++ < 500) {
            val seat = KaiserRules.currentSeat(state) ?: break
            val legal = KaiserRules.legalMoves(state, seat)
            state = KaiserRules.applyMove(state, seat, legal.first())
        }
        assertTrue("a hand was scored", state.scores.any { it != 0 } || state.handPoints.any { it != 0 })
    }

    // -- Full games ---------------------------------------------------------

    @Test
    fun `random legal play always reaches a winner`() {
        repeat(15) { iteration ->
            val random = Random(iteration.toLong())
            var state = KaiserRules.initialState(config(seed = iteration.toLong()))
            var guard = 0
            while (!KaiserRules.isFinished(state) && guard++ < 8000) {
                val seat = KaiserRules.currentSeat(state)
                if (seat == null) {
                    state = KaiserRules.nextHand(state)
                    continue
                }
                val legal = KaiserRules.legalMoves(state, seat)
                assertTrue("seat $seat had no legal move in ${state.phase}", legal.isNotEmpty())
                state = KaiserRules.applyMove(state, seat, legal[random.nextInt(legal.size)])
            }
            assertTrue("game $iteration did not finish", KaiserRules.isFinished(state))
        }
    }

    @Test
    fun `ai play always reaches a winner`() {
        val ai = KaiserAi()
        repeat(8) { iteration ->
            var state = KaiserRules.initialState(config(seed = 600L + iteration))
            var guard = 0
            while (!KaiserRules.isFinished(state) && guard++ < 8000) {
                val seat = KaiserRules.currentSeat(state)
                if (seat == null) {
                    state = KaiserRules.nextHand(state)
                    continue
                }
                val legal = KaiserRules.legalMoves(state, seat)
                val move = ai.chooseMove(state, seat, legal)
                assertTrue("ai returned an illegal move $move", legal.contains(move))
                state = KaiserRules.applyMove(state, seat, move)
            }
            assertTrue("ai game did not finish", KaiserRules.isFinished(state))
        }
    }

    // -- Redaction and wire format -----------------------------------------

    @Test
    fun `a seat view hides every other hand`() {
        val state = KaiserRules.initialState(config())
        val view = KaiserRules.viewFor(state, seat = 3)
        assertEquals(state.hands[3], view.hands[3])
        assertTrue(view.hands.filterIndexed { i, _ -> i != 3 }.all { it.isEmpty() })
        assertEquals(listOf(8, 8, 8, 8), view.handCounts)
    }

    @Test
    fun `state and moves survive a round trip`() {
        val state = KaiserRules.initialState(config())
        assertEquals(state, KaiserRules.decodeState(KaiserRules.encodeState(state)))
        listOf(
            PassBid,
            MakeBid(Bid(7, noTrump = true)),
            NameTrump(Suit.HEARTS),
            NameTrump(null),
            PlayCard(FIVE_OF_HEARTS),
        ).forEach { assertEquals(it, KaiserRules.decodeMove(KaiserRules.encodeMove(it))) }
    }
}
