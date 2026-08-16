package org.prolibertate.games.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading an address somebody has typed off another phone's screen.
 *
 * This is the way into a game when discovery cannot work, which over a phone's
 * own hotspot is the normal case — so it has to accept what a person will
 * actually type, including the trailing space the keyboard adds and the port
 * they will usually leave off.
 */
class ManualAddressTest {

    @Test
    fun `a bare address gets the usual port`() {
        assertEquals(
            ManualAddress("192.168.43.1", DEFAULT_HOST_PORT),
            parseManualAddress("192.168.43.1"),
        )
    }

    @Test
    fun `a port after a colon is used instead`() {
        assertEquals(
            ManualAddress("192.168.43.1", 50000),
            parseManualAddress("192.168.43.1:50000"),
        )
    }

    /** Typing on a phone puts spaces in places nobody intended. */
    @Test
    fun `surrounding space is ignored`() {
        assertEquals(
            ManualAddress("192.168.1.20", DEFAULT_HOST_PORT),
            parseManualAddress("  192.168.1.20  "),
        )
        assertEquals(
            ManualAddress("192.168.1.20", 47654),
            parseManualAddress(" 192.168.1.20 : 47654 "),
        )
    }

    @Test
    fun `a hostname works as well as an address`() {
        assertEquals(
            ManualAddress("pixel-9.local", DEFAULT_HOST_PORT),
            parseManualAddress("pixel-9.local"),
        )
    }

    @Test
    fun `nothing typed is nothing to connect to`() {
        assertNull(parseManualAddress(""))
        assertNull(parseManualAddress("   "))
    }

    @Test
    fun `a port that is not a number is refused rather than guessed`() {
        assertNull(parseManualAddress("192.168.43.1:door"))
        assertNull(parseManualAddress("192.168.43.1:"))
    }

    @Test
    fun `a port outside the range is refused`() {
        assertNull(parseManualAddress("192.168.43.1:0"))
        assertNull(parseManualAddress("192.168.43.1:70000"))
        assertNull(parseManualAddress("192.168.43.1:-1"))
    }

    @Test
    fun `an address with a hole in it is refused`() {
        assertNull(parseManualAddress(":47654"))
        assertNull(parseManualAddress("192.168.43.1 20:47654"))
    }

    /**
     * The default is only a default. A host that found the usual port taken
     * shows a different one, and that is what gets typed.
     */
    @Test
    fun `the default port is only used when none is given`() {
        assertEquals(DEFAULT_HOST_PORT, parseManualAddress("10.0.0.5")?.port)
        assertEquals(1234, parseManualAddress("10.0.0.5:1234")?.port)
    }

    @Test
    fun `an endpoint reads out as address and port`() {
        val endpoint = HostEndpoint(listOf("192.168.43.1", "10.0.0.5"), DEFAULT_HOST_PORT)
        assertEquals("192.168.43.1:$DEFAULT_HOST_PORT", endpoint.primary)
    }

    @Test
    fun `an endpoint with no addresses has nothing to read out`() {
        assertNull(HostEndpoint(emptyList(), DEFAULT_HOST_PORT).primary)
    }

    /** What the host shows must be what the joiner can type. */
    @Test
    fun `what a host shows is what the other end accepts`() {
        val shown = HostEndpoint(listOf("192.168.43.1"), 47654).primary!!
        assertEquals(ManualAddress("192.168.43.1", 47654), parseManualAddress(shown))
    }

    // -- Choosing which of our own addresses to send from --------------------

    /**
     * The phone sharing the hotspot has no Wi-Fi network to bind to, so it
     * picks a source address on the guest's subnet instead. Getting this wrong
     * sends the connection out over mobile data.
     */
    @Test
    fun `addresses on one hotspot subnet match`() {
        assertTrue(sameSubnet("192.168.43.1", "192.168.43.117"))
        assertTrue(sameSubnet("192.168.1.20", "192.168.1.20"))
    }

    @Test
    fun `addresses on different subnets do not`() {
        assertFalse(sameSubnet("192.168.43.1", "192.168.1.5"))
        assertFalse(sameSubnet("192.168.43.1", "10.0.0.5"))
    }

    /** Anything that is not a dotted quad is not a match, rather than a crash. */
    @Test
    fun `nonsense is not a match`() {
        assertFalse(sameSubnet("", "192.168.43.1"))
        assertFalse(sameSubnet("192.168.43", "192.168.43.1"))
        assertFalse(sameSubnet("pixel-9.local", "192.168.43.1"))
        assertFalse(sameSubnet("192.168.43.1.7", "192.168.43.1"))
    }
}
