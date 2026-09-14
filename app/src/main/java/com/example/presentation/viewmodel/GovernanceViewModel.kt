package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.budget.EconomicGovernanceService
import com.example.application.governed.HumanApprovalGate
import com.example.application.radar.CapabilityRadarService
import com.example.application.security.PermissionGrantService
import com.example.application.usecases.ManageWorkspaceBudgetUseCase
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.budget.BudgetStatus
import com.example.domain.core.budget.UsageCostRecord
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.observability.MeasurementHealth
import com.example.domain.core.radar.CapabilityChangeRecord
import com.example.domain.core.radar.RadarCapabilityStatus
import com.example.domain.core.radar.RadarRecommendation
import com.example.domain.ports.governed.HumanApprovalRequest
import com.example.domain.ports.observability.TelemetryPort
import com.example.infrastructure.network.NetworkMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * GovernanceViewModel — ADR-6 slice 4 (Design Closure 2026 UI-redesign track)
 * ============================================================================
 *
 * The governance observatory (capability radar + economic budget + the human
 * approval surface) left the MainViewModel per ADR-6 option-ج, with its whole
 * dependency set: capabilityRadarService, economicGovernanceService,
 * humanApprovalGate, permissionGrantService, manageWorkspaceBudgetUseCase,
 * networkMonitorProvider and the local principal id.
 *
 * STATE: the 10 governance UiState fields (radarCapabilityStatuses /
 * radarRecommendations / radarChanges / workspaceBudgetStatus /
 * costLedgerRecent / workspaceTokensConsumed / budgetAllocationInputUsd /
 * isSavingBudgetAllocation / pendingApprovals / measurementHealth) moved to
 * this feature-owned [GovernanceUiState] with its own error + banner
 * channels.
 *
 * BEHAVIOR (moved verbatim unless noted): the Room-backed radar observers,
 * the on-demand observatory refresh (snapshot + budget/ledger summary +
 * measurement health + the approval queue), the budget allocation editor,
 * the radar-recommendation dismissal, and the FULL approval loop
 * (approve / reject / "allow always" standing grant with ADR-2c device-user
 * principal attribution).
 *
 * RE-WIRING (the one deliberate repair, slice-2 precedent): the radar
 * observers used the stacked-collector pattern (a new inner collector per
 * active-workspace emission, the previous one never cancelled — the same
 * last-writer race family the slice-2 sessions registry had). Rewritten
 * with flatMapLatest: the observatory reflects ONLY the active workspace.
 *
 * The SESSION network policy stays an execution-time input of the radar
 * snapshot: this ViewModel keeps its own display mirror synced from the
 * studio signal bus (the same explicit-seam pattern as MainViewModel's
 * decision mirrors — no shared mutable state between the ViewModels).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GovernanceViewModel(
    private val workspaceRuntimeService: WorkspaceRuntimeService,
    private val capabilityRadarService: CapabilityRadarService? = null,
    private val economicGovernanceService: EconomicGovernanceService? = null,
    private val humanApprovalGate: HumanApprovalGate? = null,
    private val permissionGrantService: PermissionGrantService? = null,
    private val manageWorkspaceBudgetUseCase: ManageWorkspaceBudgetUseCase? = null,
    private val networkMonitorProvider: NetworkMonitor? = null,
    private val telemetryPort: TelemetryPort? = null,
    /**
     * GAP-02 (Design Closure 2026, ADR-2): the EXPLICIT local principal —
     * approvals/grants resolve with a durable device-local user id instead
     * of the anonymous "user" constant.
     */
    private val localPrincipalId: String = "local-device-user",
    /**
     * ADR-6 SLICE 4: the STUDIO signal bus — this ViewModel COLLECTS the
     * session network-policy changes (a radar-snapshot input); the decision
     * mirrors stay in MainViewModel, each feature its own collector.
     */
    private val studioSignals: StudioSignalSource? = null
) : ViewModel() {

    data class GovernanceUiState(
        // GOVERNANCE PHASE — Capability Radar observatory (backend truth,
        // Room-backed flows; nothing fabricated or UI-assumed).
        val radarCapabilityStatuses: List<RadarCapabilityStatus> = emptyList(),
        val radarRecommendations: List<RadarRecommendation> = emptyList(),
        val radarChanges: List<CapabilityChangeRecord> = emptyList(),
        val workspaceBudgetStatus: BudgetStatus? = null,
        val costLedgerRecent: List<UsageCostRecord> = emptyList(),
        val workspaceTokensConsumed: Long = 0L,
        val budgetAllocationInputUsd: String = "",
        val isSavingBudgetAllocation: Boolean = false,
        // GAP-02 (Design Closure 2026, ADR-2): the HUMAN APPROVAL SURFACE —
        // pending consent requests rendered by the governance observatory.
        val pendingApprovals: List<HumanApprovalRequest> = emptyList(),
        // GAP-24 (Design Closure 2026, ADR-8): honest measurement-health
        // snapshot — the telemetry persistence layer's own failure counters.
        // Null = not yet measured (the fetch runs with refreshGovernance).
        val measurementHealth: MeasurementHealth? = null,
        /**
         * DISPLAY MIRROR ONLY (ADR-6 slice 4): the SESSION network policy is
         * owned by StudioViewModel; this mirror is synced from the studio
         * signal bus so the radar snapshot reads ONE shared value. The
         * persisted WORKSPACE policy (egress authority) lives on the
         * workspace row — a different, unrelated field.
         */
        val networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        /** The feature's own transient diagnostic channel (local banner). */
        val diagnosticBanner: String? = null,
        /** The feature's own honest error channel (global snackbar). */
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(GovernanceUiState())
    val state: StateFlow<GovernanceUiState> = _state.asStateFlow()

    init {
        // GOVERNANCE PHASE: subscribe the observatory to the backend-truth
        // radar flows (Room-backed, survive process death).
        observeGovernance()
        refreshGovernance()
        // ADR-6 SLICE 4: the studio bus collector — the session network
        // policy is a radar-snapshot input; keep the feature's own mirror.
        observeStudioSignals()
    }

    /**
     * ADR-6 SLICE 4 — the studio signal bus collector. Only the
     * NetworkPolicyChanged projection is governance-relevant (the decision
     * mirrors are MainViewModel's; each feature collects its own stake).
     */
    private fun observeStudioSignals() {
        val signals = studioSignals ?: return
        viewModelScope.launch {
            signals.collect { signal ->
                when (signal) {
                    is StudioSignal.NetworkPolicyChanged ->
                        _state.update { it.copy(networkPolicy = signal.policy) }
                    is StudioSignal.ExecutionEvent -> Unit
                }
            }
        }
    }

    /**
     * GOVERNANCE PHASE — observatory data sources: radar statuses /
     * recommendations / changes flow straight from Room; budget + ledger
     * refresh on demand (suspend queries). All backend truth, no fabrication.
     *
     * (ADR-6 slice 4 repair, slice-2 precedent: the observers are
     * flatMapLatest-scoped to the ACTIVE workspace — the inherited
     * stacked-collector pattern kept every previous workspace's collector
     * alive, a last-writer race across workspace scopes.)
     */
    private fun observeGovernance() {
        val radar = capabilityRadarService ?: return
        viewModelScope.launch {
            runCatching {
                workspaceRuntimeService.activeWorkspace
                    .flatMapLatest { workspace -> radar.observeCapabilityStatuses(workspace?.id) }
                    .collect { statuses ->
                        _state.update { it.copy(radarCapabilityStatuses = statuses) }
                    }
            }
        }
        viewModelScope.launch {
            runCatching {
                workspaceRuntimeService.activeWorkspace
                    .flatMapLatest { workspace -> radar.observeRecommendations(workspace?.id) }
                    .collect { recos ->
                        _state.update { it.copy(radarRecommendations = recos) }
                    }
            }
        }
        viewModelScope.launch {
            runCatching {
                workspaceRuntimeService.activeWorkspace
                    .flatMapLatest { workspace -> radar.observeChanges(workspace?.id) }
                    .collect { changes ->
                        _state.update { it.copy(radarChanges = changes) }
                    }
            }
        }
    }

    /**
     * GOVERNANCE PHASE — on-demand refresh: derives a fresh radar snapshot
     * (evidence + registry facts) and reloads the budget/ledger summaries
     * for the active workspace.
     */
    fun refreshGovernance() {
        // GAP-02: the approval surface refreshes with the observatory.
        refreshPendingApprovals()
        viewModelScope.launch {
            // P0-03: bootstrap-aware — skip honestly when no workspace yet.
            val wsId = workspaceRuntimeService.awaitActiveWorkspaceId() ?: run {
                _state.update { it.copy(diagnosticBanner = "مساحة العمل لم تجهز بعد — تعذر تحديث الحوكمة.") }
                return@launch
            }
            val netAvailable = networkMonitorProvider?.isNetworkAvailable?.value ?: false
            runCatching {
                val snapshot = capabilityRadarService?.deriveSnapshot(
                    workspaceId = wsId,
                    networkPolicy = _state.value.networkPolicy,
                    isNetworkAvailable = netAvailable
                )
                if (snapshot != null) {
                    _state.update {
                        it.copy(
                            radarCapabilityStatuses = snapshot.capabilities,
                            radarRecommendations = snapshot.recommendations,
                            radarChanges = snapshot.changes
                        )
                    }
                }
            }.onFailure { failure ->
                // GAP-13: the refresh degradations are surfaced, not swallowed.
                _state.update {
                    it.copy(diagnosticBanner = "تعذر تحديث لقطة قدرات الحوكمة: ${failure.localizedMessage}")
                }
            }
            runCatching {
                val economics = economicGovernanceService ?: return@launch
                val status = economics.workspaceBudgetSummary(wsId)
                val recent = economics.recentLedgerForWorkspace(wsId, limit = 25)
                val tokens = economics.tokensConsumedForWorkspace(wsId)
                _state.update {
                    it.copy(
                        workspaceBudgetStatus = status,
                        costLedgerRecent = recent,
                        workspaceTokensConsumed = tokens
                    )
                }
            }.onFailure { failure ->
                // GAP-13: economic-summary degradation is surfaced.
                _state.update {
                    it.copy(diagnosticBanner = "تعذر تحديث ملخص الميزانية: ${failure.localizedMessage}")
                }
            }
            // GAP-24 (Design Closure 2026, ADR-8): honest measurement-health
            // snapshot — the observatory shows the telemetry persistence
            // layer's own failure counters, not just the data it persisted.
            runCatching {
                val health = telemetryPort?.measurementHealth()
                if (health != null) {
                    _state.update { it.copy(measurementHealth = health) }
                }
            }.onFailure { failure ->
                _state.update {
                    it.copy(diagnosticBanner = "تعذر قياس صحة طبقة القياس: ${failure.localizedMessage}")
                }
            }
        }
    }

    /**
     * GOVERNANCE PHASE — set the workspace monetary budget allocation (USD).
     * Policy: HARD_LIMIT + warn at 80% (enforced at BOTH the decide-time
     * gate in DecisionService and the pre-execution gate in
     * ExecutionService.executeLlmStep — GAP-05/ADR-5). Local tools carry no
     * cash cost and are honestly not cash-denied.
     */
    fun setWorkspaceBudgetAllocationUsd(amountUsd: Double) {
        val economics = economicGovernanceService ?: return
        val budgetUseCase = manageWorkspaceBudgetUseCase ?: return
        _state.update { it.copy(isSavingBudgetAllocation = true) }
        viewModelScope.launch {
            runCatching {
                // GAP-19 (ADR-6 step 2): the budget POLICY (HARD_LIMIT +
                // AUTO_LOCAL_FALLBACK, warn 80%, micro-USD conversion) lives
                // in ManageWorkspaceBudgetUseCase.
                budgetUseCase(
                    workspaceId = workspaceRuntimeService.requireActiveWorkspaceId(),
                    amountUsd = amountUsd
                )
                economics // (service presence guard retained above)
            }
            _state.update {
                it.copy(
                    isSavingBudgetAllocation = false,
                    budgetAllocationInputUsd = "%.2f".format(amountUsd)
                )
            }
            refreshGovernance()
        }
    }

    /** Dismisses a radar recommendation (persisted). */
    fun dismissRadarRecommendation(id: String) {
        viewModelScope.launch {
            runCatching { capabilityRadarService?.dismissRecommendation(id) }
        }
    }

    /**
     * GAP-02 (Design Closure 2026, ADR-2): REACTIVE approval surface —
     * pending requests land in [GovernanceUiState.pendingApprovals] where
     * the governance screen renders them (previously a dead end with no
     * reachable UI).
     */
    fun refreshPendingApprovals() {
        val gate = humanApprovalGate ?: return
        viewModelScope.launch {
            val pending = runCatching { gate.pendingApprovals() }.getOrDefault(emptyList())
            _state.update { it.copy(pendingApprovals = pending) }
        }
    }

    fun listPendingApprovals(
        onResult: (List<HumanApprovalRequest>) -> Unit
    ) {
        val gate = humanApprovalGate ?: return onResult(emptyList())
        viewModelScope.launch {
            onResult(runCatching { gate.pendingApprovals() }.getOrDefault(emptyList()))
        }
    }

    fun approveSensitiveAction(approvalId: String, resolvedBy: String = localPrincipalId) {
        val gate = humanApprovalGate ?: return
        viewModelScope.launch {
            runCatching { gate.approve(approvalId, resolvedBy) }
                .onSuccess { resolution ->
                    val banner = if (resolution.name == "APPROVED") "تمت الموافقة على الإجراء الحساس." else resolution.name
                    _state.update { it.copy(diagnosticBanner = banner) }
                    refreshPendingApprovals()
                }
                .onFailure { e ->
                    _state.update { it.copy(errorMessage = "تعذر تسجيل الموافقة: ${e.localizedMessage}") }
                }
        }
    }

    fun rejectSensitiveAction(approvalId: String, resolvedBy: String = localPrincipalId) {
        val gate = humanApprovalGate ?: return
        viewModelScope.launch {
            runCatching { gate.reject(approvalId, resolvedBy) }
                .onSuccess {
                    _state.update { it.copy(diagnosticBanner = "تم رفض الإجراء الحساس.") }
                    refreshPendingApprovals()
                }
                .onFailure { e ->
                    _state.update { it.copy(errorMessage = "تعذر تسجيل الرفض: ${e.localizedMessage}") }
                }
        }
    }

    /**
     * GAP-02 (ADR-2c): "السماح دائماً لهذه الأداة" — an EXECUTE
     * permission-grant for the tool (recorded consent: future admissions of
     * this tool pass without a new request), plus resolving the CURRENT
     * pending request so the in-flight execution can also proceed (its
     * one-shot token remains consumable).
     */
    fun grantAlwaysForApproval(approvalId: String) {
        val gate = humanApprovalGate ?: return
        val grants = permissionGrantService
        viewModelScope.launch {
            if (grants == null) {
                _state.update { it.copy(errorMessage = "خدمة منح الأذونات غير متاحة.") }
                return@launch
            }
            runCatching {
                val pending = gate.pendingApprovals().firstOrNull { it.approvalId == approvalId }
                if (pending != null) {
                    grants.grant(
                        principalType = com.example.domain.core.security.governance.PrincipalType.USER,
                        principalId = localPrincipalId,
                        // GLOBAL grant (workspaceId = null): standing consent for
                        // the tool across the single-user device profile.
                        resourceType = com.example.domain.core.security.governance.SecurableResourceType.TOOL,
                        resourceId = pending.toolName,
                        permission = com.example.domain.core.security.governance.Permission.EXECUTE,
                        grantedBy = localPrincipalId
                    )
                    gate.approve(approvalId, localPrincipalId)
                }
            }.onSuccess {
                _state.update { it.copy(diagnosticBanner = "تم السماح دائماً بهذه الأداة (منح EXECUTE دائم).") }
                refreshPendingApprovals()
            }.onFailure { e ->
                _state.update { it.copy(errorMessage = "تعذر تسجيل المنح الدائم: ${e.localizedMessage}") }
            }
        }
    }

    fun clearErrorMessage() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Dismisses the feature's transient diagnostic banner. */
    fun dismissDiagnosticBanner() {
        _state.update { it.copy(diagnosticBanner = null) }
    }
}
