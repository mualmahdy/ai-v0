package com.example.application.budget

import com.example.domain.core.budget.BudgetAllocation
import com.example.domain.core.budget.BudgetScope
import com.example.domain.core.budget.BudgetScopeType
import com.example.domain.ports.budget.BudgetAllocationPort
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.BudgetPeriodEntity
import com.example.infrastructure.persistence.entities.BudgetPolicyEntity
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ============================================================================
 * BudgetPolicyManagementService — STAGE 2 (economic governance activation)
 * ============================================================================
 * The Stage-1..7 accounting core (BudgetLedgerService over budget_policy /
 * budget_period / budget_hold / budget_hold_settlement / budget_ledger_entry)
 * shipped with NO production authoring path: createBudget/createPolicyVersion
 * existed but only tests called them, the Governance screen wrote the LEGACY
 * budget_allocations row, and the Budget screen was a read-only projection.
 *
 * This service is the missing MANAGEMENT surface — the production path the
 * UI and use-cases call to configure a real workspace budget policy:
 *
 *   1. First configuration  → createBudget (scope descriptor + v1 policy +
 *                             first OPEN period) — nothing is created until
 *                             the user explicitly configures it (no default
 *                             monetary budget is ever seeded).
 *   2. Material edit        → createPolicyVersion — a NEW immutable version
 *                             row in the same family (PDM-G05); the previous
 *                             version is RETIRED, never mutated.
 *   3. Period rollover      → a new OPEN period row for the CURRENT policy
 *                             version when the governing window has ended and
 *                             the material fields are unchanged.
 *   4. Repeated identical writes / UI refreshes → verified NO-OP (one
 *                             policy, one governing period — never a
 *                             duplicate per screen refresh or retry).
 *
 * All writes go through [BudgetLedgerService] — the ONE accounting engine;
 * this service adds management semantics, never a second budget authority.
 *
 * LEGACY COMPATIBILITY CONTRACT (documented transition semantics):
 *  - The legacy budget_allocations path keeps working verbatim for
 *    workspaces that never configure a modern policy (the authorization gate
 *    falls back to it exactly as before).
 *  - Configuring a modern policy does NOT migrate, delete or reinterpret the
 *    legacy allocation: historical legacy spending authority stays where it
 *    is, and the modern ledger starts its own period from configuration
 *    time (no silent conversion of legacy consumed amounts into modern
 *    period balances).
 *  - While a modern policy is ACTIVE with a covering period, the modern
 *    accounting path takes precedence (the gate consults it first) — the
 *    legacy allocation becomes dormant, not deleted.
 *  - When the modern period ENDS and no new one is configured, the gate
 *    DENIES honestly (expired accounting period) instead of silently
 *    falling back to the stale legacy allocation — a workspace that was
 *    explicitly moved to the modern path never resurrects its old legacy
 *    spending authority by accident (enforced in HoldBasedBudgetGate, and
 *    visible through [workspaceAuthority]).
 */
