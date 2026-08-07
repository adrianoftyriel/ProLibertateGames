package org.prolibertate.games.game.backgammon

import kotlin.random.Random
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig

/**
 * Backgammon as a pure state machine. See RULES-backgammon.md.
 *
 * The hard rule in backgammon is not how a checker moves, it is that **a player
 * must use as much of the roll as they can**. It cannot be checked one move at
 * a time, because whether a move is legal depends on what is left afterwards: a
 * move that looks fine can be illegal precisely because playing it strands the
 * other die.
 *
 * So the generator works backwards from whole turns. It searches out every way
 * the roll could be played, keeps only the longest, and offers their first
 * steps. Recomputing that after each checker means the rule is enforced by
 * construction — a player physically cannot reach a position where the rest of
 * their roll is unplayable when it need not have been.
 *
 * The dice are rolled from the table's own seed and a count of the rolls made,
 * so a game is reproducible from the config it started with and a host and a
 * client cannot disagree about what was thrown.
 */
object BackgammonRules : GameRules<BackgammonState, BackgammonMove> {

    override val gameId: String = GameCatalog.BACKGAMMON

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun initialState(config: TableConfig): BackgammonState {
        require(config.seats.size == 2) { "Backgammon is a two-handed game" }
        val options = json.decodeFromString<BackgammonOptions>(config.optionsJson)
        return initialState(options, config.seed)
    }

    fun initialState(
        options: BackgammonOptions = BackgammonOptions(),
        seed: Long = 0L,
    ): BackgammonState {
        // The opening roll is one die each and the higher starts, playing both
        // — so a double is thrown again rather than played.
        var rolls = 0
        var white: Int
        var black: Int
        do {
            white = die(seed, rolls++)
            black = die(seed, rolls++)
        } while (white == black)

        val opener = if (white > black) WHITE_SEAT else BLACK_SEAT
        val roll = listOf(white, black).sortedDescending()

        return BackgammonState(
            options = options,
            seed = seed,
            points = startingPoints(),
            bar = listOf(0, 0),
            off = listOf(0, 0),
            turn = opener,
            dice = roll,
            roll = roll,
            rollsMade = rolls,
            phase = BackgammonPhase.PLAYING,
            outcome = null,
            lastMove = null,
            moveLog = emptyList(),
        ).skipIfStuck()
    }

    private fun die(seed: Long, index: Int): Int = Random(seed * 1_000_003L + index).nextInt(6) + 1

    override fun currentSeat(state: BackgammonState): Int? =
        if (state.phase == BackgammonPhase.GAME_OVER) null else state.turn

    // -----------------------------------------------------------------------
    // Moving one checker
    // -----------------------------------------------------------------------

    /** Where a checker from [from] lands with [die], or null if it runs off the board. */
    fun destinationOf(seat: Int, from: Int, die: Int): Int? {
        if (from == BAR) return entryPoint(seat, die)
        val landing = from + directionOf(seat) * die
        return landing.takeIf { it in 0 until POINTS }
    }

    /**
     * Whether one checker may be played, ignoring what it leaves behind. The
     * "use as much of the roll as you can" rule is applied by [legalMoves] on
     * top of this.
     */
    private fun canPlay(state: BackgammonState, seat: Int, move: BackgammonMove): Boolean {
        if (move.die !in state.dice) return false

        // Anything on the bar comes back on first; nothing else may move.
        if (state.bar[seat] > 0 && move.from != BAR) return false
        if (move.from == BAR) {
            if (state.bar[seat] <= 0) return false
            val entry = entryPoint(seat, move.die)
            return !state.isBlockedFor(entry, seat)
        }

        if (state.countOn(move.from, seat) <= 0) return false

        val landing = destinationOf(seat, move.from, move.die)
        if (landing != null) return !state.isBlockedFor(landing, seat)

        // Off the end of the board is bearing off, which has rules of its own.
        return canBearOffFrom(state, seat, move.from, move.die)
    }

    /**
     * Bearing off: the exact die takes a checker from the point that needs it,
     * and a bigger die may take one from the highest occupied point when
     * nothing sits further back. An overshooting die may never take a checker
     * that a smaller one could have reached.
     */
    private fun canBearOffFrom(
        state: BackgammonState,
        seat: Int,
        from: Int,
        die: Int,
    ): Boolean {
        if (!state.canBearOff(seat)) return false
        val needed = pipsFrom(seat, from)
        if (die == needed) return true
        if (die < needed) return false
        // The die overshoots, so it is only allowed if this is the furthest
        // checker from home.
        return (0 until POINTS).none { point ->
            state.countOn(point, seat) > 0 && pipsFrom(seat, point) > needed
        }
    }

