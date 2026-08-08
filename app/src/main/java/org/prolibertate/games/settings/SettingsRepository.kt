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
    /**
     * Exactly what the user has typed, which may be blank while they are
     * mid-edit. Nothing substitutes a default into this value: doing so meant
     * that clearing the field wrote "Player" straight back and the text
     * snapped back before a real name could be typed.
     */
    val playerName: String = "",
) {
    /**
     * The name other players actually see. This is where the default lives, so
     * an empty field is a display concern rather than something written back
     * into what the user is typing.
     */
    val displayName: String get() = playerName.trim().ifBlank { DEFAULT_PLAYER_NAME }

    /** Scales a nominal duration by the chosen speed. */
    fun scaled(millis: Long): Long =
        (millis / animationSpeed.coerceIn(MIN_SPEED, MAX_SPEED)).toLong()

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        const val DEFAULT_PLAYER_NAME = "Player"
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SOUND = booleanPreferencesKey("sound_enabled")
        val SPEED = floatPreferencesKey("animation_speed")
        val UPDATE_ON_LAUNCH = booleanPreferencesKey("check_updates_on_launch")
        val NAME = stringPreferencesKey("player_name")
        // There is deliberately no update-channel key. The channel is a
        // property of the installed APK, not a preference — an install that
        // could be pointed at the other channel's builds is exactly the mix-up
        // that separate applicationIds exist to prevent. Anything an older
        // version left under "update_channel" is simply never read again.
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            soundEnabled = prefs[Keys.SOUND] ?: true,
            animationSpeed = prefs[Keys.SPEED] ?: 1.0f,
            checkForUpdatesOnLaunch = prefs[Keys.UPDATE_ON_LAUNCH] ?: true,
            playerName = prefs[Keys.NAME] ?: "",
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

    /** Stores the name verbatim. The fallback belongs to [Settings.displayName]. */
    suspend fun setPlayerName(name: String) {
        context.dataStore.edit { it[Keys.NAME] = name }
    }
}
