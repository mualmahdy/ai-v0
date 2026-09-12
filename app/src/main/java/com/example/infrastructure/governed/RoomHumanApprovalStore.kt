package com.example.infrastructure.governed

import com.example.domain.core.security.governance.ApprovalResolution
import com.example.domain.ports.governed.HumanApprovalRequest
import com.example.domain.ports.governed.HumanApprovalStorePort
import com.example.infrastructure.persistence.dao.HumanApprovalRequestDao
import com.example.infrastructure.persistence.entities.HumanApprovalRequestEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * RoomHumanApprovalStore — DURABLE approval store (v15, audit 2026 §17)
 * ============================================================================
 *
 * P1-15 / §17 FIX: approvals now survive process death. The previous
 * [InMemoryHumanApprovalStore] was volatile-only — a pending approval (and
 * an approved one-shot token) vanished on every process kill.
 *
 * Semantics preserved from the in-memory implementation:
 *  - one-shot token consumption (`markTokenConsumed` / `isTokenConsumed`);
 *  - TTL expiry sweep (`expireStale`);
 *  - pending re-use for the same (executionId, toolName).
 *
 * Correctness under contention: a per-store Mutex serializes the
 * read-modify-write cycles (create/resolve/consume) so the one-shot token
 * can never be consumed twice concurrently.
 */
class RoomHumanApprovalStore(
    private val dao: HumanApprovalRequestDao
) : HumanApprovalStorePort {

    private val mutationMutex = Mutex()

    override suspend fun create(request: HumanApprovalRequest): HumanApprovalRequest =
        withContext(Dispatchers.IO) {
            dao.upsert(request.toEntity())
            request
        }

    override suspend fun find(approvalId: String): HumanApprovalRequest? =
        withContext(Dispatchers.IO) { dao.find(approvalId)?.toDomain() }

    override suspend fun findPendingFor(executionId: String, toolName: String): HumanApprovalRequest? =
        withContext(Dispatchers.IO) { dao.findPendingFor(executionId, toolName)?.toDomain() }

    override suspend fun findPending(limit: Int): List<HumanApprovalRequest> =
        withContext(Dispatchers.IO) { dao.findPending(limit).map { it.toDomain() } }

    /** GAP-02 (ADR-2c): token TRANSPORT query. */
    override suspend fun findApprovedFor(executionId: String, toolName: String, nowEpochMs: Long): HumanApprovalRequest? =
        withContext(Dispatchers.IO) { dao.findApprovedFor(executionId, toolName, nowEpochMs)?.toDomain() }

    override suspend fun resolve(
        approvalId: String,
        resolution: ApprovalResolution,
        resolvedBy: String
    ): HumanApprovalRequest? = mutationMutex.withLock {
        withContext(Dispatchers.IO) {
            val current = dao.find(approvalId) ?: return@withContext null
            if (current.resolution != ApprovalResolution.PENDING.name) {
                return@withContext current.toDomain()
            }
            val now = System.currentTimeMillis()
            dao.resolve(approvalId, resolution.name, resolvedBy, now)
            current.copy(
                resolution = resolution.name,
                resolvedBy = resolvedBy,
                resolvedAtEpochMs = now
            ).toDomain()
        }
    }

    override suspend fun expireStale(nowEpochMs: Long): Int = withContext(Dispatchers.IO) {
        dao.expireStale(nowEpochMs)
    }

    override suspend fun markTokenConsumed(approvalId: String): Boolean = mutationMutex.withLock {
        withContext(Dispatchers.IO) {
            // ONE-SHOT semantics: an already-consumed token never authorizes
            // again (returns false — same contract as the in-memory store).
            if (dao.isTokenConsumed(approvalId) > 0) return@withContext false
            val exists = dao.find(approvalId) != null
            if (exists) dao.markTokenConsumed(approvalId)
            exists
        }
    }

    override suspend fun isTokenConsumed(approvalId: String): Boolean = withContext(Dispatchers.IO) {
        dao.isTokenConsumed(approvalId) > 0
    }

    // ---- mapping ----

    private fun HumanApprovalRequest.toEntity(): HumanApprovalRequestEntity = HumanApprovalRequestEntity(
        approvalId = approvalId,
        executionId = executionId,
        toolName = toolName,
        riskLevel = riskLevel,
        prompt = prompt,
        justification = justification,
        requestedAtEpochMs = requestedAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        resolution = resolution.name,
        resolvedBy = resolvedBy,
        resolvedAtEpochMs = resolvedAtEpochMs,
        isTokenConsumed = false
    )

    private fun HumanApprovalRequestEntity.toDomain(): HumanApprovalRequest = HumanApprovalRequest(
        approvalId = approvalId,
        executionId = executionId,
        toolName = toolName,
        riskLevel = riskLevel,
        prompt = prompt,
        justification = justification,
        requestedAtEpochMs = requestedAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        resolution = runCatching { ApprovalResolution.valueOf(resolution) }
            .getOrDefault(ApprovalResolution.PENDING),
        resolvedBy = resolvedBy,
        resolvedAtEpochMs = resolvedAtEpochMs
    )
}
