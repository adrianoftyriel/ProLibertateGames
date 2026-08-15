package org.prolibertate.games.net

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig

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
        /** Where this device is listening while hosting, for reading out. */
        val endpoint: HostEndpoint? = null,
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
    private val jobs = mutableListOf<Job>()

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

        // Discovery is not guaranteed — a phone sharing its own hotspot cannot
        // be relied on to advertise over the tethering interface — so where the
        // host is listening is put on its screen to be read out.
        jobs += scope.launch {
            lan.endpoint.collect { endpoint ->
                _state.value = _state.value.copy(endpoint = endpoint)
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

    /**
     * Joins a host at an address somebody typed in.
     *
     * The way in when discovery cannot work. Two phones on one phone's hotspot
     * is exactly that case: mDNS over a tethering interface is unreliable in
     * both directions, and no amount of listening harder fixes it, but the
     * address is right there on the host's screen and a TCP connection to it
     * works perfectly well once the socket is pointed at the right network.
     */
    fun joinAt(typed: String, displayName: String, peerId: String) {
        val parsed = parseManualAddress(typed)
        if (parsed == null) {
            _state.value = _state.value.copy(
                message = "That doesn't look like an address. Try 192.168.43.1",
            )
            return
        }
        join(
            host = DiscoveredHost(
                id = "lan:${parsed.address}:${parsed.port}",
                name = parsed.address,
                kind = TransportKind.LAN,
                address = parsed.address,
                port = parsed.port,
            ),
            displayName = displayName,
            peerId = peerId,
        )
    }

    fun join(host: DiscoveredHost, displayName: String, peerId: String) {
        jobs += scope.launch {
            _state.value = _state.value.copy(message = "Connecting to ${host.name}…")
            val transport = transports.firstOrNull { it.kind == host.kind } ?: return@launch
            val connection = runCatching { transport.join(host, scope) }.getOrElse { error ->
                _state.value = _state.value.copy(message = "Couldn't connect: ${error.message}")
                return@launch
            }
            clientLink = connection

            launch {
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

                        is Bye -> _state.value =
                            _state.value.copy(connected = false, message = "The host left.")

                        else -> Unit
                    }
                }
            }

            connection.send(Hello(peerId = peerId, displayName = displayName))
        }
    }

    fun clearStarted() {
        _state.value = _state.value.copy(started = null)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        runCatching { clientLink?.close() }
        clientLink = null
        transports.forEach { runCatching { it.stop() } }
        _state.value = State()
    }
}
