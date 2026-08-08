package org.prolibertate.games.game.solitaire

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
import org.prolibertate.games.game.freecell.COLUMNS as FREECELL_COLUMNS
import org.prolibertate.games.game.freecell.FreeCellOptions
import org.prolibertate.games.game.freecell.FreeCellRules
import org.prolibertate.games.game.freecell.MoveTo
import org.prolibertate.games.game.freecell.Place
import org.prolibertate.games.game.freecell.maxRunLength
import org.prolibertate.games.game.pyramid.PYRAMID_SIZE
import org.prolibertate.games.game.pyramid.PyramidOptions
import org.prolibertate.games.game.pyramid.PyramidRules
import org.prolibertate.games.game.pyramid.PyramidZone
import org.prolibertate.games.game.pyramid.TARGET
import org.prolibertate.games.game.pyramid.coveredBy
import org.prolibertate.games.game.pyramid.isExposed
import org.prolibertate.games.game.pyramid.rowOf
import org.prolibertate.games.game.pyramid.valueOf
import org.prolibertate.games.game.spider.COLUMNS as SPIDER_COLUMNS
import org.prolibertate.games.game.spider.DealRow
import org.prolibertate.games.game.spider.RUN_LENGTH
import org.prolibertate.games.game.spider.SpiderOptions
import org.prolibertate.games.game.spider.SpiderRules
import org.prolibertate.games.game.spider.isCompleteRun
import org.prolibertate.games.game.spider.landsOn
import org.prolibertate.games.game.spider.sameSuitRun
import org.prolibertate.games.game.spider.spiderDeck

/**
 * The three patiences added together, tested together.
 *
 * They share the ace-low ordering and little else, so what is worth pinning is
 * each one's own shape: how many cards it deals, what it will let travel, and
 * what counts as finished.
 */
class SolitaireRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun soloConfig(gameId: String, optionsJson: String, seed: Long = 3L) = TableConfig(
        gameId = gameId,
        seats = listOf(PlayerSlot(seat = 0, name = "P0", kind = PlayerKind.HUMAN_LOCAL, team = 0)),
        optionsJson = optionsJson,
        seed = seed,
    )

    private fun c(rank: Rank, suit: Suit) = Card(rank, suit)

    // ---- shared ------------------------------------------------------------

    @Test
    fun `the ace is low for all of them`() {
        assertEquals(1, Rank.ACE.patienceOrder)
        assertEquals(11, Rank.JACK.patienceOrder)
        assertEquals(13, Rank.KING.patienceOrder)
        assertTrue(buildsDownAlternating(c(Rank.ACE, Suit.HEARTS), c(Rank.TWO, Suit.SPADES)))
        assertTrue(buildsDownInSuit(c(Rank.ACE, Suit.SPADES), c(Rank.TWO, Suit.SPADES)))
        assertFalse(buildsDownInSuit(c(Rank.ACE, Suit.HEARTS), c(Rank.TWO, Suit.SPADES)))
    }

    // ---- FreeCell ----------------------------------------------------------

    @Test
    fun `FreeCell deals the whole pack face up`() {
        val state = FreeCellRules.initialState(
            soloConfig("freecell", json.encodeToString(FreeCellOptions())),
        )
        assertEquals(FREECELL_COLUMNS, state.tableau.size)
        // Fifty-two into eight is four sevens and four sixes.
        assertEquals(listOf(7, 7, 7, 7, 6, 6, 6, 6), state.tableau.map { it.size })
        val all = state.tableau.flatten()
        assertEquals(52, all.size)
        assertEquals(52, all.distinct().size)
        assertEquals(4, state.freeCellCount)
        assertEquals(0, state.emptyColumns)
    }

    @Test
    fun `how much can travel depends on cells and spaces`() {
        // One card per free cell plus the card itself, doubled for each empty
        // column that can be borrowed as a staging pile.
        assertEquals(5, maxRunLength(freeCells = 4, emptyColumns = 0, intoEmptyColumn = false))
        assertEquals(10, maxRunLength(freeCells = 4, emptyColumns = 1, intoEmptyColumn = false))
        assertEquals(20, maxRunLength(freeCells = 4, emptyColumns = 2, intoEmptyColumn = false))
        // Moving into an empty column cannot also stage through it.
        assertEquals(5, maxRunLength(freeCells = 4, emptyColumns = 1, intoEmptyColumn = true))
        assertEquals(1, maxRunLength(freeCells = 0, emptyColumns = 0, intoEmptyColumn = false))
    }

    @Test
    fun `FreeCell refuses a table for more than one`() {
        val two = TableConfig(
            gameId = "freecell",
            seats = (0..1).map { PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it) },
            optionsJson = json.encodeToString(FreeCellOptions()),
            seed = 1L,
        )
        assertThrows { FreeCellRules.initialState(two) }
    }

    @Test
    fun `FreeCell keeps every card wherever it is put`() {
        var state = FreeCellRules.initialState(
            soloConfig("freecell", json.encodeToString(FreeCellOptions())),
        )
        val ai = FirstLegalAi<org.prolibertate.games.game.freecell.FreeCellState,
            org.prolibertate.games.game.freecell.FreeCellMove>()
        var guard = 120
        while (!FreeCellRules.isFinished(state) && guard-- > 0) {
            val legal = FreeCellRules.legalMoves(state, 0)
            state = FreeCellRules.applyMove(state, 0, ai.chooseMove(state, 0, legal))
            val all = state.tableau.flatten() + state.foundations.flatten() + state.cells.filterNotNull()
            assertEquals("no card lost or copied", 52, all.size)
            assertEquals(52, all.distinct().size)
        }
    }

    @Test
    fun `parking a card takes it off the column and into the cell`() {
        val state = FreeCellRules.initialState(
            soloConfig("freecell", json.encodeToString(FreeCellOptions())),
        )
        val toCell = FreeCellRules.legalMoves(state, 0)
            .filterIsInstance<MoveTo>()
            .first { it.to.kind == org.prolibertate.games.game.freecell.CellKind.CELL }
        val column = toCell.from.index
        val card = state.tableau[column].last()

        val parked = FreeCellRules.applyMove(state, 0, toCell)
        assertEquals(3, parked.freeCellCount)
        assertEquals(card, parked.cells[toCell.to.index])
        assertEquals(state.tableau[column].size - 1, parked.tableau[column].size)
        // Deliberately not asserting the card can come back out. A parked card
        // that nothing will take is stranded, and that is the whole danger of
        // the cells rather than a fault in them.
    }

    @Test
    fun `an ace in a cell can always go home`() {
        val base = FreeCellRules.initialState(
            soloConfig("freecell", json.encodeToString(FreeCellOptions())),
        )
        val ace = c(Rank.ACE, Suit.SPADES)
        val held = base.copy(cells = listOf(ace, null, null, null))
        assertTrue(
            "an ace is the one card a cell can never strand",
            FreeCellRules.legalMoves(held, 0).contains(
                MoveTo(Place.cell(0), Place.foundation(Suit.SPADES.ordinal), 1),
            ),
        )
    }

    // ---- Spider ------------------------------------------------------------

    @Test
    fun `a Spider pack is always a hundred and four`() {
        listOf(1, 2, 4).forEach { suits ->
            val deck = spiderDeck(suits)
            assertEquals("$suits suits", 104, deck.size)
            assertEquals("$suits suits", suits, deck.map { it.suit }.distinct().size)
            // Eight of every rank, however the suits were shared out.
            assertEquals(8, deck.count { it.rank == Rank.KING })
        }
    }

    @Test
    fun `Spider deals fifty-four and keeps fifty back`() {
        val state = SpiderRules.initialState(
            soloConfig("spider", json.encodeToString(SpiderOptions())),
        )
        assertEquals(SPIDER_COLUMNS, state.tableau.size)
        assertEquals(listOf(6, 6, 6, 6, 5, 5, 5, 5, 5, 5), state.tableau.map { it.faceDown.size + it.faceUp.size })
        state.tableau.forEach { assertEquals("only the last is turned up", 1, it.faceUp.size) }
        assertEquals(50, state.stock.size)
        assertEquals(0, state.completed)
    }

    @Test
    fun `only a same-suit run travels, but anything lands on a higher card`() {
        val spades = listOf(c(Rank.TEN, Suit.SPADES), c(Rank.NINE, Suit.SPADES))
        val mixed = listOf(c(Rank.TEN, Suit.SPADES), c(Rank.NINE, Suit.HEARTS))
        assertTrue(sameSuitRun(spades))
        assertFalse("a mixed run cannot travel as one", sameSuitRun(mixed))
        // Landing is looser than travelling: any suit, one rank higher.
        assertTrue(landsOn(c(Rank.NINE, Suit.HEARTS), c(Rank.TEN, Suit.SPADES)))
        assertFalse(landsOn(c(Rank.NINE, Suit.HEARTS), c(Rank.JACK, Suit.SPADES)))
    }

    @Test
    fun `a complete run is king down to ace in one suit`() {
        val run = Rank.standard.sortedByDescending { it.patienceOrder }.map { c(it, Suit.SPADES) }
        assertEquals(RUN_LENGTH, run.size)
        assertTrue(isCompleteRun(run))
        // One card of the wrong suit and it is not going anywhere.
        val broken = run.toMutableList().also { it[5] = c(it[5].rank, Suit.HEARTS) }
        assertFalse(isCompleteRun(broken))
        assertFalse(isCompleteRun(run.drop(1)))
    }

    @Test
    fun `a row cannot be dealt onto an empty column`() {
        val state = SpiderRules.initialState(
            soloConfig("spider", json.encodeToString(SpiderOptions())),
        )
        assertTrue(state.canDeal)
        assertTrue(SpiderRules.legalMoves(state, 0).contains(DealRow))

        val emptied = state.copy(
            tableau = state.tableau.toMutableList().also {
                it[0] = org.prolibertate.games.game.spider.SpiderPile()
            },
        )
        assertFalse(emptied.canDeal)
        assertFalse(SpiderRules.legalMoves(emptied, 0).contains(DealRow))
    }

    @Test
    fun `dealing a row puts one card on every column`() {
        val state = SpiderRules.initialState(
            soloConfig("spider", json.encodeToString(SpiderOptions())),
        )
        val before = state.tableau.map { it.faceUp.size }
        val dealt = SpiderRules.applyMove(state, 0, DealRow)
        assertEquals(40, dealt.stock.size)
        dealt.tableau.forEachIndexed { index, pile ->
            assertEquals(before[index] + 1, pile.faceUp.size)
        }
    }

    // ---- Pyramid -----------------------------------------------------------

    @Test
    fun `the pyramid is twenty-eight cards in seven rows`() {
        assertEquals(28, PYRAMID_SIZE)
        assertEquals(0, rowOf(0))
        assertEquals(1, rowOf(1))
        assertEquals(1, rowOf(2))
        assertEquals(2, rowOf(3))
        assertEquals(6, rowOf(27))

        val state = PyramidRules.initialState(
            soloConfig("pyramid", json.encodeToString(PyramidOptions())),
        )
        assertEquals(PYRAMID_SIZE, state.pyramid.size)
        assertEquals(24, state.stock.size)
        assertTrue(state.pyramid.all { it != null })
    }

    @Test
    fun `a card is buried until both the cards on it are gone`() {
        // The apex rests on the two cards of the second row.
        assertEquals(listOf(1, 2), coveredBy(0))
        // The bottom row rests on nothing.
        assertTrue(coveredBy(27).isEmpty())

        val pyramid = MutableList<Card?>(PYRAMID_SIZE) { c(Rank.FIVE, Suit.SPADES) }
        assertFalse("the apex starts buried", isExposed(pyramid, 0))
        pyramid[1] = null
        assertFalse("one of the two is not enough", isExposed(pyramid, 0))
        pyramid[2] = null
        assertTrue(isExposed(pyramid, 0))
        // A place already taken is not exposed, it is gone.
        pyramid[0] = null
        assertFalse(isExposed(pyramid, 0))
    }

    @Test
    fun `pairs make thirteen, and a king makes it alone`() {
        assertEquals(1, valueOf(c(Rank.ACE, Suit.SPADES)))
        assertEquals(11, valueOf(c(Rank.JACK, Suit.SPADES)))
        assertEquals(TARGET, valueOf(c(Rank.KING, Suit.SPADES)))
        assertEquals(TARGET, valueOf(c(Rank.ACE, Suit.SPADES)) + valueOf(c(Rank.QUEEN, Suit.HEARTS)))
        assertEquals(TARGET, valueOf(c(Rank.SIX, Suit.CLUBS)) + valueOf(c(Rank.SEVEN, Suit.DIAMONDS)))
    }

    @Test
    fun `only the bottom row is in play at the start`() {
        val state = PyramidRules.initialState(
            soloConfig("pyramid", json.encodeToString(PyramidOptions())),
        )
        val reachable = PyramidRules.available(state)
        val fromPyramid = reachable.filter { it.first.zone == PyramidZone.PYRAMID }
        assertEquals("the seven of the bottom row", 7, fromPyramid.size)
        assertTrue(fromPyramid.all { rowOf(it.first.index) == 6 })
        // Nothing has been turned yet, so the waste offers nothing.
        assertTrue(reachable.none { it.first.zone == PyramidZone.WASTE })
    }

    @Test
    fun `drawing turns a card and the waste can be turned back`() {
        var state = PyramidRules.initialState(
            soloConfig("pyramid", json.encodeToString(PyramidOptions(redeals = 1))),
        )
        val stock = state.stock.size
        state = PyramidRules.applyMove(state, 0, org.prolibertate.games.game.pyramid.DrawCard)
        assertEquals(stock - 1, state.stock.size)
        assertEquals(1, state.waste.size)

        var guard = 40
        while (state.stock.isNotEmpty() && guard-- > 0) {
            state = PyramidRules.applyMove(state, 0, org.prolibertate.games.game.pyramid.DrawCard)
        }
        assertTrue(state.canRedeal)
        val turned = PyramidRules.applyMove(state, 0, org.prolibertate.games.game.pyramid.RecycleWaste)
        assertTrue(turned.waste.isEmpty())
        assertEquals(1, turned.redealsUsed)
        assertFalse("only the one redeal was allowed", turned.canRedeal)
    }

    @Test
    fun `a cleared pyramid is won`() {
        val state = PyramidRules.initialState(
            soloConfig("pyramid", json.encodeToString(PyramidOptions())),
        )
        val cleared = state.copy(pyramid = List(PYRAMID_SIZE) { null })
        assertTrue(cleared.isWon)
        assertTrue(PyramidRules.isFinished(cleared))
        assertNull(PyramidRules.currentSeat(cleared))
        assertTrue(PyramidRules.summary(cleared).contains("Cleared"))
    }

    // ---- codecs ------------------------------------------------------------

    @Test
    fun `each of them survives a round trip`() {
        val freecell = FreeCellRules.initialState(
            soloConfig("freecell", json.encodeToString(FreeCellOptions())),
        )
        assertEquals(freecell, FreeCellRules.decodeState(FreeCellRules.encodeState(freecell)))

        val spider = SpiderRules.initialState(
            soloConfig("spider", json.encodeToString(SpiderOptions(suits = 4))),
        )
        assertEquals(spider, SpiderRules.decodeState(SpiderRules.encodeState(spider)))
        assertEquals(DealRow, SpiderRules.decodeMove(SpiderRules.encodeMove(DealRow)))

        val pyramid = PyramidRules.initialState(
            soloConfig("pyramid", json.encodeToString(PyramidOptions())),
        )
        assertEquals(pyramid, PyramidRules.decodeState(PyramidRules.encodeState(pyramid)))
        val king = org.prolibertate.games.game.pyramid.TakeKing(PyramidSpotAt(3))
        assertEquals(king, PyramidRules.decodeMove(PyramidRules.encodeMove(king)))
    }

    private fun PyramidSpotAt(index: Int) =
        org.prolibertate.games.game.pyramid.PyramidSpot(PyramidZone.PYRAMID, index)

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected the rules to reject that")
        } catch (expected: IllegalArgumentException) {
            // Rejected, which is the point.
        }
    }
}
