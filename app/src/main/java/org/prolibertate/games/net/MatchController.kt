package org.prolibertate.games.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
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
    /**
     * States that should not advance on their own — an end-of-round scoreboard
     * the players need to read before the next deal. The controller stops and
     * waits for [confirmAdvance].
     */
    private val awaitsConfirmation: (S) -> Boolean = { false },
) : LinkRebinder {

    enum class Role { HOST, CLIENT }

    private val _state = MutableStateFlow<S?>(null)

    /** What the local player should see: redacted on the host, as-sent on a client. */
    val state: StateFlow<S?> = _state.asStateFlow()

    private val _legalMoves = MutableStateFlow<List<M>>(emptyList())
    val legalMoves: StateFlow<List<M>> = _legalMoves.asStateFlow()

    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** A passing remark about the last thing that happened, such as a refused move. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _abandoned = MutableStateFlow<String?>(null)

    /**
     * Why this table can no longer be played, or null while it still can.
     *
     * Kept apart from [notice] because the two want opposite treatment: a
     * refused move is worth a word in passing, whereas a game with nobody left
     * to play it has to be brought to the player's attention along with the way
     * out of it.
     */
    val abandoned: StateFlow<String?> = _abandoned.asStateFlow()

    private val _awaitingConfirmation = MutableStateFlow(false)

    /** True while a scoreboard is up and the table is waiting to be released. */
    val awaitingConfirmation: StateFlow<Boolean> = _awaitingConfirmation.asStateFlow()

    /** Host only: the true state. Clients never see this. */
    private var authoritative: S? = null
    private val lock = Mutex()
    private var sequence = 0

    /**
     * A scoreboard a player has read and released, waiting to be let past.
     *
     * The hold belongs to a particular scoreboard rather than to the phase, and
     * releasing it has to be spent: [awaitsConfirmation] is asked about the
     * state, which has not changed while the players were reading it, so
     * without something consumable here the same state would stop the table
     * again the instant [confirmAdvance] restarted the loop — and the next hand
     * would never be dealt.
     */
    private var released = false

    private val peers = mutableMapOf<String, Connection>()

    /**
     * The reader attached to each peer's link.
     *
     * Held so a replaced link's collector can be stopped. Collecting a
     * [Connection.incoming] never ends on its own — a SharedFlow has no
     * completion — so a link swapped out mid-match would otherwise leave its
     * collector behind for the rest of the game.
     */
    private val peerJobs = mutableMapOf<String, Job>()
    private var hostLink: Connection? = null
    private var hostLinkJob: Job? = null

    private val seatKinds: Map<Int, PlayerKind> =
        config.seats.associate { it.seat to it.kind }

    /**
     * Which link each seat is reached on. Mutable because a player who drops
     * and comes back is the same player in the same seat, but not necessarily
     * on the same address — a reconnecting phone can be handed a different one.
     */
    private val seatPeers: MutableMap<Int, String?> =
        config.seats.associate { it.seat to it.peerId }.toMutableMap()

    private fun nameOfPeer(peerId: String): String =
        config.seats.firstOrNull { it.peerId == peerId }?.name ?: "the other player"

    /**
     * Records that this table cannot be played any further.
     *
     * Set on both channels on purpose: screens that show [notice] as a line of
     * text carry on doing so, and one that wants to stop the player and offer
     * the way out reads [abandoned] instead.
     */
    private fun abandon(reason: String) {
        _notice.value = reason
        _abandoned.value = reason
    }

    // -----------------------------------------------------------------------
    // Start-up
    // -----------------------------------------------------------------------

    /** Host: begin the match and start pushing state to [clients]. */
    fun startAsHost(clients: List<Connection>) {
        check(role == Role.HOST) { "Not hosting" }
        clients.forEach { connection -> listenToPeer(connection) }
        scope.launch {
            val initial = runCatching { rules.initialState(config) }.getOrElse { error ->
                // A table the rules will not accept is a dead end, not a reason
                // to take the app down with it: this runs in the screen's own
                // scope, where nothing would catch it.
                abandon("This table could not be set up: ${error.message}")
                return@launch
            }
            lock.withLock {
                authoritative = initial
                publish()
            }
            driveIdleSeats()
        }
    }

    private fun listenToPeer(connection: Connection) {
        peers[connection.peerId] = connection
        peerJobs[connection.peerId] = scope.launch {
            connection.incoming.collect { message -> onHostMessage(connection, message) }
        }
    }

    /** Client: render whatever arrives from [link]. */
    fun startAsClient(link: Connection) {
        check(role == Role.CLIENT) { "Not a client" }
        hostLink = link
        hostLinkJob = scope.launch {
            link.incoming
                // Asked for from inside onSubscription, which is what makes it
                // reliable: the request cannot leave before this collector is
                // attached, so the reply cannot arrive before there is anything
                // here to receive it.
                .onSubscription { link.send(Resync) }
                .collect { message ->
                    when (message) {
                        is StateSync -> {
                            // Anything off the wire is decoded defensively. This
                            // collector runs in the screen's own scope, so
                            // throwing here would take the app down rather than
                            // the message.
                            val decoded = runCatching {
                                val state = rules.decodeState(message.stateJson)
                                state to message.legalMoves.map { rules.decodeMove(it) }
                            }.getOrNull()
                            if (decoded == null) {
                                _notice.value =
                                    "The host sent something this version cannot read."
                                return@collect
                            }
                            _state.value = decoded.first
                            _legalMoves.value = decoded.second
                            _finished.value = rules.isFinished(decoded.first)
                        }

                        is Rejected -> _notice.value = message.reason
                        is Bye -> {
                            // The link to the host has gone, either because the
                            // host left or because the link died under it. The
                            // two are indistinguishable from this end, so this
                            // says only what is actually known — and it stops
                            // the table, which gives the screen a way out to
                            // offer if getting back in does not work.
                            _legalMoves.value = emptyList()
                            abandon("The connection to the host was lost.")
                        }

                        else -> Unit
                    }
                }
        }
    }

    // -----------------------------------------------------------------------
    // Getting back in
    // -----------------------------------------------------------------------

    /**
     * Host: a player who dropped is back, on [connection].
     *
     * The seat, and the position it is sitting in, were never given up — only
     * the link was — so nothing here touches the game. The old link is let go
     * of, the new one is read from, and the seat is sent the table as it now
     * stands so the returning player has something to play on.
     */
    override fun rebindPeer(seat: Int, connection: Connection) {
        if (role != Role.HOST) return
        val previous = seatPeers[seat]
        if (previous != null) {
            peerJobs.remove(previous)?.cancel()
            // Not closed: the lobby owns these sockets and has already dealt
            // with the one being replaced. Closing a link this end has stopped
            // reading would only race the reader that is on its way out.
            peers.remove(previous)
        }
        seatPeers[seat] = connection.peerId
        listenToPeer(connection)
        // Only once everybody is reachable again. At a table of four, one player
        // coming back says nothing about the other one who has not.
        if (seatPeers.values.filterNotNull().all { peers[it]?.isOpen == true }) {
            clearAbandoned()
        }
        scope.launch { sendStateTo(connection) }
    }

    /**
     * Client: the way back to the host is [connection].
     *
     * The collector asks for a resync as it attaches, which is what fills the
     * screen back in: everything a client draws came off the wire, and whatever
     * was pushed while the link was down was pushed into nothing.
     */
    override fun rebindHostLink(connection: Connection) {
        if (role != Role.CLIENT) return
        if (hostLink === connection) return
        hostLinkJob?.cancel()
        clearAbandoned()
        startAsClient(connection)
    }

    /** Puts a table that was stopped back into play. */
    private fun clearAbandoned() {
        _abandoned.value = null
        _notice.value = null
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
        if (message is Bye) {
            // Anything arriving on a link that has since been replaced belongs
            // to the link, not to the seat: a player who dropped and came back
            // must not be reported as having left on the way in.
            if (peers[connection.peerId] !== connection) return
            // One of the players is no longer reachable. The host keeps the
            // position — there is nothing wrong with it — but a table that
            // cannot go on should say so rather than leave somebody waiting on a
            // move that is not coming.
            abandon("The connection to ${nameOfPeer(connection.peerId)} was lost.")
            return
        }
        if (message is Resync) {
            sendStateTo(connection)
            return
        }
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
                if (role == Role.HOST && awaitsConfirmation(current)) {
                    // Hold here until a player has read the scoreboard. Only the
                    // host waits; clients simply render what they were sent.
                    val letThrough = lock.withLock {
                        val granted = released
                        released = false
                        granted
                    }
                    if (!letThrough) {
                        _awaitingConfirmation.value = true
                        return
                    }
                }
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

            val legal = rules.legalMoves(current, seat)
            if (legal.isEmpty()) return
            // Off the caller's thread. A card AI answers instantly, but a chess
            // search runs for a second or more, and this scope belongs to the
            // screen — thinking here would freeze the board it is drawn on.
            val move = withContext(Dispatchers.Default) { ai.chooseMove(current, seat, legal) }

            val applied = lock.withLock {
                val live = authoritative ?: return
                // Nothing else can move while an AI seat is on the clock, but
                // the state is re-read rather than assumed. The move was chosen
                // off this thread and a chess search takes seconds, so it is
                // checked against the position as it stands now: applyMove
                // throws on a move that is no longer legal, and there is nothing
                // above this to catch it.
                if (rules.currentSeat(live) != seat) return@withLock false
                if (move !in rules.legalMoves(live, seat)) return@withLock false
                authoritative = rules.applyMove(live, seat, move)
                publish()
                true
            }
            // The position moved on under the search. Go round again and pick a
            // move for the position as it now stands; the loop returns on its own
            // as soon as a person is on the clock.
            if (!applied) continue
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
            sendStateSync(connection, slot.seat, current)
        }
    }

    /** One seat's view of [current], redacted and addressed to it. Call under [lock]. */
    private suspend fun sendStateSync(connection: Connection, seat: Int, current: S) {
        val moves = if (rules.currentSeat(current) == seat) {
            rules.legalMoves(current, seat).map { rules.encodeMove(it) }
        } else {
            emptyList()
        }
        connection.send(
            StateSync(
                seq = sequence,
                yourSeat = seat,
                stateJson = rules.encodeState(rules.viewFor(current, seat)),
                legalMoves = moves,
            )
        )
    }

    /**
     * Answers one peer's [Resync] with the state as it stands.
     *
     * Nothing to send yet means the table has not been dealt, and that is not a
     * problem: a peer that has asked is by definition listening, so the opening
     * publish moments later will reach it.
     */
    private suspend fun sendStateTo(connection: Connection) {
        lock.withLock {
            val current = authoritative ?: return
            val seat = seatPeers.entries.firstOrNull { it.value == connection.peerId }?.key
                ?: return
            sendStateSync(connection, seat, current)
        }
    }

    /**
     * Releases a table stopped on a scoreboard. A no-op anywhere else, and on a
     * client — where the host owns the decision — it does nothing but clear the
     * local flag.
     */
    fun confirmAdvance() {
        if (!_awaitingConfirmation.value) return
        _awaitingConfirmation.value = false
        if (role != Role.HOST) return
        scope.launch {
            lock.withLock { released = true }
            driveIdleSeats()
        }
    }

    /** Lets a player go on looking at a table they have been told is over. */
    fun dismissAbandoned() {
        _abandoned.value = null
    }

    /**
     * Lets go of every link. Safe on the thread drawing the screen — see
     * [StreamConnection.close], which this leans on to not block.
     */
    fun close() {
        peerJobs.values.forEach { it.cancel() }
        peerJobs.clear()
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        hostLinkJob?.cancel()
        hostLinkJob = null
        runCatching { hostLink?.close() }
        hostLink = null
    }
}
