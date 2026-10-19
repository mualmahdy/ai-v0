package com.example.domain.core.ui

/**
 * ============================================================================
 * THEME MODE (domain vocabulary) — the appearance policy vocabulary
 * ============================================================================
 *
 * A pure-Kotlin appearance policy (no Android types) so the theme decision
 * is testable on the JVM and reusable from the Settings surface, following
 * the [com.example.domain.core.security.applock.AppLockModels] precedent.
 *
 * Only the POLICY is ever persisted — never a resolved dark/light boolean
 * (the resolved value is derived at composition time from the policy +
 * the live system setting, so a system dark-mode change applies instantly).
 */
enum class ThemeMode {
    /** Follow the system dark-mode setting (the honest default). */
    SYSTEM,

    /** Always light, regardless of the system setting. */
    LIGHT,

    /** Always dark, regardless of the system setting. */
    DARK
}
