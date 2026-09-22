package com.example.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.domain.core.security.applock.AppLockMode
import com.example.domain.core.security.applock.AppLockPolicy
import com.example.domain.core.security.applock.AppLockTimeout
import com.example.domain.ports.security.AppLockSettingsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ============================================================================
 * APP LOCK SETTINGS — DataStore Preferences adapter (the ONLY app-lock store)
 * ============================================================================
 *
 * Persists exactly the POLICY keys the product contract fixes —
 * `app_lock_enabled`, `app_lock_mode`, `app_lock_timeout` — and NOTHING
 * else: no authentication state, no credentials, no biometric material
 * (the app performs no authentication of its own; the Android system does).
 *
 * The adapter owns its [DataStore] instance directly (file-scoped, not the
 * Context delegate singleton) so tests can run over isolated files and a
 * fresh process NEVER inherits a stale unlocked state — there is no
 * unlocked state on disk to inherit, by construction.
 *
 * A stored shape this version cannot understand loads as the canonical
 * DISABLED policy (fail-open on policy, never on authentication) — and the
 * version is carried in the mode key's value domain, so future migrations
 * have an honest upgrade path.
 */
class AppLockSettingsDataStore(
    dataStoreFile: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : AppLockSettingsPort {

    internal val dataStore: DataStore<Preferences> =
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) {
            dataStoreFile.apply { parentFile?.mkdirs() }
        }

    override suspend fun loadPolicy(): AppLockPolicy = withContext(Dispatchers.IO) {
        val preferences = runCatching { dataStore.data.first() }.getOrNull()
            ?: return@withContext AppLockPolicy.DISABLED
        val enabled = preferences[KEY_ENABLED] ?: false
        val mode = preferences[KEY_MODE]?.let { stored ->
            runCatching { AppLockMode.valueOf(stored) }.getOrNull()
        }
        val timeout = preferences[KEY_TIMEOUT]?.let { stored ->
            runCatching { AppLockTimeout.valueOf(stored) }.getOrNull()
        }
        // Reconcile the two honest shapes: the canonical form stores the mode
        // itself (OFF/IMMEDIATE/AFTER_TIMEOUT); a legacy/foreign shape that
        // only carries the boolean degrades to IMMEDIATE-when-enabled (the
        // fail-closed default of the catalogue) — never to a silent OFF when
        // the user had enabled the lock.
        when {
            mode != null -> AppLockPolicy(
                mode = mode,
                // A timeout mode without a usable timeout falls back to the
                // shortest catalogue entry (fail-closed: lock SOONER, not later).
                timeout = if (mode == AppLockMode.AFTER_TIMEOUT && (timeout == null || timeout == AppLockTimeout.NONE)) {
                    AppLockTimeout.S30
                } else {
                    timeout ?: AppLockTimeout.NONE
                }
            )
            enabled -> AppLockPolicy(mode = AppLockMode.IMMEDIATE, timeout = AppLockTimeout.NONE)
            else -> AppLockPolicy.DISABLED
        }
    }

    override suspend fun savePolicy(policy: AppLockPolicy) {
        withContext(Dispatchers.IO) {
            runCatching {
                dataStore.edit { preferences ->
                    preferences[KEY_ENABLED] = policy.enabled
                    preferences[KEY_MODE] = policy.mode.name
                    preferences[KEY_TIMEOUT] = policy.timeout.name
                }
            }
        }
    }

    companion object {
        /** Policy/configuration ONLY — the complete key set (see the class KDoc). */
        internal val KEY_ENABLED = booleanPreferencesKey("app_lock_enabled")
        internal val KEY_MODE = stringPreferencesKey("app_lock_mode")
        internal val KEY_TIMEOUT = stringPreferencesKey("app_lock_timeout")

        /** The production store file name (under filesDir/applock). */
        const val FILE_NAME = "app_lock_settings.preferences_pb"
    }
}
