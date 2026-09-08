package com.example.application.governed

import com.example.domain.core.runtime.IsolationLevel
import com.example.domain.core.runtime.SandboxFailure
import com.example.domain.core.runtime.SandboxLifecycleState
import com.example.domain.core.runtime.SandboxResourceLimits
import com.example.domain.core.runtime.SandboxSession
import com.example.domain.core.runtime.SandboxTransitions
import com.example.domain.core.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * SandboxLifecycleService — Phase 1 (Secure Runtime)
 * ============================================================================
 *
 * Owns the sandbox lifecycle state machine:
 *   REQUESTED -> PROVISIONING -> READY -> RUNNING -> TERMINATING -> DESTROYED
 *
 * HONESTY: this service is constructed with the isolation level the HOST
 * can actually provide. On Android in-app execution that is
 * [IsolationLevel.APP_SANDBOX_BEST_EFFORT] — the service never upgrades it.
 * `requireIsolation()` refuses code-execution admission when the required
 * isolation exceeds what the host honestly provides.
 */
class SandboxLifecycleService(
    private val hostIsolationLevel: IsolationLevel,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val sessions = ConcurrentHashMap<String, SandboxSession>()

    /**
     * Live snapshot of ALL sandbox sessions (defect family 1 wiring): the
     * composition root observes this to maintain SESSION-SCOPED egress
     * blocks — a NO_NETWORK sandbox session blocks egress ONLY for requests
     * carrying that session's own scope, and blocks are released when the
     * session reaches a terminal state (no stale leaks).
     */
    private val _sessionsState = MutableStateFlow<List<SandboxSession>>(emptyList())
    val sessionsState: StateFlow<List<SandboxSession>> = _sessionsState.asStateFlow()

    private fun publishSessions() {
        _sessionsState.value = sessions.values.toList()
    }

    /** True isolation ranking used for comparisons (higher = stronger). */
    private val isolationRank: Map<IsolationLevel, Int> = mapOf(
        IsolationLevel.NONE to 0,
        IsolationLevel.APP_SANDBOX_BEST_EFFORT to 1,
        IsolationLevel.TRUE_ISOLATION to 2
    )

    val hostIsolation: IsolationLevel get() = hostIsolationLevel

    /**
     * Provisions a sandbox: REQUESTED -> PROVISIONING -> READY.
     * Fails honestly if the host cannot meet [requiredIsolation].
     */
    fun provision(
        workspaceId: String,
        requestedBy: String,
        limits: SandboxResourceLimits = SandboxResourceLimits.Default,
        requiredIsolation: IsolationLevel = IsolationLevel.APP_SANDBOX_BEST_EFFORT
    ): Outcome<SandboxSession, SandboxFailure> {
        if (isolationRank.getValue(hostIsolationLevel) < isolationRank.getValue(requiredIsolation)) {
            return Outcome.Error(
                SandboxFailure.InsufficientIsolation(
                    required = requiredIsolation,
                    available = hostIsolationLevel,
                    message = "مستوى العزل المتاح (${hostIsolationLevel.code}) لا يلزم المتطلب (${requiredIsolation.code}); " +
                        "الرفض صريح ولا يتم الترقية الصامتة."
                )
            )
        }
        val sessionId = "sbx_${UUID.randomUUID()}"
        val now = clock()
        var session = SandboxSession(
            sessionId = sessionId,
            state = SandboxLifecycleState.REQUESTED,
            limits = limits,
            isolationLevel = hostIsolationLevel,
            workspaceId = workspaceId,
            requestedBy = requestedBy,
            createdAtEpochMs = now,
            stateHistory = listOf(SandboxLifecycleState.REQUESTED to now)
        )
        sessions[sessionId] = session
        session = advance(sessionId, SandboxLifecycleState.PROVISIONING)
            ?.let { sessions[sessionId] = it; it } ?: sessions[sessionId]!!
        session = advance(sessionId, SandboxLifecycleState.READY)
            ?.let { sessions[sessionId] = it; it } ?: sessions[sessionId]!!
        publishSessions()
        return Outcome.Success(session)
    }

    /** Marks a READY sandbox as RUNNING (execution is about to start). */
    fun start(sessionId: String): Outcome<SandboxSession, SandboxFailure> =
        transition(sessionId, SandboxLifecycleState.RUNNING)

    /** Terminates and destroys a sandbox (idempotent for terminal states). */
    fun destroy(sessionId: String): Outcome<SandboxSession, SandboxFailure> {
        val current = sessions[sessionId]
            ?: return Outcome.Error(SandboxFailure.SessionNotFound(sessionId))
        if (current.state in SandboxTransitions.terminalStates) {
            return Outcome.Success(current)
        }
        if (current.state == SandboxLifecycleState.RUNNING) {
            transition(sessionId, SandboxLifecycleState.TERMINATING).let { if (it is Outcome.Error) return it }
        }
        return transition(sessionId, SandboxLifecycleState.DESTROYED)
    }

    /** Records an honest resource-limit violation; the session FAILS. */
    fun reportLimitViolation(
        sessionId: String,
        limitName: String,
        observedValue: String,
        message: String
    ): Outcome<SandboxSession, SandboxFailure> {
        val current = sessions[sessionId]
            ?: return Outcome.Error(SandboxFailure.SessionNotFound(sessionId))
        sessions[sessionId] = current.copy(
            state = SandboxLifecycleState.FAILED,
            failureReason = "limit:$limitName observed=$observedValue — $message",
            stateHistory = current.stateHistory + (SandboxLifecycleState.FAILED to clock())
        )
        publishSessions()
        return Outcome.Success(sessions[sessionId]!!)
    }

    fun find(sessionId: String): SandboxSession? = sessions[sessionId]

    fun sessionsForWorkspace(workspaceId: String): List<SandboxSession> =
        sessions.values.filter { it.workspaceId == workspaceId }

    fun activeCount(): Int = sessions.values.count {
        it.state == SandboxLifecycleState.READY || it.state == SandboxLifecycleState.RUNNING
    }

    // ------------------------------------------------------------------ //

    private fun transition(
        sessionId: String,
        to: SandboxLifecycleState
    ): Outcome<SandboxSession, SandboxFailure> {
        val current = sessions[sessionId]
            ?: return Outcome.Error(SandboxFailure.SessionNotFound(sessionId))
        val before = current.state
        val advanced = advance(sessionId, to)
        if (advanced == null) {
            return Outcome.Error(SandboxFailure.SessionNotFound(sessionId))
        }
        if (advanced.state != to) {
            return Outcome.Error(
                SandboxFailure.IllegalTransition(from = before, to = to)
            )
        }
        return Outcome.Success(advanced)
    }

    /**
     * Attempts a lifecycle move; returns the updated session, or null when
     * the session is unknown. Illegal transitions leave the session
     * UNTOUCHED (no state corruption) and the caller surfaces the failure.
     */
    private fun advance(sessionId: String, to: SandboxLifecycleState): SandboxSession? {
        val current = sessions[sessionId] ?: return null
        val from = current.state
        if (from == to) return current
        if (!SandboxTransitions.isLegal(from, to)) {
            // Leave state untouched; signal via a sentinel: state != to.
            return current
        }
        val updated = current.copy(
            state = to,
            stateHistory = current.stateHistory + (to to clock())
        )
        sessions[sessionId] = updated
        publishSessions()
        return updated
    }
}
