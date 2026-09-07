package com.example.infrastructure.governed

import com.example.domain.core.security.governance.ApprovalResolution
import com.example.domain.ports.governed.HumanApprovalRequest
import com.example.domain.ports.governed.HumanApprovalStorePort
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * InMemoryHumanApprovalStore — Phase 1
 * ============================================================================
 *
 * Volatile implementation of [HumanApprovalStorePort].
 *
 * HONEST LIMITATION (fail-safe direction): approvals are NOT persisted
 * across process death. Losing them can only cause a tool to RE-REQUEST
 * human approval (NEEDS_HUMAN_APPROVAL) — it can never authorize execution
 * that was not approved. Durable persistence (Room v11 entity + migration)
 * is the declared follow-up; the port boundary keeps that swap non-breaking.
 */
class InMemoryHumanApprovalStore : HumanApprovalStorePort {

    private val requests = ConcurrentHashMap<String, HumanApprovalRequest>()
    private val consumedTokens = ConcurrentHashMap.newKeySet<String>()

    override suspend fun create(request: HumanApprovalRequest): HumanApprovalRequest {
        requests[request.approvalId] = request
        return request
    }

    override suspend fun find(approvalId: String): HumanApprovalRequest? = requests[approvalId]

    override suspend fun findPendingFor(executionId: String, toolName: String): HumanApprovalRequest? =
        requests.values.firstOrNull {
            it.executionId == executionId && it.toolName == toolName && it.resolution == ApprovalResolution.PENDING
        }

    override suspend fun resolve(
        approvalId: String,
        resolution: ApprovalResolution,
        resolvedBy: String
    ): HumanApprovalRequest? {
        val current = requests[approvalId] ?: return null
        if (current.resolution != ApprovalResolution.PENDING) return current
        val updated = current.copy(
            resolution = resolution,
            resolvedBy = resolvedBy,
            resolvedAtEpochMs = System.currentTimeMillis()
        )
        requests[approvalId] = updated
        return updated
    }

    override suspend fun expireStale(nowEpochMs: Long): Int {
        var expired = 0
        for ((id, request) in requests) {
            if (request.resolution == ApprovalResolution.PENDING && request.expiresAtEpochMs < nowEpochMs) {
                requests[id] = request.copy(
                    resolution = ApprovalResolution.EXPIRED,
                    resolvedBy = "system:ttl",
                    resolvedAtEpochMs = nowEpochMs
                )
                expired++
            }
        }
        return expired
    }

    override suspend fun markTokenConsumed(approvalId: String): Boolean =
        consumedTokens.add(approvalId)

    override suspend fun isTokenConsumed(approvalId: String): Boolean =
        consumedTokens.contains(approvalId)
}
