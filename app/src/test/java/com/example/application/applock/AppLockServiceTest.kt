package com.example.application.applock

import com.example.domain.core.security.applock.AppLockAuthOutcome
import com.example.domain.core.security.applock.AppLockMode
import com.example.domain.core.security.applock.AppLockPolicy
import com.example.domain.core.security.applock.AppLockState
import com.example.domain.core.security.applock.AppLockTimeout
import com.example.domain.ports.security.AppLockSettingsPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * AppLockServiceTest — the app-lock STATE MACHINE on the JVM
 * ============================================================================
 *
 * Pure-JVM policy/lifecycle semantics with a deterministic clock and an
 * in-memory settings port (the platform BiometricPrompt adapter has its own
 * mapper tests — Robolectric has no BiometricPrompt shadows in this
 * environment, and the state machine must not depend on Android anyway).
 *
 * Covers the product contract:
 *  - OFF never locks; IMMEDIATE locks on background→foreground;
 *  - AFTER_TIMEOUT locks only at/after the timeout;
 *  - auth success → UNLOCKED; failure/cancel → AUTHENTICATION_REQUIRED
 *    (NO bypass);
 *  - PROCESS DEATH IS NOT AUTHENTICATION (a fresh service under an enabled
 *    policy starts locked, regardless of the previous process's state);
 *  - policy persistence/reload round trip;
 *  - the security invariant: the port's surface carries ONLY policy —
 *    there is no credential/PIN/biometric material anywhere in the model.
 */
class AppLockServiceTest {

    /** In-memory settings port (policy ONLY — the whole port surface). */
    private class FakeAppLockSettings : AppLockSettingsPort {
        @Volatile
        var saved: AppLockPolicy = AppLockPolicy.DISABLED

        override suspend fun loadPolicy(): AppLockPolicy = saved

        override suspend fun savePolicy(policy: AppLockPolicy) {
            saved = policy
        }
    }

    private val t0 = 1_000_000L

    private fun service(
        settings: FakeAppLockSettings = FakeAppLockSettings(),
        now: () -> Long
    ): AppLockService = AppLockService(settings = settings, auditTrail = null, clock = now)

    // ------------------------------------------------------------------
    // Policy: OFF
    // ------------------------------------------------------------------

    @Test
    fun `OFF never locks across background and foreground`() = runBlocking {
        var now = t0
        val svc = service(settings = FakeAppLockSettings().apply {
            saved = AppLockPolicy(mode = AppLockMode.OFF)
        }, now = { now })
        svc.initialize()
        assertEquals(AppLockState.UNLOCKED, svc.currentState.value)

        now += 3_600_000
        svc.onAppBackgrounded()
        now += 3_600_000
        svc.onAppForegrounded()

        assertEquals("OFF must never demand authentication", AppLockState.UNLOCKED, svc.currentState.value)
        assertFalse(svc.shouldLock())
    }

    // ------------------------------------------------------------------
    // Policy: IMMEDIATE
    // ------------------------------------------------------------------

    @Test
    fun `IMMEDIATE locks on background then foreground`() = runBlocking {
        var now = t0
        val svc = service(settings = FakeAppLockSettings().apply {
            saved = AppLockPolicy(mode = AppLockMode.IMMEDIATE)
        }, now = { now })
        svc.initialize()
        assertEquals(AppLockState.AUTHENTICATION_REQUIRED, svc.currentState.value)

        // Authenticate once — the app is now usable.
        assertTrue(svc.beginAuthentication())
        svc.onAuthenticationResult(AppLockAuthOutcome.SUCCESS)
        assertEquals(AppLockState.UNLOCKED, svc.currentState.value)

        // Any absence, however short, re-locks.
        now += 1
        svc.onAppBackgrounded()
        assertEquals(AppLockState.LOCK_PENDING, svc.currentState.value)
        now += 1
        svc.onAppForegrounded()
        assertEquals(
            "IMMEDIATE must demand authentication after any background period",
            AppLockState.AUTHENTICATION_REQUIRED,
            svc.currentState.value
        )
    }

    // ------------------------------------------------------------------
    // Policy: AFTER_TIMEOUT
    // ------------------------------------------------------------------

    @Test
    fun `AFTER_TIMEOUT returning BEFORE the timeout stays unlocked`() = runBlocking {
        var now = t0
        val svc = service(settings = FakeAppLockSettings().apply {
            saved = AppLockPolicy(mode = AppLockMode.AFTER_TIMEOUT, timeout = AppLockTimeout.M1)
        }, now = { now })
        svc.initialize()
        assertTrue(svc.beginAuthentication())
        svc.onAuthenticationResult(AppLockAuthOutcome.SUCCESS)
        assertEquals(AppLockState.UNLOCKED, svc.currentState.value)

        now += 10_000 // 10s into the absence (< 60s timeout)
        svc.onAppBackgrounded()
        now += 30_000 // 40s in absence — still < 60s
        assertFalse(svc.shouldLock())
        svc.onAppForegrounded()
        assertEquals("returning before the timeout keeps the app unlocked", AppLockState.UNLOCKED, svc.currentState.value)
    }

    @Test
    fun `AFTER_TIMEOUT returning at-or-after the timeout locks`() = runBlocking {
        var now = t0
        val svc = service(settings = FakeAppLockSettings().apply {
            saved = AppLockPolicy(mode = AppLockMode.AFTER_TIMEOUT, timeout = AppLockTimeout.S30)
        }, now = { now })
        svc.initialize()
        assertTrue(svc.beginAuthentication())
        svc.onAuthenticationResult(AppLockAuthOutcome.SUCCESS)
        assertEquals(AppLockState.UNLOCKED, svc.currentState.value)

        now += 5_000
        svc.onAppBackgrounded()
        now += 30_000 // exactly the timeout → locked (>= is the boundary)
        assertTrue(svc.shouldLock())
        svc.onAppForegrounded()
        assertEquals(
            "returning at/after the timeout demands authentication",
            AppLockState.AUTHENTICATION_REQUIRED,
            svc.currentState.value
        )
    }

    @Test
    fun `a successful authentication restarts the timeout window`() = runBlocking {
        var now = t0
        val svc = service(settings = FakeAppLockSettings().apply {
            saved = AppLockPolicy(mode = AppLockMode.AFTER_TIMEOUT, timeout = AppLockTimeout.S30)
        }, now = { now })
        svc.initialize()
        svc.beginAuthentication()
        svc.onAuthenticationResult(AppLockAuthOutcome.SUCCESS)

        // Absence of 25s (< 30) then return: unlocked.
        now += 25_000
        svc.onAppBackgrounded()
        now += 25_000
        assertFalse(svc.shouldLock())
        svc.onAppForegrounded()
        assertEquals(AppLockState.UNLOCKED, svc.currentState.value)

        // A fresh 40s absence (measured from the NEW window) locks.
        now += 40_000
        svc.onAppBackgrounded()
        now += 40_000
        assertTrue(svc.shouldLock())
        svc.onAppForegrounded()
        assertEquals(AppLockState.AUTHENTICATION_REQUIRED, svc.currentState.value)
    }

    // ------------------------------------------------------------------
    // Authentication outcomes (no bypass on failure/cancel)
    // ------------------------------------------------------------------

    @Test
    fun `authentication failure stays locked`() = runBlocking {
        var now = t0
        val svc = service(settings = FakeAppLockSettings().apply {
            saved = AppLockPolicy(mode = AppLockMode.IMMEDIATE)
        }, now = { now })
        svc.initialize()
        assertTrue(svc.beginAuthentication())
        assertEquals(AppLockState.AUTHENTICATING, svc.currentState.value)
        svc.onAuthenticationResult(AppLockAuthOutcome.FAILED)
        assertEquals(
            "an authentication failure must never unlock",
            AppLockState.AUTHENTICATION_REQUIRED,
            svc.currentState.value
        )
    }

    @Test
    fun `authentication cancel stays locked`() = runBlocking {
        var now = t0
        val svc = service(settings = FakeAppLockSettings().apply {
            saved = AppLockPolicy(mode = AppLockMode.AFTER_TIMEOUT, timeout = AppLockTimeout.M5)
        }, now = { now })
        svc.initialize()
        assertTrue(svc.beginAuthentication())
        svc.onAuthenticationResult(AppLockAuthOutcome.CANCELLED)
        assertEquals(
            "a user cancellation must never unlock",
            AppLockState.AUTHENTICATION_REQUIRED,
            svc.currentState.value
        )
        // The state is back to AUTHENTICATION_REQUIRED — a retry may begin.
        assertTrue(svc.beginAuthentication())
    }

    @Test
    fun `beginAuthentication refuses honestly when no prompt is demanded`() = runBlocking {
        var now = t0
        val svc = service(now = { now })
        svc.initialize()
        assertEquals(AppLockState.UNLOCKED, svc.currentState.value)
        assertFalse("no prompt while unlocked", svc.beginAuthentication())
    }

    // ------------------------------------------------------------------
    // PROCESS DEATH IS NOT AUTHENTICATION
    // ------------------------------------------------------------------

    @Test
    fun `process death does not carry the unlocked state forward`() = runBlocking {
        val settings = FakeAppLockSettings().apply {
            saved = AppLockPolicy(mode = AppLockMode.IMMEDIATE)
        }
        var now = t0
        val first = AppLockService(settings = settings, auditTrail = null, clock = { now })
        first.initialize()
        first.beginAuthentication()
        first.onAuthenticationResult(AppLockAuthOutcome.SUCCESS)
        assertEquals(AppLockState.UNLOCKED, first.currentState.value)

        // PROCESS DEATH: a brand-new instance with the SAME persisted
        // policy (and NOTHING persisted about authentication — the port
        // only carries policy by contract) starts LOCKED.
        val second = AppLockService(settings = settings, auditTrail = null, clock = { now })
        assertEquals(
            "a fresh process is fail-closed even BEFORE initialization proves the policy",
            AppLockState.AUTHENTICATION_REQUIRED,
            second.currentState.value
        )
        assertFalse(second.initialized.value)
        second.initialize()
        assertEquals(
            "process death is NOT authentication — the fresh process stays locked",
            AppLockState.AUTHENTICATION_REQUIRED,
            second.currentState.value
        )
        assertTrue(second.initialized.value)
    }

    // ------------------------------------------------------------------
    // Policy persistence / reload
    // ------------------------------------------------------------------

    @Test
    fun `policy persistence and reload restore the same settings`() = runBlocking {
        val settings = FakeAppLockSettings()
        var now = t0
        val svc = AppLockService(settings = settings, auditTrail = null, clock = { now })
        svc.initialize()

        val policy = AppLockPolicy(mode = AppLockMode.AFTER_TIMEOUT, timeout = AppLockTimeout.M15)
        svc.setPolicy(policy)
        assertEquals(policy, settings.saved)

        // A new service over the SAME store reloads the exact policy.
        val reloaded = AppLockService(settings = settings, auditTrail = null, clock = { now })
        reloaded.initialize()
        assertEquals(policy, reloaded.policy)
        assertEquals(AppLockState.AUTHENTICATION_REQUIRED, reloaded.currentState.value)
    }

    @Test
    fun `enabling the lock on an unauthenticated process locks immediately (fail-closed)`() = runBlocking {
        val settings = FakeAppLockSettings()
        var now = t0
        val svc = AppLockService(settings = settings, auditTrail = null, clock = { now })
        svc.initialize()
        assertEquals(AppLockState.UNLOCKED, svc.currentState.value)

        svc.setPolicy(AppLockPolicy(mode = AppLockMode.AFTER_TIMEOUT, timeout = AppLockTimeout.M1))
        assertEquals(
            "the pre-lock unlocked session is not an authenticated session",
            AppLockState.AUTHENTICATION_REQUIRED,
            svc.currentState.value
        )
    }

    @Test
    fun `disabling the lock unlocks`() = runBlocking {
        val settings = FakeAppLockSettings().apply {
            saved = AppLockPolicy(mode = AppLockMode.IMMEDIATE)
        }
        var now = t0
        val svc = AppLockService(settings = settings, auditTrail = null, clock = { now })
        svc.initialize()
        assertEquals(AppLockState.AUTHENTICATION_REQUIRED, svc.currentState.value)

        svc.setPolicy(AppLockPolicy.DISABLED)
        assertEquals(AppLockState.UNLOCKED, svc.currentState.value)
        assertEquals(AppLockPolicy.DISABLED, settings.saved)
    }

    // ------------------------------------------------------------------
    // Security invariant: policy-only model surface
    // ------------------------------------------------------------------

    @Test
    fun `the app-lock policy vocabulary cannot desync and ships disabled`() {
        val policy = AppLockPolicy(mode = AppLockMode.AFTER_TIMEOUT, timeout = AppLockTimeout.S30)
        // The derived enabled invariant cannot desync from the mode (there
        // is no stored "enabled" flag to contradict the mode — the compiler
        // enforces "nothing else exists to store": the persisted type is a
        // (mode, timeout, allowedAuthenticators) triple, and the port's
        // surface is loadPolicy/savePolicy ONLY).
        assertEquals(policy.mode != AppLockMode.OFF, policy.enabled)
        assertTrue(AppLockPolicy.DISABLED.isDisabled())
        assertEquals(AppLockTimeout.S30.durationMs, 30_000L)
        assertEquals(AppLockTimeout.M15.durationMs, 900_000L)
    }
}
