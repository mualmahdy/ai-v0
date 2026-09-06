package com.example.application.testing

import com.example.domain.core.budget.BudgetAllocation
import com.example.domain.core.budget.BudgetScope
import com.example.domain.core.budget.BudgetScopeType
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.PricingEntry
import com.example.domain.core.budget.UsageCostRecord
import com.example.domain.core.radar.CapabilityChangeRecord
import com.example.domain.core.radar.CapabilityEvidence
import com.example.domain.core.radar.RadarCapabilityStatus
import com.example.domain.core.radar.RadarRecommendation
import com.example.domain.ports.budget.BudgetAllocationPort
import com.example.domain.ports.budget.CostAggregate
import com.example.domain.ports.budget.CostLedgerPort
import com.example.domain.ports.budget.PricingRepositoryPort
import com.example.domain.ports.radar.CapabilityRadarPersistencePort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * GovernanceTestFakes — in-memory port implementations for governance tests
 * ============================================================================
 *
 * These fakes implement the SAME domain ports Room implements in production.
 * They are honest: no fabricated defaults, UNKNOWN stays null, and the
 * consumed-cost aggregation mirrors the production SQL semantics.
 */

class FakePricingRepository : PricingRepositoryPort {
    val entries = ConcurrentHashMap<String, PricingEntry>()

    override suspend fun upsertPricing(entry: PricingEntry) {
        entries[entry.id] = entry
    }

    override suspend fun pricingById(id: String): PricingEntry? = entries[id]

    override suspend fun allPricing(): List<PricingEntry> = entries.values.toList()

    override suspend fun effectivePricingForProvider(providerId: String, atEpochMs: Long): List<PricingEntry> =
        entries.values.filter {
            it.providerId == providerId &&
                it.effectiveFromEpochMs <= atEpochMs &&
                (it.effectiveToEpochMs == null || it.effectiveToEpochMs >= atEpochMs)
        }

    override suspend fun deletePricing(id: String) {
        entries.remove(id)
    }

    override fun observePricing(): Flow<List<PricingEntry>> = MutableStateFlow(entries.values.toList())
}

class FakeCostLedger : CostLedgerPort {
    val records = mutableListOf<UsageCostRecord>()
    private val flow = MutableStateFlow<List<UsageCostRecord>>(emptyList())

    override suspend fun insert(record: UsageCostRecord) {
        records.add(record)
        flow.value = records.toList()
    }

    override suspend fun recordsForExecution(executionId: String): List<UsageCostRecord> =
        records.filter { it.executionId == executionId }

    override suspend fun recentRecordsForWorkspace(workspaceId: String, limit: Int): List<UsageCostRecord> =
        records.filter { it.workspaceId == workspaceId }.sortedByDescending { it.timestampEpochMs }.take(limit)

    override fun observeForWorkspace(workspaceId: String): Flow<List<UsageCostRecord>> =
        flow.map { list -> list.filter { it.workspaceId == workspaceId } }

    override suspend fun consumedCostForScope(
        scopeType: BudgetScopeType,
        scopeId: String,
        currency: String
    ): CostAggregate {
        val matching = records.filter { record ->
            val dimValue = when (scopeType) {
                BudgetScopeType.WORKSPACE -> record.workspaceId
                BudgetScopeType.AGENT -> record.agentId
                BudgetScopeType.TASK -> record.taskId
                BudgetScopeType.EXECUTION -> record.executionId
                BudgetScopeType.PROVIDER -> record.providerId
                BudgetScopeType.SERVICE -> record.serviceId
                BudgetScopeType.MODEL -> record.modelId
                else -> null
            }
            dimValue == scopeId
        }
        val known = matching.filter { it.costStatus != com.example.domain.core.budget.CostStatus.UNKNOWN && it.cost?.currency == currency }
        return CostAggregate(
            totalMicro = known.sumOf { it.cost?.amountMicro ?: 0L },
            currency = currency,
            recordCount = matching.size,
            hadUnknownCost = matching.any { it.costStatus == com.example.domain.core.budget.CostStatus.UNKNOWN }
        )
    }

    override suspend fun tokensConsumedForScope(scopeType: BudgetScopeType, scopeId: String): Long {
        return records.filter { record ->
            val dimValue = when (scopeType) {
                BudgetScopeType.WORKSPACE -> record.workspaceId
                BudgetScopeType.AGENT -> record.agentId
                BudgetScopeType.TASK -> record.taskId
                BudgetScopeType.EXECUTION -> record.executionId
                BudgetScopeType.PROVIDER -> record.providerId
                BudgetScopeType.SERVICE -> record.serviceId
                BudgetScopeType.MODEL -> record.modelId
                else -> null
            }
            dimValue == scopeId
        }.sumOf { it.usage.totalTokens.toLong() }
    }

    override suspend fun recentRecordsForIdentity(
        providerId: String?,
        modelId: String?,
        limit: Int
    ): List<UsageCostRecord> =
        records.filter { r ->
            (providerId == null || r.providerId == providerId) &&
                (modelId == null || r.modelId == modelId)
        }.sortedByDescending { it.timestampEpochMs }.take(limit)

    override suspend fun pruneOlderThan(epochMs: Long) {
        records.removeAll { it.timestampEpochMs < epochMs }
        flow.value = records.toList()
    }
}

class FakeBudgetAllocationRepository : BudgetAllocationPort {
    val allocations = ConcurrentHashMap<String, BudgetAllocation>()
    private val flow = MutableStateFlow<List<BudgetAllocation>>(emptyList())

    override suspend fun upsertAllocation(allocation: BudgetAllocation) {
        allocations[allocation.scope.key()] = allocation
        flow.value = allocations.values.toList()
    }

