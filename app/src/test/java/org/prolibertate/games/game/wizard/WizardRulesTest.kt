package org.prolibertate.games.game.wizard

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

class WizardRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(options: WizardOptions = WizardOptions(), seed: Long = 11L) = TableConfig(
        gameId = "wizard",
        seats = (0 until options.playerCount).map {
            PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it)
        },
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    private fun wizardOf(suit: Suit) = Card(Rank.WIZARD, suit)
    private fun jesterOf(suit: Suit) = Card(Rank.JESTER, suit)

    // -- The deck -----------------------------------------------------------

    @Test
    fun `the deck is sixty cards with four wizards and four jesters`() {
        val deck = Decks.wizard()
        assertEquals(60, deck.size)
        assertEquals("no duplicates", 60, deck.distinct().size)
        assertEquals(4, deck.count { isWizardCard(it) })
        assertEquals(4, deck.count { isJester(it) })
        assertEquals(52, deck.count { it.rank.standard })
    }

    @Test
    fun `the whole deck is dealt out over the rounds`() {
        assertEquals(20, WizardOptions(playerCount = 3).totalRounds())
        assertEquals(15, WizardOptions(playerCount = 4).totalRounds())
        assertEquals(12, WizardOptions(playerCount = 5).totalRounds())
        assertEquals(10, WizardOptions(playerCount = 6).totalRounds())
        // A short game stops early rather than inventing extra rounds.
        assertEquals(5, WizardOptions(playerCount = 4, rounds = 5).totalRounds())
        assertEquals(15, WizardOptions(playerCount = 4, rounds = 99).totalRounds())
    }

    // -- Dealing and trump --------------------------------------------------

    @Test
    fun `round one deals a single card each and turns one for trump`() {
        val state = WizardRules.initialState(config())
        assertEquals(WizardPhase.BIDDING, state.phase)
        state.hands.forEach { assertEquals(1, it.size) }
        assertEquals(1, state.cardsThisRound)
        assertEquals("something was turned", true, state.trumpCard != null)
    }

    @Test
    fun `the last round turns nothing and is played without trump`() {
        // Four players, fifteen rounds: the last one uses every card in the deck.
        var state = WizardRules.initialState(config())
        var guard = 0
        while (state.round < 14 && guard++ < 40) {
            state = playRoundOut(state)
            if (state.phase == WizardPhase.ROUND_OVER) state = WizardRules.nextRound(state)
        }
        assertEquals(14, state.round)
        assertEquals(15, state.hands[0].size)
        assertNull("nothing left to turn", state.trumpCard)
        assertNull("so there is no trump", state.trump)
    }

    @Test
    fun `a turned jester means no trump`() {
        // Search seeds until one turns a jester rather than asserting on luck.
        val seed = (1L..400L).firstOrNull { s ->
            val state = WizardRules.initialState(config(seed = s))
            state.trumpCard?.let { isJester(it) } == true
        }
        requireNotNull(seed) { "no seed in range turned a jester" }
        val state = WizardRules.initialState(config(seed = seed))
        assertNull(state.trump)
        assertTrue("bidding starts left of the dealer", state.turn == (state.dealer + 1) % 4)
    }

    @Test
    fun `a turned wizard hands the dealer the choice of trump`() {
        val seed = (1L..400L).firstOrNull { s ->
            val state = WizardRules.initialState(config(seed = s))
            state.trumpCard?.let { isWizardCard(it) } == true
        }
        requireNotNull(seed) { "no seed in range turned a wizard" }
        var state = WizardRules.initialState(config(seed = seed))

        assertNull("trump is not set yet", state.trump)
        assertEquals("the dealer is on turn", state.dealer, state.turn)
        val choices = WizardRules.legalMoves(state, state.dealer)
        assertEquals(Suit.entries.map { ChooseTrump(it) }, choices)

        state = WizardRules.applyMove(state, state.dealer, ChooseTrump(Suit.HEARTS))
        assertEquals(Suit.HEARTS, state.trump)
        assertEquals("then bidding opens on the left", (state.dealer + 1) % 4, state.turn)
        assertTrue(WizardRules.legalMoves(state, state.turn).all { it is MakeBid })
    }

    // -- Bidding ------------------------------------------------------------

    @Test
    fun `you may bid anything from zero up to the tricks available`() {
        val state = WizardRules.initialState(config())
        val bids = WizardRules.legalMoves(state, state.turn).filterIsInstance<MakeBid>()
        assertEquals(listOf(0, 1), bids.map { it.tricks })
    }

    @Test
    fun `screw the dealer forbids the bid that would balance the book`() {
        var state = WizardRules.initialState(
            config(WizardOptions(screwTheDealer = true, rounds = 4), seed = 3L)
        )
        // Get to a round with room to bid, then walk round to the dealer.
        while (state.cardsThisRound < 3) {
            state = playRoundOut(state)
            state = WizardRules.nextRound(state)
        }
        if (state.trumpCard?.let { isWizardCard(it) } == true) {
            state = WizardRules.applyMove(state, state.dealer, ChooseTrump(Suit.SPADES))
        }
        val available = state.cardsThisRound
        var bidSoFar = 0
        while (state.turn != state.dealer) {
            val seat = state.turn
            state = WizardRules.applyMove(state, seat, MakeBid(1))
            bidSoFar += 1
        }
        val dealerBids = WizardRules.legalMoves(state, state.dealer)
            .filterIsInstance<MakeBid>().map { it.tricks }
        assertFalse("the balancing bid is off the table", dealerBids.contains(available - bidSoFar))
        assertEquals(available, dealerBids.size) // one fewer than the 0..available range
    }

    @Test
    fun `play starts once everyone has bid`() {
        var state = WizardRules.initialState(config())
        if (state.trumpCard?.let { isWizardCard(it) } == true) {
            state = WizardRules.applyMove(state, state.dealer, ChooseTrump(Suit.SPADES))
        }
        repeat(4) { state = WizardRules.applyMove(state, state.turn, MakeBid(0)) }
        assertEquals(WizardPhase.PLAYING, state.phase)
        assertEquals("the player left of the dealer leads", (state.dealer + 1) % 4, state.turn)
    }

    // -- Trick ranking ------------------------------------------------------

    @Test
    fun `a wizard beats everything including trump`() {
        val trump = Suit.SPADES
        assertTrue(
            trickStrength(wizardOf(Suit.CLUBS), 1, trump, Suit.HEARTS) >
                trickStrength(Card(Rank.ACE, trump), 0, trump, Suit.HEARTS)
        )
    }

    @Test
    fun `the first wizard down takes the trick`() {
        assertTrue(
            trickStrength(wizardOf(Suit.CLUBS), 0, Suit.SPADES, null) >
                trickStrength(wizardOf(Suit.HEARTS), 3, Suit.SPADES, null)
        )
    }

    @Test
    fun `a jester loses to everything, even an off-suit discard`() {
        val trump = Suit.SPADES
        val jester = trickStrength(jesterOf(Suit.CLUBS), 1, trump, Suit.HEARTS)
        val offSuit = trickStrength(Card(Rank.TWO, Suit.DIAMONDS), 2, trump, Suit.HEARTS)
        assertTrue(offSuit > jester)
        assertTrue(trickStrength(Card(Rank.TWO, Suit.HEARTS), 2, trump, Suit.HEARTS) > offSuit)
    }

    @Test
    fun `a trick of nothing but jesters goes to the first one played`() {
        val hands = List(4) { seat -> listOf(jesterOf(Suit.entries[seat])) }
        var state = playingState(hands, trump = Suit.SPADES, bids = listOf(0, 0, 0, 0))
        repeat(4) {
            val seat = WizardRules.currentSeat(state)!!
            state = WizardRules.applyMove(state, seat, PlayCard(state.hands[seat].first()))
        }
        assertEquals(0, state.lastTrickWinner)
    }

    @Test
    fun `a led jester leaves the suit to the next ordinary card`() {
        val trick = listOf(
            PlayedCard(0, jesterOf(Suit.CLUBS)),
            PlayedCard(1, Card(Rank.NINE, Suit.HEARTS)),
        )
        assertEquals(Suit.HEARTS, ledSuitOf(trick))
    }

    @Test
    fun `a wizard before any suit is established means anything may be played`() {
        assertNull(ledSuitOf(listOf(PlayedCard(0, wizardOf(Suit.CLUBS)))))
        assertNull(
            ledSuitOf(
                listOf(
                    PlayedCard(0, jesterOf(Suit.CLUBS)),
                    PlayedCard(1, wizardOf(Suit.HEARTS)),
                    PlayedCard(2, Card(Rank.NINE, Suit.SPADES)),
                )
            )
        )
    }

    // -- Following suit -----------------------------------------------------

    @Test
    fun `you must follow suit but may always throw a wizard or a jester`() {
        val hand = listOf(
            Card(Rank.NINE, Suit.HEARTS),
            Card(Rank.ACE, Suit.CLUBS),
            wizardOf(Suit.SPADES),
            jesterOf(Suit.DIAMONDS),
        )
        val state = playingState(
            hands = listOf(emptyList(), hand, emptyList(), emptyList()),
            trump = Suit.SPADES,
            bids = listOf(0, 1, 0, 0),
            turn = 1,
        ).copy(trick = listOf(PlayedCard(0, Card(Rank.KING, Suit.HEARTS))))

        val playable = WizardRules.playableCards(state, 1)
        assertTrue(playable.contains(Card(Rank.NINE, Suit.HEARTS)))
        assertTrue(playable.contains(wizardOf(Suit.SPADES)))
        assertTrue(playable.contains(jesterOf(Suit.DIAMONDS)))
        assertFalse("the ace of clubs is not a heart", playable.contains(Card(Rank.ACE, Suit.CLUBS)))
    }

    @Test
    fun `with none of the led suit you may play anything`() {
        val hand = listOf(Card(Rank.ACE, Suit.CLUBS), Card(Rank.TWO, Suit.SPADES))
        val state = playingState(
            hands = listOf(emptyList(), hand, emptyList(), emptyList()),
            trump = Suit.SPADES,
            bids = listOf(0, 1, 0, 0),
            turn = 1,
        ).copy(trick = listOf(PlayedCard(0, Card(Rank.KING, Suit.HEARTS))))
        assertEquals(hand, WizardRules.playableCards(state, 1))
    }

    @Test
    fun `a finished trick is held on the table`() {
        val hands = listOf(
            listOf(Card(Rank.ACE, Suit.CLUBS), Card(Rank.KING, Suit.CLUBS)),
            listOf(Card(Rank.EIGHT, Suit.CLUBS), Card(Rank.NINE, Suit.CLUBS)),
            listOf(Card(Rank.TEN, Suit.CLUBS), Card(Rank.QUEEN, Suit.CLUBS)),
            listOf(Card(Rank.JACK, Suit.CLUBS), Card(Rank.TWO, Suit.HEARTS)),
        )
        var state = playingState(hands, trump = Suit.SPADES, bids = listOf(1, 0, 0, 1))
        repeat(4) {
            val seat = WizardRules.currentSeat(state)!!
            state = WizardRules.applyMove(state, seat, PlayCard(state.hands[seat].first()))
        }
        assertTrue(state.trick.isEmpty())
        assertEquals("all four held for display", 4, state.completedTrick.size)
        assertEquals("seat 0 took it with the ace", 0, state.lastTrickWinner)
    }

    // -- Scoring ------------------------------------------------------------

    @Test
    fun `an exact bid pays twenty plus ten a trick`() {
        assertEquals(20, scoreRound(bid = 0, won = 0))
        assertEquals(30, scoreRound(bid = 1, won = 1))
        assertEquals(50, scoreRound(bid = 3, won = 3))
    }

    @Test
    fun `missing costs ten for every trick out either way`() {
        assertEquals(-10, scoreRound(bid = 0, won = 1))
        assertEquals(-30, scoreRound(bid = 3, won = 0))
        assertEquals(-20, scoreRound(bid = 1, won = 3))
    }

    @Test
    fun `the round is scored when the last trick is taken`() {
        val hands = listOf(
            listOf(Card(Rank.ACE, Suit.CLUBS)),
            listOf(Card(Rank.EIGHT, Suit.CLUBS)),
            listOf(Card(Rank.NINE, Suit.CLUBS)),
            listOf(Card(Rank.TEN, Suit.CLUBS)),
        )
        var state = playingState(hands, trump = Suit.SPADES, bids = listOf(1, 0, 0, 1))
        repeat(4) {
            val seat = WizardRules.currentSeat(state)!!
            state = WizardRules.applyMove(state, seat, PlayCard(state.hands[seat].first()))
        }
        assertEquals(WizardPhase.ROUND_OVER, state.phase)
        assertEquals("bid one and took it", 30, state.roundScores[0])
        assertEquals("bid none and took none", 20, state.roundScores[1])
        assertEquals("bid one and took none", -10, state.roundScores[3])
        assertEquals(state.roundScores, state.scores)
        assertNull("no seat is on turn between rounds", WizardRules.currentSeat(state))
    }

    @Test
    fun `the next round deals one more card and moves the deal on`() {
        var state = WizardRules.initialState(config())
        val dealer = state.dealer
        state = playRoundOut(state)
        assertEquals(WizardPhase.ROUND_OVER, state.phase)

        val next = WizardRules.nextRound(state)
        assertEquals(1, next.round)
        assertEquals(2, next.cardsThisRound)
        next.hands.forEach { assertEquals(2, it.size) }
        assertEquals((dealer + 1) % 4, next.dealer)
        assertEquals("scores carry over", state.scores, next.scores)
    }

    // -- Full games ---------------------------------------------------------

    @Test
    fun `random legal play always reaches the end of the game`() {
        for (players in 3..6) {
            repeat(3) { iteration ->
                val random = Random(iteration.toLong())
                val options = WizardOptions(playerCount = players)
                var state = WizardRules.initialState(
                    config(options, seed = 100L * players + iteration)
                )
                var guard = 0
                while (!WizardRules.isFinished(state) && guard++ < 20_000) {
                    val seat = WizardRules.currentSeat(state)
                    if (seat == null) {
                        state = WizardRules.nextRound(state)
                        continue
                    }
                    val legal = WizardRules.legalMoves(state, seat)
                    assertTrue("seat $seat had no move in ${state.phase}", legal.isNotEmpty())
                    state = WizardRules.applyMove(state, seat, legal[random.nextInt(legal.size)])
                }
                assertTrue("$players-handed game $iteration did not finish",
                    WizardRules.isFinished(state))
                assertEquals(options.totalRounds() - 1, state.round)
            }
        }
    }

    @Test
    fun `ai play always reaches the end of the game`() {
        val ai = WizardAi()
        repeat(4) { iteration ->
            var state = WizardRules.initialState(config(seed = 700L + iteration))
            var guard = 0
            while (!WizardRules.isFinished(state) && guard++ < 20_000) {
                val seat = WizardRules.currentSeat(state)
                if (seat == null) {
                    state = WizardRules.nextRound(state)
                    continue
                }
                val legal = WizardRules.legalMoves(state, seat)
                val move = ai.chooseMove(state, seat, legal)
                assertTrue("ai returned an illegal move $move", legal.contains(move))
                state = WizardRules.applyMove(state, seat, move)
            }
            assertTrue("ai game did not finish", WizardRules.isFinished(state))
        }
    }

    @Test
    fun `the ai hits its bid more often than chance would`() {
        val ai = WizardAi()
        var exact = 0
        var rounds = 0
        repeat(6) { iteration ->
            var state = WizardRules.initialState(config(seed = 900L + iteration))
            var guard = 0
            while (!WizardRules.isFinished(state) && guard++ < 20_000) {
                val seat = WizardRules.currentSeat(state)
                if (seat == null) {
                    exact += state.roundScores.count { it > 0 }
                    rounds += state.roundScores.size
                    state = WizardRules.nextRound(state)
                    continue
                }
                val legal = WizardRules.legalMoves(state, seat)
                state = WizardRules.applyMove(state, seat, ai.chooseMove(state, seat, legal))
            }
        }
        // Blind guessing over a fifteen-round game lands well under a fifth of
        // the time; anything paying attention should clear a quarter.
        assertTrue("only $exact of $rounds bids were exact", exact * 4 >= rounds)
    }

    // -- Redaction and wire format -----------------------------------------

    @Test
    fun `a seat view hides every other hand`() {
        val state = WizardRules.initialState(config(WizardOptions(playerCount = 4)))
        val view = WizardRules.viewFor(state, seat = 2)
        assertEquals(state.hands[2], view.hands[2])
        assertTrue(view.hands.filterIndexed { i, _ -> i != 2 }.all { it.isEmpty() })
        assertEquals(listOf(1, 1, 1, 1), view.handCounts)
    }

    @Test
    fun `state and moves survive a round trip`() {
        val state = WizardRules.initialState(config())
        assertEquals(state, WizardRules.decodeState(WizardRules.encodeState(state)))
        listOf(
            MakeBid(0),
            MakeBid(3),
            ChooseTrump(Suit.DIAMONDS),
            PlayCard(wizardOf(Suit.SPADES)),
            PlayCard(jesterOf(Suit.HEARTS)),
        ).forEach { assertEquals(it, WizardRules.decodeMove(WizardRules.encodeMove(it))) }
    }

    // -- Helpers ------------------------------------------------------------

    private fun playingState(
        hands: List<List<Card>>,
        trump: Suit?,
        bids: List<Int?>,
        turn: Int = 0,
    ) = WizardState(
        options = WizardOptions(playerCount = hands.size),
        seed = 1L,
        round = hands.first().size - 1,
        dealer = hands.size - 1,
        phase = WizardPhase.PLAYING,
        hands = hands,
        handCounts = hands.map { it.size },
        trumpCard = null,
        trump = trump,
        bids = bids,
        tricksWon = List(hands.size) { 0 },
        turn = turn,
        trick = emptyList(),
        completedTrick = emptyList(),
        leader = turn,
        lastTrickWinner = null,
        scores = List(hands.size) { 0 },
        roundScores = List(hands.size) { 0 },
        log = emptyList(),
    )

    /** Bids zero all round and plays the first legal card until the round ends. */
    private fun playRoundOut(start: WizardState): WizardState {
        var state = start
        var guard = 0
        while (state.phase == WizardPhase.BIDDING || state.phase == WizardPhase.PLAYING) {
            if (guard++ > 5000) break
            val seat = WizardRules.currentSeat(state)!!
            val legal = WizardRules.legalMoves(state, seat)
            state = WizardRules.applyMove(state, seat, legal.first())
        }
        return state
    }
}
