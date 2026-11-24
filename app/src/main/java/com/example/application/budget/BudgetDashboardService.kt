package com.example.application.budget

import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.dao.PeriodBalanceRow
import com.example.infrastructure.persistence.dao.ProviderCostRow
import com.example.infrastructure.persistence.entities.BudgetHoldEntity
import com.example.infrastructure.persistence.entities.BudgetLedgerEntryEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * ============================================================================
 * BudgetDashboardService — Stage 4: the live monitoring dashboard feeds
 * ============================================================================
 * Roadmap Phase 4: «لوحة متابعة حية (استهلاك حسب الوكيل/المزوّد/النموذج/
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
 *
 * STAGE 2B additions:
 *  - DERIVED PERIOD LIFECYCLE (WS-B): [PeriodLifecycle] is computed from
 *    the stored statuses + the window + the outstanding-hold count on
 *    every emission — a period whose window passed never shows as merely
 *    OPEN, and a period with outstanding accounting obligations says so
 *    instead of silently closing. No financial entry ever moves between
 *    periods; this is presentation-layer honesty only.
 *  - SCOPE-AWARE FEEDS (WS-E): [periodBalancesForScope] filters the
 *    periods of ONE accounting scope (the Budget screen shows the
 *    WORKSPACE's periods only — another workspace's periods are never
 *    presented as the active workspace's). The unscoped [periodBalances]
 *    stays for the legacy observatory view and compatibility.
 *  - TTL FRESHNESS WITHOUT DB MUTATION (WS-E): the scoped feed re-binds
 *    the Room query with a FRESH clock value on every refresh signal
 *    (default: every 60 seconds) — an expired reservation stops counting
 *    as an active reservation in the derived balance without any database
 *    write and without recreating the ViewModel. Table changes still emit
 *    immediately between ticks (each tick re-subscribes the live Room
 *    flow).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetDashboardService(
    private val database: AppDatabase,
    /** Injectable clock — every re-bind reads a FRESH value. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Re-evaluation signal for time-derived state (TTL freshness). The
     *  default ticks every 60s; tests inject a manual signal for
     *  deterministic control. */
    private val refreshSignal: Flow<Unit>? = null
) {

    /**
     * STAGE 2B (WS-B) — the DERIVED lifecycle state of a budget period.
     * Computed on read (never stored), so it can never diverge from the
     * accounting truth the way a stale stored status can:
     *
     *  - CLOSED: stored terminal marker (explicit closure — only written
     *    when the window ended AND no outstanding holds existed).
     *  - SUPERSEDED[_WITH_OUTSTANDING_HOLDS]: the owning policy version
     *    was retired by a newer immutable version; the period can no
     *    longer receive new holds but may still owe settlements.
     *  - ENDED[_WITH_OUTSTANDING_HOLDS]: the window passed; with no
     *    outstanding holds the period's accounting is complete (the
     *    stored status converges to CLOSED on the next bookkeeping pass).
     *  - ACTIVE: stored OPEN, covering the current instant.
     */
    enum class PeriodLifecycle {
        ACTIVE,
        ENDED,
        ENDED_WITH_OUTSTANDING_HOLDS,
        SUPERSEDED,
        SUPERSEDED_WITH_OUTSTANDING_HOLDS,
        CLOSED
    }

    /** Per-period balance rows (limit, posted, holds, available). */
    fun periodBalances(nowEpochMs: Long): Flow<List<PeriodBalanceRow>> =
        database.budgetDashboardDao().periodBalancesFlow(nowEpochMs)

    /**
     * STAGE 2B (WS-E) — the scope-filtered, freshness-preserving feed of
     * the Budget screen: the periods of ONE accounting scope, re-queried
     * with a fresh clock on every refresh signal (TTL-derived balances
     * update without any DB mutation).
     */
    fun periodBalancesForScope(
        scopeType: String,
        scopePlatformObjectId: String?
    ): Flow<List<PeriodBalanceRow>> =
        refreshBase().flatMapLatest {
            database.budgetDashboardDao().periodBalancesForScopeFlow(
                scopeType = scopeType,
                scopePlatformObjectId = scopePlatformObjectId,
                nowEpochMs = clock()
            )
        }

    /** The live ledger feed for one period. */
    fun entries(periodId: String): Flow<List<BudgetLedgerEntryEntity>> =
        database.budgetDashboardDao().entriesFlow(periodId)

    /** The live holds feed for one period. */
    fun holds(periodId: String): Flow<List<BudgetHoldEntity>> =
        database.budgetDashboardDao().holdsFlow(periodId)

    /** Calculated cost by provider over the pricing chain (I-37). */
    fun costByProvider(): Flow<List<ProviderCostRow>> =
        database.budgetDashboardDao().costByProviderFlow()

    /** STAGE 2B (WS-B) — the derived lifecycle state of a balance row. */
    fun lifecycleOf(row: PeriodBalanceRow, nowEpochMs: Long = clock()): PeriodLifecycle = when {
        row.periodStatus == "CLOSED" -> PeriodLifecycle.CLOSED
        row.policyStatus == "RETIRED" ->
            if (row.outstandingHoldCount > 0) PeriodLifecycle.SUPERSEDED_WITH_OUTSTANDING_HOLDS
            else PeriodLifecycle.SUPERSEDED
        nowEpochMs >= row.periodEndEpochMs ->
            if (row.outstandingHoldCount > 0) PeriodLifecycle.ENDED_WITH_OUTSTANDING_HOLDS
            else PeriodLifecycle.ENDED
        else -> PeriodLifecycle.ACTIVE
    }

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

    /** The refresh cadence: an immediate emission, then one per interval. */
    private fun refreshBase(): Flow<Unit> = refreshSignal ?: flow {
        emit(Unit)
        while (true) {
            delay(REFRESH_INTERVAL_MS)
            emit(Unit)
        }
    }

    companion object {
        /** Default re-evaluation cadence for time-derived state (TTL
         *  freshness without database mutation). */
        const val REFRESH_INTERVAL_MS = 60_000L
    }
}
