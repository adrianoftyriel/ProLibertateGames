package org.prolibertate.games.score

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Its own store rather than a corner of the settings one: a score sheet is
// session data with a shape that will keep changing, and it has no business
// being rewritten every time somebody moves the animation slider.
private val Context.scorekeeperDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "scorekeeper",
)

/**
 * Keeps the score sheet across the app being left, backgrounded or killed.
 *
 * A scorekeeper that forgets the tally because somebody took a phone call is
 * worse than a pencil, so the sheet is written on every change and read back at
 * the top of the screen.
 */
class ScorekeeperRepository(private val context: Context) {

    private object Keys {
        val SHEET = stringPreferencesKey("sheet")
    }

    // Unknown keys are ignored so a sheet written by a newer build can still be
    // read by an older one — the alternative is losing a game in progress to a
    // channel switch.
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    val sheet: Flow<ScoreSheet> = context.scorekeeperDataStore.data.map { prefs ->
        prefs[Keys.SHEET]
            ?.let { stored -> runCatching { json.decodeFromString<ScoreSheet>(stored) }.getOrNull() }
            ?: ScoreSheet()
    }

    suspend fun save(sheet: ScoreSheet) {
        context.scorekeeperDataStore.edit { it[Keys.SHEET] = json.encodeToString(sheet) }
    }
}
