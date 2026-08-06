package org.prolibertate.games.game.president

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

class PresidentRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(
        options: PresidentOptions = PresidentOptions(),
        seed: Long = 11L,
    ) = TableConfig(
        gameId = "president",
        seats = (0 until options.playerCount).map {
            PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it)
        },
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    // -- Ranking ------------------------------------------------------------

    @Test
    fun `threes are low and twos are high`() {
        assertTrue(presidentRank(Card(Rank.THREE, Suit.CLUBS)) < presidentRank(Card(Rank.TEN, Suit.CLUBS)))
        assertTrue(presidentRank(Card(Rank.KING, Suit.CLUBS)) < presidentRank(Card(Rank.ACE, Suit.CLUBS)))
        assertTrue(
            "a two outranks an ace",
            presidentRank(Card(Rank.TWO, Suit.CLUBS)) > presidentRank(Card(Rank.ACE, Suit.CLUBS)),
        )
    }

    // -- Dealing ------------------------------------------------------------

    @Test
    fun `the whole deck is dealt out`() {
        val state = PresidentRules.initialState(config())
        assertEquals(52, state.hands.sumOf { it.size })
        assertEquals(52, state.hands.flatten().distinct().size)
        assertTrue("hands differ by at most one card",
            state.hands.maxOf { it.size } - state.hands.minOf { it.size } <= 1)
    }

    @Test
    fun `the lowest card leads the first round`() {
        val state = PresidentRules.initialState(config())
        val lowestHolder = state.hands.indices.minByOrNull { seat ->
            state.hands[seat].minOf { presidentRank(it) }
        }
        assertEquals(lowestHolder, state.turn)
    }

    // -- Leading and following ----------------------------------------------

    private fun playingState(
        hands: List<List<Card>>,
        setSize: Int = 0,
        setRank: Int = 0,
        turn: Int = 0,
        options: PresidentOptions = PresidentOptions(playerCount = 4),
        pile: List<Card> = emptyList(),
    ) = PresidentState(
        options = options,
        seed = 1L,
        roundNumber = 0,
        hands = hands,
        handCounts = hands.map { it.size },
        pile = pile,
        setSize = setSize,
        setRank = setRank,
        turn = turn,
        passed = List(options.playerCount) { false },
        finishedOrder = emptyList(),
        previousFinish = List(options.playerCount) { -1 },
        scores = List(options.playerCount) { 0 },
        phase = PresidentPhase.PLAYING,
        log = emptyList(),
    )

    private fun hand(vararg cards: Pair<Rank, Suit>) = cards.map { Card(it.first, it.second) }

    @Test
    fun `leading offers every set size of every rank held`() {
        val h = hand(
            Rank.FIVE to Suit.CLUBS, Rank.FIVE to Suit.HEARTS, Rank.FIVE to Suit.SPADES,
            Rank.NINE to Suit.CLUBS,
        )
        val state = playingState(listOf(h, emptyList(), emptyList(), emptyList()))
        val plays = PresidentRules.legalMoves(state, 0).filterIsInstance<PlayCards>()
        assertEquals("singles, pairs and triples of fives, plus the nine", 4, plays.size)
        assertTrue(plays.any { it.cards.size == 3 })
        assertFalse("nothing to pass on when leading",
            PresidentRules.legalMoves(state, 0).contains(PassTurn))
    }

    @Test
    fun `following must match the set size and beat the rank`() {
        val h = hand(
            Rank.FOUR to Suit.CLUBS, Rank.FOUR to Suit.HEARTS,
            Rank.KING to Suit.CLUBS, Rank.KING to Suit.HEARTS,
            Rank.SEVEN to Suit.SPADES,
        )
        val state = playingState(
            hands = listOf(h, emptyList(), emptyList(), emptyList()),
            setSize = 2,
            setRank = presidentRank(Card(Rank.NINE, Suit.CLUBS)),
        )
        val plays = PresidentRules.legalMoves(state, 0).filterIsInstance<PlayCards>()
        assertTrue("only the kings beat a pair of nines", plays.all { it.cards.first().rank == Rank.KING })
        assertTrue(plays.all { it.cards.size == 2 })
        assertTrue("passing is allowed", PresidentRules.legalMoves(state, 0).contains(PassTurn))
    }

    @Test
    fun `a two beats anything and clears the pile`() {
        val h = hand(Rank.TWO to Suit.CLUBS, Rank.FOUR to Suit.HEARTS)
        var state = playingState(
            hands = listOf(h, hand(Rank.SIX to Suit.CLUBS), emptyList(), emptyList()),
            setSize = 1,
            setRank = presidentRank(Card(Rank.ACE, Suit.SPADES)),
            pile = hand(Rank.ACE to Suit.SPADES),
        )
        val two = PlayCards(hand(Rank.TWO to Suit.CLUBS))
        assertTrue(PresidentRules.legalMoves(state, 0).contains(two))

        state = PresidentRules.applyMove(state, 0, two)
        assertEquals("pile is cleared", 0, state.setSize)
        assertTrue(state.pile.isEmpty())
        assertEquals("and the same player leads again", 0, state.turn)
    }

    @Test
    fun `with twos clear disabled a two is just the highest card`() {
        val options = PresidentOptions(playerCount = 4, twosClear = false)
        val h = hand(Rank.TWO to Suit.CLUBS)
        var state = playingState(
            hands = listOf(h, hand(Rank.SIX to Suit.CLUBS), emptyList(), emptyList()),
            setSize = 1,
            setRank = presidentRank(Card(Rank.ACE, Suit.SPADES)),
            options = options,
            pile = hand(Rank.ACE to Suit.SPADES),
        )
        state = PresidentRules.applyMove(state, 0, PlayCards(hand(Rank.TWO to Suit.CLUBS)))
        assertEquals("the pile stands", 1, state.setSize)
    }

    @Test
    fun `when everyone else passes the pile is taken down`() {
        val hands = listOf(
            hand(Rank.FIVE to Suit.CLUBS, Rank.SIX to Suit.CLUBS),
            hand(Rank.FOUR to Suit.HEARTS),
            hand(Rank.THREE to Suit.SPADES),
        )
        val options = PresidentOptions(playerCount = 3)
        var state = playingState(hands = hands, options = options)

        state = PresidentRules.applyMove(state, 0, PlayCards(hand(Rank.FIVE to Suit.CLUBS)))
        assertEquals(1, state.turn)
        state = PresidentRules.applyMove(state, 1, PassTurn)
        state = PresidentRules.applyMove(state, 2, PassTurn)

        assertEquals("pile cleared", 0, state.setSize)
        assertEquals("the last player to play leads again", 0, state.turn)
        assertTrue(state.passed.none { it })
    }

    // -- Going out and scoring ----------------------------------------------

    @Test
    fun `going out records a finishing position`() {
        val hands = listOf(
            hand(Rank.FIVE to Suit.CLUBS),
            hand(Rank.SIX to Suit.HEARTS, Rank.SEVEN to Suit.HEARTS),
            hand(Rank.EIGHT to Suit.SPADES, Rank.NINE to Suit.SPADES),
        )
        val options = PresidentOptions(playerCount = 3)
        var state = playingState(hands = hands, options = options)
        state = PresidentRules.applyMove(state, 0, PlayCards(hand(Rank.FIVE to Suit.CLUBS)))
        assertEquals(listOf(0), state.finishedOrder)
        assertTrue(state.isOut(0))
    }

    @Test
    fun `a round ends when only one player still holds cards`() {
        val options = PresidentOptions(playerCount = 3, roundsToPlay = 2)
        var state = playingState(
            hands = listOf(
                hand(Rank.FIVE to Suit.CLUBS),
                hand(Rank.SIX to Suit.HEARTS),
                hand(Rank.EIGHT to Suit.SPADES, Rank.NINE to Suit.SPADES),
            ),
            options = options,
        )
        state = PresidentRules.applyMove(state, 0, PlayCards(hand(Rank.FIVE to Suit.CLUBS)))
        state = PresidentRules.applyMove(state, 1, PlayCards(hand(Rank.SIX to Suit.HEARTS)))

        assertEquals(PresidentPhase.ROUND_OVER, state.phase)
        assertEquals("the straggler takes last place", listOf(0, 1, 2), state.finishedOrder)
        assertEquals("President scores most", 2, state.scores[0])
        assertEquals(1, state.scores[1])
        assertEquals(0, state.scores[2])
    }

    @Test
    fun `the last round ends the game`() {
        val options = PresidentOptions(playerCount = 3, roundsToPlay = 1)
        var state = playingState(
            hands = listOf(
                hand(Rank.FIVE to Suit.CLUBS),
                hand(Rank.SIX to Suit.HEARTS),
                hand(Rank.EIGHT to Suit.SPADES, Rank.NINE to Suit.SPADES),
            ),
            options = options,
        )
        state = PresidentRules.applyMove(state, 0, PlayCards(hand(Rank.FIVE to Suit.CLUBS)))
        state = PresidentRules.applyMove(state, 1, PlayCards(hand(Rank.SIX to Suit.HEARTS)))
        assertTrue(PresidentRules.isFinished(state))
    }

    @Test
    fun `titles run from President down to Scum`() {
        assertEquals("President", titleFor(0, 4))
        assertEquals("Vice President", titleFor(1, 4))
        assertEquals("Vice Scum", titleFor(2, 4))
        assertEquals("Scum", titleFor(3, 4))
        assertEquals("Citizen", titleFor(2, 6))
    }

    // -- Full games ---------------------------------------------------------

    @Test
    fun `random legal play always reaches a winner`() {
        repeat(20) { iteration ->
            val random = Random(iteration.toLong())
            var state = PresidentRules.initialState(
                config(PresidentOptions(playerCount = 4, roundsToPlay = 3), seed = iteration.toLong())
            )
            var guard = 0
            while (!PresidentRules.isFinished(state) && guard++ < 6000) {
                val seat = PresidentRules.currentSeat(state)
                if (seat == null) {
                    state = PresidentRules.nextRound(state)
                    continue
                }
                val legal = PresidentRules.legalMoves(state, seat)
                assertTrue("seat $seat had no legal move", legal.isNotEmpty())
                state = PresidentRules.applyMove(state, seat, legal[random.nextInt(legal.size)])
            }
            assertTrue("game $iteration did not finish", PresidentRules.isFinished(state))
        }
    }

    @Test
    fun `ai play always reaches a winner`() {
        val ai = PresidentAi()
        repeat(10) { iteration ->
            var state = PresidentRules.initialState(
                config(PresidentOptions(playerCount = 5, roundsToPlay = 2), seed = 400L + iteration)
            )
            var guard = 0
            while (!PresidentRules.isFinished(state) && guard++ < 6000) {
                val seat = PresidentRules.currentSeat(state)
                if (seat == null) {
                    state = PresidentRules.nextRound(state)
                    continue
                }
                val legal = PresidentRules.legalMoves(state, seat)
                val move = ai.chooseMove(state, seat, legal)
                assertTrue("ai returned an illegal move $move", legal.contains(move))
                state = PresidentRules.applyMove(state, seat, move)
            }
            assertTrue("ai game did not finish", PresidentRules.isFinished(state))
        }
    }

    @Test
    fun `card exchange moves the best cards to the president`() {
        val options = PresidentOptions(playerCount = 4, roundsToPlay = 3)
        var state = PresidentRules.initialState(config(options, seed = 5L))
        var guard = 0
        while (state.phase == PresidentPhase.PLAYING && guard++ < 6000) {
            val seat = PresidentRules.currentSeat(state)!!
            val legal = PresidentRules.legalMoves(state, seat)
            state = PresidentRules.applyMove(state, seat, legal.first())
        }
        assertEquals(PresidentPhase.ROUND_OVER, state.phase)

        val president = state.finishedOrder.first()
        val scum = state.finishedOrder.last()
        val next = PresidentRules.nextRound(state)

        assertEquals("still a full deck", 52, next.hands.sumOf { it.size })
        assertEquals("the scum leads the new round", scum, next.turn)
        assertTrue(
            "the president holds a high card after the swap",
            next.hands[president].maxOf { presidentRank(it) } >= 13,
        )
    }

    // -- Redaction and wire format -----------------------------------------

    @Test
    fun `a seat view hides every other hand`() {
        val state = PresidentRules.initialState(config())
        val view = PresidentRules.viewFor(state, seat = 1)
        assertEquals(state.hands[1], view.hands[1])
        assertTrue(view.hands.filterIndexed { i, _ -> i != 1 }.all { it.isEmpty() })
        assertEquals(state.handCounts, view.handCounts)
    }

    @Test
    fun `state and moves survive a round trip`() {
        val state = PresidentRules.initialState(config())
        assertEquals(state, PresidentRules.decodeState(PresidentRules.encodeState(state)))
        listOf(
            PassTurn,
            PlayCards(hand(Rank.FIVE to Suit.CLUBS, Rank.FIVE to Suit.HEARTS)),
        ).forEach {
            assertEquals(it, PresidentRules.decodeMove(PresidentRules.encodeMove(it)))
        }
    }
}
