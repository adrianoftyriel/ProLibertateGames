package org.prolibertate.games.game.tayu

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

/**
 * Whether the AI is actually playing Ta Yü, and whether the two settings differ.
 *
 * It needs saying because this game hides a weak opponent well. Scores sit at
 * zero for most of a game — a product needs *both* your edges — so an opponent
 * placing tiles at random looks exactly like one that is thinking, right up
 * until the board fills and the scores come out lopsided. Playing the settings
 * off against random placement is the only way to tell them apart.
 *
 * The margins here are wide on purpose. The numbers observed while tuning were
 * 20/20 for full strength against random and 16/20 against the gentle setting,
 * from either side of the board; the assertions sit well below that so a change
 * of weights has to be a real regression to trip them.
 */
class TayuAiTest {

    private val json = Json { encodeDefaults = true }

    private fun config(seed: Long, options: TayuOptions = TayuOptions()) = TableConfig(
        gameId = "tayu",
        seats = (0 until options.playerCount).map {
            PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it % 2)
        },
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    /**
     * Plays one game out and returns the two scores. A null brain places at
     * random from the legal moves, which is the yardstick the AI has to beat.
     */
    private fun duel(seed: Long, north: TayuAi?, east: TayuAi?): Pair<Int, Int> {
        var state = TayuRules.initialState(config(seed))
        val random = Random(seed * 31 + 7)
        var guard = 0
        while (!TayuRules.isFinished(state) && guard++ < 400) {
            val seat = TayuRules.currentSeat(state) ?: break
            val legal = TayuRules.legalMoves(state, seat)
            if (legal.isEmpty()) break
            val brain = if (seat == 0) north else east
            val move = brain?.chooseMove(state, seat, legal) ?: legal[random.nextInt(legal.size)]
            state = TayuRules.applyMove(state, seat, move)
        }
        return TayuRules.scoreOf(state, 0) to TayuRules.scoreOf(state, 1)
    }

    /** Games out of [games] the north–south side won. */
    private fun northWins(games: Int, north: TayuAi?, east: TayuAi?): Int =
        (1..games).count { seed ->
            val (northScore, eastScore) = duel(seed.toLong(), north, east)
            northScore > eastScore
        }

    private val full = TayuAi(TayuLevel.FULL)
    private val gentle = TayuAi(TayuLevel.GENTLE)

    @Test
    fun `full strength beats random placement from either side of the board`() {
        val games = 20
        val asNorthSouth = northWins(games, north = full, east = null)
        val asEastWest = games - northWins(games, north = null, east = full)
        println("full strength won $asNorthSouth/$games as north–south, $asEastWest/$games as east–west")

        // Ta Yü is a game of position rather than luck of the draw, so an
        // opponent that understands it should win nearly every game against
        // random placement — from either axis.
        assertTrue("only $asNorthSouth/$games as north–south", asNorthSouth >= games - 1)
        assertTrue("only $asEastWest/$games as east–west", asEastWest >= games - 1)
    }

    @Test
    fun `the gentle setting is weaker without being random`() {
        val games = 20
        val fullOverGentle = northWins(games, north = full, east = gentle)
        val fullOverGentleReversed = games - northWins(games, north = gentle, east = full)
        val gentleOverRandom = northWins(games, north = gentle, east = null)
        println(
            "full over gentle $fullOverGentle/$games and $fullOverGentleReversed/$games; " +
                "gentle over random $gentleOverRandom/$games"
        )

        assertTrue("gentle is not weaker: $fullOverGentle/$games", fullOverGentle >= 13)
        assertTrue(
            "gentle is not weaker from the other side: $fullOverGentleReversed/$games",
            fullOverGentleReversed >= 13,
        )
        // But it must still be playing the game, not flailing — otherwise it is
        // not a gentler opponent, just a broken one.
        assertTrue("gentle is no better than random: $gentleOverRandom/$games", gentleOverRandom >= 12)
    }

    @Test
    fun `neither axis is favoured when both sides play the same way`() {
        // If random against random came out lopsided, the board or the scoring
        // would favour one axis and every result above would mean nothing.
        val games = 20
        val wins = northWins(games, north = null, east = null)
        println("random against random: north–south won $wins/$games")
        assertTrue("north–south wins $wins/$games at random", wins in 5..15)
    }

    @Test
    fun `the ai reaches both of its own edges nearly every game`() {
        // The point of the multiplicative scoring: an opponent that only ever
        // broke through on one side would score nothing however much of the
        // board it covered.
        val games = 10
        val scored = (1..games).count { seed ->
            duel(seed.toLong(), north = full, east = null).first > 0
        }
        println("full strength scored in $scored/$games games")
        assertTrue("scored in only $scored/$games games", scored >= 8)
    }

    @Test
    fun `a table plays at full strength unless it is turned down`() {
        assertEquals(TayuLevel.FULL, TayuOptions().level)
    }
}
