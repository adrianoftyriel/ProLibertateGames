package org.prolibertate.games.game.mastermind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MastermindRulesTest {

    private fun start(
        options: MastermindOptions = MastermindOptions(),
        seed: Long = 42L,
    ) = MastermindRules.initialState(options, seed)

    /** Sets both codes by hand, which is the only way to write a test about them. */
    private fun withSecrets(
        first: List<Int>,
        second: List<Int>,
        options: MastermindOptions = MastermindOptions(),
    ) = start(options).copy(secrets = listOf(first, second))

    private fun guess(state: MastermindState, code: List<Int>) =
        MastermindRules.applyMove(state, state.turn, MastermindMove(code))

    // -----------------------------------------------------------------------
    // Scoring
    // -----------------------------------------------------------------------

    @Test
    fun `a peg in the right place is exact and one in the wrong place is not`() {
        assertEquals(Feedback(4, 0), scoreGuess(listOf(0, 1, 2, 3), listOf(0, 1, 2, 3)))
        assertEquals(Feedback(0, 4), scoreGuess(listOf(0, 1, 2, 3), listOf(3, 2, 1, 0)))
        assertEquals(Feedback(2, 2), scoreGuess(listOf(0, 1, 2, 3), listOf(0, 2, 1, 3)))
        assertEquals(Feedback(0, 0), scoreGuess(listOf(0, 0, 0, 0), listOf(1, 1, 1, 1)))
    }

    @Test
    fun `a colour is never counted twice`() {
        // Three reds guessed against one red is worth one peg, however they sit.
        assertEquals(Feedback(1, 0), scoreGuess(listOf(0, 1, 2, 3), listOf(0, 0, 0, 0)))
        assertEquals(Feedback(0, 1), scoreGuess(listOf(0, 1, 2, 3), listOf(4, 4, 4, 0)))
        // And one red guessed against three is worth one as well.
        assertEquals(Feedback(1, 0), scoreGuess(listOf(0, 0, 0, 1), listOf(0, 2, 3, 4)))
        assertEquals(Feedback(2, 2), scoreGuess(listOf(0, 0, 1, 1), listOf(0, 1, 0, 1)))
    }

    @Test
    fun `scoring reads the same either way round`() {
        // Which is what lets the solver test a candidate against a past guess
        // rather than having to hold on to the code it was scored against.
        val codes = allCodes(MastermindOptions(colours = 4, length = 3))
        codes.take(30).forEach { a ->
            codes.take(30).forEach { b ->
                assertEquals(scoreGuess(a, b), scoreGuess(b, a))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Setting up
    // -----------------------------------------------------------------------

    @Test
    fun `both players are set a code of the right shape`() {
        val state = start()
        assertEquals(2, state.secrets.size)
        state.secrets.forEach { secret ->
            assertEquals(4, secret.size)
            assertTrue(secret.all { it in 0 until 6 })
        }
        assertEquals(FIRST_SEAT, state.turn)
        assertEquals(MastermindPhase.GUESSING, state.phase)
    }

    @Test
    fun `the same seed sets the same codes, and a different one does not`() {
        assertEquals(start(seed = 7L).secrets, start(seed = 7L).secrets)
        assertNotEquals(start(seed = 7L).secrets, start(seed = 8L).secrets)
    }

    @Test
    fun `a game without duplicates never repeats a colour`() {
        val options = MastermindOptions(colours = 6, length = 4, allowDuplicates = false)
        (0L until 20L).forEach { seed ->
            MastermindRules.initialState(options, seed).secrets.forEach { secret ->
                assertEquals(secret.size, secret.distinct().size)
            }
        }
        // And the code space is the falling factorial rather than a power.
        assertEquals(6 * 5 * 4 * 3, options.codeSpace())
        assertEquals(6 * 5 * 4 * 3, allCodes(options).size)
    }

    @Test
    fun `every code is offered once and only once`() {
        val options = MastermindOptions(colours = 4, length = 3)
        val codes = allCodes(options)
        assertEquals(4 * 4 * 4, codes.size)
        assertEquals(codes.size, codes.distinct().size)
        assertEquals(options.codeSpace(), codes.size)
    }

    // -----------------------------------------------------------------------
    // Keeping the codes secret
    // -----------------------------------------------------------------------

    @Test
    fun `a player is shown their own code and not the one they are breaking`() {
        val state = withSecrets(listOf(0, 1, 2, 3), listOf(4, 5, 0, 1))
        val asFirst = MastermindRules.viewFor(state, FIRST_SEAT)
        assertEquals(listOf(0, 1, 2, 3), asFirst.secrets[FIRST_SEAT])
        assertTrue("the code being broken must not cross the wire", asFirst.isHidden(SECOND_SEAT))

        val asSecond = MastermindRules.viewFor(state, SECOND_SEAT)
        assertEquals(listOf(4, 5, 0, 1), asSecond.secrets[SECOND_SEAT])
        assertTrue(asSecond.isHidden(FIRST_SEAT))
    }

    @Test
    fun `both codes are shown once the game is over`() {
        val state = withSecrets(listOf(0, 1, 2, 3), listOf(4, 5, 0, 1))
            .copy(phase = MastermindPhase.GAME_OVER, outcome = MastermindOutcome.DRAW_NEITHER)
        val seen = MastermindRules.viewFor(state, FIRST_SEAT)
        assertFalse(seen.isHidden(SECOND_SEAT))
        assertEquals(state, seen)
    }

    @Test
    fun `a device that was never told the code cannot score a guess against it`() {
        // Which is what stops a client trying to run the host's job locally and
        // silently scoring every guess as nothing.
        val redacted = MastermindRules.viewFor(
            withSecrets(listOf(0, 1, 2, 3), listOf(4, 5, 0, 1)),
            FIRST_SEAT,
        )
        val attempt = runCatching {
            MastermindRules.applyMove(redacted, FIRST_SEAT, MastermindMove(listOf(0, 0, 0, 0)))
        }
        assertTrue(attempt.exceptionOrNull() is IllegalArgumentException)
    }

    // -----------------------------------------------------------------------
    // Playing
    // -----------------------------------------------------------------------

    @Test
    fun `a guess is answered and the turn passes`() {
        var state = withSecrets(listOf(0, 1, 2, 3), listOf(5, 5, 5, 5))
        state = guess(state, listOf(0, 1, 3, 2))
        assertEquals(SECOND_SEAT, state.turn)
        assertEquals(1, state.guesses[FIRST_SEAT].size)
        // The first player is guessing at the second player's code.
        assertEquals(Feedback(0, 0), state.guesses[FIRST_SEAT].single().feedback)

        state = guess(state, listOf(0, 1, 2, 3))
        assertEquals(Feedback(4, 0), state.guesses[SECOND_SEAT].single().feedback)
    }

    @Test
    fun `the same guess is not offered twice`() {
        var state = withSecrets(listOf(0, 1, 2, 3), listOf(5, 5, 5, 5))
        val opening = listOf(0, 0, 1, 1)
        val before = MastermindRules.legalMoves(state, FIRST_SEAT).size
        state = guess(state, opening)
        state = guess(state, listOf(3, 3, 3, 3))
        val after = MastermindRules.legalMoves(state, FIRST_SEAT)
        assertEquals(before - 1, after.size)
        assertTrue(after.none { it.code == opening })

        val repeated = runCatching {
            MastermindRules.applyMove(state, FIRST_SEAT, MastermindMove(opening))
        }
        assertTrue(repeated.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a guess of the wrong shape is refused`() {
        val state = withSecrets(listOf(0, 1, 2, 3), listOf(5, 5, 5, 5))
        assertTrue(
            runCatching {
                MastermindRules.applyMove(state, FIRST_SEAT, MastermindMove(listOf(0, 1, 2)))
            }.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            "a colour outside the game is not a guess",
            runCatching {
                MastermindRules.applyMove(state, FIRST_SEAT, MastermindMove(listOf(0, 1, 2, 9)))
            }.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            runCatching {
                MastermindRules.applyMove(state, SECOND_SEAT, MastermindMove(listOf(0, 1, 2, 3)))
            }.exceptionOrNull() is IllegalArgumentException
        )
    }

    // -----------------------------------------------------------------------
    // Ending
    // -----------------------------------------------------------------------

    @Test
    fun `the player who replies always gets an equal number of guesses`() {
        var state = withSecrets(listOf(0, 1, 2, 3), listOf(4, 5, 4, 5))
        // The first player breaks it immediately. The game does not stop
        // there — the second player is owed a guess of their own.
        state = guess(state, listOf(4, 5, 4, 5))
        assertEquals(MastermindPhase.GUESSING, state.phase)
        assertEquals(SECOND_SEAT, state.turn)

        state = guess(state, listOf(0, 0, 0, 0))
        assertEquals(MastermindPhase.GAME_OVER, state.phase)
        assertEquals(MastermindOutcome.FIRST_WINS, state.outcome)
        assertNull(MastermindRules.currentSeat(state))
    }

    @Test
    fun `breaking it in the same round is a draw`() {
        var state = withSecrets(listOf(0, 1, 2, 3), listOf(4, 5, 4, 5))
        state = guess(state, listOf(4, 5, 4, 5))
        state = guess(state, listOf(0, 1, 2, 3))
        assertEquals(MastermindOutcome.DRAW_BOTH, state.outcome)
        assertEquals(1, state.brokeIn(FIRST_SEAT))
        assertEquals(1, state.brokeIn(SECOND_SEAT))
    }

    @Test
    fun `the player who replies can win it outright`() {
        var state = withSecrets(listOf(0, 1, 2, 3), listOf(4, 5, 4, 5))
        state = guess(state, listOf(0, 0, 0, 0))
        state = guess(state, listOf(0, 1, 2, 3))
        assertEquals(MastermindOutcome.SECOND_WINS, state.outcome)
        assertNull(state.brokeIn(FIRST_SEAT))
    }

    @Test
    fun `running out of guesses with neither code broken is a draw`() {
        val options = MastermindOptions(colours = 6, length = 4, maxGuesses = 2)
        var state = withSecrets(listOf(0, 0, 0, 0), listOf(1, 1, 1, 1), options)
        repeat(2) {
            state = guess(state, listOf(2, 2, 2, it))
            state = guess(state, listOf(3, 3, 3, it))
        }
        assertEquals(MastermindOutcome.DRAW_NEITHER, state.outcome)
        assertEquals(0, state.guessesLeft(FIRST_SEAT))
        assertTrue(MastermindRules.legalMoves(state, FIRST_SEAT).isEmpty())
    }

    @Test
    fun `a state survives the round trip over the wire`() {
        var state = withSecrets(listOf(0, 1, 2, 3), listOf(4, 5, 4, 5))
        state = guess(state, listOf(0, 1, 2, 3))
        assertEquals(state, MastermindRules.decodeState(MastermindRules.encodeState(state)))
        val move = MastermindMove(listOf(1, 2, 3, 4))
        assertEquals(move, MastermindRules.decodeMove(MastermindRules.encodeMove(move)))
    }

    @Test
    fun `the summary counts guesses without ever naming a colour`() {
        val state = withSecrets(listOf(0, 1, 2, 3), listOf(4, 5, 4, 5))
        assertEquals("Guess 1 of 10", MastermindRules.summary(state))
        val over = state.copy(
            phase = MastermindPhase.GAME_OVER,
            outcome = MastermindOutcome.FIRST_WINS,
        )
        assertEquals(MastermindOutcome.FIRST_WINS.label, MastermindRules.summary(over))
    }

    @Test
    fun `the codes still standing are exactly those that fit every answer`() {
        var state = withSecrets(listOf(0, 1, 2, 3), listOf(5, 4, 3, 2))
        state = guess(state, listOf(0, 0, 1, 1))
        state = guess(state, listOf(0, 0, 0, 0))
        state = guess(state, listOf(2, 3, 4, 5))

        val standing = MastermindRules.consistentCodes(state, FIRST_SEAT)
        assertTrue("the real code must survive", listOf(5, 4, 3, 2) in standing)
        standing.forEach { candidate ->
            state.guesses[FIRST_SEAT].forEach { answered ->
                assertEquals(answered.feedback, scoreGuess(candidate, answered.code))
            }
        }
    }
}
