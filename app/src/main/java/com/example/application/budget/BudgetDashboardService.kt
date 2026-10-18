package com.example.application.budget

import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.dao.PeriodBalanceRow
import com.example.infrastructure.persistence.dao.ProviderCostRow
import com.example.infrastructure.persistence.entities.BudgetHoldEntity
import com.example.infrastructure.persistence.entities.BudgetLedgerEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * BudgetDashboardService — Stage 4: the live monitoring dashboard feeds
 * ============================================================================
 * Roadmap Phase 4: «لوحة متابعة حية (استهلاك حسب الوكيل/المزود/النموذج/
 * مساحة العمل، ونسب الاستغلال، رصيد كل فترة)». The study, Section 9:
 * the presentation layer consumes Flows straight from Room — this service
 * is the thin, testable surface over BudgetDashboardDao (the SQL lives in
 * one place; the balance equation is recomputed from raw rows per
 * emission, never cached).
 *
 * Attribution honesty (I-37): provider attribution rides the pricing chain
 * (calculation -> version -> profile -> provider) and therefore covers
 * CALCULATED cost only; entries without a calculation line are excluded
 * from the provider breakdown and say so. Model/agent attribution rides
 * the observation domain and lands with its writers (next Stage-4
 * increment — the roadmap is cumulative by design).
 */
class BudgetDashboardService(private val database: AppDatabase) {

    /** Per-period balance rows (limit, posted, holds, available). */
    fun periodBalances(nowEpochMs: Long): Flow<List<PeriodBalanceRow>> =
        database.budgetDashboardDao().periodBalancesFlow(nowEpochMs)

    /** The live ledger feed for one period. */
    fun entries(periodId: String): Flow<List<BudgetLedgerEntryEntity>> =
        database.budgetDashboardDao().entriesFlow(periodId)

    /** The live holds feed for one period. */
    fun holds(periodId: String): Flow<List<BudgetHoldEntity>> =
        database.budgetDashboardDao().holdsFlow(periodId)

    /** Calculated cost by provider over the pricing chain (I-37). */
    fun costByProvider(): Flow<List<ProviderCostRow>> =
        database.budgetDashboardDao().costByProviderFlow()

    /**
     * Utilization ratios derived from a balance row: posted/limit (how much
     * of the period is consumed) and holds/limit (how much is reserved).
     * NaN when the limit is 0 (a zero-limit period is a monitoring signal,
     * not a division result).
     */
    fun utilization(row: PeriodBalanceRow): Utilization {
        val limit = row.limitMicroUnits.toFloat()
        return Utilization(
            periodId = row.periodId,
            postedToLimit = if (limit > 0f) row.postedEffectsMicroUnits.toFloat() / limit else Float.NaN,
            holdsToLimit = if (limit > 0f) row.remainingHoldMicroUnits.toFloat() / limit else Float.NaN,
            availableToLimit = if (limit > 0f) row.availableMicroUnits().toFloat() / limit else Float.NaN
        )
    }

    data class Utilization(
        val periodId: String,
        val postedToLimit: Float,
        val holdsToLimit: Float,
        val availableToLimit: Float
    )
}
