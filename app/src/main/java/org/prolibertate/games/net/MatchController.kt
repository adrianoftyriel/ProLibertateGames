package org.prolibertate.games.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.prolibertate.games.game.engine.GameAi
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.TableConfig

/**
 * Drives one match, in either of two roles.
 *
 * As HOST it owns the authoritative state: it validates every move (local, AI
 * or remote), advances the game, and pushes each participant a view redacted
 * for their seat. As CLIENT it holds only what the host has sent and turns
 * local input into [MoveIntent]s.
 *
 * A purely offline game against AI is just a host with no connections, so
 * there is only one code path to get right.
 */
class MatchController<S : Any, M : Any>(
    private val rules: GameRules<S, M>,
    private val ai: GameAi<S, M>,
    private val config: TableConfig,
    private val scope: CoroutineScope,
    val role: Role,
    /** Seats played by a person on this device. */
    private val localSeats: Set<Int>,
    /** The local player's primary seat, used to pick the view to render. */
    val primarySeat: Int,
    /**
     * Advances a state that nobody is waiting on — Euchre's gap between a
     * scored hand and the next deal. Returning null means the game is over.
     */
    private val advanceIdle: (S) -> S? = { null },
    /** Scaled by the animation-speed setting so the table paces itself. */
    private val aiThinkingMillis: () -> Long = { 700L },
    /**
     * Extra pause before the next move is made, given the current state. Lets a
     * game hold a finished trick on the table long enough to be read before the
     * next card lands on top of it.
     */
    private val holdBeforeNextMove: (S) -> Long = { 0L },
) {

    enum class Role { HOST, CLIENT }

    private val _state = MutableStateFlow<S?>(null)

    /** What the local player should see: redacted on the host, as-sent on a client. */
    val state: StateFlow<S?> = _state.asStateFlow()

    private val _legalMoves = MutableStateFlow<List<M>>(emptyList())
    val legalMoves: StateFlow<List<M>> = _legalMoves.asStateFlow()

    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Host only: the true state. Clients never see this. */
    private var authoritative: S? = null
    private val lock = Mutex()
    private var sequence = 0

    private val peers = mutableMapOf<String, Connection>()
    private var hostLink: Connection? = null

    private val seatKinds: Map<Int, PlayerKind> =
        config.seats.associate { it.seat to it.kind }
    private val seatPeers: Map<Int, String?> =
        config.seats.associate { it.seat to it.peerId }

    // -----------------------------------------------------------------------
    // Start-up
    // -----------------------------------------------------------------------

    /** Host: begin the match and start pushing state to [clients]. */
    fun startAsHost(clients: List<Connection>) {
        check(role == Role.HOST) { "Not hosting" }
        clients.forEach { connection ->
            peers[connection.peerId] = connection
            scope.launch {
                connection.incoming.collect { message -> onHostMessage(connection, message) }
            }
        }
        scope.launch {
            lock.withLock {
                authoritative = rules.initialState(config)
                publish()
            }
            driveIdleSeats()
        }
    }

    /** Client: render whatever arrives from [link]. */
    fun startAsClient(link: Connection) {
        check(role == Role.CLIENT) { "Not a client" }
        hostLink = link
        scope.launch {
            link.incoming.collect { message ->
                when (message) {
                    is StateSync -> {
                        _state.value = rules.decodeState(message.stateJson)
                        _legalMoves.value = message.legalMoves.map { rules.decodeMove(it) }
                        _finished.value = _state.value?.let { rules.isFinished(it) } ?: false
                    }

                    is Rejected -> _notice.value = message.reason
                    is Bye -> _notice.value = "The host left the game."
                    else -> Unit
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    /** Called by the UI when the local player commits to a move. */
    fun submit(move: M) {
        scope.launch {
            when (role) {
                Role.HOST -> applyIfLegal(primarySeatOnClock(), move)
                Role.CLIENT -> hostLink?.send(MoveIntent(rules.encodeMove(move)))
            }
        }
    }

    private fun primarySeatOnClock(): Int {
        val current = authoritative?.let { rules.currentSeat(it) }
        // With several local seats (pass-and-play or AI fill), the seat on the
        // clock is the one submitting, provided it is actually ours.
        return if (current != null && current in localSeats) current else primarySeat
    }

    private suspend fun onHostMessage(connection: Connection, message: NetMessage) {
        if (message !is MoveIntent) return
        val seat = seatPeers.entries.firstOrNull { it.value == connection.peerId }?.key
        if (seat == null) {
            connection.send(Rejected("You are not seated at this table."))
            return
        }
        val move = runCatching { rules.decodeMove(message.moveJson) }.getOrNull()
        if (move == null) {
            connection.send(Rejected("Unreadable move."))
            return
        }
        applyIfLegal(seat, move)
    }

    /**
     * The single choke point where a move becomes real. Everything — local
     * taps, AI picks and anything arriving over the wire — lands here and is
     * re-checked against the rules.
     */
    private suspend fun applyIfLegal(seat: Int, move: M) {
        lock.withLock {
            val current = authoritative ?: return
            if (rules.currentSeat(current) != seat) {
                seatPeers[seat]?.let { peers[it]?.send(Rejected("It is not your turn.")) }
                return
            }
            if (move !in rules.legalMoves(current, seat)) {
                seatPeers[seat]?.let { peers[it]?.send(Rejected("That move is not legal.")) }
                return
            }
            authoritative = rules.applyMove(current, seat, move)
            publish()
        }
        driveIdleSeats()
    }

    // -----------------------------------------------------------------------
    // Host loop
    // -----------------------------------------------------------------------

    /**
     * Plays out every seat the humans are not responsible for, then advances
     * through any state nobody is waiting on, until a person is on the clock.
     */
    private suspend fun driveIdleSeats() {
        while (true) {
            val current = lock.withLock { authoritative } ?: return
            if (rules.isFinished(current)) {
                _finished.value = true
                return
            }

            val seat = rules.currentSeat(current)
            if (seat == null) {
                // Nobody on the clock: a hand has been scored, or the game ended.
                delay(aiThinkingMillis() * 2)
                val advanced = advanceIdle(current)
                if (advanced == null) {
                    _finished.value = true
                    return
                }
                lock.withLock {
                    authoritative = advanced
                    publish()
                }
                continue
            }

            if (seatKinds[seat] != PlayerKind.AI) return // a person's turn

            delay(holdBeforeNextMove(current) + aiThinkingMillis())
            val chosen = lock.withLock {
                val live = authoritative ?: return
                if (rules.currentSeat(live) != seat) return@withLock null
                val legal = rules.legalMoves(live, seat)
                if (legal.isEmpty()) return@withLock null
                val move = ai.chooseMove(live, seat, legal)
                authoritative = rules.applyMove(live, seat, move)
                publish()
                move
            }
            if (chosen == null) return
        }
    }

    /** Pushes the local view and every remote seat's view. Call under [lock]. */
    private suspend fun publish() {
        val current = authoritative ?: return
        sequence++

        _state.value = rules.viewFor(current, primarySeat)
        _legalMoves.value = if (rules.currentSeat(current) in localSeats) {
            rules.legalMoves(current, rules.currentSeat(current)!!)
        } else {
            emptyList()
        }
        _finished.value = rules.isFinished(current)

        for (slot in config.seats) {
            val peerId = slot.peerId ?: continue
            val connection = peers[peerId] ?: continue
            val seatView = rules.viewFor(current, slot.seat)
            val moves = if (rules.currentSeat(current) == slot.seat) {
                rules.legalMoves(current, slot.seat).map { rules.encodeMove(it) }
            } else {
                emptyList()
            }
            connection.send(
                StateSync(
                    seq = sequence,
                    yourSeat = slot.seat,
                    stateJson = rules.encodeState(seatView),
                    legalMoves = moves,
                )
            )
        }
    }

    fun close() {
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        runCatching { hostLink?.close() }
        hostLink = null
    }
}
