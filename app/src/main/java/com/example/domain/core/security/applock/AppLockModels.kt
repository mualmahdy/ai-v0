package com.example.domain.core.security.applock

/**
 * ============================================================================
 * APP LOCK (backend contract) — the domain-side policy vocabulary
 * ============================================================================
 *
 * SECURITY PRINCIPLE (by construction): the app lock NEVER stores a custom
 * PIN, password, biometric secret, or any authentication material of its
 * own. Authentication is delegated EXCLUSIVELY to the Android system
 * (BiometricPrompt with BIOMETRIC_STRONG | DEVICE_CREDENTIAL — see
 * [AppLockAllowedAuthenticators]); this model describes only WHEN the app
 * demands that system authentication, never HOW the user is verified.
 *
 * The model is deliberately pure Kotlin (no Android types) so the policy
 * semantics are testable on the JVM and reusable from a future Settings UI.
 */

/**
 * The lock scheduling policy.
 *
 *  - [OFF]: no lock — the app never demands authentication.
 *  - [IMMEDIATE]: leaving and returning to the app demands authentication
 *    (the classic banking-app behavior).
 *  - [AFTER_TIMEOUT]: returning BEFORE the timeout has elapsed keeps the
 *    app unlocked; returning AT/AFTER it demands authentication.
 */
enum class AppLockMode { OFF, IMMEDIATE, AFTER_TIMEOUT }

/**
 * The re-lock timeout for [AppLockMode.AFTER_TIMEOUT] (the exact catalogue
 * the product contract fixes: 30s / 1m / 5m / 15m; [NONE] is meaningful
 * only while the lock is off or the mode is not timeout-based).
 */
enum class AppLockTimeout(val durationMs: Long) {
    NONE(0L),
    S30(30_000L),
    M1(60_000L),
    M5(300_000L),
    M15(900_000L)
}

/**
 * The ONLY authenticator class this product accepts: the Android system's
 * STRONG biometrics (Class 3 fingerprint/face) OR the device credential
 * (PIN/pattern/password). Weak biometrics (Class 2/3-less) are deliberately
 * NOT acceptable. The platform adapter maps this to
 * `BiometricManager.Authenticators.BIOMETRIC_STRONG or DEVICE_CREDENTIAL`.
 */
enum class AppLockAllowedAuthenticators { BIOMETRIC_STRONG_OR_DEVICE_CREDENTIAL }

/**
 * The complete, persisted configuration of the app lock. `enabled` is a
 * DERIVED invariant of [mode] (a desynced "enabled but OFF" state cannot
 * exist by construction); only policy/configuration is ever persisted —
 * never authentication state and never credentials.
 */
data class AppLockPolicy(
    val mode: AppLockMode = AppLockMode.OFF,
    val timeout: AppLockTimeout = AppLockTimeout.NONE,
    val allowedAuthenticators: AppLockAllowedAuthenticators =
        AppLockAllowedAuthenticators.BIOMETRIC_STRONG_OR_DEVICE_CREDENTIAL
) {
    /** The lock is active iff the mode is not OFF (derived — never stored). */
    val enabled: Boolean get() = mode != AppLockMode.OFF

    /** The canonical disabled policy (also the honest default before the
     *  user configures anything — the lock ships OFF). */
    fun isDisabled(): Boolean = !enabled

    companion object {
        val DISABLED = AppLockPolicy()
    }
}

/** The app-lock state machine's states (the service owns the transitions). */
enum class AppLockState {
    /** Open — the app content is visible. */
    UNLOCKED,

    /**
     * The app has gone to the background while unlocked (armed): returning
     * decides by the policy whether authentication is demanded.
     */
    LOCK_PENDING,

    /** Locked — the app content is hidden until system authentication succeeds. */
    AUTHENTICATION_REQUIRED,

    /** A system authentication prompt is in flight. */
    AUTHENTICATING
}

/** The outcome of one system-authentication attempt (never a stored secret). */
enum class AppLockAuthOutcome { SUCCESS, FAILED, CANCELLED }
