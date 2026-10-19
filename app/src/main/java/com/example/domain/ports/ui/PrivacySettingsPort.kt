package com.example.domain.ports.ui

import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * PRIVACY SETTINGS PORT — the screenshot-protection persistence seam
 * ============================================================================
 *
 * STAGE 7 (user round 3): the screen-capture privacy policy
 * (FLAG_SECURE) becomes a REAL user preference instead of a hardcoded
 * window flag. The Settings surface reads and writes ONLY the POLICY
 * here; the shell (the Activity) owns applying the flag to the window —
 * the same value+lambda ownership split as [ThemeSettingsPort].
 *
 * SECURE BY DEFAULT: the honest default is ENABLED — an unreadable or
 * empty store must never silently disable a protection that was on.
 */
interface PrivacySettingsPort {

    /**
     * The persisted screenshot-protection policy (live; TRUE when nothing
     * was stored — secure by default, never the reverse).
     */
    val screenshotProtection: Flow<Boolean>

    /** One-shot load (the honest default TRUE on any unreadable store). */
    suspend fun loadScreenshotProtection(): Boolean

    /** Persists the policy. A failed write surfaces to the caller. */
    suspend fun saveScreenshotProtection(enabled: Boolean)
}
