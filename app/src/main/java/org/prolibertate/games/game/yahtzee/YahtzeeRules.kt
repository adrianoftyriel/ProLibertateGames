package org.prolibertate.games.game.yahtzee

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

/**
 * Yahtzee as a pure state machine. See RULES-yahtzee.md.
 *
 * The dice are thrown from the state rather than from a generator held on the
 * side: [applyMove] has to return the same thing every time it is handed the
 * same state and move, or the host and its clients would part company on the
 * first reroll. Seeding on the round, the seat, the rolls used and what was kept
 * gives a throw that is unpredictable to play against and identical to replay.
 */
object YahtzeeRules : GameRules<YahtzeeState, YahtzeeMove> {

    override val gameId: String = GameCatalog.YAHTZEE

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): YahtzeeState {
        val options = json.decodeFromString<YahtzeeOptions>(config.optionsJson)
        require(config.seats.size == options.playerCount) {
            "Seat count ${config.seats.size} does not match ${options.playerCount} players"
        }
        return YahtzeeState(
            options = options,
            seed = config.seed,
            round = 0,
            turn = 0,
            dice = emptyList(),
            rollsUsed = 0,
            cards = List(options.playerCount) { YahtzeeCard() },
            yahtzeeBonuses = List(options.playerCount) { 0 },
            log = listOf("Round 1."),
        )
    }

    override fun currentSeat(state: YahtzeeState): Int? =
        if (state.isOver) null else state.turn

    override fun legalMoves(state: YahtzeeState, seat: Int): List<YahtzeeMove> {
        if (state.isOver || state.turn != seat) return emptyList()
        val moves = mutableListOf<YahtzeeMove>()
        if (state.rollsUsed < ROLLS_PER_TURN) {
            // The first throw of a turn is all five dice: there is nothing on
            // the table yet to keep.
            moves += if (!state.hasRolled) {
                listOf(RollDice(emptyList()))
            } else {
                keepSubsets().map { RollDice(it) }
            }
        }
        if (state.hasRolled) {
            moves += YahtzeeCategory.entries
                .filterNot { state.cards[seat].isFilled(it) }
                .map { ScoreIn(it) }
        }
        return moves
    }

    /** Every set of dice positions a player might hold back — thirty-two of them. */
    private fun keepSubsets(): List<List<Int>> =
        (0 until (1 shl DICE_COUNT)).map { mask ->
            (0 until DICE_COUNT).filter { mask and (1 shl it) != 0 }
        }

    override fun applyMove(state: YahtzeeState, seat: Int, move: YahtzeeMove): YahtzeeState {
        require(!state.isOver) { "The game is over" }
        require(state.turn == seat) { "Seat $seat is not on the clock" }
        return when (move) {
            is RollDice -> applyRoll(state, move)
            is ScoreIn -> applyScore(state, seat, move)
        }
    }

    private fun applyRoll(state: YahtzeeState, move: RollDice): YahtzeeState {
        require(state.rollsUsed < ROLLS_PER_TURN) { "No rolls left this turn" }
        require(move.keep.distinct().size == move.keep.size) { "Cannot keep a die twice" }
        require(move.keep.all { it in 0 until DICE_COUNT }) { "No such die" }
        require(state.hasRolled || move.keep.isEmpty()) { "Nothing to keep before the first roll" }
        return state.copy(
            dice = throwDice(state, move.keep),
            rollsUsed = state.rollsUsed + 1,
        )
    }

    private fun throwDice(state: YahtzeeState, keep: List<Int>): List<Int> {
        val random = Random(
            state.seed +
                state.round * 10_000L +
                state.turn * 1_000L +
                state.rollsUsed * 100L +
                keep.sorted().hashCode(),
        )
        return (0 until DICE_COUNT).map { index ->
            if (keep.contains(index) && index < state.dice.size) {
                state.dice[index]
            } else {
                random.nextInt(1, 7)
            }
        }
    }

    private fun applyScore(state: YahtzeeState, seat: Int, move: ScoreIn): YahtzeeState {
        require(state.hasRolled) { "Roll before writing anything in" }
        val card = state.cards[seat]
        require(!card.isFilled(move.category)) { "${move.category.label} is already written in" }

        val written = scoreOf(move.category, state.dice)
        // A second Yahtzee pays a hundred on top, but only once the box itself
        // holds the fifty — a zero written there earlier forfeits the bonus, as
        // the printed rule has it.
        val earnsBonus = state.options.yahtzeeBonus &&
            isYahtzee(state.dice) &&
            card[YahtzeeCategory.YAHTZEE] == YAHTZEE_SCORE
        val bonuses = if (!earnsBonus) {
            state.yahtzeeBonuses
        } else {
            state.yahtzeeBonuses.toMutableList().also { it[seat] = it[seat] + YAHTZEE_BONUS }
        }

        val cards = state.cards.toMutableList().also { it[seat] = card.with(move.category, written) }
        val nextTurn = (seat + 1) % state.options.playerCount
        val wrapped = nextTurn == 0
        val round = if (wrapped) state.round + 1 else state.round
        val notes = buildList {
            add("Seat $seat wrote $written in ${move.category.label}.")
            if (earnsBonus) add("Seat $seat rolled another Yahtzee: $YAHTZEE_BONUS more.")
            if (cards.all { it.isComplete }) add("Cards full.") else if (wrapped) add("Round ${round + 1}.")
        }
        return state.copy(
            round = round,
            turn = nextTurn,
            // The dice go back in the cup for whoever is next.
            dice = emptyList(),
            rollsUsed = 0,
            cards = cards,
            yahtzeeBonuses = bonuses,
            log = state.log + notes,
        )
    }

    override fun isFinished(state: YahtzeeState): Boolean = state.isOver

    override fun summary(state: YahtzeeState): String = when {
        state.isOver -> {
            val totals = state.cards.indices.map { state.totalFor(it) }
            val best = totals.max()
            "Winner: seat ${totals.indexOf(best)} on $best"
        }

        !state.hasRolled -> "Seat ${state.turn} to throw"
        else -> "Seat ${state.turn}, ${state.rollsLeft} rolls left"
    }

    /**
     * Nothing is hidden. The dice are on the table and every card is face up —
     * Yahtzee is a game of what you do with a throw everyone watched, so there
     * is no private state to strip.
     */
    override fun viewFor(state: YahtzeeState, seat: Int): YahtzeeState = state

    override fun encodeState(state: YahtzeeState): String = json.encodeToString(state)
    override fun decodeState(json: String): YahtzeeState = this.json.decodeFromString(json)
    override fun encodeMove(move: YahtzeeMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): YahtzeeMove = this.json.decodeFromString(json)
}
