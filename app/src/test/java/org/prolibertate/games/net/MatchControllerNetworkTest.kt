package org.prolibertate.games.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.chess.ChessAi
import org.prolibertate.games.game.chess.ChessMove
import org.prolibertate.games.game.chess.ChessOptions
import org.prolibertate.games.game.chess.ChessOutcome
import org.prolibertate.games.game.chess.ChessPhase
import org.prolibertate.games.game.chess.ChessRules
import org.prolibertate.games.game.chess.ChessState
import org.prolibertate.games.game.chess.squareFromName
import org.prolibertate.games.game.crazyeights.CrazyEightsAi
import org.prolibertate.games.game.crazyeights.CrazyEightsMove
import org.prolibertate.games.game.crazyeights.CrazyEightsOptions
import org.prolibertate.games.game.crazyeights.CrazyEightsRules
import org.prolibertate.games.game.crazyeights.CrazyEightsState
import org.prolibertate.games.game.engine.GameAi
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Two devices playing one networked game, wired up the way PlayScreen wires it,
 * over a real socket pair on loopback.
 *
 * The engines are covered exhaustively elsewhere; what is worth testing here is
 * the part no rules test can reach — a game with a live connection in it being
 * started, played, and left.
 *
 * The order matters and is reproduced faithfully. A guest's link is opened in the
 * lobby and is already being read there; the match screen only subscribes to it
 * once the host's "start" message has been through a recomposition. So the host
 * is always publishing into a link whose match screen has not attached yet, which
 * is the gap [Table.start] leaves open on purpose.
 */
class MatchControllerNetworkTest {

    private val tables = mutableListOf<Table<*, *>>()

    @After
    fun tearDown() {
        tables.forEach { it.dispose() }
    }

    private fun chess(hostSeat: Int = HOST_SEAT): Table<ChessState, ChessMove> = Table(
        rules = ChessRules,
        ai = ChessAi(),
        gameId = GameCatalog.CHESS,
        optionsJson = testJson.encodeToString(ChessOptions()),
        seatCount = 2,
        hostSeat = hostSeat,
    ).also { tables += it }

    private fun crazyEights(): Table<CrazyEightsState, CrazyEightsMove> = Table(
        rules = CrazyEightsRules,
        ai = CrazyEightsAi(),
        gameId = GameCatalog.CRAZY_EIGHTS,
        optionsJson = testJson.encodeToString(CrazyEightsOptions(playerCount = 2)),
        seatCount = 2,
    ).also { tables += it }

    // -----------------------------------------------------------------------
    // Everyone can see the table from the moment it starts
    // -----------------------------------------------------------------------

    /**
     * The guest's screen has to have something to draw as soon as the game
     * starts. It used to have nothing until the next state push, which on most
     * tables means until the guest's own turn came round — a board that does not
     * appear, and a hand of cards that does not either.
     */
    @Test
    fun `a guest can see the board as soon as the game starts`() = runBlocking {
        val table = chess()
        table.start()

        assertNotNull(
            "The guest has no state to draw: the opening position never reached it",
            table.client.state.value,
        )
        assertEquals(ChessPhase.PLAYING, table.client.state.value?.phase)
        // 32 men on the board, before anybody has moved.
        assertEquals(32, table.client.state.value?.board?.count { it != null })
    }

    @Test
    fun `a guest can see its own hand as soon as the game starts`() = runBlocking {
        val table = crazyEights()
        table.start()

        val view = table.client.state.value
        assertNotNull("The guest was never dealt a visible hand", view)
        assertTrue(
            "The guest cannot see its own cards: hand was ${view?.hands?.get(GUEST_SEAT)}",
            (view?.hands?.get(GUEST_SEAT)?.size ?: 0) > 0,
        )
        // The table itself, not just the hand: something has been turned up to
        // play on to.
        assertNotNull("No discard pile to play on to", view?.topCard)
        // And the opponent's hand is still none of the guest's business.
        assertEquals(emptyList<Any>(), view?.hands?.get(HOST_SEAT))
        assertTrue(
            "Opponent's card count should still be known",
            (view?.handCounts?.get(HOST_SEAT) ?: 0) > 0,
        )
    }

    @Test
    fun `the player on the clock is given its moves as soon as the game starts`() = runBlocking {
        // Crazy 8s deals to seat 0 first, so on this table it is the host that is
        // on the clock; the guest correctly has nothing to play yet.
        val table = crazyEights()
        table.start()

        assertEquals(HOST_SEAT, table.host.state.value?.turn)
        assertTrue("The host has no moves to make on its own turn", table.host.legalMoves.value.isNotEmpty())
        assertEquals(emptyList<CrazyEightsMove>(), table.client.legalMoves.value)
    }

