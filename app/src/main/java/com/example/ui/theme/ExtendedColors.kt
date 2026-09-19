package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ============================================================================
 * ExtendedColors — semantic color TOKENS beyond the M3 colorScheme
 * (UI Design Closure: D-11)
 * ============================================================================
 *
 * The M3 colorScheme carries error/tertiary for failure/degraded states, but
 * the app has honest semantics with NO scheme slot: SUCCESS (a step/action
 * completed), SUCCESS_STRONG (the terminal "task completed" accent) and
 * TELEMETRY (the engine-feedback/observation accent). Before this file those
 * meanings were STABBED as raw hex colors inside a feature component
 * (StudioComponents: 0xFF2E7D32 / 0xFF00897B / 0xFF1B5E20) — a violation of
 * the token rule ("no feature component hardcodes hex").
 *
 * These tokens centralize the exact values that were already in use, and are
 * provided through [LocalExtendedColors] from [MyApplicationTheme] — the
 * single provision point, mirroring how the colorScheme itself is provided.
 */
@Immutable
data class ExtendedColors(
    /** A completed action/step (was hardcoded 0xFF2E7D32). */
    val success: Color = Color(0xFF2E7D32),
    /** Terminal completion accent (was hardcoded 0xFF1B5E20). */
    val successStrong: Color = Color(0xFF1B5E20),
    /** Engine feedback / observation accent (was hardcoded 0xFF00897B). */
    val telemetry: Color = Color(0xFF00897B)
)

val LocalExtendedColors = staticCompositionLocalOf { ExtendedColors() }
