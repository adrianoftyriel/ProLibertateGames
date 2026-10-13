package org.prolibertate.games.score

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.net.Hello
import org.prolibertate.games.net.NetMessage
import org.prolibertate.games.net.PURPOSE_GAME
import org.prolibertate.games.net.SheetEdit
import org.prolibertate.games.net.SheetState
import org.prolibertate.games.net.protocolJson

class SheetOpTest {

    private val table = ScoreSheet.of(3)
    private val ids = table.players.map { it.id }

    @Test
    fun `every op does what the sheet method of the same name does`() {
        assertEquals(table.withPlayerAdded(), AddPlayer.applyTo(table))
        assertEquals(table.renamed(ids[1], "Bob"), RenamePlayer(ids[1], "Bob").applyTo(table))
        assertEquals(table.withPlayerRemoved(ids[2]), RemovePlayer(ids[2]).applyTo(table))
        assertEquals(table.moved(0, 2), MovePlayer(ids[0], 2).applyTo(table))
        assertEquals(ScoreSheet.of(5), StartSheet(5).applyTo(table))
        assertEquals(ScoreSheet(), ClearSheet.applyTo(table))

        val scored = AddRound(mapOf(ids[0] to 4)).applyTo(table)
        assertEquals(table.withRound(mapOf(ids[0] to 4)), scored)
        assertEquals(4, EditRound(0, mapOf(ids[0] to 4)).applyTo(AddRound(mapOf(ids[0] to 1)).applyTo(table)).total(ids[0]))
        assertTrue(DeleteRound(0).applyTo(scored).rounds.isEmpty())
    }

    @Test
    fun `an op that no longer makes sense is a no-op`() {
        val scored = AddRound(mapOf(ids[0] to 4)).applyTo(table)

        assertEquals(scored, RenamePlayer(999, "Nobody").applyTo(scored))
        assertEquals(scored, MovePlayer(999, 0).applyTo(scored))
        assertEquals(scored, RemovePlayer(999).applyTo(scored))
        assertEquals(scored, EditRound(7, mapOf(ids[0] to 1)).applyTo(scored))
        assertEquals(scored, DeleteRound(7).applyTo(scored))
    }

    @Test
    fun `moving a column goes by who it is, not where it was`() {
        // Somebody else has already moved the column this guest was looking at.
        val shuffled = table.moved(0, 2)
        val moved = MovePlayer(ids[0], 0).applyTo(shuffled)
        assertEquals(ids, moved.players.map { it.id })
    }

    @Test
    fun `two rounds entered at once are two rounds`() {
        val both = listOf(
            AddRound(mapOf(ids[0] to 3)),
            AddRound(mapOf(ids[1] to 5)),
        ).fold(table) { sheet, op -> op.applyTo(sheet) }

        assertEquals(2, both.rounds.size)
        assertEquals(3, both.total(ids[0]))
        assertEquals(5, both.total(ids[1]))
    }

    @Test
    fun `every op survives the wire`() {
        val ops = listOf(
            StartSheet(4), ClearSheet, AddPlayer, RemovePlayer(2), RenamePlayer(1, "Ann"),
            MovePlayer(1, 3), AddRound(mapOf(1 to 5, 2 to -3, 3 to 0)),
            EditRound(0, mapOf(1 to 9)), DeleteRound(2),
        )
        ops.forEach { op ->
            val message: NetMessage = SheetEdit(seq = 7, op = op)
            val decoded = protocolJson.decodeFromString<NetMessage>(protocolJson.encodeToString(message))
            assertEquals(message, decoded)
        }
    }

    @Test
    fun `a sheet survives the wire with its rounds`() {
        val sheet = AddRound(mapOf(ids[0] to 3, ids[2] to -2)).applyTo(table)
        val message: NetMessage = SheetState(revision = 4, sheet = sheet, acknowledged = 2)
        assertEquals(message, protocolJson.decodeFromString<NetMessage>(protocolJson.encodeToString(message)))
    }

    @Test
    fun `a hello from before there were score sheets is a hello for a game`() {
        val old = """{"t":"hello","peerId":"p","displayName":"Ann","protocol":2}"""
        val hello = protocolJson.decodeFromString<NetMessage>(old) as Hello
        assertEquals(PURPOSE_GAME, hello.purpose)
    }
}

class SheetReplicaTest {

    private val base = ScoreSheet.of(2)
    private val a = base.players[0].id
    private val b = base.players[1].id

    @Test
    fun `a change shows at once`() {
        val replica = SheetReplica()
        replica.receive(base, revision = 1, acknowledged = 0)

        replica.local(AddRound(mapOf(a to 5)))

        assertEquals(5, replica.view.total(a))
        assertEquals(1, replica.unconfirmed)
    }

    @Test
    fun `a sheet that has not seen my change does not wipe it out`() {
        val replica = SheetReplica()
        replica.receive(base, 1, 0)
        replica.local(AddRound(mapOf(a to 5)))

        // The host pushes a rename it made before it had heard about the round.
        replica.receive(base.renamed(b, "Bob"), 2, 0)

        assertEquals("Bob", replica.view.players[1].name)
        assertEquals(5, replica.view.total(a))
    }

    @Test
    fun `a change the host has applied is not applied again`() {
        val replica = SheetReplica()
        replica.receive(base, 1, 0)
        val seq = replica.local(AddRound(mapOf(a to 5)))

        // The host's sheet now includes the round, and says so.
        replica.receive(base.withRound(mapOf(a to 5)), 2, seq)

        assertEquals(5, replica.view.total(a))
        assertEquals(1, replica.view.rounds.size)
        assertEquals(0, replica.unconfirmed)
    }

    @Test
    fun `only the acknowledged changes are dropped`() {
        val replica = SheetReplica()
        replica.receive(base, 1, 0)
        val first = replica.local(AddRound(mapOf(a to 1)))
        replica.local(AddRound(mapOf(a to 10)))

        replica.receive(base.withRound(mapOf(a to 1)), 2, first)

        assertEquals(1, replica.unconfirmed)
        assertEquals(11, replica.view.total(a))
        assertEquals(2, replica.view.rounds.size)
    }

    @Test
    fun `an older sheet that arrives late is ignored`() {
        val replica = SheetReplica()
        replica.receive(base.withRound(mapOf(a to 3)), 5, 0)
        replica.receive(base, 4, 0)

        assertEquals(3, replica.view.total(a))
        assertEquals(5, replica.revision)
    }

    @Test
    fun `a fresh link starts from nothing`() {
        val replica = SheetReplica()
        replica.receive(base, 3, 0)
        replica.local(AddRound(mapOf(a to 5)))

        replica.reset()

        assertEquals(ScoreSheet(), replica.view)
        assertEquals(0, replica.unconfirmed)
        // And a sheet at an early revision is taken rather than thought stale.
        replica.receive(base, 1, 0)
        assertEquals(base, replica.view)
    }
}