    /**
     * A guest can be the one to move first — a card game's turn order follows the
     * deal, not the seating — and a guest that can see the table but has nothing
     * it is allowed to play is no better off than one that can see nothing.
     */
    @Test
    fun `a guest on the clock at the deal is given its moves`() = runBlocking {
        // The host takes Black, which leaves the guest playing White and first to
        // move before either device has done anything.
        val table = chess(hostSeat = 1)
        table.start()

        assertNotNull(table.client.state.value)
        assertEquals(
            "The guest has to move but was given nothing to play",
            20,
            table.client.legalMoves.value.size,
        )
        assertEquals(emptyList<ChessMove>(), table.host.legalMoves.value)
    }

    @Test
    fun `a guest is given its moves when its turn arrives`() = runBlocking {
        val table = chess()
        table.start()

        assertEquals(emptyList<ChessMove>(), table.client.legalMoves.value)
        table.host.submit(ChessMove(squareFromName("e2"), squareFromName("e4")))
        table.settle()
        // Twenty legal replies to 1. e4.
        assertEquals(20, table.client.legalMoves.value.size)
    }

    // -----------------------------------------------------------------------
    // Playing and finishing
    // -----------------------------------------------------------------------

    @Test
    fun `a networked game plays to a result on both devices`() = runBlocking {
        val table = chess()
        table.start()

        table.white("f2", "f3")
        table.black("e7", "e5")
        table.white("g2", "g4")
        table.black("d8", "h4")

        assertEquals(ChessOutcome.BLACK_WINS, table.host.state.value?.outcome)
        assertEquals(ChessOutcome.BLACK_WINS, table.client.state.value?.outcome)
        assertTrue(table.host.finished.value)
        assertTrue(table.client.finished.value)
    }

    // -----------------------------------------------------------------------
    // Leaving
    // -----------------------------------------------------------------------

    /**
     * Leaving a game runs on the thread drawing the screen — Android's main
     * thread — by way of AppRoot.pop() and PlayScreen's onDispose. It closed the
     * buffered reader first, which wants the same lock readLine() holds for the
     * whole of a blocking read, so the main thread waited on a read that only its
     * own close could ever end. The app hung for good, and a chess game is the
     * one long enough that a player wants out of it mid-game.
     */
    @Test
    fun `leaving a game in progress does not block the thread that leaves`() = runBlocking {
        for (side in listOf(Side.HOST, Side.GUEST)) {
            val table = chess()
            table.start()
            table.white("e2", "e4")

            // Left on its own thread, and a daemon one: should this ever hang
            // again, the test has to report it rather than take the build down
            // with it — a thread stuck on a lock cannot be interrupted.
            val left = Thread { table.leave(side) }.apply { isDaemon = true }
            left.start()
            left.join(BLOCKED_MILLIS)

            assertTrue(
                "Leaving as $side did not return: the thread that ends a game is stuck in close()",
                !left.isAlive,
            )
        }
    }

    @Test
    fun `a player leaving is reported to the device still at the table`() = runBlocking {
        val guestLeft = chess()
        guestLeft.start()
        guestLeft.leave(Side.GUEST)
        guestLeft.settle()
        assertEquals("Guest left the game.", guestLeft.host.abandoned.value)

        val hostLeft = chess()
        hostLeft.start()
        hostLeft.leave(Side.HOST)
        hostLeft.settle()
        assertEquals("The host left the game.", hostLeft.client.abandoned.value)
        // Nothing more can be played on a table with no host.
        assertEquals(emptyList<ChessMove>(), hostLeft.client.legalMoves.value)
    }

    @Test
    fun `closing a link on purpose is not reported as the other player leaving`() = runBlocking {
        val table = chess()
        table.start()
        assertNull(table.host.abandoned.value)

        // The host closes its own end, as leaving does. Its own screen has no
        // business being told that somebody else left.
        table.hostLink.close()
        table.settle()
        assertNull(table.host.abandoned.value)
        // The guest, on the other hand, has lost the game it was playing.
        assertNotNull(table.client.abandoned.value)
    }

    // -----------------------------------------------------------------------

    private enum class Side { HOST, GUEST }

