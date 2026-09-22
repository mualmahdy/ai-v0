package com.example.application.applock

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.example.application.audit.AuditTrailService
import com.example.domain.core.audit.AuditActorType
import com.example.domain.core.audit.AuditResult
import com.example.domain.core.context.ResourceScope
import com.example.domain.core.security.applock.AppLockAuthOutcome
import com.example.domain.core.security.applock.AppLockMode
import com.example.domain.core.security.applock.AppLockPolicy
import com.example.domain.core.security.applock.AppLockState
import com.example.domain.ports.security.AppLockSettingsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * APP LOCK SERVICE — the policy/state machine (NOT the platform prompt)
 * ============================================================================
 *
 * Owns the complete app-lock behavior; MainActivity (or any shell) stays a
 * THIN adapter that forwards lifecycle transitions and shows the system
 * prompt through an [AppLockAuthenticator]. No timeout arithmetic, policy
 * logic, or security decision lives in the UI layer.
 *
 * State machine:
 *
 *   UNLOCKED → LOCK_PENDING → AUTHENTICATION_REQUIRED → AUTHENTICATING → UNLOCKED
 *     (background)      (foreground past       (prompt launched)   (system auth
 *                        the threshold)                             succeeded)
 *
 *  - LOCK_PENDING → UNLOCKED: the user returned BEFORE the timeout elapsed.
 *  - AUTHENTICATING → AUTHENTICATION_REQUIRED: failure or cancellation (no
 *    bypass — the lock stays until a REAL system authentication succeeds).
 *
 * SECURITY INVARIANTS:
 *  - PROCESS DEATH IS NOT AUTHENTICATION: a fresh service instance under an
 *    enabled policy starts AUTHENTICATION_REQUIRED — in-memory unlocked
 *    state is never trusted across processes (and nothing of the sort is
 *    persisted; only the policy is).
 *  - The service stores NO secrets: authentication itself is delegated
 *    exclusively to the Android system (BIOMETRIC_STRONG |
 *    DEVICE_CREDENTIAL) via the platform adapter.
 *  - Failure/cancel of an authentication attempt never unlocks anything.
 *
 * All transitions are audited through the EXISTING unified audit trail
 * (LOCK_ENABLED / LOCK_DISABLED / LOCK_TRIGGERED / AUTH_STARTED /
 * AUTH_SUCCESS / AUTH_FAILED / AUTH_CANCELLED — no biometric data, no
 * secrets; the audit writes are fire-and-forget and can never break the
 * state machine).
 */
