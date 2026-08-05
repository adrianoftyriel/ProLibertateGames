package org.prolibertate.games.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Bluetooth play over Classic RFCOMM.
 *
 * RFCOMM rather than BLE: this is a sustained, ordered, two-way stream of small
 * messages, which is exactly what a serial profile is for. BLE would mean
 * chunking everything into 20-byte GATT writes for no benefit.
 *
 * Discovery is limited to *paired* devices. Pair the two devices once in
 * Android's Bluetooth settings and they will see each other here; scanning for
 * unpaired devices needs a foreground scan plus location permission on older
 * releases and is not worth the friction for a game lobby.
 */
class BluetoothTransport(private val context: Context) : Transport {

    override val kind: TransportKind = TransportKind.BLUETOOTH

    private val serviceUuid: UUID = UUID.fromString(RFCOMM_UUID)

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var serverSocket: BluetoothServerSocket? = null

    override fun isAvailable(): Boolean = adapter?.isEnabled == true && hasConnectPermission()

    /**
     * Android 12 replaced the blanket BLUETOOTH permission with BLUETOOTH_CONNECT.
     * Below 31 the manifest permission is granted at install time.
     */
    fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    // -----------------------------------------------------------------------
    // Hosting
    // -----------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    override fun host(displayName: String, scope: CoroutineScope): Flow<Connection> = callbackFlow {
        val bluetooth = adapter
        if (bluetooth == null || !hasConnectPermission()) {
            close()
            return@callbackFlow
        }

        val server = runCatching {
            bluetooth.listenUsingRfcommWithServiceRecord(SERVICE_NAME, serviceUuid)
        }.getOrNull()
        if (server == null) {
            close()
            return@callbackFlow
        }
        serverSocket = server

        val acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket: BluetoothSocket = runCatching { server.accept() }.getOrNull() ?: break
                val connection = StreamConnection(
                    peerId = runCatching { socket.remoteDevice?.address }.getOrNull() ?: "bt",
                    kind = TransportKind.BLUETOOTH,
                    input = socket.inputStream,
                    output = socket.outputStream,
                    scope = scope,
                    onClosed = { runCatching { socket.close() } },
                )
                trySend(connection)
            }
        }

        awaitClose {
            acceptJob.cancel()
            runCatching { server.close() }
            serverSocket = null
        }
    }

    // -----------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------

    /**
     * Paired devices are re-published on a slow poll: there is no callback for
     * "a device became reachable", and a host that is not listening simply
     * refuses the connection when joined.
     */
    @SuppressLint("MissingPermission")
    override fun discover(scope: CoroutineScope): Flow<List<DiscoveredHost>> = callbackFlow {
        if (!hasConnectPermission()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val pollJob = scope.launch {
            while (isActive) {
                val bonded = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
                trySend(
                    bonded.map { device ->
                        DiscoveredHost(
                            id = "bt:${device.address}",
                            name = runCatching { device.name }.getOrNull() ?: device.address,
                            kind = TransportKind.BLUETOOTH,
                            address = device.address,
                        )
                    }
                )
                delay(POLL_INTERVAL_MS)
            }
        }

        awaitClose { pollJob.cancel() }
    }

    // -----------------------------------------------------------------------
    // Joining
    // -----------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    override suspend fun join(host: DiscoveredHost, scope: CoroutineScope): Connection =
        withContext(Dispatchers.IO) {
            val bluetooth = requireNotNull(adapter) { "No Bluetooth adapter" }
            require(hasConnectPermission()) { "Bluetooth permission not granted" }

            val device = bluetooth.getRemoteDevice(host.address)
            // Discovery is expensive and slows down a connect attempt sharply.
            runCatching { bluetooth.cancelDiscovery() }

            val socket = device.createRfcommSocketToServiceRecord(serviceUuid)
            socket.connect()
            StreamConnection(
                peerId = host.id,
                kind = TransportKind.BLUETOOTH,
                input = socket.inputStream,
                output = socket.outputStream,
                scope = scope,
                onClosed = { runCatching { socket.close() } },
            )
        }

    override fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private companion object {
        const val POLL_INTERVAL_MS = 4_000L
    }
}
