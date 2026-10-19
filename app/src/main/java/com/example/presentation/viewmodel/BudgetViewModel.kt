package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.budget.BudgetDashboardService
import com.example.infrastructure.persistence.dao.PeriodBalanceRow
import com.example.infrastructure.persistence.dao.ProviderCostRow
import com.example.infrastructure.persistence.entities.BudgetHoldEntity
import com.example.infrastructure.persistence.entities.BudgetLedgerEntryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * BudgetViewModel — STAGE 7 (wiring the accounting budget core to the UI)
 * ============================================================================
 *
 * The budget intelligence system (schema v23..v28 — allocation periods,
 * holds, the append-only ledger, cost calculations, reconciliation,
 * derived-state projection) shipped across six verified stages with ZERO
 * user-facing surface: [BudgetDashboardService] sat instantiated in the
 * container with no consumer. This ViewModel is that consumer's state
 * owner — the READ-ONLY projection the new Budget screen composes on:
 *
 *  - the period balance rows (limit / posted / holds / available) with the
 *    service's own utilization derivation;
 *  - the per-period live ledger + holds feeds of the SELECTED period;
 *  - the calculated cost by provider over the pricing chain (I-37).
 *
 * Every feed is the service's Room-backed flow — nothing is cached in the
 * VM, nothing is fabricated; an empty database renders as an honest empty
 * state, never as zeros-that-mean-nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModel(
    private val dashboardService: BudgetDashboardService,
    /** Injectable clock for deterministic period snapshots (tests). */
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : ViewModel() {

    data class BudgetUiState(
        /** The selected period id (null = nothing selected yet). */
        val selectedPeriodId: String? = null,
        /** The feature's honest error channel. */
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(BudgetUiState())
    val state: StateFlow<BudgetUiState> = _state.asStateFlow()

    /** All period balance rows (live, Room-backed). */
    val periodBalances: StateFlow<List<PeriodBalanceRow>> =
        dashboardService.periodBalances(nowEpochMs())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The calculated cost by provider over the pricing chain (live). */
    val costByProvider: StateFlow<List<ProviderCostRow>> =
        dashboardService.costByProvider()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The selected period's live ledger feed (empty until a period is chosen). */
    val ledgerEntries: StateFlow<List<BudgetLedgerEntryEntity>> =
        state.flatMapLatest { s ->
            if (s.selectedPeriodId == null) flowOf(emptyList())
            else dashboardService.entries(s.selectedPeriodId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The selected period's live holds feed (empty until a period is chosen). */
    val holds: StateFlow<List<BudgetHoldEntity>> =
        state.flatMapLatest { s ->
            if (s.selectedPeriodId == null) flowOf(emptyList())
            else dashboardService.holds(s.selectedPeriodId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Auto-select the first period once rows arrive (the user lands on a
        // real period, not an empty pane next to existing data).
        viewModelScope.launch {
            periodBalances.collect { rows ->
                if (_state.value.selectedPeriodId == null && rows.isNotEmpty()) {
                    _state.update { it.copy(selectedPeriodId = rows.first().periodId) }
                }
            }
        }
    }

    /** Selects the period whose ledger + holds feeds the screen renders. */
    fun selectPeriod(periodId: String) {
        _state.update { it.copy(selectedPeriodId = periodId) }
    }

    /** The service's own utilization derivation for a balance row. */
    fun utilization(row: PeriodBalanceRow): BudgetDashboardService.Utilization =
        dashboardService.utilization(row)

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
