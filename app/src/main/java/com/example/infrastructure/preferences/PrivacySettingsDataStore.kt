package com.example.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.example.domain.ports.ui.PrivacySettingsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ============================================================================
 * PRIVACY SETTINGS — DataStore Preferences adapter (the ONLY screenshot
 * policy store)
 * ============================================================================
 *
 * Persists exactly ONE key — `screenshot_protection` — whose absence means
 * ENABLED (secure by default: an unreadable store must never silently turn
 * a protection off). Nothing else is stored: no resolved window flags, no
 * UI state — the shell derives the FLAG_SECURE application from the policy
 * at activity level, exactly as [ThemeSettingsDataStore] precedents the
 * theme policy.
 *
 * The adapter owns its [DataStore] instance directly (file-scoped, not the
 * Context delegate singleton) so tests run over isolated files.
 */
class PrivacySettingsDataStore(
    dataStoreFile: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : PrivacySettingsPort {

    internal val dataStore: DataStore<Preferences> =
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) {
            dataStoreFile.apply { parentFile?.mkdirs() }
        }

    override val screenshotProtection: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_SCREENSHOT_PROTECTION] ?: DEFAULT_SCREENSHOT_PROTECTION
    }

    override suspend fun loadScreenshotProtection(): Boolean = withContext(Dispatchers.IO) {
        runCatching { screenshotProtection.first() }
            .getOrDefault(DEFAULT_SCREENSHOT_PROTECTION)
    }

    override suspend fun saveScreenshotProtection(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            dataStore.edit { preferences ->
                preferences[KEY_SCREENSHOT_PROTECTION] = enabled
            }
        }
    }

    companion object {
        private val KEY_SCREENSHOT_PROTECTION = booleanPreferencesKey("screenshot_protection")

        /** SECURE BY DEFAULT — never the reverse. */
        const val DEFAULT_SCREENSHOT_PROTECTION = true

        /** The production file name (one per app, under filesDir). */
        const val FILE_NAME = "privacy_settings.preferences_pb"
    }
}
