package org.prolibertate.games.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.prolibertate.games.net.ScorekeeperSession.Role
import org.prolibertate.games.score.AddRound
import org.prolibertate.games.score.RenamePlayer
import org.prolibertate.games.score.ScoreSheet
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * A shared score sheet between real sessions, over real sockets on loopback.
 *
 * The sheet logic is covered on its own; what is worth testing here is the part
 * that only exists with a link in it — being asked before being let in, being
 * let back in without asking, and everybody ending up looking at one sheet.
 */
class ScorekeeperSessionTest {

    private val scopes = mutableListOf<CoroutineScope>()
    private val sessions = mutableListOf<ScorekeeperSession>()
    private val transports = mutableListOf<LoopbackTransport>()

    @After
    fun tearDown() {
        sessions.forEach { runCatching { it.stop() } }
        transports.forEach { runCatching { it.stop() } }
        scopes.forEach { it.cancel() }
    }

    /** TCP on loopback, with the discovery a test does not need left out. */
    private class LoopbackTransport : Transport {
        override val kind = TransportKind.LAN

        @Volatile
        var server: ServerSocket? = null
        val port: Int get() = server!!.localPort

        override fun isAvailable() = true

        override fun host(
            displayName: String,
            scope: CoroutineScope,
            purpose: HostPurpose,
        ): Flow<Connection> = callbackFlow {
            val listening = ServerSocket(0)
            server = listening
            val accepting = scope.launch(Dispatchers.IO) {
                while (isActive && !listening.isClosed) {
                    val socket = runCatching { listening.accept() }.getOrNull() ?: break
                    trySend(
                        StreamConnection(
                            peerId = "guest:${socket.port}",
                            kind = TransportKind.LAN,
                            input = socket.getInputStream(),
                            output = socket.getOutputStream(),
                            scope = scope,
                            onClosed = { runCatching { socket.close() } },
                        )
                    )
                }
            }
            awaitClose {
                accepting.cancel()
                runCatching { listening.close() }
            }
        }

        override fun discover(scope: CoroutineScope): Flow<List<DiscoveredHost>> = flowOf(emptyList())

        override suspend fun join(host: DiscoveredHost, scope: CoroutineScope): Connection {
            val socket = Socket("127.0.0.1", host.port)
            return StreamConnection(
                peerId = host.id,
                kind = TransportKind.LAN,
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                scope = scope,
                onClosed = { runCatching { socket.close() } },
            )
        }

        override fun stop() {
            runCatching { server?.close() }
        }
    }

