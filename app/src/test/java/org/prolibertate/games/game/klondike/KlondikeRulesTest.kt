package org.prolibertate.games.game.klondike

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

class KlondikeRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(options: KlondikeOptions = KlondikeOptions(), seed: Long = 5L) = TableConfig(
        gameId = "klondike",
        seats = listOf(PlayerSlot(seat = 0, name = "P0", kind = PlayerKind.HUMAN_LOCAL, team = 0)),
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    private fun c(rank: Rank, suit: Suit) = Card(rank, suit)

    // ---- the deal ----------------------------------------------------------

    @Test
    fun `the deal is seven columns and a stock of twenty-four`() {
        val state = KlondikeRules.initialState(config())
        assertEquals(TABLEAU_PILES, state.tableau.size)
        state.tableau.forEachIndexed { index, pile ->
            assertEquals("column ${index + 1}", index + 1, pile.faceDown.size + pile.faceUp.size)
            assertEquals("only the last card is turned up", 1, pile.faceUp.size)
        }
        assertEquals(24, state.stock.size)
        assertTrue(state.waste.isEmpty())
        assertTrue(state.foundations.all { it.isEmpty() })
    }

    @Test
    fun `the pack is dealt whole and no card is dealt twice`() {
        val state = KlondikeRules.initialState(config())
        val all = state.stock + state.waste + state.tableau.flatMap { it.faceDown + it.faceUp }
        assertEquals(52, all.size)
        assertEquals(52, all.distinct().size)
    }

    @Test
    fun `a table for more than one seat is refused`() {
        val two = TableConfig(
            gameId = "klondike",
            seats = (0..1).map { PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it) },
            optionsJson = json.encodeToString(KlondikeOptions()),
            seed = 1L,
        )
        assertThrows { KlondikeRules.initialState(two) }
    }

    // ---- stock and waste ---------------------------------------------------

    @Test
    fun `drawing one turns one card up`() {
        val state = KlondikeRules.initialState(config(KlondikeOptions(drawCount = 1)))
        val drawn = KlondikeRules.applyMove(state, 0, Draw)
        assertEquals(23, drawn.stock.size)
        assertEquals(1, drawn.waste.size)
        assertEquals("the top of the stock is the card turned", state.stock.last(), drawn.waste.last())
    }

    @Test
    fun `drawing three leaves the deepest of them showing`() {
        // Turning a packet of three over reverses it, so the card that was
        // furthest down is the one in play.
        val state = KlondikeRules.initialState(config(KlondikeOptions(drawCount = 3)))
        val drawn = KlondikeRules.applyMove(state, 0, Draw)
        assertEquals(21, drawn.stock.size)
        assertEquals(3, drawn.waste.size)
        val top3 = state.stock.takeLast(3)
        assertEquals(top3.reversed(), drawn.waste)
        assertEquals(top3.first(), drawn.waste.last())
    }

    @Test
    fun `the waste turns back over into a new stock`() {
        var state = KlondikeRules.initialState(config(KlondikeOptions(drawCount = 3)))
        repeat(8) { state = KlondikeRules.applyMove(state, 0, Draw) }
        assertTrue(state.stock.isEmpty())
        assertEquals(24, state.waste.size)
        assertTrue(state.canRedeal)

        val wasteBefore = state.waste
        val turned = KlondikeRules.applyMove(state, 0, Redeal)
        assertEquals(wasteBefore.reversed(), turned.stock)
        assertTrue(turned.waste.isEmpty())
        assertEquals(1, turned.redealsUsed)
    }

    @Test
    fun `a redeal limit of zero means one pass through the pack`() {
        var state = KlondikeRules.initialState(
            config(KlondikeOptions(drawCount = 3, redealLimit = 0)),
        )
        repeat(8) { state = KlondikeRules.applyMove(state, 0, Draw) }
        assertFalse("no turning it back over", state.canRedeal)
        assertFalse(KlondikeRules.legalMoves(state, 0).contains(Redeal))
    }

    // ---- building ----------------------------------------------------------

    @Test
    fun `foundations run ace upwards in one suit`() {
        assertTrue(acceptsOnFoundation(emptyList(), c(Rank.ACE, Suit.SPADES)))
        assertFalse(acceptsOnFoundation(emptyList(), c(Rank.TWO, Suit.SPADES)))

        val ace = listOf(c(Rank.ACE, Suit.SPADES))
        assertTrue(acceptsOnFoundation(ace, c(Rank.TWO, Suit.SPADES)))
        assertFalse("wrong suit", acceptsOnFoundation(ace, c(Rank.TWO, Suit.HEARTS)))
        assertFalse("skips a rank", acceptsOnFoundation(ace, c(Rank.THREE, Suit.SPADES)))
    }

    @Test
    fun `columns build down and in the other colour`() {
        assertTrue(buildsDown(c(Rank.NINE, Suit.HEARTS), c(Rank.TEN, Suit.SPADES)))
        assertTrue(buildsDown(c(Rank.NINE, Suit.DIAMONDS), c(Rank.TEN, Suit.CLUBS)))
        assertFalse("same colour", buildsDown(c(Rank.NINE, Suit.HEARTS), c(Rank.TEN, Suit.DIAMONDS)))
        assertFalse("wrong way up", buildsDown(c(Rank.JACK, Suit.HEARTS), c(Rank.TEN, Suit.SPADES)))
    }

    @Test
    fun `a run travels only if it is a run`() {
        assertTrue(
            isMovableRun(
                listOf(
                    c(Rank.TEN, Suit.SPADES),
                    c(Rank.NINE, Suit.HEARTS),
                    c(Rank.EIGHT, Suit.CLUBS),
                ),
            ),
        )
        assertFalse(
            "colours repeat",
            isMovableRun(listOf(c(Rank.TEN, Suit.SPADES), c(Rank.NINE, Suit.CLUBS))),
        )
        assertTrue("one card is a run", isMovableRun(listOf(c(Rank.TEN, Suit.SPADES))))
        assertFalse(isMovableRun(emptyList()))
    }

    @Test
    fun `an empty column takes a king, or anything if the table says so`() {
        assertTrue(acceptsInSpace(c(Rank.KING, Suit.SPADES), kingsOnly = true))
        assertFalse(acceptsInSpace(c(Rank.QUEEN, Suit.SPADES), kingsOnly = true))
        assertTrue(acceptsInSpace(c(Rank.TWO, Suit.SPADES), kingsOnly = false))
    }

    // ---- moving ------------------------------------------------------------

    @Test
    fun `emptying a column turns the card underneath up`() {
        val base = KlondikeRules.initialState(config())
        // Column 1 holds one buried card and a red six; column 2's black seven
        // will take it.
        val state = base.copy(
            tableau = base.tableau.toMutableList().also {
                it[1] = TableauPile(
                    faceDown = listOf(c(Rank.ACE, Suit.CLUBS)),
                    faceUp = listOf(c(Rank.SIX, Suit.HEARTS)),
                )
                it[2] = TableauPile(faceUp = listOf(c(Rank.SEVEN, Suit.SPADES)))
            },
        )
        val move = MoveCards(Spot.tableau(1), Spot.tableau(2))
        assertTrue(KlondikeRules.legalMoves(state, 0).contains(move))

        val after = KlondikeRules.applyMove(state, 0, move)
        assertEquals(listOf(c(Rank.ACE, Suit.CLUBS)), after.tableau[1].faceUp)
        assertTrue("nothing left buried", after.tableau[1].faceDown.isEmpty())
        assertEquals(
            listOf(c(Rank.SEVEN, Suit.SPADES), c(Rank.SIX, Suit.HEARTS)),
            after.tableau[2].faceUp,
        )
    }

    @Test
    fun `shifting a whole column into an empty one is not offered`() {
        // It gains nothing and would let a game shuffle back and forth for ever.
        val base = KlondikeRules.initialState(config())
        val state = base.copy(
            tableau = base.tableau.toMutableList().also {
                it[0] = TableauPile(faceUp = listOf(c(Rank.KING, Suit.SPADES)))
                it[1] = TableauPile()
            },
        )
        assertFalse(
            KlondikeRules.legalMoves(state, 0)
                .contains(MoveCards(Spot.tableau(0), Spot.tableau(1))),
        )
    }

    @Test
    fun `an illegal move is refused rather than applied`() {
        val state = KlondikeRules.initialState(config())
        // Nothing is ever taken off the stock by hand, and an empty foundation
        // has nothing to give — neither depends on how the pack fell.
        assertThrows {
            KlondikeRules.applyMove(state, 0, MoveCards(Spot.stock, Spot.tableau(0)))
        }
        assertThrows {
            KlondikeRules.applyMove(state, 0, MoveCards(Spot.foundation(0), Spot.tableau(0)))
        }
        assertThrows { KlondikeRules.applyMove(state, 1, Draw) }
    }

    // ---- finishing ---------------------------------------------------------

    @Test
    fun `four full foundations is out`() {
        val base = KlondikeRules.initialState(config())
        val full = Suit.entries.map { suit -> Rank.standard.map { Card(it, suit) } }
        val won = base.copy(foundations = full, tableau = List(TABLEAU_PILES) { TableauPile() })
        assertTrue(won.isWon)
        assertTrue(KlondikeRules.isFinished(won))
        assertNull(KlondikeRules.currentSeat(won))
        assertTrue(KlondikeRules.summary(won).contains("Out"))
    }

    @Test
    fun `a deal with nothing left to try is finished too`() {
        val base = KlondikeRules.initialState(config())
        // No stock, no waste, and two columns that cannot touch each other.
        val stuck = base.copy(
            stock = emptyList(),
            waste = emptyList(),
            tableau = List(TABLEAU_PILES) { index ->
                when (index) {
                    0 -> TableauPile(faceUp = listOf(c(Rank.FIVE, Suit.SPADES)))
                    1 -> TableauPile(faceUp = listOf(c(Rank.NINE, Suit.CLUBS)))
                    else -> TableauPile(faceUp = listOf(c(Rank.SEVEN, Suit.CLUBS)))
                }
            },
        )
        assertFalse(stuck.isWon)
        assertTrue(KlondikeRules.isFinished(stuck))
        assertTrue(KlondikeRules.summary(stuck).contains("Blocked"))
    }

    @Test
    fun `a deal can be played a long way without the rules complaining`() {
        var state = KlondikeRules.initialState(config())
        var guard = 400
        while (!KlondikeRules.isFinished(state) && guard-- > 0) {
            val legal = KlondikeRules.legalMoves(state, 0)
            state = KlondikeRules.applyMove(state, 0, KlondikeAi.chooseMove(state, 0, legal))
            // Every card is somewhere, and only ever in one place.
            val all = state.stock + state.waste +
                state.foundations.flatten() +
                state.tableau.flatMap { it.faceDown + it.faceUp }
            assertEquals("no card lost or copied", 52, all.size)
            assertEquals(52, all.distinct().size)
        }
    }

    // ---- housekeeping ------------------------------------------------------

    @Test
    fun `restart deals the same pack again`() {
        val state = KlondikeRules.initialState(config())
        val played = KlondikeRules.applyMove(state, 0, Draw)
        val again = KlondikeRules.restart(played)
        assertEquals(state.tableau, again.tableau)
        assertEquals(state.stock, again.stock)
        assertEquals(0, again.moves)
    }

    @Test
    fun `nothing is hidden from the only player`() {
        val state = KlondikeRules.initialState(config())
        assertEquals(state, KlondikeRules.viewFor(state, 0))
    }

    @Test
    fun `state and moves survive a round trip`() {
        val state = KlondikeRules.applyMove(KlondikeRules.initialState(config()), 0, Draw)
        assertEquals(state, KlondikeRules.decodeState(KlondikeRules.encodeState(state)))

        listOf<KlondikeMove>(
            Draw,
            Redeal,
            MoveCards(Spot.waste, Spot.foundation(2)),
            MoveCards(Spot.tableau(3), Spot.tableau(5), 4),
        ).forEach {
            assertEquals(it, KlondikeRules.decodeMove(KlondikeRules.encodeMove(it)))
        }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected the rules to reject that")
        } catch (expected: IllegalArgumentException) {
            // Rejected, which is the point.
        }
    }
}
