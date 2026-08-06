package org.prolibertate.games.score

import kotlinx.serialization.Serializable

/**
 * One person on the score sheet.
 *
 * Points are recorded against [id] rather than against a position, which is
 * what lets the players be dragged into a different order without their scores
 * moving with them. Ids come from [ScoreSheet.nextPlayerId] and are never
 * reused: somebody added where somebody else was removed must not inherit the
 * rounds that were scored against them.
 *
 * [name] is stored exactly as typed, blank included, so clearing the field
 * while renaming does not fight back. What to show for a blank name is a
 * display concern and belongs to [ScoreSheet.displayName].
 */
@Serializable
data class ScorePlayer(val id: Int, val name: String = "")

/**
 * What each player scored in one round, keyed by [ScorePlayer.id].
 *
 * A player absent from the map scored nothing, which is the same as zero — so
 * a round where only one person scored carries one entry rather than a column
 * of noughts.
 */
@Serializable
data class ScoreRound(val deltas: Map<Int, Int> = emptyMap()) {
    fun delta(playerId: Int): Int = deltas[playerId] ?: 0
}

/**
 * A pencil-and-paper score sheet: some players, and a row of points per round.
 *
 * Every change returns a new sheet rather than mutating this one, so the screen
 * can hand the result straight to state and persistence without worrying about
 * which of them holds the live copy. Nothing here touches Android, for the same
 * reason `game/` does not: it can then be unit-tested on the JVM.
 */
@Serializable
data class ScoreSheet(
    val players: List<ScorePlayer> = emptyList(),
    val rounds: List<ScoreRound> = emptyList(),
    /** The next id to hand out. Only ever counts up; see [ScorePlayer.id]. */
    val nextPlayerId: Int = 1,
) {
    /** A sheet with nobody on it has not been set up yet. */
    val started: Boolean get() = players.isNotEmpty()

    /** What to call a player who has not been named: their seat at the table. */
    fun displayName(player: ScorePlayer): String {
        val typed = player.name.trim()
        if (typed.isNotEmpty()) return typed
        val position = players.indexOfFirst { it.id == player.id }
        return "Player ${if (position >= 0) position + 1 else player.id}"
    }

    fun total(playerId: Int): Int = rounds.sumOf { it.delta(playerId) }

    fun totals(): Map<Int, Int> = players.associate { it.id to total(it.id) }

    /**
     * Grows or shrinks the table to [count], clamped to what a sheet can hold.
     *
     * Shrinking drops players from the end and takes their scores with them,
     * because points recorded against somebody nobody can see are points that
     * can never be corrected.
     */
    fun withPlayerCount(count: Int): ScoreSheet {
        val target = count.coerceIn(MIN_PLAYERS, MAX_PLAYERS)
        if (target == players.size) return this
        if (target < players.size) return copy(players = players.take(target)).pruned()

        var next = nextPlayerId
        val added = List(target - players.size) { ScorePlayer(id = next++) }
        return copy(players = players + added, nextPlayerId = next)
    }

    fun withPlayerAdded(): ScoreSheet = withPlayerCount(players.size + 1)

    /** Removes one player by id. The last two cannot be removed. */
    fun withPlayerRemoved(playerId: Int): ScoreSheet {
        if (players.size <= MIN_PLAYERS) return this
        if (players.none { it.id == playerId }) return this
        return copy(players = players.filterNot { it.id == playerId }).pruned()
    }

    fun renamed(playerId: Int, name: String): ScoreSheet = copy(
        players = players.map { if (it.id == playerId) it.copy(name = name) else it }
    )

    /** Moves the player at [from] to [to], which is what a drag amounts to. */
    fun moved(from: Int, to: Int): ScoreSheet {
        if (from !in players.indices) return this
        val target = to.coerceIn(0, players.size - 1)
        if (from == target) return this
        val reordered = players.toMutableList()
        reordered.add(target, reordered.removeAt(from))
        return copy(players = reordered)
    }

    fun withRound(deltas: Map<Int, Int>): ScoreSheet = copy(rounds = rounds + roundOf(deltas))

    /** Corrects a round already written down. Out-of-range indices do nothing. */
    fun withRoundAt(index: Int, deltas: Map<Int, Int>): ScoreSheet {
        if (index !in rounds.indices) return this
        return copy(rounds = rounds.toMutableList().also { it[index] = roundOf(deltas) })
    }

    fun withoutRound(index: Int): ScoreSheet {
        if (index !in rounds.indices) return this
        return copy(rounds = rounds.filterIndexed { at, _ -> at != index })
    }

    /**
     * A round holding only what is worth storing: entries for players who are
     * still at the table, and only where somebody actually scored.
     */
    private fun roundOf(deltas: Map<Int, Int>): ScoreRound {
        val ids = players.map { it.id }.toSet()
        return ScoreRound(deltas.filterKeys { it in ids }.filterValues { it != 0 })
    }

    /** Drops points recorded against anybody no longer at the table. */
    private fun pruned(): ScoreSheet {
        val ids = players.map { it.id }.toSet()
        return copy(rounds = rounds.map { round -> ScoreRound(round.deltas.filterKeys { it in ids }) })
    }

    companion object {
        const val MIN_PLAYERS = 2
        const val MAX_PLAYERS = 12
        const val DEFAULT_PLAYERS = 4

        /** A fresh sheet seated for [count] players, none of them named yet. */
        fun of(count: Int): ScoreSheet = ScoreSheet().withPlayerCount(count)
    }
}
