package org.prolibertate.games

import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        env = AppEnv(
            settingsRepository = SettingsRepository(applicationContext),
            updater = Updater(this),
            lobby = LobbyController(applicationContext, lifecycleScope),
            peerId = stablePeerId(),
        )

        setContent {
            ProLibertateTheme {
                AppRoot(env)
            }
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
