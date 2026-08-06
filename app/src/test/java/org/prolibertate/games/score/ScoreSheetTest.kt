package org.prolibertate.games.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreSheetTest {

    private fun sheetOf(vararg names: String): ScoreSheet =
        names.foldIndexed(ScoreSheet.of(names.size)) { index, sheet, name ->
            sheet.renamed(sheet.players[index].id, name)
        }

    @Test
    fun `a new sheet seats the players asked for, unnamed`() {
        val sheet = ScoreSheet.of(5)
        assertEquals(5, sheet.players.size)
        assertTrue(sheet.started)
        assertTrue(sheet.rounds.isEmpty())
        assertEquals("Player 3", sheet.displayName(sheet.players[2]))
    }

    @Test
    fun `a table cannot be smaller than two or larger than twelve`() {
        assertEquals(ScoreSheet.MIN_PLAYERS, ScoreSheet.of(1).players.size)
        assertEquals(ScoreSheet.MAX_PLAYERS, ScoreSheet.of(40).players.size)
    }

    @Test
    fun `points add up round on round, and can be taken away`() {
        val sheet = sheetOf("Alice", "Bob")
            .let { it.withRound(mapOf(it.players[0].id to 3, it.players[1].id to -1)) }
            .let { it.withRound(mapOf(it.players[0].id to -5, it.players[1].id to 10)) }

        assertEquals(-2, sheet.total(sheet.players[0].id))
        assertEquals(9, sheet.total(sheet.players[1].id))
        assertEquals(2, sheet.rounds.size)
    }

    @Test
    fun `a player with no entry that round scored nothing`() {
        val sheet = sheetOf("Alice", "Bob").let { it.withRound(mapOf(it.players[0].id to 4)) }
        assertEquals(0, sheet.total(sheet.players[1].id))
        // Nothing scored is not worth storing.
        assertEquals(1, sheet.rounds.first().deltas.size)
    }

    @Test
    fun `dragging a player into a different order leaves the scores where they were`() {
        val start = sheetOf("Alice", "Bob", "Carol")
            .let {
                it.withRound(
                    mapOf(
                        it.players[0].id to 1,
                        it.players[1].id to 2,
                        it.players[2].id to 3,
                    )
                )
            }
        val alice = start.players[0].id
        val carol = start.players[2].id

        // Carol dragged to the front.
        val moved = start.moved(from = 2, to = 0)

        assertEquals(listOf("Carol", "Alice", "Bob"), moved.players.map { it.name })
        assertEquals(3, moved.total(carol))
        assertEquals(1, moved.total(alice))
    }

    @Test
    fun `a drag that lands where it started changes nothing`() {
        val sheet = sheetOf("Alice", "Bob", "Carol")
        assertEquals(sheet, sheet.moved(from = 1, to = 1))
        // Off the end of the list is clamped rather than rejected: a finger can
        // always be dragged further than the list is long.
        assertEquals(listOf("Bob", "Carol", "Alice"), sheet.moved(from = 0, to = 9).players.map { it.name })
    }

    @Test
    fun `renaming touches nobody else`() {
        val sheet = sheetOf("Alice", "Bob")
        val renamed = sheet.renamed(sheet.players[0].id, "Alasdair")
        assertEquals(listOf("Alasdair", "Bob"), renamed.players.map { it.name })
    }

    @Test
    fun `a blank name shows as the seat, and is stored as typed`() {
        val sheet = sheetOf("Alice", "Bob").let { it.renamed(it.players[0].id, "") }
        assertEquals("", sheet.players[0].name)
        assertEquals("Player 1", sheet.displayName(sheet.players[0]))
        // Whitespace is not a name either.
        assertEquals("Player 1", sheet.displayName(sheet.players[0].copy(name = "   ")))
    }

    @Test
    fun `adding a player mid-game gives them a fresh id and no history`() {
        val start = sheetOf("Alice", "Bob", "Carol")
            .let { it.withRound(mapOf(it.players[1].id to 7)) }
        val bob = start.players[1].id

        // Bob leaves and somebody else sits down in his place. The id he was
        // scored against must not come round again with him.
        val grown = start.withPlayerRemoved(bob).withPlayerAdded()
        val newcomer = grown.players.last()

        assertNotEquals(bob, newcomer.id)
        assertEquals(0, grown.total(newcomer.id))
        assertEquals(3, grown.players.size)
    }

    @Test
    fun `removing a player takes their score with them`() {
        val start = sheetOf("Alice", "Bob", "Carol")
            .let { it.withRound(mapOf(it.players[0].id to 5, it.players[1].id to 6)) }
        val bob = start.players[1].id

        val without = start.withPlayerRemoved(bob)

        assertEquals(listOf("Alice", "Carol"), without.players.map { it.name })
        assertEquals(5, without.total(without.players[0].id))
        assertTrue("Bob's points went with him", without.rounds.all { bob !in it.deltas })
        // The round itself stays: it was still played.
        assertEquals(1, without.rounds.size)
    }

    @Test
    fun `shrinking the table drops from the end and prunes those scores`() {
        val start = sheetOf("Alice", "Bob", "Carol")
            .let { it.withRound(mapOf(it.players[2].id to 9)) }
        val carol = start.players[2].id

        val smaller = start.withPlayerCount(2)

        assertEquals(listOf("Alice", "Bob"), smaller.players.map { it.name })
        assertTrue(smaller.rounds.all { carol !in it.deltas })
    }

    @Test
    fun `the last two players cannot be removed`() {
        val sheet = sheetOf("Alice", "Bob")
        assertEquals(sheet, sheet.withPlayerRemoved(sheet.players[0].id))
    }

    @Test
    fun `a round can be corrected or struck out`() {
        val start = sheetOf("Alice", "Bob")
            .let { it.withRound(mapOf(it.players[0].id to 1)) }
            .let { it.withRound(mapOf(it.players[0].id to 100)) }
        val alice = start.players[0].id

        val corrected = start.withRoundAt(1, mapOf(alice to 10))
        assertEquals(11, corrected.total(alice))

        val struck = corrected.withoutRound(0)
        assertEquals(1, struck.rounds.size)
        assertEquals(10, struck.total(alice))
    }

    @Test
    fun `points for somebody not at the table are ignored`() {
        val sheet = sheetOf("Alice", "Bob")
        val stranger = 9999
        val recorded = sheet.withRound(mapOf(sheet.players[0].id to 2, stranger to 50))
        assertEquals(mapOf(sheet.players[0].id to 2), recorded.rounds.first().deltas)
    }

    @Test
    fun `an out of range round index is left alone rather than crashing`() {
        val sheet = sheetOf("Alice", "Bob").withRound(mapOf())
        assertEquals(sheet, sheet.withRoundAt(7, mapOf()))
        assertEquals(sheet, sheet.withoutRound(-1))
        assertEquals(sheet, sheet.moved(from = 5, to = 0))
    }

    @Test
    fun `totals cover everybody at the table`() {
        val sheet = sheetOf("Alice", "Bob", "Carol")
            .let { it.withRound(mapOf(it.players[1].id to 4)) }
        assertEquals(sheet.players.map { it.id }.toSet(), sheet.totals().keys)
        assertEquals(0, sheet.totals()[sheet.players[0].id])
        assertEquals(4, sheet.totals()[sheet.players[1].id])
    }
}
