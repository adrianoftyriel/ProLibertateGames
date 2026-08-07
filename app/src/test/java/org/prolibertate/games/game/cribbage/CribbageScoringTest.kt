package org.prolibertate.games.game.cribbage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Decks
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit

class CribbageScoringTest {

    private fun c(rank: Rank, suit: Suit) = Card(rank, suit)

    private fun showTotal(hand: List<Card>, starter: Card, isCrib: Boolean = false): Int =
        CribbageScoring.show(hand, starter, isCrib).sumOf { it.points }

    private fun pegTotal(vararg cards: Card): Int =
        CribbageScoring.peg(cards.toList()).sumOf { it.points }

    // -- What a card is worth ------------------------------------------------

    @Test
    fun `aces are one and every court card is ten`() {
        assertEquals(1, pipValue(c(Rank.ACE, Suit.SPADES)))
        assertEquals(10, pipValue(c(Rank.TEN, Suit.SPADES)))
        assertEquals(10, pipValue(c(Rank.JACK, Suit.SPADES)))
        assertEquals(10, pipValue(c(Rank.QUEEN, Suit.SPADES)))
        assertEquals(10, pipValue(c(Rank.KING, Suit.SPADES)))
        assertEquals(7, pipValue(c(Rank.SEVEN, Suit.SPADES)))
    }

    @Test
    fun `the ace is low in a run and does not follow the king`() {
        assertEquals(1, runOrder(c(Rank.ACE, Suit.SPADES)))
        assertEquals(13, runOrder(c(Rank.KING, Suit.SPADES)))
        // A-2-3 is a run of three.
        val low = CribbageScoring.show(
            hand = listOf(
                c(Rank.ACE, Suit.SPADES),
                c(Rank.TWO, Suit.HEARTS),
                c(Rank.THREE, Suit.DIAMONDS),
                c(Rank.NINE, Suit.CLUBS),
            ),
            starter = c(Rank.SEVEN, Suit.CLUBS),
            isCrib = false,
        )
        assertEquals(3, low.first { it.label == "Run of 3" }.points)

        // Q-K-A is not.
        val high = CribbageScoring.show(
            hand = listOf(
                c(Rank.QUEEN, Suit.SPADES),
                c(Rank.KING, Suit.HEARTS),
                c(Rank.ACE, Suit.DIAMONDS),
                c(Rank.TWO, Suit.CLUBS),
            ),
            starter = c(Rank.SEVEN, Suit.CLUBS),
            isCrib = false,
        )
        assertTrue(high.none { it.label.startsWith("Run") })
    }

    // -- The show ------------------------------------------------------------

    @Test
    fun `the best hand in cribbage is twenty-nine`() {
        // Three fives and the jack of the suit that is cut.
        val hand = listOf(
            c(Rank.FIVE, Suit.SPADES),
            c(Rank.FIVE, Suit.HEARTS),
            c(Rank.FIVE, Suit.DIAMONDS),
            c(Rank.JACK, Suit.CLUBS),
        )
        val lines = CribbageScoring.show(hand, c(Rank.FIVE, Suit.CLUBS), isCrib = false)
        assertEquals(29, lines.sumOf { it.points })
        assertEquals(16, lines.first { it.label.endsWith("fifteens") }.points)
        assertEquals(12, lines.first { it.label.startsWith("Double pair royal") }.points)
        assertEquals(1, lines.first { it.label == "His nob" }.points)
    }

    @Test
    fun `nineteen is no hand at all`() {
        val hand = listOf(
            c(Rank.KING, Suit.SPADES),
            c(Rank.QUEEN, Suit.HEARTS),
            c(Rank.EIGHT, Suit.DIAMONDS),
            c(Rank.SIX, Suit.CLUBS),
        )
        assertTrue(CribbageScoring.show(hand, c(Rank.TWO, Suit.CLUBS), false).isEmpty())
    }

    @Test
    fun `a doubled run counts twice over rather than lengthening`() {
        // 4-5-5-6 is two runs of three, not a run of four.
        val hand = listOf(
            c(Rank.FOUR, Suit.SPADES),
            c(Rank.FIVE, Suit.SPADES),
            c(Rank.FIVE, Suit.HEARTS),
            c(Rank.SIX, Suit.DIAMONDS),
        )
        val lines = CribbageScoring.show(hand, c(Rank.KING, Suit.CLUBS), isCrib = false)
        val run = lines.first { it.label.contains("runs of") }
        assertEquals("2 runs of 3", run.label)
        assertEquals(6, run.points)
        // Two 4-5-6 fifteens, two 5-king fifteens, and the pair of fives.
        assertEquals(16, lines.sumOf { it.points })
    }

    @Test
    fun `a hand takes four for its own suit and five with the starter`() {
        val flush = listOf(
            c(Rank.TWO, Suit.HEARTS),
            c(Rank.FOUR, Suit.HEARTS),
            c(Rank.NINE, Suit.HEARTS),
            c(Rank.KING, Suit.HEARTS),
        )
        assertEquals(
            ScoreLine("Flush of 4", 4),
            CribbageScoring.show(flush, c(Rank.SEVEN, Suit.SPADES), isCrib = false)
                .first { it.label.startsWith("Flush") },
        )
        assertEquals(
            ScoreLine("Flush of 5", 5),
            CribbageScoring.show(flush, c(Rank.SEVEN, Suit.HEARTS), isCrib = false)
                .first { it.label.startsWith("Flush") },
        )
    }

