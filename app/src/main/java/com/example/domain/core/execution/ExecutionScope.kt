package com.example.domain.core.execution

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * ============================================================================
 * ExecutionScope — gap-closure P0-02 (pinned workspace context)
 * ============================================================================
 *
 * Coroutine-context element that PINS an execution's workspace for every
 * suspending call inside its coroutine tree (memory writes, RAG retrieval,
 * resource scoping). Previously each adapter re-asked the ACTIVE workspace
 * at call time, so a mid-task workspace switch silently moved attribution,
 * memory and retrieval to another workspace.
 *
 * The orchestrator wraps the closed-loop execution in
 * `withContext(ExecutionScope(...))`; adapters resolve
 * `coroutineContext[ExecutionScope.Key]` FIRST and fall back to the active
 * workspace only when no scope is present (user-driven paths).
 */
class ExecutionScope(
    val executionId: String,
    val workspaceId: String
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<ExecutionScope>
}