    /** Applies one checker's move to the board, hitting a blot if there is one. */
    private fun place(state: BackgammonState, seat: Int, move: BackgammonMove): BackgammonState {
        val points = state.points.toMutableList()
        val bar = state.bar.toMutableList()
        val off = state.off.toMutableList()
        val sign = if (seat == WHITE_SEAT) 1 else -1

        if (move.from == BAR) {
            bar[seat] = bar[seat] - 1
        } else {
            points[move.from] = points[move.from] - sign
        }

        val landing = destinationOf(seat, move.from, move.die)
        if (landing == null) {
            off[seat] = off[seat] + 1
        } else {
            // A lone enemy checker on the landing point is hit and starts again.
            if (state.countOn(landing, other(seat)) == 1) {
                points[landing] = 0
                bar[other(seat)] = bar[other(seat)] + 1
            }
            points[landing] = points[landing] + sign
        }

        val dice = state.dice.toMutableList()
        dice.remove(move.die)

        return state.copy(points = points, bar = bar, off = off, dice = dice, lastMove = move)
    }

    // -----------------------------------------------------------------------
    // Moving a whole turn
    // -----------------------------------------------------------------------

    fun legalMoves(state: BackgammonState): List<BackgammonMove> = legalMoves(state, state.turn)

    /**
     * The first steps of every way of playing as much of the roll as possible.
     *
     * Two ways of playing that both use every die are equally legal however
     * they differ; one that uses a single die when both could have been used is
     * not a legal turn at all. And when only one die can be played, it must be
     * the higher — which is the one part of the rule that does not fall out of
     * counting alone, so it is applied by hand at the end.
     */
    override fun legalMoves(state: BackgammonState, seat: Int): List<BackgammonMove> {
        if (state.phase == BackgammonPhase.GAME_OVER || state.turn != seat) return emptyList()
        if (state.dice.isEmpty()) return emptyList()

        val memo = mutableMapOf<String, Int>()
        val target = depthOf(state, seat, memo)
        if (target == 0) return emptyList()

        val moves = candidates(state, seat).filter { move ->
            1 + depthOf(place(state, seat, move), seat, memo) == target
        }.distinct()

        if (target == 1) {
            val highest = moves.maxOf { it.die }
            return moves.filter { it.die == highest }
        }
        return moves
    }

    /**
     * How many of the remaining dice can still be played, at best.
     *
     * Memoised on the position rather than on the path to it: the four orders a
     * double can be played in mostly reach the same boards, and without the
     * memo the search would walk every one of them separately. The cut-off when
     * the best possible depth is reached matters just as much — most turns find
     * a full-length play immediately and never look at the rest.
     */
    private fun depthOf(
        state: BackgammonState,
        seat: Int,
        memo: MutableMap<String, Int>,
    ): Int {
        if (state.dice.isEmpty()) return 0
        val key = turnKey(state)
        memo[key]?.let { return it }

        var best = 0
        for (move in candidates(state, seat)) {
            val depth = 1 + depthOf(place(state, seat, move), seat, memo)
            if (depth > best) best = depth
            if (best == state.dice.size) break
        }
        memo[key] = best
        return best
    }

    /** What a half-played turn amounts to: the board, the bar, and the dice left. */
    private fun turnKey(state: BackgammonState): String =
        state.points.joinToString(",") + "|" + state.bar.joinToString(",") +
            "|" + state.off.joinToString(",") + "|" + state.dice.sorted().joinToString(",")

    /**
     * Every longest way of playing what is left of the roll.
     *
     * Only for the computer, which wants to weigh whole turns rather than
     * single checkers. Branches that cannot reach full length are cut before
     * they are walked, and the list is capped: a double with checkers spread
     * across the board has more orderings than are worth looking at, and they
     * are mostly the same play in a different order anyway.
     */
    fun deepestSequences(state: BackgammonState, seat: Int): List<List<BackgammonMove>> {
        val memo = mutableMapOf<String, Int>()
        val target = depthOf(state, seat, memo)
        if (target == 0) return emptyList()

        val found = mutableListOf<List<BackgammonMove>>()
        val seen = mutableSetOf<String>()

        fun walk(current: BackgammonState, played: List<BackgammonMove>) {
            if (found.size >= SEQUENCE_CAP) return
            if (played.size == target) {
                // Two orderings that end on the same board are one play.
                if (seen.add(turnKey(current))) found += played
                return
            }
            for (move in candidates(current, seat)) {
                val next = place(current, seat, move)
                if (played.size + 1 + depthOf(next, seat, memo) == target) {
                    walk(next, played + move)
                }
            }
        }

        walk(state, emptyList())

        if (target == 1 && found.isNotEmpty()) {
            val highest = found.maxOf { it.single().die }
            return found.filter { it.single().die == highest }
        }
        return found
    }

    /** The checkers that could move with one of the dice still in hand. */
    private fun candidates(state: BackgammonState, seat: Int): List<BackgammonMove> {
        if (state.dice.isEmpty()) return emptyList()
        val dice = state.dice.distinct()
        val origins = if (state.bar[seat] > 0) {
            listOf(BAR)
        } else {
            (0 until POINTS).filter { state.countOn(it, seat) > 0 }
        }
        return origins.flatMap { from ->
            dice.mapNotNull { die ->
                BackgammonMove(from, die).takeIf { canPlay(state, seat, it) }
            }
        }
    }

