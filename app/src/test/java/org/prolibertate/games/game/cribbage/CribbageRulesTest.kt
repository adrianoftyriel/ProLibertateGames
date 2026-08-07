package org.prolibertate.games.game.cribbage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

class CribbageRulesTest {

    private val json = Json { encodeDefaults = true }

    private fun config(
        options: CribbageOptions = CribbageOptions(),
        seed: Long = 21L,
    ) = TableConfig(
        gameId = "cribbage",
        seats = (0 until options.playerCount).map {
            PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = options.teamOf(it))
        },
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    private fun c(rank: Rank, suit: Suit) = Card(rank, suit)

    /** A table part-way through the play, built rather than dealt. */
    private fun playing(
        hands: List<List<Card>>,
        options: CribbageOptions = CribbageOptions(playerCount = hands.size),
        dealer: Int = hands.size - 1,
        turn: Int = 0,
        starter: Card = c(Rank.NINE, Suit.CLUBS),
        played: List<PeggedCard> = emptyList(),
        series: List<PeggedCard> = played,
        crib: List<Card> = emptyList(),
        scores: List<Int> = List(options.teamCount) { 0 },
    ) = CribbageState(
        options = options,
        seed = 1L,
        handNumber = 0,
        dealer = dealer,
        hands = hands,
        handCounts = hands.indices.map { seat ->
            hands[seat].size - played.count { it.seat == seat }
        },
        crib = crib,
        cribCount = crib.size,
        deck = emptyList(),
        starter = starter,
        played = played,
        series = series,
        saidGo = emptySet(),
        turn = turn,
        lastToPlay = played.lastOrNull()?.seat,
        scores = scores,
        previousScores = scores,
        show = emptyList(),
        phase = CribbagePhase.PLAY,
        winner = null,
        log = emptyList(),
    )

    /** Lays away for every seat in turn, leaving the table cut and ready to play. */
    private fun dealAndLayAway(state: CribbageState): CribbageState {
        var next = state
        while (next.phase == CribbagePhase.DISCARD) {
            val seat = CribbageRules.currentSeat(next)!!
            next = CribbageRules.applyMove(next, seat, CribbageRules.legalMoves(next, seat).first())
        }
        return next
    }

    // -- The deal ------------------------------------------------------------

    @Test
    fun `two players get six cards each and three or four get five`() {
        assertEquals(6, CribbageOptions(playerCount = 2).dealSize)
        assertEquals(5, CribbageOptions(playerCount = 3).dealSize)
        assertEquals(5, CribbageOptions(playerCount = 4).dealSize)
        assertEquals(2, CribbageOptions(playerCount = 2).layAwaySize)
        assertEquals(1, CribbageOptions(playerCount = 4).layAwaySize)
    }

    @Test
    fun `the crib is four cards at every table size`() {
        listOf(2, 3, 4).forEach { players ->
            val state = dealAndLayAway(
                CribbageRules.initialState(config(CribbageOptions(playerCount = players)))
            )
            assertEquals("$players players", CRIBBAGE_HAND_SIZE, state.crib.size)
            assertTrue(
                "$players players kept the wrong number",
                state.hands.all { it.size == CRIBBAGE_HAND_SIZE },
            )
            // No card is in two places at once.
            val all = state.hands.flatten() + state.crib + state.starter!!
            assertEquals(all.size, all.distinct().size)
        }
    }

    @Test
    fun `three-handed deals the odd card straight into the crib`() {
        val state = CribbageRules.initialState(config(CribbageOptions(playerCount = 3)))
        assertEquals(1, state.crib.size)
        assertEquals(1, state.cribCount)
        assertEquals(0, CribbageRules.initialState(config(CribbageOptions(playerCount = 2))).crib.size)
    }

