package com.example.domain.core.context

/**
 * REPAIR ORDER §4 (Unified Context and Scope Architecture) —
 * the single authoritative scope hierarchy of the application:
 *
 *   APPLICATION → WORKSPACE → PROJECT → SESSION → TASK
 *
 * One place defines what a scope IS; [ScopeRules] defines, in ONE place,
 * who may access what. Every service (storage, RAG, tools, transfer,
 * governance) must resolve access through these types instead of ad-hoc
 * workspace/project null-checks scattered across layers.
 */
enum class ScopeType {
    APPLICATION,
    WORKSPACE,
    PROJECT,
    SESSION,
    TASK
}

/**
 * Explicit resource scope. Immutable value describing WHERE a resource
 * lives / WHERE an execution runs.
 *
 * Ownership & inheritance rules are enforced by [ScopeRules]:
 *   - a CHILD scope may access PERMITTED ancestor-shared resources;
 *   - a SIBLING scope may NOT access another sibling's private resources;
 *   - a PARENT scope may NOT automatically access child-private resources.
 *
 * `sharedUpward` marks resources explicitly declared as shared with
 * descendant scopes (workspace-shared knowledge, application-shared
 * artifacts).
 */
sealed class ResourceScope {
    abstract val type: ScopeType
    abstract val workspaceIdOrNull: String?
    abstract val projectIdOrNull: Long?
    abstract val sharedUpward: Boolean

    /** APPLICATION — device-global scope (e.g. provider catalog, app config). */
    data object Application : ResourceScope() {
        override val type: ScopeType = ScopeType.APPLICATION
        override val workspaceIdOrNull: String? = null
        override val projectIdOrNull: Long? = null
        override val sharedUpward: Boolean = true
    }

    /** WORKSPACE — the isolation boundary. */
    data class Workspace(
        val workspaceId: String,
        /** True when this workspace's resources are explicitly shared app-wide. */
        override val sharedUpward: Boolean = false
    ) : ResourceScope() {
        override val type: ScopeType = ScopeType.WORKSPACE
        override val workspaceIdOrNull: String = workspaceId
        override val projectIdOrNull: Long? = null
    }

    /**
     * PROJECT — the sibling-isolation boundary. Two projects in one
     * workspace are SIBLINGS: neither sees the other's private resources
     * unless an explicit [ScopeGrant] exists.
     */
    data class Project(
        val workspaceId: String,
        val projectId: Long,
        /** True when this project's resources are explicitly shared with the workspace. */
        override val sharedUpward: Boolean = false
    ) : ResourceScope() {
        override val type: ScopeType = ScopeType.PROJECT
        override val workspaceIdOrNull: String = workspaceId
        override val projectIdOrNull: Long = projectId
    }

    data class Session(
        val workspaceId: String,
        val projectId: Long?,
        val sessionId: String
    ) : ResourceScope() {
        override val type: ScopeType = ScopeType.SESSION
        override val workspaceIdOrNull: String = workspaceId
        override val projectIdOrNull: Long? = projectId
        override val sharedUpward: Boolean = false
    }

    data class Task(
        val workspaceId: String,
        val projectId: Long?,
        val sessionId: String?,
        val taskId: String
    ) : ResourceScope() {
        override val type: ScopeType = ScopeType.TASK
        override val workspaceIdOrNull: String = workspaceId
        override val projectIdOrNull: Long? = projectId
        override val sharedUpward: Boolean = false
    }

    /** Machine-readable stable label for persistence/diagnostics. */
    fun describe(): String = when (this) {
        is Application -> "APPLICATION"
        is Workspace -> "WORKSPACE:$workspaceId"
        is Project -> "PROJECT:$workspaceId/$projectId"
        is Session -> "SESSION:$workspaceId/${projectId ?: "-"}/$sessionId"
        is Task -> "TASK:$workspaceId/${projectId ?: "-"}/${sessionId ?: "-"}/$taskId"
    }
}

/**
 * Explicit permissions for cross-scope resource use (REPAIR ORDER §8).
 * Cross-scope visibility NEVER implies cross-scope access: an explicit
 * grant with one of these permissions is required.
 */
enum class ScopePermission {
    READ,
    READ_WRITE,
    EXPORT,
    SHARE,
    EXECUTE
}

/**
 * An explicit, persisted grant that shares a resource across scope
 * boundaries. Absence of a grant = private resource (default).
 */
data class ScopeGrant(
    val id: String,
    val resourceScope: ResourceScope,
    val resourceType: String,
    val resourceId: String,
    val permission: ScopePermission,
    val grantedToScope: ResourceScope,
    val grantedBy: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null
)

/**
 * The identity of WHO is asking to touch a resource. Principal types map
 * onto the authorization layers: agents (tool execution), users (transfers,
 * grants), system services (repair, import).
 */
enum class PrincipalType { USER, AGENT, SYSTEM }

