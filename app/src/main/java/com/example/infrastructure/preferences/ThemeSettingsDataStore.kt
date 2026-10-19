package com.example.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.domain.core.ui.ThemeMode
import com.example.domain.ports.ui.ThemeSettingsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ============================================================================
 * THEME SETTINGS — DataStore Preferences adapter (the ONLY theme store)
 * ============================================================================
 *
 * Persists exactly ONE key — `theme_mode` — whose value domain is the
 * [ThemeMode] name. Nothing else is stored: no resolved dark/light boolean
 * (the shell resolves SYSTEM against the live system setting at composition
 * time), no UI state. A value this version cannot understand loads as the
 * honest default [ThemeMode.SYSTEM] (never crashes the shell over an
 * appearance policy).
 *
 * The adapter owns its [DataStore] instance directly (file-scoped, not the
 * Context delegate singleton) so tests run over isolated files — the
 * AppLockSettingsDataStore precedent applied to appearance.
 */
class ThemeSettingsDataStore(
    dataStoreFile: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : ThemeSettingsPort {

    internal val dataStore: DataStore<Preferences> =
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) {
            dataStoreFile.apply { parentFile?.mkdirs() }
        }

    override val mode: Flow<ThemeMode> = dataStore.data.map { preferences ->
        preferences[KEY_MODE]?.let { stored ->
            runCatching { ThemeMode.valueOf(stored) }.getOrNull()
        } ?: ThemeMode.SYSTEM
    }

    override suspend fun loadMode(): ThemeMode = withContext(Dispatchers.IO) {
        runCatching { mode.first() }.getOrDefault(ThemeMode.SYSTEM)
    }

    override suspend fun saveMode(mode: ThemeMode) {
        withContext(Dispatchers.IO) {
            dataStore.edit { preferences ->
                preferences[KEY_MODE] = mode.name
            }
        }
    }

    companion object {
        private val KEY_MODE = stringPreferencesKey("theme_mode")

        /** The production file name (one per app, under filesDir). */
        const val FILE_NAME = "theme_settings.preferences_pb"
    }
}
