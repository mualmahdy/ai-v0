package com.example.application.usecases

import com.example.application.budget.EconomicGovernanceService
import com.example.domain.core.budget.BudgetPolicy
import com.example.domain.core.budget.BudgetPolicyAction
import com.example.domain.core.budget.BudgetScope
import com.example.domain.core.budget.BudgetScopeType
import com.example.domain.core.budget.MoneyAmount

/**
 * ============================================================================
 * ManageWorkspaceBudgetUseCase — GAP-19 (Design Closure 2026, ADR-6 step 2)
 * ============================================================================
 *
 * Extracted from MainViewModel.setWorkspaceBudgetAllocationUsd — the budget
 * POLICY (HARD_LIMIT + AUTO_LOCAL_FALLBACK, warn at 80%, micro-USD
 * conversion) is application governance logic that lived as literals in
 * the ViewModel. The policy is enforced at BOTH the decide-time gate
 * (DecisionService) and the pre-execution gate
 * (ExecutionService.executeLlmStep — GAP-05/ADR-5); local tools carry no
 * cash cost and are honestly not cash-denied.
 */
class ManageWorkspaceBudgetUseCase(
    private val economicGovernanceService: EconomicGovernanceService
) {

    operator suspend fun invoke(
        workspaceId: String,
        amountUsd: Double
    ) {
        val scope = BudgetScope(BudgetScopeType.WORKSPACE, workspaceId)
        economicGovernanceService.setAllocation(
            scope = scope,
            allocated = MoneyAmount.of((amountUsd * 1_000_000.0).toLong(), "USD"),
            policy = BudgetPolicy(
                actions = listOf(
                    BudgetPolicyAction.HARD_LIMIT,
                    BudgetPolicyAction.AUTO_LOCAL_FALLBACK
                ),
                warnThresholdRatio = 0.8f
            )
        )
    }
}