    @Test
    fun `the dealer's left lays away first and the dealer last`() {
        val state = CribbageRules.initialState(config(CribbageOptions(playerCount = 3)))
        assertEquals(0, state.dealer)
        assertEquals(1, CribbageRules.currentSeat(state))
        val afterOne = CribbageRules.applyMove(
            state, 1, CribbageRules.legalMoves(state, 1).first()
        )
        assertEquals(2, CribbageRules.currentSeat(afterOne))
        val afterTwo = CribbageRules.applyMove(
            afterOne, 2, CribbageRules.legalMoves(afterOne, 2).first()
        )
        assertEquals(0, CribbageRules.currentSeat(afterTwo))
    }

    @Test
    fun `a lay-away means the same cards whichever order they were picked in`() {
        val one = c(Rank.FIVE, Suit.SPADES)
        val two = c(Rank.KING, Suit.HEARTS)
        assertEquals(LayAway.of(listOf(one, two)), LayAway.of(listOf(two, one)))
    }

    @Test
    fun `heads-up offers every way of laying two away`() {
        val state = CribbageRules.initialState(config())
        // Fifteen ways of choosing two cards out of six.
        assertEquals(15, CribbageRules.legalMoves(state, 1).size)
    }

    @Test
    fun `the starter is cut once everybody has laid away`() {
        val dealt = CribbageRules.initialState(config())
        assertNull(dealt.starter)
        val ready = dealAndLayAway(dealt)
        assertNotNull(ready.starter)
        assertEquals(CribbagePhase.PLAY, ready.phase)
        // The non-dealer leads.
        assertEquals(1, ready.turn)
    }

    @Test
    fun `a jack cut is two for his heels and they are the dealer's`() {
        var found = 0
        for (seed in 0L until 200L) {
            val ready = dealAndLayAway(CribbageRules.initialState(config(seed = seed)))
            if (ready.starter?.rank != Rank.JACK) {
                assertEquals("seed $seed scored something for a plain cut", 0, ready.scores.sum())
                continue
            }
            found++
            assertEquals("seed $seed", 2, ready.scores[ready.options.teamOf(ready.dealer)])
            assertEquals(0, ready.scores[1 - ready.options.teamOf(ready.dealer)])
        }
        assertTrue("no jack was ever cut in two hundred deals", found > 0)
    }

    // -- The play ------------------------------------------------------------

    @Test
    fun `you may not take the count past thirty-one`() {
        val state = playing(
            hands = listOf(
                listOf(
                    c(Rank.KING, Suit.SPADES), c(Rank.NINE, Suit.DIAMONDS),
                    c(Rank.TWO, Suit.HEARTS), c(Rank.SEVEN, Suit.CLUBS),
                ),
                listOf(
                    c(Rank.QUEEN, Suit.CLUBS), c(Rank.JACK, Suit.SPADES),
                    c(Rank.TEN, Suit.DIAMONDS), c(Rank.THREE, Suit.HEARTS),
                ),
            ),
            played = listOf(
                PeggedCard(0, c(Rank.KING, Suit.SPADES)),
                PeggedCard(1, c(Rank.QUEEN, Suit.CLUBS)),
                PeggedCard(0, c(Rank.NINE, Suit.DIAMONDS)),
            ),
            turn = 0,
        )
        // Twenty-nine on the table: of the two cards left, only the two fits.
        assertEquals(29, state.count)
        assertEquals(
            listOf<CribbageMove>(PegCard(c(Rank.TWO, Suit.HEARTS))),
            CribbageRules.legalMoves(state, 0),
        )
    }

    @Test
    fun `the last card is worth one and the next series starts beyond it`() {
        val state = playing(
            hands = listOf(
                listOf(
                    c(Rank.KING, Suit.SPADES), c(Rank.TEN, Suit.HEARTS),
                    c(Rank.NINE, Suit.DIAMONDS), c(Rank.EIGHT, Suit.CLUBS),
                ),
                listOf(
                    c(Rank.QUEEN, Suit.CLUBS), c(Rank.JACK, Suit.SPADES),
                    c(Rank.TEN, Suit.DIAMONDS), c(Rank.NINE, Suit.HEARTS),
                ),
            ),
            played = listOf(
                PeggedCard(0, c(Rank.KING, Suit.SPADES)),
                PeggedCard(1, c(Rank.QUEEN, Suit.CLUBS)),
            ),
            turn = 0,
        )
        assertEquals(20, state.count)

        val after = CribbageRules.applyMove(state, 0, PegCard(c(Rank.TEN, Suit.HEARTS)))

        // Thirty on the table and nobody holds a card small enough, so seat 0
        // takes one for the last card and the count is cleared.
        assertEquals(1, after.scores[0])
        assertEquals(0, after.scores[1])
        assertEquals(0, after.count)
        assertTrue(after.series.isEmpty())
        assertTrue(after.saidGo.isEmpty())
        assertEquals("the seat after the last card leads", 1, after.turn)
        assertTrue(after.log.any { it.contains("says go") })
    }

