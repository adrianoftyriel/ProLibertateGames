package org.prolibertate.games.game.yahtzee

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig

class YahtzeeRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(options: YahtzeeOptions = YahtzeeOptions(), seed: Long = 11L) = TableConfig(
        gameId = "yahtzee",
        seats = (0 until options.playerCount).map {
            PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it)
        },
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    // ---- scoring -----------------------------------------------------------

    @Test
    fun `the upper six count only the die they name`() {
        val dice = listOf(3, 3, 5, 1, 3)
        assertEquals(1, scoreOf(YahtzeeCategory.ONES, dice))
        assertEquals(0, scoreOf(YahtzeeCategory.TWOS, dice))
        assertEquals(9, scoreOf(YahtzeeCategory.THREES, dice))
        assertEquals(5, scoreOf(YahtzeeCategory.FIVES, dice))
        assertEquals(0, scoreOf(YahtzeeCategory.SIXES, dice))
    }

    @Test
    fun `three and four of a kind pay the whole throw`() {
        val three = listOf(4, 4, 4, 2, 6)
        assertEquals(20, scoreOf(YahtzeeCategory.THREE_OF_A_KIND, three))
        assertEquals(0, scoreOf(YahtzeeCategory.FOUR_OF_A_KIND, three))

        val four = listOf(4, 4, 4, 4, 6)
        assertEquals(22, scoreOf(YahtzeeCategory.THREE_OF_A_KIND, four))
        assertEquals(22, scoreOf(YahtzeeCategory.FOUR_OF_A_KIND, four))

        assertEquals(0, scoreOf(YahtzeeCategory.THREE_OF_A_KIND, listOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `a full house is three of one and two of another, and nothing else`() {
        assertEquals(25, scoreOf(YahtzeeCategory.FULL_HOUSE, listOf(2, 2, 5, 5, 5)))
        assertEquals(0, scoreOf(YahtzeeCategory.FULL_HOUSE, listOf(2, 2, 2, 5, 6)))
        // Five of a kind is not a full house on the printed card, whatever the
        // joker variations do with it.
        assertEquals(0, scoreOf(YahtzeeCategory.FULL_HOUSE, listOf(4, 4, 4, 4, 4)))
    }

    @Test
    fun `straights want consecutive faces, and a repeat does not break them`() {
        assertEquals(4, longestRun(listOf(1, 2, 3, 4, 4)))
        assertEquals(5, longestRun(listOf(2, 3, 4, 5, 6)))
        assertEquals(1, longestRun(listOf(3, 3, 3, 3, 3)))

        assertEquals(30, scoreOf(YahtzeeCategory.SMALL_STRAIGHT, listOf(1, 2, 3, 4, 4)))
        assertEquals(0, scoreOf(YahtzeeCategory.LARGE_STRAIGHT, listOf(1, 2, 3, 4, 4)))
        assertEquals(40, scoreOf(YahtzeeCategory.LARGE_STRAIGHT, listOf(3, 4, 5, 6, 2)))
        // A large straight is a small one as well.
        assertEquals(30, scoreOf(YahtzeeCategory.SMALL_STRAIGHT, listOf(3, 4, 5, 6, 2)))
    }

    @Test
    fun `five of a kind is fifty, and chance is whatever is showing`() {
        assertEquals(50, scoreOf(YahtzeeCategory.YAHTZEE, listOf(6, 6, 6, 6, 6)))
        assertEquals(0, scoreOf(YahtzeeCategory.YAHTZEE, listOf(6, 6, 6, 6, 1)))
        assertEquals(19, scoreOf(YahtzeeCategory.CHANCE, listOf(6, 6, 4, 2, 1)))
        assertTrue(isYahtzee(listOf(2, 2, 2, 2, 2)))
        assertFalse(isYahtzee(listOf(2, 2, 2, 2, 3)))
    }

    @Test
    fun `the upper bonus arrives at sixty-three`() {
        var card = YahtzeeCard()
        // Exactly three of each is sixty-three, which is what the bonus is for.
        YahtzeeCategory.entries.filter { it.section == YahtzeeSection.UPPER }.forEach {
            card = card.with(it, it.face!! * 3)
        }
        assertEquals(63, card.upperSubtotal)
        assertEquals(35, card.upperBonus)

        var short = YahtzeeCard()
        YahtzeeCategory.entries.filter { it.section == YahtzeeSection.UPPER }.forEach {
            short = short.with(it, it.face!! * 2)
        }
        assertEquals(42, short.upperSubtotal)
        assertEquals(0, short.upperBonus)
    }

    @Test
    fun `a box can only be written in once`() {
        val card = YahtzeeCard().with(YahtzeeCategory.CHANCE, 20)
        assertTrue(card.isFilled(YahtzeeCategory.CHANCE))
        assertFalse(card.isFilled(YahtzeeCategory.ONES))
        try {
            card.with(YahtzeeCategory.CHANCE, 5)
            throw AssertionError("expected a rejection")
        } catch (expected: IllegalArgumentException) {
            // As intended.
        }
    }

    // ---- the state machine -------------------------------------------------

    @Test
    fun `a turn opens with one thing to do, and it is throwing`() {
        val state = YahtzeeRules.initialState(config())
        assertEquals(0, YahtzeeRules.currentSeat(state))
        val legal = YahtzeeRules.legalMoves(state, 0)
        assertEquals(listOf(RollDice(emptySet())), legal)
        // Nothing may be written in before the dice have been thrown.
        assertThrows { YahtzeeRules.applyMove(state, 0, ScoreIn(YahtzeeCategory.CHANCE)) }
    }

    @Test
    fun `a throw puts five dice on the table`() {
        val state = YahtzeeRules.initialState(config())
        val rolled = YahtzeeRules.applyMove(state, 0, RollDice())
        assertEquals(DICE_COUNT, rolled.dice.size)
        assertTrue(rolled.dice.all { it in 1..6 })
        assertEquals(1, rolled.rollsUsed)
        assertEquals(2, rolled.rollsLeft)
    }

    @Test
    fun `the same state and move always throw the same dice`() {
        // The host and every client run applyMove separately; a throw that
        // differed between them would split the game in two.
        val state = YahtzeeRules.initialState(config())
        val once = YahtzeeRules.applyMove(state, 0, RollDice())
        val again = YahtzeeRules.applyMove(state, 0, RollDice())
        assertEquals(once.dice, again.dice)

        val keptOnce = YahtzeeRules.applyMove(once, 0, RollDice(setOf(0, 1)))
        val keptAgain = YahtzeeRules.applyMove(once, 0, RollDice(setOf(0, 1)))
        assertEquals(keptOnce.dice, keptAgain.dice)
    }

    @Test
    fun `kept dice stay put and the rest are thrown again`() {
        val state = YahtzeeRules.applyMove(YahtzeeRules.initialState(config()), 0, RollDice())
        val kept = YahtzeeRules.applyMove(state, 0, RollDice(setOf(0, 2, 4)))
        assertEquals(state.dice[0], kept.dice[0])
        assertEquals(state.dice[2], kept.dice[2])
        assertEquals(state.dice[4], kept.dice[4])
        assertEquals(2, kept.rollsUsed)
    }

    @Test
    fun `there are three throws in a turn and no more`() {
        var state = YahtzeeRules.applyMove(YahtzeeRules.initialState(config()), 0, RollDice())
        state = YahtzeeRules.applyMove(state, 0, RollDice())
        state = YahtzeeRules.applyMove(state, 0, RollDice())
        assertEquals(ROLLS_PER_TURN, state.rollsUsed)
        assertEquals(0, state.rollsLeft)
        assertThrows { YahtzeeRules.applyMove(state, 0, RollDice()) }
        // Only writing it down is left.
        assertTrue(YahtzeeRules.legalMoves(state, 0).all { it is ScoreIn })
    }

    @Test
    fun `the order dice were tapped in does not matter`() {
        // The screen adds each die as it is tapped, so holding the third and
        // then the first once gave [2, 0]. As a list that was a different move
        // from [0, 2], and only the ascending one was ever offered as legal, so
        // the controller dropped it and the throw did nothing at all.
        val state = YahtzeeRules.applyMove(YahtzeeRules.initialState(config()), 0, RollDice())
        assertEquals(RollDice(setOf(2, 0)), RollDice(setOf(0, 2)))

        val legal = YahtzeeRules.legalMoves(state, 0)
        assertTrue(
            "a keep must be legal however it was built up",
            legal.contains(RollDice(setOf(2, 0))),
        )
        // And it has to actually hold those dice.
        val thrown = YahtzeeRules.applyMove(state, 0, RollDice(setOf(2, 0)))
        assertEquals(state.dice[0], thrown.dice[0])
        assertEquals(state.dice[2], thrown.dice[2])
        assertEquals(2, thrown.rollsUsed)
    }

    @Test
    fun `every way of keeping dice is on offer`() {
        // Thirty-two subsets of five dice, and each of them exactly once.
        val state = YahtzeeRules.applyMove(YahtzeeRules.initialState(config()), 0, RollDice())
        val rolls = YahtzeeRules.legalMoves(state, 0).filterIsInstance<RollDice>()
        assertEquals(32, rolls.size)
        assertEquals(32, rolls.map { it.keep }.distinct().size)
    }

    @Test
    fun `nothing may be kept before the first throw`() {
        val state = YahtzeeRules.initialState(config())
        assertThrows { YahtzeeRules.applyMove(state, 0, RollDice(setOf(0))) }
    }

    @Test
    fun `writing a box passes the dice on and the round turns over`() {
        val state = YahtzeeRules.applyMove(YahtzeeRules.initialState(config()), 0, RollDice())
        val written = YahtzeeRules.applyMove(state, 0, ScoreIn(YahtzeeCategory.CHANCE))
        assertEquals("the dice go back in the cup", emptyList<Int>(), written.dice)
        assertEquals(0, written.rollsUsed)
        assertEquals(1, written.turn)
        assertEquals("still the first round", 0, written.round)
        assertEquals(state.dice.sum(), written.cards[0][YahtzeeCategory.CHANCE])

        val second = YahtzeeRules.applyMove(written, 1, RollDice())
        val wrapped = YahtzeeRules.applyMove(second, 1, ScoreIn(YahtzeeCategory.CHANCE))
        assertEquals(0, wrapped.turn)
        assertEquals("round turns over once everyone has written", 1, wrapped.round)
    }

    @Test
    fun `a seat that is not on the clock cannot move`() {
        val state = YahtzeeRules.initialState(config())
        assertTrue(YahtzeeRules.legalMoves(state, 1).isEmpty())
        assertThrows { YahtzeeRules.applyMove(state, 1, RollDice()) }
    }

    @Test
    fun `a second Yahtzee pays a hundred, but only over a fifty`() {
        val base = YahtzeeRules.initialState(config())
        val fives = listOf(5, 5, 5, 5, 5)

        // The box already holds the fifty, so the bonus is earned.
        val earned = base.copy(
            dice = fives,
            rollsUsed = 1,
            cards = listOf(
                YahtzeeCard().with(YahtzeeCategory.YAHTZEE, YAHTZEE_SCORE),
                YahtzeeCard(),
            ),
        )
        val after = YahtzeeRules.applyMove(earned, 0, ScoreIn(YahtzeeCategory.FIVES))
        assertEquals(YAHTZEE_BONUS, after.yahtzeeBonuses[0])
        assertEquals(25, after.cards[0][YahtzeeCategory.FIVES])

        // A zero written there earlier forfeits it.
        val forfeited = base.copy(
            dice = fives,
            rollsUsed = 1,
            cards = listOf(YahtzeeCard().with(YahtzeeCategory.YAHTZEE, 0), YahtzeeCard()),
        )
        assertEquals(
            0,
            YahtzeeRules.applyMove(forfeited, 0, ScoreIn(YahtzeeCategory.FIVES)).yahtzeeBonuses[0],
        )

        // And it can be switched off at the table.
        val off = earned.copy(options = YahtzeeOptions(playerCount = 2, yahtzeeBonus = false))
        assertEquals(
            0,
            YahtzeeRules.applyMove(off, 0, ScoreIn(YahtzeeCategory.FIVES)).yahtzeeBonuses[0],
        )
    }

    @Test
    fun `a full card ends the game`() {
        var state = YahtzeeRules.initialState(config(YahtzeeOptions(playerCount = 1)))
        // Thirteen turns, each filling one box.
        var guard = 200
        while (!YahtzeeRules.isFinished(state) && guard-- > 0) {
            val legal = YahtzeeRules.legalMoves(state, state.turn)
            state = YahtzeeRules.applyMove(state, state.turn, YahtzeeAi.chooseMove(state, state.turn, legal))
        }
        assertTrue("the game should end on its own", guard > 0)
        assertTrue(YahtzeeRules.isFinished(state))
        assertTrue(state.cards[0].isComplete)
        assertEquals(YahtzeeCategory.entries.size, state.round)
        assertNull(YahtzeeRules.currentSeat(state))
        assertTrue(YahtzeeRules.summary(state).contains("Winner"))
    }

    @Test
    fun `two can play it through as well`() {
        var state = YahtzeeRules.initialState(config(YahtzeeOptions(playerCount = 2)))
        var guard = 400
        while (!YahtzeeRules.isFinished(state) && guard-- > 0) {
            val legal = YahtzeeRules.legalMoves(state, state.turn)
            state = YahtzeeRules.applyMove(state, state.turn, YahtzeeAi.chooseMove(state, state.turn, legal))
        }
        assertTrue(guard > 0)
        assertTrue(state.cards.all { it.isComplete })
        state.cards.indices.forEach { assertTrue(state.totalFor(it) >= 0) }
    }

    @Test
    fun `nothing is hidden from anyone`() {
        val state = YahtzeeRules.initialState(config())
        assertEquals(state, YahtzeeRules.viewFor(state, 0))
        assertEquals(state, YahtzeeRules.viewFor(state, 1))
    }

    @Test
    fun `state and moves survive a round trip`() {
        val state = YahtzeeRules.applyMove(YahtzeeRules.initialState(config()), 0, RollDice())
        assertEquals(state, YahtzeeRules.decodeState(YahtzeeRules.encodeState(state)))

        val roll: YahtzeeMove = RollDice(setOf(0, 3))
        assertEquals(roll, YahtzeeRules.decodeMove(YahtzeeRules.encodeMove(roll)))
        val write: YahtzeeMove = ScoreIn(YahtzeeCategory.LARGE_STRAIGHT)
        assertEquals(write, YahtzeeRules.decodeMove(YahtzeeRules.encodeMove(write)))
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