    /** One two-seat table with a real socket between the two devices. */
    private class Table<S : Any, M : Any>(
        private val rules: GameRules<S, M>,
        ai: GameAi<S, M>,
        gameId: String,
        optionsJson: String,
        seatCount: Int,
        private val hostSeat: Int = HOST_SEAT,
    ) {
        private val server = ServerSocket(0)
        private val clientSocket = Socket()
        private val hostSocket: Socket
        private val hostScope = CoroutineScope(Job() + Dispatchers.Default)
        private val clientScope = CoroutineScope(Job() + Dispatchers.Default)

        val hostLink: StreamConnection
        private val clientLink: StreamConnection
        val host: MatchController<S, M>
        val client: MatchController<S, M>

        init {
            clientSocket.connect(InetSocketAddress("127.0.0.1", server.localPort), CONNECT_MILLIS)
            hostSocket = server.accept()
            val guestPeerId = hostSocket.inetAddress?.hostAddress ?: "guest"

            hostLink = StreamConnection(
                peerId = guestPeerId,
                kind = TransportKind.LAN,
                input = hostSocket.getInputStream(),
                output = hostSocket.getOutputStream(),
                scope = hostScope,
                onClosed = { runCatching { hostSocket.close() } },
            )
            clientLink = StreamConnection(
                peerId = "lan:host",
                kind = TransportKind.LAN,
                input = clientSocket.getInputStream(),
                output = clientSocket.getOutputStream(),
                scope = clientScope,
                onClosed = { runCatching { clientSocket.close() } },
            )

            val config = TableConfig(
                gameId = gameId,
                seats = (0 until seatCount).map { seat ->
                    PlayerSlot(
                        seat = seat,
                        name = if (seat == hostSeat) "Host" else "Guest",
                        kind = if (seat == hostSeat) {
                            PlayerKind.HUMAN_LOCAL
                        } else {
                            PlayerKind.HUMAN_REMOTE
                        },
                        team = seat,
                        peerId = if (seat == hostSeat) null else guestPeerId,
                    )
                },
                optionsJson = optionsJson,
                seed = 1L,
            )

            fun controller(role: MatchController.Role, seat: Int, scope: CoroutineScope) =
                MatchController(
                    rules = rules,
                    ai = ai,
                    config = config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(seat),
                    primarySeat = seat,
                    aiThinkingMillis = { 0L },
                )

            host = controller(MatchController.Role.HOST, hostSeat, hostScope)
            client = controller(
                MatchController.Role.CLIENT,
                (0 until seatCount).first { it != hostSeat },
                clientScope,
            )

            // The lobby is already reading the guest's link when the game starts,
            // and goes on reading it for the whole match. Without this the test
            // would be the only reader, which is not the arrangement the app has.
            clientScope.launch { clientLink.incoming.collect { /* the lobby's collector */ } }
        }

        /**
         * Starts the match the way the two devices do: the host first, then the
         * guest once its screen has been through a frame. Anything the host
         * publishes in between is published into a link the guest's match screen
         * has not subscribed to yet.
         */
        suspend fun start() {
            host.startAsHost(listOf(hostLink))
            delay(NAVIGATION_MILLIS)
            client.startAsClient(clientLink)
            settle()
        }

        /** Lets whatever is in flight cross the socket and be applied. */
        suspend fun settle() = delay(SETTLE_MILLIS)

        suspend fun white(from: String, to: String) {
            @Suppress("UNCHECKED_CAST")
            host.submit(chessMove(from, to) as M)
            settle()
        }

        suspend fun black(from: String, to: String) {
            @Suppress("UNCHECKED_CAST")
            client.submit(chessMove(from, to) as M)
            settle()
        }

        /** Exactly what AppRoot.pop() and PlayScreen's onDispose do, in that order. */
        fun leave(side: Side) {
            if (side == Side.HOST) {
                runCatching { hostLink.close() }
                host.close()
                hostScope.cancel()
            } else {
                runCatching { clientLink.close() }
                client.close()
                clientScope.cancel()
            }
        }

        /**
         * Sockets first, on purpose: that is what frees any reader still parked
         * on one, so clearing up after a failed test cannot itself hang.
         */
        fun dispose() {
            runCatching { hostSocket.close() }
            runCatching { clientSocket.close() }
            runCatching { server.close() }
            runCatching { hostLink.close() }
            runCatching { clientLink.close() }
            runCatching { host.close() }
            runCatching { client.close() }
            hostScope.cancel()
            clientScope.cancel()
        }

        private fun chessMove(from: String, to: String) =
            ChessMove(squareFromName(from), squareFromName(to))
    }

    private companion object {
        const val HOST_SEAT = 0
        const val GUEST_SEAT = 1
        const val CONNECT_MILLIS = 2_000
        const val SETTLE_MILLIS = 300L

        /**
         * Stands in for the guest's trip from the lobby to the table: a state
         * change, a recomposition and a LaunchedEffect, none of which the host
         * waits for.
         */
        const val NAVIGATION_MILLIS = 250L

        /**
         * Generous on purpose: leaving takes single-digit milliseconds, and the
         * failure this guards against is not a slow close but one that never
         * returns at all.
         */
        const val BLOCKED_MILLIS = 5_000L

        val testJson = Json { encodeDefaults = true }
    }
}
