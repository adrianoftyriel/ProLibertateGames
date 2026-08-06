package org.prolibertate.games.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * played, finished, and above all *left*, which is where a networked table has
 * to let go of a socket while the screen that owns it is being torn down.
 */
class MatchControllerNetworkTest {

    private val tables = mutableListOf<Table>()

    @After
    fun tearDown() {
        tables.forEach { it.dispose() }
    }

    private fun table(): Table = Table().also { tables += it }

    // -----------------------------------------------------------------------

    @Test
    fun `a networked game plays to a result on both devices`() = runBlocking {
        val table = table()
        table.settle()

        assertEquals(ChessPhase.PLAYING, table.client.state.value?.phase)
        // Black is not on the clock, so has nothing to play.
        assertEquals(0, table.client.legalMoves.value.size)

        table.white("f2", "f3")
        table.black("e7", "e5")
        table.white("g2", "g4")
        table.black("d8", "h4")

        assertEquals(ChessOutcome.BLACK_WINS, table.host.state.value?.outcome)
        assertEquals(ChessOutcome.BLACK_WINS, table.client.state.value?.outcome)
        assertTrue(table.host.finished.value)
        assertTrue(table.client.finished.value)
    }

    /**
     * The regression this file exists for.
     *
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
            val table = table()
            table.settle()
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
        val guestLeft = table()
        guestLeft.settle()
        guestLeft.leave(Side.GUEST)
        guestLeft.settle()
        assertEquals("Guest left the game.", guestLeft.host.abandoned.value)

        val hostLeft = table()
        hostLeft.settle()
        hostLeft.leave(Side.HOST)
        hostLeft.settle()
        assertEquals("The host left the game.", hostLeft.client.abandoned.value)
        // Nothing more can be played on a table with no host.
        assertEquals(0, hostLeft.client.legalMoves.value.size)
    }

    @Test
    fun `closing a link on purpose is not reported as the other player leaving`() = runBlocking {
        val table = table()
        table.settle()
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

    /** One two-seat chess table with a real socket between the two devices. */
    private class Table {
        private val server = ServerSocket(0)
        private val clientSocket = Socket()
        private val hostSocket: Socket
        private val hostScope = CoroutineScope(Job() + Dispatchers.Default)
        private val clientScope = CoroutineScope(Job() + Dispatchers.Default)

        val hostLink: StreamConnection
        private val clientLink: StreamConnection
        val host: MatchController<ChessState, ChessMove>
        val client: MatchController<ChessState, ChessMove>

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
                gameId = GameCatalog.CHESS,
                seats = listOf(
                    PlayerSlot(0, "Host", PlayerKind.HUMAN_LOCAL, team = 0),
                    PlayerSlot(1, "Guest", PlayerKind.HUMAN_REMOTE, team = 1, peerId = guestPeerId),
                ),
                optionsJson = Json { encodeDefaults = true }.encodeToString(ChessOptions()),
                seed = 1L,
            )

            fun controller(role: MatchController.Role, seat: Int, scope: CoroutineScope) =
                MatchController<ChessState, ChessMove>(
                    rules = ChessRules,
                    ai = ChessAi(),
                    config = config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(seat),
                    primarySeat = seat,
                    aiThinkingMillis = { 0L },
                )

            host = controller(MatchController.Role.HOST, 0, hostScope)
            client = controller(MatchController.Role.CLIENT, 1, clientScope)
            client.startAsClient(clientLink)
            host.startAsHost(listOf(hostLink))
        }

        /** Lets whatever is in flight cross the socket and be applied. */
        suspend fun settle() = delay(SETTLE_MILLIS)

        suspend fun white(from: String, to: String) {
            host.submit(move(from, to))
            settle()
        }

        suspend fun black(from: String, to: String) {
            client.submit(move(from, to))
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

        private fun move(from: String, to: String) =
            ChessMove(squareFromName(from), squareFromName(to))
    }

    private companion object {
        const val CONNECT_MILLIS = 2_000
        const val SETTLE_MILLIS = 300L

        /**
         * Generous on purpose: leaving takes single-digit milliseconds, and the
         * failure this guards against is not a slow close but one that never
         * returns at all.
         */
        const val BLOCKED_MILLIS = 5_000L
    }
}
