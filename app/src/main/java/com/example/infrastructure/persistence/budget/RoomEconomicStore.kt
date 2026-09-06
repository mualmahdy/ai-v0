package com.example.infrastructure.persistence.budget

import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.BudgetAllocation
import com.example.domain.core.budget.BudgetPolicy
import com.example.domain.core.budget.BudgetPolicyAction
import com.example.domain.core.budget.BudgetScope
import com.example.domain.core.budget.BudgetScopeType
import com.example.domain.core.budget.CostStatus
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.PricingEntry
import com.example.domain.core.budget.PricingScope
import com.example.domain.core.budget.TokenUsageRecord
import com.example.domain.core.budget.UsageCostRecord
import com.example.domain.ports.budget.BudgetAllocationPort
import com.example.domain.ports.budget.CostAggregate
import com.example.domain.ports.budget.CostLedgerPort
import com.example.domain.ports.budget.PricingRepositoryPort
import com.example.infrastructure.persistence.dao.BudgetAllocationDao
import com.example.infrastructure.persistence.dao.CostLedgerEntryDao
import com.example.infrastructure.persistence.dao.PricingEntryDao
import com.example.infrastructure.persistence.entities.BudgetAllocationEntity
import com.example.infrastructure.persistence.entities.CostLedgerEntryEntity
import com.example.infrastructure.persistence.entities.PricingEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed persistence for the economic governance subsystem:
 * pricing entries, the cost ledger, and budget allocations.
 */
