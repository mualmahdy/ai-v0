package com.example.presentation.ui.screens.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.budget.PricingEntry
import com.example.infrastructure.persistence.dao.PeriodBalanceRow
import com.example.presentation.viewmodel.BudgetViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * BudgetScreen — STAGE 7 observatory + STAGE 2 governance activation
 * ============================================================================
 *
 * STAGE 7 (unchanged): the READ-ONLY projection of the accounting core —
 * period balances with the live equation (limit / posted / holds /
 * available), the selected period's append-only ledger feed, its holds,
 * and the calculated cost by provider (I-37).
 *
 * STAGE 2 (this stage): the screen becomes the AUTHORITATIVE budget
 * management surface:
 *
 *  - THE AUTHORITY CARD: which budget governs this workspace RIGHT NOW — a
 *    modern accounting policy (name, version, window, limit, currency,
 *    overage behavior, live equation), a modern policy whose period has
 *    ENDED (honest warning: spending denied until a new period opens), the
 *    legacy allocation (transition not started), or NO authority (costs
 *    tracked, never denied — honest absence, never "unlimited").
 *  - THE BUDGET EDITOR: configure the modern policy through the production
 *    path (BudgetPolicyManagementService) — first policy, immutable new
 *    version on edit, new period on rollover; repeated identical saves are
 *    verified no-ops.
 *  - PRICING MANAGEMENT: the verified pricing path — list the evidence
 *    (prices, currency, provenance, effective windows) and register
 *    USER_CONFIG pricing; a live probe answers honestly whether a
 *    provider/model's price is KNOWN or UNKNOWN.
 *
 * Honesty contract (I-3): a monetary hard limit is only enforceable when
 * pricing is KNOWN; unknown-priced interactions proceed without a hold and
 * their actual cost lands at settlement — the UI says so explicitly.
 */
