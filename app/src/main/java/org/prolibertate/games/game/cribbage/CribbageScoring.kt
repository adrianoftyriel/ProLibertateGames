package org.prolibertate.games.game.cribbage

import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Rank

/**
 * Everything cribbage counts, as pure arithmetic over cards.
 *
 * Both halves of the game are here: [show] counts a hand against the starter,
 * and [peg] counts what the card just laid on the table scored. They are kept
 * apart because they genuinely differ — a flush and his nob are worth nothing
 * during the play, and the count reaching fifteen is worth nothing at the show.
 */
object CribbageScoring {

    /**
     * A hand of four counted with the [starter].
     *
     * [isCrib] tightens one rule only: a crib scores a flush only when all five
     * cards share a suit, where a hand takes four for its own four.
     */
    fun show(hand: List<Card>, starter: Card, isCrib: Boolean): List<ScoreLine> {
        val all = hand + starter
        val lines = mutableListOf<ScoreLine>()

        val fifteens = countFifteens(all)
        if (fifteens > 0) {
            lines += ScoreLine(
                label = if (fifteens == 1) "Fifteen" else "$fifteens fifteens",
                points = fifteens * 2,
            )
        }

        lines += pairs(all)
        lines += runs(all)
        flush(hand, starter, isCrib)?.let { lines += it }

        if (hand.any { it.rank == Rank.JACK && it.suit == starter.suit }) {
            lines += ScoreLine("His nob", 1)
        }
        return lines
    }

    /**
     * What the last card of [series] scored as it landed.
     *
     * The series is the run of cards since the count was last cleared, so
     * everything here is read off its tail: only cards still on the table
     * between one thirty-one and the next can pair or make a run.
     */
    fun peg(series: List<Card>): List<ScoreLine> {
        if (series.isEmpty()) return emptyList()
        val lines = mutableListOf<ScoreLine>()
        val count = series.sumOf { pipValue(it) }

        if (count == 15) lines += ScoreLine("Fifteen", 2)
        if (count == CRIBBAGE_LIMIT) lines += ScoreLine("Thirty-one", 2)

        // Pairs peg only off the end: a pair split by somebody else's card is
        // not a pair, which is the whole point of playing one to break it up.
        val last = series.last()
        var matching = 0
        for (card in series.asReversed()) {
            if (card.rank != last.rank) break
            matching++
        }
        if (matching >= 2) lines += ScoreLine(pairLabel(matching), matching * (matching - 1))

        // The longest tail that is a run, in any order — 5-3-4 is a run of three.
        for (length in series.size downTo 3) {
            val tail = series.takeLast(length).map { runOrder(it) }
            if (tail.distinct().size == length && tail.max() - tail.min() == length - 1) {
                lines += ScoreLine("Run of $length", length)
                break
            }
        }
        return lines
    }

    /** Every combination adding to fifteen, of any size. */
    private fun countFifteens(cards: List<Card>): Int {
        var found = 0
        for (mask in 1 until (1 shl cards.size)) {
            var sum = 0
            for (index in cards.indices) {
                if ((mask shr index) and 1 == 1) sum += pipValue(cards[index])
            }
            if (sum == 15) found++
        }
        return found
    }

    private fun pairs(cards: List<Card>): List<ScoreLine> = cards
        .groupBy { it.rank }
        .values
        .filter { it.size >= 2 }
        .sortedBy { runOrder(it.first()) }
        .map { group ->
            ScoreLine(
                label = "${pairLabel(group.size)} of ${group.first().rank.short}s",
                points = group.size * (group.size - 1),
            )
        }

    private fun pairLabel(size: Int): String = when (size) {
        2 -> "Pair"
        3 -> "Pair royal"
        else -> "Double pair royal"
    }

    /**
     * Runs, with duplicated ranks multiplying rather than adding.
     *
     * 4-5-5-6 is two runs of three and not a run of four: the two fives each
     * make their own run with the four and the six, which is why the length is
     * multiplied by how many ways each rank in it can be filled.
     */
    private fun runs(cards: List<Card>): List<ScoreLine> {
        val copies = cards.groupingBy { runOrder(it) }.eachCount()
        val ordered = copies.keys.sorted()
        val lines = mutableListOf<ScoreLine>()

        var index = 0
        while (index < ordered.size) {
            var end = index
            while (end + 1 < ordered.size && ordered[end + 1] == ordered[end] + 1) end++
            val length = end - index + 1
            if (length >= 3) {
                val ways = (index..end).fold(1) { acc, at -> acc * copies.getValue(ordered[at]) }
                lines += ScoreLine(
                    label = if (ways == 1) "Run of $length" else "$ways runs of $length",
                    points = length * ways,
                )
            }
            index = end + 1
        }
        return lines
    }

    private fun flush(hand: List<Card>, starter: Card, isCrib: Boolean): ScoreLine? {
        val suit = hand.firstOrNull()?.suit ?: return null
        if (hand.any { it.suit != suit }) return null
        val withStarter = starter.suit == suit
        // A crib takes nothing for four of a suit — it has to be all five.
        if (isCrib && !withStarter) return null
        val size = hand.size + if (withStarter) 1 else 0
        return ScoreLine("Flush of $size", size)
    }
}
