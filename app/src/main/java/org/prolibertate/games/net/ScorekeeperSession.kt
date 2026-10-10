package org.prolibertate.games.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.prolibertate.games.score.ScoreSheet
import org.prolibertate.games.score.SheetOp
import org.prolibertate.games.score.SheetReplica
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One score sheet shared between several devices, in either of two roles.
 *
 * As HOST this device owns the sheet. Everybody's changes — its own and its
 * guests' — are applied to it here, in the order they arrive, and the result is
 * sent back out. As GUEST it holds what the host last sent, with its own
 * unacknowledged changes laid on top (see [SheetReplica]), and sends changes
 * rather than sheets.
 *
 * A guest is not let in on its own say-so. Its [Hello] lands in [State.requests]
 * and waits there until somebody on the host answers it with [allow] or [deny];
 * nothing the guest sends in the meantime is looked at. A device that has already
 * been let in is let back in without asking again, because a phone that was
 * locked for a minute is not a new person.
 *
 * All the state is changed synchronously, under one lock, and the messages that
 * follow from it are queued and sent in order by one coroutine. The first half
 * is what lets a keystroke show on screen the moment it is typed; the second is
 * what stops a guest being sent a sheet before it has been sent its welcome.
 */
class ScorekeeperSession(
    private val transport: Transport,
    private val endpoint: StateFlow<HostEndpoint?>,
    private val scope: CoroutineScope,
    /** Identifies this device across reconnects, so a returning guest is recognised. */
    private val deviceId: String,
) {

    enum class Role { NONE, HOST, GUEST }

    /** A guest the host has let in. [online] is false while its link is down. */
    data class Guest(val id: String, val name: String, val online: Boolean)

    /** Somebody asking to be let in. */
    data class Request(val id: String, val name: String)

    data class State(
        val role: Role = Role.NONE,
        val hostName: String = "",
        /** The sheet being shared: the host's own, or a guest's view of it. */
        val sheet: ScoreSheet = ScoreSheet(),
        /** Guest: let in, and the link to the host is up. */
        val connected: Boolean = false,
        /** Guest: asked to join and not yet answered. */
        val awaiting: Boolean = false,
        /** Guest: turned away, or removed. Nothing will be retried. */
        val refused: Boolean = false,
        val guests: List<Guest> = emptyList(),
        val requests: List<Request> = emptyList(),
        val searching: Boolean = false,
        val discovered: List<DiscoveredHost> = emptyList(),
        /** Host: where this device is listening, for reading out. */
        val endpoint: HostEndpoint? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private val jobs = CopyOnWriteArrayList<Job>()
    private var discoveryJob: Job? = null

    /** Everything that goes out, in order. See the class comment. */
    private val outbox = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (task in outbox) runCatching { task() }
        }
    }

    private fun enqueue(connection: Connection, message: NetMessage) {
        outbox.trySend { connection.send(message) }
    }

    private fun enqueueClose(connection: Connection) {
        outbox.trySend { connection.close() }
    }

    // -----------------------------------------------------------------------
    // Host
    // -----------------------------------------------------------------------

    /** A guest that has been let in. Its [connection] is null while the link is down. */
    private class HostLink(
        val deviceId: String,
        var name: String,
        var connection: Connection?,
        /** The last change from this guest, on this link, that has been applied. */
        var lastSeq: Int = 0,
    )

    private val admitted = linkedMapOf<String, HostLink>()
    private val waiting = linkedMapOf<String, Pair<String, Connection>>()
    private var hostSheet = ScoreSheet()
    private var revision = 0
    private var hostName = ""

    /** Starts sharing [sheet] with whoever is allowed to join. */
    fun startHosting(name: String, sheet: ScoreSheet) {
        stop()
        synchronized(lock) {
            hostName = name
            hostSheet = sheet
            revision = 0
            _state.value = State(
                role = Role.HOST,
                hostName = name,
                sheet = sheet,
                endpoint = endpoint.value,
                message = "Waiting for others to join…",
            )
        }
        jobs += scope.launch {
            transport.host(name, scope, HostPurpose.SCOREKEEPER).collect { connection ->
                listenToGuest(connection)
            }
        }
        jobs += scope.launch {
            endpoint.collect { where -> _state.update { it.copy(endpoint = where) } }
        }
    }

    private fun listenToGuest(connection: Connection) {
        jobs += scope.launch {
            connection.incoming.collect { message ->
                synchronized(lock) { onHostMessage(connection, message) }
            }
        }
    }

    private fun linkFor(connection: Connection): HostLink? =
        admitted.values.firstOrNull { it.connection === connection }

    private fun onHostMessage(connection: Connection, message: NetMessage) {
        if (_state.value.role != Role.HOST) return
        when (message) {
            is Hello -> onHello(connection, message)

            is SheetEdit -> {
                val link = linkFor(connection) ?: return
                // A change already applied is answered rather than applied twice.
                if (message.seq > link.lastSeq) {
                    link.lastSeq = message.seq
                    val next = message.op.applyTo(hostSheet)
                    if (next != hostSheet) {
                        hostSheet = next
                        revision++
                    }
                }
                broadcast()
            }

            is Resync -> linkFor(connection)?.let { sendSheet(it) }

            is Bye -> {
                val link = linkFor(connection)
                if (link != null) {
                    link.connection = null
                    publishHost("${link.name} lost their connection.")
                } else {
                    val gone = waiting.filterValues { it.second === connection }.keys
                    if (gone.isNotEmpty()) {
                        gone.toList().forEach { waiting.remove(it) }
                        publishHost(null)
                    }
                }
            }

            else -> Unit
        }
    }

    private fun onHello(connection: Connection, hello: Hello) {
        val refusal = when {
            hello.purpose != PURPOSE_SCOREKEEPER -> "That is a score sheet, not a game."
            hello.protocol != PROTOCOL_VERSION -> "Different app version — update both devices."
            else -> null
        }
        if (refusal != null) {
            enqueue(connection, Welcome(hostName, "", accepted = false, reason = refusal))
            enqueueClose(connection)
            return
        }

        val known = admitted[hello.peerId]
        if (known != null) {
            // Somebody already let in, back on a new link — or saying hello a
            // second time on the one it has, which is what a guest does until it
            // hears something. Either way it is put straight back.
            val stale = known.connection
            if (stale != null && stale !== connection) enqueueClose(stale)
            if (known.connection !== connection) known.lastSeq = 0
            known.connection = connection
            known.name = hello.displayName
            enqueue(connection, Welcome(hostName, "", accepted = true))
            sendSheet(known)
            publishHost("${known.name} is back.")
            return
        }

        if (admitted.size >= MAX_GUESTS) {
            enqueue(connection, Welcome(hostName, "", accepted = false, reason = "The sheet is full."))
            enqueueClose(connection)
            return
        }

        val earlier = waiting[hello.peerId]?.second
        if (earlier != null && earlier !== connection) enqueueClose(earlier)
        waiting[hello.peerId] = hello.displayName to connection
        publishHost("${hello.displayName} wants to join.")
    }

    /** Host: lets a waiting guest in. */
    fun allow(requestId: String) = synchronized(lock) {
        val (name, connection) = waiting.remove(requestId) ?: return@synchronized
        val link = HostLink(requestId, name, connection)
        admitted[requestId] = link
        enqueue(connection, Welcome(hostName, "", accepted = true))
        sendSheet(link)
        publishHost("$name joined.")
    }

    /** Host: turns a waiting guest away. */
    fun deny(requestId: String) = synchronized(lock) {
        val (name, connection) = waiting.remove(requestId) ?: return@synchronized
        enqueue(connection, Welcome(hostName, "", accepted = false, reason = "The host declined."))
        enqueueClose(connection)
        publishHost("Turned $name away.")
    }

    /** Host: takes a guest who was let in back out. They are asked again if they return. */
    fun remove(guestId: String) = synchronized(lock) {
        val link = admitted.remove(guestId) ?: return@synchronized
        link.connection?.let {
            enqueue(it, Welcome(hostName, "", accepted = false, reason = "The host removed you."))
            enqueueClose(it)
        }
        publishHost("${link.name} was removed.")
    }

    private fun sendSheet(link: HostLink) {
        link.connection?.let { enqueue(it, SheetState(revision, hostSheet, link.lastSeq)) }
    }

    private fun broadcast() {
        admitted.values.forEach { sendSheet(it) }
        publishHost(_state.value.message)
    }

    private fun publishHost(message: String?) {
        _state.update {
            it.copy(
                sheet = hostSheet,
                guests = admitted.values.map { g -> Guest(g.deviceId, g.name, g.connection != null) },
                requests = waiting.map { (id, entry) -> Request(id, entry.first) },
                message = message,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Guest
    // -----------------------------------------------------------------------

    private val replica = SheetReplica()
    private var link: Connection? = null
    private var linkJob: Job? = null
    private var joined: DiscoveredHost? = null
    private var joinedName = ""
    @Volatile
    private var heardBack = false
    private var refused = false
    private val reconnecting = AtomicBoolean(false)

    /** Starts looking for sheets being shared nearby. */
    fun startDiscovery() {
        stop()
        _state.value = State(searching = true, message = "Looking for shared sheets…")
        discoveryJob = scope.launch {
            transport.discover(scope).collect { found ->
                val sheets = found.filter { it.purpose == HostPurpose.SCOREKEEPER }
                _state.update { it.copy(discovered = sheets) }
            }
        }
    }

    /** Joins a host at an address somebody typed in, as [LobbyController.joinAt] does. */
    fun joinAt(typed: String, name: String) {
        val parsed = parseManualAddress(typed)
        if (parsed == null) {
            _state.update { it.copy(message = "That doesn't look like an address. Try 192.168.43.1") }
            return
        }
        join(
            DiscoveredHost(
                id = "lan:${parsed.address}:${parsed.port}",
                name = parsed.address,
                kind = TransportKind.LAN,
                address = parsed.address,
                port = parsed.port,
                purpose = HostPurpose.SCOREKEEPER,
            ),
            name,
        )
    }

    fun join(host: DiscoveredHost, name: String) {
        discoveryJob?.cancel()
        discoveryJob = null
        synchronized(lock) {
            joined = host
            joinedName = name
            heardBack = false
            refused = false
            replica.reset()
            _state.value = State(
                role = Role.GUEST,
                hostName = host.name,
                awaiting = true,
                message = "Connecting to ${host.name}…",
            )
        }
        jobs += scope.launch {
            val connection = dial(host)
            if (connection == null) {
                // Back to the list, with the reason on it.
                val why = _state.value.message
                startDiscovery()
                _state.update { it.copy(message = why) }
                return@launch
            }
            attach(connection)
        }
    }

    private suspend fun dial(host: DiscoveredHost): Connection? =
        runCatching { transport.join(host, scope) }.getOrElse { error ->
            _state.update { it.copy(message = "Couldn't connect: ${error.message}") }
            null
        }

    /**
     * Starts reading [connection] as the way to the host, and introduces this
     * device on it.
     *
     * The hello is sent from inside the subscription, so it cannot leave before
     * anything is listening for the answer. It is then repeated until the host
     * has said something, because a host that has only just accepted the link
     * may not be listening yet either, and a hello nobody heard is a guest
     * waiting for an answer to a question that was never asked.
     */
    private fun attach(connection: Connection) {
        synchronized(lock) {
            linkJob?.cancel()
            link?.let { old -> if (old !== connection) enqueueClose(old) }
            link = connection
            heardBack = false
            replica.reset()
        }
        val hello = Hello(peerId = deviceId, displayName = joinedName, purpose = PURPOSE_SCOREKEEPER)
        linkJob = scope.launch {
            launch {
                var tries = 0
                while (isActive && !heardBack && connection.isOpen && tries++ < HELLO_TRIES) {
                    delay(HELLO_RETRY_MILLIS)
                    if (!heardBack && connection.isOpen) connection.send(hello)
                }
            }
            connection.incoming
                .onSubscription { connection.send(hello) }
                .collect { message -> synchronized(lock) { onGuestMessage(connection, message) } }
        }
    }

    private fun onGuestMessage(connection: Connection, message: NetMessage) {
        if (connection !== link || _state.value.role != Role.GUEST) return
        when (message) {
            is Welcome -> {
                heardBack = true
                if (message.accepted) {
                    _state.update {
                        it.copy(
                            hostName = message.hostName.ifBlank { it.hostName },
                            awaiting = false,
                            connected = true,
                            message = "Joined ${message.hostName.ifBlank { it.hostName }}.",
                        )
                    }
                } else {
                    refused = true
                    enqueueClose(connection)
                    _state.update {
                        it.copy(
                            awaiting = false,
                            connected = false,
                            refused = true,
                            message = message.reason ?: "The host declined.",
                        )
                    }
                }
            }

            is SheetState -> {
                if (refused) return
                heardBack = true
                replica.receive(message.sheet, message.revision, message.acknowledged)
                _state.update { it.copy(sheet = replica.view) }
            }

            is Bye -> {
                // A refusal closes the link too, and the Bye that follows it
                // must not turn into an attempt to get back in.
                if (refused) return
                _state.update {
                    it.copy(connected = false, message = "Lost the connection to the host.")
                }
                reconnectLater()
            }

            else -> Unit
        }
    }

    /**
     * Guest: asks the host for the sheet as it stands, or gets back to the host
     * if the link has gone.
     *
     * Always safe, and cheap when nothing is wrong — a live link is sent one
     * [Resync] and answered with the sheet it already has.
     */
    fun sync() {
        val live = synchronized(lock) {
            if (_state.value.role != Role.GUEST || refused) return
            link
        }
        if (live != null && live.isOpen) {
            enqueue(live, Resync)
        } else {
            reconnectLater()
        }
    }

    private fun reconnectLater() {
        jobs += scope.launch { reconnect() }
    }

    private suspend fun reconnect() {
        val host = synchronized(lock) { if (refused) null else joined } ?: return
        if (!reconnecting.compareAndSet(false, true)) return
        try {
            repeat(RECONNECT_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(RECONNECT_BACKOFF_MILLIS * attempt)
                if (synchronized(lock) { refused || _state.value.role != Role.GUEST }) return
                _state.update { it.copy(message = "Reconnecting to ${host.name}…") }
                val connection = dial(host)
                if (connection != null) {
                    attach(connection)
                    return
                }
            }
            _state.update { it.copy(message = "Couldn't get back to ${host.name}.") }
        } finally {
            reconnecting.set(false)
        }
    }

    // -----------------------------------------------------------------------
    // Either role
    // -----------------------------------------------------------------------

    /**
     * Makes a change to the sheet being shared.
     *
     * Applied here before it returns, so the screen draws it at once. As host it
     * is then sent to everyone; as guest it is sent to the host, which may
     * disagree — the sheet it sends back is the answer.
     */
    fun edit(op: SheetOp) {
        synchronized(lock) {
            when (_state.value.role) {
                Role.HOST -> {
                    val next = op.applyTo(hostSheet)
                    if (next != hostSheet) {
                        hostSheet = next
                        revision++
                        broadcast()
                    }
                }

                Role.GUEST -> {
                    val live = link
                    if (!_state.value.connected || live == null || !live.isOpen) {
                        _state.update {
                            it.copy(message = "Not connected to the host. Tap Sync to reconnect.")
                        }
                        return
                    }
                    val seq = replica.local(op)
                    _state.update { it.copy(sheet = replica.view) }
                    enqueue(live, SheetEdit(seq, op))
                }

                Role.NONE -> Unit
            }
        }
    }

    /** Stops hosting, joining or looking, and lets go of every link. */
    fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        linkJob?.cancel()
        linkJob = null
        val open = synchronized(lock) {
            val all = admitted.values.mapNotNull { it.connection } +
                waiting.values.map { it.second } + listOfNotNull(link)
            admitted.clear()
            waiting.clear()
            link = null
            joined = null
            refused = false
            heardBack = false
            replica.reset()
            hostSheet = ScoreSheet()
            revision = 0
            all
        }
        open.forEach { runCatching { it.close() } }
        runCatching { transport.stop() }
        _state.value = State()
    }

    private companion object {
        /** A table of friends, not a room. */
        const val MAX_GUESTS = 8
        const val HELLO_RETRY_MILLIS = 2_000L
        const val HELLO_TRIES = 15
        const val RECONNECT_ATTEMPTS = 4
        const val RECONNECT_BACKOFF_MILLIS = 750L
    }
}