    @Test
    fun `a crib takes nothing for four of a suit`() {
        val flush = listOf(
            c(Rank.TWO, Suit.HEARTS),
            c(Rank.FOUR, Suit.HEARTS),
            c(Rank.NINE, Suit.HEARTS),
            c(Rank.KING, Suit.HEARTS),
        )
        assertNull(
            CribbageScoring.show(flush, c(Rank.SEVEN, Suit.SPADES), isCrib = true)
                .firstOrNull { it.label.startsWith("Flush") }
        )
        assertEquals(
            ScoreLine("Flush of 5", 5),
            CribbageScoring.show(flush, c(Rank.SEVEN, Suit.HEARTS), isCrib = true)
                .first { it.label.startsWith("Flush") },
        )
    }

    @Test
    fun `his nob wants the jack in the hand and the suit that was cut`() {
        val withJack = listOf(
            c(Rank.JACK, Suit.HEARTS),
            c(Rank.TWO, Suit.SPADES),
            c(Rank.SEVEN, Suit.CLUBS),
            c(Rank.NINE, Suit.DIAMONDS),
        )
        assertEquals(
            ScoreLine("His nob", 1),
            CribbageScoring.show(withJack, c(Rank.THREE, Suit.HEARTS), isCrib = false)
                .first { it.label == "His nob" },
        )
        assertNull(
            CribbageScoring.show(withJack, c(Rank.THREE, Suit.SPADES), isCrib = false)
                .firstOrNull { it.label == "His nob" }
        )
    }

    @Test
    fun `fifteens count every combination and not just pairs of cards`() {
        // 7-8 makes fifteen, and so does 2-3-4-6.
        val hand = listOf(
            c(Rank.SEVEN, Suit.SPADES),
            c(Rank.EIGHT, Suit.HEARTS),
            c(Rank.TWO, Suit.DIAMONDS),
            c(Rank.THREE, Suit.CLUBS),
        )
        val lines = CribbageScoring.show(hand, c(Rank.FOUR, Suit.CLUBS), isCrib = false)
        assertEquals(4, lines.first { it.label == "2 fifteens" }.points)
        assertEquals(3, lines.first { it.label == "Run of 3" }.points)
        assertEquals(7, lines.sumOf { it.points })
    }

    // -- The play ------------------------------------------------------------

    @Test
    fun `fifteen and thirty-one are worth two apiece during the play`() {
        assertEquals(2, pegTotal(c(Rank.SEVEN, Suit.SPADES), c(Rank.EIGHT, Suit.HEARTS)))
        assertEquals(
            2,
            pegTotal(
                c(Rank.KING, Suit.SPADES),
                c(Rank.QUEEN, Suit.HEARTS),
                c(Rank.SIX, Suit.DIAMONDS),
                c(Rank.FIVE, Suit.CLUBS),
            ),
        )
    }

    @Test
    fun `pairs peg off the end of the run and not through another card`() {
        assertEquals(2, pegTotal(c(Rank.SEVEN, Suit.SPADES), c(Rank.SEVEN, Suit.HEARTS)))
        assertEquals(
            6,
            pegTotal(
                c(Rank.SEVEN, Suit.SPADES),
                c(Rank.SEVEN, Suit.HEARTS),
                c(Rank.SEVEN, Suit.CLUBS),
            ),
        )
        // Broken up by a three, so the sevens are no longer a pair.
        assertEquals(
            0,
            pegTotal(
                c(Rank.SEVEN, Suit.SPADES),
                c(Rank.THREE, Suit.HEARTS),
                c(Rank.SEVEN, Suit.CLUBS),
            ),
        )
    }

    @Test
    fun `a run pegs in any order and lengthens as it is extended`() {
        assertEquals(
            3,
            pegTotal(
                c(Rank.FIVE, Suit.SPADES),
                c(Rank.THREE, Suit.HEARTS),
                c(Rank.FOUR, Suit.CLUBS),
            ),
        )
        assertEquals(
            4,
            pegTotal(
                c(Rank.FIVE, Suit.SPADES),
                c(Rank.THREE, Suit.HEARTS),
                c(Rank.FOUR, Suit.CLUBS),
                c(Rank.SIX, Suit.DIAMONDS),
            ),
        )
        // A repeated rank breaks the run: 5-3-4-4 is no run at all.
        assertEquals(
            2,
            pegTotal(
                c(Rank.FIVE, Suit.SPADES),
                c(Rank.THREE, Suit.HEARTS),
                c(Rank.FOUR, Suit.CLUBS),
                c(Rank.FOUR, Suit.DIAMONDS),
            ),
        )
    }

    @Test
    fun `twenty-nine really is the ceiling`() {
        // Exhaustive over a shortened pack — aces to fives and the jacks, which
        // is where every big hand lives. Nothing may beat 29, and 29 has to be
        // reachable, or the scoring has quietly stopped counting something.
        val pack = Decks.standard52().filter { runOrder(it) <= 5 || it.rank == Rank.JACK }
        var best = 0
        for (a in pack.indices) {
            for (b in a + 1 until pack.size) {
                for (d in b + 1 until pack.size) {
                    for (e in d + 1 until pack.size) {
                        val hand = listOf(pack[a], pack[b], pack[d], pack[e])
                        for (starter in pack) {
                            if (starter in hand) continue
                            best = maxOf(best, showTotal(hand, starter))
                        }
                    }
                }
            }
        }
        assertEquals(29, best)
    }
}