@Composable
fun BudgetScreen(
    viewModel: BudgetViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val periodBalances by viewModel.periodBalances.collectAsState()
    val costByProvider by viewModel.costByProvider.collectAsState()
    val ledgerEntries by viewModel.ledgerEntries.collectAsState()
    val holds by viewModel.holds.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("budget_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- Header ----------
        item {
            Column(modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)) {
                Text(
                    text = "الميزانية المحاسبية",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "سلطة الإنفاق الفعلية، فترات التخصيص، دفتر القيود، الحجوزات وتكلفة المزوّدين — من النظام المحاسبي مباشرة",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------- STAGE 2: the authority card ----------
        item {
            AuthorityCard(
                authority = state.authority,
                workspaceId = state.authorityWorkspaceId,
                onRefresh = { viewModel.refreshManagementState() }
            )
        }

        // ---------- STAGE 2: the budget editor (modern policy path) ----------
        item {
            BudgetEditorCard(
                state = state,
                onConfigure = { amount, currency, overage, days ->
                    viewModel.configureWorkspaceBudget(amount, currency, overage, days)
                }
            )
        }

        // ---------- Period balances ----------
        item {
            SectionTitle(text = "أرصدة فترات التخصيص (${periodBalances.size})")
        }
        if (periodBalances.isEmpty()) {
            item {
                HonestEmptyCard(
                    text = "لا توجد فترات ميزانية بعد — تُنشأ الفترة المحاسبية عند تكوين سياسة ميزانية " +
                        "حديثة من محرّر «تكوين الميزانية» أعلاه. محرر شاشة الحوكمة يعدّل التخصيص " +
                        "القديم (legacy) فقط ولا ينشئ فترة محاسبية."
                )
            }
        }
        items(periodBalances, key = { it.periodId }) { row ->
            PeriodBalanceCard(
                row = row,
                selected = row.periodId == state.selectedPeriodId,
                utilization = viewModel.utilization(row),
                lifecycle = viewModel.lifecycleOf(row),
                onClick = { viewModel.selectPeriod(row.periodId) }
            )
        }

        // ---------- Ledger (selected period) ----------
        if (state.selectedPeriodId != null) {
            item {
                SectionTitle(text = "دفتر القيود — الفترة ${state.selectedPeriodId}")
            }
            if (ledgerEntries.isEmpty()) {
                item { HonestEmptyCard(text = "لا قيود في هذه الفترة بعد — يُقيَّد الاستهلاك فعلياً عند تنفيذ الطلبات عبر بوابات الميزانية.") }
            } else {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("budget_ledger_card"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            ledgerEntries.take(50).forEachIndexed { index, entry ->
                                LedgerEntryRow(entry = entry)
                                if (index < minOf(ledgerEntries.size, 50) - 1) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                            }
                            if (ledgerEntries.size > 50) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "…و${ledgerEntries.size - 50} قيداً أقدم (سجل ملاحقة فقط)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ---------- Holds (selected period) ----------
            item {
                SectionTitle(text = "الحجوزات (${holds.size})")
            }
            if (holds.isEmpty()) {
                item { HonestEmptyCard(text = "لا حجوزات نشطة في هذه الفترة — تُنشأ الحجوزات عند بدء تنفيذ يستهلك من الميزانية.") }
            } else {
                items(holds, key = { it.holdId }) { hold ->
                    HoldRow(hold = hold)
                }
            }
        }

        // ---------- STAGE 2: pricing management ----------
        item {
            SectionTitle(text = "إدارة التسعير — أدلة مُدخلة يدوياً (${state.pricingEntries.size})")
        }
        item {
            PricingManagementCard(
                state = state,
                onCheck = { provider, service, model ->
                    viewModel.checkPricing(provider, service, model)
                },
                onSave = { provider, service, model, inPrice, outPrice, currency, evidence ->
                    viewModel.savePricing(
                        providerId = provider,
                        serviceId = service,
                        modelId = model,
                        inputPricePerMillionText = inPrice,
                        outputPricePerMillionText = outPrice,
                        cachedInputPricePerMillionText = null,
                        currencyText = currency,
                        evidenceReference = evidence
                    )
                },
                onResync = { viewModel.resyncPricingCatalog() }
            )
        }
        if (state.pricingEntries.isEmpty()) {
            item {
                HonestEmptyCard(
                    text = "لا أدلة تسعير بعد — التسعير مجهول (UNKNOWN) لكل المزوّدين: لا يُخترع سعر ولا يُفترض " +
                        "مجاني، ولا يمكن إعمال سقف نقدي صارم قبل تسجيل تسعير موثّق."
                )
            }
        } else {
            items(state.pricingEntries, key = { it.id }) { entry ->
                PricingEntryCard(entry = entry)
            }
        }

        // ---------- Cost by provider ----------
        item {
            SectionTitle(text = "التكلفة المحسوبة لكل مزوّد (${costByProvider.size})")
        }
        if (costByProvider.isEmpty()) {
            item { HonestEmptyCard(text = "لا حسابات تكلفة بعد — تُحتسب التكلفة عبر سلسلة التسعير (المرحلة الثانية) عند تنفيذ طلبات فعلية.") }
        } else {
            items(costByProvider, key = { it.providerId }) { row ->
                ProviderCostCard(row = row)
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------------------
// STAGE 2 — authority card, budget editor, pricing management
// ---------------------------------------------------------------------------

@Composable
private fun AuthorityCard(
    authority: com.example.application.budget.BudgetPolicyManagementService.WorkspaceBudgetAuthority?,
    workspaceId: String?,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("budget_authority_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (authority?.kind) {
                com.example.application.budget.BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.MODERN_ACCOUNTING ->
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                com.example.application.budget.BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.MODERN_PERIOD_ENDED ->
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (authority?.kind) {
                        com.example.application.budget.BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.MODERN_ACCOUNTING -> Icons.Default.Savings
                        com.example.application.budget.BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.MODERN_PERIOD_ENDED -> Icons.Default.LockClock
                        else -> Icons.Default.AttachMoney
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "سلطة الإنفاق الحالية" + (workspaceId?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            when (authority?.kind) {
                com.example.application.budget.BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.MODERN_ACCOUNTING -> {
                    val policy = authority.modernPolicy
                    val balance = authority.modernBalance
                    Text(
                        text = "سياسة محاسبية حديثة تحكم التنفيذ: «${policy?.budgetName}» " +
                            "(إصدار ${policy?.policyVersion}، عائلة ${policy?.policyFamilyId?.takeLast(8)})",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "الفترة: ${authority.modernPeriod?.let { formatDate(it.periodStartEpochMs) } ?: "—"} ← " +
                            "${authority.modernPeriod?.let { formatDate(it.periodEndEpochMs) } ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BalanceStat(
                            icon = Icons.Default.Savings,
                            label = "السقف",
                            value = microToText(balance?.limitMicroUnits ?: 0L, policy?.currency ?: "USD"),
                            modifier = Modifier.weight(1f)
                        )
                        BalanceStat(
                            icon = Icons.Default.Payments,
                            label = "المرحّل",
                            value = microToText(balance?.postedEffectsMicroUnits ?: 0L, policy?.currency ?: "USD"),
                            modifier = Modifier.weight(1f)
                        )
                        BalanceStat(
                            icon = Icons.Default.LockClock,
                            label = "المحجوز",
                            value = microToText(balance?.activeHoldRemainingMicroUnits ?: 0L, policy?.currency ?: "USD"),
                            modifier = Modifier.weight(1f)
                        )
                        BalanceStat(
                            icon = Icons.Default.CheckCircle,
                            label = "المتاح",
                            value = microToText(balance?.availableMicroUnits ?: 0L, policy?.currency ?: "USD"),
                            modifier = Modifier.weight(1f),
                            highlight = true
                        )
                    }
                    Text(
                        text = "سلوك بلوغ السقف: ${policy?.overagePolicy ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                com.example.application.budget.BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.MODERN_PERIOD_ENDED -> {
                    Text(
                        text = "انتهت الفترة المحاسبية للسياسة «${authority.modernPolicy?.budgetName}» — " +
                            "الإنفاق مرفوض بأمان حتى فتح فترة جديدة من محرّر «تكوين الميزانية». " +
                            "لا ارتداد صامت إلى التخصيص القديم (legacy).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                com.example.application.budget.BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.LEGACY_ALLOCATION -> {
                    val alloc = authority.legacyAllocation
                    Text(
                        text = "تخصيص قديم (legacy) يحكم التنفيذ — لم تُفعّل سياسة محاسبية حديثة بعد. " +
                            "المخصص: ${alloc?.allocated?.amountMicro ?: "غير معروف"} micro. " +
                            "سياسة الإنفاق: ${alloc?.policy?.primaryAction()?.name ?: "—"}.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "لتكوين سياسة محاسبية حديثة (فترات + حجوزات + دفتر قيود) استخدم المحرّر أدناه.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                com.example.application.budget.BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.NONE -> {
                    Text(
                        text = "لا سلطة إنفاق مهيأة — التكاليف تُرصد فقط ولا يُرفض شيء. " +
                            "هذا غياب صادق، وليس ميزانية غير محدودة ولا صفرية.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                null -> {
                    Text(
                        text = "إدارة السياسات غير متاحة في هذا البناء — العرض للقراءة فقط.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "ملاحظة أمانة (I-3): السقف النقدي يُفرض فقط عند معرفة التسعير؛ " +
                    "التكاليف مجهولة التسعير تمضي بلا حجز وتُقيَّد فعلية عند التسوية. " +
                    "(WS-A) وإذا كان التسعير بعملة غير عملة السياسة وجب وجود قاعدة تحويل مسجّلة — " +
                    "بدونها لا يُنشأ حجز ولا يُفترض سعر 1:1، وتُسجّل التكلفة الفعلية كدليل غير محوّل.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BudgetEditorCard(
    state: com.example.presentation.viewmodel.BudgetViewModel.BudgetUiState,
    onConfigure: (String, String, String, String) -> Unit
) {
    var amountInput by remember { mutableStateOf("") }
    var currencyInput by remember { mutableStateOf("USD") }
    var overagePolicy by remember {
        mutableStateOf(com.example.application.budget.BudgetLedgerService.OVERAGE_HARD_LIMIT)
    }
    var periodDaysInput by remember { mutableStateOf("30") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("budget_editor_card"),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "تكوين الميزانية (السياسة المحاسبية)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "إنشاء أول سياسة، أو إصدار نسخة جديدة عند التعديل، أو فتح فترة جديدة بعد انتهاء الحالية. " +
                    "المبلغ يُقرأ بكسور عشرية بدقة مطلقة (حتى 6 خانات) ويُحوّل إلى micro — لا قيم سالبة، ولا NaN/Infinity، ولا اقتطاع ثنائي.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it },
                    label = { Text("سقف الفترة") },
                    placeholder = { Text("مثال: 5.00") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("budget_editor_amount")
                )
                OutlinedTextField(
                    value = currencyInput,
                    onValueChange = { currencyInput = it },
                    label = { Text("العملة") },
                    placeholder = { Text("USD") },
                    singleLine = true,
                    modifier = Modifier
                        .width(96.dp)
                        .testTag("budget_editor_currency")
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "سلوك بلوغ السقف",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    com.example.application.budget.BudgetLedgerService.OVERAGE_HARD_LIMIT to "حد صارم",
                    com.example.application.budget.BudgetLedgerService.OVERAGE_SOFT_LIMIT to "حد مرن",
                    com.example.application.budget.BudgetLedgerService.OVERAGE_REQUIRE_APPROVAL to "موافقة"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = overagePolicy == value,
                        onClick = { overagePolicy = value },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("budget_editor_overage_$value")
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = periodDaysInput,
                onValueChange = { periodDaysInput = it },
                label = { Text("طول الفترة (أيام)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("budget_editor_period_days")
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    // STAGE 2B (WS-F): raw strings — the ViewModel validates
                    // with exact decimal arithmetic and surfaces a VISIBLE
                    // error; nothing invalid is silently ignored or coerced.
                    onConfigure(amountInput, currencyInput, overagePolicy, periodDaysInput)
                },
                enabled = amountInput.isNotBlank() && !state.isSavingBudget,
                modifier = Modifier.testTag("budget_editor_save")
            ) {
                if (state.isSavingBudget) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تكوين / تحديث الميزانية")
                }
            }
            // STAGE 2B (WS-F): the editor's validation error channel —
            // invalid input is DISPLAYED, never silently dropped.
            state.inputError?.let { error ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("budget_editor_validation")
                )
            }
            state.managementMessage?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun PricingManagementCard(
    state: com.example.presentation.viewmodel.BudgetViewModel.BudgetUiState,
    onCheck: (String, String?, String?) -> Unit,
    onSave: (String, String?, String?, String?, String?, String, String?) -> Unit,
    onResync: () -> Unit
) {
    var providerInput by remember { mutableStateOf("") }
    var serviceInput by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf("") }
    var inputPriceInput by remember { mutableStateOf("") }
    var outputPriceInput by remember { mutableStateOf("") }
    var currencyInput by remember { mutableStateOf("USD") }
    var evidenceInput by remember { mutableStateOf("") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("pricing_management_card"),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "تسجيل تسعير مُدخل يدوياً (USER_CONFIG)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "أدخل الأسعار التي تحققت منها من نشرة أسعار مزوّدك — لكل مليون رمز. " +
                    "الوسم USER_CONFIG يعني: مصدر بشري مُدخل يدوياً (وليس اكتشافاً من المزوّد، وليس تحققاً مستقلاً) — " +
                    "كل تسعير يُسجَّل غير مُتحقق منه بشكل مستقل، ويُنسخ إلى فهرس الإصدارات المؤرّخ. " +
                    "استخدم حقل «مرجع الدليل» لتوثيق ما تحققت منه (رابط/مستند).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = providerInput,
                onValueChange = {
                    providerInput = it
                    onCheck(providerInput, serviceInput, modelInput)
                },
                label = { Text("معرّف المزوّد") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pricing_form_provider")
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = serviceInput,
                onValueChange = {
                    serviceInput = it
                    onCheck(providerInput, serviceInput, modelInput)
                },
                label = { Text("معرّف الخدمة (مطلوب لتسعير نموذج)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = modelInput,
                onValueChange = {
                    modelInput = it
                    onCheck(providerInput, serviceInput, modelInput)
                },
                label = { Text("معرّف النموذج (اختياري — يوسّع نطاق التسعير)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            val resolution = state.formPricingResolution
            if (providerInput.isNotBlank()) {
                Text(
                    text = when {
                        resolution == null -> "التسعير الحالي: مجهول (UNKNOWN) — لا سعر معروف لهذه الهوية."
                        resolution.isUnknown -> "التسعير الحالي: مجهول (UNKNOWN) — لا سعر معروف لهذه الهوية."
                        else -> "التسعير الحالي: معروف من نطاق ${resolution.matchedScope}" +
                            " (المصدر: ${resolution.resolvedEntry?.provenance ?: "—"})"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        resolution?.isUnknown == false -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag("pricing_form_knowledge")
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = inputPriceInput,
                    onValueChange = { inputPriceInput = it },
                    label = { Text("دخل/مليون") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pricing_form_input_price")
                )
                OutlinedTextField(
                    value = outputPriceInput,
                    onValueChange = { outputPriceInput = it },
                    label = { Text("خرج/مليون") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pricing_form_output_price")
                )
                OutlinedTextField(
                    value = currencyInput,
                    onValueChange = { currencyInput = it },
                    label = { Text("العملة") },
                    singleLine = true,
                    modifier = Modifier
                        .width(88.dp)
                        .testTag("pricing_form_currency")
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = evidenceInput,
                onValueChange = { evidenceInput = it },
                label = { Text("مرجع الدليل (اختياري — رابط نشرة الأسعار/مستند)") },
                placeholder = { Text("https://provider.example.com/pricing") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pricing_form_evidence")
            )
            // STAGE 2B (WS-F/WS-C): the pricing form's validation + sync-
            // failure channels (the resync action is the recovery path).
            state.inputError?.let { error ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("pricing_form_validation")
                )
            }
            if (state.managementMessage?.contains("إعادة مزامنة") == true) {
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(onClick = onResync, modifier = Modifier.testTag("pricing_form_resync")) {
                    Text("إعادة مزامنة الفهرس")
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    // STAGE 2B (WS-F): raw strings — the ViewModel parses with
                    // exact decimal arithmetic and shows a visible error for
                    // invalid input; the currency default is EXPLICIT here in
                    // the UI (never silently guessed downstream).
                    onSave(
                        providerInput.trim(),
                        serviceInput.trim(),
                        modelInput.trim(),
                        inputPriceInput,
                        outputPriceInput,
                        currencyInput.ifBlank { "USD" },
                        evidenceInput.trim().ifBlank { null }
                    )
                },
                enabled = providerInput.isNotBlank() &&
                    (inputPriceInput.isNotBlank() || outputPriceInput.isNotBlank()) &&
                    !state.isSavingPricing,
                modifier = Modifier.testTag("pricing_form_save")
            ) {
                if (state.isSavingPricing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تسجيل التسعير")
                }
            }
        }
    }
}

@Composable
private fun PricingEntryCard(entry: com.example.domain.core.budget.PricingEntry) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("pricing_entry_${entry.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Icon(
                Icons.Default.AttachMoney,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${entry.providerId} • ${entry.scope.name}" +
                        (entry.modelId?.let { " • $it" } ?: "") +
                        (entry.serviceId?.let { " • $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = buildString {
                        entry.inputPricePerMillion?.let { price ->
                            append("دخل ").append(microToText(price.amountMicro ?: 0L, price.currency)).append("/مليون • ")
                        }
                        entry.outputPricePerMillion?.let { price ->
                            append("خرج ").append(microToText(price.amountMicro ?: 0L, price.currency)).append("/مليون")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "المصدر: ${entry.provenance} • من ${formatDate(entry.effectiveFromEpochMs)}" +
                        (entry.effectiveToEpochMs?.let { " حتى ${formatDate(it)}" } ?: " (بلا نهاية)"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (entry.provenance.startsWith("USER_CONFIG")) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                }
            ) {
                // STAGE 2B (WS-D): honest provenance — user-entered evidence is
                // NOT independently verified. The badge says exactly that;
                // the evidence reference (if recorded) is shown beneath it.
                Text(
                    text = if (entry.provenance.startsWith("USER_CONFIG")) {
                        "مُدخل يدوياً — غير مُتحقق بشكل مستقل"
                    } else entry.provenance,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
    }
    if (entry.provenance.startsWith("USER_CONFIG") && entry.provenance.contains("|ref=")) {
        Text(
            text = "مرجع الدليل: ${entry.provenance.substringAfter("|ref=")}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 28.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun HonestEmptyCard(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp)
        )
    }
}

/** Micro units (the ledger's fixed-point domain) → a readable money string. */
private fun microToText(micro: Long, currency: String): String {
    val whole = micro / 1_000_000
    val fraction = ((micro % 1_000_000).toDouble().let { if (it < 0) -it else it } / 10_000).toInt()
    return "%s %d.%02d".format(currency, whole, fraction)
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(epochMs))

@Composable
private fun PeriodBalanceCard(
    row: PeriodBalanceRow,
    selected: Boolean,
    utilization: com.example.application.budget.BudgetDashboardService.Utilization,
    lifecycle: com.example.application.budget.BudgetDashboardService.PeriodLifecycle,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick)
            .testTag("budget_period_${row.periodId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (selected) Icons.Default.Savings else Icons.Default.AttachMoney,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = row.periodId,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // STAGE 2B (WS-B): the DERIVED lifecycle badge — the period's
                // displayed state reflects its actual lifecycle (a window that
                // passed never shows as merely OPEN; outstanding obligations
                // are named).
                PeriodLifecycleBadge(lifecycle = lifecycle)
                if (selected) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "المعروضة",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "الفترة: ${formatDate(row.periodStartEpochMs)} ← ${formatDate(row.periodEndEpochMs)}" +
                    // STAGE 2B (WS-E): the scope of every displayed period is
                    // distinguishable (workspace id when the row carries it).
                    (row.scopePlatformObjectId?.let { " • نطاق: $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BalanceStat(
                    icon = Icons.Default.Savings,
                    label = "السقف",
                    value = microToText(row.limitMicroUnits, "و.ن"),
                    modifier = Modifier.weight(1f)
                )
                BalanceStat(
                    icon = Icons.Default.Payments,
                    label = "المرحّل",
                    value = microToText(row.postedEffectsMicroUnits, "و.ن"),
                    modifier = Modifier.weight(1f)
                )
                BalanceStat(
                    icon = Icons.Default.LockClock,
                    label = "المحجوز",
                    value = microToText(row.remainingHoldMicroUnits, "و.ن"),
                    modifier = Modifier.weight(1f)
                )
                BalanceStat(
                    icon = Icons.Default.CheckCircle,
                    label = "المتاح",
                    value = microToText(row.availableMicroUnits(), "و.ن"),
                    modifier = Modifier.weight(1f),
                    highlight = true
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            UtilizationLine(
                postedToLimit = utilization.postedToLimit,
                holdsToLimit = utilization.holdsToLimit
            )
        }
    }
}

/** The utilization derivation type alias keeps the card decoupled from VM internals. */

/** STAGE 2B (WS-B) — the derived lifecycle badge of a displayed period. */
@Composable
private fun PeriodLifecycleBadge(
    lifecycle: com.example.application.budget.BudgetDashboardService.PeriodLifecycle
) {
    val (label, color) = when (lifecycle) {
        com.example.application.budget.BudgetDashboardService.PeriodLifecycle.ACTIVE ->
            "نشطة" to MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        com.example.application.budget.BudgetDashboardService.PeriodLifecycle.ENDED ->
            "منتهية" to MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        com.example.application.budget.BudgetDashboardService.PeriodLifecycle.ENDED_WITH_OUTSTANDING_HOLDS ->
            "منتهية — حجوزات قائمة" to MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        com.example.application.budget.BudgetDashboardService.PeriodLifecycle.SUPERSEDED ->
            "مُستبدلة بإصدار أحدث" to MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        com.example.application.budget.BudgetDashboardService.PeriodLifecycle.SUPERSEDED_WITH_OUTSTANDING_HOLDS ->
            "مُستبدلة — حجوزات قائمة" to MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        com.example.application.budget.BudgetDashboardService.PeriodLifecycle.CLOSED ->
            "مغلقة" to MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color,
        modifier = Modifier.testTag("period_lifecycle_badge")
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun UtilizationLine(postedToLimit: Float, holdsToLimit: Float) {
    val postedPct = if (postedToLimit.isNaN()) null else (postedToLimit * 100).toInt()
    val holdsPct = if (holdsToLimit.isNaN()) null else (holdsToLimit * 100).toInt()
    val text = when {
        postedPct == null && holdsPct == null -> "استخدام: مراقبة بلا سقف"
        else -> buildString {
            append("استخدام: ")
            append(postedPct?.let { "$it%" } ?: "—")
            append(" مرحّل • ")
            append(holdsPct?.let { "$it%" } ?: "—")
            append(" محجوز")
        }
    }
    val warning = (postedPct ?: 0) >= 80 || (holdsPct ?: 0) >= 80
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = when {
            warning -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

@Composable
private fun BalanceStat(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LedgerEntryRow(entry: com.example.infrastructure.persistence.entities.BudgetLedgerEntryEntity) {
    val (typeLabel, typeColor) = when (entry.entryType) {
        "SETTLE" -> "تسوية" to MaterialTheme.colorScheme.primary
        "RELEASE" -> "تحرير" to MaterialTheme.colorScheme.tertiary
        "CONSUMPTION" -> "استهلاك" to MaterialTheme.colorScheme.secondary
        "REVERSAL" -> "قيد عكسي" to MaterialTheme.colorScheme.error
        "ADJUSTMENT" -> "تسوية يدوية" to Color(0xFFF59E0B)
        else -> entry.entryType to MaterialTheme.colorScheme.outline
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .testTag("budget_entry_${entry.entryId}")
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = typeLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = typeColor
            )
            Text(
                text = "${formatDate(entry.entryTimestampEpochMs)} • الأساس: ${entry.entryBasis} • ${entry.currency}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = microToText(entry.availableEffectMicroUnits, entry.currency),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (entry.availableEffectMicroUnits >= 0) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun HoldRow(hold: com.example.infrastructure.persistence.entities.BudgetHoldEntity) {
    val (statusLabel, statusColor) = when (hold.status) {
        "ACTIVE" -> "نشط" to MaterialTheme.colorScheme.primary
        "SETTLED" -> "مُسوّى" to MaterialTheme.colorScheme.tertiary
        "PARTIALLY_SETTLED" -> "مُسوّى جزئياً" to Color(0xFFF59E0B)
        "RELEASED" -> "محرَّر" to MaterialTheme.colorScheme.outline
        "EXPIRED" -> "منتهٍ" to MaterialTheme.colorScheme.outline
        else -> hold.status to MaterialTheme.colorScheme.outline
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("budget_hold_${hold.holdId}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                if (hold.status == "ACTIVE") Icons.Default.Lock else Icons.Default.LockClock,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hold.reason ?: "حجز ${hold.holdId.takeLast(8)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = buildString {
                        append(formatDate(hold.createdAtEpochMs))
                        hold.expiresAtEpochMs?.let { append(" • ينتهي: ${formatDate(it)}") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = microToText(hold.holdAmountMicroUnits, hold.currency),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderCostCard(row: com.example.infrastructure.persistence.dao.ProviderCostRow) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("budget_provider_cost_${row.providerId}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Icon(
                Icons.Default.AttachMoney,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.providerId,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${row.calculationCount} عملية احتساب عبر سلسلة التسعير",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = microToText(row.calculatedCostMicroUnits, "و.ن"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
