package org.prolibertate.games.game.mastermind

import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig

/**
 * A row of pegs.
 *
 * The same move does both jobs the game asks for: while the codes are being
 * set it *is* your code, and after that it is a guess at theirs. Which one it
 * means is decided by the phase rather than by a flag on the move, because the
 * phase is the thing that actually knows — a flag could disagree with it, and
 * then one of the two would be wrong.
 */
@Serializable
data class MastermindMove(val code: List<Int>) {
    override fun toString(): String = code.joinToString("") { ('A' + it).toString() }
}

/**
 * Mastermind as a state machine. See RULES-mastermind.md.
 *
 * A game runs in two phases. First each player chooses the code their opponent
 * will have to break, one after the other; then both players guess, a row each
 * in turn, until somebody cracks one.
 *
 * This is the one game here with anything to hide, so [viewFor] does real work:
 * the host holds both codes and sends each device only its own, which means an
 * opponent's code is never on the wire in the first place. A code set on a
 * guest's device travels to the host as a move and stops there — moves are
 * intents sent to the host and are never relayed to the other player, so the
 * only copy that reaches an opponent's screen is the redacted one.
 *
 * Nothing else in this file may leak a code either: note that [summary] talks
 * about counts rather than colours.
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
        return initialState(options)
    }

    fun initialState(options: MastermindOptions = MastermindOptions()): MastermindState {
        require(options.length >= 1) { "a code needs at least one peg" }
        require(options.colours >= 2) { "a code needs at least two colours" }
        require(options.allowDuplicates || options.colours >= options.length) {
            "${options.length} different colours cannot be drawn from ${options.colours}"
        }
        return MastermindState(
            options = options,
            // Nobody has chosen anything yet. The codes are the players' to
            // set, which is half of what makes it a game rather than a puzzle.
            secrets = listOf(emptyList(), emptyList()),
            declared = listOf(false, false),
            guesses = listOf(emptyList(), emptyList()),
            turn = FIRST_SEAT,
            phase = MastermindPhase.SETTING,
            outcome = null,
        )
    }

    /** A code drawn at random, which is how the computer sets its own. */
    fun randomCode(options: MastermindOptions, random: Random): List<Int> {
        if (options.allowDuplicates) {
            return List(options.length) { random.nextInt(options.colours) }
        }
        val bag = (0 until options.colours).toMutableList()
        return List(options.length) { bag.removeAt(random.nextInt(bag.size)) }
    }

    /** Whether a row of pegs is a code this table allows at all. */
    fun isWellFormed(options: MastermindOptions, code: List<Int>): Boolean =
        code.size == options.length &&
            code.all { it in 0 until options.colours } &&
            (options.allowDuplicates || code.distinct().size == code.size)

    override fun currentSeat(state: MastermindState): Int? =
        if (state.phase == MastermindPhase.GAME_OVER) null else state.turn

    /**
     * While the codes are being set, every code this table allows. After that,
     * every code this seat has not already guessed.
     *
     * Repeating a guess is not illegal by the rules of the game, but it can
     * only ever waste a turn, and leaving it out keeps the computer from
     * wasting one.
     */
    override fun legalMoves(state: MastermindState, seat: Int): List<MastermindMove> {
        if (state.phase == MastermindPhase.GAME_OVER || state.turn != seat) return emptyList()
        if (state.phase == MastermindPhase.SETTING) {
            return allCodes(state.options).map { MastermindMove(it) }
        }
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
        require(isWellFormed(state.options, move.code)) {
            "a code is ${state.options.length} pegs from ${state.options.colours} colours" +
                if (state.options.allowDuplicates) "" else ", all different"
        }

        if (state.phase == MastermindPhase.SETTING) return declare(state, seat, move.code)

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
     * Writes down the code this seat will be guarding.
     *
     * The two players choose one after the other rather than at the same time,
     * because the engine only ever has one seat on the clock. It costs nothing:
     * neither can see the other's, so going second is not an advantage — and
     * once both are down, the guessing starts with the player who set first.
     */
    private fun declare(
        state: MastermindState,
        seat: Int,
        code: List<Int>,
    ): MastermindState {
        require(!state.declared[seat]) { "seat $seat has already set a code" }

        val secrets = state.secrets.toMutableList()
        secrets[seat] = code
        val declared = state.declared.toMutableList()
        declared[seat] = true

        val ready = declared.all { it }
        return state.copy(
            secrets = secrets,
            declared = declared,
            phase = if (ready) MastermindPhase.GUESSING else MastermindPhase.SETTING,
            turn = if (ready) FIRST_SEAT else other(seat),
        )
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

    override fun summary(state: MastermindState): String = state.outcome?.label ?: when {
        state.phase == MastermindPhase.SETTING ->
            "Setting the codes — ${state.declared.count { it }} of 2 chosen"

        else -> "Guess ${state.guesses[state.turn].size + 1} of ${state.options.maxGuesses}"
    }

    /**
     * Strips out the code this seat is not entitled to see.
     *
     * A seat keeps its own code — they chose it, and they are guarding it — and
     * loses the one they are trying to break. Whether the opponent has chosen
     * yet is *not* stripped: that is not a secret, and a screen has to be able
     * to say "waiting for them" rather than sitting blank.
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
