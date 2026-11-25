package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.budget.BudgetDashboardService
import com.example.application.budget.BudgetPolicyManagementService
import com.example.application.budget.FxRateInput
import com.example.application.budget.FxRuleManagementService
import com.example.application.budget.MoneyInput
import com.example.application.budget.PricingManagementService
import com.example.application.budget.BudgetLedgerService
import com.example.application.budget.EconomicGovernanceService
import com.example.application.budget.EntitlementManagementService
import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.PriceResolution
import com.example.domain.core.budget.PricingEntry
import com.example.infrastructure.persistence.dao.PeriodBalanceRow
import com.example.infrastructure.persistence.dao.ProviderCostRow
import com.example.infrastructure.persistence.entities.BudgetHoldEntity
import com.example.infrastructure.persistence.entities.BudgetLedgerEntryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * BudgetViewModel — STAGE 7 wiring + STAGE 2 governance activation + 2B
 * ============================================================================
 *
 * STAGE 7 (unchanged): the READ-ONLY projection over
 * [BudgetDashboardService] — period balance rows (limit / posted / holds /
 * available) with the service's utilization derivation, the selected
 * period's live ledger + holds feeds, and the calculated cost by provider
 * (I-37). Every feed is the service's Room-backed flow; nothing is cached
 * in the VM, nothing is fabricated.
 *
 * STAGE 2: the Budget screen becomes the AUTHORITATIVE budget management
 * surface (authority card, budget editor, pricing management — see the
 * class doc of [BudgetPolicyManagementService] / [PricingManagementService]).
 *
 * STAGE 2B:
 *  - WORKSPACE-AWARE STATE (WS-E): when constructed with the
 *    [activeWorkspace] STREAM (the production wiring), every
 *    workspace-specific projection — the authority card AND the period
 *    balances feed — re-binds to the CURRENT workspace as it changes;
 *    in-flight reads of the previous workspace are discarded (guarded
 *    before every state write); the selected period is cleared on switch;
 *    the balances feed is SCOPE-FILTERED (this workspace's periods only —
 *    another workspace's periods are never presented as this one's) and
 *    TTL-fresh (the service re-binds with a new clock value on its
 *    refresh signal — expired reservations stop counting WITHOUT any
 *    database mutation or ViewModel recreation).
 *  - MONETARY INPUT INTEGRITY (WS-F): the editors submit raw STRINGS;
 *    [MoneyInput] converts them with exact decimal arithmetic — NaN,
 *    ±Infinity, out-of-range values and >6-decimal precision are REJECTED
 *    with a visible error (never a silent no-op, never a double-multiply
 *    truncation); the currency is an explicit field (never a silent
 *    guess); period length is validated before persistence.
 *
 * Backward compatibility: a ViewModel constructed with only the one-shot
 * [activeWorkspaceId] provider (tests, legacy wiring) keeps the Stage-7
 * behavior — the unscoped observatory feed and refresh-on-action reads.
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
    /** Legacy one-shot workspace provider (null = no workspace yet). */
    private val activeWorkspaceId: suspend () -> String? = { null },
    /** STAGE 2B (WS-E) — the active workspace as a REAL stream
     *  (production wiring: WorkspaceRuntimeService.activeWorkspace). When
     *  present, the feature observes workspace changes live. */
    private val activeWorkspace: Flow<String?>? = null,
    /** STAGE 2C — the FX-rule operational management surface (optional;
     *  null = the FX section is not wired in this build). */
    private val fxRuleManagement: FxRuleManagementService? = null,
    /**
     * FIW Stage 3 — the entitlements management surface (optional; null =
     * the الاستحقاقات section reports its honest absence instead of
     * pretending). The accruals ENGINE exists since Stage 3 of the
     * economic-governance roadmap; this is its first UI consumer.
     */
    private val entitlementManagement: EntitlementManagementService? = null
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
        val managementMessage: String? = null,
        /** STAGE 2B (WS-F) — a validation error of the editors, shown next
         *  to the offending input (never a silent no-op). */
        val inputError: String? = null,
        /** STAGE 2B (WS-E) — the workspace id the period feed is scoped to
         *  (null in legacy observatory mode = unscoped feed). */
        val periodsWorkspaceId: String? = null,
        /** STAGE 2C — every registered FX rule with its derived
         *  applicability state (live; empty when the surface is absent). */
        val fxRules: List<FxRuleManagementService.FxRuleView> = emptyList(),
        /** STAGE 2C — FX rule registration in flight. */
        val isSavingFxRule: Boolean = false,
        /** FIW Stage 3 — every recorded entitlement, derived state included. */
        val entitlements: List<EntitlementManagementService.EntitlementView> = emptyList(),
        /** FIW Stage 3 — entitlement registration in flight. */
        val isSavingEntitlement: Boolean = false
    )

    private val _state = MutableStateFlow(BudgetUiState())
    val state: StateFlow<BudgetUiState> = _state.asStateFlow()

    /** STAGE 2B (WS-E) — the live workspace context. In STREAM mode this is
     *  the collected stream; in legacy mode it is the one-shot provider's
     *  cached value (refreshed by management actions, Stage-2 behavior). */
    private val currentWorkspace = MutableStateFlow<String?>(null)

    /** True when the real workspace STREAM drives the feature. */
    private val workspaceStreamMode = activeWorkspace != null

    /** Management refresh signal — initial, after every write, and on
     *  workspace switch (STREAM mode re-reads authority per workspace). */
    private val refreshes = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 16)

    /** All period balance rows (live, Room-backed).
     *  STREAM mode: SCOPE-FILTERED to the current workspace + TTL-fresh
     *  (the service re-binds its Room query with a new clock value on the
     *  refresh signal). Legacy mode: the Stage-7 unscoped observatory. */
    val periodBalances: StateFlow<List<PeriodBalanceRow>> =
        if (workspaceStreamMode) {
            currentWorkspace.flatMapLatest { ws ->
                if (ws == null) flowOf(emptyList())
                else dashboardService.periodBalancesForScope(
                    scopeType = BudgetPolicyManagementService.WORKSPACE_SCOPE_TYPE,
                    scopePlatformObjectId = ws
                )
            }
        } else {
            dashboardService.periodBalances(nowEpochMs())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        if (workspaceStreamMode) {
            // STAGE 2B (WS-E): the active workspace as a REAL stream — every
            // switch rebinds authority + scoped projections, clears the stale
            // selected period, and requests a fresh authority read.
            viewModelScope.launch {
                activeWorkspace!!.collect { ws ->
                    val previous = currentWorkspace.value
                    currentWorkspace.value = ws
                    if (previous != ws) {
                        // A stale selected period of the PREVIOUS workspace
                        // must never carry over (WS-E requirement 8).
                        _state.update {
                            it.copy(
                                selectedPeriodId = null,
                                periodsWorkspaceId = ws,
                                authority = if (ws == null) null else it.authority,
                                authorityWorkspaceId = if (ws == null) null else it.authorityWorkspaceId
                            )
                        }
                        refreshes.tryEmit(Unit)
                    }
                }
            }
            // Authority + pricing re-projection per (workspace, refresh).
            viewModelScope.launch {
                combine(currentWorkspace, refreshes) { ws, _ -> ws }
                    .flatMapLatest { ws -> authorityAndPricingFor(ws) }
                    .collect { projection ->
                        // WS-E requirement 3: an in-flight read of a PREVIOUS
                        // workspace must never overwrite the new one's state.
                        if (currentWorkspace.value == projection.workspaceId) {
                            _state.update {
                                it.copy(
                                    authority = projection.authority,
                                    authorityWorkspaceId = projection.workspaceId,
                                    pricingEntries = projection.pricingEntries,
                                    fxRules = projection.fxRules
                                )
                            }
                        }
                    }
            }
            _state.update { it.copy(periodsWorkspaceId = currentWorkspace.value) }
        } else {
            // Legacy mode: the Stage-2 one-shot reads (refresh on entry and
            // after management actions). Observing never writes.
            refreshManagementState()
        }
        refreshes.tryEmit(Unit)
    }

    /** Selects the period whose ledger + holds feeds the screen renders. */
    fun selectPeriod(periodId: String) {
        _state.update { it.copy(selectedPeriodId = periodId) }
    }

    /** The service's own utilization derivation for a balance row. */
    fun utilization(row: PeriodBalanceRow): BudgetDashboardService.Utilization =
        dashboardService.utilization(row)

    /** STAGE 2B (WS-B) — the derived lifecycle of a displayed period. */
    fun lifecycleOf(row: PeriodBalanceRow): BudgetDashboardService.PeriodLifecycle =
        dashboardService.lifecycleOf(row, nowEpochMs())

    /**
     * Stage 2 — reload the authority projection and the pricing list.
     * READ-ONLY: repeated calls never create or mutate anything. In STREAM
     * mode the flows already rebind; this forces an immediate re-read.
     */
    fun refreshManagementState() {
        if (workspaceStreamMode) {
            refreshes.tryEmit(Unit)
            return
        }
        viewModelScope.launch {
            val wsId = runCatching { activeWorkspaceId() }.getOrNull()
            currentWorkspace.value = wsId
            loadAuthorityAndPricing(wsId)?.let { projection ->
                if (currentWorkspace.value == projection.workspaceId) {
                    _state.update {
                        it.copy(
                            authority = projection.authority,
                            authorityWorkspaceId = projection.workspaceId,
                            pricingEntries = projection.pricingEntries,
                            fxRules = projection.fxRules
                        )
                    }
                }
            }
        }
    }

    private data class ManagementProjection(
        val workspaceId: String?,
        val authority: BudgetPolicyManagementService.WorkspaceBudgetAuthority?,
        val pricingEntries: List<PricingEntry>,
        val fxRules: List<FxRuleManagementService.FxRuleView> = emptyList()
    )

    private fun authorityAndPricingFor(wsId: String?): Flow<ManagementProjection> = flow {
        emit(loadAuthorityAndPricing(wsId) ?: ManagementProjection(wsId, null, emptyList()))
    }

    private suspend fun loadAuthorityAndPricing(wsId: String?): ManagementProjection? {
        var authority: BudgetPolicyManagementService.WorkspaceBudgetAuthority? = null
        val management = policyManagement
        if (wsId != null && management != null) {
            runCatching { management.workspaceAuthority(wsId) }
                .onSuccess { authority = it }
                .onFailure { failure ->
                    // A cancelled load (workspace switched mid-read — the
                    // flatMapLatest cancellation) is NOT an error: the next
                    // projection for the NEW workspace is already loading.
                    if (failure !is kotlinx.coroutines.CancellationException) {
                        _state.update {
                            it.copy(errorMessage = "تعذر تحميل سلطة الميزانية: ${failure.localizedMessage}")
                        }
                    }
                }
        }
        var pricing: List<PricingEntry> = emptyList()
        val pricingService = pricingManagement
        if (pricingService != null) {
            runCatching { pricingService.listPricing() }
                .onSuccess { pricing = it }
                .onFailure { failure ->
                    if (failure !is kotlinx.coroutines.CancellationException) {
                        _state.update {
                            it.copy(errorMessage = "تعذر تحميل قائمة التسعير: ${failure.localizedMessage}")
                        }
                    }
                }
        }
        // STAGE 2C — the registered FX rules (pure read; the applicability
        // state is derived against the injectable clock).
        var fxRules: List<FxRuleManagementService.FxRuleView> = emptyList()
        val fxManagement = fxRuleManagement
        if (fxManagement != null) {
            runCatching { fxManagement.listRules(nowEpochMs()) }
                .onSuccess { fxRules = it }
                .onFailure { failure ->
                    if (failure !is kotlinx.coroutines.CancellationException) {
                        _state.update {
                            it.copy(errorMessage = "تعذر تحميل قواعد تحويل العملات: ${failure.localizedMessage}")
                        }
                    }
                }
        }
        // FIW Stage 3 — the recorded entitlements (workspace-INDEPENDENT
        // records; the derived state loads with the same projection).
        val entitlementManagementService = entitlementManagement
        if (entitlementManagementService != null) {
            runCatching { entitlementManagementService.listEntitlements() }
                .onSuccess { views ->
                    _state.update { it.copy(entitlements = views) }
                }
                .onFailure { failure ->
                    if (failure !is kotlinx.coroutines.CancellationException) {
                        _state.update {
                            it.copy(errorMessage = "تعذر تحميل الاستحقاقات: ${failure.localizedMessage}")
                        }
                    }
                }
        }
        return ManagementProjection(wsId, authority, pricing, fxRules)
    }

    /**
     * STAGE 2B (WS-F) — the production authoring action with EXACT decimal
     * input integrity: [amountText] and [currencyText] are raw editor
     * strings parsed by [MoneyInput.parseMicroUnits] / [parseCurrency]
     * (NaN, ±Infinity, negatives, >6-decimal precision and out-of-range
     * values are rejected with a VISIBLE error; the currency is explicit);
     * [periodDaysText] is validated in 1..3650 (never silently coerced).
     */
    fun configureWorkspaceBudget(
        amountText: String,
        currencyText: String,
        overagePolicy: String = BudgetLedgerService.OVERAGE_HARD_LIMIT,
        periodDaysText: String = "30"
    ) {
        val management = policyManagement
        if (management == null) {
            _state.update { it.copy(errorMessage = "إدارة السياسات غير متاحة في هذا البناء.") }
            return
        }
        // ---- WS-F: validate BEFORE any write; display errors, never
        //      silently ignore an invalid submission.
        val amount = when (val parsed = MoneyInput.parseMicroUnits(amountText)) {
            is MoneyInput.Parsed.Micro -> parsed.microUnits
            is MoneyInput.Parsed.Invalid -> {
                _state.update { it.copy(inputError = parsed.reason) }
                return
            }
        }
        val currency = MoneyInput.parseCurrency(currencyText)
        if (currency == null) {
            _state.update { it.copy(inputError = "رمز عملة غير صالح — ثلاثة أحرف لاتينية (مثال: USD)") }
            return
        }
        val days = periodDaysText.trim().toIntOrNull()
        if (days == null || days < 1 || days > 3650) {
            _state.update { it.copy(inputError = "طول الفترة يجب أن يكون يوماً صحيحاً بين 1 و3650") }
            return
        }
        _state.update { it.copy(isSavingBudget = true, managementMessage = null, inputError = null) }
        viewModelScope.launch {
            val wsId = currentWorkspace.value
                ?: runCatching { activeWorkspaceId() }.getOrNull()
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
                        limitMicroUnits = amount,
                        currency = currency,
                        overagePolicy = overagePolicy,
                        periodStartEpochMs = nowEpochMs(),
                        periodEndEpochMs = nowEpochMs() + days.toLong() * 86_400_000L
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
     * STAGE 2B (WS-F) — save verified pricing evidence through the
     * management path. Prices are per MILLION tokens entered as STRINGS
     * and parsed with exact decimal arithmetic; the currency is explicit
     * (WS-F: a missing currency is never silently guessed — the caller
     * supplies its explicit default and the parser validates it);
     * [evidenceReference] (WS-D) records what the price was attested
     * against (user-attested accuracy, never independent verification).
     */
    fun savePricing(
        providerId: String,
        serviceId: String?,
        modelId: String?,
        inputPricePerMillionText: String?,
        outputPricePerMillionText: String?,
        cachedInputPricePerMillionText: String?,
        currencyText: String,
        billingClass: BillingClass = BillingClass.PAID,
        effectiveUntilEpochMs: Long? = null,
        sourceNote: String? = null,
        evidenceReference: String? = null
    ) {
        val management = pricingManagement
        if (management == null) {
            _state.update { it.copy(errorMessage = "إدارة التسعير غير متاحة في هذا البناء.") }
            return
        }
        // ---- WS-F: exact-decimal parsing of the optional price fields.
        //      Empty text = price not published (null); invalid text =
        //      VISIBLE error, never a silent zero.
        fun parseOptional(text: String?, label: String): MoneyInput.Parsed? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return MoneyInput.parseMicroUnits(trimmed).also { parsed ->
                if (parsed is MoneyInput.Parsed.Invalid) {
                    _state.update { it.copy(inputError = "$label: ${parsed.reason}") }
                }
            }
        }

        val inputParsed = parseOptional(inputPricePerMillionText, "سعر الدخل")
        if (inputParsed is MoneyInput.Parsed.Invalid) return
        val outputParsed = parseOptional(outputPricePerMillionText, "سعر الخرج")
        if (outputParsed is MoneyInput.Parsed.Invalid) return
        val cachedParsed = parseOptional(cachedInputPricePerMillionText, "سعر الدخل المخزّن")
        if (cachedParsed is MoneyInput.Parsed.Invalid) return

        val currency = MoneyInput.parseCurrency(currencyText)
        if (currency == null) {
            _state.update { it.copy(inputError = "رمز عملة غير صالح — ثلاثة أحرف لاتينية (مثال: USD)") }
            return
        }
        val inputMicro = (inputParsed as? MoneyInput.Parsed.Micro)?.microUnits
        val outputMicro = (outputParsed as? MoneyInput.Parsed.Micro)?.microUnits
        val cachedMicro = (cachedParsed as? MoneyInput.Parsed.Micro)?.microUnits
        if (inputMicro == null && outputMicro == null && cachedMicro == null) {
            _state.update { it.copy(inputError = "أدخل سعراً واحداً على الأقل — التسعير المجهول لا يُسجَّل أبداً") }
            return
        }

        _state.update { it.copy(isSavingPricing = true, managementMessage = null, inputError = null) }
        viewModelScope.launch {
            runCatching {
                management.registerUserConfiguredPricing(
                    PricingManagementService.UserPricingRequest(
                        providerId = providerId,
                        serviceId = serviceId?.takeIf { it.isNotBlank() },
                        modelId = modelId?.takeIf { it.isNotBlank() },
                        inputPriceMicroPerMillion = inputMicro,
                        outputPriceMicroPerMillion = outputMicro,
                        cachedInputPriceMicroPerMillion = cachedMicro,
                        currency = currency,
                        billingClass = billingClass,
                        effectiveFromEpochMs = nowEpochMs(),
                        effectiveUntilEpochMs = effectiveUntilEpochMs,
                        sourceNote = sourceNote,
                        evidenceReference = evidenceReference?.takeIf { it.isNotBlank() }
                    )
                )
            }.onSuccess { outcome ->
                _state.update {
                    it.copy(
                        isSavingPricing = false,
                        managementMessage = when (outcome) {
                            is PricingManagementService.RegisterOutcome.Registered -> buildString {
                                append("سُجّل تسعير مُدخل يدوياً (USER_CONFIG")
                                if (outcome.catalogSyncFailure != null) {
                                    append(") — لكن تعذّرت مزامنة فهرس الإصدارات؛ استخدم «إعادة مزامنة الفهرس» للتعافي")
                                } else {
                                    append(") واعتُمد في فهرس الإصدارات")
                                }
                                when (outcome.identityStatus) {
                                    PricingManagementService.PricingIdentityStatus.PROVIDER_UNKNOWN ->
                                        append("؛ تحذير: المزوّد غير معروف في السجل")
                                    PricingManagementService.PricingIdentityStatus.PROVIDER_FOUND_SERVICE_UNKNOWN ->
                                        append("؛ تحذير: الخدمة غير معروفة تحت هذا المزوّد")
                                    else -> Unit
                                }
                            }
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

    /** STAGE 2B (WS-C) — the catalog-mirror recovery action. */
    fun resyncPricingCatalog() {
        val management = pricingManagement ?: return
        viewModelScope.launch {
            runCatching { management.resyncPricingCatalog() }
                .onSuccess { sync ->
                    _state.update {
                        it.copy(
                            managementMessage = "أُعيدت مزامنة الفهرس: +${sync.versionsCreated} نسخة تسعير."
                        )
                    }
                    refreshManagementState()
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(errorMessage = "تعذّرت إعادة المزامنة: ${failure.localizedMessage}")
                    }
                }
        }
    }

    /**
     * STAGE 2C — register an immutable FX rule through the management
     * surface. Raw editor STRINGS validated BEFORE any write (WS-F
     * discipline): currencies via [MoneyInput.parseCurrency], the rate via
     * [FxRateInput.parse] (exact decimal, strictly positive), the effective
     * window as epoch-ms TEXT (blank "from" = now; blank "until" =
     * open-ended; from must precede until). Invalid input is a VISIBLE
     * error; a conflicting duplicate / rejected registration is surfaced
     * honestly — never a silent overwrite of a used rule.
     */
    fun registerFxRule(
        fromCurrencyText: String,
        toCurrencyText: String,
        rateText: String,
        effectiveFromText: String,
        effectiveUntilText: String,
        sourceAuthorityText: String
    ) {
        val management = fxRuleManagement
        if (management == null) {
            _state.update { it.copy(errorMessage = "إدارة قواعد التحويل غير متاحة في هذا البناء.") }
            return
        }
        val from = MoneyInput.parseCurrency(fromCurrencyText)
        if (from == null) {
            _state.update { it.copy(inputError = "عملة المصدر غير صالحة — ثلاثة أحرف لاتينية (مثال: USD)") }
            return
        }
        val to = MoneyInput.parseCurrency(toCurrencyText)
        if (to == null) {
            _state.update { it.copy(inputError = "عملة الهدف غير صالحة — ثلاثة أحرف لاتينية (مثال: EUR)") }
            return
        }
        if (from == to) {
            _state.update { it.copy(inputError = "قاعدة التحويل يجب أن تغيّر العملة (المصدر ≠ الهدف)") }
            return
        }
        val rate = when (val parsed = FxRateInput.parse(rateText)) {
            is FxRateInput.Parsed.Rate -> parsed.ratePrecise
            is FxRateInput.Parsed.Invalid -> {
                _state.update { it.copy(inputError = parsed.reason) }
                return
            }
        }
        val now = nowEpochMs()
        // Blank "from" = now; blank "until" = open-ended. Non-blank text must
        // be a valid epoch-ms value (never silently coerced).
        val effectiveFrom: Long
        if (effectiveFromText.isBlank()) {
            effectiveFrom = now
        } else {
            val parsed = effectiveFromText.trim().toLongOrNull()
            if (parsed == null) {
                _state.update { it.copy(inputError = "بداية السريان غير صالحة — أدخل زمن Unix بالميلي ثانية أو اتركه فارغاً للبدء الآن") }
                return
            }
            effectiveFrom = parsed
        }
        var effectiveUntil: Long? = null
        if (effectiveUntilText.isNotBlank()) {
            val parsed = effectiveUntilText.trim().toLongOrNull()
            if (parsed == null) {
                _state.update { it.copy(inputError = "نهاية السريان غير صالحة — أدخل زمن Unix بالميلي ثانية أو اتركه فارغاً (مفتوح)") }
                return
            }
            effectiveUntil = parsed
        }
        if (effectiveUntil != null && effectiveFrom >= effectiveUntil) {
            _state.update { it.copy(inputError = "نافذة السريان غير صالحة — البداية يجب أن تسبق النهاية") }
            return
        }
        val authority = sourceAuthorityText.trim().ifBlank { "operator" }
        _state.update { it.copy(isSavingFxRule = true, managementMessage = null, inputError = null) }
        viewModelScope.launch {
            runCatching {
                management.registerRule(
                    fromCurrency = from,
                    toCurrency = to,
                    ratePrecise = rate,
                    effectiveFromEpochMs = effectiveFrom,
                    effectiveUntilEpochMs = effectiveUntil,
                    sourceAuthority = authority
                )
            }.onSuccess { outcome ->
                _state.update {
                    it.copy(
                        isSavingFxRule = false,
                        managementMessage = when (outcome) {
                            is FxRuleManagementService.RegisterOutcome.Registered ->
                                "سُجّلت قاعدة تحويل جديدة (${outcome.ruleId}) — دليل مُسجّل تشغيلياً، غير مُتحقق منه بشكل مستقل."
                            is FxRuleManagementService.RegisterOutcome.Replayed ->
                                "لا تغيير — قاعدة مطابقة مسجّلة فعلاً (${outcome.ruleId})."
                            is FxRuleManagementService.RegisterOutcome.Rejected ->
                                "رُفض التسجيل: ${outcome.reason}"
                        }
                    )
                }
                refreshManagementState()
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        isSavingFxRule = false,
                        errorMessage = "تعذر تسجيل قاعدة التحويل: ${failure.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * FIW Stage 3 — the entitlement REGISTRATION action (the engine's own
     * shape validation; honest field-level errors; verification stays
     * UNKNOWN — a user-registered record is not provider evidence).
     */
    fun registerEntitlement(
        providerIdText: String,
        scopeIdText: String,
        entitlementType: String,
        valueKind: String,
        quantityLimitText: String,
        unitText: String,
        monetaryLimitText: String,
        currencyText: String,
        creditLimitText: String,
        creditUnitText: String,
        startsAtText: String,
        expiresAtText: String,
        resetPolicyText: String
    ) {
        val management = entitlementManagement
        if (management == null) {
            _state.update { it.copy(errorMessage = "إدارة الاستحقاقات غير متاحة في هذا البناء.") }
            return
        }
        if (providerIdText.isBlank() || scopeIdText.isBlank()) {
            _state.update { it.copy(inputError = "معرّف المزود ونطاق التطبيق (معرّف النموذج/العرض) حقلان إلزاميان.") }
            return
        }
        // Value-shape fields are parsed to their typed forms EXACTLY (no
        // silent coercion): quantity/credit = decimal strings, monetary =
        // micro-units via the SAME MoneyInput parser the budget editor uses.
        val quantity = quantityLimitText.trim().takeIf { it.isNotEmpty() }
        if (quantity != null && quantity.toBigDecimalOrNull() == null) {
            _state.update { it.copy(inputError = "حد الكمية غير صالح — أدخل رقماً عشرياً دقيقاً (مثال: 1000000).") }
            return
        }
        val credit = creditLimitText.trim().takeIf { it.isNotEmpty() }
        if (credit != null && credit.toBigDecimalOrNull() == null) {
            _state.update { it.copy(inputError = "حد الرصيد غير صالح — أدخل رقماً عشرياً دقيقاً.") }
            return
        }
        var monetaryMicro: Long? = null
        if (monetaryLimitText.isNotBlank()) {
            when (val parsed = MoneyInput.parseMicroUnits(monetaryLimitText)) {
                is MoneyInput.Parsed.Micro -> monetaryMicro = parsed.microUnits
                is MoneyInput.Parsed.Invalid -> {
                    _state.update { it.copy(inputError = "الحد المالي غير صالح: ${parsed.reason}") }
                    return
                }
            }
        }
        val currency = currencyText.trim().takeIf { it.isNotEmpty() }
        if (currency != null && MoneyInput.parseCurrency(currency) == null) {
            _state.update { it.copy(inputError = "العملة غير صالحة — ثلاثة أحرف لاتينية (مثال: USD).") }
            return
        }
        val startsAt = startsAtText.trim().takeIf { it.isNotEmpty() }?.let {
            it.toLongOrNull() ?: run {
                _state.update { it.copy(inputError = "بداية السريان غير صالحة — زمن Unix بالميلي ثانية أو فارغ.") }
                return
            }
        }
        val expiresAt = expiresAtText.trim().takeIf { it.isNotEmpty() }?.let {
            it.toLongOrNull() ?: run {
                _state.update { it.copy(inputError = "نهاية السريان غير صالحة — زمن Unix بالميلي ثانية أو فارغ.") }
                return
            }
        }
        if (startsAt != null && expiresAt != null && startsAt >= expiresAt) {
            _state.update { it.copy(inputError = "نافذة السريان غير صالحة — البداية يجب أن تسبق النهاية.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSavingEntitlement = true, managementMessage = null, inputError = null) }
            runCatching {
                management.registerEntitlement(
                    com.example.application.budget.EntitlementManagementService.RegistrationSpec(
                        providerId = providerIdText.trim(),
                        applicabilityScopeId = scopeIdText.trim(),
                        entitlementType = entitlementType,
                        valueKind = valueKind,
                        quantityLimitPrecise = quantity,
                        unitId = unitText.trim().takeIf { it.isNotEmpty() },
                        monetaryLimitMicroUnits = monetaryMicro,
                        currency = currency,
                        creditLimitPrecise = credit,
                        creditUnitId = creditUnitText.trim().takeIf { it.isNotEmpty() },
                        startsAtEpochMs = startsAt,
                        expiresAtEpochMs = expiresAt,
                        resetPolicy = resetPolicyText.trim().takeIf { it.isNotEmpty() }
                    )
                )
            }.onSuccess { outcome ->
                _state.update {
                    it.copy(
                        isSavingEntitlement = false,
                        managementMessage = when (outcome) {
                            is com.example.application.budget.EntitlementManagementService.RegistrationOutcome.Registered ->
                                "سُجّل الاستحقاق (${outcome.entitlementId.take(18)}…) — حالة التحقق: غير معروف (إدخال تشغيلي، ليس دليلاً من المزود)."
                            is com.example.application.budget.EntitlementManagementService.RegistrationOutcome.Replayed ->
                                "لا تغيير — استحقاق مطابق مسجّل فعلاً."
                            is com.example.application.budget.EntitlementManagementService.RegistrationOutcome.Rejected ->
                                "رُفض التسجيل: ${outcome.reason}"
                        }
                    )
                }
                refreshManagementState()
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        isSavingEntitlement = false,
                        errorMessage = "تعذر تسجيل الاستحقاق: ${failure.localizedMessage}"
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

    /** Dismisses the editor validation error (WS-F). */
    fun dismissInputError() {
        _state.update { it.copy(inputError = null) }
    }
}
