package org.prolibertate.games.game.yahtzee

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Five dice, thirteen boxes, and thirteen turns to fill them. */
const val DICE_COUNT: Int = 5
const val ROLLS_PER_TURN: Int = 3

@Serializable
enum class YahtzeeSection { UPPER, LOWER }

/**
 * The thirteen boxes on the card, in the order they are printed.
 *
 * The upper six score only the die they name, which is what the bonus is for:
 * three of each is exactly sixty-three, so the bonus rewards a card that did not
 * give up on any of them.
 */
@Serializable
enum class YahtzeeCategory(val label: String, val section: YahtzeeSection) {
    ONES("Ones", YahtzeeSection.UPPER),
    TWOS("Twos", YahtzeeSection.UPPER),
    THREES("Threes", YahtzeeSection.UPPER),
    FOURS("Fours", YahtzeeSection.UPPER),
    FIVES("Fives", YahtzeeSection.UPPER),
    SIXES("Sixes", YahtzeeSection.UPPER),
    THREE_OF_A_KIND("Three of a kind", YahtzeeSection.LOWER),
    FOUR_OF_A_KIND("Four of a kind", YahtzeeSection.LOWER),
    FULL_HOUSE("Full house", YahtzeeSection.LOWER),
    SMALL_STRAIGHT("Small straight", YahtzeeSection.LOWER),
    LARGE_STRAIGHT("Large straight", YahtzeeSection.LOWER),
    YAHTZEE("Yahtzee", YahtzeeSection.LOWER),
    CHANCE("Chance", YahtzeeSection.LOWER),
    ;

    /** The face this box counts, for the upper six. */
    val face: Int? get() = if (section == YahtzeeSection.UPPER) ordinal + 1 else null
}

/** Three of each of the upper six, which is what the bonus asks for. */
const val UPPER_BONUS_AT: Int = 63
const val UPPER_BONUS: Int = 35
const val YAHTZEE_SCORE: Int = 50
const val YAHTZEE_BONUS: Int = 100

@Serializable
data class YahtzeeOptions(
    /** One plays it as a puzzle against the card; up to six round a table. */
    val playerCount: Int = 2,
    /**
     * A second Yahtzee is worth a hundred on top, once the Yahtzee box itself
     * holds fifty. Switched off, extra Yahtzees are only worth whatever box they
     * are written into.
     */
    val yahtzeeBonus: Boolean = true,
) {
    init {
        require(playerCount in 1..6) { "Yahtzee seats one to six" }
    }
}

/**
 * One player's card.
 *
 * Boxes are held as a list indexed by [YahtzeeCategory.ordinal] rather than a
 * map, so what crosses the wire is thirteen slots in a fixed order and a box
 * that has never been filled is plainly null rather than absent.
 */
@Serializable
data class YahtzeeCard(
    val boxes: List<Int?> = List(YahtzeeCategory.entries.size) { null },
) {
    operator fun get(category: YahtzeeCategory): Int? = boxes[category.ordinal]

    fun isFilled(category: YahtzeeCategory): Boolean = this[category] != null

    val isComplete: Boolean get() = boxes.none { it == null }

    fun with(category: YahtzeeCategory, score: Int): YahtzeeCard {
        require(!isFilled(category)) { "${category.label} is already written in" }
        return copy(boxes = boxes.toMutableList().also { it[category.ordinal] = score })
    }

    val upperSubtotal: Int
        get() = YahtzeeCategory.entries
            .filter { it.section == YahtzeeSection.UPPER }
            .sumOf { this[it] ?: 0 }

    val upperBonus: Int get() = if (upperSubtotal >= UPPER_BONUS_AT) UPPER_BONUS else 0

    val lowerSubtotal: Int
        get() = YahtzeeCategory.entries
            .filter { it.section == YahtzeeSection.LOWER }
            .sumOf { this[it] ?: 0 }

    /** Does not include Yahtzee bonuses, which are held on the state. */
    val total: Int get() = upperSubtotal + upperBonus + lowerSubtotal
}

@Serializable
data class YahtzeeState(
    val options: YahtzeeOptions,
    val seed: Long,
    /** Zero-based. Thirteen rounds fill thirteen boxes. */
    val round: Int,
    val turn: Int,
    /** Empty until the seat has rolled; five dice after that. */
    val dice: List<Int>,
    val rollsUsed: Int,
    val cards: List<YahtzeeCard>,
    /** Hundreds earned for extra Yahtzees, counted per seat. */
    val yahtzeeBonuses: List<Int>,
    val log: List<String>,
) {
    val rollsLeft: Int get() = ROLLS_PER_TURN - rollsUsed

    /** Nothing can be written in until the dice have been thrown at least once. */
    val hasRolled: Boolean get() = rollsUsed > 0

    fun totalFor(seat: Int): Int = cards[seat].total + yahtzeeBonuses[seat]

    val isOver: Boolean get() = cards.all { it.isComplete }
}

@Serializable
sealed interface YahtzeeMove

/**
 * Throws the dice, keeping the ones at [keep].
 *
 * Positions rather than faces: two fives are two different dice, and a player
 * who keeps one of them and rerolls the other is doing something a set of faces
 * could not express.
 */
@Serializable
@SerialName("roll")
data class RollDice(val keep: List<Int> = emptyList()) : YahtzeeMove

@Serializable
@SerialName("score")
data class ScoreIn(val category: YahtzeeCategory) : YahtzeeMove

/** How many of each face, indexed one to six. */
fun countsOf(dice: List<Int>): Map<Int, Int> = dice.groupingBy { it }.eachCount()

/** The longest run of consecutive faces present, however many of each. */
fun longestRun(dice: List<Int>): Int {
    val present = dice.toSortedSet()
    var best = 0
    var run = 0
    var previous: Int? = null
    for (face in present) {
        run = if (previous != null && face == previous + 1) run + 1 else 1
        best = maxOf(best, run)
        previous = face
    }
    return best
}

/**
 * What [category] is worth for [dice].
 *
 * A full house is strictly three of one and two of another, so five of a kind
 * does not pay for it — that is the printed rule, and the joker variations that
 * change it are not played here.
 */
fun scoreOf(category: YahtzeeCategory, dice: List<Int>): Int {
    val counts = countsOf(dice)
    val sum = dice.sum()
    return when (category) {
        YahtzeeCategory.ONES, YahtzeeCategory.TWOS, YahtzeeCategory.THREES,
        YahtzeeCategory.FOURS, YahtzeeCategory.FIVES, YahtzeeCategory.SIXES,
        -> {
            val face = category.face!!
            face * (counts[face] ?: 0)
        }

        YahtzeeCategory.THREE_OF_A_KIND -> if (counts.values.any { it >= 3 }) sum else 0
        YahtzeeCategory.FOUR_OF_A_KIND -> if (counts.values.any { it >= 4 }) sum else 0
        YahtzeeCategory.FULL_HOUSE ->
            if (counts.values.sorted() == listOf(2, 3)) 25 else 0

        YahtzeeCategory.SMALL_STRAIGHT -> if (longestRun(dice) >= 4) 30 else 0
        YahtzeeCategory.LARGE_STRAIGHT -> if (longestRun(dice) >= 5) 40 else 0
        YahtzeeCategory.YAHTZEE -> if (counts.values.any { it == DICE_COUNT }) YAHTZEE_SCORE else 0
        YahtzeeCategory.CHANCE -> sum
    }
}

fun isYahtzee(dice: List<Int>): Boolean =
    dice.size == DICE_COUNT && dice.toSet().size == 1
