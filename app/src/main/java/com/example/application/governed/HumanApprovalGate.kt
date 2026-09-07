package com.example.application.governed

import com.example.domain.core.security.governance.ApprovalResolution
import com.example.domain.ports.governed.HumanApprovalRequest
import com.example.domain.ports.governed.HumanApprovalStorePort
import java.util.UUID

/**
 * ============================================================================
 * HumanApprovalGate — Phase 1
 * ============================================================================
 *
 * Explicit human-in-the-loop consent for high-risk admissions.
 *  - Approval requests are DURABLE (via [HumanApprovalStorePort]).
 *  - An approval is a ONE-SHOT token: consuming it satisfies exactly one
 *    admission for the same (executionId, toolName) — replay is impossible.
 *  - Requests EXPIRE (TTL) instead of blocking forever.
 *  - Nothing in the pipeline may self-approve (the actor resolving an
 *    approval must be a user identity, enforced by caller convention:
 *    resolution APIs require a non-blank resolvedBy).
 */
class HumanApprovalGate(
    private val store: HumanApprovalStorePort,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun requestApproval(
        executionId: String,
        toolName: String,
        riskLevel: String,
        prompt: String,
        justification: String,
        ttlMs: Long = HumanApprovalRequest.DEFAULT_TTL_MS
    ): HumanApprovalRequest {
        // Re-use a still-pending request for the same (execution, tool)
        // instead of stacking duplicates.
        val existing = store.findPendingFor(executionId, toolName)
        if (existing != null && existing.expiresAtEpochMs > clock()) return existing

        val request = HumanApprovalRequest(
            approvalId = "apr_${UUID.randomUUID()}",
            executionId = executionId,
            toolName = toolName,
            riskLevel = riskLevel,
            prompt = prompt,
            justification = justification,
            requestedAtEpochMs = clock(),
            expiresAtEpochMs = clock() + ttlMs
        )
        return store.create(request)
    }

    /** User approves. The token remains valid until consumed ONCE. */
    suspend fun approve(approvalId: String, resolvedBy: String): ApprovalResolution {
        require(resolvedBy.isNotBlank()) { "المُوافق يجب أن يكون هوية مستخدم حقيقية وغير فارغة." }
        return resolveInternal(approvalId, ApprovalResolution.APPROVED, resolvedBy)
    }

    suspend fun reject(approvalId: String, resolvedBy: String): ApprovalResolution {
        require(resolvedBy.isNotBlank()) { "الرافض يجب أن يكون هوية مستخدم حقيقية وغير فارغة." }
        return resolveInternal(approvalId, ApprovalResolution.REJECTED, resolvedBy)
    }

    /**
     * Validates that [approvalId] is a usable APPROVED token for
     * (executionId, toolName) and CONSUMES it atomically.
     */
    suspend fun tryConsumeToken(
        approvalId: String,
        executionId: String,
        toolName: String
    ): Boolean {
        val request = store.find(approvalId) ?: return false
        if (request.executionId != executionId || request.toolName != toolName) return false
        if (request.resolution != ApprovalResolution.APPROVED) return false
        if (store.isTokenConsumed(approvalId)) return false
        if (request.expiresAtEpochMs < clock()) {
            store.resolve(approvalId, ApprovalResolution.EXPIRED, "system:ttl")
            return false
        }
        return store.markTokenConsumed(approvalId)
    }

    /** Housekeeping: expire stale pending requests; returns how many. */
    suspend fun expireStale(): Int = store.expireStale(clock())

    private suspend fun resolveInternal(
        approvalId: String,
        resolution: ApprovalResolution,
        resolvedBy: String
    ): ApprovalResolution {
        val request = store.find(approvalId)
            ?: return ApprovalResolution.EXPIRED // unknown id: honest non-success
        if (request.resolution != ApprovalResolution.PENDING) {
            return request.resolution // already resolved — idempotent
        }
        if (request.expiresAtEpochMs < clock()) {
            store.resolve(approvalId, ApprovalResolution.EXPIRED, "system:ttl")
            return ApprovalResolution.EXPIRED
        }
        store.resolve(approvalId, resolution, resolvedBy)
        return resolution
    }
}
