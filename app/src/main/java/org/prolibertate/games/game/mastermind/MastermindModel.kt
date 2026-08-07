package org.prolibertate.games.game.mastermind

import kotlinx.serialization.Serializable

/**
 * Mastermind as a duel.
 *
 * The pencil-and-paper game is one person setting a code and another breaking
 * it, which makes a poor two-handed game: one player would sit and watch. Here
 * **both** players are set a code and both are breaking one, a guess each in
 * turn, and whoever cracks theirs first wins.
 *
 * That symmetry is what makes it fair to end the game at the foot of a round
 * rather than the moment somebody gets it: if the first player cracks the code
 * on their sixth guess, the second player still gets a sixth guess to equal it,
 * and equalling it is a draw.
 */
@Serializable
data class MastermindOptions(
    /** How many colours the code is drawn from. */
    val colours: Int = 6,
    /** How many pegs long the code is. */
    val length: Int = 4,
    /** Whether a colour may appear more than once in the code. */
    val allowDuplicates: Boolean = true,
    /** Guesses each. Running out with neither code broken is a draw. */
    val maxGuesses: Int = 10,
    val level: MastermindLevel = MastermindLevel.CLUB,
) {
    /** How many codes are possible, which is what a guess is chosen from. */
    fun codeSpace(): Int = if (allowDuplicates) {
        var total = 1
        repeat(length) { total *= colours }
        total
    } else {
        var total = 1
        for (step in 0 until length) total *= (colours - step).coerceAtLeast(0)
        total
    }
}

/** How hard the computer thinks about it. */
@Serializable
enum class MastermindLevel(val label: String) {
    /**
     * Guesses anything it has not tried, whether or not the answers so far rule
     * it out. Beatable by anybody who is paying attention.
     */
    CASUAL("Casual"),

    /**
     * Never guesses a code the answers have already ruled out. That alone is a
     * respectable player and cracks a four-peg, six-colour code in about five.
     */
    CLUB("Club"),

    /**
     * Consistent, and among the consistent codes it picks the one whose worst
     * answer leaves the fewest possibilities standing — a one-ply minimax over
     * the replies, which is Knuth's idea without the full lookahead.
     */
    STRONG("Strong"),
}

/**
 * The answer to a guess: how many pegs are the right colour in the right place,
 * and how many are the right colour somewhere else.
 *
 * The two counts never double-count a peg. That is the whole subtlety of
 * scoring Mastermind, and it is why [scoreGuess] counts colours rather than
 * walking pairs.
 */
@Serializable
data class Feedback(val exact: Int, val misplaced: Int) {
    override fun toString(): String = "$exact·$misplaced"
}

@Serializable
data class Guess(val code: List<Int>, val feedback: Feedback)

/**
 * Scores [guess] against [secret].
 *
 * Exact matches are counted first and taken out; what is left is compared by
 * colour count, so three reds guessed against one red can only ever be worth
 * one peg however they are arranged.
 */
fun scoreGuess(secret: List<Int>, guess: List<Int>): Feedback {
    require(secret.size == guess.size) { "a guess must be as long as the code" }
    var exact = 0
    val secretLeft = mutableListOf<Int>()
    val guessLeft = mutableListOf<Int>()
    for (index in secret.indices) {
        if (secret[index] == guess[index]) {
            exact++
        } else {
            secretLeft += secret[index]
            guessLeft += guess[index]
        }
    }
    val counts = secretLeft.groupingBy { it }.eachCount().toMutableMap()
    var misplaced = 0
    for (colour in guessLeft) {
        val left = counts[colour] ?: 0
        if (left > 0) {
            misplaced++
            counts[colour] = left - 1
        }
    }
    return Feedback(exact = exact, misplaced = misplaced)
}

/** Every code the options allow, in a fixed order. */
fun allCodes(options: MastermindOptions): List<List<Int>> {
    var codes = listOf(emptyList<Int>())
    repeat(options.length) {
        codes = codes.flatMap { prefix ->
            (0 until options.colours)
                .filter { options.allowDuplicates || it !in prefix }
                .map { prefix + it }
        }
    }
    return codes
}

@Serializable
enum class MastermindPhase { GUESSING, GAME_OVER }

@Serializable
enum class MastermindOutcome(val label: String) {
    FIRST_WINS("Broken first by the player who opened"),
    SECOND_WINS("Broken first by the player who replied"),
    DRAW_BOTH("A draw — both codes broken in the same number of guesses"),
    DRAW_NEITHER("A draw — neither code was broken in time"),
}

@Serializable
data class MastermindState(
    val options: MastermindOptions,
    /**
     * The code each seat is guarding, which is the one their opponent is trying
     * to break. Redacted before this ever leaves the host: see
     * [MastermindRules.viewFor].
     */
    val secrets: List<List<Int>>,
    /** What each seat has guessed at their opponent's code, oldest first. */
    val guesses: List<List<Guess>>,
    val turn: Int,
    val phase: MastermindPhase,
    val outcome: MastermindOutcome?,
) {
    fun broke(seat: Int): Boolean =
        guesses[seat].any { it.feedback.exact == options.length }

    /** How many guesses it took, or null if this seat has not cracked it. */
    fun brokeIn(seat: Int): Int? = guesses[seat]
        .indexOfFirst { it.feedback.exact == options.length }
        .takeIf { it >= 0 }
        ?.plus(1)

    fun guessesLeft(seat: Int): Int = options.maxGuesses - guesses[seat].size

    /** True where the secret has been stripped out for a client's view. */
    fun isHidden(seat: Int): Boolean = secrets[seat].isEmpty()
}

const val FIRST_SEAT = 0
const val SECOND_SEAT = 1

fun other(seat: Int): Int = 1 - seat
