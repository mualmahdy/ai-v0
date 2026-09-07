package com.example.domain.ports.governed

import com.example.domain.core.security.governance.PrincipalType
import com.example.domain.core.security.governance.Permission
import com.example.domain.core.security.governance.SecurableResourceType
import com.example.domain.core.security.governance.ApprovalResolution

/**
 * ============================================================================
 * Governed Runtime Ports — Phase 1
 * ============================================================================
 * Hexagonal boundaries for the admission control pipeline. The application
 * service depends ONLY on these ports; production wiring lives in
 * AppContainer (Room-backed, telemetry-backed adapters), tests use fakes.
 */

/**
 * Principal authorization check: does principal [principalId] of type
 * [principalType] hold [permission] on resource [resourceId]?
 * Production adapter delegates to PermissionGrantService (Room-backed).
 */
fun interface PrincipalAuthorizationPort {
    suspend fun check(
        principalType: PrincipalType,
        principalId: String,
        resourceType: SecurableResourceType,
        resourceId: String,
        permission: Permission
    ): Boolean
}

/** Persisted record of a human approval request (one-shot token). */
data class HumanApprovalRequest(
    val approvalId: String,
    val executionId: String,
    val toolName: String,
    val riskLevel: String,
    val prompt: String,
    val justification: String,
    val requestedAtEpochMs: Long = System.currentTimeMillis(),
    val expiresAtEpochMs: Long = requestedAtEpochMs + DEFAULT_TTL_MS,
    val resolution: ApprovalResolution = ApprovalResolution.PENDING,
    val resolvedBy: String? = null,
    val resolvedAtEpochMs: Long? = null
) {
    companion object {
        const val DEFAULT_TTL_MS: Long = 10L * 60 * 1000 // 10 minutes
    }
}

/**
 * Durable store for approval requests. A pending approval BLOCKS admission;
 * an APPROVED approval yields a one-shot token that satisfies exactly ONE
 * subsequent admission of the same (executionId, toolName).
 */
interface HumanApprovalStorePort {
    suspend fun create(request: HumanApprovalRequest): HumanApprovalRequest
    suspend fun find(approvalId: String): HumanApprovalRequest?
    suspend fun findPendingFor(executionId: String, toolName: String): HumanApprovalRequest?
    suspend fun resolve(approvalId: String, resolution: ApprovalResolution, resolvedBy: String): HumanApprovalRequest?
    suspend fun expireStale(nowEpochMs: Long): Int
    suspend fun markTokenConsumed(approvalId: String): Boolean
    suspend fun isTokenConsumed(approvalId: String): Boolean
}

/** Audit sink for admission decisions (deny OR allow — both are recorded). */
fun interface AdmissionAuditPort {
    suspend fun record(
        severity: String,
        actor: String,
        action: String,
        resourceType: String,
        resourceId: String,
        decision: String,
        reason: String,
        workspaceId: String?,
        attributes: Map<String, String>
    )
}
