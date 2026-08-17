package org.prolibertate.games.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * The heartbeat, which exists because a link with nothing on it cannot be told
 * from a link that is no longer there.
 *
 * Between one turn of a card game and the next, nothing at all crosses the
 * socket. A phone whose screen has gone off puts its Wi-Fi radio into power
 * save and, given long enough, the link goes with it — and TCP does not say so:
 * the read simply never returns, and the next write disappears into a socket
 * that goes nowhere. So the transport talks to itself, and treats a peer that
 * has stopped answering as gone.
 */
class StreamConnectionHeartbeatTest {

    private val scopes = mutableListOf<CoroutineScope>()
    private val closeables = mutableListOf<() -> Unit>()

    @After
    fun tearDown() {
        closeables.forEach { runCatching { it() } }
        scopes.forEach { it.cancel() }
    }

    private fun scope(): CoroutineScope =
        CoroutineScope(Job() + Dispatchers.Default).also { scopes += it }

    // -----------------------------------------------------------------------

    /**
     * The case the whole thing has to get right: two players thinking, nobody
     * moving, for far longer than a link is allowed to be silent. Both ends
     * answer each other's pings, so neither concludes the other has gone.
     */
    @Test
    fun `a quiet link between two live peers is left alone`() = runBlocking {
        val server = ServerSocket(0)
        val clientSocket = Socket()
        closeables += { server.close() }
        closeables += { clientSocket.close() }

        clientSocket.connect(InetSocketAddress("127.0.0.1", server.localPort), CONNECT_MILLIS)
        val hostSocket = server.accept()
        closeables += { hostSocket.close() }

        val hostScope = scope()
        val clientScope = scope()
        val a = connection("host", hostSocket.getInputStream(), hostSocket.getOutputStream(), hostScope)
        val b = connection("guest", clientSocket.getInputStream(), clientSocket.getOutputStream(), clientScope)

        val heardByA = subscribe(a, hostScope)
        val heardByB = subscribe(b, clientScope)

        // Several times the silence allowance, with not one game message sent.
        delay(SILENCE_MILLIS * 4)

        assertTrue("The host's end gave up on a peer that was answering", a.isOpen)
        assertTrue("The guest's end gave up on a peer that was answering", b.isOpen)
        // And the pings are the transport's own business: nothing above it sees
        // them, so a screen is not asked to redraw twice a second for nothing.
        assertEquals(emptyList<NetMessage>(), heardByA)
        assertEquals(emptyList<NetMessage>(), heardByB)
    }

    /**
     * A link that has died without saying so. The reader would wait on it for
     * as long as the game lasted, which is what left a player tapping a board
     * that answered nothing.
     */
    @Test
    fun `a peer that stops answering is given up on and reported`() = runBlocking {
        val scope = scope()
        val input = BlockingInputStream()
        closeables += { input.close() }

        val link = connection("gone", input, DiscardingOutputStream(), scope)
        val heard = subscribe(link, scope)

        // Long enough for the silence to be noticed, and then some.
        delay(SILENCE_MILLIS * 3)

        assertFalse("A link nobody is answering on was left open", link.isOpen)
        assertEquals(
            "The screen was never told the link had gone",
            listOf<NetMessage>(Bye),
            heard,
        )
    }

    /** A link this end let go of is not somebody else going away. */
    @Test
    fun `closing a link on purpose says nothing`() = runBlocking {
        val scope = scope()
        val input = BlockingInputStream()
        closeables += { input.close() }

        val link = connection("ours", input, DiscardingOutputStream(), scope)
        val heard = subscribe(link, scope)

        link.close()
        delay(SILENCE_MILLIS)

        assertFalse(link.isOpen)
        assertEquals(emptyList<NetMessage>(), heard)
    }

    // -----------------------------------------------------------------------

    private fun connection(
        peerId: String,
        input: InputStream,
        output: OutputStream,
        scope: CoroutineScope,
    ) = StreamConnection(
        peerId = peerId,
        kind = TransportKind.LAN,
        input = input,
        output = output,
        scope = scope,
        heartbeatMillis = HEARTBEAT_MILLIS,
        silenceMillis = SILENCE_MILLIS,
    )

    /** Everything a screen would have been shown, in order. */
    private fun subscribe(link: Connection, scope: CoroutineScope): List<NetMessage> {
        val heard = Collections.synchronizedList(mutableListOf<NetMessage>())
        scope.launch { link.incoming.collect { heard += it } }
        return heard
    }

    /**
     * A stream that has nothing to say and never will, which is what a link
     * whose far end has vanished looks like from here. Blocks until it is
     * closed, then fails the read the way a closed socket does.
     */
    private class BlockingInputStream : InputStream() {
        private val gate = CountDownLatch(1)

        override fun read(): Int {
            gate.await()
            throw java.io.IOException("stream closed")
        }

        override fun close() = gate.countDown()
    }

    private class DiscardingOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
    }

    private companion object {
        const val CONNECT_MILLIS = 2_000

        /**
         * The real intervals are seconds; these are the same arrangement run
         * fast, so the tests take a moment rather than a minute.
         */
        const val HEARTBEAT_MILLIS = 40L
        const val SILENCE_MILLIS = 200L
    }
}
