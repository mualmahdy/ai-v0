package com.example.presentation.ui.screens.governance

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
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.budget.CostStatus
import com.example.domain.core.radar.CapabilityHealth
import com.example.domain.core.radar.CapabilityTrend
import com.example.domain.core.radar.OperationalCapabilityState
import com.example.domain.core.radar.RecommendationPriority
import com.example.presentation.ui.components.DonutChart
import com.example.presentation.ui.components.EmptyState
import com.example.presentation.ui.components.InfoRow
import com.example.presentation.ui.components.MiniBarChart
import com.example.presentation.ui.components.SectionHeader
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * GovernanceScreen — the observatory, now with REAL data visualization
 * ============================================================================
 *
 * Text-only lists become an actual observatory: budget utilization donut +
 * allocation editor, cost-ledger bar chart (tokens per entry) with per-entry
 * attribution rows, capability radar statuses with evidence bar chart,
 * dismissible recommendations and state-change history. All enum states are
 * rendered in Arabic; UNKNOWN is never faked (honesty contract).
 */
@Composable
fun GovernanceScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var budgetInput by remember(state.budgetAllocationInputUsd) {
        mutableStateOf(state.budgetAllocationInputUsd)
    }

    LazyColumn(
        modifier = modifier.testTag("governance_screen"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SectionHeader(
                icon = Icons.Default.Radar,
                title = "مرصد الحوكمة والاستدامة",
                subtitle = "كل حالة مشتقة من أدلة حقيقية (Room) — لا شيء مُفبرَك",
                trailing = {
                    IconButton(
                        onClick = viewModel::refreshGovernance,
                        modifier = Modifier.testTag("btn_refresh_governance")
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "تحديث",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }

        // ===================== BUDGET =====================
        item {
            SectionHeader(
                icon = Icons.Default.AccountBalance,
                title = "الميزانية الاقتصادية لمساحة العمل"
            )
        }

        item {
            val budget = state.workspaceBudgetStatus
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DonutChart(
                            fraction = budget?.utilizationRatio,
                            centerValue = if (budget?.hasNoAllocation == true) "غير محدد"
                            else "${((budget?.utilizationRatio ?: 0f) * 100).toInt()}%",
                            centerLabel = "نسبة الاستهلاك",
                            diameter = 112.dp,
                            ringTint = when {
                                budget == null -> MaterialTheme.colorScheme.outline
                                (budget.utilizationRatio ?: 0f) >= 0.9f -> MaterialTheme.colorScheme.error
                                (budget.utilizationRatio ?: 0f) >= 0.8f -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.tertiary
                            }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            if (budget == null) {
                                InfoRow("الحالة", "لا توجد بيانات ميزانية بعد")
                            } else {
                                InfoRow(
                                    label = "المخصص",
                                    value = moneyText(
                                        budget.allocation?.allocated?.amountMicro,
                                        budget.currency
                                    )
                                )
                                InfoRow(
                                    label = "المستهلك",
                                    value = moneyText(budget.consumed.amountMicro, budget.currency)
                                )
                                InfoRow(
                                    label = "المتبقي",
                                    value = budget.remaining?.let { moneyText(it.amountMicro, budget.currency) }
                                        ?: "غير معروف"
                                )
                                InfoRow(
                                    label = "سياسة الإنفاق",
                                    value = budget.allocation?.let { policyText(it.policy.primaryAction().name) }
                                        ?: "لا سياسة (رصد فقط)"
                                )
                                if (budget.hasNoAllocation) {
                                    InfoRow(
                                        label = "ملاحظة",
                                        value = "لا يوجد سقف تخصيص — التكاليف تُرصد فقط"
                                    )
                                }
                                InfoRow(
                                    label = "رموز مساحة العمل",
                                    value = "${state.workspaceTokensConsumed}"
                                )
                            }
                        }
                    }
                }
            }
        }

        // Budget allocation editor
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = budgetInput,
                        onValueChange = { budgetInput = it },
                        label = { Text("سقف الميزانية (USD)") },
                        placeholder = { Text("مثال: 5.00") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("budget_allocation_input")
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = { viewModel.setWorkspaceBudgetAllocationUsd(budgetInput.toDoubleOrNull() ?: 0.0) },
                        enabled = budgetInput.isNotBlank() && !state.isSavingBudgetAllocation,
                        modifier = Modifier.testTag("btn_save_budget_allocation")
                    ) {
                        if (state.isSavingBudgetAllocation) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // ===================== COST LEDGER =====================
        item {
            SectionHeader(
                icon = Icons.Default.Paid,
                title = "سجل التكاليف (آخر ${state.costLedgerRecent.size} قيداً)"
            )
        }

        if (state.costLedgerRecent.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Paid,
                    title = "لا قيود تكلفة بعد",
                    hint = "تُسجَّل قيود التكلفة تلقائياً مع كل استدعاء مدفوع للنماذج مع سلسلة عزو كاملة."
                )
            }
        } else {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "إجمالي الرموز لكل قيد (الأحدث أولاً)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        MiniBarChart(
                            values = state.costLedgerRecent.map { it.usage.totalTokens.toFloat() }
                        )
                    }
                }
            }
            items(state.costLedgerRecent, key = { it.id }) { record ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = record.modelId ?: "نموذج غير معروف",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            StatusBadge(
                                text = when (record.costStatus) {
                                    CostStatus.ESTIMATED -> "تكلفة تقديرية"
                                    CostStatus.ACTUAL -> "تكلفة فعلية"
                                    CostStatus.UNKNOWN -> "تكلفة غير معروفة"
                                },
                                tint = when (record.costStatus) {
                                    CostStatus.ACTUAL -> MaterialTheme.colorScheme.tertiary
                                    CostStatus.ESTIMATED -> MaterialTheme.colorScheme.secondary
                                    CostStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        InfoRow(
                            label = "الرموز",
                            value = "دخول ${record.usage.inputTokens} • خروج ${record.usage.outputTokens}" +
                                (if (record.usage.cachedTokens > 0) " • مخبّأ ${record.usage.cachedTokens}" else "")
                        )
                        InfoRow(
                            label = "التكلفة",
                            value = moneyText(record.cost?.amountMicro, record.cost?.currency ?: "USD"),
                            valueTint = if (record.cost?.amountMicro != null) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.outline
                        )
                        InfoRow(
                            label = "فئة الفوترة",
                            value = billingText(record.billingClass.name),
                            monospace = false
                        )
                        InfoRow(
                            label = "العزو",
                            value = buildString {
                                record.agentId?.let { append("وكيل: ${it.take(12)}…") }
                                record.providerId?.let { if (isNotEmpty()) append(" • "); append("مزود: $it") }
                                if (isEmpty()) append("غير متوفر")
                            },
                            monospace = true
                        )
                    }
                }
            }
        }

        // ===================== CAPABILITY RADAR =====================
        item {
            SectionHeader(
                icon = Icons.Default.Radar,
                title = "رادار القدرات التشغيلي (${state.radarCapabilityStatuses.size})"
            )
        }

        if (state.radarCapabilityStatuses.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Radar,
                    title = "لا حالات قدرات مشتقة بعد",
                    hint = "اضغط التحديث لاشتقاق لقطة رادار من الأدلة المسجلة في مساحة العمل."
                )
            }
        } else {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "عدد الأدلة المدروسة لكل قدرة",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        MiniBarChart(
                            values = state.radarCapabilityStatuses.map { it.evidenceCount.toFloat() },
                            barTint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
            items(state.radarCapabilityStatuses, key = { it.capabilityKey }) { status ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = status.capabilityKey,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            StatusBadge(
                                text = stateText(status.state.name),
                                tint = stateTint(status.state)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusBadge(
                                text = healthText(status.health.name),
                                tint = status.health.toTint()
                            )
                            StatusBadge(
                                text = trendText(status.trend.name),
                                tint = status.trend.toTint()
                            )
                            StatusBadge(
                                text = "${status.evidenceCount} دليل",
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = status.rationale,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3
                        )
                    }
                }
            }
        }

        // ===================== RECOMMENDATIONS =====================
        if (state.radarRecommendations.isNotEmpty()) {
            item {
                SectionHeader(
                    icon = Icons.Default.TrendingUp,
                    title = "توصيات قابلة للتنفيذ (${state.radarRecommendations.size})"
                )
            }
            items(state.radarRecommendations, key = { it.id }) { reco ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (reco.priority) {
                            RecommendationPriority.CRITICAL -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            RecommendationPriority.HIGH -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        }
                    )
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.weight(1f)) {
                            StatusBadge(
                                text = when (reco.priority) {
                                    RecommendationPriority.LOW -> "أولوية منخفضة"
                                    RecommendationPriority.MEDIUM -> "أولوية متوسطة"
                                    RecommendationPriority.HIGH -> "أولوية عالية"
                                    RecommendationPriority.CRITICAL -> "حرجة"
                                },
                                tint = when (reco.priority) {
                                    RecommendationPriority.CRITICAL -> MaterialTheme.colorScheme.error
                                    RecommendationPriority.HIGH -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = reco.message,
                                style = MaterialTheme.typography.bodySmall
                            )
                            reco.actionHint?.let {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "الإجراء المقترح: $it",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.dismissRadarRecommendation(reco.id) },
                            modifier = Modifier.testTag("radar_recommendation_${reco.id}")
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "تجاهل التوصية",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // ===================== STATE CHANGES =====================
        if (state.radarChanges.isNotEmpty()) {
            item {
                SectionHeader(
                    icon = Icons.Default.TrendingUp,
                    title = "سجل تغيّر الحالات (${state.radarChanges.size})"
                )
            }
            items(state.radarChanges, key = { it.id }) { change ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = change.capabilityKey,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${stateText(change.fromState.name)} ← ${stateText(change.toState.name)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (change.detail.isNotBlank()) {
                            Text(
                                text = change.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Arabic label maps (honest UNKNOWN rendering preserved)
// ---------------------------------------------------------------------------

private fun moneyText(amountMicro: Long?, currency: String): String =
    amountMicro?.let { "%.4f %s".format(it / 1_000_000.0, currency) } ?: "غير معروفة ($currency)"

private fun policyText(action: String): String = when (action) {
    "HARD_LIMIT" -> "حد صارم (رفض عند التجاوز)"
    "SOFT_LIMIT" -> "حد مرن (تحذير عند التجاوز)"
    "AUTO_DOWNGRADE" -> "تخفيض تلقائي لنموذج أرخص"
    "AUTO_LOCAL_FALLBACK" -> "تحويل تلقائي لمورد محلي"
    "REQUIRE_APPROVAL" -> "يتطلب موافقة بشرية"
    else -> action
}

private fun billingText(billing: String): String = when (billing) {
    "FREE" -> "مجاني (فئة مجانية)"
    "PAID" -> "مدفوع"
    "TRIAL" -> "تجريبي"
    "CREDIT" -> "رصيد مسبق الدفع"
    else -> "غير محدد"
}

private fun stateText(state: String): String = when (state) {
    "UNKNOWN" -> "غير معروف"
    "PLANNED" -> "مخطط"
    "AVAILABLE" -> "متاح"
    "PARTIAL" -> "جزئي"
    "DEGRADED" -> "متراجع"
    "BLOCKED" -> "محجوب"
    "FAILED" -> "فاشل"
    "DISABLED" -> "معطّل"
    "DEPRECATED" -> "مُلغى"
    else -> state
}

@Composable
private fun stateTint(state: OperationalCapabilityState): androidx.compose.ui.graphics.Color = when (state) {
    OperationalCapabilityState.AVAILABLE -> MaterialTheme.colorScheme.tertiary
    OperationalCapabilityState.PARTIAL, OperationalCapabilityState.DEGRADED -> MaterialTheme.colorScheme.secondary
    OperationalCapabilityState.BLOCKED, OperationalCapabilityState.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

private fun healthText(health: String): String = when (health) {
    "HEALTHY" -> "صحي"
    "DEGRADED_HEALTH" -> "صحة متراجعة"
    "FAILING" -> "يفشل"
    "UNAVAILABLE" -> "غير متاح"
    else -> "غير معروف"
}

private fun trendText(trend: String): String = when (trend) {
    "IMPROVING" -> "يتحسّن"
    "STABLE" -> "مستقر"
    "DETERIORATING" -> "يتدهور"
    else -> "غير معروف"
}

@Composable
private fun CapabilityHealth.toTint(): androidx.compose.ui.graphics.Color = when (this) {
    CapabilityHealth.HEALTHY -> MaterialTheme.colorScheme.tertiary
    CapabilityHealth.DEGRADED_HEALTH, CapabilityHealth.UNAVAILABLE -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun CapabilityTrend.toTint(): androidx.compose.ui.graphics.Color = when (this) {
    CapabilityTrend.IMPROVING -> MaterialTheme.colorScheme.tertiary
    CapabilityTrend.DETERIORATING -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.outline
}