/**
 * REPAIR ORDER §4 — pinned, immutable execution context. One authoritative
 * value threaded through the execution pipeline (orchestrator → decision →
 * governance → execution → tools/storage). Services MUST resolve scope from
 * this (or from the [com.example.domain.core.execution.ExecutionScope]
 * coroutine element built from it), never from "whatever workspace is
 * active right now".
 */
data class ExecutionContext(
    val executionId: String,
    val workspaceId: String,
    val projectId: Long? = null,
    val sessionId: String? = null,
    val taskId: String? = null,
    val agentId: String? = null,
    val principalType: PrincipalType = PrincipalType.AGENT,
    val principalId: String = agentId ?: "system"
) {
    val scope: ResourceScope
        get() = ResourceScope.Task(
            workspaceId = workspaceId,
            projectId = projectId,
            sessionId = sessionId,
            taskId = taskId ?: executionId
        )
}

/**
 * Resolved authorization input for a single resource operation.
 * Built by the central ContextResolver — NOT by individual services.
 */
data class AccessContext(
    val principalType: PrincipalType,
    val principalId: String,
    val accessorScope: ResourceScope,
    val permission: ScopePermission = ScopePermission.READ
)

/**
 * REPAIR ORDER §4/§6 — THE single place where scope/ownership rules live.
 *
 * Invariants (enforced, tested):
 *   1. A child scope may access permitted ancestor-shared resources.
 *   2. A sibling scope may NOT access another sibling's private resources.
 *   3. A parent scope may NOT automatically access child-private resources.
 *   4. No implicit cross-project fallback. Ever.
 */
object ScopeRules {

    /**
     * Is [accessor] allowed to touch a resource living at [resource] with
     * [permission]? Explicit [grants] can widen access across boundaries;
     * nothing else can.
     */
    fun canAccess(
        accessor: ResourceScope,
        resource: ResourceScope,
        permission: ScopePermission = ScopePermission.READ,
        grants: List<ScopeGrant> = emptyList()
    ): Boolean {
        // 1. Same scope → allowed.
        if (accessor == resource) return true

        // 2. Explicit grant covering this resource/permission for this accessor.
        if (grants.any { grantCovers(it, accessor, resource, permission) }) return true

        // 3. Child → ancestor scope whose resources are EXPLICITLY shared
        //    across their boundary (workspace-shared knowledge, application
        //    catalog).
        if (isAncestorOf(resource, accessor)) {
            return resource.sharedUpward
        }

        // 4. Ancestor → child scope whose resources are EXPLICITLY shared
        //    across their boundary (a project that declares sharing with
        //    the workspace). PRIVATE child resources stay private (rule 3).
        if (isAncestorOf(accessor, resource)) {
            return resource.sharedUpward
        }

        // 5. Siblings and unrelated scopes: DENIED (rule 2 + rule 4).
        return false
    }

    /** True if [maybeAncestor] is a strict ancestor scope of [scope]. */
    fun isAncestorOf(maybeAncestor: ResourceScope, scope: ResourceScope): Boolean = when (maybeAncestor) {
        is ResourceScope.Application -> true
        is ResourceScope.Workspace -> scope.workspaceIdOrNull == maybeAncestor.workspaceId &&
                scope.type != ScopeType.WORKSPACE
        is ResourceScope.Project -> scope.projectIdOrNull == maybeAncestor.projectId &&
                scope.workspaceIdOrNull == maybeAncestor.workspaceId &&
                (scope.type == ScopeType.SESSION || scope.type == ScopeType.TASK)
        else -> false
    }

    /**
     * True when two PROJECT scopes are siblings: same workspace,
     * different project — the exact boundary cross-project isolation
     * must reject.
     */
    fun areSiblings(a: ResourceScope, b: ResourceScope): Boolean {
        if (a !is ResourceScope.Project || b !is ResourceScope.Project) return false
        return a.workspaceId == b.workspaceId && a.projectId != b.projectId
    }

    private fun grantCovers(
        grant: ScopeGrant,
        accessor: ResourceScope,
        resource: ResourceScope,
        permission: ScopePermission
    ): Boolean {
        if (grant.resourceScope != resource) return false
        if (grant.permission != permission && grant.permission != ScopePermission.READ_WRITE) return false
        if (grant.expiresAtEpochMs != null && grant.expiresAtEpochMs < System.currentTimeMillis()) return false
        return grant.grantedToScope == accessor || isAncestorOf(grant.grantedToScope, accessor)
    }
}

/**
 * REPAIR ORDER §23 — coherent resource health model.
 * Distinguishes REGISTERED vs REACHABLE vs CAPABILITY-COMPATIBLE vs USABLE
 * via explicit states instead of conflating "has a row" with "works".
 */
enum class ResourceHealthState {
    UNKNOWN,
    DISCOVERING,
    READY,
    DEGRADED,
    UNAVAILABLE,
    MISCONFIGURED,
    BLOCKED;

    val isUsable: Boolean
        get() = this == READY || this == DEGRADED
}
