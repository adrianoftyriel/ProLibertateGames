package org.prolibertate.games.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    BLUETOOTH("Bluetooth"),
}

/**
 * A host someone could join, found by one of the transports. [address] is
 * transport-specific: an IP for LAN, a MAC for Bluetooth.
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
 * Both transports end up with a plain pair of streams, so the framing and
 * serialisation live here once rather than twice.
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
            try {
                while (isActive && !closed.get()) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val message = runCatching {
                        protocolJson.decodeFromString<NetMessage>(line)
                    }.getOrNull() ?: continue // ignore anything we can't parse
                    _incoming.emit(message)
                }
            } catch (_: Exception) {
                // A dropped link is normal: someone walked out of range or
                // closed the app. Fall through to close().
            } finally {
                close()
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { reader.close() }
        runCatching { writer.close() }
        runCatching { input.close() }
        runCatching { output.close() }
        onClosed(this)
    }
}

/**
 * Discovery and connection setup. Everything after the handshake is the same
 * for both transports, which is what lets a lobby accept players over Wi-Fi and
 * Bluetooth at the same time.
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
