package org.prolibertate.games.game.hearts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig

class HeartsRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(options: HeartsOptions = HeartsOptions(), seed: Long = 7L) = TableConfig(
        gameId = "hearts",
        seats = (0 until options.playerCount).map {
            PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it)
        },
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    private fun c(rank: Rank, suit: Suit) = Card(rank, suit)

    /** A state part way through a round, with passing already done. */
    private fun playing(
        hands: List<List<Card>>,
        turn: Int = 0,
        leader: Int = 0,
        trick: List<PlayedCard> = emptyList(),
        trickNumber: Int = 1,
        heartsBroken: Boolean = false,
        taken: List<List<Card>> = List(hands.size) { emptyList() },
        options: HeartsOptions = HeartsOptions(),
    ) = HeartsState(
        options = options,
        seed = 1L,
        round = 0,
        phase = HeartsPhase.PLAYING,
        hands = hands,
        handCounts = hands.map { it.size },
        passSelections = List(hands.size) { emptyList() },
        turn = turn,
        leader = leader,
        trickNumber = trickNumber,
        trick = trick,
        taken = taken,
        heartsBroken = heartsBroken,
        scores = List(hands.size) { 0 },
        roundScores = List(hands.size) { 0 },
        log = emptyList(),
    )

    @Test
    fun `a deal gives four hands of thirteen from one pack`() {
        val state = HeartsRules.initialState(config())
        assertEquals(4, state.hands.size)
        state.hands.forEach { assertEquals(13, it.size) }
        val all = state.hands.flatten()
        assertEquals(52, all.size)
        assertEquals("no card is dealt twice", 52, all.distinct().size)
    }

    @Test
    fun `the pass direction cycles and the fourth round holds`() {
        assertEquals(PassDirection.LEFT, PassDirection.forRound(0))
        assertEquals(PassDirection.RIGHT, PassDirection.forRound(1))
        assertEquals(PassDirection.ACROSS, PassDirection.forRound(2))
        assertEquals(PassDirection.HOLD, PassDirection.forRound(3))
        assertEquals(PassDirection.LEFT, PassDirection.forRound(4))
    }

    @Test
    fun `a hold round opens straight onto the lead`() {
        // Round three holds, so there is nothing to swap and play begins at once
        // with whoever holds the two of clubs.
        var state = HeartsRules.initialState(config())
        // Walk to round three through the public entry point rather than
        // reaching into the private deal.
        repeat(3) {
            state = HeartsRules.startNextRound(state.copy(phase = HeartsPhase.ROUND_OVER))
        }
        assertEquals(3, state.round)
        assertEquals(PassDirection.HOLD, state.passDirection)
        assertEquals(HeartsPhase.PLAYING, state.phase)
        assertTrue(state.hands[state.turn].contains(TWO_OF_CLUBS))
    }

    @Test
    fun `passing left moves three cards to the next seat`() {
        var state = HeartsRules.initialState(config())
        assertEquals(HeartsPhase.PASSING, state.phase)
        val given = (0 until 4).map { state.hands[it].take(PASS_SIZE) }
        for (seat in 0 until 4) {
            assertEquals(seat, HeartsRules.currentSeat(state))
            state = HeartsRules.applyMove(state, seat, PassCards(given[seat]))
        }
        assertEquals(HeartsPhase.PLAYING, state.phase)
        for (seat in 0 until 4) {
            val receiver = (seat + 1) % 4
            assertTrue(
                "seat $receiver should hold what seat $seat passed",
                state.hands[receiver].containsAll(given[seat]),
            )
            assertFalse(
                "seat $seat should not still hold what it passed",
                state.hands[seat].any { given[seat].contains(it) },
            )
        }
        state.hands.forEach { assertEquals(13, it.size) }
    }

    @Test
    fun `a pass must be three distinct cards from the hand`() {
        val state = HeartsRules.initialState(config())
        val hand = state.hands[0]
        assertThrows { HeartsRules.applyMove(state, 0, PassCards(hand.take(2))) }
        assertThrows { HeartsRules.applyMove(state, 0, PassCards(listOf(hand[0], hand[0], hand[1]))) }
        // A card this seat does not hold — it is in somebody else's thirteen.
        val notHeld = state.hands[1].first()
        assertThrows { HeartsRules.applyMove(state, 0, PassCards(listOf(notHeld) + hand.take(2))) }
    }

    @Test
    fun `the two of clubs opens the round`() {
        val hands = listOf(
            listOf(c(Rank.TWO, Suit.CLUBS), c(Rank.KING, Suit.SPADES)),
            listOf(c(Rank.THREE, Suit.CLUBS), c(Rank.FOUR, Suit.HEARTS)),
            listOf(c(Rank.FIVE, Suit.CLUBS), c(Rank.SIX, Suit.DIAMONDS)),
            listOf(c(Rank.SEVEN, Suit.CLUBS), c(Rank.EIGHT, Suit.DIAMONDS)),
        )
        val state = playing(hands, trickNumber = 0)
        assertEquals(listOf(TWO_OF_CLUBS), HeartsRules.playableCards(state, 0))
        assertThrows { HeartsRules.applyMove(state, 0, PlayCard(c(Rank.KING, Suit.SPADES))) }
    }

    @Test
    fun `a seat holding the led suit must follow it`() {
        val hands = listOf(
            listOf(c(Rank.TWO, Suit.CLUBS)),
            listOf(c(Rank.THREE, Suit.CLUBS), c(Rank.ACE, Suit.HEARTS)),
            listOf(c(Rank.FIVE, Suit.CLUBS)),
            listOf(c(Rank.SEVEN, Suit.CLUBS)),
        )
        val state = playing(
            hands,
            turn = 1,
            trick = listOf(PlayedCard(0, c(Rank.TWO, Suit.CLUBS))),
        )
        assertEquals(listOf(c(Rank.THREE, Suit.CLUBS)), HeartsRules.playableCards(state, 1))
    }

    @Test
    fun `no point card may be discarded on the first trick`() {
        val hands = listOf(
            listOf(c(Rank.TWO, Suit.CLUBS)),
            // Void in clubs, holding the queen, a heart, and one harmless card.
            listOf(QUEEN_OF_SPADES, c(Rank.ACE, Suit.HEARTS), c(Rank.NINE, Suit.DIAMONDS)),
            listOf(c(Rank.FIVE, Suit.CLUBS)),
            listOf(c(Rank.SEVEN, Suit.CLUBS)),
        )
        val state = playing(
            hands,
            turn = 1,
            trickNumber = 0,
            trick = listOf(PlayedCard(0, c(Rank.TWO, Suit.CLUBS))),
        )
        assertEquals(listOf(c(Rank.NINE, Suit.DIAMONDS)), HeartsRules.playableCards(state, 1))
    }

    @Test
    fun `the first trick ban lifts when a seat holds nothing but points`() {
        val hands = listOf(
            listOf(c(Rank.TWO, Suit.CLUBS)),
            listOf(QUEEN_OF_SPADES, c(Rank.ACE, Suit.HEARTS)),
            listOf(c(Rank.FIVE, Suit.CLUBS)),
            listOf(c(Rank.SEVEN, Suit.CLUBS)),
        )
        val state = playing(
            hands,
            turn = 1,
            trickNumber = 0,
            trick = listOf(PlayedCard(0, c(Rank.TWO, Suit.CLUBS))),
        )
        assertEquals(
            setOf(QUEEN_OF_SPADES, c(Rank.ACE, Suit.HEARTS)),
            HeartsRules.playableCards(state, 1).toSet(),
        )
    }

    @Test
    fun `hearts cannot be led until one has been played`() {
        val hands = listOf(
            listOf(c(Rank.ACE, Suit.HEARTS), c(Rank.THREE, Suit.CLUBS)),
            listOf(c(Rank.FOUR, Suit.CLUBS)),
            listOf(c(Rank.FIVE, Suit.CLUBS)),
            listOf(c(Rank.SIX, Suit.CLUBS)),
        )
        val unbroken = playing(hands, heartsBroken = false)
        assertEquals(listOf(c(Rank.THREE, Suit.CLUBS)), HeartsRules.playableCards(unbroken, 0))

        val broken = playing(hands, heartsBroken = true)
        assertEquals(2, HeartsRules.playableCards(broken, 0).size)
    }

    @Test
    fun `hearts may be led when they are all that is left`() {
        val hands = listOf(
            listOf(c(Rank.ACE, Suit.HEARTS), c(Rank.TWO, Suit.HEARTS)),
            listOf(c(Rank.FOUR, Suit.CLUBS)),
            listOf(c(Rank.FIVE, Suit.CLUBS)),
            listOf(c(Rank.SIX, Suit.CLUBS)),
        )
        val state = playing(hands, heartsBroken = false)
        assertEquals(2, HeartsRules.playableCards(state, 0).size)
    }

    @Test
    fun `the highest card of the led suit takes the trick`() {
        val hands = listOf(
            listOf(c(Rank.TWO, Suit.CLUBS)),
            listOf(c(Rank.KING, Suit.CLUBS)),
            // An ace off suit cannot win, however high.
            listOf(c(Rank.ACE, Suit.DIAMONDS)),
            listOf(c(Rank.FIVE, Suit.CLUBS)),
        )
        var state = playing(hands)
        state = HeartsRules.applyMove(state, 0, PlayCard(c(Rank.TWO, Suit.CLUBS)))
        state = HeartsRules.applyMove(state, 1, PlayCard(c(Rank.KING, Suit.CLUBS)))
        state = HeartsRules.applyMove(state, 2, PlayCard(c(Rank.ACE, Suit.DIAMONDS)))
        state = HeartsRules.applyMove(state, 3, PlayCard(c(Rank.FIVE, Suit.CLUBS)))
        assertEquals("king of clubs takes it", 1, state.leader)
        assertEquals(4, state.taken[1].size)
    }

    @Test
    fun `playing a heart breaks them`() {
        val hands = listOf(
            listOf(c(Rank.TWO, Suit.CLUBS)),
            listOf(c(Rank.ACE, Suit.HEARTS)),
            listOf(c(Rank.FIVE, Suit.CLUBS)),
            listOf(c(Rank.SEVEN, Suit.CLUBS)),
        )
        var state = playing(hands)
        assertFalse(state.heartsBroken)
        state = HeartsRules.applyMove(state, 0, PlayCard(c(Rank.TWO, Suit.CLUBS)))
        state = HeartsRules.applyMove(state, 1, PlayCard(c(Rank.ACE, Suit.HEARTS)))
        assertTrue(state.heartsBroken)
    }

    @Test
    fun `hearts cost a point each and the queen costs thirteen`() {
        assertEquals(1, pointsOf(c(Rank.TWO, Suit.HEARTS)))
        assertEquals(1, pointsOf(c(Rank.ACE, Suit.HEARTS)))
        assertEquals(13, pointsOf(QUEEN_OF_SPADES))
        assertEquals(0, pointsOf(c(Rank.KING, Suit.SPADES)))
        assertEquals(0, pointsOf(c(Rank.ACE, Suit.DIAMONDS)))
        // The whole pack is worth twenty-six and nothing more.
        assertEquals(ALL_POINTS, org.prolibertate.games.game.cards.Decks.standard52().sumOf { pointsOf(it) })
    }

    @Test
    fun `an ordinary round shares out twenty-six points`() {
        val hearts = Rank.standard.map { Card(it, Suit.HEARTS) }
        val taken = listOf(
            hearts.take(6),
            hearts.drop(6) + QUEEN_OF_SPADES,
            emptyList(),
            emptyList(),
        )
        val scored = scoreRound(taken, allowShootTheMoon = true)
        assertEquals(listOf(6, 20, 0, 0), scored)
        assertEquals(ALL_POINTS, scored.sum())
    }

    @Test
    fun `shooting the moon scores everyone else instead`() {
        val everything = Rank.standard.map { Card(it, Suit.HEARTS) } + QUEEN_OF_SPADES
        val taken = listOf(emptyList(), everything, emptyList<Card>(), emptyList())
        assertEquals(listOf(26, 0, 26, 26), scoreRound(taken, allowShootTheMoon = true))
        // Switched off, a moon is simply the worst hand at the table.
        assertEquals(listOf(0, 26, 0, 0), scoreRound(taken, allowShootTheMoon = false))
    }

    @Test
    fun `a client sees neither other hands nor what they are passing`() {
        var state = HeartsRules.initialState(config())
        state = HeartsRules.applyMove(state, 0, PassCards(state.hands[0].take(PASS_SIZE)))
        val view = HeartsRules.viewFor(state, 0)
        assertEquals(state.hands[0], view.hands[0])
        assertTrue("other hands are stripped", view.hands.drop(1).all { it.isEmpty() })
        assertEquals("counts survive so backs can be drawn", state.handCounts, view.handCounts)
        assertEquals(state.passSelections[0], view.passSelections[0])
        assertTrue(view.passSelections.drop(1).all { it.isEmpty() })
    }

    @Test
    fun `state and moves survive a round trip`() {
        val state = HeartsRules.initialState(config())
        val decoded = HeartsRules.decodeState(HeartsRules.encodeState(state))
        assertEquals(state, decoded)

        val pass: HeartsMove = PassCards(state.hands[0].take(PASS_SIZE))
        assertEquals(pass, HeartsRules.decodeMove(HeartsRules.encodeMove(pass)))
        val play: HeartsMove = PlayCard(TWO_OF_CLUBS)
        assertEquals(play, HeartsRules.decodeMove(HeartsRules.encodeMove(play)))
    }

    @Test
    fun `nobody is on the clock once the game is over`() {
        val state = HeartsRules.initialState(config()).copy(phase = HeartsPhase.GAME_OVER)
        assertNull(HeartsRules.currentSeat(state))
        assertTrue(HeartsRules.legalMoves(state, 0).isEmpty())
        assertTrue(HeartsRules.isFinished(state))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected the move to be rejected")
        } catch (expected: IllegalArgumentException) {
            // The rules rejected it, which is the point.
        }
    }
}