    private fun newSession(deviceId: String): Pair<ScorekeeperSession, LoopbackTransport> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val transport = LoopbackTransport().also { transports += it }
        val session = ScorekeeperSession(transport, MutableStateFlow(null), scope, deviceId)
            .also { sessions += it }
        return session to transport
    }

    private class Host(val session: ScorekeeperSession, val transport: LoopbackTransport) {
        val state get() = session.state.value
    }

    private fun startHost(sheet: ScoreSheet = ScoreSheet.of(2)): Host {
        val (session, transport) = newSession("host-device")
        session.startHosting("Hosty", sheet)
        await("the host to be listening") { transport.server != null }
        return Host(session, transport)
    }

    private fun joinOf(host: Host) = DiscoveredHost(
        id = "lan:127.0.0.1:${host.transport.port}",
        name = "Hosty",
        kind = TransportKind.LAN,
        address = "127.0.0.1",
        port = host.transport.port,
        purpose = HostPurpose.SCOREKEEPER,
    )

    private fun guestOf(host: Host, deviceId: String, name: String): ScorekeeperSession {
        val (session, _) = newSession(deviceId)
        session.join(joinOf(host), name)
        return session
    }

    /** A guest that has asked, been let in, and is looking at the host's sheet. */
    private fun admittedGuest(host: Host, deviceId: String, name: String): ScorekeeperSession {
        val guest = guestOf(host, deviceId, name)
        await("$name to be asking") { host.state.requests.any { it.id == deviceId } }
        host.session.allow(deviceId)
        await("$name to be let in") { guest.state.value.connected }
        return guest
    }

    @Test
    fun `a guest is asked about, and sees nothing, until the host allows it`() {
        val host = startHost()
        val guest = guestOf(host, "g1", "Gwen")

        await("the request to reach the host") { host.state.requests.isNotEmpty() }
        assertEquals("Gwen", host.state.requests.single().name)
        assertTrue(guest.state.value.awaiting)
        assertFalse(guest.state.value.connected)
        assertFalse(guest.state.value.sheet.started)
        assertTrue(host.state.guests.isEmpty())

        host.session.allow("g1")

        await("the guest to be let in") { guest.state.value.connected }
        assertFalse(guest.state.value.awaiting)
        assertEquals(host.state.sheet, guest.state.value.sheet)
        assertTrue(guest.state.value.sheet.started)
        assertTrue(host.state.requests.isEmpty())
        assertEquals(listOf("Gwen"), host.state.guests.map { it.name })
    }

    @Test
    fun `a guest that is turned away stays turned away`() {
        val host = startHost()
        val guest = guestOf(host, "g1", "Gwen")
        await("the request to reach the host") { host.state.requests.isNotEmpty() }

        host.session.deny("g1")

        await("the refusal to arrive") { guest.state.value.refused }
        assertEquals("The host declined.", guest.state.value.message)
        assertFalse(guest.state.value.connected)
        assertFalse(guest.state.value.sheet.started)
        assertTrue(host.state.guests.isEmpty())
        assertTrue(host.state.requests.isEmpty())
    }

    @Test
    fun `a guest's change reaches the host and the other guests`() {
        val host = startHost()
        val first = admittedGuest(host, "g1", "Gwen")
        val second = admittedGuest(host, "g2", "Gus")
        val player = host.state.sheet.players[0].id

        first.edit(AddRound(mapOf(player to 7)))

        // Shown at once on the guest that made it, without waiting for the host.
        assertEquals(7, first.state.value.sheet.total(player))
        await("the host to apply it") { host.state.sheet.total(player) == 7 }
        await("the other guest to see it") { second.state.value.sheet.total(player) == 7 }
        assertEquals(1, host.state.sheet.rounds.size)
    }

    @Test
    fun `the host's own change reaches the guests`() {
        val host = startHost()
        val guest = admittedGuest(host, "g1", "Gwen")
        val player = host.state.sheet.players[1].id

        host.session.edit(RenamePlayer(player, "Hosty"))

        await("the guest to see the new name") {
            guest.state.value.sheet.players[1].name == "Hosty"
        }
    }

    @Test
    fun `changes made at the same moment all land`() {
        val host = startHost()
        val first = admittedGuest(host, "g1", "Gwen")
        val second = admittedGuest(host, "g2", "Gus")
        val player = host.state.sheet.players[0].id

        first.edit(AddRound(mapOf(player to 1)))
        second.edit(AddRound(mapOf(player to 10)))
        host.session.edit(AddRound(mapOf(player to 100)))

        await("every round to land everywhere") {
            listOf(host.state.sheet, first.state.value.sheet, second.state.value.sheet)
                .all { it.rounds.size == 3 && it.total(player) == 111 }
        }
    }

    @Test
    fun `a guest asking to sync is sent the sheet as it stands`() {
        val host = startHost()
        val guest = admittedGuest(host, "g1", "Gwen")
        val player = host.state.sheet.players[0].id
        host.session.edit(AddRound(mapOf(player to 4)))

        guest.sync()

        await("the guest to be in step") { guest.state.value.sheet.total(player) == 4 }
        assertEquals(host.state.sheet, guest.state.value.sheet)
    }

    @Test
    fun `a guest that was let in is let back in without being asked again`() {
        val host = startHost()
        val first = admittedGuest(host, "g1", "Gwen")
        // The phone is put down and its link goes with it.
        first.stop()
        await("the host to notice") { host.state.guests.singleOrNull()?.online == false }

        val back = guestOf(host, "g1", "Gwen")

        await("the guest to be back on the sheet") { back.state.value.connected }
        assertTrue(host.state.requests.isEmpty())
        assertEquals(1, host.state.guests.size)
        assertTrue(host.state.guests.single().online)
        assertEquals(host.state.sheet, back.state.value.sheet)
    }

    @Test
    fun `a guest the host removes has to ask again`() {
        val host = startHost()
        val guest = admittedGuest(host, "g1", "Gwen")

        host.session.remove("g1")

        await("the removal to arrive") { guest.state.value.refused }
        assertEquals("The host removed you.", guest.state.value.message)
        assertTrue(host.state.guests.isEmpty())

        val again = guestOf(host, "g1", "Gwen")
        await("the new request") { host.state.requests.any { it.id == "g1" } }
        assertFalse(again.state.value.connected)
    }

    @Test
    fun `somebody who is not let in cannot change the sheet`() {
        val host = startHost()
        val (_, transport) = newSession("stranger")
        val before = host.state.sheet
        val player = before.players[0].id

        runBlocking {
            val raw = transport.join(joinOf(host), CoroutineScope(Dispatchers.IO).also { scopes += it })
            val hello = Hello("stranger", "Stan", purpose = PURPOSE_SCOREKEEPER)
            // Said more than once, as a real guest does: the host may not be
            // listening yet the first time. Saying it twice is harmless.
            repeat(3) {
                raw.send(hello)
                delay(150)
            }
            raw.send(SheetEdit(1, AddRound(mapOf(player to 99))))
        }

        await("the request to reach the host") { host.state.requests.any { it.id == "stranger" } }
        Thread.sleep(300)
        assertEquals(before, host.state.sheet)
    }

    @Test
    fun `a phone looking for a game is told this is a score sheet`() {
        val host = startHost()
        val (_, transport) = newSession("gamer")
        val answer = AtomicInteger(0)

        runBlocking {
            val scope = CoroutineScope(Dispatchers.IO).also { scopes += it }
            val raw = transport.join(joinOf(host), scope)
            val reply = scope.launch {
                raw.incoming
                    .onSubscription { raw.send(Hello("gamer", "Gail", purpose = PURPOSE_GAME)) }
                    .collect { message ->
                        if (message is Welcome && !message.accepted) {
                            assertEquals("That is a score sheet, not a game.", message.reason)
                            answer.set(1)
                        }
                    }
            }
            val hello = Hello("gamer", "Gail", purpose = PURPOSE_GAME)
            val end = System.currentTimeMillis() + 5_000
            while (answer.get() == 0 && System.currentTimeMillis() < end) {
                delay(300)
                if (answer.get() == 0) raw.send(hello)
            }
            reply.cancel()
        }

        assertEquals(1, answer.get())
        assertTrue(host.state.requests.isEmpty())
        assertEquals(Role.HOST, host.state.role)
    }

    @Test
    fun `stopping hosting lets go of everybody`() {
        val host = startHost()
        val guest = admittedGuest(host, "g1", "Gwen")

        host.session.stop()

        assertEquals(Role.NONE, host.state.role)
        assertTrue(host.state.guests.isEmpty())
        await("the guest to notice") { !guest.state.value.connected }
    }

    private fun await(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for $what")
    }
}
