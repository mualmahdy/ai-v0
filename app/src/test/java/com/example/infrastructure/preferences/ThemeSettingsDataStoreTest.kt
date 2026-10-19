package com.example.infrastructure.preferences

import com.example.domain.core.ui.ThemeMode
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * ThemeSettingsDataStoreTest — STAGE 7 (the REAL appearance setting)
 * ============================================================================
 *
 * The persistence contract behind the Settings appearance category:
 *  - the honest default is SYSTEM (nothing was ever stored);
 *  - a save→load round trip persists exactly the policy;
 *  - the live flow projects the same policy;
 *  - an unreadable/foreign store loads as SYSTEM (never crashes the shell
 *    over an appearance policy) — the AppLockSettingsDataStore precedent.
 */
class ThemeSettingsDataStoreTest {

    private lateinit var tempDir: File
    private lateinit var store: ThemeSettingsDataStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("theme_settings_test").toFile()
        store = ThemeSettingsDataStore(File(tempDir, "theme.preferences_pb"))
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `the honest default is SYSTEM when nothing was stored`() = runBlocking {
        assertEquals(ThemeMode.SYSTEM, store.loadMode())
    }

    @Test
    fun `a save-load round trip persists exactly the policy`() = runBlocking {
        store.saveMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, store.loadMode())

        store.saveMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, store.loadMode())
    }

    @Test
    fun `the live flow projects the persisted policy`() = runBlocking {
        store.saveMode(ThemeMode.DARK)
        val mode = store.mode.first()
        assertEquals(ThemeMode.DARK, mode)
    }

    @Test
    fun `a fresh store over a foreign file loads as SYSTEM`() = runBlocking {
        // A file this version cannot understand (garbage bytes).
        val foreignFile = File(tempDir, "foreign.preferences_pb").apply {
            writeText("not-a-protobuf-store")
        }
        val foreignStore = ThemeSettingsDataStore(foreignFile)
        assertEquals(ThemeMode.SYSTEM, foreignStore.loadMode())
    }
}
