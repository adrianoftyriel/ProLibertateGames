package org.prolibertate.games.game.crazyeights

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

class CrazyEightsRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(
        options: CrazyEightsOptions = CrazyEightsOptions(),
        seed: Long = 21L,
    ) = TableConfig(
        gameId = "crazy8s",
        seats = (0 until options.playerCount).map {
            PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it)
        },
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    private fun c(rank: Rank, suit: Suit) = Card(rank, suit)

    private fun playingState(
        hands: List<List<Card>>,
        top: Card,
        suitInForce: Suit = top.suit,
        stock: List<Card> = emptyList(),
        turn: Int = 0,
        options: CrazyEightsOptions = CrazyEightsOptions(playerCount = hands.size),
        drawnThisTurn: Int = 0,
    ) = CrazyEightsState(
        options = options,
        seed = 1L,
        roundNumber = 0,
        hands = hands,
        handCounts = hands.map { it.size },
        stock = stock,
        discard = listOf(top),
        suitInForce = suitInForce,
        turn = turn,
        drawnThisTurn = drawnThisTurn,
        scores = List(hands.size) { 0 },
        roundWinner = null,
        phase = CrazyEightsPhase.PLAYING,
        log = emptyList(),
    )

    // -- Matching -----------------------------------------------------------

    @Test
    fun `a card matches on suit or on rank`() {
        val top = c(Rank.SEVEN, Suit.HEARTS)
        assertTrue("same suit", canPlay(c(Rank.KING, Suit.HEARTS), top, Suit.HEARTS))
        assertTrue("same rank", canPlay(c(Rank.SEVEN, Suit.CLUBS), top, Suit.HEARTS))
        assertFalse("neither", canPlay(c(Rank.KING, Suit.CLUBS), top, Suit.HEARTS))
    }

    @Test
    fun `an eight always plays`() {
        val top = c(Rank.SEVEN, Suit.HEARTS)
        Suit.entries.forEach { suit ->
            assertTrue(canPlay(c(Rank.EIGHT, suit), top, Suit.HEARTS))
        }
    }

    @Test
    fun `the suit in force overrides the top card`() {
        // An eight was played on a heart and clubs were called.
        val top = c(Rank.EIGHT, Suit.HEARTS)
        assertTrue(canPlay(c(Rank.TWO, Suit.CLUBS), top, Suit.CLUBS))
        assertFalse("hearts no longer run", canPlay(c(Rank.TWO, Suit.HEARTS), top, Suit.CLUBS))
    }

    // -- Legal moves --------------------------------------------------------

    @Test
    fun `playing an eight offers a choice of suit`() {
        val hand = listOf(c(Rank.EIGHT, Suit.SPADES))
        val state = playingState(listOf(hand, emptyList()), top = c(Rank.THREE, Suit.HEARTS))
        val moves = CrazyEightsRules.legalMoves(state, 0).filterIsInstance<PlayCard>()
        assertEquals("one move per suit", 4, moves.size)
        assertEquals(Suit.entries.toSet(), moves.mapNotNull { it.nominatedSuit }.toSet())
    }

    @Test
    fun `you must play when you can`() {
        val hand = listOf(c(Rank.KING, Suit.HEARTS), c(Rank.TWO, Suit.CLUBS))
        val state = playingState(
            hands = listOf(hand, emptyList()),
            top = c(Rank.THREE, Suit.HEARTS),
            stock = listOf(c(Rank.NINE, Suit.SPADES)),
        )
        val moves = CrazyEightsRules.legalMoves(state, 0)
        assertTrue("no drawing your way out of a playable hand", moves.none { it is DrawCard })
        assertEquals(1, moves.size)
    }

    @Test
    fun `with nothing playable you draw`() {
        val hand = listOf(c(Rank.KING, Suit.CLUBS))
        val state = playingState(
            hands = listOf(hand, emptyList()),
            top = c(Rank.THREE, Suit.HEARTS),
            stock = listOf(c(Rank.NINE, Suit.SPADES)),
        )
        assertEquals(listOf(DrawCard), CrazyEightsRules.legalMoves(state, 0))
    }

    @Test
    fun `with an empty stock and nothing playable you pass`() {
        val hand = listOf(c(Rank.KING, Suit.CLUBS))
        val state = playingState(
            hands = listOf(hand, emptyList()),
            top = c(Rank.THREE, Suit.HEARTS),
            stock = emptyList(),
        )
        assertEquals(listOf(PassTurn), CrazyEightsRules.legalMoves(state, 0))
    }

    @Test
    fun `draw one and pass stops after a single card`() {
        val options = CrazyEightsOptions(playerCount = 2, drawUntilPlayable = false)
        val hand = listOf(c(Rank.KING, Suit.CLUBS))
        val state = playingState(
            hands = listOf(hand, emptyList()),
            top = c(Rank.THREE, Suit.HEARTS),
            stock = listOf(c(Rank.NINE, Suit.SPADES), c(Rank.TEN, Suit.SPADES)),
            options = options,
            drawnThisTurn = 1,
        )
        assertEquals("already drawn, so pass", listOf(PassTurn), CrazyEightsRules.legalMoves(state, 0))
    }

    // -- Play ---------------------------------------------------------------

    @Test
    fun `an eight sets the suit in force`() {
        val hand = listOf(c(Rank.EIGHT, Suit.SPADES), c(Rank.TWO, Suit.CLUBS))
        var state = playingState(listOf(hand, listOf(c(Rank.FIVE, Suit.CLUBS))),
            top = c(Rank.THREE, Suit.HEARTS))
        state = CrazyEightsRules.applyMove(
            state, 0, PlayCard(c(Rank.EIGHT, Suit.SPADES), Suit.DIAMONDS)
        )
        assertEquals(Suit.DIAMONDS, state.suitInForce)
        assertEquals(c(Rank.EIGHT, Suit.SPADES), state.topCard)
        assertEquals(1, state.turn)
    }

    @Test
    fun `drawing keeps the turn`() {
        val state = playingState(
            hands = listOf(listOf(c(Rank.KING, Suit.CLUBS)), emptyList()),
            top = c(Rank.THREE, Suit.HEARTS),
            stock = listOf(c(Rank.NINE, Suit.SPADES)),
        )
        val after = CrazyEightsRules.applyMove(state, 0, DrawCard)
        assertEquals("still your turn", 0, after.turn)
        assertEquals(2, after.hands[0].size)
        assertEquals(1, after.drawnThisTurn)
    }

    @Test
    fun `an exhausted stock is refilled from the discards`() {
        val state = playingState(
            hands = listOf(listOf(c(Rank.KING, Suit.CLUBS)), emptyList()),
            top = c(Rank.THREE, Suit.HEARTS),
            stock = emptyList(),
        ).copy(discard = listOf(c(Rank.FOUR, Suit.SPADES), c(Rank.THREE, Suit.HEARTS)))

        val after = CrazyEightsRules.applyMove(state, 0, DrawCard)
        assertEquals("the card in play stays put", c(Rank.THREE, Suit.HEARTS), after.topCard)
        assertEquals("and the rest became stock", 2, after.hands[0].size)
    }

    // -- Scoring ------------------------------------------------------------

    @Test
    fun `penalties charge eights the most`() {
        assertEquals(50, crazyEightsPenalty(c(Rank.EIGHT, Suit.CLUBS)))
        assertEquals(10, crazyEightsPenalty(c(Rank.KING, Suit.CLUBS)))
        assertEquals(10, crazyEightsPenalty(c(Rank.TEN, Suit.CLUBS)))
        assertEquals(1, crazyEightsPenalty(c(Rank.ACE, Suit.CLUBS)))
        assertEquals(5, crazyEightsPenalty(c(Rank.FIVE, Suit.CLUBS)))
    }

    @Test
    fun `going out charges everyone else for what they hold`() {
        val options = CrazyEightsOptions(playerCount = 3, roundsToPlay = 2)
        var state = playingState(
            hands = listOf(
                listOf(c(Rank.THREE, Suit.HEARTS)),
                listOf(c(Rank.EIGHT, Suit.CLUBS), c(Rank.ACE, Suit.SPADES)),
                listOf(c(Rank.KING, Suit.SPADES)),
            ),
            top = c(Rank.FOUR, Suit.HEARTS),
            options = options,
        )
        state = CrazyEightsRules.applyMove(state, 0, PlayCard(c(Rank.THREE, Suit.HEARTS)))

        assertEquals(CrazyEightsPhase.ROUND_OVER, state.phase)
        assertEquals(0, state.roundWinner)
        assertEquals("the winner is charged nothing", 0, state.scores[0])
        assertEquals("an eight and an ace", 51, state.scores[1])
        assertEquals(10, state.scores[2])
    }

    @Test
    fun `the last round ends the game`() {
        val options = CrazyEightsOptions(playerCount = 2, roundsToPlay = 1)
        var state = playingState(
            hands = listOf(listOf(c(Rank.THREE, Suit.HEARTS)), listOf(c(Rank.KING, Suit.SPADES))),
            top = c(Rank.FOUR, Suit.HEARTS),
            options = options,
        )
        state = CrazyEightsRules.applyMove(state, 0, PlayCard(c(Rank.THREE, Suit.HEARTS)))
        assertTrue(CrazyEightsRules.isFinished(state))
    }

    // -- Dealing ------------------------------------------------------------

    @Test
    fun `two players get seven cards and everyone else five`() {
        assertEquals(7, CrazyEightsOptions(playerCount = 2).handSize())
        assertEquals(5, CrazyEightsOptions(playerCount = 4).handSize())
        assertEquals(9, CrazyEightsOptions(playerCount = 4, startingHand = 9).handSize())
    }

    @Test
    fun `the opening card is never an eight`() {
        // An eight on top would leave the suit in force unnamed.
        repeat(30) { seed ->
            val state = CrazyEightsRules.initialState(config(seed = seed.toLong()))
            assertFalse("seed $seed dealt a wild upcard", isWild(state.topCard!!))
            assertEquals(state.topCard!!.suit, state.suitInForce)
        }
    }

    // -- Full games ---------------------------------------------------------

    @Test
    fun `random legal play always finishes`() {
        repeat(20) { iteration ->
            val random = Random(iteration.toLong())
            var state = CrazyEightsRules.initialState(
                config(CrazyEightsOptions(playerCount = 4, roundsToPlay = 2), iteration.toLong())
            )
            var guard = 0
            while (!CrazyEightsRules.isFinished(state) && guard++ < 8000) {
                val seat = CrazyEightsRules.currentSeat(state)
                if (seat == null) {
                    state = CrazyEightsRules.nextRound(state)
                    continue
                }
                val legal = CrazyEightsRules.legalMoves(state, seat)
                assertTrue("seat $seat had no legal move", legal.isNotEmpty())
                state = CrazyEightsRules.applyMove(state, seat, legal[random.nextInt(legal.size)])
            }
            assertTrue("game $iteration did not finish", CrazyEightsRules.isFinished(state))
        }
    }

    @Test
    fun `ai play always finishes and beats random play`() {
        val ai = CrazyEightsAi()
        var aiTotal = 0
        var randomTotal = 0

        repeat(12) { iteration ->
            val random = Random(3000L + iteration)
            var state = CrazyEightsRules.initialState(
                config(CrazyEightsOptions(playerCount = 2, roundsToPlay = 3), 800L + iteration)
            )
            var guard = 0
            while (!CrazyEightsRules.isFinished(state) && guard++ < 8000) {
                val seat = CrazyEightsRules.currentSeat(state)
                if (seat == null) {
                    state = CrazyEightsRules.nextRound(state)
                    continue
                }
                val legal = CrazyEightsRules.legalMoves(state, seat)
                val move = if (seat == 0) {
                    ai.chooseMove(state, seat, legal)
                } else {
                    legal[random.nextInt(legal.size)]
                }
                assertTrue("ai returned an illegal move $move", legal.contains(move))
                state = CrazyEightsRules.applyMove(state, seat, move)
            }
            assertTrue(CrazyEightsRules.isFinished(state))
            aiTotal += state.scores[0]
            randomTotal += state.scores[1]
        }
        // Penalty points, so lower is better.
        assertTrue(
            "ai took $aiTotal against random's $randomTotal — no better than flailing",
            aiTotal < randomTotal,
        )
    }

    // -- Redaction and wire format -----------------------------------------

    @Test
    fun `a seat view hides other hands and the stock`() {
        val state = CrazyEightsRules.initialState(config())
        val view = CrazyEightsRules.viewFor(state, seat = 2)
        assertEquals(state.hands[2], view.hands[2])
        assertTrue(view.hands.filterIndexed { i, _ -> i != 2 }.all { it.isEmpty() })
        assertTrue(view.stock.isEmpty())
        assertEquals(state.handCounts, view.handCounts)
    }

    @Test
    fun `state and moves survive a round trip`() {
        val state = CrazyEightsRules.initialState(config())
        assertEquals(state, CrazyEightsRules.decodeState(CrazyEightsRules.encodeState(state)))
        listOf(
            DrawCard,
            PassTurn,
            PlayCard(c(Rank.KING, Suit.HEARTS)),
            PlayCard(c(Rank.EIGHT, Suit.CLUBS), Suit.DIAMONDS),
        ).forEach {
            assertEquals(it, CrazyEightsRules.decodeMove(CrazyEightsRules.encodeMove(it)))
        }
    }
}
