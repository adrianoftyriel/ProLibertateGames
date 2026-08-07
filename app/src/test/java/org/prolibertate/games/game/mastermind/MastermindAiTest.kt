package org.prolibertate.games.game.mastermind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MastermindAiTest {

    private fun choose(
        state: MastermindState,
        seat: Int = state.turn,
        level: MastermindLevel = MastermindLevel.CLUB,
    ): MastermindMove =
        MastermindAi(level).chooseMove(state, seat, MastermindRules.legalMoves(state, seat))

    @Test
    fun `it sets a code the table allows`() {
        listOf(
            MastermindOptions(),
            MastermindOptions(colours = 8, length = 5),
            MastermindOptions(colours = 6, length = 4, allowDuplicates = false),
        ).forEach { options ->
            val state = MastermindRules.initialState(options)
            repeat(10) {
                val move = choose(state, FIRST_SEAT)
                assertTrue(
                    "the computer set ${move.code} on $options",
                    MastermindRules.isWellFormed(options, move.code),
                )
            }
        }
    }

    @Test
    fun `the code it sets is not the same every game`() {
        // It is the one choice in the game drawn from a source the opponent
        // cannot reconstruct — a code that came off the table's own seed could
        // be worked out by the player who is meant to be guessing it.
        val options = MastermindOptions()
        val seen = (1..40).map {
            choose(MastermindRules.initialState(options), FIRST_SEAT).code
        }.toSet()
        assertTrue("it set the same code 40 times running", seen.size > 1)
    }

    @Test
    fun `a consistent solver breaks a code well inside the limit`() {
        // Both codes set by hand, then the computer plays both seats. A player
        // that never guesses something the answers have ruled out should crack
        // a four-peg, six-colour code in about five.
        var state = MastermindRules.initialState(MastermindOptions(maxGuesses = 10))
        state = MastermindRules.applyMove(state, FIRST_SEAT, MastermindMove(listOf(3, 1, 4, 1)))
        state = MastermindRules.applyMove(state, SECOND_SEAT, MastermindMove(listOf(5, 0, 2, 5)))

        var plies = 0
        while (!MastermindRules.isFinished(state) && plies < 40) {
            state = MastermindRules.applyMove(state, state.turn, choose(state))
            plies++
        }

        assertTrue("the game never ended", MastermindRules.isFinished(state))
        assertNotNull(state.outcome)
        val broke = state.brokeIn(FIRST_SEAT) ?: state.brokeIn(SECOND_SEAT)
        assertNotNull("neither side broke a code in ten guesses", broke)
        assertTrue("it took $broke guesses", broke!! <= 8)
    }

    @Test
    fun `it never guesses something the answers have already ruled out`() {
        var state = MastermindRules.initialState(MastermindOptions())
        state = MastermindRules.applyMove(state, FIRST_SEAT, MastermindMove(listOf(0, 1, 2, 3)))
        state = MastermindRules.applyMove(state, SECOND_SEAT, MastermindMove(listOf(4, 5, 4, 5)))

        repeat(6) {
            if (MastermindRules.isFinished(state)) return
            val seat = state.turn
            val move = choose(state, level = MastermindLevel.STRONG)
            // Everything already answered must still hold for this guess.
            state.guesses[seat].forEach { answered ->
                assertEquals(
                    "guess ${move.code} contradicts ${answered.code}",
                    answered.feedback,
                    scoreGuess(move.code, answered.code),
                )
            }
            state = MastermindRules.applyMove(state, seat, move)
        }
    }
}