    @Test
    fun `thirty-one pays two and nothing more for the go`() {
        val state = playing(
            hands = listOf(
                listOf(
                    c(Rank.FIVE, Suit.SPADES), c(Rank.TWO, Suit.HEARTS),
                    c(Rank.THREE, Suit.DIAMONDS), c(Rank.FOUR, Suit.CLUBS),
                ),
                listOf(
                    c(Rank.KING, Suit.CLUBS), c(Rank.QUEEN, Suit.SPADES),
                    c(Rank.SIX, Suit.HEARTS), c(Rank.SEVEN, Suit.DIAMONDS),
                ),
            ),
            played = listOf(
                PeggedCard(0, c(Rank.FOUR, Suit.CLUBS)),
                PeggedCard(1, c(Rank.KING, Suit.CLUBS)),
                PeggedCard(0, c(Rank.TWO, Suit.HEARTS)),
                PeggedCard(1, c(Rank.QUEEN, Suit.SPADES)),
            ),
            turn = 0,
        )
        assertEquals(26, state.count)

        val after = CribbageRules.applyMove(state, 0, PegCard(c(Rank.FIVE, Suit.SPADES)))

        assertEquals("two for thirty-one, and no point for the go on top", 2, after.scores[0])
        assertEquals(0, after.count)
        assertEquals(1, after.turn)
    }

    @Test
    fun `pegging out ends the game in the middle of the play`() {
        val state = playing(
            hands = listOf(
                listOf(
                    c(Rank.SEVEN, Suit.SPADES), c(Rank.SEVEN, Suit.HEARTS),
                    c(Rank.NINE, Suit.DIAMONDS), c(Rank.EIGHT, Suit.CLUBS),
                ),
                listOf(
                    c(Rank.SEVEN, Suit.CLUBS), c(Rank.QUEEN, Suit.SPADES),
                    c(Rank.TEN, Suit.DIAMONDS), c(Rank.NINE, Suit.HEARTS),
                ),
            ),
            played = listOf(
                PeggedCard(0, c(Rank.SEVEN, Suit.SPADES)),
                PeggedCard(1, c(Rank.SEVEN, Suit.CLUBS)),
            ),
            scores = listOf(119, 60),
            turn = 0,
        )
        val after = CribbageRules.applyMove(state, 0, PegCard(c(Rank.SEVEN, Suit.HEARTS)))

        assertEquals("pair royal", 125, after.scores[0])
        assertEquals(0, after.winner)
        assertTrue(CribbageRules.isFinished(after))
        assertNull(CribbageRules.currentSeat(after))
        assertTrue("the hands were never counted", after.show.isEmpty())
    }

    // -- The show ------------------------------------------------------------

    @Test
    fun `the hands are counted from the dealer's left with the crib last`() {
        var state = dealAndLayAway(
            CribbageRules.initialState(config(CribbageOptions(playerCount = 3), seed = 4L))
        )
        while (state.phase == CribbagePhase.PLAY) {
            val seat = CribbageRules.currentSeat(state)!!
            state = CribbageRules.applyMove(state, seat, CribbageRules.legalMoves(state, seat).first())
        }

        assertEquals(CribbagePhase.SHOW, state.phase)
        assertEquals(4, state.show.size)
        assertEquals(listOf(1, 2, 0, 0), state.show.map { it.seat })
        assertEquals(listOf(false, false, false, true), state.show.map { it.isCrib })
        assertTrue(state.show.all { it.counted })
        assertEquals(state.crib, state.show.last().cards)
    }

