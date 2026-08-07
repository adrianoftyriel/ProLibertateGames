package org.prolibertate.games.game.mastermind

import kotlin.random.Random
import org.prolibertate.games.game.engine.GameAi

/**
 * A code setter and a code breaker.
 *
 * There is no tree to search here — the opponent makes no reply, and a guess
 * cannot be answered badly — so the whole game is in choosing which code to try
 * next. Three ways of choosing it, in rising order of how much it thinks:
 *
 * - **Casual** takes anything untried, ruled out or not.
 * - **Club** never guesses a code the answers have already eliminated. That one
 *   rule is most of the strength.
 * - **Strong** is Knuth's idea one ply deep: among the codes still standing, it
 *   plays the one whose *worst* possible answer leaves the smallest number of
 *   candidates behind, so it cannot be given a reply that tells it little.
 */
class MastermindAi(private val level: MastermindLevel? = null) :
    GameAi<MastermindState, MastermindMove> {

    override fun chooseMove(
        state: MastermindState,
        seat: Int,
        legal: List<MastermindMove>,
    ): MastermindMove {
        require(legal.isNotEmpty()) { "No legal guess for seat $seat" }
        val setting = level ?: state.options.level

        // Deterministic for a given position, so a host and a client watching
        // the same game agree on what the computer did.
        val random = Random(seat * 7919L + state.guesses[seat].size * 31L + state.options.hashCode())

        // Choosing its own code, which is a different job from breaking one.
        //
        // Two deliberate things here. It does not try to be clever — a code
        // picked to beat a particular solver is a code with a pattern in it,
        // and a pattern is the one thing a code should not have. And it is the
        // only choice in this file drawn from a source nobody else can see:
        // every other random here is seeded from the position so a host and a
        // client agree, but a code seeded from anything on the wire could be
        // worked out by the player who is supposed to be guessing it.
        if (state.phase == MastermindPhase.SETTING) {
            return MastermindMove(MastermindRules.randomCode(state.options, Random.Default))
        }

        if (setting == MastermindLevel.CASUAL) return legal[random.nextInt(legal.size)]

        val standing = MastermindRules.consistentCodes(state, seat)
            .filterNot { code -> state.guesses[seat].any { it.code == code } }
        // Nothing consistent left means the answers contradict each other,
        // which cannot happen in a game this code scored — but a guess still
        // has to be returned rather than an exception.
        if (standing.isEmpty()) return legal[random.nextInt(legal.size)]
        if (standing.size == 1) return MastermindMove(standing.single())

        if (setting == MastermindLevel.CLUB) {
            return MastermindMove(standing[random.nextInt(standing.size)])
        }

        return MastermindMove(minimax(standing, random))
    }

    /**
     * The candidate whose worst reply leaves the fewest codes standing.
     *
     * Both lists are sampled when they are large. The full calculation is every
     * candidate against every survivor, which on a fresh six-colour game is
     * more than a million scorings — a second or two of a phone's time for a
     * choice that a sample of them makes just as well.
     */
    private fun minimax(standing: List<List<Int>>, random: Random): List<Int> {
        val candidates = sample(standing, CANDIDATES, random)
        val against = sample(standing, OPPONENTS, random)

        var best = candidates.first()
        var bestWorst = Int.MAX_VALUE
        for (candidate in candidates) {
            val partitions = mutableMapOf<Feedback, Int>()
            for (possible in against) {
                val answer = scoreGuess(possible, candidate)
                partitions[answer] = (partitions[answer] ?: 0) + 1
            }
            val worst = partitions.values.max()
            if (worst < bestWorst) {
                bestWorst = worst
                best = candidate
            }
        }
        return best
    }

    private fun sample(codes: List<List<Int>>, limit: Int, random: Random): List<List<Int>> {
        if (codes.size <= limit) return codes
        val picked = LinkedHashSet<Int>()
        while (picked.size < limit) picked += random.nextInt(codes.size)
        return picked.map { codes[it] }
    }

    private companion object {
        const val CANDIDATES = 80
        const val OPPONENTS = 400
    }
}
