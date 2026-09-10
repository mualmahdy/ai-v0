package com.example.application.context

import com.example.domain.core.context.AccessContext
import com.example.domain.core.context.ExecutionContext
import com.example.domain.core.context.PrincipalType
import com.example.domain.core.context.ResourceScope
import com.example.domain.core.context.ScopeGrant
import com.example.domain.core.context.ScopePermission
import com.example.domain.core.context.ScopeRules
import com.example.infrastructure.persistence.AppDatabase
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext
import com.example.domain.core.execution.ExecutionScope

/**
 * ============================================================================
 * REPAIR ORDER §4/§6/§8 — CENTRALIZED SCOPE RESOLUTION & ACCESS ENFORCEMENT
 * ============================================================================
 * ONE service resolves the authoritative AccessContext for any operation
 * and enforces the scope rules. Previously every service rolled its own
 * "workspaceId ?: activeWorkspace" logic; now:
 *
 *   - resolution priority: pinned coroutine ExecutionScope → explicit
 *     parameters → active workspace (LAST resort, explicit);
 *   - enforcement: [ScopeRules] (single place, single semantics);
 *   - grants: explicit [ScopeGrant]s widen access across boundaries —
 *     nothing else does.
 *
 * Fail-CLOSED: an operation that cannot resolve a scope FAILS with
 * [ScopeResolutionException] instead of silently targeting a global or
 * "default" scope.
 */
class ContextResolverService(
    private val database: AppDatabase,
    /** Active-workspace provider (LAST-resort resolution, explicit). */
    private val activeWorkspaceIdProvider: () -> String?
) {

    class ScopeResolutionException(message: String) : IllegalStateException(message)

    /**
     * Resolves the pinned ExecutionScope from the CURRENT coroutine context
     * (set by AgentOrchestrator at execution launch) — the authoritative
     * in-flight binding. Null when not inside an execution.
     */
    suspend fun currentExecutionScope(): ExecutionScope? = coroutineContext[ExecutionScope.Key]

    /**
     * Resolves the CURRENT AccessContext for the given principal.
     * Priority: pinned coroutine scope → active workspace.
     * Throws [ScopeResolutionException] when nothing can be resolved
     * (fail-closed, never a fabricated scope).
     */
    suspend fun resolveAccessContext(
        principalType: PrincipalType,
        principalId: String,
        permission: ScopePermission = ScopePermission.READ
    ): AccessContext {
        val pinned = currentExecutionScope()
        val workspaceId = pinned?.workspaceId ?: activeWorkspaceIdProvider()
            ?: throw ScopeResolutionException(
                "SCOPE_RESOLUTION_FAILED: لا يمكن تحديد نطاق وصول صالح (لا يوجد نطاق مثبّت ولا مساحة نشطة)."
            )
        return AccessContext(
            principalType = principalType,
            principalId = principalId,
            accessorScope = if (pinned != null) {
                ResourceScope.Task(
                    workspaceId = workspaceId,
                    projectId = pinned.projectId,
                    sessionId = pinned.sessionId,
                    taskId = pinned.executionId
                )
            } else {
                ResourceScope.Workspace(workspaceId)
            },
            permission = permission
        )
    }

    /**
     * Builds an ExecutionContext from the pinned scope or the active
     * workspace — the canonical context for pipelines that need the full
     * identity (execution id, task, session, agent).
     */
    suspend fun resolveExecutionContext(
        executionId: String,
        taskId: String? = null,
        agentId: String? = null
    ): ExecutionContext {
        val pinned = currentExecutionScope()
        val workspaceId = pinned?.workspaceId ?: activeWorkspaceIdProvider()
            ?: throw ScopeResolutionException(
                "SCOPE_RESOLUTION_FAILED: لا يمكن تحديد سياق تنفيذ صالح (لا نطاق مثبّت ولا مساحة نشطة)."
            )
        return ExecutionContext(
            executionId = executionId,
            workspaceId = workspaceId,
            projectId = pinned?.projectId,
            sessionId = pinned?.sessionId,
            taskId = taskId,
            agentId = agentId
        )
    }

    /**
     * ENFORCEMENT boundary (§6): asserts that [accessor] may touch
     * [resource] with [permission]. Throws [AccessDeniedException] with a
     * user-presentable message on violation — never a silent fallback.
     */
    fun enforceAccess(
        accessor: ResourceScope,
        resource: ResourceScope,
        permission: ScopePermission = ScopePermission.READ,
        grants: List<ScopeGrant> = emptyList()
    ) {
        if (!ScopeRules.canAccess(accessor, resource, permission, grants)) {
            throw AccessDeniedException(
                "ACCESS_DENIED: نطاق الوصول (${accessor.describe()}) غير مخوّل للوصول إلى " +
                        "المورد (${resource.describe()}) بصلاحية ${permission.name}."
            )
        }
    }

    /** Boolean variant for query-time filtering. */
    fun canAccess(
        accessor: ResourceScope,
        resource: ResourceScope,
        permission: ScopePermission = ScopePermission.READ,
        grants: List<ScopeGrant> = emptyList()
    ): Boolean = ScopeRules.canAccess(accessor, resource, permission, grants)

    class AccessDeniedException(message: String) : SecurityException(message)
}
