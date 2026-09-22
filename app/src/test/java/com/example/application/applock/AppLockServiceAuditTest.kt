package com.example.application.applock

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.audit.AuditTrailService
import com.example.domain.core.security.applock.AppLockAuthOutcome
import com.example.domain.core.security.applock.AppLockMode
import com.example.domain.core.security.applock.AppLockPolicy
import com.example.domain.core.security.applock.AppLockState
import com.example.domain.core.security.applock.AppLockTimeout
import com.example.domain.ports.security.AppLockSettingsPort
import com.example.infrastructure.persistence.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * AppLockServiceAuditTest — the audit events land in the EXISTING trail
 * ============================================================================
 *
 * App Lock lifecycle events (LOCK_ENABLED / LOCK_DISABLED / LOCK_TRIGGERED /
 * AUTH_STARTED / AUTH_SUCCESS / AUTH_FAILED / AUTH_CANCELLED) are recorded
 * through the SAME unified AuditTrailService every other security-sensitive
 * action uses — no new audit framework, and no secrets/biometric data (the
 * trail rows carry only the action/policy/outcome vocabulary).
 */
@RunWith(RobolectricTestRunner::class)
class AppLockServiceAuditTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var auditTrail: AuditTrailService

    private val settings = object : AppLockSettingsPort {
        @Volatile
        var saved: AppLockPolicy = AppLockPolicy.DISABLED
        override suspend fun loadPolicy(): AppLockPolicy = saved
        override suspend fun savePolicy(policy: AppLockPolicy) {
            saved = policy
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        auditTrail = AuditTrailService(database = db)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(20)
        }
        assertTrue("timed out waiting for the audit rows", condition())
    }

    /** The audit rows (unscoped recent — app-lock events are Application-scope). */
    private fun recentAuditRows(): List<com.example.infrastructure.persistence.entities.AuditEventEntity> =
        runBlocking { db.auditEventDao().recent(100) }

    @Test
    fun `the full lock lifecycle lands every audit event kind in the existing trail`() = runBlocking {
        var now = 1_000_000L
        val service = AppLockService(
            settings = settings,
            auditTrail = auditTrail,
            clock = { now },
            actorId = "audit_test_user",
            // Unconfined: the fire-and-forget audit writes run inline —
            // deterministic for the assertions below.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        service.initialize()

        // Enable (locking an unauthenticated process → ENABLED + TRIGGERED).
        service.setPolicy(AppLockPolicy(mode = AppLockMode.AFTER_TIMEOUT, timeout = AppLockTimeout.M1))

        // A full authentication episode: prompt → success.
        service.beginAuthentication()
        service.onAuthenticationResult(AppLockAuthOutcome.SUCCESS)
        assertEquals(AppLockState.UNLOCKED, service.currentState.value)

        // A re-lock by timeout: background → foreground past the threshold.
        now += 120_000
        service.onAppBackgrounded()
        now += 120_000
        service.onAppForegrounded()
        assertEquals(AppLockState.AUTHENTICATION_REQUIRED, service.currentState.value)

        // A failed attempt, then a cancelled one, then disable.
        service.beginAuthentication()
        service.onAuthenticationResult(AppLockAuthOutcome.FAILED)
        service.beginAuthentication()
        service.onAuthenticationResult(AppLockAuthOutcome.CANCELLED)
        service.setPolicy(AppLockPolicy.DISABLED)

        // The unified trail writes async (the service scope launches each
        // record) — await the full episode (≥ 9 rows).
        awaitUntil { recentAuditRows().size >= 9 }
        val events = recentAuditRows()
        val actions = events.map { it.action }

        val expectedKinds = listOf(
            "APP_LOCK_ENABLED",
            "APP_LOCK_TRIGGERED",
            "APP_LOCK_AUTH_STARTED",
            "APP_LOCK_AUTH_SUCCESS",
            "APP_LOCK_AUTH_FAILED",
            "APP_LOCK_AUTH_CANCELLED",
            "APP_LOCK_DISABLED"
        )
        expectedKinds.forEach { kind ->
            assertTrue(
                "the audit trail must contain the app-lock event $kind (saw: $actions)",
                actions.contains(kind)
            )
        }
        // The rows carry policy/outcome vocabulary only — no PIN/secret/
        // biometric material (the redaction last line stays for the reason
        // free-text, which here is fixed Arabic explanations).
        assertTrue(events.all { event ->
            event.reason == null || (
                !event.reason.contains("pin", ignoreCase = true) &&
                    !event.reason.contains("secret", ignoreCase = true) &&
                    !event.reason.contains("password", ignoreCase = true) &&
                    !event.reason.contains("token", ignoreCase = true)
                )
        })
        // The audited resource type is the app-lock domain, honestly.
        assertTrue(events.filter { it.action.startsWith("APP_LOCK") }.all { it.resourceType == "APP_LOCK" })
    }
}