    @Test
    fun `a hand counted out wins before the dealer ever counts`() {
        // Seat 0 is holding the twenty-nine hand and counts first.
        val state = playing(
            hands = listOf(
                listOf(
                    c(Rank.FIVE, Suit.SPADES), c(Rank.FIVE, Suit.HEARTS),
                    c(Rank.FIVE, Suit.DIAMONDS), c(Rank.JACK, Suit.CLUBS),
                ),
                listOf(
                    c(Rank.TWO, Suit.SPADES), c(Rank.THREE, Suit.HEARTS),
                    c(Rank.FOUR, Suit.DIAMONDS), c(Rank.SIX, Suit.CLUBS),
                ),
            ),
            dealer = 1,
            starter = c(Rank.FIVE, Suit.CLUBS),
            crib = listOf(
                c(Rank.KING, Suit.SPADES), c(Rank.QUEEN, Suit.HEARTS),
                c(Rank.NINE, Suit.DIAMONDS), c(Rank.EIGHT, Suit.SPADES),
            ),
            played = listOf(
                PeggedCard(0, c(Rank.FIVE, Suit.SPADES)),
                PeggedCard(1, c(Rank.TWO, Suit.SPADES)),
                PeggedCard(0, c(Rank.FIVE, Suit.HEARTS)),
                PeggedCard(1, c(Rank.THREE, Suit.HEARTS)),
                PeggedCard(0, c(Rank.FIVE, Suit.DIAMONDS)),
                PeggedCard(1, c(Rank.FOUR, Suit.DIAMONDS)),
                PeggedCard(1, c(Rank.SIX, Suit.CLUBS)),
            ),
            scores = listOf(100, 100),
            turn = 0,
        ).let { it.copy(series = listOf(PeggedCard(1, c(Rank.SIX, Suit.CLUBS)))) }

        // Seat 0's last card ends the play and takes the table to the show.
        val after = CribbageRules.applyMove(state, 0, PegCard(c(Rank.JACK, Suit.CLUBS)))

        assertEquals(CribbagePhase.GAME_OVER, after.phase)
        assertEquals(0, after.winner)
        assertEquals(3, after.show.size)
        assertTrue("the non-dealer counted", after.show[0].counted)
        assertFalse("the dealer never got to count", after.show[1].counted)
        assertFalse("nor did the crib", after.show[2].counted)
        assertEquals(29, after.show[0].total)
    }

    // -- Whole games ---------------------------------------------------------

    @Test
    fun `random legal play always finishes, at every table size`() {
        listOf(2, 3, 4).forEach { players ->
            repeat(6) { iteration ->
                val random = Random(iteration.toLong())
                var state = CribbageRules.initialState(
                    config(
                        CribbageOptions(playerCount = players, pointsToWin = 61),
                        seed = 700L + iteration,
                    )
                )
                var guard = 0
                while (!CribbageRules.isFinished(state) && guard++ < 20000) {
                    val seat = CribbageRules.currentSeat(state)
                    if (seat == null) {
                        state = CribbageRules.nextHand(state)
                        continue
                    }
                    val legal = CribbageRules.legalMoves(state, seat)
                    assertTrue("$players players: seat $seat had no legal move", legal.isNotEmpty())
                    state = CribbageRules.applyMove(state, seat, legal[random.nextInt(legal.size)])
                }
                assertTrue("$players players, game $iteration hung", CribbageRules.isFinished(state))
                assertNotNull(state.winner)
                assertTrue(state.scores.max() >= 61)
            }
        }
    }

