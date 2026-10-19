package com.example.domain.ports.ui

import com.example.domain.core.ui.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * THEME SETTINGS PORT — the appearance persistence seam
 * ============================================================================
 *
 * The Settings surface reads and writes ONLY the theme POLICY
 * ([ThemeMode]); the shell resolves it against the live system setting at
 * composition time. Implementations persist exactly one key and nothing
 * else (no resolved booleans, no UI state) — the AppLockSettingsPort
 * precedent applied to appearance.
 */
interface ThemeSettingsPort {

    /** The persisted theme policy (live; SYSTEM when nothing was stored). */
    val mode: Flow<ThemeMode>

    /** One-shot load (the honest default SYSTEM on any unreadable store). */
    suspend fun loadMode(): ThemeMode

    /** Persists the policy. A failed write surfaces to the caller. */
    suspend fun saveMode(mode: ThemeMode)
}
