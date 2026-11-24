package com.example.domain.core.execution

/**
 * ============================================================================
 * WorkspaceIdentity — Stage 3A (DEBT-4) canonical workspace identity
 * ============================================================================
 *
 * THE CONTRACT (one authority): when an execution runs inside a PINNED
 * [ExecutionScope] — the production path, where the orchestrator pins the
 * workspace at launch (P0-02) — the scope's workspace is the ONE canonical
 * workspace identity for BOTH economic gates of a billable step:
 *
 *   - the decide-time gate ([com.example.application.decision.DecisionService]
 *     .applyEconomicGate), and
 *   - the pre-dispatch gate (ExecutionService.executeLlmStep).
 *
 * A task-supplied `workspaceId` input parameter is NOT an alternative
 * authority. When it is absent, the pinned scope governs alone (the
 * orchestrator stamps it into the task parameters for informational
 * consumers anyway). When it DISAGREES with the pinned scope, that is an
 * explicit INTEGRITY ERROR — [Resolution.Conflict] — never a silent
 * fallback to either value, never an arbitrary workspace, never an
 * unattributed scope: one billable operation must never be evaluated
 * against two different workspace budgets at two different stages.
 *
 * Legacy compatibility (preserved verbatim): when NO ExecutionScope is
 * present in the coroutine context (standalone DecisionService callers,
 * pure-JVM tests), there is nothing to conflict with — the task-supplied
 * parameter remains the decide-gate's source exactly as before Stage 3A,
 * and the pre-dispatch gate keeps its scope-derived (null) value.
 */
object WorkspaceIdentity {

    /** The orchestrator's honest label for an execution with no workspace. */
    const val UNATTRIBUTED = "unattributed"

    /** The resolution of (pinned scope workspace, task-supplied parameter). */
    sealed interface Resolution {
        /**
         * The canonical workspace id for both gates. Null = the execution is
         * honestly unattributed (no workspace was ever pinned).
         */
        data class Canonical(val workspaceId: String?) : Resolution

        /**
         * The task-supplied parameter disagrees with the pinned scope (or the
         * pinned execution is unattributed while the task claims a
         * workspace). An explicit integrity error: the caller must reject
         * the step, never silently prefer either value.
         */
        data class Conflict(
            /** The pinned scope's workspace (null = pinned-but-unattributed). */
            val pinnedWorkspaceId: String?,
            /** The conflicting task-supplied value. */
            val taskSuppliedWorkspaceId: String
        ) : Resolution
    }

    /** The pinned scope's workspace id, null when absent/unattributed. */
    fun pinnedWorkspaceIdOrNull(scope: ExecutionScope?): String? =
        scope?.workspaceId?.takeIf { it.isNotBlank() && it != UNATTRIBUTED }

    /**
     * Resolves the canonical workspace identity. A task-supplied value equal
     * to the pinned one (or absent) is harmless; anything else is a
     * [Resolution.Conflict].
     */
    fun resolve(pinnedWorkspaceId: String?, taskSuppliedWorkspaceId: String?): Resolution =
        when {
            taskSuppliedWorkspaceId == null -> Resolution.Canonical(pinnedWorkspaceId)
            taskSuppliedWorkspaceId == pinnedWorkspaceId -> Resolution.Canonical(pinnedWorkspaceId)
            else -> Resolution.Conflict(pinnedWorkspaceId, taskSuppliedWorkspaceId)
        }
}
