package com.example.presentation.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.BudgetStatus
import com.example.domain.core.budget.CostStatus
import com.example.domain.core.budget.UsageCostRecord
import com.example.domain.core.radar.CapabilityChangeRecord
import com.example.domain.core.radar.OperationalCapabilityState
import com.example.domain.core.radar.RadarCapabilityStatus
import com.example.domain.core.radar.RadarRecommendation
import com.example.presentation.state.UiState
import com.example.presentation.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * GovernanceObservatoryScreen — Intelligence Governance & Sustainability
 * ============================================================================
 *
 * GOVERNANCE PHASE observatory (the LAST layer of the architecture, built
 * after domain/persistence/services/runtime/decision/telemetry):
 *
 *   - Capability Radar: evidence-derived operational states, health, gaps'
 *     recommendations, and detected changes — straight from the Room-backed
 *     flows, never UI-invented.
 *   - Economic Budget: token usage, monetary budget (allocated / consumed /
 *     remaining / UNKNOWN), billing classes, cost ledger with honest UNKNOWN
 *     display, and the workspace budget policy controls.
 *
 * TRUTHFULNESS CONTRACT: every UNKNOWN is rendered as UNKNOWN. No fabricated
 * numbers, no fake health, no decorative dashboards.
 */
@Composable
fun GovernanceObservatoryScreen(
    state: UiState,
    viewModel: MainViewModel
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .testTag("governance_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Radar, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("مرصد الحوكمة والاستدامة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "حالة القدرات (مبنية على الأدلة) + حوكمة الميزانية والتكلفة",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { viewModel.refreshGovernance() }, modifier = Modifier.testTag("btn_refresh_governance")) {
                    Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                }
            }
        }

        // ============ Section 1: Capability Radar ============
        item {
            SectionHeader("رادار القدرات التشغيلية", "المشتق من الأدلة الحية — لا حالات مزيّفة")
        }
        if (state.radarCapabilityStatuses.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "لا توجد حالة قدرات مشتقة بعد.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "اضغط زر التحديث لاشتقاق اللقطة الأولى من السجل والأدلة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(state.radarCapabilityStatuses, key = { it.capabilityKey }) { status ->
                RadarCapabilityRow(status)
            }
        }

        if (state.radarRecommendations.isNotEmpty()) {
            item { SectionHeader("توصيات الرادار", "مبنية على الأدلة — قابلة للتنفيذ") }
            items(state.radarRecommendations, key = { it.id }) { reco ->
                RecommendationRow(reco) { viewModel.dismissRadarRecommendation(reco.id) }
            }
        }

        if (state.radarChanges.isNotEmpty()) {
            item { SectionHeader("تغيّرات مكتشفة (التطور)", "انتقالات حالة القدرات") }
            items(state.radarChanges.take(10), key = { it.id }) { change ->
                ChangeRow(change)
            }
        }

        // ============ Section 2: Economic Budget ============
        item {
            SectionHeader("حوكمة الميزانية والتكلفة", "الاستخدام محسوب، التسعير صريح، المجهول يُعرض كمجهول")
        }
        item {
            BudgetSummaryCard(state.workspaceBudgetStatus, state.workspaceTokensConsumed)
        }
        item {
            BudgetAllocationEditor(
                initial = state.budgetAllocationInputUsd,
                isSaving = state.isSavingBudgetAllocation,
                onSave = { usd -> viewModel.setWorkspaceBudgetAllocationUsd(usd) }
            )
        }
        if (state.costLedgerRecent.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        "لا توجد قيود تكلفة بعد — سجلّها يُملأ من التنفيذات الحقيقية.",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            item { SectionHeader("سجل التكلفة (آخر القيود)", "التقدير يُميّز عن الفعلي؛ مجهول يبقى مجهولاً") }
            items(state.costLedgerRecent, key = { it.id }) { record ->
                LedgerRow(record)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun RadarCapabilityRow(status: RadarCapabilityStatus) {
    val stateColor = when (status.state) {
        OperationalCapabilityState.AVAILABLE -> Color(0xFF2E7D32)
        OperationalCapabilityState.DEGRADED, OperationalCapabilityState.PARTIAL -> Color(0xFFEF6C00)
        OperationalCapabilityState.BLOCKED, OperationalCapabilityState.FAILED -> MaterialTheme.colorScheme.error
        OperationalCapabilityState.DISABLED, OperationalCapabilityState.DEPRECATED -> Color(0xFF616161)
        else -> Color(0xFF9E9E9E)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("radar_capability_${status.capabilityKey}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status.capabilityKey,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    status.state.name,
                    color = stateColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(4.dp)
                        .testTag("radar_state_${status.capabilityKey}")
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "الصحة: ${status.health.name} | الاتجاه: ${status.trend.name} | الأدلة: ${status.evidenceCount}" +
                    (status.lastEvidenceEpochMs?.let { " | آخر دليل: ${formatTime(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                status.rationale,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun RecommendationRow(reco: RadarRecommendation, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("radar_recommendation_${reco.id}"),
        colors = CardDefaults.cardColors(
            containerColor = when (reco.priority.name) {
                "HIGH", "CRITICAL" -> MaterialTheme.colorScheme.errorContainer
                "MEDIUM" -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = when (reco.priority.name) {
                    "HIGH", "CRITICAL" -> Icons.Default.WarningAmber
                    else -> Icons.Default.Memory
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(reco.message, style = MaterialTheme.typography.bodySmall)
                reco.actionHint?.let {
                    Text(
                        "إجراء مقترح: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(onClick = onDismiss) { Text("تجاهل") }
        }
    }
}

@Composable
private fun ChangeRow(change: CapabilityChangeRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (change.toState == OperationalCapabilityState.AVAILABLE) Icons.Default.CheckCircle
                    else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (change.toState == OperationalCapabilityState.AVAILABLE) Color(0xFF2E7D32)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${change.capabilityKey}: ${change.fromState.name} ← ${change.toState.name}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatTime(change.detectedAtEpochMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                change.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BudgetSummaryCard(budget: BudgetStatus?, tokensConsumed: Long) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("budget_summary_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountBalance, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("ملخص ميزانية مساحة العمل", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))

            if (budget == null) {
                Text("لا توجد حالة ميزانية محمّلة بعد.", style = MaterialTheme.typography.bodySmall)
            } else {
                val allocated = budget.allocation?.allocated
                Text(
                    "المخصص: " + when {
                        budget.hasNoAllocation -> "غير مضبوط (التكلفة تُتتبع دون سقف)"
                        allocated?.amountMicro == null -> "مجهول (UNKNOWN)"
                        else -> formatMoney(allocated.amountMicro, allocated.currency)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "المستهلك: " + (budget.consumed.amountMicro?.let { formatMoney(it, budget.currency) }
                        ?: "مجهول (UNKNOWN)") +
                        (if (budget.consumed.isUnknown) " — بعض القيود بتكلفة مجهولة" else ""),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "المتبقي: " + (budget.remaining?.amountMicro?.let { formatMoney(it, budget.currency) }
                        ?: if (budget.hasNoAllocation) "غير محدود (بلا سقف مضبوط)" else "مجهول"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "نسبة الاستخدام: " + (budget.utilizationRatio?.let { "%.0f%%".format(it * 100) } ?: "غير محددة"),
                    style = MaterialTheme.typography.bodySmall
                )
                budget.allocation?.policy?.let { policy ->
                    Text(
                        "السياسة: ${policy.actions.joinToString(" ثم ") { it.name }} (تحذير عند ${"%.0f".format(policy.warnThresholdRatio * 100)}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "إجمالي التوكنز المستهلكة في مساحة العمل: $tokensConsumed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BudgetAllocationEditor(
    initial: String,
    isSaving: Boolean,
    onSave: (Double) -> Unit
) {
    var input by remember(initial) { mutableStateOf(initial) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("ضبط مخصص الميزانية (USD)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "سياسة افتراضية: HARD_LIMIT عند التجاوز + تحذير عند 80%.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("المبلغ (USD)") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("budget_allocation_input"),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                } else {
                    Button(
                        onClick = { input.toDoubleOrNull()?.let { onSave(it) } },
                        modifier = Modifier.testTag("btn_save_budget_allocation")
                    ) {
                        Text("حفظ")
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerRow(record: UsageCostRecord) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cost_ledger_${record.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        buildString {
                            append(record.providerId ?: "مزود غير معروف")
                            record.modelId?.let { append(" / $it") }
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "دخل: ${record.usage.inputTokens} | خرج: ${record.usage.outputTokens}" +
                            (if (record.usage.cachedTokens > 0) " | مخبأ: ${record.usage.cachedTokens}" else "") +
                            (if (record.usage.isEstimate) " (تقديري)" else " (فعلي)"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        when {
                            record.cost?.amountMicro != null -> formatMoney(record.cost.amountMicro, record.cost.currency)
                            else -> "UNKNOWN"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${billingClassLabel(record.billingClass)} • ${record.costStatus.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = when (record.costStatus) {
                            CostStatus.ACTUAL -> Color(0xFF2E7D32)
                            CostStatus.ESTIMATED -> Color(0xFFEF6C00)
                            else -> Color(0xFF9E9E9E)
                        }
                    )
                }
            }
            Text(
                "${formatTime(record.timestampEpochMs)}${record.executionId.take(8).let { " | تنفيذ: $it…" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun billingClassLabel(cls: BillingClass): String = when (cls) {
    BillingClass.FREE -> "مجاني (FREE)"
    BillingClass.PAID -> "مدفوع (PAID)"
    BillingClass.TRIAL -> "تجريبي (TRIAL)"
    BillingClass.CREDIT -> "رصيد (CREDIT)"
    BillingClass.LOCAL -> "محلي (LOCAL)"
    BillingClass.UNKNOWN -> "غير معروف (UNKNOWN)"
}

private fun formatMoney(amountMicro: Long, currency: String): String =
    "%.6f %s".format(amountMicro / 1_000_000.0, currency)

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
