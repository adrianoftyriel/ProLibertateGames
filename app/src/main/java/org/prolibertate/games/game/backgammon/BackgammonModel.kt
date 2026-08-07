package org.prolibertate.games.game.backgammon

import kotlinx.serialization.Serializable

/**
 * The board is twenty-four points, 0 to 23.
 *
 * **White runs down the numbers and bears off past 0; Black runs up them and
 * bears off past 23.** White's home board is points 0–5, Black's is 18–23, and
 * each enters from the bar into the *other* player's home: White at 24 minus
 * the die, Black at the die minus one.
 *
 * A point holds a positive number of White checkers or a negative number of
 * Black ones, never both — which is the rule that a point belongs to whoever
 * has two or more on it, expressed as arithmetic rather than as a check.
 */
const val POINTS = 24

const val CHECKERS = 15

const val WHITE_SEAT = 0
const val BLACK_SEAT = 1

/** Where a checker sits when it has been hit, as a [BackgammonMove.from]. */
const val BAR = 24

fun other(seat: Int): Int = 1 - seat

fun seatName(seat: Int): String = if (seat == WHITE_SEAT) "White" else "Black"

/** Which way this seat's checkers travel along the numbering. */
fun directionOf(seat: Int): Int = if (seat == WHITE_SEAT) -1 else 1

/** The six points this seat bears off from. */
fun homeOf(seat: Int): IntRange = if (seat == WHITE_SEAT) 0..5 else 18..23

/** Where a checker coming off the bar lands for a given die. */
fun entryPoint(seat: Int, die: Int): Int = if (seat == WHITE_SEAT) 24 - die else die - 1

/**
 * How far a point is from being borne off, counted in pips: 1 for the last
 * point before home, 24 for the far corner. The pip count is the sum of these
 * over a player's checkers and is the single most useful number in the game.
 */
fun pipsFrom(seat: Int, point: Int): Int = if (seat == WHITE_SEAT) point + 1 else 24 - point

@Serializable
enum class BackgammonPhase { PLAYING, GAME_OVER }

/**
 * How a game ended. Backgammon scores by how badly the loser lost: a plain win
 * is worth one point, a gammon two — the loser bore nothing off — and a
 * backgammon three, where the loser still had a checker on the bar or in the
 * winner's home when it finished.
 */
@Serializable
enum class BackgammonOutcome(val label: String, val points: Int) {
    WHITE_SINGLE("White wins", 1),
    WHITE_GAMMON("White wins a gammon — Black bore nothing off", 2),
    WHITE_BACKGAMMON("White wins a backgammon — Black was still stranded", 3),
    BLACK_SINGLE("Black wins", 1),
    BLACK_GAMMON("Black wins a gammon — White bore nothing off", 2),
    BLACK_BACKGAMMON("Black wins a backgammon — White was still stranded", 3),
}

/** How hard the computer thinks about it. */
@Serializable
enum class BackgammonLevel(val label: String) {
    /** Takes the first playable thing it finds a reason for. */
    CASUAL("Casual"),

    /** Weighs every way the turn could be played and takes the best. */
    CLUB("Club"),

    /**
     * The same, and then weighs what the dice might do to it next — every one
     * of the twenty-one distinct rolls, and the best reply to each.
     */
    STRONG("Strong"),
}

@Serializable
data class BackgammonOptions(
    val level: BackgammonLevel = BackgammonLevel.CLUB,
    /**
     * Score a gammon as two and a backgammon as three, rather than calling
     * every win a win. It changes nothing about the moves and everything about
     * whether a hopeless game is worth playing out.
     */
    val countGammons: Boolean = true,
)

/**
 * One checker moving one die's worth.
 *
 * A turn is two moves, or four on a double, and each is submitted on its own —
 * which is how a person plays it, one checker at a time. What keeps that honest
 * is that the legal moves are recomputed after each one: a move that would
 * strand the rest of the turn is never offered, because a move is only legal if
 * it starts a way of playing the whole roll.
 */
@Serializable
data class BackgammonMove(val from: Int, val die: Int) {
    override fun toString(): String {
        val origin = if (from == BAR) "bar" else "${from + 1}"
        return "$origin/$die"
    }
}

@Serializable
data class BackgammonState(
    val options: BackgammonOptions,
    /**
     * The table's own seed, which every die of the game is thrown from
     * together with [rollsMade]. It is part of the state rather than kept
     * beside it so that a game is reproducible from what crosses the wire, and
     * so two games running at once cannot get hold of each other's dice.
     *
     * Nothing is given away by sending it: backgammon has nothing hidden, and
     * the host is authoritative about every roll in any case.
     */
    val seed: Long,
    /** Positive is that many White checkers, negative that many Black ones. */
    val points: List<Int>,
    /** Checkers waiting to come back on, by seat. */
    val bar: List<Int>,
    /** Checkers safely borne off, by seat. */
    val off: List<Int>,
    val turn: Int,
    /** The dice still to be played this turn. A double is four of them. */
    val dice: List<Int>,
    /** The pair as it was rolled, kept so the screen can show it whole. */
    val roll: List<Int>,
    /** How many rolls have been made, which is what makes the next one. */
    val rollsMade: Int,
    val phase: BackgammonPhase,
    val outcome: BackgammonOutcome?,
    val lastMove: BackgammonMove?,
    val moveLog: List<String>,
) {
    fun countOn(point: Int, seat: Int): Int {
        val value = points[point]
        return if (seat == WHITE_SEAT) maxOf(value, 0) else maxOf(-value, 0)
    }

    fun ownerOf(point: Int): Int? = when {
        points[point] > 0 -> WHITE_SEAT
        points[point] < 0 -> BLACK_SEAT
        else -> null
    }

    /** A single checker, which can be hit and sent back to the bar. */
    fun isBlot(point: Int): Boolean = points[point] == 1 || points[point] == -1

    /** Two or more of the enemy's: nowhere to land. */
    fun isBlockedFor(point: Int, seat: Int): Boolean = countOn(point, other(seat)) >= 2

    /** Everything home or borne off, which is what bearing off waits for. */
    fun canBearOff(seat: Int): Boolean {
        if (bar[seat] > 0) return false
        val home = homeOf(seat)
        return (0 until POINTS).none { it !in home && countOn(it, seat) > 0 }
    }

    /**
     * The pip count: how many pips this seat still has to travel. A checker on
     * the bar has the whole board to cross, which is why it costs 25.
     */
    fun pipCount(seat: Int): Int {
        var pips = bar[seat] * 25
        for (point in 0 until POINTS) pips += countOn(point, seat) * pipsFrom(seat, point)
        return pips
    }

    fun checkersLeft(seat: Int): Int = CHECKERS - off[seat]
}

/**
 * The standard opening array, from White's point of view: two on the 24, five
 * on the 13, three on the 8 and five on the 6 — mirrored for Black.
 */
fun startingPoints(): List<Int> {
    val points = IntArray(POINTS)
    points[23] = 2
    points[12] = 5
    points[7] = 3
    points[5] = 5
    points[0] = -2
    points[11] = -5
    points[16] = -3
    points[18] = -5
    return points.toList()
}