class AppLockService(
    private val settings: AppLockSettingsPort,
    private val auditTrail: AuditTrailService? = null,
    /** Deterministic time source for tests (production: wall clock). */
    private val clock: () -> Long = System::currentTimeMillis,
    /** The actor identity that lands in audit events. */
    private val actorId: String = "local-device-user",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val lock = Any()

    /** The current in-memory policy (replaced only through [initialize]/[setPolicy]). */
    @Volatile
    private var currentPolicy: AppLockPolicy = AppLockPolicy.DISABLED

    /** When the app went to the background under an enabled policy (null = foregrounded). */
    @Volatile
    private var backgroundedAtMs: Long? = null

    /** Whether a system authentication succeeded in THIS process (process death resets it). */
    @Volatile
    private var authenticatedThisProcess: Boolean = false

    /** Whether [initialize] has completed (the policy is proven, not assumed). */
    private val _initialized = MutableStateFlow(false)

    /**
     * Whether the persisted policy has been loaded. The machine starts
     * LOCKED and UNPROVEN: until initialization completes, the shell keeps
     * the cover WITHOUT prompting (fail-closed against the cold-start race
     * where content would otherwise render before the enabled policy loads).
     */
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    // Fail-closed initial state: LOCKED until the persisted policy PROVES
    // otherwise (see [initialized]).
    private val _state = MutableStateFlow(AppLockState.AUTHENTICATION_REQUIRED)

    /** The live lock state (the shell renders its lock cover from this). */
    val currentState: StateFlow<AppLockState> = _state.asStateFlow()

    /** The active policy (read-only snapshot for the UI/settings surface). */
    val policy: AppLockPolicy get() = currentPolicy

    // ------------------------------------------------------------------
    // PROCESS START
    // ------------------------------------------------------------------

    /**
     * ONE-TIME initialization at process start: loads the persisted policy
     * and applies the PROCESS-DEATH invariant — with the lock enabled, a
     * fresh process is AUTHENTICATION_REQUIRED (fail-closed; an in-memory
     * unlocked state from a previous process is never trusted and nothing
     * of the sort is persisted). With the lock disabled the machine unlocks
     * (the cover was only the fail-closed pre-policy state).
     */
    suspend fun initialize() {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { settings.loadPolicy() }.getOrDefault(AppLockPolicy.DISABLED)
        }
        synchronized(lock) {
            currentPolicy = loaded
            backgroundedAtMs = null
            authenticatedThisProcess = false
            _state.value = if (loaded.enabled) AppLockState.AUTHENTICATION_REQUIRED
            else AppLockState.UNLOCKED
            _initialized.value = true
        }
        if (loaded.enabled) {
            audit(
                com.example.domain.core.audit.AuditActions.APP_LOCK_TRIGGERED,
                AuditResult.DENIED,
                reason = "process start under enabled app-lock policy (process death is not authentication)"
            )
        }
    }

    // ------------------------------------------------------------------
    // LIFECYCLE
    // ------------------------------------------------------------------

    /**
     * The app went to the background. Under an enabled policy the machine
     * arms (UNLOCKED → LOCK_PENDING) and records the background timestamp
     * the timeout decision will use; with the lock off this is a no-op.
     */
    fun onAppBackgrounded(nowMs: Long = clock()) {
        synchronized(lock) {
            if (!currentPolicy.enabled) return
            backgroundedAtMs = nowMs
            if (_state.value == AppLockState.UNLOCKED) {
                _state.value = AppLockState.LOCK_PENDING
            }
        }
    }

    /**
     * The app returned to the foreground. Under an enabled policy:
     * LOCK_PENDING locks when the absence reached the policy threshold
     * (IMMEDIATE = any absence; AFTER_TIMEOUT = elapsed >= timeout);
     * otherwise it returns UNLOCKED. Locked states stay locked.
     */
    fun onAppForegrounded(nowMs: Long = clock()) {
        val shouldLockNow: Boolean
        synchronized(lock) {
            if (!currentPolicy.enabled) {
                _state.value = AppLockState.UNLOCKED
                return
            }
            if (_state.value != AppLockState.LOCK_PENDING) return
            shouldLockNow = shouldLockLocked(nowMs)
            _state.value =
                if (shouldLockNow) AppLockState.AUTHENTICATION_REQUIRED
                else AppLockState.UNLOCKED
            backgroundedAtMs = null
        }
        if (shouldLockNow) {
            audit(
                com.example.domain.core.audit.AuditActions.APP_LOCK_TRIGGERED,
                AuditResult.DENIED,
                reason = "foregrounded past the policy threshold (mode=${currentPolicy.mode})"
            )
        }
    }

    /**
     * Whether an app foregrounded NOW would have to authenticate (the
     * policy-decision predicate, exposed for tests and honest UI states).
     */
    fun shouldLock(nowMs: Long = clock()): Boolean = synchronized(lock) {
        shouldLockLocked(nowMs)
    }

    private fun shouldLockLocked(nowMs: Long): Boolean {
        val policy = currentPolicy
        if (!policy.enabled) return false
        val backgroundedAt = backgroundedAtMs ?: return false
        return when (policy.mode) {
            AppLockMode.OFF -> false
            AppLockMode.IMMEDIATE -> true
            AppLockMode.AFTER_TIMEOUT -> nowMs - backgroundedAt >= policy.timeout.durationMs
        }
    }

    // ------------------------------------------------------------------
    // AUTHENTICATION
    // ------------------------------------------------------------------

    /**
     * The system authentication prompt is being shown (AUTHENTICATION_
     * REQUIRED → AUTHENTICATING). Returns false honestly when no prompt is
     * demanded right now (already open, already authenticating, or the lock
     * is off) — the shell must not spawn prompts the state did not ask for.
     */
    fun beginAuthentication(): Boolean {
        val started: Boolean = synchronized(lock) {
            if (_state.value != AppLockState.AUTHENTICATION_REQUIRED) return false
            _state.value = AppLockState.AUTHENTICATING
            true
        }
        if (started) {
            audit(
                com.example.domain.core.audit.AuditActions.APP_LOCK_AUTH_STARTED,
                AuditResult.SUCCESS,
                reason = "system authentication prompt (BIOMETRIC_STRONG | DEVICE_CREDENTIAL)"
            )
        }
        return started
    }

    /** System authentication SUCCEEDED → UNLOCKED (the timeout window restarts). */
    fun markAuthenticated() {
        synchronized(lock) {
            authenticatedThisProcess = true
            backgroundedAtMs = null
            _state.value = AppLockState.UNLOCKED
        }
        audit(
            com.example.domain.core.audit.AuditActions.APP_LOCK_AUTH_SUCCESS,
            AuditResult.SUCCESS,
            reason = "Android system authentication succeeded"
        )
    }

    /** System authentication FAILED → still AUTHENTICATION_REQUIRED (no bypass). */
    fun onAuthenticationFailed() {
        synchronized(lock) { _state.value = AppLockState.AUTHENTICATION_REQUIRED }
        audit(
            com.example.domain.core.audit.AuditActions.APP_LOCK_AUTH_FAILED,
            AuditResult.FAILURE,
            reason = "Android system authentication failed"
        )
    }

    /** The user CANCELLED the prompt → still AUTHENTICATION_REQUIRED (no bypass). */
    fun onAuthenticationCancelled() {
        synchronized(lock) { _state.value = AppLockState.AUTHENTICATION_REQUIRED }
        audit(
            com.example.domain.core.audit.AuditActions.APP_LOCK_AUTH_CANCELLED,
            AuditResult.DENIED,
            reason = "user dismissed the system authentication prompt"
        )
    }

    /** Applies a platform outcome ([AppLockAuthOutcome]) to the machine. */
    fun onAuthenticationResult(outcome: AppLockAuthOutcome) {
        when (outcome) {
            AppLockAuthOutcome.SUCCESS -> markAuthenticated()
            AppLockAuthOutcome.FAILED -> onAuthenticationFailed()
            AppLockAuthOutcome.CANCELLED -> onAuthenticationCancelled()
        }
    }

    /**
     * INVALIDATION: the app becomes UNLOCKED → AUTHENTICATION_REQUIRED
     * (a security event withdrew the current unlocked session; the next
     * app use demands system authentication again).
     */
    fun invalidateAuthentication() {
        synchronized(lock) {
            if (!currentPolicy.enabled) return
            _state.value = AppLockState.AUTHENTICATION_REQUIRED
        }
        audit(
            com.example.domain.core.audit.AuditActions.APP_LOCK_TRIGGERED,
            AuditResult.DENIED,
            reason = "authentication invalidated"
        )
    }

    // ------------------------------------------------------------------
    // POLICY
    // ------------------------------------------------------------------

    /**
     * Replaces (and persists) the lock policy.
     *
     *  - Lock OFF: the app unlocks immediately (an explicit user decision
     *    recorded as LOCK_DISABLED).
     *  - Lock ON while the app is currently unlocked WITHOUT a successful
     *    system authentication in this process: the machine goes
     *    AUTHENTICATION_REQUIRED immediately (fail-closed — the session that
     *    started before the lock existed is not an authenticated session).
     */
    suspend fun setPolicy(newPolicy: AppLockPolicy) {
        withContext(Dispatchers.IO) {
            runCatching { settings.savePolicy(newPolicy) }
        }
        val wasEnabled: Boolean
        val lockNow: Boolean
        synchronized(lock) {
            wasEnabled = currentPolicy.enabled
            currentPolicy = newPolicy
            if (!newPolicy.enabled) {
                backgroundedAtMs = null
                _state.value = AppLockState.UNLOCKED
                lockNow = false
            } else {
                lockNow = _state.value == AppLockState.UNLOCKED && !authenticatedThisProcess
                if (lockNow) {
                    _state.value = AppLockState.AUTHENTICATION_REQUIRED
                }
            }
        }
        val enabledNow = newPolicy.enabled
        if (enabledNow != wasEnabled) {
            audit(
                if (enabledNow) com.example.domain.core.audit.AuditActions.APP_LOCK_ENABLED
                else com.example.domain.core.audit.AuditActions.APP_LOCK_DISABLED,
                AuditResult.SUCCESS,
                reason = "policy set to mode=${newPolicy.mode} timeout=${newPolicy.timeout}"
            )
        }
        if (lockNow) {
            audit(
                com.example.domain.core.audit.AuditActions.APP_LOCK_TRIGGERED,
                AuditResult.DENIED,
                reason = "lock enabled while the process session was not authenticated"
            )
        }
    }

    // ------------------------------------------------------------------
    // Audit (existing unified trail; fire-and-forget, secret-free)
    // ------------------------------------------------------------------

    private fun audit(action: String, result: AuditResult, reason: String) {
        val trail = auditTrail ?: return
        scope.launch {
            runCatching {
                trail.record(
                    actorType = AuditActorType.USER,
                    actorId = actorId,
                    action = action,
                    resourceType = "APP_LOCK",
                    resourceId = "app",
                    sourceScope = ResourceScope.Application,
                    targetScope = null,
                    policy = currentPolicy.mode.name,
                    result = result,
                    reason = reason
                )
            }
        }
    }
}