    override suspend fun allocationFor(scope: BudgetScope): BudgetAllocation? =
        allocations[scope.key()]?.takeIf { it.isActive }

    override suspend fun allocationsForType(scopeType: BudgetScopeType): List<BudgetAllocation> =
        allocations.values.filter { it.scope.scopeType == scopeType && it.isActive }

    override suspend fun allAllocations(): List<BudgetAllocation> = allocations.values.toList()

    override suspend fun deactivateAllocation(scope: BudgetScope) {
        allocations[scope.key()]?.let {
            allocations[scope.key()] = it.copy(isActive = false)
        }
        flow.value = allocations.values.toList()
    }

    override fun observeAllocations(): Flow<List<BudgetAllocation>> = flow

    override suspend fun allocatedTotal(scopeType: BudgetScopeType, currency: String): MoneyAmount {
        val matching = allocations.values.filter { it.scope.scopeType == scopeType && it.allocated.currency == currency }
        if (matching.isEmpty()) return MoneyAmount.unknown(currency)
        return MoneyAmount.of(matching.sumOf { it.allocated.amountMicro ?: 0L }, currency)
    }
}

class FakeRadarPersistence : CapabilityRadarPersistencePort {
    val evidence = mutableListOf<CapabilityEvidence>()
    val statuses = ConcurrentHashMap<String, RadarCapabilityStatus>()
    val changes = mutableListOf<CapabilityChangeRecord>()
    val recommendations = ConcurrentHashMap<String, RadarRecommendation>()
    private val statusesFlow = MutableStateFlow<List<RadarCapabilityStatus>>(emptyList())
    private val changesFlow = MutableStateFlow<List<CapabilityChangeRecord>>(emptyList())
    private val recommendationsFlow = MutableStateFlow<List<RadarRecommendation>>(emptyList())

    private fun key(capabilityKey: String, workspaceId: String?) = "$capabilityKey|${workspaceId ?: "\u0000"}"

    override suspend fun insertEvidence(evidence: CapabilityEvidence) {
        this.evidence.add(evidence)
    }

    override suspend fun insertEvidenceAll(evidence: List<CapabilityEvidence>) {
        this.evidence.addAll(evidence)
    }

    override suspend fun recentEvidenceForCapability(
        capabilityKey: String,
        workspaceId: String?,
        limit: Int
    ): List<CapabilityEvidence> =
        evidence.filter {
            it.capabilityKey == capabilityKey &&
                // Mirrors the Room DAO semantics: a null filter param sees
                // everything; global (null-workspace) rows count for any
                // workspace derivation; scoped rows only for their workspace.
                (workspaceId == null || it.workspaceId == null || it.workspaceId == workspaceId)
        }
            .sortedByDescending { it.timestampEpochMs }
            .take(limit)

    override suspend fun evidenceForExecution(executionId: String): List<CapabilityEvidence> =
        evidence.filter { it.executionId == executionId }

    override suspend fun countEvidenceForCapability(capabilityKey: String, workspaceId: String?): Int =
        evidence.count { it.capabilityKey == capabilityKey }

    override suspend fun pruneEvidenceOlderThan(epochMs: Long) {
        evidence.removeAll { it.timestampEpochMs < epochMs }
    }

    override suspend fun upsertCapabilityStatus(status: RadarCapabilityStatus) {
        statuses[key(status.capabilityKey, status.workspaceId)] = status
        statusesFlow.value = statuses.values.toList()
    }

    override suspend fun upsertCapabilityStatuses(statuses: List<RadarCapabilityStatus>) {
        statuses.forEach { upsertCapabilityStatus(it) }
    }

    override suspend fun getCapabilityStatus(
        capabilityKey: String,
        workspaceId: String?
    ): RadarCapabilityStatus? = statuses[key(capabilityKey, workspaceId)]

    override suspend fun capabilityStatusesForWorkspace(workspaceId: String?): List<RadarCapabilityStatus> =
        statuses.values.filter { it.workspaceId == workspaceId }

    override fun observeCapabilityStatuses(workspaceId: String?): Flow<List<RadarCapabilityStatus>> =
        statusesFlow.map { list -> list.filter { it.workspaceId == workspaceId } }

    override suspend fun insertChange(change: CapabilityChangeRecord) {
        changes.add(change)
        changesFlow.value = changes.sortedByDescending { it.detectedAtEpochMs }
    }

    override suspend fun recentChangesForWorkspace(workspaceId: String?, limit: Int): List<CapabilityChangeRecord> =
        changes.filter { it.workspaceId == workspaceId }.sortedByDescending { it.detectedAtEpochMs }.take(limit)

    override fun observeChanges(workspaceId: String?, limit: Int): Flow<List<CapabilityChangeRecord>> =
        changesFlow.map { list -> list.filter { it.workspaceId == workspaceId }.take(limit) }

    override suspend fun upsertRecommendations(recommendations: List<RadarRecommendation>) {
        recommendations.forEach { this.recommendations[it.id] = it }
        recommendationsFlow.value = this.recommendations.values.toList()
    }

    override suspend fun activeRecommendationsForWorkspace(workspaceId: String?): List<RadarRecommendation> =
        recommendations.values.filter { it.workspaceId == workspaceId && !it.isDismissed }

    override fun observeRecommendations(workspaceId: String?): Flow<List<RadarRecommendation>> =
        recommendationsFlow.map { list -> list.filter { it.workspaceId == workspaceId } }

    override suspend fun dismissRecommendation(id: String) {
        recommendations[id]?.let {
            recommendations[id] = it.copy(isDismissed = true)
            recommendationsFlow.value = recommendations.values.toList()
        }
    }
}
