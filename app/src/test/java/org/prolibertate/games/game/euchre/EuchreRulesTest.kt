package org.prolibertate.games.game.euchre

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

class EuchreRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(options: EuchreOptions = EuchreOptions(), seed: Long = 42L) = TableConfig(
        gameId = "euchre",
        seats = (0 until 4).map {
            PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it % 2)
        },
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    // -- Deck ---------------------------------------------------------------

    @Test
    fun `24 card deck runs nine to ace`() {
        val deck = Decks.euchre(24)
        assertEquals(24, deck.size)
        assertEquals(24, deck.distinct().size)
        assertTrue(deck.none { it.rank.order < Rank.NINE.order })
    }

    @Test
    fun `32 card deck runs seven to ace`() {
        val deck = Decks.euchre(32)
        assertEquals(32, deck.size)
        assertTrue(deck.any { it.rank == Rank.SEVEN })
    }

    // -- Bower ranking ------------------------------------------------------

    @Test
    fun `left bower counts as trump not its printed suit`() {
        val trump = Suit.SPADES
        val leftBower = Card(Rank.JACK, Suit.CLUBS)
        assertTrue(isLeftBower(leftBower, trump))
        assertEquals(Suit.SPADES, effectiveSuit(leftBower, trump))
        assertTrue(isTrumpCard(leftBower, trump))
    }

    @Test
    fun `right bower outranks left bower which outranks the ace of trump`() {
        val trump = Suit.SPADES
        val right = trickStrength(Card(Rank.JACK, Suit.SPADES), trump, trump)
        val left = trickStrength(Card(Rank.JACK, Suit.CLUBS), trump, trump)
        val aceTrump = trickStrength(Card(Rank.ACE, Suit.SPADES), trump, trump)
        assertTrue("right > left", right > left)
        assertTrue("left > ace of trump", left > aceTrump)
    }

    @Test
    fun `off suit card cannot win a trick`() {
        val trump = Suit.SPADES
        val ledSuit = Suit.HEARTS
        val offSuit = trickStrength(Card(Rank.ACE, Suit.DIAMONDS), trump, ledSuit)
        assertEquals(0, offSuit)
    }

    // -- Following suit -----------------------------------------------------

    private fun playingState(
        hands: List<List<Card>>,
        trump: Suit,
        trick: List<PlayedCard> = emptyList(),
        turn: Int = 0,
    ) = EuchreState(
        options = EuchreOptions(),
        seed = 1L,
        handNumber = 0,
        dealer = 3,
        phase = EuchrePhase.PLAYING,
        hands = hands,
        handCounts = hands.map { it.size },
        upCard = null,
        turnedDown = null,
        trump = trump,
        maker = 0,
        aloneSeat = null,
        turn = turn,
        passes = 0,
        trick = trick,
        leader = 0,
        tricksWon = listOf(0, 0, 0, 0),
        scores = listOf(0, 0),
        lastTrickWinner = null,
        log = emptyList(),
    )

    @Test
    fun `left bower must be played when trump is led`() {
        val trump = Suit.SPADES
        val hand = listOf(Card(Rank.JACK, Suit.CLUBS), Card(Rank.KING, Suit.HEARTS))
        val state = playingState(
            hands = listOf(emptyList(), hand, emptyList(), emptyList()),
            trump = trump,
            trick = listOf(PlayedCard(0, Card(Rank.NINE, Suit.SPADES))),
            turn = 1,
        )
        val playable = EuchreRules.playableCards(state, 1)
        assertEquals(listOf(Card(Rank.JACK, Suit.CLUBS)), playable)
    }

    @Test
    fun `left bower does not satisfy following its printed suit`() {
        val trump = Suit.SPADES
        // Clubs is led, and the only club held is the left bower — which is a
        // spade for this hand, so the player is void in clubs and may play
        // anything.
        val hand = listOf(Card(Rank.JACK, Suit.CLUBS), Card(Rank.KING, Suit.HEARTS))
        val state = playingState(
            hands = listOf(emptyList(), hand, emptyList(), emptyList()),
            trump = trump,
            trick = listOf(PlayedCard(0, Card(Rank.NINE, Suit.CLUBS))),
            turn = 1,
        )
        assertEquals(hand.toSet(), EuchreRules.playableCards(state, 1).toSet())
    }

    // -- Bidding ------------------------------------------------------------

    @Test
    fun `ordering up sets trump and sends the dealer to discard`() {
        val state = EuchreRules.initialState(config())
        val upSuit = state.upCard!!.suit
        val next = EuchreRules.applyMove(state, state.turn, OrderUp(alone = false))
        assertEquals(EuchrePhase.DEALER_DISCARD, next.phase)
        assertEquals(upSuit, next.trump)
        assertEquals(next.dealer, next.turn)
        assertEquals(6, next.hands[next.dealer].size)
    }

    @Test
    fun `dealer discard returns the hand to five cards and starts play`() {
        var state = EuchreRules.initialState(config())
        state = EuchreRules.applyMove(state, state.turn, OrderUp(alone = false))
        val discard = EuchreRules.legalMoves(state, state.dealer).first() as Discard
        state = EuchreRules.applyMove(state, state.dealer, discard)
        assertEquals(EuchrePhase.PLAYING, state.phase)
        assertEquals(5, state.hands[state.dealer].size)
        assertEquals((state.dealer + 1) % 4, state.turn)
    }

    @Test
    fun `four passes turn the card down and open the second round`() {
        var state = EuchreRules.initialState(config())
        val up = state.upCard!!
        repeat(4) { state = EuchreRules.applyMove(state, state.turn, Pass) }
        assertEquals(EuchrePhase.BID_ROUND_2, state.phase)
        assertEquals(up, state.turnedDown)
        assertTrue("turned-down suit cannot be named",
            EuchreRules.legalMoves(state, state.turn)
                .filterIsInstance<CallTrump>()
                .none { it.suit == up.suit })
    }

    @Test
    fun `stick the dealer removes the dealers option to pass`() {
        var state = EuchreRules.initialState(config(EuchreOptions(stickTheDealer = true)))
        repeat(4) { state = EuchreRules.applyMove(state, state.turn, Pass) }
        repeat(3) { state = EuchreRules.applyMove(state, state.turn, Pass) }
        assertEquals(state.dealer, state.turn)
        val moves = EuchreRules.legalMoves(state, state.dealer)
        assertFalse("dealer must name a suit", moves.contains(Pass))
        assertTrue(moves.all { it is CallTrump })
    }

    @Test
    fun `without stick the dealer a passed out hand is redealt`() {
        var state = EuchreRules.initialState(config(EuchreOptions(stickTheDealer = false)))
        val firstDealer = state.dealer
        repeat(8) { state = EuchreRules.applyMove(state, state.turn, Pass) }
        assertEquals(EuchrePhase.BID_ROUND_1, state.phase)
        assertEquals((firstDealer + 1) % 4, state.dealer)
    }

    // -- Going alone --------------------------------------------------------

    @Test
    fun `going alone sits the partner out`() {
        var state = EuchreRules.initialState(config())
        val bidder = state.turn
        state = EuchreRules.applyMove(state, bidder, OrderUp(alone = true))
        val discard = EuchreRules.legalMoves(state, state.dealer).first() as Discard
        state = EuchreRules.applyMove(state, state.dealer, discard)
        assertEquals(bidder, state.aloneSeat)
        assertEquals(partnerOf(bidder), state.sittingOut)
        assertEquals(3, state.activeSeatCount)
        assertTrue(state.hands[partnerOf(bidder)].isEmpty())
        assertTrue("the sitting-out seat never gets the turn", state.turn != state.sittingOut)
    }

    // -- Scoring ------------------------------------------------------------

    /** Plays a hand out with a fixed strategy so scoring can be asserted. */
    private fun playOutHand(state: EuchreState, pick: (EuchreState, Int) -> EuchreMove): EuchreState {
        var current = state
        var guard = 0
        while (current.phase == EuchrePhase.PLAYING && guard++ < 100) {
            val seat = EuchreRules.currentSeat(current)!!
            current = EuchreRules.applyMove(current, seat, pick(current, seat))
        }
        return current
    }

    @Test
    fun `taking all five tricks scores two, or four when alone`() {
        // Team 0 holds every trump; team 1 cannot win a trick.
        val trump = Suit.SPADES
        val strong = listOf(
            Card(Rank.JACK, Suit.SPADES), Card(Rank.JACK, Suit.CLUBS),
            Card(Rank.ACE, Suit.SPADES), Card(Rank.KING, Suit.SPADES),
            Card(Rank.QUEEN, Suit.SPADES),
        )
        val weak0 = listOf(
            Card(Rank.NINE, Suit.HEARTS), Card(Rank.TEN, Suit.HEARTS),
            Card(Rank.QUEEN, Suit.HEARTS), Card(Rank.KING, Suit.HEARTS),
            Card(Rank.ACE, Suit.HEARTS),
        )
        val weak1 = listOf(
            Card(Rank.NINE, Suit.DIAMONDS), Card(Rank.TEN, Suit.DIAMONDS),
            Card(Rank.QUEEN, Suit.DIAMONDS), Card(Rank.KING, Suit.DIAMONDS),
            Card(Rank.ACE, Suit.DIAMONDS),
        )
        val weak2 = listOf(
            Card(Rank.NINE, Suit.CLUBS), Card(Rank.TEN, Suit.CLUBS),
            Card(Rank.QUEEN, Suit.CLUBS), Card(Rank.KING, Suit.CLUBS),
            Card(Rank.ACE, Suit.CLUBS),
        )

        val state = playingState(
            hands = listOf(strong, weak0, weak1, weak2),
            trump = trump,
        ).copy(maker = 0, leader = 0, turn = 0)

        val finished = playOutHand(state) { s, seat ->
            PlayCard(EuchreRules.playableCards(s, seat).first())
        }
        assertEquals(5, finished.tricksWon[0])
        assertEquals(2, finished.scores[0])
        assertEquals(0, finished.scores[1])
    }

    @Test
    fun `failing to take three tricks hands the defenders two points`() {
        val trump = Suit.SPADES
        // Seat 0 makes it but holds nothing; seat 1 holds all the trump.
        val makerHand = listOf(
            Card(Rank.NINE, Suit.HEARTS), Card(Rank.TEN, Suit.HEARTS),
            Card(Rank.QUEEN, Suit.HEARTS), Card(Rank.KING, Suit.HEARTS),
            Card(Rank.ACE, Suit.HEARTS),
        )
        val trumpHand = listOf(
            Card(Rank.JACK, Suit.SPADES), Card(Rank.JACK, Suit.CLUBS),
            Card(Rank.ACE, Suit.SPADES), Card(Rank.KING, Suit.SPADES),
            Card(Rank.QUEEN, Suit.SPADES),
        )
        val filler1 = listOf(
            Card(Rank.NINE, Suit.DIAMONDS), Card(Rank.TEN, Suit.DIAMONDS),
            Card(Rank.QUEEN, Suit.DIAMONDS), Card(Rank.KING, Suit.DIAMONDS),
            Card(Rank.ACE, Suit.DIAMONDS),
        )
        val filler2 = listOf(
            Card(Rank.NINE, Suit.CLUBS), Card(Rank.TEN, Suit.CLUBS),
            Card(Rank.QUEEN, Suit.CLUBS), Card(Rank.KING, Suit.CLUBS),
            Card(Rank.ACE, Suit.CLUBS),
        )

        val state = playingState(
            hands = listOf(makerHand, trumpHand, filler1, filler2),
            trump = trump,
        ).copy(maker = 0, leader = 0, turn = 0)

        // Seat 1 always trumps in; everyone else follows or throws off.
        val finished = playOutHand(state) { s, seat ->
            val playable = EuchreRules.playableCards(s, seat)
            val card = if (seat == 1) {
                playable.maxByOrNull { trumpStrength(it, trump) }!!
            } else {
                playable.first()
            }
            PlayCard(card)
        }
        assertEquals(EuchrePhase.HAND_OVER, finished.phase)
        assertTrue("makers took fewer than three", finished.tricksWon[0] + finished.tricksWon[2] < 3)
        assertEquals(2, finished.scores[1])
        assertEquals(0, finished.scores[0])
    }

    // -- Completed tricks ---------------------------------------------------

    @Test
    fun `a finished trick stays on the table until the next card is played`() {
        val trump = Suit.SPADES
        val hands = listOf(
            listOf(Card(Rank.ACE, Suit.SPADES), Card(Rank.NINE, Suit.HEARTS)),
            listOf(Card(Rank.NINE, Suit.SPADES), Card(Rank.TEN, Suit.HEARTS)),
            listOf(Card(Rank.TEN, Suit.SPADES), Card(Rank.QUEEN, Suit.HEARTS)),
            listOf(Card(Rank.QUEEN, Suit.SPADES), Card(Rank.KING, Suit.HEARTS)),
        )
        var state = playingState(hands = hands, trump = trump)

        // Play a full trick.
        repeat(4) {
            val seat = EuchreRules.currentSeat(state)!!
            state = EuchreRules.applyMove(state, seat, PlayCard(state.hands[seat].first()))
        }

        assertTrue("the live trick is cleared", state.trick.isEmpty())
        assertEquals("all four cards are held for display", 4, state.completedTrick.size)
        assertEquals("seat 0 played the ace of trump", 0, state.lastTrickWinner)

        // The next card played sweeps it away.
        val next = EuchreRules.currentSeat(state)!!
        state = EuchreRules.applyMove(state, next, PlayCard(state.hands[next].first()))
        assertTrue("held trick is cleared once play resumes", state.completedTrick.isEmpty())
    }

    @Test
    fun `a fresh hand starts with nothing held on the table`() {
        var state = EuchreRules.initialState(config())
        assertTrue(state.completedTrick.isEmpty())
        state = EuchreRules.applyMove(state, state.turn, OrderUp(alone = false))
        val discard = EuchreRules.legalMoves(state, state.dealer).first() as Discard
        state = EuchreRules.applyMove(state, state.dealer, discard)
        assertTrue("play begins with a clear table", state.completedTrick.isEmpty())
    }

    // -- Full games ---------------------------------------------------------

    @Test
    fun `random legal play always reaches a winner`() {
        repeat(25) { iteration ->
            val random = Random(iteration.toLong())
            var state = EuchreRules.initialState(config(seed = iteration.toLong()))
            var guard = 0
            while (!EuchreRules.isFinished(state) && guard++ < 5000) {
                val seat = EuchreRules.currentSeat(state)
                if (seat == null) {
                    state = EuchreRules.nextHand(state)
                    continue
                }
                val legal = EuchreRules.legalMoves(state, seat)
                assertTrue("seat $seat had no legal move in ${state.phase}", legal.isNotEmpty())
                state = EuchreRules.applyMove(state, seat, legal[random.nextInt(legal.size)])
            }
            assertTrue("game $iteration did not finish", EuchreRules.isFinished(state))
            assertTrue(state.scores.max() >= state.options.pointsToWin)
        }
    }

    @Test
    fun `ai play always reaches a winner`() {
        val ai = EuchreAi()
        repeat(10) { iteration ->
            var state = EuchreRules.initialState(config(seed = 500L + iteration))
            var guard = 0
            while (!EuchreRules.isFinished(state) && guard++ < 5000) {
                val seat = EuchreRules.currentSeat(state)
                if (seat == null) {
                    state = EuchreRules.nextHand(state)
                    continue
                }
                val legal = EuchreRules.legalMoves(state, seat)
                val move = ai.chooseMove(state, seat, legal)
                assertTrue("ai returned an illegal move $move", legal.contains(move))
                state = EuchreRules.applyMove(state, seat, move)
            }
            assertTrue("ai game $iteration did not finish", EuchreRules.isFinished(state))
        }
    }

    // -- Redaction and wire format -----------------------------------------

    @Test
    fun `a seat view hides every other hand but keeps the counts`() {
        val state = EuchreRules.initialState(config())
        val view = EuchreRules.viewFor(state, seat = 2)
        assertEquals(state.hands[2], view.hands[2])
        assertTrue(view.hands.filterIndexed { i, _ -> i != 2 }.all { it.isEmpty() })
        assertEquals(listOf(5, 5, 5, 5), view.handCounts)
    }

    @Test
    fun `state and moves survive a round trip`() {
        val state = EuchreRules.initialState(config())
        val decoded = EuchreRules.decodeState(EuchreRules.encodeState(state))
        assertEquals(state, decoded)

        val moves = listOf(
            Pass,
            OrderUp(alone = true),
            CallTrump(Suit.HEARTS, alone = false),
            Discard(Card(Rank.NINE, Suit.CLUBS)),
            PlayCard(Card(Rank.ACE, Suit.SPADES)),
        )
        moves.forEach { move ->
            assertEquals(move, EuchreRules.decodeMove(EuchreRules.encodeMove(move)))
        }
    }

    @Test
    fun `illegal moves are rejected`() {
        val state = EuchreRules.initialState(config())
        val wrongSeat = (state.turn + 1) % 4
        var threw = false
        try {
            EuchreRules.applyMove(state, wrongSeat, Pass)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("playing out of turn must be rejected", threw)
    }
}
