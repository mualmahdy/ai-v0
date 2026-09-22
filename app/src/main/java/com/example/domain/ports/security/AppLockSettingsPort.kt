package com.example.domain.ports.security

import com.example.domain.core.security.applock.AppLockPolicy

/**
 * ============================================================================
 * APP LOCK SETTINGS — domain-owned persistence port
 * ============================================================================
 *
 * Persists ONLY the lock POLICY/configuration (mode + timeout — see
 * [AppLockPolicy]). It MUST NEVER persist authentication state and MUST
 * NEVER persist credentials or biometric material (the app performs no
 * authentication of its own; the Android system does — that is the security
 * contract of the whole app-lock backend).
 *
 * Semantics contract for implementors:
 *  - [loadPolicy] returns the last saved policy, or the canonical
 *    [AppLockPolicy.DISABLED] when nothing was ever saved (or the stored
 *    shape is not understandable — fail-open on POLICY, never on AUTH:
 *    an unreadable policy disables the lock rather than guessing one, but
 *    an unreadable policy can never count as an authentication).
 *  - [savePolicy] durably replaces the stored policy.
 */
interface AppLockSettingsPort {

    /** The persisted lock policy (DISABLED when absent/unreadable). */
    suspend fun loadPolicy(): AppLockPolicy = AppLockPolicy.DISABLED

    /** Durably replaces the persisted policy. */
    suspend fun savePolicy(policy: AppLockPolicy) = Unit
}
