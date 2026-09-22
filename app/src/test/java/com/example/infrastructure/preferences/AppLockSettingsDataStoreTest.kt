package com.example.infrastructure.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.security.applock.AppLockMode
import com.example.domain.core.security.applock.AppLockPolicy
import com.example.domain.core.security.applock.AppLockTimeout
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ============================================================================
 * AppLockSettingsDataStoreTest — the REAL persistence adapter (Robolectric)
 * ============================================================================
 *
 *  1. save/load round trip restores the exact policy.
 *  2. A NEW adapter instance over the same file restores the same policy
 *     (real durable persistence — not an in-memory artifact).
 *  3. SECURITY INVARIANT: the persisted preference keys are EXACTLY the
 *     three POLICY keys (app_lock_enabled / app_lock_mode /
 *     app_lock_timeout) — no credential, PIN, biometric, or token key can
 *     exist in the store (the app performs no authentication of its own).
 */
@RunWith(RobolectricTestRunner::class)
class AppLockSettingsDataStoreTest {

    private lateinit var context: Context
    private lateinit var file: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        file = File.createTempFile("applock_settings_", ".preferences_pb")
    }

    @After
    fun teardown() {
        file.delete()
    }

    @Test
    fun `save and load round trip restore the exact policy`() = runBlocking {
        val store = AppLockSettingsDataStore(dataStoreFile = file)
        val policy = AppLockPolicy(mode = AppLockMode.AFTER_TIMEOUT, timeout = AppLockTimeout.M5)
        store.savePolicy(policy)
        assertEquals(policy, store.loadPolicy())
    }

    @Test
    fun `a fresh adapter instance over the same file restores the policy (durability)`() = runBlocking {
        // "Process death": the first store's scope is CANCELLED before the
        // second instance is created (DataStore requires one active instance
        // per file at a time — the same discipline AppContainer follows in
        // production with its single applicationScope-owned store).
        val firstScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        val first = AppLockSettingsDataStore(dataStoreFile = file, scope = firstScope)
        val policy = AppLockPolicy(mode = AppLockMode.IMMEDIATE, timeout = AppLockTimeout.NONE)
        first.savePolicy(policy)
        firstScope.cancel()

        val second = AppLockSettingsDataStore(dataStoreFile = file)
        assertEquals(policy, second.loadPolicy())
    }

    @Test
    fun `an absent store loads the canonical DISABLED policy`() = runBlocking {
        val store = AppLockSettingsDataStore(dataStoreFile = file)
        assertEquals(AppLockPolicy.DISABLED, store.loadPolicy())
    }

    @Test
    fun `the persisted key set is EXACTLY the three policy keys (no credential surface)`() = runBlocking {
        val store = AppLockSettingsDataStore(dataStoreFile = file)
        store.savePolicy(AppLockPolicy(mode = AppLockMode.AFTER_TIMEOUT, timeout = AppLockTimeout.M15))

        val preferences = store.dataStore.data.first()
        val keys = preferences.asMap().keys.map { it.name }.toSet()

        assertEquals(
            "the app-lock store may contain ONLY the policy keys — a PIN/secret/biometric key cannot exist here",
            setOf("app_lock_enabled", "app_lock_mode", "app_lock_timeout"),
            keys
        )
        // And the values are plain policy vocabulary (no material to leak).
        val values = preferences.asMap().values.map { it.toString() }.toSet()
        assertTrue(values.contains("AFTER_TIMEOUT"))
        assertTrue(values.contains("M15"))
    }

    @Test
    fun `a timeout policy without a usable timeout degrades to the shortest entry (fail-closed)`() = runBlocking {
        // Simulate a stored shape that claims AFTER_TIMEOUT with NONE.
        val store = AppLockSettingsDataStore(dataStoreFile = file)
        store.dataStore.edit { preferences ->
            preferences[AppLockSettingsDataStore.KEY_ENABLED] = true
            preferences[AppLockSettingsDataStore.KEY_MODE] = "AFTER_TIMEOUT"
            preferences[AppLockSettingsDataStore.KEY_TIMEOUT] = "NONE"
        }
        val loaded = store.loadPolicy()
        assertEquals(AppLockMode.AFTER_TIMEOUT, loaded.mode)
        assertEquals(
            "an unusable timeout locks SOONER, not later",
            AppLockTimeout.S30,
            loaded.timeout
        )
    }
}
