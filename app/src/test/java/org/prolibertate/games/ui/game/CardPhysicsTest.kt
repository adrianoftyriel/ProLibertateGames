package org.prolibertate.games.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Decks
import org.prolibertate.games.game.cards.Rank
import org.prolibertate.games.game.cards.Suit

/**
 * The scatter under the card animations.
 *
 * Two properties matter and neither is visible in a screenshot. It has to be
 * *bounded*, because the whole difference between cards on a table and cards
 * thrown down a flight of stairs is the size of the numbers; and it has to be
 * *stable*, because a card that picks a new angle on every recomposition
 * twitches, and a card whose angle depends on where it sits in a list jumps
 * whenever a card is played next to it.
 */
class CardPhysicsTest {

    private val deck = Decks.standard52()

    @Test
    fun `noise stays inside minus one to one`() {
        for (seed in -500..500) {
            for (salt in 0..8) {
                val n = unitNoise(seed, salt)
                assertTrue("$seed/$salt gave $n", n >= -1f && n < 1f)
            }
        }
    }

    @Test
    fun `noise is the same every time it is asked`() {
        assertEquals(unitNoise(12345, 3), unitNoise(12345, 3), 0f)
        assertEquals(unitNoise(-7, 1), unitNoise(-7, 1), 0f)
    }

    /**
     * The salt is what lets one seed produce a tilt, two drifts, a spin and a
     * queue position that are not all the same number. If it stopped separating
     * them, every card would drift exactly as far as it tilted.
     */
    @Test
    fun `salts do not agree with each other`() {
        val fromOneSeed = (0..8).map { unitNoise(4242, it) }
        assertEquals(fromOneSeed.size, fromOneSeed.toSet().size)
    }

    /** Sequential seeds are the normal case: adjacent cards in a hand. */
    @Test
    fun `neighbouring seeds do not give neighbouring values`() {
        val runs = (0 until 200).count { abs(unitNoise(it, 1) - unitNoise(it + 1, 1)) < 0.05f }
        assertTrue("$runs of 200 neighbouring seeds landed together", runs < 30)
    }

    @Test
    fun `every card in a deck rests within the stated bounds`() {
        deck.forEach { card ->
            val rest = cardRest(cardSeed(card))
            assertTrue(abs(rest.tiltDegrees) <= REST_TILT_DEGREES)
            assertTrue(abs(rest.driftX) <= REST_DRIFT_FRACTION)
            assertTrue(abs(rest.driftY) <= REST_DRIFT_FRACTION)
            assertTrue(abs(rest.spinDegrees) <= THROW_SPIN_DEGREES)
            assertTrue(rest.lateness in 0f..1f)
            assertTrue(abs(handTilt(cardSeed(card))) <= HAND_TILT_DEGREES)
        }
    }

    @Test
    fun `a card lies the same way however often it is asked`() {
        val card = Card(Rank.QUEEN, Suit.SPADES)
        assertEquals(cardRest(cardSeed(card)), cardRest(cardSeed(card)))
        assertEquals(cardRest(cardSeed(card, 3)), cardRest(cardSeed(card, 3)))
    }

    /**
     * Two cards lying at the same angle in the same place is the one thing that
     * makes a scattered pile look printed rather than dealt.
     */
    @Test
    fun `no two cards in a deck share a seed`() {
        val seeds = deck.map { cardSeed(it) }
        assertEquals(seeds.size, seeds.toSet().size)
    }

    /**
     * A pile can hold the same card twice — Golf and Spider deal more than one
     * deck — so where a card is lying has to be part of its seed.
     */
    @Test
    fun `the same card in two places lies two ways`() {
        val card = Card(Rank.SEVEN, Suit.HEARTS)
        assertNotEquals(cardRest(cardSeed(card, 0)), cardRest(cardSeed(card, 1)))
    }

    /** Every seat's card arrives from that seat, and from a card-width or two out. */
    @Test
    fun `a card comes in from its own player's side`() {
        assertEquals(0f to THROW_DISTANCE, seatOrigin(0))
        assertEquals(-THROW_DISTANCE to 0f, seatOrigin(1))
        assertEquals(0f to -THROW_DISTANCE, seatOrigin(2))
        assertEquals(THROW_DISTANCE to 0f, seatOrigin(3))
    }

    @Test
    fun `a pile card comes in from somewhere off the pile`() {
        deck.forEach { card ->
            val (x, y) = pileOrigin(cardSeed(card))
            val distance = kotlin.math.hypot(x, y)
            assertEquals(THROW_DISTANCE, distance, 0.001f)
        }
    }

    @Test
    fun `nothing has left the table before the sweep starts`() {
        deck.take(8).forEach { card ->
            assertEquals(0f, sweepProgress(0f, cardRest(cardSeed(card))), 0f)
        }
    }

    @Test
    fun `everything has left the table by the end of the sweep`() {
        deck.take(8).forEach { card ->
            assertEquals(1f, sweepProgress(1f, cardRest(cardSeed(card))), 0.0001f)
        }
    }

    @Test
    fun `a card only ever gets further from the table`() {
        val rest = cardRest(cardSeed(Card(Rank.ACE, Suit.CLUBS)))
        var previous = 0f
        for (step in 0..100) {
            val now = sweepProgress(step / 100f, rest)
            assertTrue("went backwards at $step", now >= previous - 0.0001f)
            assertTrue(now in 0f..1f)
            previous = now
        }
    }

    /**
     * The stagger: a trick gathers up as one motion, so the cards must not all
     * be at the same point in it, and none of them may still be sitting there
     * when the sweep is nearly over.
     */
    @Test
    fun `cards leave at different moments but none is left behind`() {
        val hand = deck.take(4).map { cardRest(cardSeed(it)) }
        val halfway = hand.map { sweepProgress(0.5f, it) }
        assertEquals(halfway.size, halfway.toSet().size)
        hand.forEach { assertTrue(sweepProgress(0.95f, it) > 0.5f) }
    }

    /** A trick that never completes must not leave a card part way off the table. */
    @Test
    fun `a sweep that never starts moves nothing`() {
        deck.take(4).forEach { card ->
            assertEquals(0f, sweepProgress(0f, cardRest(cardSeed(card)), spread = 0f), 0f)
        }
    }
}