/**
 * ============================================================================
 * APP LOCK AUTHENTICATOR — the platform-prompt contract
 * ============================================================================
 *
 * The Android-specific implementation (BiometricPrompt) lives behind this
 * seam in infrastructure so the state machine and its tests never touch
 * Android UI classes. Implementors MUST use Android SYSTEM authentication
 * (BIOMETRIC_STRONG | DEVICE_CREDENTIAL) — no custom PIN UI, no app-owned
 * secrets — and MUST NEVER report SUCCESS for hardware/enrollment/cancel
 * conditions (fail-closed: those are NOT unlocks).
 */
interface AppLockAuthenticator {

    /** Whether the device can authenticate with the allowed authenticators. */
    fun checkAvailability(context: Context): AppLockAuthAvailability

    /**
     * Shows the SYSTEM authentication prompt. [onOutcome] receives exactly
     * one terminal outcome (SUCCESS only after a real system
     * authentication; FAILED for authentication errors; CANCELLED for user
     * dismissal).
     */
    fun launchPrompt(
        activity: FragmentActivity,
        onOutcome: (AppLockAuthOutcome) -> Unit
    )
}

/** The honest availability answers an adapter can give (none is an unlock). */
enum class AppLockAuthAvailability {
    /** Strong biometrics and/or device credential can authenticate now. */
    READY,
    /** No authenticator is enrolled (no biometric, no device credential). */
    NONE_ENROLLED,
    /** The device has no matching hardware. */
    NO_HARDWARE,
    /** The hardware is currently unavailable (busy/locked out). */
    HARDWARE_UNAVAILABLE,
    /** The combination is unsupported on this device/version. */
    UNSUPPORTED
}
