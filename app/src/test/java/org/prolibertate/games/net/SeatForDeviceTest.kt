package org.prolibertate.games.net

import org.junit.Assert.assertEquals
import org.junit.Test
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig

/**
 * Which seat a guest sits in, worked out from the seat list the host sends.
 *
 * A guest that gets this wrong draws the wrong seat's hand — and since the host
 * redacts every hand but the one it addressed the view to, the wrong seat's hand
 * is an empty one. So this is what stands between a guest and a table it cannot
 * see itself at.
 */
class SeatForDeviceTest {

    @Test
    fun `a guest finds itself by its own device id`() {
        val config = table(
            host(0),
            guest(1, deviceId = "device-a"),
            guest(2, deviceId = "device-b"),
        )

        assertEquals(1, seatForDevice(config, "device-a"))
        assertEquals(2, seatForDevice(config, "device-b"))
    }

    /**
     * The regression this exists for.
     *
     * The seat used to be looked up by [PlayerSlot.peerId], which is the address
     * the host's own transport made up for the incoming link — an IP on Wi-Fi, a
     * MAC over Bluetooth. A guest has never seen that value and so never matched
     * it, and every guest fell through to "the first seat held by somebody
     * remote". With one guest that is right by luck. With two, both of them
     * claimed seat 1, and the one really sitting in seat 2 was left looking at a
     * table with no cards in front of it.
     */
    @Test
    fun `two guests do not both claim the first remote seat`() {
        val config = table(
            host(0),
            // Filed under a transport address, as the host files them.
            guest(1, deviceId = "device-a", peerId = "192.168.1.20"),
            guest(2, deviceId = "device-b", peerId = "192.168.1.21"),
        )

        val seats = listOf(seatForDevice(config, "device-a"), seatForDevice(config, "device-b"))
        assertEquals(listOf(1, 2), seats)
    }

    @Test
    fun `seats held by the computer are never claimed by a guest`() {
        val config = table(host(0), guest(1, deviceId = "device-a"), ai(2))

        assertEquals(1, seatForDevice(config, "device-a"))
    }

    @Test
    fun `a guest dropped for the computer no longer claims its old seat`() {
        // What setSeatKind leaves behind when the host drops somebody.
        val config = table(host(0), ai(1), guest(2, deviceId = "device-b"))

        assertEquals(2, seatForDevice(config, "device-b"))
    }

    /**
     * A host on an older version sends no device ids at all. One guest still
     * lands in the right seat, which is what keeps a mixed pair of devices
     * playable rather than silently wrong.
     */
    @Test
    fun `a single guest still finds its seat without device ids`() {
        val config = table(host(0), guest(1, deviceId = null), ai(2), ai(3))

        assertEquals(1, seatForDevice(config, "device-a"))
    }

    @Test
    fun `an unknown device falls back to a seat rather than failing`() {
        assertEquals(0, seatForDevice(table(host(0), ai(1)), "who-is-this"))
    }

    // -----------------------------------------------------------------------

    private fun table(vararg seats: PlayerSlot) = TableConfig(
        gameId = GameCatalog.CRAZY_EIGHTS,
        seats = seats.toList(),
        optionsJson = "{}",
        seed = 1L,
    )

    private fun host(seat: Int) =
        PlayerSlot(seat, "Host", PlayerKind.HUMAN_LOCAL, team = seat)

    private fun guest(seat: Int, deviceId: String?, peerId: String = "10.0.0.$seat") =
        PlayerSlot(seat, "Guest $seat", PlayerKind.HUMAN_REMOTE, team = seat, peerId, deviceId)

    private fun ai(seat: Int) =
        PlayerSlot(seat, "Computer $seat", PlayerKind.AI, team = seat)
}
