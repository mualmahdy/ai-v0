package com.example.application.governed

import com.example.domain.core.Outcome
import com.example.domain.core.runtime.IsolationLevel
import com.example.domain.core.runtime.SandboxFailure
import com.example.domain.core.runtime.SandboxLifecycleState
import com.example.domain.core.runtime.SandboxResourceLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SandboxLifecycleService tests — state machine legality, honest isolation
 * refusal, limit violation reporting, idempotent destroy.
 */
class SandboxLifecycleServiceTest {

    @Test
    fun `provision walks REQUESTED to PROVISIONING to READY`() {
        val service = SandboxLifecycleService(IsolationLevel.APP_SANDBOX_BEST_EFFORT)
        val result = service.provision("ws-1", "agent-1")
        assertTrue(result is Outcome.Success)
        val session = (result as Outcome.Success).value
        assertEquals(SandboxLifecycleState.READY, session.state)
        assertEquals(
            listOf(SandboxLifecycleState.REQUESTED, SandboxLifecycleState.PROVISIONING, SandboxLifecycleState.READY),
            session.stateHistory.map { it.first }
        )
    }

    @Test
    fun `honest isolation refusal when TRUE_ISOLATION is required`() {
        val service = SandboxLifecycleService(IsolationLevel.APP_SANDBOX_BEST_EFFORT)
        val result = service.provision(
            workspaceId = "ws-1",
            requestedBy = "agent-1",
            requiredIsolation = IsolationLevel.TRUE_ISOLATION
        )
        assertTrue(result is Outcome.Error)
        val failure = (result as Outcome.Error).failure
        assertTrue(failure is SandboxFailure.InsufficientIsolation)
        assertEquals(IsolationLevel.TRUE_ISOLATION, (failure as SandboxFailure.InsufficientIsolation).required)
        assertEquals(IsolationLevel.APP_SANDBOX_BEST_EFFORT, failure.available)
    }

    @Test
    fun `start moves READY to RUNNING then destroy to TERMINATING to DESTROYED`() {
        val service = SandboxLifecycleService(IsolationLevel.APP_SANDBOX_BEST_EFFORT)
        val session = (service.provision("ws-1", "a") as Outcome.Success).value

        val running = service.start(session.sessionId)
        assertTrue(running is Outcome.Success)
        assertEquals(SandboxLifecycleState.RUNNING, (running as Outcome.Success).value.state)

        val destroyed = service.destroy(session.sessionId)
        assertTrue(destroyed is Outcome.Success)
        val history = (destroyed as Outcome.Success).value.stateHistory.map { it.first }
        assertEquals(
            listOf(
                SandboxLifecycleState.REQUESTED,
                SandboxLifecycleState.PROVISIONING,
                SandboxLifecycleState.READY,
                SandboxLifecycleState.RUNNING,
                SandboxLifecycleState.TERMINATING,
                SandboxLifecycleState.DESTROYED
            ),
            history
        )
    }

    @Test
    fun `destroy of READY sandbox skips TERMINATING legally`() {
        val service = SandboxLifecycleService(IsolationLevel.APP_SANDBOX_BEST_EFFORT)
        val session = (service.provision("ws-1", "a") as Outcome.Success).value
        val destroyed = service.destroy(session.sessionId)
        assertTrue(destroyed is Outcome.Success)
        assertEquals(SandboxLifecycleState.DESTROYED, (destroyed as Outcome.Success).value.state)
    }

    @Test
    fun `destroy is idempotent for terminal states`() {
        val service = SandboxLifecycleService(IsolationLevel.APP_SANDBOX_BEST_EFFORT)
        val session = (service.provision("ws-1", "a") as Outcome.Success).value
        service.destroy(session.sessionId)
        val second = service.destroy(session.sessionId)
        assertTrue(second is Outcome.Success)
        assertEquals(SandboxLifecycleState.DESTROYED, (second as Outcome.Success).value.state)
    }

    @Test
    fun `start on DESTROYED session is refused`() {
        val service = SandboxLifecycleService(IsolationLevel.APP_SANDBOX_BEST_EFFORT)
        val session = (service.provision("ws-1", "a") as Outcome.Success).value
        service.destroy(session.sessionId)
        val restart = service.start(session.sessionId)
        assertTrue(restart is Outcome.Error)
        assertTrue((restart as Outcome.Error).failure is SandboxFailure.IllegalTransition)
    }

    @Test
    fun `unknown session operations fail with SessionNotFound`() {
        val service = SandboxLifecycleService(IsolationLevel.APP_SANDBOX_BEST_EFFORT)
        val result = service.destroy("does-not-exist")
        assertTrue(result is Outcome.Error)
        assertTrue((result as Outcome.Error).failure is SandboxFailure.SessionNotFound)
    }

    @Test
    fun `limit violation moves session to FAILED with honest reason`() {
        val service = SandboxLifecycleService(IsolationLevel.APP_SANDBOX_BEST_EFFORT)
        val session = (service.provision("ws-1", "a") as Outcome.Success).value
        service.start(session.sessionId)

        val violated = service.reportLimitViolation(
            sessionId = session.sessionId,
            limitName = "wallClockTimeoutMs",
            observedValue = "45210ms > 30000ms",
            message = "تجاوز المهلة الزمنية"
        )
        assertTrue(violated is Outcome.Success)
        val failed = (violated as Outcome.Success).value
        assertEquals(SandboxLifecycleState.FAILED, failed.state)
        assertNotNull(failed.failureReason)
        assertTrue(failed.failureReason!!.startsWith("limit:wallClockTimeoutMs"))
    }

    @Test
    fun `FAILED session cannot be restarted (terminal)`() {
        val service = SandboxLifecycleService(IsolationLevel.APP_SANDBOX_BEST_EFFORT)
        val session = (service.provision("ws-1", "a") as Outcome.Success).value
        service.reportLimitViolation(session.sessionId, "memoryLimitBytes", "999MB", "تجاوز الذاكرة")
        val restart = service.start(session.sessionId)
        assertTrue(restart is Outcome.Error)
    }

    @Test
    fun `custom resource limits are carried on the session`() {
        val service = SandboxLifecycleService(IsolationLevel.APP_SANDBOX_BEST_EFFORT)
        val limits = SandboxResourceLimits(
            wallClockTimeoutMs = 5_000,
            memoryLimitBytes = 16L * 1024 * 1024,
            maxFileCount = 50,
            networkPolicy = com.example.domain.core.runtime.SandboxNetworkPolicy.NO_NETWORK
        )
        val session = (service.provision("ws-1", "a", limits) as Outcome.Success).value
        assertEquals(5_000, session.limits.wallClockTimeoutMs)
        assertEquals(50, session.limits.maxFileCount)
        assertEquals(com.example.domain.core.runtime.SandboxNetworkPolicy.NO_NETWORK, session.limits.networkPolicy)
    }

    @Test
    fun `active count tracks READY and RUNNING only`() {
        val service = SandboxLifecycleService(IsolationLevel.APP_SANDBOX_BEST_EFFORT)
        val s1 = (service.provision("ws-1", "a") as Outcome.Success).value
        service.provision("ws-1", "b")
        assertEquals(2, service.activeCount())
        service.start(s1.sessionId)
        assertEquals(2, service.activeCount())
        service.destroy(s1.sessionId)
        assertEquals(1, service.activeCount())
    }
}
