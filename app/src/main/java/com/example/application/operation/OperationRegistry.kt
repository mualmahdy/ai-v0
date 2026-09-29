package com.example.application.operation

import com.example.domain.core.execution.ScopeSnapshot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ============================================================================
 * OperationRegistry — CLOSURE P0-1 (A2): one lifecycle for meaningful ops
 * ============================================================================
 *
 * The audit (step 5 §5.3/A2) requires every meaningful operation to become
 * an OBJECT with an explicit lifecycle instead of each subsystem owning a
 * private, divergent notion of "what is running / what happened".
 *
 * Lifecycle:
 *
 *   CREATED
 *     ↓
 *   VALIDATING → AUTHORIZED → RUNNING
 *     ↓                                ↓
 *   SUCCEEDED ──→ PROJECTED ──→ FINALIZED
 *   FAILED ─────────────────────→ FINALIZED
 *   CANCELLED ──────────────────→ FINALIZED
 *
 * Transition rules are enforced MONOTONICALLY (see [canTransition]): a
 * terminal record is immutable, a FAILED operation can never become
 * SUCCEEDED, and PROJECTED is only reachable from SUCCEEDED — the registry
 * itself refuses the "UI projection advances ahead of durable truth"
 * reorderings the audit flagged (§5.4/B2).
 *
 * Records are in-memory runtime state (observability/recovery surface), NOT
 * durable truth; durability stays with the owning subsystem (Room, journal,
 * filesystem). The registry is bounded — finalized records beyond
 * [maxRetainedOperations] are evicted oldest-first so a long-lived process
 * cannot grow it without limit (audit §5.3/A3 resource ownership).
 *
 * HONEST BOUNDARY: originally bound at the mutation surfaces that carried
 * the audit's truth-divergence symptoms (session delete, transient session
 * open). The FULL EXECUTION PATH migrated onto the registry in the final
 * closure stage (§5/item 2): every chat-turn execution registers as
 * CHAT_TURN_EXECUTION keyed by its execution task id — RUNNING at the
 * kernel launch, SUCCEEDED/FAILED/CANCELLED at the honest terminal
 * (SUCCEEDED only AFTER the durable turn persisted), PROJECTED when the
 * assistant entry lands, FINALIZED in the finally-guard (with a defensive
 * mirror that refuses to leave a dead run "live"). A consent-halted run is
 * CANCELLED — the turn was never fulfilled; the resolution retry opens a
 * NEW operation (the AWAITING_APPROVAL live block is the VIEW's resting
 * state, not the operation's).
 */
enum class OperationPhase {
    CREATED,
    VALIDATING,
    AUTHORIZED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PROJECTED,
    FINALIZED
}

/**
 * The registry's record of one meaningful operation — the reference point
 * for activity, cancellation, error reporting, retry and observability
 * (audit §5.3/A2).
 */
data class OperationRecord(
    val operationId: String,
    /** Stable operation family, e.g. SESSION_DELETE, SESSION_TRANSIENT_OPEN. */
    val type: String,
    /** The immutable acceptance-time scope (never the live one). */
    val scope: ScopeSnapshot,
    /** The subsystem that owns the operation's execution. */
    val owner: String,
    val startedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val phase: OperationPhase,
    val cancellationRequested: Boolean = false,
    val resourceId: String? = null,
    val error: String? = null,
    val resultSummary: String? = null
) {
    val isTerminal: Boolean get() = phase == OperationPhase.FINALIZED
    val isActive: Boolean
        get() = phase in setOf(
            OperationPhase.CREATED, OperationPhase.VALIDATING,
            OperationPhase.AUTHORIZED, OperationPhase.RUNNING
        )
}

class OperationRegistry(
    /** Bounded retention: finalized records beyond this count are evicted. */
    private val maxRetainedOperations: Int = 500
) {
    private val operations = ConcurrentHashMap<String, OperationRecord>()
    private val listeners = CopyOnWriteArrayList<(OperationRecord) -> Unit>()

    /** Registers a new operation in CREATED phase. */
    fun register(
        type: String,
        scope: ScopeSnapshot,
        owner: String,
        resourceId: String? = null
    ): OperationRecord {
        val now = System.currentTimeMillis()
        val record = OperationRecord(
            operationId = scope.operationId,
            type = type,
            scope = scope,
            owner = owner,
            startedAtEpochMs = now,
            updatedAtEpochMs = now,
            phase = OperationPhase.CREATED,
            resourceId = resourceId
        )
        operations[record.operationId] = record
        evictFinalizedIfNeeded()
        notify(record)
        return record
    }

    /**
     * Moves an operation to [to]. Illegal transitions (terminal records,
     * FAILED→SUCCEEDED, projection before success, …) are REFUSED — null is
     * returned so callers can detect the rejection honestly instead of
     * silently overwriting lifecycle truth.
     */
    fun transition(
        operationId: String,
        to: OperationPhase,
        resultSummary: String? = null
    ): OperationRecord? {
        val current = operations[operationId] ?: return null
        if (!canTransition(current.phase, to)) return null
        val updated = current.copy(
            phase = to,
            updatedAtEpochMs = System.currentTimeMillis(),
            resultSummary = resultSummary ?: current.resultSummary
        )
        if (!operations.replace(operationId, current, updated)) return null
        evictFinalizedIfNeeded()
        notify(updated)
        return updated
    }

    /** Marks an operation FAILED with its error (terminal path begins). */
    fun fail(operationId: String, error: String?): OperationRecord? {
        val current = operations[operationId] ?: return null
        if (!canTransition(current.phase, OperationPhase.FAILED)) return null
        val updated = current.copy(
            phase = OperationPhase.FAILED,
            updatedAtEpochMs = System.currentTimeMillis(),
            error = error ?: "unknown failure"
        )
        if (!operations.replace(operationId, current, updated)) return null
        notify(updated)
        return updated
    }

    /**
     * Requests cancellation of a still-active operation (the owner observes
     * the flag; the registry does NOT kill anything itself).
     */
    fun requestCancellation(operationId: String): OperationRecord? {
        val current = operations[operationId] ?: return null
        if (current.isTerminal || current.cancellationRequested) return current
        val updated = current.copy(
            cancellationRequested = true,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        if (!operations.replace(operationId, current, updated)) return null
        notify(updated)
        return updated
    }

    fun get(operationId: String): OperationRecord? = operations[operationId]

    /** Still-running operations (lifecycle-truth, never UI projection). */
    fun activeOperations(): List<OperationRecord> =
        operations.values.filter { it.isActive }

    /** All records resolved under one workspace's scope. */
    fun operationsForWorkspace(workspaceId: String): List<OperationRecord> =
        operations.values.filter { it.scope.workspaceId == workspaceId }

    /** Registers a lifecycle listener (observability surface). */
    fun addListener(listener: (OperationRecord) -> Unit) {
        listeners.add(listener)
    }

    private fun notify(record: OperationRecord) {
        listeners.forEach { runCatching { it(record) } }
    }

    /**
     * Monotonic lifecycle legality. Notably:
     *  - FINALIZED is terminal;
     *  - PROJECTED only from SUCCEEDED (UI projection follows success);
     *  - FAILED/CANCELLED only from non-terminal phases;
     *  - CREATED may fast-path to RUNNING for simple mutations.
     */
    private fun canTransition(from: OperationPhase, to: OperationPhase): Boolean = when (from) {
        OperationPhase.CREATED -> to in setOf(
            OperationPhase.VALIDATING, OperationPhase.RUNNING,
            OperationPhase.FAILED, OperationPhase.CANCELLED
        )
        OperationPhase.VALIDATING -> to in setOf(
            OperationPhase.AUTHORIZED, OperationPhase.RUNNING,
            OperationPhase.FAILED, OperationPhase.CANCELLED
        )
        OperationPhase.AUTHORIZED -> to in setOf(
            OperationPhase.RUNNING, OperationPhase.FAILED, OperationPhase.CANCELLED
        )
        OperationPhase.RUNNING -> to in setOf(
            OperationPhase.SUCCEEDED, OperationPhase.FAILED, OperationPhase.CANCELLED
        )
        OperationPhase.SUCCEEDED -> to in setOf(OperationPhase.PROJECTED, OperationPhase.FINALIZED)
        OperationPhase.PROJECTED -> to == OperationPhase.FINALIZED
        OperationPhase.FAILED -> to == OperationPhase.FINALIZED
        OperationPhase.CANCELLED -> to == OperationPhase.FINALIZED
        OperationPhase.FINALIZED -> false
    }

    private fun evictFinalizedIfNeeded() {
        if (operations.size <= maxRetainedOperations) return
        operations.values
            .filter { it.isTerminal }
            .sortedBy { it.updatedAtEpochMs }
            .take(operations.size - maxRetainedOperations)
            .forEach { operations.remove(it.operationId) }
    }

    companion object {
        /** Fresh operation id for callers that have no natural one. */
        fun newOperationId(prefix: String = "op"): String =
            "${prefix}_${UUID.randomUUID().toString().take(16)}"
    }
}