class BudgetPolicyManagementService(
    private val database: AppDatabase,
    private val ledger: BudgetLedgerService,
    /** Read-only view of the legacy allocation for the authority projection;
     *  the SAME repository instance EconomicGovernanceService writes through
     *  in production (RoomEconomicStore). */
    private val allocationRepository: BudgetAllocationPort,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {

    /** A workspace budget configuration request (all monetary values in
     *  MICRO units of [currency]; USD micro = 1e-6 USD). */
    data class ConfigureWorkspaceBudgetRequest(
        val workspaceId: String,
        val limitMicroUnits: Long,
        val currency: String = "USD",
        /** Overage behavior: HARD_LIMIT | SOFT_LIMIT | REQUIRE_APPROVAL. */
        val overagePolicy: String = BudgetLedgerService.OVERAGE_HARD_LIMIT,
        /** Accounting period window. Defaults: start now, 30 days. */
        val periodStartEpochMs: Long? = null,
        val periodEndEpochMs: Long? = null,
        val budgetName: String = "workspace budget"
    )

    /** What a configuration call actually did (never silently different). */
    sealed class ConfigureOutcome {
        data class Created(
            val scopeId: String,
            val policyId: String,
            val policyFamilyId: String,
            val periodId: String
        ) : ConfigureOutcome()

        data class NewVersion(
            val scopeId: String,
            val policyId: String,
            val policyFamilyId: String,
            val periodId: String,
            val previousPolicyId: String
        ) : ConfigureOutcome()

        data class NewPeriod(
            val scopeId: String,
            val policyId: String,
            val policyFamilyId: String,
            val periodId: String
        ) : ConfigureOutcome()

        /** Idempotent no-op: the exact same policy already governs. */
        data class Unchanged(
            val scopeId: String,
            val policyId: String,
            val policyFamilyId: String,
            val periodId: String
        ) : ConfigureOutcome()
    }

    /**
     * The authoritative answer to "what governs spending for this workspace
     * right now" — the Budget screen's authority card and the Governance
     * screen's compatibility note both render from it.
     */
    data class WorkspaceBudgetAuthority(
        val workspaceId: String,
        /** Latest ACTIVE modern policy for the scope, regardless of period. */
        val modernPolicy: BudgetPolicyEntity?,
        /** The OPEN period covering `now` (null when none covers). */
        val modernPeriod: BudgetPeriodEntity?,
        /** The live balance equation of [modernPeriod] (posted + holds). */
        val modernBalance: BudgetLedgerService.AvailableBudget?,
        /** Modern policy exists but its accounting period has ended. */
        val modernPeriodEnded: Boolean,
        /** The legacy budget_allocations row (compatibility view, read-only). */
        val legacyAllocation: BudgetAllocation?,
        val kind: AuthorityKind
    ) {
        enum class AuthorityKind {
            /** A modern accounting policy + covering period govern execution. */
            MODERN_ACCOUNTING,
            /** A modern policy exists but no period covers now — spending is
             *  DENIED honestly until a new period is configured (never a
             *  silent legacy fallback). */
            MODERN_PERIOD_ENDED,
            /** No modern policy; the legacy allocation governs (transition
             *  not started). */
            LEGACY_ALLOCATION,
            /** No spending authority configured at all — costs are tracked
             *  but never denied (honest absence, NOT an implicit budget). */
            NONE
        }
    }

    /** Serializes configuration writes within the process (two rapid saves
     *  must not race two policy families into existence). */
    private val configureMutex = Mutex()

    /**
     * Configures (creates / edits / rolls over) the modern accounting budget
     * policy of a workspace. See the class doc for the decision table.
     */
    suspend fun configureWorkspaceBudget(
        request: ConfigureWorkspaceBudgetRequest
    ): ConfigureOutcome = configureMutex.withLock {
        require(request.workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(request.limitMicroUnits >= 0) { "limit must be >= 0 (a zero limit is a real monitoring ceiling)" }
        require(request.currency.isNotBlank()) { "currency must be explicit — never silently assumed" }
        require(
            request.overagePolicy in SUPPORTED_OVERAGE_POLICIES
        ) { "unsupported overage policy ${request.overagePolicy}" }

        val now = clock()
        val start = request.periodStartEpochMs ?: now
        val end = request.periodEndEpochMs ?: (start + DEFAULT_PERIOD_MS)
        require(start < end) { "period window invalid: [$start, $end)" }

        val currency = request.currency.trim().uppercase()

        val activePolicy = ledger.activePolicyForScopeIgnoringPeriod(
            scopeType = WORKSPACE_SCOPE_TYPE,
            platformObjectId = request.workspaceId,
            nowEpochMs = now
        )

        // ---- Case 1: first configuration → v1 policy family + first period.
        if (activePolicy == null) {
            val created = ledger.createBudget(
                BudgetLedgerService.CreateBudgetRequest(
                    scopeType = WORKSPACE_SCOPE_TYPE,
                    platformObjectType = WORKSPACE_SCOPE_TYPE,
                    platformObjectId = request.workspaceId,
                    providerId = null,
                    budgetName = request.budgetName,
                    currency = currency,
                    limitMicroUnits = request.limitMicroUnits,
                    periodStartEpochMs = start,
                    periodEndEpochMs = end,
                    overagePolicy = request.overagePolicy
                )
            )
            return ConfigureOutcome.Created(
                scopeId = created.scopeId,
                policyId = created.policyId,
                policyFamilyId = created.policyFamilyId,
                periodId = created.periodId
            )
        }

        val coveringPeriod = ledger.activePeriodForPolicy(activePolicy.budgetPolicyId, now)
        val materialFieldsEqual = activePolicy.defaultLimitMicroUnits == request.limitMicroUnits &&
            activePolicy.currency == currency &&
            activePolicy.overagePolicy == request.overagePolicy

        // ---- Case 2: identical policy already governs a covering period →
        //      verified no-op (repeated UI writes never duplicate anything).
        if (materialFieldsEqual && coveringPeriod != null) {
            return ConfigureOutcome.Unchanged(
                scopeId = activePolicy.scopeId,
                policyId = activePolicy.budgetPolicyId,
                policyFamilyId = activePolicy.policyFamilyId,
                periodId = coveringPeriod.budgetPeriodId
            )
        }

        // ---- Case 3: same policy, but the governing window ended → open the
        //      NEXT period for the CURRENT policy version (a new period is
        //      not a policy edit; the immutable version keeps governing).
        if (materialFieldsEqual && coveringPeriod == null) {
            val periodId = openPeriod(
                policy = activePolicy,
                limitMicroUnits = request.limitMicroUnits,
                start = start,
                end = end,
                now = now
            )
            return ConfigureOutcome.NewPeriod(
                scopeId = activePolicy.scopeId,
                policyId = activePolicy.budgetPolicyId,
                policyFamilyId = activePolicy.policyFamilyId,
                periodId = periodId
            )
        }

        // ---- Case 4: material edit → NEW immutable policy version in the
        //      same family (PDM-G05) with its own new OPEN period.
        val versioned = ledger.createPolicyVersion(
            familyId = activePolicy.policyFamilyId,
            updatedRequest = BudgetLedgerService.CreateBudgetRequest(
                scopeType = WORKSPACE_SCOPE_TYPE,
                platformObjectType = WORKSPACE_SCOPE_TYPE,
                platformObjectId = request.workspaceId,
                providerId = null,
                budgetName = request.budgetName,
                currency = currency,
                limitMicroUnits = request.limitMicroUnits,
                periodStartEpochMs = start,
                periodEndEpochMs = end,
                overagePolicy = request.overagePolicy
            )
        )
        return ConfigureOutcome.NewVersion(
            scopeId = versioned.scopeId,
            policyId = versioned.policyId,
            policyFamilyId = versioned.policyFamilyId,
            periodId = versioned.periodId,
            previousPolicyId = activePolicy.budgetPolicyId
        )
    }

    /**
     * The authoritative authority projection for a workspace: which budget
     * governs execution RIGHT NOW — the modern accounting policy (with its
     * live balance equation), the modern policy with an ended period, the
     * legacy allocation, or nothing at all.
     */
    suspend fun workspaceAuthority(
        workspaceId: String,
        nowEpochMs: Long = clock()
    ): WorkspaceBudgetAuthority {
        val policy = ledger.activePolicyForScopeIgnoringPeriod(
            scopeType = WORKSPACE_SCOPE_TYPE,
            platformObjectId = workspaceId,
            nowEpochMs = nowEpochMs
        )
        val period = policy?.let { ledger.activePeriodForPolicy(it.budgetPolicyId, nowEpochMs) }
        val balance = period?.let { ledger.availableForPeriod(it.budgetPeriodId, nowEpochMs) }
        val legacy = allocationRepository.allocationFor(
            BudgetScope(BudgetScopeType.WORKSPACE, workspaceId)
        )?.takeIf { it.isActive }

        val kind = when {
            policy != null && period != null -> WorkspaceBudgetAuthority.AuthorityKind.MODERN_ACCOUNTING
            policy != null -> WorkspaceBudgetAuthority.AuthorityKind.MODERN_PERIOD_ENDED
            legacy != null -> WorkspaceBudgetAuthority.AuthorityKind.LEGACY_ALLOCATION
            else -> WorkspaceBudgetAuthority.AuthorityKind.NONE
        }
        return WorkspaceBudgetAuthority(
            workspaceId = workspaceId,
            modernPolicy = policy,
            modernPeriod = period,
            modernBalance = balance,
            modernPeriodEnded = policy != null && period == null,
            legacyAllocation = legacy,
            kind = kind
        )
    }

    /** Appends the next OPEN period under [policy] — the period-rollover
     *  write path (the policy version itself stays immutable). */
    private suspend fun openPeriod(
        policy: BudgetPolicyEntity,
        limitMicroUnits: Long,
        start: Long,
        end: Long,
        now: Long
    ): String {
        val periodId = idGenerator()
        database.budgetPeriodDao().insert(
            BudgetPeriodEntity(
                budgetPeriodId = periodId,
                budgetPolicyId = policy.budgetPolicyId,
                periodStartEpochMs = start,
                periodEndEpochMs = end,
                limitMicroUnits = limitMicroUnits,
                periodStatus = BudgetLedgerService.PERIOD_OPEN,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )
        return periodId
    }

    companion object {
        const val WORKSPACE_SCOPE_TYPE = "WORKSPACE"

        /** Default accounting period: 30 days (explicit, documented). */
        const val DEFAULT_PERIOD_MS: Long = 30L * 24L * 60L * 60L * 1000L

        /** The overage behaviors the management path can configure — the
         *  gate's fail-closed mapping covers exactly these plus its legacy
         *  string aliases. */
        val SUPPORTED_OVERAGE_POLICIES = setOf(
            BudgetLedgerService.OVERAGE_HARD_LIMIT,
            BudgetLedgerService.OVERAGE_SOFT_LIMIT,
            BudgetLedgerService.OVERAGE_REQUIRE_APPROVAL
        )
    }
}