class RoomEconomicStore(
    private val pricingDao: PricingEntryDao,
    private val ledgerDao: CostLedgerEntryDao,
    private val allocationDao: BudgetAllocationDao
) : PricingRepositoryPort, CostLedgerPort, BudgetAllocationPort {

    // ================= PricingRepositoryPort =================

    override suspend fun upsertPricing(entry: PricingEntry) {
        pricingDao.upsert(entry.toEntity())
    }

    override suspend fun pricingById(id: String): PricingEntry? =
        pricingDao.byId(id)?.toDomain()

    override suspend fun allPricing(): List<PricingEntry> =
        pricingDao.all().map { it.toDomain() }

    override suspend fun effectivePricingForProvider(providerId: String, atEpochMs: Long): List<PricingEntry> =
        pricingDao.effectiveForProvider(providerId, atEpochMs).map { it.toDomain() }

    override suspend fun deletePricing(id: String) {
        pricingDao.delete(id)
    }

    override fun observePricing(): Flow<List<PricingEntry>> =
        pricingDao.observeAll().map { list -> list.map { it.toDomain() } }

    // ================= CostLedgerPort =================

    override suspend fun insert(record: UsageCostRecord) {
        ledgerDao.insert(record.toEntity())
    }

    override suspend fun recordsForExecution(executionId: String): List<UsageCostRecord> =
        ledgerDao.forExecution(executionId).map { it.toDomain() }

    override suspend fun recentRecordsForWorkspace(workspaceId: String, limit: Int): List<UsageCostRecord> =
        ledgerDao.recentForWorkspace(workspaceId, limit).map { it.toDomain() }

    override fun observeForWorkspace(workspaceId: String): Flow<List<UsageCostRecord>> =
        ledgerDao.observeForWorkspace(workspaceId).map { list -> list.map { it.toDomain() } }

    override suspend fun consumedCostForScope(
        scopeType: BudgetScopeType,
        scopeId: String,
        currency: String
    ): CostAggregate {
        val total = ledgerDao.consumedCostMicro(scopeType.name, scopeId, currency)
        val unknownCount = ledgerDao.unknownCostRecordCount(scopeType.name, scopeId, currency)
        val count = ledgerDao.recordCountForScope(scopeType.name, scopeId)
        return CostAggregate(
            totalMicro = total,
            currency = currency,
            recordCount = count,
            hadUnknownCost = unknownCount > 0
        )
    }

    override suspend fun tokensConsumedForScope(scopeType: BudgetScopeType, scopeId: String): Long =
        ledgerDao.tokensConsumed(scopeType.name, scopeId)

    override suspend fun recentRecordsForIdentity(
        providerId: String?,
        modelId: String?,
        limit: Int
    ): List<UsageCostRecord> =
        ledgerDao.recentForIdentity(providerId, modelId, limit).map { it.toDomain() }

    override suspend fun pruneOlderThan(epochMs: Long) {
        ledgerDao.pruneOlderThan(epochMs)
    }

    // ================= BudgetAllocationPort =================

    override suspend fun upsertAllocation(allocation: BudgetAllocation) {
        allocationDao.upsert(allocation.toEntity())
    }

    override suspend fun allocationFor(scope: BudgetScope): BudgetAllocation? =
        allocationDao.forScope(scope.scopeType.name, scope.scopeId)?.takeIf { it.isActive }?.toDomain()

    override suspend fun allocationsForType(scopeType: BudgetScopeType): List<BudgetAllocation> =
        allocationDao.forType(scopeType.name).map { it.toDomain() }

    override suspend fun allAllocations(): List<BudgetAllocation> =
        allocationDao.all().map { it.toDomain() }

    override suspend fun deactivateAllocation(scope: BudgetScope) {
        allocationDao.deactivate(scope.scopeType.name, scope.scopeId, System.currentTimeMillis())
    }

    override fun observeAllocations(): Flow<List<BudgetAllocation>> =
        allocationDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun allocatedTotal(scopeType: BudgetScopeType, currency: String): MoneyAmount {
        val allocations = allocationsForType(scopeType)
        val matching = allocations.filter { it.allocated.currency == currency }
        if (matching.isEmpty()) return MoneyAmount.unknown(currency)
        return MoneyAmount.of(matching.sumOf { it.allocated.amountMicro ?: 0L }, currency)
    }

    // ================= Mappers =================

    private fun PricingEntry.toEntity() = PricingEntryEntity(
        id = id,
        scopeType = scope.name,
        providerId = providerId,
        serviceId = serviceId,
        modelId = modelId,
        inputPriceMicroPerMillion = inputPricePerMillion?.amountMicro,
        outputPriceMicroPerMillion = outputPricePerMillion?.amountMicro,
        cachedInputPriceMicroPerMillion = cachedInputPricePerMillion?.amountMicro,
        currency = (inputPricePerMillion ?: outputPricePerMillion ?: cachedInputPricePerMillion)?.currency
            // Bookkeeping currency for price-less entries (billing-class-only
            // declarations); USD is the documented default ledger currency.
            ?: "USD",
        billingClass = billingClass.name,
        pricingVersion = pricingVersion,
        effectiveFromEpochMs = effectiveFromEpochMs,
        effectiveToEpochMs = effectiveToEpochMs,
        provenance = provenance,
        createdAtEpochMs = System.currentTimeMillis()
    )

    private fun PricingEntryEntity.toDomain(): PricingEntry {
        val currency = currency
        val input = inputPriceMicroPerMillion?.let { MoneyAmount.of(it, currency) }
        val output = outputPriceMicroPerMillion?.let { MoneyAmount.of(it, currency) }
        val cached = cachedInputPriceMicroPerMillion?.let { MoneyAmount.of(it, currency) }
        return PricingEntry(
            id = id,
            scope = runCatching { PricingScope.valueOf(scopeType) }.getOrDefault(PricingScope.PROVIDER),
            providerId = providerId,
            serviceId = serviceId,
            modelId = modelId,
            inputPricePerMillion = input,
            outputPricePerMillion = output,
            cachedInputPricePerMillion = cached,
            billingClass = runCatching { BillingClass.valueOf(billingClass) }.getOrDefault(BillingClass.UNKNOWN),
            pricingVersion = pricingVersion,
            effectiveFromEpochMs = effectiveFromEpochMs,
            effectiveToEpochMs = effectiveToEpochMs,
            provenance = provenance
        )
    }

    private fun UsageCostRecord.toEntity() = CostLedgerEntryEntity(
        id = id,
        executionId = executionId,
        taskId = taskId,
        workspaceId = workspaceId,
        agentId = agentId,
        providerId = providerId,
        serviceId = serviceId,
        modelId = modelId,
        resourceId = resourceId,
        inputTokens = usage.inputTokens,
        outputTokens = usage.outputTokens,
        cachedTokens = usage.cachedTokens,
        totalTokens = usage.totalTokens,
        isEstimate = usage.isEstimate,
        appliedInputPriceMicroPerMillion = appliedPricing?.inputPricePerMillion?.amountMicro,
        appliedOutputPriceMicroPerMillion = appliedPricing?.outputPricePerMillion?.amountMicro,
        appliedCachedInputPriceMicroPerMillion = appliedPricing?.cachedInputPricePerMillion?.amountMicro,
        appliedPricingVersion = appliedPricing?.pricingVersion,
        costAmountMicro = cost?.amountMicro,
        currency = cost?.currency ?: appliedPricing?.inputPricePerMillion?.currency ?: "USD",
        costStatus = costStatus.name,
        billingClass = billingClass.name,
        timestampEpochMs = timestampEpochMs
    )

    private fun CostLedgerEntryEntity.toDomain(): UsageCostRecord {
        val hasPricing = appliedPricingVersion != null
        val currency0 = currency
        val appliedPricing: PricingEntry? = if (hasPricing) {
            PricingEntry(
                id = "applied:${appliedPricingVersion}",
                scope = PricingScope.MODEL,
                providerId = providerId ?: "",
                serviceId = serviceId,
                modelId = modelId,
                inputPricePerMillion = appliedInputPriceMicroPerMillion?.let { MoneyAmount.of(it, currency0) },
                outputPricePerMillion = appliedOutputPriceMicroPerMillion?.let { MoneyAmount.of(it, currency0) },
                cachedInputPricePerMillion = appliedCachedInputPriceMicroPerMillion?.let { MoneyAmount.of(it, currency0) },
                pricingVersion = appliedPricingVersion!!,
                effectiveFromEpochMs = 0L,
                provenance = "LEDGER_SNAPSHOT"
            )
        } else null

        return UsageCostRecord(
            id = id,
            executionId = executionId,
            taskId = taskId,
            workspaceId = workspaceId,
            agentId = agentId,
            providerId = providerId,
            serviceId = serviceId,
            modelId = modelId,
            resourceId = resourceId,
            usage = TokenUsageRecord(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedTokens = cachedTokens,
                providerTotalTokens = totalTokens,
                isEstimate = isEstimate
            ),
            appliedPricing = appliedPricing,
            cost = costAmountMicro?.let { MoneyAmount.of(it, currency0) },
            costStatus = runCatching { CostStatus.valueOf(costStatus) }.getOrDefault(CostStatus.UNKNOWN),
            billingClass = runCatching { BillingClass.valueOf(billingClass) }.getOrDefault(BillingClass.UNKNOWN),
            timestampEpochMs = timestampEpochMs
        )
    }

    private fun BudgetAllocation.toEntity() = BudgetAllocationEntity(
        scopeType = scope.scopeType.name,
        scopeId = scope.scopeId,
        allocatedAmountMicro = allocated.amountMicro ?: 0L,
        currency = allocated.currency,
        policyActionsCsv = policy.actions.joinToString(",") { it.name },
        warnThresholdRatio = policy.warnThresholdRatio,
        policyNote = policy.note,
        isActive = isActive,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs
    )

    private fun BudgetAllocationEntity.toDomain() = BudgetAllocation(
        scope = BudgetScope(
            scopeType = runCatching { BudgetScopeType.valueOf(scopeType) }.getOrDefault(BudgetScopeType.WORKSPACE),
            scopeId = scopeId
        ),
        allocated = MoneyAmount.of(allocatedAmountMicro, currency),
        policy = BudgetPolicy(
            actions = policyActionsCsv.split(",")
                .mapNotNull { runCatching { BudgetPolicyAction.valueOf(it.trim()) }.getOrNull() },
            warnThresholdRatio = warnThresholdRatio,
            note = policyNote
        ),
        isActive = isActive,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs
    )
}
