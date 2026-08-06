package org.prolibertate.games.game.golf

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

class GolfRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(options: GolfOptions = GolfOptions(), seed: Long = 3L) = TableConfig(
        gameId = "golf",
        seats = (0 until options.playerCount).map {
            PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it)
        },
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    private fun card(rank: Rank, suit: Suit = Suit.CLUBS) = Card(rank, suit)

    // -- Scoring ------------------------------------------------------------

    @Test
    fun `card values follow the golf table`() {
        assertEquals(0, golfValue(card(Rank.KING)))
        assertEquals(1, golfValue(card(Rank.ACE)))
        assertEquals(-2, golfValue(card(Rank.TWO)))
        assertEquals(10, golfValue(card(Rank.JACK)))
        assertEquals(10, golfValue(card(Rank.QUEEN)))
        assertEquals(7, golfValue(card(Rank.SEVEN)))
    }

    @Test
    fun `a matching column cancels to nothing`() {
        val options = GolfOptions(gridSize = 6)
        // 2x3: columns are (0,3) (1,4) (2,5).
        val grid = listOf(
            card(Rank.NINE), card(Rank.FOUR), card(Rank.KING),
            card(Rank.NINE, Suit.HEARTS), card(Rank.SEVEN), card(Rank.KING, Suit.HEARTS),
        )
        // Nines cancel, kings are worth nothing anyway, 4 + 7 remain.
        assertEquals(11, scoreGrid(grid, options))
    }

    @Test
    fun `an unmatched grid scores the sum of its cards`() {
        val options = GolfOptions(gridSize = 4)
        val grid = listOf(
            card(Rank.THREE), card(Rank.FOUR),
            card(Rank.FIVE), card(Rank.SIX),
        )
        assertEquals(18, scoreGrid(grid, options))
    }

    @Test
    fun `twos pull a score below zero`() {
        val options = GolfOptions(gridSize = 4)
        val grid = listOf(
            card(Rank.TWO), card(Rank.KING),
            card(Rank.THREE), card(Rank.KING, Suit.HEARTS),
        )
        assertEquals(1, scoreGrid(grid, options))
    }

    @Test
    fun `rows cancel only on the nine card board`() {
        val threeByThree = GolfOptions(gridSize = 9)
        val grid = listOf(
            card(Rank.EIGHT), card(Rank.EIGHT, Suit.HEARTS), card(Rank.EIGHT, Suit.SPADES),
            card(Rank.THREE), card(Rank.FOUR), card(Rank.FIVE),
            card(Rank.SIX), card(Rank.SEVEN), card(Rank.NINE),
        )
        // The row of eights is wiped out, leaving 3+4+5+6+7+9.
        assertEquals(34, scoreGrid(grid, threeByThree))

        // The same three-in-a-line on a 2x3 board is not a line at all.
        val twoByThree = GolfOptions(gridSize = 6)
        val flat = listOf(
            card(Rank.EIGHT), card(Rank.EIGHT, Suit.HEARTS), card(Rank.EIGHT, Suit.SPADES),
            card(Rank.THREE), card(Rank.FOUR), card(Rank.FIVE),
        )
        assertEquals(36, scoreGrid(flat, twoByThree))
    }

    // -- Dealing ------------------------------------------------------------

    @Test
    fun `everyone gets a grid and two cards face up`() {
        val state = GolfRules.initialState(config(GolfOptions(playerCount = 3, gridSize = 6)))
        assertEquals(3, state.grids.size)
        state.grids.forEach { assertEquals(6, it.size) }
        state.revealed.forEach { assertEquals(2, it.count { shown -> shown }) }
        assertEquals(1, state.discard.size)
        assertEquals(GolfPhase.DRAW, state.phase)
    }

    @Test
    fun `a big table uses two decks so the stock survives`() {
        val state = GolfRules.initialState(config(GolfOptions(playerCount = 6, gridSize = 9)))
        val dealt = 6 * 9
        assertTrue("stock must not be empty at the deal", state.stock.isNotEmpty())
        assertEquals(104, dealt + state.stock.size + state.discard.size)
    }

    // -- Turn structure -----------------------------------------------------

    @Test
    fun `a turn is draw then place`() {
        var state = GolfRules.initialState(config(GolfOptions(playerCount = 2)))
        assertEquals(setOf(DrawFromStock, DrawFromDiscard), GolfRules.legalMoves(state, 0).toSet())

        state = GolfRules.applyMove(state, 0, DrawFromStock)
        assertEquals(GolfPhase.PLACE, state.phase)
        assertTrue(state.drawn != null)

        val moves = GolfRules.legalMoves(state, 0)
        assertTrue("can place it anywhere", moves.filterIsInstance<ReplaceCard>().size == 6)
        assertTrue("or throw it and turn one over", moves.any { it is DiscardAndFlip })
    }

    @Test
    fun `a card taken from the discard pile must be used`() {
        var state = GolfRules.initialState(config(GolfOptions(playerCount = 2)))
        state = GolfRules.applyMove(state, 0, DrawFromDiscard)
        val moves = GolfRules.legalMoves(state, 0)
        assertTrue("no throwing it back", moves.none { it is DiscardAndFlip })
        assertTrue(moves.all { it is ReplaceCard })
    }

    @Test
    fun `replacing a card turns that slot face up and discards the old one`() {
        var state = GolfRules.initialState(config(GolfOptions(playerCount = 2)))
        state = GolfRules.applyMove(state, 0, DrawFromStock)
        val drawn = state.drawn!!
        val replacedCard = state.grids[0][5]

        state = GolfRules.applyMove(state, 0, ReplaceCard(5))
        assertEquals(drawn, state.grids[0][5])
        assertTrue(state.revealed[0][5])
        assertEquals(replacedCard, state.discard.last())
        assertEquals("turn passes", 1, state.turn)
        assertEquals(GolfPhase.DRAW, state.phase)
    }

    @Test
    fun `throwing the drawn card turns one of your own over`() {
        var state = GolfRules.initialState(config(GolfOptions(playerCount = 2)))
        state = GolfRules.applyMove(state, 0, DrawFromStock)
        val drawn = state.drawn!!
        assertFalse(state.revealed[0][4])

        state = GolfRules.applyMove(state, 0, DiscardAndFlip(4))
        assertTrue(state.revealed[0][4])
        assertEquals(drawn, state.discard.last())
    }

    // -- Closing and scoring ------------------------------------------------

    @Test
    fun `turning the last card over gives everyone else one more turn`() {
        val options = GolfOptions(playerCount = 3, gridSize = 4, startingReveals = 3, holes = 1)
        var state = GolfRules.initialState(config(options))
        // Seat 0 has one card left face down; use it to close.
        state = GolfRules.applyMove(state, 0, DrawFromStock)
        state = GolfRules.applyMove(state, 0, DiscardAndFlip(3))

        assertEquals(0, state.closedBy)
        assertEquals("one turn each for the other two", 2, state.finalTurnsLeft)
        assertFalse(GolfRules.isFinished(state))

        // Those two turns play out, then the hole is scored.
        repeat(2) {
            val seat = GolfRules.currentSeat(state)!!
            state = GolfRules.applyMove(state, seat, GolfRules.legalMoves(state, seat).first())
            state = GolfRules.applyMove(state, seat, GolfRules.legalMoves(state, seat).first())
        }
        assertTrue("single-hole game is over", GolfRules.isFinished(state))
        assertTrue("everything is turned up to count", state.revealed.all { row -> row.all { it } })
    }

    @Test
    fun `scores accumulate across holes`() {
        val options = GolfOptions(playerCount = 2, gridSize = 4, startingReveals = 4, holes = 3)
        var state = GolfRules.initialState(config(options))
        var guard = 0
        while (state.hole == 0 && !GolfRules.isFinished(state) && guard++ < 400) {
            val seat = GolfRules.currentSeat(state) ?: break
            state = GolfRules.applyMove(state, seat, GolfRules.legalMoves(state, seat).first())
        }
        assertEquals(GolfPhase.HOLE_OVER, state.phase)
        val afterFirst = state.scores.toList()

        state = GolfRules.nextHole(state)
        assertEquals(1, state.hole)
        assertEquals("running totals carry over", afterFirst, state.scores)
    }

    // -- Full games ---------------------------------------------------------

    @Test
    fun `random legal play always finishes`() {
        repeat(15) { iteration ->
            val random = Random(iteration.toLong())
            var state = GolfRules.initialState(
                config(GolfOptions(playerCount = 3, gridSize = 6, holes = 2), seed = iteration.toLong())
            )
            var guard = 0
            while (!GolfRules.isFinished(state) && guard++ < 8000) {
                val seat = GolfRules.currentSeat(state)
                if (seat == null) {
                    state = GolfRules.nextHole(state)
                    continue
                }
                val legal = GolfRules.legalMoves(state, seat)
                assertTrue("seat $seat had no legal move in ${state.phase}", legal.isNotEmpty())
                state = GolfRules.applyMove(state, seat, legal[random.nextInt(legal.size)])
            }
            assertTrue("game $iteration did not finish", GolfRules.isFinished(state))
        }
    }

    @Test
    fun `ai play always finishes and beats random play on average`() {
        val ai = GolfAi()
        var aiTotal = 0
        var randomTotal = 0
        val games = 12

        repeat(games) { iteration ->
            val random = Random(9000L + iteration)
            var state = GolfRules.initialState(
                config(GolfOptions(playerCount = 2, gridSize = 6, holes = 3), seed = 700L + iteration)
            )
            var guard = 0
            while (!GolfRules.isFinished(state) && guard++ < 8000) {
                val seat = GolfRules.currentSeat(state)
                if (seat == null) {
                    state = GolfRules.nextHole(state)
                    continue
                }
                val legal = GolfRules.legalMoves(state, seat)
                // Seat 0 plays properly, seat 1 flails.
                val move = if (seat == 0) {
                    ai.chooseMove(state, seat, legal)
                } else {
                    legal[random.nextInt(legal.size)]
                }
                assertTrue("ai returned an illegal move $move", legal.contains(move))
                state = GolfRules.applyMove(state, seat, move)
            }
            assertTrue(GolfRules.isFinished(state))
            aiTotal += state.scores[0]
            randomTotal += state.scores[1]
        }

        // Golf is won by the lowest score, so the AI should be under random.
        assertTrue(
            "ai scored $aiTotal against random's $randomTotal — no better than flailing",
            aiTotal < randomTotal,
        )
    }

    // -- Redaction and wire format -----------------------------------------

    @Test
    fun `a view hides face-down cards including your own`() {
        val state = GolfRules.initialState(config(GolfOptions(playerCount = 2, gridSize = 6)))
        val view = GolfRules.viewFor(state, seat = 0)

        for (index in 0 until 6) {
            if (state.revealed[0][index]) {
                assertEquals(state.grids[0][index], view.grids[0][index])
            } else {
                assertEquals("face-down cards are masked", HIDDEN_CARD, view.grids[0][index])
            }
        }
        assertTrue("the stock order is hidden too", view.stock.isEmpty())
    }

    @Test
    fun `masking does not leak the real card`() {
        // A grid deliberately built with no twos of spades in it, so if the
        // mask ever showed through it would be obvious.
        val state = GolfRules.initialState(config(GolfOptions(playerCount = 2, gridSize = 6), seed = 42L))
        val view = GolfRules.viewFor(state, seat = 1)
        val hiddenIndices = (0 until 6).filter { !state.revealed[1][it] }
        hiddenIndices.forEach { index ->
            val real = state.grids[1][index]
            if (real != HIDDEN_CARD) {
                assertNotEquals("the real card must not survive redaction", real, view.grids[1][index])
            }
        }
    }

    @Test
    fun `state and moves survive a round trip`() {
        val state = GolfRules.initialState(config())
        assertEquals(state, GolfRules.decodeState(GolfRules.encodeState(state)))
        listOf(DrawFromStock, DrawFromDiscard, ReplaceCard(2), DiscardAndFlip(5)).forEach {
            assertEquals(it, GolfRules.decodeMove(GolfRules.encodeMove(it)))
        }
    }
}
