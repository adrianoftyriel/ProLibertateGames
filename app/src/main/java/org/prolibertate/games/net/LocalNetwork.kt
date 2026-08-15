package org.prolibertate.games.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket

/**
 * Finding the network the other phone is actually on.
 *
 * A phone's hotspot has no internet behind it, and Android takes that
 * literally: the Wi-Fi network fails its captive-portal check, stays
 * unvalidated, and **mobile data remains the default network**. Every socket
 * this app opens without saying otherwise is therefore routed out over
 * cellular, where 192.168.x.x means nothing at all. That is the whole of the
 * "two phones on one phone's hotspot cannot see each other" bug: nothing is
 * wrong with the Wi-Fi, the packets are simply being posted to the wrong
 * network.
 *
 * The cure is to name the network explicitly rather than accept the default —
 * which is why everything here deliberately looks for a Wi-Fi network *without*
 * asking whether it has internet. Asking is what breaks it.
 */

/** The Wi-Fi network this device is on, internet or no internet. */
fun wifiNetwork(context: Context): Network? {
    val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return null
    @Suppress("DEPRECATION")
    return manager.allNetworks.firstOrNull { network ->
        val capabilities = manager.getNetworkCapabilities(network)
        capabilities != null &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}

/**
 * Sends this socket out over the Wi-Fi rather than wherever the system would
 * otherwise send it. Silent if there is no Wi-Fi network to bind to: a phone
 * running the hotspot itself has no Wi-Fi *client* network, and reaches its own
 * guests over the tethering interface's own route without any help.
 */
fun bindToWifi(context: Context, socket: Socket) {
    val network = wifiNetwork(context) ?: return
    runCatching { network.bindSocket(socket) }
}

/**
 * The addresses another device on the same Wi-Fi could reach this one at.
 *
 * Read off the interfaces rather than asked of [WifiManager], because the phone
 * running the hotspot is not a Wi-Fi client and has no client address to give —
 * its guests reach it at whatever the tethering interface is numbered, which
 * only the interface list knows.
 */
fun localIpv4Addresses(): List<String> {
    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }
        .getOrNull() ?: return emptyList()

    val found = mutableListOf<Pair<Int, String>>()
    for (nic in interfaces) {
        if (runCatching { !nic.isUp || nic.isLoopback }.getOrDefault(true)) continue
        for (address in nic.inetAddresses) {
            if (address !is Inet4Address || address.isLoopbackAddress) continue
            val text = address.hostAddress ?: continue
            // Tethering interfaces first: on the phone running the hotspot that
            // is the only address its guests can reach, and it is the case the
            // list exists to serve.
            val rank = when {
                nic.name.startsWith("ap") || nic.name.startsWith("swlan") -> 0
                nic.name.startsWith("wlan") -> 1
                else -> 2
            }
            found += rank to text
        }
    }
    return found.sortedBy { it.first }.map { it.second }.distinct()
}

/**
 * Holds the Wi-Fi chip's multicast filter open.
 *
 * mDNS is multicast, and without this lock the radio drops multicast frames
 * that are not addressed to this device before the system ever sees them. The
 * manifest has asked for the permission since the beginning; nothing ever took
 * the lock.
 */
class MulticastGuard(context: Context) {

    private val wifi = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var lock: WifiManager.MulticastLock? = null

    fun acquire() {
        if (lock != null) return
        lock = runCatching {
            wifi?.createMulticastLock("plgames-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    fun release() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }
}

/** An address and port typed in by hand, once it has been made sense of. */
data class ManualAddress(val address: String, val port: Int)

/**
 * Reads what somebody typed into the "connect to" box.
 *
 * Accepts a bare address, since [DEFAULT_HOST_PORT] means the port is usually
 * the same one and asking a person to copy five more digits off another screen
 * is a good way to have them type four of them. A port may still be given
 * after a colon for the case where the usual one was taken.
 */
fun parseManualAddress(typed: String, defaultPort: Int = DEFAULT_HOST_PORT): ManualAddress? {
    val text = typed.trim()
    if (text.isEmpty()) return null

    val colon = text.lastIndexOf(':')
    if (colon < 0) return ManualAddress(text, defaultPort).takeIf { looksLikeHost(it.address) }

    val address = text.substring(0, colon).trim()
    val port = text.substring(colon + 1).trim().toIntOrNull() ?: return null
    if (port !in 1..65_535) return null
    return ManualAddress(address, port).takeIf { looksLikeHost(it.address) }
}

/**
 * Enough of a check to catch a slip, and no more. Anything that survives this
 * is handed to the socket, which is the real judge of whether it exists.
 */
private fun looksLikeHost(address: String): Boolean =
    address.isNotEmpty() && address.none { it.isWhitespace() }
