package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.budget.BudgetDashboardService
import com.example.application.budget.BudgetPolicyManagementService
import com.example.application.budget.PricingManagementService
import com.example.application.budget.BudgetLedgerService
import com.example.application.budget.EconomicGovernanceService
import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.PriceResolution
import com.example.domain.core.budget.PricingEntry
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
 * BudgetViewModel — STAGE 7 wiring + STAGE 2 governance activation
 * ============================================================================
 *
 * STAGE 7 (unchanged): the READ-ONLY projection over
 * [BudgetDashboardService] — period balance rows (limit / posted / holds /
 * available) with the service's utilization derivation, the selected
 * period's live ledger + holds feeds, and the calculated cost by provider
 * (I-37). Every feed is the service's Room-backed flow; nothing is cached
 * in the VM, nothing is fabricated.
 *
 * STAGE 2 (this stage): the Budget screen becomes the AUTHORITATIVE budget
 * management surface. The ViewModel exposes:
 *
 *  - [authority] — what governs the workspace RIGHT NOW: a modern
 *    accounting policy (with its live balance equation), a modern policy
 *    whose period has ended (spending denied until a new period opens), the
 *    legacy allocation (transition not started), or nothing (costs tracked,
 *    never denied). Reads only — observing never writes.
 *  - [configureWorkspaceBudget] — the production authoring action through
 *    [BudgetPolicyManagementService] (first policy / immutable new version /
 *    period rollover; repeated identical writes are verified no-ops).
 *  - [pricingEntries] + [savePricing] + [checkPricing] — the verified
 *    pricing management path ([PricingManagementService]): user-entered
 *    prices are stored as USER_CONFIG evidence, mirrored into the versioned
 *    catalog, and UNKNOWN pricing stays honestly UNKNOWN.
 *
 * The Stage 2 dependencies are OPTIONAL (null default): a ViewModel
 * constructed without them behaves exactly like the Stage 7 read-only
 * observatory (tests and any legacy construction site stay valid).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModel(
    private val dashboardService: BudgetDashboardService,
    /** Injectable clock for deterministic period snapshots (tests). */
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    /** Stage 2 — the modern budget policy authoring surface (optional). */
    private val policyManagement: BudgetPolicyManagementService? = null,
    /** Stage 2 — the verified pricing management surface (optional). */
    private val pricingManagement: PricingManagementService? = null,
    /** Stage 2 — the pricing RESOLUTION authority (read-only probe). */
    private val legacyEconomics: EconomicGovernanceService? = null,
    /** Stage 2 — the active workspace id provider (null = no workspace yet). */
    private val activeWorkspaceId: suspend () -> String? = { null }
) : ViewModel() {

    data class BudgetUiState(
        /** The selected period id (null = nothing selected yet). */
        val selectedPeriodId: String? = null,
        /** The feature's honest error channel. */
        val errorMessage: String? = null,
        /** Stage 2 — what governs the workspace right now (null = not yet loaded
         *  or the management surface is not wired — read-only mode). */
        val authority: BudgetPolicyManagementService.WorkspaceBudgetAuthority? = null,
        /** Stage 2 — the workspace id the authority projection belongs to. */
        val authorityWorkspaceId: String? = null,
        /** Stage 2 — all pricing evidence (config + discovery), live list. */
        val pricingEntries: List<PricingEntry> = emptyList(),
        /** Stage 2 — the pricing form's live knowledge probe result. */
        val formPricingResolution: PriceResolution? = null,
        /** Stage 2 — management action in flight (budget editor). */
        val isSavingBudget: Boolean = false,
        /** Stage 2 — management action in flight (pricing editor). */
        val isSavingPricing: Boolean = false,
        /** Stage 2 — the last management outcome (transient, honest). */
        val managementMessage: String? = null
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
        // Stage 2 — the authority + pricing READS on entry. Observing never
        // writes: no policy, period or pricing row is ever created by a
        // refresh (only explicit user actions write).
        refreshManagementState()
    }

    /** Selects the period whose ledger + holds feeds the screen renders. */
    fun selectPeriod(periodId: String) {
        _state.update { it.copy(selectedPeriodId = periodId) }
    }

    /** The service's own utilization derivation for a balance row. */
    fun utilization(row: PeriodBalanceRow): BudgetDashboardService.Utilization =
        dashboardService.utilization(row)

    /**
     * Stage 2 — reload the authority projection and the pricing list.
     * READ-ONLY: repeated calls never create or mutate anything.
     */
    fun refreshManagementState() {
        viewModelScope.launch {
            val wsId = runCatching { activeWorkspaceId() }.getOrNull()
            if (wsId != null && policyManagement != null) {
                runCatching { policyManagement.workspaceAuthority(wsId) }
                    .onSuccess { authority ->
                        _state.update {
                            it.copy(
                                authority = authority,
                                authorityWorkspaceId = wsId
                            )
                        }
                    }
                    .onFailure { failure ->
                        _state.update {
                            it.copy(
                                errorMessage = "تعذر تحميل سلطة الميزانية: ${failure.localizedMessage}"
                            )
                        }
                    }
            }
            if (pricingManagement != null) {
                runCatching { pricingManagement.listPricing() }
                    .onSuccess { entries ->
                        _state.update { it.copy(pricingEntries = entries) }
                    }
                    .onFailure { failure ->
                        _state.update {
                            it.copy(
                                errorMessage = "تعذر تحميل قائمة التسعير: ${failure.localizedMessage}"
                            )
                        }
                    }
            }
        }
    }

    /**
     * Stage 2 — the production authoring action: configure the workspace's
     * modern accounting budget (create the first policy, or edit → a new
     * immutable version, or roll over into a new period). [amountUsd] is
     * converted to micro units exactly like the legacy editor; the overage
     * policy and period length are explicit.
     */
    fun configureWorkspaceBudget(
        amountUsd: Double,
        overagePolicy: String = BudgetLedgerService.OVERAGE_HARD_LIMIT,
        periodDays: Int = 30
    ) {
        val management = policyManagement
        if (management == null) {
            _state.update { it.copy(errorMessage = "إدارة السياسات غير متاحة في هذا البناء.") }
            return
        }
        _state.update { it.copy(isSavingBudget = true, managementMessage = null) }
        viewModelScope.launch {
            val wsId = runCatching { activeWorkspaceId() }.getOrNull()
            if (wsId == null) {
                _state.update {
                    it.copy(
                        isSavingBudget = false,
                        errorMessage = "مساحة العمل لم تجهز بعد — تعذر تكوين الميزانية."
                    )
                }
                return@launch
            }
            runCatching {
                management.configureWorkspaceBudget(
                    BudgetPolicyManagementService.ConfigureWorkspaceBudgetRequest(
                        workspaceId = wsId,
                        limitMicroUnits = (amountUsd * 1_000_000.0).toLong(),
                        currency = "USD",
                        overagePolicy = overagePolicy,
                        periodStartEpochMs = nowEpochMs(),
                        periodEndEpochMs = nowEpochMs() + periodDays.toLong() * 86_400_000L
                    )
                )
            }.onSuccess { outcome ->
                _state.update {
                    it.copy(
                        isSavingBudget = false,
                        managementMessage = when (outcome) {
                            is BudgetPolicyManagementService.ConfigureOutcome.Created ->
                                "أُنشئت سياسة محاسبية جديدة مع أول فترة."
                            is BudgetPolicyManagementService.ConfigureOutcome.NewVersion ->
                                "أُنشئت نسخة سياسة جديدة غير قابلة للتعديل (v-next)."
                            is BudgetPolicyManagementService.ConfigureOutcome.NewPeriod ->
                                "فُتحت فترة محاسبية جديدة تحت السياسة الحالية."
                            is BudgetPolicyManagementService.ConfigureOutcome.Unchanged ->
                                "لا تغيير — السياسة الحالية مطابقة تماماً."
                        }
                    )
                }
                refreshManagementState()
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        isSavingBudget = false,
                        errorMessage = "تعذر تكوين الميزانية: ${failure.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Stage 2 — save verified pricing evidence through the management path.
     * Prices are per MILLION tokens in [currency]; the entry is stamped
     * USER_CONFIG and mirrored into the versioned catalog.
     */
    fun savePricing(
        providerId: String,
        serviceId: String?,
        modelId: String?,
        inputPricePerMillionUsd: Double?,
        outputPricePerMillionUsd: Double?,
        cachedInputPricePerMillionUsd: Double?,
        currency: String,
        billingClass: BillingClass = BillingClass.PAID,
        effectiveUntilEpochMs: Long? = null,
        sourceNote: String? = null
    ) {
        val management = pricingManagement
        if (management == null) {
            _state.update { it.copy(errorMessage = "إدارة التسعير غير متاحة في هذا البناء.") }
            return
        }
        _state.update { it.copy(isSavingPricing = true, managementMessage = null) }
        viewModelScope.launch {
            runCatching {
                management.registerUserConfiguredPricing(
                    PricingManagementService.UserPricingRequest(
                        providerId = providerId,
                        serviceId = serviceId?.takeIf { it.isNotBlank() },
                        modelId = modelId?.takeIf { it.isNotBlank() },
                        inputPriceMicroPerMillion = inputPricePerMillionUsd
                            ?.let { (it * 1_000_000.0).toLong() },
                        outputPriceMicroPerMillion = outputPricePerMillionUsd
                            ?.let { (it * 1_000_000.0).toLong() },
                        cachedInputPriceMicroPerMillion = cachedInputPricePerMillionUsd
                            ?.let { (it * 1_000_000.0).toLong() },
                        currency = currency,
                        billingClass = billingClass,
                        effectiveFromEpochMs = nowEpochMs(),
                        effectiveUntilEpochMs = effectiveUntilEpochMs,
                        sourceNote = sourceNote
                    )
                )
            }.onSuccess { outcome ->
                _state.update {
                    it.copy(
                        isSavingPricing = false,
                        managementMessage = when (outcome) {
                            is PricingManagementService.RegisterOutcome.Registered ->
                                "سُجّل تسعير موثّق (USER_CONFIG) واعتُمد في فهرس الإصدارات."
                            is PricingManagementService.RegisterOutcome.Unchanged ->
                                "لا تغيير — تسعير مطابق موجود فعلاً."
                        }
                    )
                }
                refreshManagementState()
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        isSavingPricing = false,
                        errorMessage = "تعذر تسجيل التسعير: ${failure.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Stage 2 — the honest knowledge probe behind the pricing form: is the
     * price for this provider/service/model KNOWN (and from where) or
     * UNKNOWN? UNKNOWN is a real answer, never a fabricated price.
     */
    fun checkPricing(providerId: String, serviceId: String?, modelId: String?) {
        val economics = legacyEconomics
        if (economics == null) {
            _state.update { it.copy(formPricingResolution = null) }
            return
        }
        viewModelScope.launch {
            runCatching {
                economics.resolvePricing(
                    providerId = providerId.takeIf { it.isNotBlank() },
                    serviceId = serviceId?.takeIf { it.isNotBlank() },
                    modelId = modelId?.takeIf { it.isNotBlank() }
                )
            }.onSuccess { resolution ->
                _state.update { it.copy(formPricingResolution = resolution) }
            }.onFailure {
                _state.update { it.copy(formPricingResolution = null) }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Dismisses the transient management message. */
    fun dismissManagementMessage() {
        _state.update { it.copy(managementMessage = null) }
    }
}
