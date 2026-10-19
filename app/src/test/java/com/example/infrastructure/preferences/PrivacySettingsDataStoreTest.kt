package com.example.infrastructure.preferences

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * PrivacySettingsDataStoreTest — STAGE 7 user round 3 (the REAL
 * screenshot-protection preference)
 * ============================================================================
 *
 * The persistence contract behind the Settings privacy toggle:
 *  - the honest default is ENABLED (secure by default — an unreadable or
 *    empty store must never silently disable a protection that was on);
 *  - a save→load round trip persists exactly the policy;
 *  - the live flow projects the same policy (the shell's reactive flag
 *    application reads this);
 *  - an unreadable/foreign store loads as ENABLED (never crashes the
 *    shell over a privacy policy, never turns the protection off).
 */
class PrivacySettingsDataStoreTest {

    private lateinit var tempDir: File
    private lateinit var store: PrivacySettingsDataStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("privacy_settings_test").toFile()
        store = PrivacySettingsDataStore(File(tempDir, "privacy.preferences_pb"))
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `the honest default is ENABLED - secure by default`() = runBlocking {
        assertTrue(store.loadScreenshotProtection())
    }

    @Test
    fun `a save-load round trip persists exactly the policy`() = runBlocking {
        store.saveScreenshotProtection(false)
        assertFalse(store.loadScreenshotProtection())

        store.saveScreenshotProtection(true)
        assertTrue(store.loadScreenshotProtection())
    }

    @Test
    fun `the live flow projects the persisted policy`() = runBlocking {
        store.saveScreenshotProtection(false)
        assertFalse(store.screenshotProtection.first())

        store.saveScreenshotProtection(true)
        assertTrue(store.screenshotProtection.first())
    }

    @Test
    fun `a fresh store over a foreign file loads as ENABLED`() = runBlocking {
        // A file this version cannot understand (garbage bytes).
        val foreignFile = File(tempDir, "foreign.preferences_pb").apply {
            writeText("not-a-protobuf-store")
        }
        val foreignStore = PrivacySettingsDataStore(foreignFile)
        assertTrue(
            "an unreadable store must keep the protection ON",
            foreignStore.loadScreenshotProtection()
        )
    }

    @Test
    fun `the declared default constant is secure`() {
        assertEquals(true, PrivacySettingsDataStore.DEFAULT_SCREENSHOT_PROTECTION)
    }
}
