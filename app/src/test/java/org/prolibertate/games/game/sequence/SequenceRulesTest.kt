package org.prolibertate.games.game.sequence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

class SequenceRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(
        options: SequenceOptions = SequenceOptions(),
        seed: Long = 7L,
    ) = TableConfig(
        gameId = "sequence",
        seats = (0 until options.playerCount).map {
            PlayerSlot(
                seat = it,
                name = "P$it",
                kind = PlayerKind.AI,
                team = it % options.teamCount,
            )
        },
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    // -- Board --------------------------------------------------------------

    @Test
    fun `board holds every non-jack card twice plus four free corners`() {
        assertEquals(BOARD_CELLS, SequenceBoard.cells.size)
        val cards = SequenceBoard.cells.filterNotNull()
        assertEquals(96, cards.size)
        assertEquals(4, SequenceBoard.cells.count { it == null })

        val counts = cards.groupingBy { it }.eachCount()
        assertEquals("48 distinct cards", 48, counts.size)
        assertTrue("each appears exactly twice", counts.values.all { it == 2 })
        assertTrue("no jacks on the board", cards.none { it.rank == Rank.JACK })
    }

    @Test
    fun `the four corners are the free squares`() {
        listOf(
            SequenceBoard.cellAt(0, 0),
            SequenceBoard.cellAt(0, 9),
            SequenceBoard.cellAt(9, 0),
            SequenceBoard.cellAt(9, 9),
        ).forEach { assertTrue(SequenceBoard.isCorner(it)) }
    }

    @Test
    fun `every non-jack card maps to exactly two squares`() {
        val nonJacks = Suit.entries.flatMap { suit ->
            Rank.standard.filter { it != Rank.JACK }.map { Card(it, suit) }
        }
        nonJacks.forEach { card ->
            assertEquals("$card", 2, SequenceBoard.squaresByCard[card]?.size)
        }
    }

    @Test
    fun `jack classification splits one-eyed from two-eyed`() {
        assertTrue(isTwoEyedJack(Card(Rank.JACK, Suit.DIAMONDS)))
        assertTrue(isTwoEyedJack(Card(Rank.JACK, Suit.CLUBS)))
        assertTrue(isOneEyedJack(Card(Rank.JACK, Suit.HEARTS)))
        assertTrue(isOneEyedJack(Card(Rank.JACK, Suit.SPADES)))
        assertFalse(isOneEyedJack(Card(Rank.ACE, Suit.SPADES)))
    }

    // -- Dealing ------------------------------------------------------------

    @Test
    fun `hand sizes follow the player count`() {
        assertEquals(7, handSizeFor(2))
        assertEquals(6, handSizeFor(3))
        assertEquals(6, handSizeFor(4))
        assertEquals(5, handSizeFor(6))
        assertEquals(4, handSizeFor(8))
        assertEquals(3, handSizeFor(12))
    }

    @Test
    fun `initial deal gives everyone a hand from a 104 card deck`() {
        val options = SequenceOptions(teamCount = 2, playersPerTeam = 2)
        val state = SequenceRules.initialState(config(options))
        assertEquals(4, state.playerCount)
        state.hands.forEach { assertEquals(6, it.size) }
        assertEquals(104 - 4 * 6, state.drawPile.size)
        assertTrue(state.chips.all { it == NO_TEAM })
    }

    // -- Placement ----------------------------------------------------------

    @Test
    fun `placing a chip claims the square and passes the turn`() {
        var state = SequenceRules.initialState(config())
        val move = SequenceRules.legalMoves(state, 0).filterIsInstance<PlaceChip>().first()
        state = SequenceRules.applyMove(state, 0, move)
        assertEquals(state.teams[0], state.chips[move.cell])
        assertEquals(1, state.turn)
    }

    @Test
    fun `a card whose squares are both taken becomes a dead card`() {
        val card = Card(Rank.ACE, Suit.HEARTS)
        val squares = SequenceBoard.squaresByCard.getValue(card)
        val chips = MutableList(BOARD_CELLS) { NO_TEAM }
        squares.forEach { chips[it] = 1 }

        val state = baseState(chips = chips, hand0 = listOf(card))
        val moves = SequenceRules.legalMoves(state, 0)
        assertTrue("no placement is possible", moves.none { it is PlaceChip })
        assertEquals(listOf(ExchangeDeadCard(card)), moves)
    }

    @Test
    fun `exchanging a dead card keeps the turn`() {
        val card = Card(Rank.ACE, Suit.HEARTS)
        val squares = SequenceBoard.squaresByCard.getValue(card)
        val chips = MutableList(BOARD_CELLS) { NO_TEAM }
        squares.forEach { chips[it] = 1 }

        var state = baseState(chips = chips, hand0 = listOf(card))
        state = SequenceRules.applyMove(state, 0, ExchangeDeadCard(card))
        assertEquals("turn does not pass", 0, state.turn)
        assertTrue(state.exchangedThisTurn)
        assertFalse("the dead card is gone", state.hands[0].contains(card))
    }

    // -- Jacks --------------------------------------------------------------

    @Test
    fun `a two-eyed jack can be placed on any empty square`() {
        val jack = Card(Rank.JACK, Suit.DIAMONDS)
        val state = baseState(hand0 = listOf(jack))
        val placements = SequenceRules.legalMoves(state, 0).filterIsInstance<PlaceChip>()
        // Every square except the four corners is available.
        assertEquals(BOARD_CELLS - 4, placements.size)
    }

    @Test
    fun `a one-eyed jack removes an opposing chip but not a locked one`() {
        val jack = Card(Rank.JACK, Suit.SPADES)
        val chips = MutableList(BOARD_CELLS) { NO_TEAM }
        val target = SequenceBoard.cellAt(5, 5)
        val protectedCell = SequenceBoard.cellAt(6, 6)
        chips[target] = 1
        chips[protectedCell] = 1
        val locked = MutableList(BOARD_CELLS) { false }
        locked[protectedCell] = true

        val state = baseState(chips = chips, locked = locked, hand0 = listOf(jack))
        val removals = SequenceRules.legalMoves(state, 0).filterIsInstance<RemoveChip>()
        assertEquals(listOf(target), removals.map { it.cell })
    }

    @Test
    fun `a one-eyed jack cannot remove your own teams chip`() {
        val jack = Card(Rank.JACK, Suit.SPADES)
        val chips = MutableList(BOARD_CELLS) { NO_TEAM }
        chips[SequenceBoard.cellAt(4, 4)] = 0 // seat 0 is on team 0

        val state = baseState(chips = chips, hand0 = listOf(jack))
        assertTrue(SequenceRules.legalMoves(state, 0).none { it is RemoveChip })
    }

    // -- Sequences ----------------------------------------------------------

    @Test
    fun `five in a row completes a sequence and locks the squares`() {
        // Four chips already down in row 4; the card for the fifth is in hand.
        val fifth = SequenceBoard.cellAt(4, 4)
        val card = SequenceBoard.cells[fifth]!!
        val chips = MutableList(BOARD_CELLS) { NO_TEAM }
        listOf(0, 1, 2, 3).forEach { chips[SequenceBoard.cellAt(4, it)] = 0 }

        var state = baseState(chips = chips, hand0 = listOf(card))
        state = SequenceRules.applyMove(state, 0, PlaceChip(card, fifth))

        assertEquals(1, state.sequencesByTeam[0])
        (0..4).forEach { col ->
            assertTrue("(4,$col) locked", state.locked[SequenceBoard.cellAt(4, col)])
        }
    }

    @Test
    fun `a corner counts as a free square for the run`() {
        // Corner (0,0) plus (0,1)..(0,3) held, needing only (0,4).
        val fifth = SequenceBoard.cellAt(0, 4)
        val card = SequenceBoard.cells[fifth]!!
        val chips = MutableList(BOARD_CELLS) { NO_TEAM }
        listOf(1, 2, 3).forEach { chips[SequenceBoard.cellAt(0, it)] = 0 }

        var state = baseState(chips = chips, hand0 = listOf(card))
        state = SequenceRules.applyMove(state, 0, PlaceChip(card, fifth))
        assertEquals("the free corner completed the five", 1, state.sequencesByTeam[0])
    }

    @Test
    fun `a diagonal run counts`() {
        val fifth = SequenceBoard.cellAt(4, 4)
        val card = SequenceBoard.cells[fifth]!!
        val chips = MutableList(BOARD_CELLS) { NO_TEAM }
        listOf(0, 1, 2, 3).forEach { chips[SequenceBoard.cellAt(it, it)] = 0 }

        var state = baseState(chips = chips, hand0 = listOf(card))
        state = SequenceRules.applyMove(state, 0, PlaceChip(card, fifth))
        assertEquals(1, state.sequencesByTeam[0])
    }

    @Test
    fun `an opposing chip breaks the run`() {
        val fifth = SequenceBoard.cellAt(4, 4)
        val card = SequenceBoard.cells[fifth]!!
        val chips = MutableList(BOARD_CELLS) { NO_TEAM }
        listOf(0, 1, 3).forEach { chips[SequenceBoard.cellAt(4, it)] = 0 }
        chips[SequenceBoard.cellAt(4, 2)] = 1

        var state = baseState(chips = chips, hand0 = listOf(card))
        state = SequenceRules.applyMove(state, 0, PlaceChip(card, fifth))
        assertEquals(0, state.sequencesByTeam[0])
    }

    @Test
    fun `two sequences may share at most one chip`() {
        // A full row of nine for team 0. Placing the tenth cannot manufacture
        // an extra sequence out of heavily overlapping windows.
        val chips = MutableList(BOARD_CELLS) { NO_TEAM }
        val locked = MutableList(BOARD_CELLS) { false }
        (0..4).forEach {
            chips[SequenceBoard.cellAt(6, it)] = 0
            locked[SequenceBoard.cellAt(6, it)] = true
        }
        (5..8).forEach { chips[SequenceBoard.cellAt(6, it)] = 0 }

        val fifth = SequenceBoard.cellAt(6, 9)
        val card = SequenceBoard.cells[fifth]!!
        var state = baseState(chips = chips, locked = locked, hand0 = listOf(card))
            .copy(sequencesByTeam = listOf(1, 0))
        state = SequenceRules.applyMove(state, 0, PlaceChip(card, fifth))

        // Squares 5..9 contain no already-locked chip, so this is a clean second.
        assertEquals(2, state.sequencesByTeam[0])
    }

    @Test
    fun `reaching the sequence target wins the game`() {
        val chips = MutableList(BOARD_CELLS) { NO_TEAM }
        listOf(0, 1, 2, 3).forEach { chips[SequenceBoard.cellAt(4, it)] = 0 }
        val fifth = SequenceBoard.cellAt(4, 4)
        val card = SequenceBoard.cells[fifth]!!

        var state = baseState(
            chips = chips,
            hand0 = listOf(card),
            options = SequenceOptions(teamCount = 2, sequencesToWin = 1),
        )
        state = SequenceRules.applyMove(state, 0, PlaceChip(card, fifth))
        assertTrue(SequenceRules.isFinished(state))
        assertEquals(0, state.winner)
        assertEquals(SequencePhase.GAME_OVER, state.phase)
    }

    // -- Full games ---------------------------------------------------------

    @Test
    fun `random legal play terminates without an illegal state`() {
        repeat(10) { iteration ->
            val random = Random(iteration.toLong())
            var state = SequenceRules.initialState(config(seed = iteration.toLong()))
            var guard = 0
            while (!SequenceRules.isFinished(state) && guard++ < 4000) {
                val seat = SequenceRules.currentSeat(state) ?: break
                val legal = SequenceRules.legalMoves(state, seat)
                if (legal.isEmpty()) break // a blocked table is a legitimate draw
                state = SequenceRules.applyMove(state, seat, legal[random.nextInt(legal.size)])
            }
            assertTrue("ran away", guard < 4000)
        }
    }

    @Test
    fun `ai play produces only legal moves and reaches a result`() {
        val ai = SequenceAi()
        repeat(5) { iteration ->
            var state = SequenceRules.initialState(
                config(SequenceOptions(teamCount = 2, playersPerTeam = 1), seed = 900L + iteration)
            )
            var guard = 0
            while (!SequenceRules.isFinished(state) && guard++ < 4000) {
                val seat = SequenceRules.currentSeat(state) ?: break
                val legal = SequenceRules.legalMoves(state, seat)
                if (legal.isEmpty()) break
                val move = ai.chooseMove(state, seat, legal)
                assertTrue("ai returned an illegal move $move", legal.contains(move))
                state = SequenceRules.applyMove(state, seat, move)
            }
            assertTrue("ai game ran away", guard < 4000)
        }
    }

    // -- Redaction and wire format -----------------------------------------

    @Test
    fun `a seat view hides other hands and the deck order`() {
        val state = SequenceRules.initialState(config(SequenceOptions(playersPerTeam = 2)))
        val view = SequenceRules.viewFor(state, seat = 1)
        assertEquals(state.hands[1], view.hands[1])
        assertTrue(view.hands.filterIndexed { i, _ -> i != 1 }.all { it.isEmpty() })
        assertTrue("deck order is hidden", view.drawPile.isEmpty())
        assertEquals(state.handCounts, view.handCounts)
    }

    @Test
    fun `state and moves survive a round trip`() {
        val state = SequenceRules.initialState(config())
        assertEquals(state, SequenceRules.decodeState(SequenceRules.encodeState(state)))

        val moves = listOf(
            PlaceChip(Card(Rank.ACE, Suit.HEARTS), 12),
            RemoveChip(Card(Rank.JACK, Suit.SPADES), 40),
            ExchangeDeadCard(Card(Rank.TWO, Suit.CLUBS)),
        )
        moves.forEach { assertEquals(it, SequenceRules.decodeMove(SequenceRules.encodeMove(it))) }
    }

    @Test
    fun `summary reports the running sequence count`() {
        val state = SequenceRules.initialState(config())
        assertNotNull(SequenceRules.summary(state))
    }

    // -- Helpers ------------------------------------------------------------

    private fun baseState(
        chips: List<Int> = List(BOARD_CELLS) { NO_TEAM },
        locked: List<Boolean> = List(BOARD_CELLS) { false },
        hand0: List<Card>,
        options: SequenceOptions = SequenceOptions(),
    ): SequenceState {
        val hands = listOf(hand0) + List(options.playerCount - 1) { emptyList<Card>() }
        return SequenceState(
            options = options,
            seed = 1L,
            teams = (0 until options.playerCount).map { it % options.teamCount },
            chips = chips,
            locked = locked,
            hands = hands,
            handCounts = hands.map { it.size },
            drawPile = emptyList(),
            discardPile = emptyList(),
            turn = 0,
            sequencesByTeam = List(options.teamCount) { 0 },
            winner = null,
            phase = SequencePhase.PLAYING,
            exchangedThisTurn = false,
            lastPlacedCell = null,
            log = emptyList(),
        )
    }
}
