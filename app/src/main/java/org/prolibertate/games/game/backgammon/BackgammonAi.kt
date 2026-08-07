package org.prolibertate.games.game.backgammon

import kotlin.random.Random
import org.prolibertate.games.game.engine.GameAi

/**
 * A backgammon player.
 *
 * This is not a search in the sense the chess and checkers players are. There
 * is no tree to walk: the opponent's reply depends on dice nobody has thrown
 * yet, so looking further ahead means averaging over twenty-one rolls rather
 * than choosing the best one. What it does instead is weigh **whole turns** —
 * every distinct way the roll can be played — and score the board each leaves
 * behind.
 *
 * Weighing turns rather than checkers is the whole point. A backgammon move is
 * only good in company: splitting the back checkers is right or wrong depending
 * on what the other die did, and a player that chose one checker at a time
 * would never see it.
 *
 * The interface hands back one checker at a time, so the best turn is worked
 * out and its first step returned; the rest follows on the moves after, because
 * the same calculation reaches the same turn.
 */
class BackgammonAi(private val level: BackgammonLevel? = null) :
    GameAi<BackgammonState, BackgammonMove> {

    override fun chooseMove(
        state: BackgammonState,
        seat: Int,
        legal: List<BackgammonMove>,
    ): BackgammonMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        val setting = level ?: state.options.level
        if (legal.size == 1) return legal.first()

        val random = Random(state.rollsMade * 31L + state.moveLog.size)

        if (setting == BackgammonLevel.CASUAL) {
            // Not thoughtless: it still prefers to hit, to come in off the bar
            // and to bear off, which is most of what a beginner knows.
            val scored = legal.map { move -> move to rough(state, seat, move) }
            val best = scored.maxOf { it.second }
            val equal = scored.filter { it.second == best }
            return equal[random.nextInt(equal.size)].first
        }

        val sequences = BackgammonRules.deepestSequences(state, seat)
        if (sequences.isEmpty()) return legal.first()

        var best = sequences.first()
        var bestScore = -Double.MAX_VALUE
        for (sequence in sequences) {
            val after = BackgammonRules.playSequence(state, seat, sequence)
            val score = if (setting == BackgammonLevel.STRONG) {
                // What the position is worth once the dice have had their say.
                evaluate(after, seat) - averageReply(after, other(seat))
            } else {
                evaluate(after, seat)
            }
            if (score > bestScore) {
                bestScore = score
                best = sequence
            }
        }
        return best.first()
    }

    /** A quick opinion of one checker, for the level that does not think. */
    private fun rough(state: BackgammonState, seat: Int, move: BackgammonMove): Int {
        val landing = BackgammonRules.destinationOf(seat, move.from, move.die)
        var score = 0
        if (move.from == BAR) score += 50
        if (landing == null) score += 40
        if (landing != null && state.countOn(landing, other(seat)) == 1) score += 60
        if (landing != null && state.countOn(landing, seat) >= 1) score += 15
        return score
    }

    /**
     * What the opponent is likely to make of this position, averaged over the
     * twenty-one distinct rolls of two dice.
     *
     * Doubles come up half as often as a mixed pair — 6-5 can be thrown two
     * ways and 5-5 only one — so each roll is weighted rather than counted.
     * Only the opponent's best answer to each roll is taken: they will not
     * play a bad one on purpose.
     */
    private fun averageReply(state: BackgammonState, opponent: Int): Double {
        if (state.phase == BackgammonPhase.GAME_OVER) return 0.0

        var total = 0.0
        var weight = 0.0
        for (high in 1..6) {
            for (low in 1..high) {
                val chance = if (high == low) 1.0 else 2.0
                val dice = if (high == low) List(4) { high } else listOf(high, low)
                val rolled = state.copy(turn = opponent, dice = dice, roll = listOf(high, low))
                val replies = BackgammonRules.deepestSequences(rolled, opponent)
                val bestReply = replies.maxOfOrNull { sequence ->
                    evaluate(BackgammonRules.playSequence(rolled, opponent, sequence), opponent)
                } ?: evaluate(rolled, opponent)
                total += chance * bestReply
                weight += chance
            }
        }
        return total / weight
    }

    /**
     * The position from [seat]'s point of view.
     *
     * The pip count is the backbone of it — backgammon is a race, and the
     * player who is ahead in the race wants to keep it simple. Everything else
     * is about what happens when it is not simple: **a blot is only a liability
     * if it can be hit**, so exposure is weighted by how far away the enemy
     * checkers are, and a made point in your own home board is worth having
     * because it is one more number your opponent cannot come in on.
     */
    private fun evaluate(state: BackgammonState, seat: Int): Double {
        val enemy = other(seat)

        if (state.phase == BackgammonPhase.GAME_OVER) {
            val won = state.off[seat] >= CHECKERS
            return if (won) WIN else -WIN
        }

        var score = 0.0

        // The race. Being ahead in pips is the plainest good thing there is.
        score += (state.pipCount(enemy) - state.pipCount(seat)) * PIP

        // Checkers home and dry, and checkers sent back to the beginning.
        score += state.off[seat] * BORNE_OFF
        score -= state.off[enemy] * BORNE_OFF
        score -= state.bar[seat] * ON_BAR
        score += state.bar[enemy] * ON_BAR

        for (point in 0 until POINTS) {
            val mine = state.countOn(point, seat)
            if (mine == 0) continue

            if (mine >= 2) {
                score += POINT_MADE
                // A point in your own home board is a door shut in the face of
                // anyone you send to the bar.
                if (point in homeOf(seat)) score += HOME_POINT
                // And an anchor deep in theirs is somewhere safe to come back to.
                if (point in homeOf(enemy)) score += ANCHOR
            } else {
                // A blot, weighted by how easily it can be reached: something
                // six away is in real danger, something twenty away is not.
                val distance = shortestEnemyDistance(state, seat, point)
                score -= when {
                    distance <= 0 -> 0.0
                    distance <= 6 -> BLOT_NEAR
                    distance <= 12 -> BLOT_FAR
                    else -> BLOT_DISTANT
                }
            }
        }

        score += primeLength(state, seat) * PRIME
        return score
    }

    /** How far the nearest enemy checker is from reaching [point], in pips. */
    private fun shortestEnemyDistance(state: BackgammonState, seat: Int, point: Int): Int {
        val enemy = other(seat)
        // Anything on the bar comes in at the far end and is a threat to the
        // whole board on its way round.
        if (state.bar[enemy] > 0) {
            val fromBar = if (enemy == WHITE_SEAT) 24 - point else point + 1
            if (fromBar in 1..6) return fromBar
        }
        var best = Int.MAX_VALUE
        for (from in 0 until POINTS) {
            if (state.countOn(from, enemy) == 0) continue
            val gap = (point - from) * directionOf(enemy)
            if (gap in 1..24 && gap < best) best = gap
        }
        return if (best == Int.MAX_VALUE) 0 else best
    }

    /** The longest run of consecutive points held, which is what traps a checker. */
    private fun primeLength(state: BackgammonState, seat: Int): Int {
        var best = 0
        var run = 0
        for (point in 0 until POINTS) {
            if (state.countOn(point, seat) >= 2) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }

    private companion object {
        const val WIN = 10_000.0

        const val PIP = 1.0
        const val BORNE_OFF = 12.0
        const val ON_BAR = 25.0
        const val POINT_MADE = 4.0
        const val HOME_POINT = 8.0
        const val ANCHOR = 6.0
        const val PRIME = 5.0
        const val BLOT_NEAR = 14.0
        const val BLOT_FAR = 6.0
        const val BLOT_DISTANT = 2.0
    }
}