    override fun applyMove(
        state: BackgammonState,
        seat: Int,
        move: BackgammonMove,
    ): BackgammonState {
        require(state.phase != BackgammonPhase.GAME_OVER) { "The game is over" }
        require(state.turn == seat) { "It is not seat $seat's turn" }
        require(move in legalMoves(state, seat)) { "Illegal move $move for seat $seat" }

        val played = place(state, seat, move)
            .copy(moveLog = state.moveLog + "${seatName(seat)} $move")

        val finished = played.withTerminalCheck()
        if (finished.phase == BackgammonPhase.GAME_OVER) return finished

        // The turn ends when the dice run out or nothing more can be played.
        return if (played.dice.isEmpty() || legalMoves(played, seat).isEmpty()) {
            played.passTurn()
        } else {
            played
        }
    }

    /** Hands the dice to the other player and throws them. */
    private fun BackgammonState.passTurn(): BackgammonState {
        val next = other(turn)
        val first = die(seed, rollsMade)
        val second = die(seed, rollsMade + 1)
        // A double is four moves of the same number, which is the one piece of
        // luck in backgammon big enough to decide a game on its own.
        val thrown = if (first == second) List(4) { first } else listOf(first, second)
        return copy(
            turn = next,
            dice = thrown,
            roll = listOf(first, second),
            rollsMade = rollsMade + 2,
        ).skipIfStuck()
    }

    /**
     * A player with nothing they can play loses the turn.
     *
     * Usually this is somebody stuck on the bar against a closed board. It can
     * happen several times over, so it keeps rolling until somebody can move —
     * with a cap, because a board that closed against a player who has nothing
     * else on it would otherwise roll for ever.
     */
    private fun BackgammonState.skipIfStuck(): BackgammonState {
        var state = this
        var skipped = 0
        while (state.phase != BackgammonPhase.GAME_OVER &&
            legalMoves(state, state.turn).isEmpty() &&
            skipped < MAX_SKIPS
        ) {
            val next = other(state.turn)
            val first = die(state.seed, state.rollsMade)
            val second = die(state.seed, state.rollsMade + 1)
            val thrown = if (first == second) List(4) { first } else listOf(first, second)
            state = state.copy(
                turn = next,
                dice = thrown,
                roll = listOf(first, second),
                rollsMade = state.rollsMade + 2,
                moveLog = state.moveLog + "${seatName(state.turn)} cannot move",
            )
            skipped++
        }
        return state
    }

    /**
     * Fifteen checkers off is the game. How much it is worth depends on how the
     * loser was left: nothing borne off is a gammon, and still being on the bar
     * or in the winner's home on top of that is a backgammon.
     */
    private fun BackgammonState.withTerminalCheck(): BackgammonState {
        val winner = (0..1).firstOrNull { off[it] >= CHECKERS } ?: return this
        val loser = other(winner)

        val stranded = bar[loser] > 0 || homeOf(winner).any { countOn(it, loser) > 0 }
        val severity = when {
            !options.countGammons || off[loser] > 0 -> 0
            stranded -> 2
            else -> 1
        }

        val outcome = if (winner == WHITE_SEAT) {
            when (severity) {
                2 -> BackgammonOutcome.WHITE_BACKGAMMON
                1 -> BackgammonOutcome.WHITE_GAMMON
                else -> BackgammonOutcome.WHITE_SINGLE
            }
        } else {
            when (severity) {
                2 -> BackgammonOutcome.BLACK_BACKGAMMON
                1 -> BackgammonOutcome.BLACK_GAMMON
                else -> BackgammonOutcome.BLACK_SINGLE
            }
        }
        return copy(phase = BackgammonPhase.GAME_OVER, outcome = outcome, dice = emptyList())
    }

    // -----------------------------------------------------------------------
    // Engine contract
    // -----------------------------------------------------------------------

    override fun isFinished(state: BackgammonState): Boolean =
        state.phase == BackgammonPhase.GAME_OVER

    override fun summary(state: BackgammonState): String = state.outcome?.label ?: buildString {
        append(seatName(state.turn))
        append(" to play ")
        append(state.roll.joinToString("-"))
        if (state.dice.size < state.roll.size) append(" (${state.dice.size} left)")
    }

    /** Backgammon hides nothing: the board and the dice are on the table. */
    override fun viewFor(state: BackgammonState, seat: Int): BackgammonState = state

    override fun encodeState(state: BackgammonState): String = json.encodeToString(state)
    override fun decodeState(json: String): BackgammonState = this.json.decodeFromString(json)
    override fun encodeMove(move: BackgammonMove): String = json.encodeToString(move)
    override fun decodeMove(json: String): BackgammonMove = this.json.decodeFromString(json)

    /**
     * Plays a whole turn's worth of moves in one go, for the search and the
     * tests. Anything that ends the turn stops it early.
     */
    fun playSequence(
        state: BackgammonState,
        seat: Int,
        sequence: List<BackgammonMove>,
    ): BackgammonState {
        var current = state
        for (move in sequence) {
            if (current.phase == BackgammonPhase.GAME_OVER || current.turn != seat) break
            current = applyMove(current, seat, move)
        }
        return current
    }

    private const val MAX_SKIPS = 8

    /** How many distinct ways of playing one roll the computer will weigh. */
    private const val SEQUENCE_CAP = 300
}
