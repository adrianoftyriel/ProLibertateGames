package org.prolibertate.games.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.engine.GameAi
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig

/**
 * The pause between hands, on a table with nobody but the local player at it.
 *
 * Cribbage's show, Golf's card and Wizard's bids-against-tricks all stop the
 * table on a scoreboard and wait to be released. The release has to be spent:
 * the state does not change while it is being read, so a controller that only
 * re-asks `awaitsConfirmation` about it stops on the same scoreboard again the
 * moment it is restarted, and the next hand is never dealt.
 */
class MatchControllerScoreboardTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    /** A hand is one move long, and every hand ends on a scoreboard. */
    private data class Hands(val handNumber: Int, val scoring: Boolean)

    private object Rules : GameRules<Hands, String> {
        override val gameId: String = "scoreboard"
        override fun initialState(config: TableConfig) = Hands(0, scoring = false)
        override fun currentSeat(state: Hands): Int? = if (state.scoring) null else 0
        override fun legalMoves(state: Hands, seat: Int): List<String> =
            if (state.scoring) emptyList() else listOf("play")

        override fun applyMove(state: Hands, seat: Int, move: String) =
            state.copy(scoring = true)

        override fun isFinished(state: Hands) = state.handNumber >= HANDS
        override fun summary(state: Hands) = state.toString()
        override fun viewFor(state: Hands, seat: Int) = state
        override fun encodeState(state: Hands) = "${state.handNumber}:${state.scoring}"
        override fun decodeState(json: String) = Hands(
            handNumber = json.substringBefore(':').toInt(),
            scoring = json.substringAfter(':').toBooleanStrict(),
        )

        override fun encodeMove(move: String) = move
        override fun decodeMove(json: String) = json
    }

    private fun controller(): MatchController<Hands, String> {
        val scope = CoroutineScope(Job() + Dispatchers.Default).also { scopes += it }
        return MatchController(
            rules = Rules,
            ai = object : GameAi<Hands, String> {
                override fun chooseMove(state: Hands, seat: Int, legal: List<String>) = legal.first()
            },
            config = TableConfig(
                gameId = "scoreboard",
                seats = listOf(PlayerSlot(0, "Me", PlayerKind.HUMAN_LOCAL, team = 0)),
                optionsJson = "{}",
                seed = 1L,
            ),
            scope = scope,
            role = MatchController.Role.HOST,
            localSeats = setOf(0),
            primarySeat = 0,
            advanceIdle = { state ->
                if (state.scoring) {
                    Hands(state.handNumber + 1, scoring = false)
                } else {
                    null
                }
            },
            aiThinkingMillis = { 0L },
            awaitsConfirmation = { state -> state.scoring },
        )
    }

    @Test
    fun `the table stops on a scoreboard`() = runBlocking {
        val table = controller()
        table.startAsHost(emptyList())
        settle()

        assertFalse("nothing to read yet", table.awaitingConfirmation.value)
        table.submit("play")
        settle()

        assertTrue("the scoreboard was skipped past", table.awaitingConfirmation.value)
        assertEquals(Hands(0, scoring = true), table.state.value)
    }

    @Test
    fun `confirming it deals the next hand`() = runBlocking {
        val table = controller()
        table.startAsHost(emptyList())
        settle()
        table.submit("play")
        settle()

        table.confirmAdvance()
        settle()

        assertEquals(
            "the table is still sitting on the scoreboard it was told to leave",
            Hands(1, scoring = false),
            table.state.value,
        )
        assertFalse(table.awaitingConfirmation.value)
        assertTrue("and the next hand is playable", table.legalMoves.value.isNotEmpty())
    }

    @Test
    fun `every hand in the game stops and is released in turn`() = runBlocking {
        val table = controller()
        table.startAsHost(emptyList())
        settle()

        repeat(HANDS) { hand ->
            table.submit("play")
            settle()
            assertTrue("hand $hand did not stop to be read", table.awaitingConfirmation.value)
            table.confirmAdvance()
            settle()
        }

        assertTrue(table.finished.value)
        assertEquals(HANDS, table.state.value?.handNumber)
    }

    private suspend fun settle() = delay(SETTLE_MILLIS)

    private companion object {
        const val HANDS = 3
        const val SETTLE_MILLIS = 250L
    }
}
