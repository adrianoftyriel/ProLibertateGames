package org.prolibertate.games

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import org.prolibertate.games.net.LobbyController
import org.prolibertate.games.settings.SettingsRepository
import org.prolibertate.games.ui.AppEnv
import org.prolibertate.games.ui.AppRoot
import org.prolibertate.games.ui.theme.ProLibertateTheme
import org.prolibertate.games.update.Updater
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var env: AppEnv

    /**
     * Bluetooth needs runtime consent from Android 12 onwards. The result is
     * ignored deliberately: refusing simply means the Bluetooth transport
     * reports itself unavailable and the lobby falls back to Wi-Fi.
     */
    private val bluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        env = AppEnv(
            settingsRepository = SettingsRepository(applicationContext),
            updater = Updater(this),
            lobby = LobbyController(applicationContext, lifecycleScope),
            peerId = stablePeerId(),
            requestBluetoothPermissions = ::requestBluetoothPermissions,
        )

        setContent {
            ProLibertateTheme {
                AppRoot(env)
            }
        }
    }

    private fun requestBluetoothPermissions() {
        // Only CONNECT: the transport reaches paired devices and never scans.
        // See the manifest for what discovery of unpaired devices would add.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
    }

    /**
     * Identifies this device to a host for the length of an install. ANDROID_ID
     * is per-app-signing-key and survives restarts, which is all a lobby needs
     * to tell two phones apart.
     */
    private fun stablePeerId(): String {
        val androidId = runCatching {
            AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.ANDROID_ID)
        }.getOrNull()
        return androidId ?: UUID.randomUUID().toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::env.isInitialized) env.lobby.stop()
    }
}
