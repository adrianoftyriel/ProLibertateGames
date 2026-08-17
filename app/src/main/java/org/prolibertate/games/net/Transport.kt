package org.prolibertate.games.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

enum class TransportKind(val label: String) {
    LAN("Wi-Fi"),
}

/**
 * A host someone could join, found by one of the transports. [address] is
 * transport-specific: an IP for LAN.
 */
data class DiscoveredHost(
    val id: String,
    val name: String,
    val kind: TransportKind,
    val address: String,
    val port: Int = 0,
)

/**
 * One peer-to-peer link carrying [NetMessage]s.
 *
 * A transport ends up with a plain pair of streams, so the framing and
 * serialisation live here rather than in each transport.
 */
interface Connection : Closeable {
    val peerId: String
    val kind: TransportKind
    val incoming: SharedFlow<NetMessage>
    suspend fun send(message: NetMessage)
    val isOpen: Boolean
}

/**
 * Newline-delimited JSON over a stream pair.
 *
 * Line framing keeps this debuggable — you can watch a session with netcat —
 * and JSON payloads never contain a raw newline, so the framing is safe.
 */
class StreamConnection(
    override val peerId: String,
    override val kind: TransportKind,
    private val input: InputStream,
    private val output: OutputStream,
    private val scope: CoroutineScope,
    private val onClosed: (StreamConnection) -> Unit = {},
    /** How often a link with nothing to say is pinged. */
    private val heartbeatMillis: Long = HEARTBEAT_MILLIS,
    /** How long a link may go completely silent before it counts as gone. */
    private val silenceMillis: Long = SILENCE_MILLIS,
) : Connection {

    private val reader: BufferedReader = input.bufferedReader()
    private val writer: BufferedWriter = output.bufferedWriter()
    private val writeLock = Mutex()
    private val closed = AtomicBoolean(false)

    /**
     * Whether this end closed the link on purpose, as leaving a game does.
     *
     * Kept apart from [closed] because the two answer different questions. A
     * link is closed either way; only one of the two ways is worth telling
     * anybody about, and the heartbeat closes links that very much are.
     */
    private val closedOnPurpose = AtomicBoolean(false)

    /**
     * When the peer was last heard from, on the monotonic clock.
     *
     * Wall time would do the wrong thing across a clock adjustment, and
     * SystemClock is Android's, which the unit tests do not have.
     */
    @Volatile
    private var lastHeardNanos: Long = System.nanoTime()

    private val _incoming = MutableSharedFlow<NetMessage>(replay = 0, extraBufferCapacity = 64)
    override val incoming: SharedFlow<NetMessage> = _incoming

    override val isOpen: Boolean get() = !closed.get()

    init {
        scope.launch(Dispatchers.IO) {
            var droppedByPeer = false
            try {
                while (isActive && !closed.get()) {
                    val line = reader.readLine()
                    if (line == null) {
                        droppedByPeer = true
                        break
                    }
                    // Anything at all counts as a sign of life, including a line
                    // this version cannot make sense of: the point is that the
                    // peer is still there and still talking.
                    lastHeardNanos = System.nanoTime()
                    if (line.isBlank()) continue
                    val message = runCatching {
                        protocolJson.decodeFromString<NetMessage>(line)
                    }.getOrNull() ?: continue // ignore anything we can't parse
                    // The heartbeat is the transport's own business and stops
                    // here. Answering on this thread is safe: send() hops to IO,
                    // which is where this already is.
                    when (message) {
                        is Ping -> send(Pong)
                        is Pong -> Unit
                        else -> _incoming.emit(message)
                    }
                }
            } catch (_: Exception) {
                // A dropped link is normal: someone walked out of range or
                // closed the app. Fall through to close().
                droppedByPeer = true
            } finally {
                // Safe here and nowhere else: this thread owns the reader's
                // lock, so it is the only one that can close it without
                // waiting. See close().
                runCatching { reader.close() }
                val deliberate = closedOnPurpose.get()
                shutdown()
                // A link that died on its own reaches the screens on the same
                // channel as everything else, so losing a player is something the
                // other end finds out about the same way it finds out about a
                // move. Withdrawing deliberately needs no announcement, and the
                // emit is not cancellable — the whole point of it is to arrive
                // while everything around it is being torn down.
                if (droppedByPeer && !deliberate) {
                    withContext(NonCancellable) { runCatching { _incoming.emit(Bye) } }
                }
            }
        }

        // Keeps the link warm and, more to the point, keeps it answerable: a
        // link nobody has spoken on cannot be told from a link that no longer
        // exists, and between two turns of a card game that is most of the game.
        scope.launch(Dispatchers.IO) {
            while (isActive && !closed.get()) {
                delay(heartbeatMillis)
                if (closed.get()) break
                val silentFor = (System.nanoTime() - lastHeardNanos) / 1_000_000
                if (silentFor > silenceMillis) {
                    // Closing the stream is what unblocks the read; the reader
                    // then announces the drop from its own thread, exactly as it
                    // would for a peer that had hung up.
                    shutdown()
                    break
                }
                send(Ping)
            }
        }
    }

    override suspend fun send(message: NetMessage) {
        if (closed.get()) return
        withContext(Dispatchers.IO) {
            // One message at a time. The framing is what makes a line a message,
            // and a write is three calls — the text, the newline, the flush — so
            // two of them running at once would splice one message through the
            // middle of another and neither would arrive. The heartbeat is what
            // makes that likely: it writes on its own schedule, without caring
            // what the game happens to be sending.
            writeLock.withLock {
                try {
                    writer.write(protocolJson.encodeToString<NetMessage>(message))
                    writer.newLine()
                    writer.flush()
                } catch (_: Exception) {
                    // Not close(): a write that failed is a link that broke, and
                    // the screen has to hear about that. Going through close()
                    // here used to mark it deliberate, so a player whose link
                    // had died tapped a board that answered nothing and was
                    // never told why.
                    shutdown()
                }
            }
        }
    }

    /**
     * Safe to call from any thread, including the one drawing the screen.
     *
     * Only the raw streams are closed here, and that is deliberate. A reader
     * parked in [BufferedReader.readLine] holds the reader's own lock for the
     * whole of the blocking read, and [BufferedReader.close] wants that same
     * lock — so closing the wrapper from another thread waits for a read that
     * only this close can end. Since the read is waiting on the socket, and the
     * caller is usually the main thread leaving a game, that wait is the app
     * hanging for good.
     *
     * Closing the underlying stream is what actually ends the read. The reader
     * coroutine then unblocks and closes its own wrapper on its own thread. The
     * writer needs no closing: every [send] flushes, so nothing is ever left
     * buffered in it.
     */
    override fun close() {
        closedOnPurpose.set(true)
        shutdown()
    }

    /** Lets go of the streams without saying anything about why. */
    private fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
        onClosed(this)
    }

    private companion object {
        /**
         * Often enough to hold an access point's idea of the association open
         * and to notice a dead link inside a turn, seldom enough that it is a
         * few dozen bytes a minute.
         */
        const val HEARTBEAT_MILLIS = 5_000L

        /**
         * Several missed beats rather than one. A phone with its screen off
         * answers late — the radio is in power save and the timer that would
         * have sent the ping is deferred — and a link is not dead just because
         * it is slow.
         */
        const val SILENCE_MILLIS = 25_000L
    }
}

/**
 * A table that can be handed a replacement for a link it was given.
 *
 * A dropped link is not the end of a game — the position is untouched and the
 * player is still sitting there — so the lobby, which owns the sockets and goes
 * on listening for the whole match, reconnects and passes the new link through
 * this. Narrow on purpose: the lobby has no business knowing what game is being
 * played on the other side of it.
 */
interface LinkRebinder {

    /** Host: [connection] is the seat's link from now on. */
    fun rebindPeer(seat: Int, connection: Connection)

    /** Client: [connection] is the way to the host from now on. */
    fun rebindHostLink(connection: Connection)
}

/**
 * Discovery and connection setup. Everything after the handshake is transport
 * independent, so a lobby accepts players without caring how they arrived.
 */
interface Transport {

    val kind: TransportKind

    /** Whether the radio exists and is usable right now. */
    fun isAvailable(): Boolean

    /** Starts advertising and emits each client that connects. */
    fun host(displayName: String, scope: CoroutineScope): Flow<Connection>

    /** Emits the current list of visible hosts as it changes. */
    fun discover(scope: CoroutineScope): Flow<List<DiscoveredHost>>

    suspend fun join(host: DiscoveredHost, scope: CoroutineScope): Connection

    fun stop()
}