    @Test
    fun `nobody is ever carried past the target`() {
        // The game stops on the point that reaches it, so the winner's score is
        // the only one that may sit on or above the line.
        var state = CribbageRules.initialState(config(CribbageOptions(pointsToWin = 61), seed = 9L))
        val random = Random(9L)
        var guard = 0
        while (!CribbageRules.isFinished(state) && guard++ < 20000) {
            val seat = CribbageRules.currentSeat(state)
            if (seat == null) {
                state = CribbageRules.nextHand(state)
                continue
            }
            val legal = CribbageRules.legalMoves(state, seat)
            state = CribbageRules.applyMove(state, seat, legal[random.nextInt(legal.size)])
        }
        val winner = state.winner!!
        state.scores.forEachIndexed { team, score ->
            if (team != winner) assertTrue("team $team reached $score", score < 61)
        }
    }

    @Test
    fun `the computer beats random play head to head`() {
        val ai = CribbageAi()
        var aiWins = 0
        var randomWins = 0

        repeat(10) { iteration ->
            val random = Random(4000L + iteration)
            var state = CribbageRules.initialState(
                config(CribbageOptions(pointsToWin = 61), seed = 200L + iteration)
            )
            var guard = 0
            while (!CribbageRules.isFinished(state) && guard++ < 20000) {
                val seat = CribbageRules.currentSeat(state)
                if (seat == null) {
                    state = CribbageRules.nextHand(state)
                    continue
                }
                val legal = CribbageRules.legalMoves(state, seat)
                val move = if (seat == 0) {
                    ai.chooseMove(state, seat, legal)
                } else {
                    legal[random.nextInt(legal.size)]
                }
                assertTrue("the ai returned an illegal move $move", legal.contains(move))
                state = CribbageRules.applyMove(state, seat, move)
            }
            if (state.winner == 0) aiWins++ else randomWins++
        }

        assertTrue("ai won $aiWins of ten against random play", aiWins > randomWins)
    }

    // -- Redaction and wire format ------------------------------------------

    @Test
    fun `a seat view hides the other hands, the crib and the pack`() {
        val state = dealAndLayAway(CribbageRules.initialState(config(CribbageOptions(playerCount = 4))))
        val view = CribbageRules.viewFor(state, seat = 2)

        assertEquals(state.hands[2], view.hands[2])
        assertTrue(view.hands.filterIndexed { seat, _ -> seat != 2 }.all { it.isEmpty() })
        assertTrue("the crib is nobody's business until the show", view.crib.isEmpty())
        assertTrue("the cut must not be knowable in advance", view.deck.isEmpty())
        assertEquals(CRIBBAGE_HAND_SIZE, view.cribCount)
        assertEquals(state.handCounts, view.handCounts)
        assertEquals(state.starter, view.starter)
    }

    @Test
    fun `everything is turned face up at the show`() {
        var state = dealAndLayAway(CribbageRules.initialState(config(seed = 12L)))
        while (state.phase == CribbagePhase.PLAY) {
            val seat = CribbageRules.currentSeat(state)!!
            state = CribbageRules.applyMove(state, seat, CribbageRules.legalMoves(state, seat).first())
        }
        val view = CribbageRules.viewFor(state, seat = 0)
        assertEquals(state.hands, view.hands)
        assertEquals(state.crib, view.crib)
    }

    @Test
    fun `state and moves survive a round trip`() {
        val state = CribbageRules.initialState(config())
        assertEquals(state, CribbageRules.decodeState(CribbageRules.encodeState(state)))
        listOf(
            PegCard(c(Rank.KING, Suit.HEARTS)),
            LayAway.of(listOf(c(Rank.FIVE, Suit.SPADES), c(Rank.SIX, Suit.CLUBS))),
        ).forEach {
            assertEquals(it, CribbageRules.decodeMove(CribbageRules.encodeMove(it)))
        }
    }

    @Test
    fun `four play in partnerships and two or three play for themselves`() {
        val four = CribbageOptions(playerCount = 4)
        assertEquals(2, four.teamCount)
        assertEquals(listOf(0, 1, 0, 1), (0..3).map { four.teamOf(it) })

        val three = CribbageOptions(playerCount = 3)
        assertEquals(3, three.teamCount)
        assertEquals(listOf(0, 1, 2), (0..2).map { three.teamOf(it) })
    }
}
