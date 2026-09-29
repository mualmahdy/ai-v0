package com.example.domain.core.execution

/**
 * ============================================================================
 * ScopeSnapshot — CLOSURE P0-1 (canonical acceptance-time scope)
 * ============================================================================
 *
 * The audit (step 5 §5.3/A1) found the scope capture fragmented across at
 * least FOUR parallel shapes:
 *   - the domain [ExecutionScope] (coroutine-context, service-consumed),
 *   - `ChatAttachmentCoordinator.AttachmentScope`,
 *   - `ChatCapabilitiesViewModel.InvocationScopeSnapshot` (private UI-side),
 *   - ad-hoc `pinned*` locals inside StudioViewModel.
 *
 * This type is the ONE canonical acceptance-time capture. It is an immutable
 * VALUE: the (workspace, project, session, operation) tuple frozen at the
 * moment a user action is ACCEPTED — never re-read from the live active
 * scope afterwards. Downstream operations either:
 *   - carry it explicitly as parameters, or
 *   - project it into an [ExecutionScope] coroutine element
 *     ([toExecutionScope]) so every suspending call inside the coroutine
 *     tree resolves the SAME pinned scope.
 *
 * PRINCIPLE (audit §5.18): one operation → one immutable scope → one owner
 * → one lifecycle → one outcome → one durable truth → one UI projection.
 *
 * UNIFICATION PROGRESS (CLOSURE §5/item 1 — COMPLETE in the final closure
 * stage): the four parallel capture shapes named above are retired onto
 * THIS type —
 *   - `ChatAttachmentCoordinator.AttachmentScope` — REPLACED (the
 *     coordinator's capture/import/cleanup API now speaks ScopeSnapshot);
 *   - `ChatCapabilitiesViewModel.InvocationScopeSnapshot` — REPLACED (the
 *     capability invocations capture the canonical snapshot and project it
 *     via [toExecutionScope]);
 *   - the ad-hoc `pinned*` locals inside StudioViewModel — RETIRED (the
 *     execution path captures ONE canonical snapshot and the locals are
 *     explicit PROJECTIONS of its fields; the snapshot also keys the
 *     operation's registration in the OperationRegistry);
 *   - the domain [ExecutionScope] coroutine element STAYS by design (it is
 *     the runtime projection, not a capture — see [toExecutionScope]).
 */
data class ScopeSnapshot(
    /** The workspace the action was accepted in (null = honestly unattributed). */
    val workspaceId: String?,
    /** The active workspace's sandbox project at acceptance (null = not bound). */
    val projectId: Long?,
    /** The conversation session the action belongs to (null = none/transient). */
    val sessionId: String?,
    /** The id of the operation this snapshot was captured for. */
    val operationId: String
) {
    /**
     * Projects the snapshot into the domain coroutine-context element so
     * service-layer calls (memory writes, RAG retrieval, file tools) resolve
     * the PINNED scope instead of the live active workspace.
     */
    fun toExecutionScope(): ExecutionScope = ExecutionScope(
        executionId = operationId,
        workspaceId = workspaceId ?: "unattributed",
        projectId = projectId,
        sessionId = sessionId
    )

    companion object {
        /**
         * Captures the canonical snapshot for [operationId]. Callers MUST
         * invoke this SYNCHRONOUSLY at acceptance — before launching any
         * coroutine — so a rapid scope switch between tap and dispatch can
         * never retarget the operation.
         */
        fun capture(
            operationId: String,
            workspaceId: String?,
            projectId: Long?,
            sessionId: String? = null
        ): ScopeSnapshot = ScopeSnapshot(
            workspaceId = workspaceId,
            projectId = projectId,
            sessionId = sessionId,
            operationId = operationId
        )
    }
}

/**
 * Re-captures an [ExecutionScope] (the service-side pinned element) back
 * into the canonical [ScopeSnapshot] value — e.g. when an execution's
 * kernel records the operation it is running under.
 */
fun ExecutionScope.toScopeSnapshot(operationId: String = executionId): ScopeSnapshot =
    ScopeSnapshot(
        workspaceId = workspaceId.takeIf { it != "unattributed" },
        projectId = projectId,
        sessionId = sessionId,
        operationId = operationId
    )
