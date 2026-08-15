package org.prolibertate.games.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * Where a host is listening, for reading out loud.
 *
 * Discovery is not always possible — a phone sharing its own hotspot does not
 * reliably advertise over the tethering interface, whatever the other end does
 * — so a host has to be able to say where it is in a form somebody can type
 * into the other phone.
 */
data class HostEndpoint(val addresses: List<String>, val port: Int) {
    /** The one to read out, tethering address first. */
    val primary: String? get() = addresses.firstOrNull()?.let { "$it:$port" }
}

/**
 * The port a host listens on when it can have it.
 *
 * Fixed rather than assigned by the system, so that joining by hand is typing
 * an address and not an address and a five-digit number. A host that finds it
 * taken falls back to whatever it is given and says so.
 */
const val DEFAULT_HOST_PORT = 47_654

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
) : Connection {

    private val reader: BufferedReader = input.bufferedReader()
    private val writer: BufferedWriter = output.bufferedWriter()
    private val closed = AtomicBoolean(false)

    private val _incoming = MutableSharedFlow<NetMessage>(replay = 0, extraBufferCapacity = 64)
    override val incoming: SharedFlow<NetMessage> = _incoming

    override val isOpen: Boolean get() = !closed.get()

    init {
        scope.launch(Dispatchers.IO) {
            // Told apart from a link this side closed on purpose, because only
            // one of the two is worth telling anybody about.
            var droppedByPeer = false
            try {
                while (isActive && !closed.get()) {
                    val line = reader.readLine()
                    if (line == null) {
                        droppedByPeer = true
                        break
                    }
                    if (line.isBlank()) continue
                    val message = runCatching {
                        protocolJson.decodeFromString<NetMessage>(line)
                    }.getOrNull() ?: continue // ignore anything we can't parse
                    _incoming.emit(message)
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
                val weClosedIt = closed.get()
                close()
                // A link that died on its own reaches the screens on the same
                // channel as everything else, so leaving a game is something the
                // other end finds out about the same way it finds out about a
                // move. Withdrawing deliberately needs no announcement, and the
                // emit is not cancellable — the whole point of it is to arrive
                // while everything around it is being torn down.
                if (droppedByPeer && !weClosedIt) {
                    withContext(NonCancellable) { runCatching { _incoming.emit(Bye) } }
                }
            }
        }
    }

    override suspend fun send(message: NetMessage) {
        if (closed.get()) return
        withContext(Dispatchers.IO) {
            try {
                writer.write(protocolJson.encodeToString<NetMessage>(message))
                writer.newLine()
                writer.flush()
            } catch (_: Exception) {
                close()
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
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
        onClosed(this)
    }
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
