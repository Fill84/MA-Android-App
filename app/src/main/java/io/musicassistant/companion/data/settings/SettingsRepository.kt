package io.musicassistant.companion.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val IS_CONFIGURED = booleanPreferencesKey("is_configured")
        val BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val settingsFlow: Flow<AppSettings> =
            context.dataStore.data.map { prefs ->
                AppSettings(
                        serverUrl = prefs[Keys.SERVER_URL] ?: "",
                        serverName = prefs[Keys.SERVER_NAME] ?: "",
                        isConfigured = prefs[Keys.IS_CONFIGURED] ?: false,
                        backgroundPlaybackEnabled = prefs[Keys.BACKGROUND_PLAYBACK] ?: true,
                        keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: false,
                        themeMode =
                                prefs[Keys.THEME_MODE]?.let {
                                    try {
                                        ThemeMode.valueOf(it)
                                    } catch (_: Exception) {
                                        ThemeMode.DARK
                                    }
                                }
                                        ?: ThemeMode.DARK
                )
            }

    suspend fun updateServer(url: String, name: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = url
            prefs[Keys.SERVER_NAME] = name
            prefs[Keys.IS_CONFIGURED] = true
        }
    }

    suspend fun clearServer() {
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = ""
            prefs[Keys.SERVER_NAME] = ""
            prefs[Keys.IS_CONFIGURED] = false
        }
    }

    suspend fun setBackgroundPlayback(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.BACKGROUND_PLAYBACK] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = mode.name }
    }
}
