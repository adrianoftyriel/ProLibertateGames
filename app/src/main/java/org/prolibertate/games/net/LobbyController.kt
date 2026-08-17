package org.prolibertate.games.net

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The pre-game lobby.
 *
 * The host advertises on every available transport at once — from the seat
 * list's point of view a peer is a peer, whichever one it arrived on. Seats
 * start filled with AI and are handed over to people as they join, which is
 * what makes "AI, humans, or any combination" fall out for free: whatever is
 * still AI when the host starts, stays AI.
 */
class LobbyController(
    context: Context,
    private val scope: CoroutineScope,
) {

    data class State(
        val hosting: Boolean = false,
        val joining: Boolean = false,
        val connected: Boolean = false,
        val gameId: String? = null,
        val optionsJson: String = "{}",
        val hostName: String = "",
        val seats: List<PlayerSlot> = emptyList(),
        val discovered: List<DiscoveredHost> = emptyList(),
        val message: String? = null,
        val started: TableConfig? = null,
    )

    private val lan = LanTransport(context)

    val transports: List<Transport> = listOf(lan)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Peer id -> live connection, for every seated remote player. */
    private val connections = linkedMapOf<String, Connection>()
    private var clientLink: Connection? = null
    private var clientJob: Job? = null
    private val jobs = mutableListOf<Job>()

    /**
     * Where this device joined, so it can find its way back.
     *
     * The host goes on listening on the same port for as long as it is hosting,
     * which is the whole match, so a guest that has lost its link can dial the
     * address it already knows rather than go round discovery again.
     */
    private var joinedHost: DiscoveredHost? = null
    private var joinedName: String = ""
    private var joinedPeerId: String = ""
    private val reconnecting = AtomicBoolean(false)

    /**
     * The table currently being played, if any, so a replaced link can be given
     * to it. Set by the match screen for as long as it is on screen.
     */
    var rebinder: LinkRebinder? = null

    val hostConnections: List<Connection> get() = connections.values.toList()
    val clientConnection: Connection? get() = clientLink

    fun availableTransports(): List<Transport> = transports.filter { it.isAvailable() }

    // -----------------------------------------------------------------------
    // Hosting
    // -----------------------------------------------------------------------

    fun startHosting(
        gameId: String,
        optionsJson: String,
        hostName: String,
        seatCount: Int,
        teamOf: (Int) -> Int,
    ) {
        stop()
        val seats = (0 until seatCount).map { seat ->
            PlayerSlot(
                seat = seat,
                name = if (seat == 0) hostName else "Computer $seat",
                kind = if (seat == 0) PlayerKind.HUMAN_LOCAL else PlayerKind.AI,
                team = teamOf(seat),
            )
        }
        _state.value = State(
            hosting = true,
            gameId = gameId,
            optionsJson = optionsJson,
            hostName = hostName,
            seats = seats,
            message = "Waiting for players…",
        )

        for (transport in availableTransports()) {
            jobs += scope.launch {
                transport.host(hostName, scope).collect { connection ->
                    onClientConnected(connection)
                }
            }
        }
    }

    private fun onClientConnected(connection: Connection) {
        jobs += scope.launch {
            connection.incoming.collect { message ->
                if (message !is Hello) return@collect
                if (message.protocol != PROTOCOL_VERSION) {
                    connection.send(
                        Welcome(
                            hostName = _state.value.hostName,
                            gameId = _state.value.gameId.orEmpty(),
                            accepted = false,
                            reason = "Different app version — update both devices.",
                        )
                    )
                    connection.close()
                    return@collect
                }
                seatPlayer(connection, message)
            }
        }
    }

    /** Gives an arriving player the first seat still held by the computer. */
    private suspend fun seatPlayer(connection: Connection, hello: Hello) {
        val current = _state.value
        val seats = current.seats.toMutableList()

        // A device the table already knows is not somebody new asking for a
        // seat: it is a player whose link went away — a phone locked long
        // enough for the radio to drop it is the usual way — coming back to the
        // one it already has. Seating it again would find no free seat and turn
        // it away from its own game, so it is put back where it was.
        val returning = seats.indexOfFirst { it.deviceId == hello.peerId }
        if (returning >= 0) {
            val stale = seats[returning].peerId?.let { connections.remove(it) }
            seats[returning] = seats[returning].copy(
                name = hello.displayName,
                kind = PlayerKind.HUMAN_REMOTE,
                peerId = connection.peerId,
            )
            connections[connection.peerId] = connection
            if (stale != null && stale !== connection) runCatching { stale.close() }
            _state.value = current.copy(
                seats = seats,
                message = "${hello.displayName} is back.",
            )
            connection.send(Welcome(current.hostName, current.gameId.orEmpty(), accepted = true))
            broadcastLobby()
            // A match already under way keeps the seat and the position it is
            // sitting in. Only the link is new.
            rebinder?.rebindPeer(seats[returning].seat, connection)
            return
        }

        val index = seats.indexOfFirst { it.kind == PlayerKind.AI }
        if (index < 0) {
            connection.send(
                Welcome(current.hostName, current.gameId.orEmpty(), accepted = false, reason = "The table is full.")
            )
            connection.close()
            return
        }

        seats[index] = seats[index].copy(
            name = hello.displayName,
            kind = PlayerKind.HUMAN_REMOTE,
            peerId = connection.peerId,
            // Recorded so the guest can find itself in this list once it is sent
            // back: connection.peerId is an address this end invented and the
            // guest has never seen.
            deviceId = hello.peerId,
        )
        connections[connection.peerId] = connection
        _state.value = current.copy(
            seats = seats,
            message = "${hello.displayName} joined over ${connection.kind.label}.",
        )

        connection.send(Welcome(current.hostName, current.gameId.orEmpty(), accepted = true))
        broadcastLobby()
    }

    private suspend fun broadcastLobby() {
        val current = _state.value
        val update = LobbyUpdate(
            gameId = current.gameId.orEmpty(),
            seats = current.seats,
            optionsJson = current.optionsJson,
            hostName = current.hostName,
        )
        connections.values.forEach { it.send(update) }
    }

    /** Host: lock the table in and tell everyone to build the same match. */
    fun startMatch(seed: Long): TableConfig {
        val current = _state.value
        val config = TableConfig(
            gameId = current.gameId.orEmpty(),
            seats = current.seats,
            optionsJson = current.optionsJson,
            seed = seed,
        )
        scope.launch { connections.values.forEach { it.send(StartGame(config)) } }
        _state.value = current.copy(started = config)
        return config
    }

    /** Replaces a seat's occupant — used by the host to swap a person for AI. */
    fun setSeatKind(seat: Int, kind: PlayerKind, name: String? = null) {
        val current = _state.value
        val seats = current.seats.toMutableList()
        if (seat !in seats.indices) return
        seats[seat] = seats[seat].copy(
            kind = kind,
            name = name ?: seats[seat].name,
            peerId = if (kind == PlayerKind.AI) null else seats[seat].peerId,
            // A dropped player must stop claiming the seat, or it would still
            // recognise itself in it when the table starts.
            deviceId = if (kind == PlayerKind.AI) null else seats[seat].deviceId,
        )
        _state.value = current.copy(seats = seats)
        scope.launch { broadcastLobby() }
    }

    // -----------------------------------------------------------------------
    // Joining
    // -----------------------------------------------------------------------

    fun startDiscovery() {
        stop()
        _state.value = State(joining = true, message = "Looking for games…")
        for (transport in availableTransports()) {
            jobs += scope.launch {
                transport.discover(scope).collect { hosts ->
                    val others = _state.value.discovered.filter { it.kind != transport.kind }
                    _state.value = _state.value.copy(discovered = others + hosts)
                }
            }
        }
    }

    fun join(host: DiscoveredHost, displayName: String, peerId: String) {
        jobs += scope.launch {
            _state.value = _state.value.copy(message = "Connecting to ${host.name}…")
            joinedHost = host
            joinedName = displayName
            joinedPeerId = peerId
            val connection = dial(host) ?: return@launch
            attachClientLink(connection)
        }
    }

    private suspend fun dial(host: DiscoveredHost): Connection? {
        val transport = transports.firstOrNull { it.kind == host.kind } ?: return null
        return runCatching { transport.join(host, scope) }.getOrElse { error ->
            _state.value = _state.value.copy(message = "Couldn't connect: ${error.message}")
            null
        }
    }

    /**
     * Starts reading [connection] as this device's way to the host, and
     * introduces itself on it.
     *
     * Any previous reader is stopped first: collecting a connection's messages
     * never ends on its own, so a link that has been replaced would otherwise
     * leave its collector behind writing into the same lobby state.
     */
    private suspend fun attachClientLink(connection: Connection) {
        clientJob?.cancel()
        clientLink = connection
        clientJob = scope.launch {
            connection.incoming.collect { message ->
                when (message) {
                    is Welcome -> _state.value = if (message.accepted) {
                        _state.value.copy(
                            connected = true,
                            gameId = message.gameId,
                            hostName = message.hostName,
                            message = "Joined ${message.hostName}. Waiting for the host to start…",
                        )
                    } else {
                        _state.value.copy(message = message.reason ?: "The host declined.")
                    }

                    is LobbyUpdate -> _state.value = _state.value.copy(
                        seats = message.seats,
                        gameId = message.gameId,
                        optionsJson = message.optionsJson,
                        hostName = message.hostName,
                    )

                    is StartGame -> _state.value = _state.value.copy(started = message.config)

                    is Bye -> {
                        _state.value =
                            _state.value.copy(connected = false, message = "The host left.")
                        // A link that has gone is worth replacing while there is
                        // still a table to come back to. In its own job, because
                        // taking the new link into use stops this one.
                        if (rebinder != null) jobs += scope.launch { reconnect() }
                    }

                    else -> Unit
                }
            }
        }

        connection.send(Hello(peerId = joinedPeerId, displayName = joinedName))
    }

    /**
     * Client: makes sure there is still a way to the host, and asks for the
     * table again.
     *
     * Called when the app comes back to the front. A phone that was locked when
     * its turn came round has missed whatever the host pushed in the meantime —
     * a client draws nothing but what it was sent — and may have lost the link
     * entirely while the radio was down, in which case there is no point asking
     * politely and it dials again instead.
     *
     * Costs nothing when nothing is wrong: a live link is sent one [Resync],
     * and the answer is the state it already has.
     */
    fun restoreLink() {
        if (joinedHost == null) return
        jobs += scope.launch {
            val live = clientLink
            if (live != null && live.isOpen) live.send(Resync) else reconnect()
        }
    }

    /**
     * Dials the host again and hands the new link to whatever is being played.
     *
     * One attempt at a time: the link can be found to be gone twice over — the
     * reader announcing it and the screen coming back both arrive at the same
     * conclusion — and two dials would leave the host holding two links for one
     * seat, having closed the one actually in use.
     */
    private suspend fun reconnect() {
        val host = joinedHost ?: return
        if (!reconnecting.compareAndSet(false, true)) return
        try {
            repeat(RECONNECT_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(RECONNECT_BACKOFF_MILLIS * attempt)
                if (clientLink?.isOpen == true) return
                val connection = dial(host)
                if (connection != null) {
                    // Hello first and rebind second, in that order: the host has
                    // to have put this device back in its seat before it can
                    // answer the table's resync with anything.
                    attachClientLink(connection)
                    rebinder?.rebindHostLink(connection)
                    return
                }
            }
            _state.value = _state.value.copy(message = "Couldn't get back to ${host.name}.")
        } finally {
            reconnecting.set(false)
        }
    }

    fun clearStarted() {
        _state.value = _state.value.copy(started = null)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        clientJob?.cancel()
        clientJob = null
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        runCatching { clientLink?.close() }
        clientLink = null
        // Nothing left to get back to, and nothing left to hand a link to.
        joinedHost = null
        joinedName = ""
        joinedPeerId = ""
        rebinder = null
        transports.forEach { runCatching { it.stop() } }
        _state.value = State()
    }

    private companion object {
        /**
         * Enough to ride out a radio that has not finished waking up, and few
         * enough that a host which is genuinely gone is reported as gone rather
         * than dialled at forever.
         */
        const val RECONNECT_ATTEMPTS = 4
        const val RECONNECT_BACKOFF_MILLIS = 750L
    }
}
