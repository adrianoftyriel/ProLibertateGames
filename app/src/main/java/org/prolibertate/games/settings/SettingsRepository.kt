package org.prolibertate.games.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * User settings.
 *
 * [animationSpeed] is a multiplier rather than a duration so every animated
 * thing in the app can scale off one number: 0.5x is slow and deliberate, 2x
 * is for people who already know the rules.
 */
data class Settings(
    val soundEnabled: Boolean = true,
    val animationSpeed: Float = 1.0f,
    val checkForUpdatesOnLaunch: Boolean = true,
    val playerName: String = "Player",
) {
    /** Scales a nominal duration by the chosen speed. */
    fun scaled(millis: Long): Long =
        (millis / animationSpeed.coerceIn(MIN_SPEED, MAX_SPEED)).toLong()

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SOUND = booleanPreferencesKey("sound_enabled")
        val SPEED = floatPreferencesKey("animation_speed")
        val UPDATE_ON_LAUNCH = booleanPreferencesKey("check_updates_on_launch")
        val NAME = stringPreferencesKey("player_name")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            soundEnabled = prefs[Keys.SOUND] ?: true,
            animationSpeed = prefs[Keys.SPEED] ?: 1.0f,
            checkForUpdatesOnLaunch = prefs[Keys.UPDATE_ON_LAUNCH] ?: true,
            playerName = prefs[Keys.NAME] ?: "Player",
        )
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOUND] = enabled }
    }

    suspend fun setAnimationSpeed(speed: Float) {
        context.dataStore.edit {
            it[Keys.SPEED] = speed.coerceIn(Settings.MIN_SPEED, Settings.MAX_SPEED)
        }
    }

    suspend fun setCheckForUpdatesOnLaunch(enabled: Boolean) {
        context.dataStore.edit { it[Keys.UPDATE_ON_LAUNCH] = enabled }
    }

    suspend fun setPlayerName(name: String) {
        context.dataStore.edit { it[Keys.NAME] = name.trim().ifBlank { "Player" } }
    }
}
