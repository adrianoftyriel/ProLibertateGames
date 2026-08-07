package org.prolibertate.games.game.mastermind

import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig

/** A guess at the opponent's code. */
@Serializable
data class MastermindMove(val code: List<Int>) {
    override fun toString(): String = code.joinToString("") { ('A' + it).toString() }
}

/**
 * Mastermind as a state machine. See RULES-mastermind.md.
 *
 * This is the one game here with anything to hide, so [viewFor] does real work:
 * the host holds both codes and sends each device only its own, which means an
 * opponent's code is never on the wire in the first place. Nothing else in this
 * file may leak it either — note that [summary] talks about counts rather than
 * colours.
 */
object MastermindRules : GameRules<MastermindState, MastermindMove> {

    override val gameId: String = GameCatalog.MASTERMIND

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): MastermindState {
        require(config.seats.size == 2) { "Mastermind is a two-handed game" }
        val options = json.decodeFromString<MastermindOptions>(config.optionsJson)
        return initialState(options, config.seed)
    }

    fun initialState(options: MastermindOptions, seed: Long = 0L): MastermindState {
        require(options.length >= 1) { "a code needs at least one peg" }
        require(options.colours >= 2) { "a code needs at least two colours" }
        require(options.allowDuplicates || options.colours >= options.length) {
            "${options.length} different colours cannot be drawn from ${options.colours}"
        }
        val random = Random(seed)
        return MastermindState(
            options = options,
            // Both codes come off the table's own seed, so a game can be
            // replayed exactly from the config it started with.
            secrets = listOf(secretFrom(options, random), secretFrom(options, random)),
            guesses = listOf(emptyList(), emptyList()),
            turn = FIRST_SEAT,
            phase = MastermindPhase.GUESSING,
            outcome = null,
        )
    }

    private fun secretFrom(options: MastermindOptions, random: Random): List<Int> {
        if (options.allowDuplicates) {
            return List(options.length) { random.nextInt(options.colours) }
        }
        val bag = (0 until options.colours).toMutableList()
        return List(options.length) { bag.removeAt(random.nextInt(bag.size)) }
    }

    override fun currentSeat(state: MastermindState): Int? =
        if (state.phase == MastermindPhase.GAME_OVER) null else state.turn

    /**
     * Every code this seat has not already tried.
     *
     * Repeating a guess is not illegal by the rules of the game, but it can
     * only ever waste a turn, and leaving it out keeps the computer from
     * wasting one.
     */
    override fun legalMoves(state: MastermindState, seat: Int): List<MastermindMove> {
        if (state.phase == MastermindPhase.GAME_OVER || state.turn != seat) return emptyList()
        val tried = state.guesses[seat].map { it.code }.toSet()
        return allCodes(state.options).filterNot { it in tried }.map { MastermindMove(it) }
    }

    override fun applyMove(
        state: MastermindState,
        seat: Int,
        move: MastermindMove,
    ): MastermindState {
        require(state.phase != MastermindPhase.GAME_OVER) { "The game is over" }
        require(state.turn == seat) { "It is not seat $seat's turn" }
        require(move.code.size == state.options.length) {
            "a guess is ${state.options.length} pegs long"
        }
        require(move.code.all { it in 0 until state.options.colours }) {
            "that colour is not in this game"
        }
        require(state.guesses[seat].none { it.code == move.code }) {
            "that guess has been made already"
        }
        val secret = state.secrets[other(seat)]
        require(secret.isNotEmpty()) {
            "this device does not hold that code — a guess is scored by the host"
        }

        val scored = Guess(code = move.code, feedback = scoreGuess(secret, move.code))
        val guesses = state.guesses.toMutableList()
        guesses[seat] = guesses[seat] + scored

        return state.copy(guesses = guesses, turn = other(seat)).withTerminalCheck()
    }

    /**
     * The game ends at the foot of a round, never in the middle of one.
     *
     * Both players have had the same number of guesses only when the turn has
     * come back round to the opener, so that is the only place it is fair to
     * compare them — otherwise opening would be worth half a guess.
     */
    private fun MastermindState.withTerminalCheck(): MastermindState {
        if (turn != FIRST_SEAT) return this

        val first = broke(FIRST_SEAT)
        val second = broke(SECOND_SEAT)
        val outcome = when {
            first && second -> MastermindOutcome.DRAW_BOTH
            first -> MastermindOutcome.FIRST_WINS
            second -> MastermindOutcome.SECOND_WINS
            guessesLeft(FIRST_SEAT) <= 0 && guessesLeft(SECOND_SEAT) <= 0 ->
                MastermindOutcome.DRAW_NEITHER

            else -> return this
        }
        return copy(phase = MastermindPhase.GAME_OVER, outcome = outcome)
    }

    override fun isFinished(state: MastermindState): Boolean =
        state.phase == MastermindPhase.GAME_OVER

    override fun summary(state: MastermindState): String = state.outcome?.label
        ?: "Guess ${state.guesses[state.turn].size + 1} of ${state.options.maxGuesses}"

    /**
     * Strips out the code this seat is not entitled to see.
     *
     * A seat keeps its own code — it is theirs to guard, and the screen shows
     * it once the game is over — and loses the one it is trying to break. The
     * feedback on every guess stays, because that is the game.
     *
     * Once the game is finished both codes are shown: there is nothing left to
     * protect, and a code breaker wants to see what they were up against.
     */
    override fun viewFor(state: MastermindState, seat: Int): MastermindState {
        if (state.phase == MastermindPhase.GAME_OVER) return state
        val secrets = state.secrets.mapIndexed { index, code ->
            if (index == seat) code else emptyList()
        }
        return state.copy(secrets = secrets)
    }

    override fun encodeState(state: MastermindState): String = json.encodeToString(state)
    override fun decodeState(json: String): MastermindState = this.json.decodeFromString(json)
    override fun encodeMove(move: MastermindMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): MastermindMove = this.json.decodeFromString(json)

    /** Codes still consistent with everything [seat] has been told so far. */
    fun consistentCodes(state: MastermindState, seat: Int): List<List<Int>> {
        val answers = state.guesses[seat]
        return allCodes(state.options).filter { candidate ->
            answers.all { scoreGuess(candidate, it.code) == it.feedback }
        }
    }
}
