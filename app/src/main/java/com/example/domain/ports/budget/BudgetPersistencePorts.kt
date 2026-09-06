package com.example.domain.ports.budget

import com.example.domain.core.budget.BudgetAllocation
import com.example.domain.core.budget.BudgetScope
import com.example.domain.core.budget.BudgetScopeType
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.PricingEntry
import com.example.domain.core.budget.UsageCostRecord
import kotlinx.coroutines.flow.Flow

/**
 * Pricing persistence: versioned unit-price entries at provider/service/
 * model scopes, with effective-time windows.
 */
interface PricingRepositoryPort {
    suspend fun upsertPricing(entry: PricingEntry)
    suspend fun pricingById(id: String): PricingEntry?
    suspend fun allPricing(): List<PricingEntry>
    suspend fun effectivePricingForProvider(providerId: String, atEpochMs: Long): List<PricingEntry>
    suspend fun deletePricing(id: String)
    /** Named distinctly from BudgetAllocationPort.observeAllocations so one
     *  class can implement both ports without a JVM signature clash. */
    fun observePricing(): Flow<List<PricingEntry>>
}

/**
 * Cost ledger persistence: append-only usage/cost accounting with
 * attribution and scope aggregation queries.
 */
interface CostLedgerPort {
    suspend fun insert(record: UsageCostRecord)
    suspend fun recordsForExecution(executionId: String): List<UsageCostRecord>
    suspend fun recentRecordsForWorkspace(workspaceId: String, limit: Int): List<UsageCostRecord>
    fun observeForWorkspace(workspaceId: String): Flow<List<UsageCostRecord>>
    /**
     * Sum of consumed cost for a scope in MICRO currency units of [currency].
     * Only records whose cost currency matches are summed; unknown-cost
     * records are excluded (never treated as zero silently — callers get
     * [hadUnknownCost] to surface uncertainty).
     */
    suspend fun consumedCostForScope(
        scopeType: BudgetScopeType,
        scopeId: String,
        currency: String
    ): CostAggregate
    suspend fun tokensConsumedForScope(scopeType: BudgetScopeType, scopeId: String): Long
    /** Recent usage history for a (model or provider) identity — feeds estimation. */
    suspend fun recentRecordsForIdentity(
        providerId: String?,
        modelId: String?,
        limit: Int
    ): List<UsageCostRecord>
    suspend fun pruneOlderThan(epochMs: Long)
}

/** Aggregate of a scope's consumed cost with honest unknown accounting. */
data class CostAggregate(
    val totalMicro: Long,
    val currency: String,
    val recordCount: Int,
    /** True when at least one record had UNKNOWN cost — the sum is a lower bound. */
    val hadUnknownCost: Boolean
)

/**
 * Monetary budget allocations per scope, with enforceable policies.
 */
interface BudgetAllocationPort {
    suspend fun upsertAllocation(allocation: BudgetAllocation)
    suspend fun allocationFor(scope: BudgetScope): BudgetAllocation?
    suspend fun allocationsForType(scopeType: BudgetScopeType): List<BudgetAllocation>
    suspend fun allAllocations(): List<BudgetAllocation>
    suspend fun deactivateAllocation(scope: BudgetScope)
    /** Named distinctly from PricingRepositoryPort.observePricing so one
     *  class can implement both ports without a JVM signature clash. */
    fun observeAllocations(): Flow<List<BudgetAllocation>>
    /** Sum helper for hierarchical evaluation (consumed already known). */
    suspend fun allocatedTotal(scopeType: BudgetScopeType, currency: String): MoneyAmount
}
