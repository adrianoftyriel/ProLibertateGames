package org.prolibertate.games.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * LAN play: mDNS service discovery to find the host, then a plain TCP socket
 * per client.
 *
 * NSD is used rather than a UDP broadcast ping because it works across the
 * Wi-Fi isolation settings most home routers ship with, and because Android
 * gives us the resolver for free.
 */
class LanTransport(private val context: Context) : Transport {

    override val kind: TransportKind = TransportKind.LAN

    private val nsdManager: NsdManager
        get() = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override fun isAvailable(): Boolean = true

    // -----------------------------------------------------------------------
    // Hosting
    // -----------------------------------------------------------------------

    override fun host(displayName: String, scope: CoroutineScope): Flow<Connection> = callbackFlow {
        // Port 0 lets the OS pick a free port, which is then advertised.
        val server = ServerSocket(0)
        serverSocket = server

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME @ $displayName"
            serviceType = SERVICE_TYPE
            port = server.localPort
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }

        val acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.tcpNoDelay = true
                val connection = StreamConnection(
                    peerId = socket.inetAddress?.hostAddress ?: "unknown",
                    kind = TransportKind.LAN,
                    input = socket.getInputStream(),
                    output = socket.getOutputStream(),
                    scope = scope,
                    onClosed = { runCatching { socket.close() } },
                )
                trySend(connection)
            }
        }

        awaitClose {
            acceptJob.cancel()
            stopAdvertising()
            runCatching { server.close() }
        }
    }

    // -----------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------

    override fun discover(scope: CoroutineScope): Flow<List<DiscoveredHost>> = callbackFlow {
        val found = Collections.synchronizedMap(linkedMapOf<String, DiscoveredHost>())

        fun publish() {
            trySend(synchronized(found) { found.values.toList() })
        }

        // Resolutions are queued one at a time: NsdManager rejects concurrent
        // resolve calls with FAILURE_ALREADY_ACTIVE on many devices.
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val next = synchronized(pending) { pending.removeFirstOrNull() } ?: return
            resolving = true
            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    resolving = false
                    resolveNext()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val address = info.host?.hostAddress
                    if (address != null) {
                        val host = DiscoveredHost(
                            id = "lan:$address:${info.port}",
                            name = info.serviceName.substringAfter("@ ").trim()
                                .ifBlank { info.serviceName },
                            kind = TransportKind.LAN,
                            address = address,
                            port = info.port,
                        )
                        synchronized(found) { found[host.id] = host }
                        publish()
                    }
                    resolving = false
                    resolveNext()
                }
            }
            @Suppress("DEPRECATION")
            runCatching { nsdManager.resolveService(next, resolveListener) }
                .onFailure {
                    resolving = false
                    resolveNext()
                }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.contains("plgames") != true) return
                synchronized(pending) { pending.addLast(info) }
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) {
                    val key = found.entries.firstOrNull { it.value.name == info.serviceName }?.key
                    if (key != null) found.remove(key)
                }
                publish()
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener

        publish()
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { close() }

        awaitClose { stopDiscovery() }
    }

    // -----------------------------------------------------------------------
    // Joining
    // -----------------------------------------------------------------------

    override suspend fun join(host: DiscoveredHost, scope: CoroutineScope): Connection =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            socket.connect(InetSocketAddress(host.address, host.port), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            StreamConnection(
                peerId = host.id,
                kind = TransportKind.LAN,
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                scope = scope,
                onClosed = { runCatching { socket.close() } },
            )
        }

    // -----------------------------------------------------------------------

    private fun stopAdvertising() {
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
    }

    private fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    override fun stop() {
        stopAdvertising()
        stopDiscovery()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
    }
}
