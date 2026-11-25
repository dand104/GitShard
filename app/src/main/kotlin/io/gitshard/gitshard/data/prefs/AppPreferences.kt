package io.gitshard.gitshard.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val editorFontSizeSp: Int = DEFAULT_FONT_SIZE_SP,
    val editorMonospace: Boolean = false,
    val autoSyncEnabled: Boolean = true,
    val syncIntervalMinutes: Int = DEFAULT_SYNC_INTERVAL_MINUTES,
    val syncDebounceMillis: Long = DEFAULT_SYNC_DEBOUNCE_MILLIS,
    val syncOnMeteredNetwork: Boolean = true,
) {
    companion object {
        const val MIN_FONT_SIZE_SP = 12
        const val MAX_FONT_SIZE_SP = 28
        const val DEFAULT_FONT_SIZE_SP = 16
        const val MIN_SYNC_INTERVAL_MINUTES = 15
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 15
        const val MIN_SYNC_DEBOUNCE_MILLIS = 1_000L
        const val DEFAULT_SYNC_DEBOUNCE_MILLIS = 5_000L
    }
}

class AppPreferences(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs ->
            AppSettings(
                themeMode = prefs[KEY_THEME_MODE]
                    ?.let { raw -> ThemeMode.entries.firstOrNull { it.name == raw } }
                    ?: ThemeMode.SYSTEM,
                dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
                editorFontSizeSp = (prefs[KEY_EDITOR_FONT_SIZE] ?: AppSettings.DEFAULT_FONT_SIZE_SP)
                    .coerceIn(AppSettings.MIN_FONT_SIZE_SP, AppSettings.MAX_FONT_SIZE_SP),
                editorMonospace = prefs[KEY_EDITOR_MONOSPACE] ?: false,
                autoSyncEnabled = prefs[KEY_AUTO_SYNC_ENABLED] ?: true,
                syncIntervalMinutes = (prefs[KEY_SYNC_INTERVAL_MINUTES] ?: AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES)
                    .coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES),
                syncDebounceMillis = (prefs[KEY_SYNC_DEBOUNCE_MILLIS] ?: AppSettings.DEFAULT_SYNC_DEBOUNCE_MILLIS)
                    .coerceAtLeast(AppSettings.MIN_SYNC_DEBOUNCE_MILLIS),
                syncOnMeteredNetwork = prefs[KEY_SYNC_ON_METERED] ?: true,
            )
        }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setEditorFontSizeSp(sizeSp: Int) {
        val clamped = sizeSp.coerceIn(AppSettings.MIN_FONT_SIZE_SP, AppSettings.MAX_FONT_SIZE_SP)
        dataStore.edit { it[KEY_EDITOR_FONT_SIZE] = clamped }
    }

    suspend fun setEditorMonospace(enabled: Boolean) {
        dataStore.edit { it[KEY_EDITOR_MONOSPACE] = enabled }
    }

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_SYNC_ENABLED] = enabled }
    }

    suspend fun setSyncIntervalMinutes(minutes: Int) {
        val clamped = minutes.coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES)
        dataStore.edit { it[KEY_SYNC_INTERVAL_MINUTES] = clamped }
    }

    suspend fun setSyncDebounceMillis(millis: Long) {
        val clamped = millis.coerceAtLeast(AppSettings.MIN_SYNC_DEBOUNCE_MILLIS)
        dataStore.edit { it[KEY_SYNC_DEBOUNCE_MILLIS] = clamped }
    }

    suspend fun setSyncOnMeteredNetwork(allowed: Boolean) {
        dataStore.edit { it[KEY_SYNC_ON_METERED] = allowed }
    }

    companion object {
        const val STORE_NAME = "app_preferences"

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_EDITOR_FONT_SIZE = intPreferencesKey("editor_font_size_sp")
        private val KEY_EDITOR_MONOSPACE = booleanPreferencesKey("editor_monospace")
        private val KEY_AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
        private val KEY_SYNC_INTERVAL_MINUTES = intPreferencesKey("sync_interval_minutes")
        private val KEY_SYNC_DEBOUNCE_MILLIS = longPreferencesKey("sync_debounce_millis")
        private val KEY_SYNC_ON_METERED = booleanPreferencesKey("sync_on_metered_network")

        fun create(context: Context, scope: CoroutineScope): AppPreferences {
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { context.applicationContext.preferencesDataStoreFile(STORE_NAME) },
            )
            return AppPreferences(store)
        }
    }
}
