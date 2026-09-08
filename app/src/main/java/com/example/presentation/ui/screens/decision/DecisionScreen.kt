package com.example.presentation.ui.screens.decision

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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.presentation.ui.components.DonutChart
import com.example.presentation.ui.components.EmptyState
import com.example.presentation.ui.components.InfoRow
import com.example.presentation.ui.components.MetricBar
import com.example.presentation.ui.components.SectionHeader
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * DecisionScreen — the CBR-MDP engine cockpit
 * ============================================================================
 *
 * Real decision-engine surface: the chosen action with confidence donut and
 * rationale, ranked alternatives as metric bars, live simulation spinner
 * (previously missing), case-base statistics, and an honest explainer of
 * how decisions are made (retrieval + Q-learning + governance gates BEFORE
 * the engine).
 */
@Composable
fun DecisionScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = modifier.testTag("screen_decision_intelligence"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SectionHeader(
                icon = Icons.Default.Psychology,
                title = "ذكاء القرار (CBR-MDP)",
                subtitle = "استرجاع الحالات المشابهة + قيم Q المكتسبة — تعلّم معزز حقيقي مستمر"
            )
        }

        // ---- Parameters ----
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "متجه الحالة (Decision State)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "تعقيد المهمة: ${"%.2f".format(state.decisionTaskComplexity)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Slider(
                        value = state.decisionTaskComplexity,
                        onValueChange = viewModel::updateDecisionComplexity,
                        valueRange = 0f..1f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("slider_complexity")
                    )
                    Text(
                        text = "درجة عدم اليقين: ${"%.2f".format(state.decisionUncertainty)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Slider(
                        value = state.decisionUncertainty,
                        onValueChange = viewModel::updateDecisionUncertainty,
                        valueRange = 0f..1f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("slider_uncertainty")
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = viewModel::simulateDecision,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_recompute_decision")
                    ) {
                        if (state.isSimulatingDecision) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري تشغيل المحرك…")
                        } else {
                            Icon(Icons.Default.Calculate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تشغيل المحرك على الحالة الحالية")
                        }
                    }
                }
            }
        }

        // ---- Latest decision ----
        state.latestDecision?.let { decision ->
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DonutChart(
                                fraction = decision.confidence,
                                centerValue = "${(decision.confidence * 100).toInt()}%",
                                centerLabel = "الثقة",
                                diameter = 84.dp
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "الفعل المختار",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = decision.chosenAction.type.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = decision.rationale,
                            style = MaterialTheme.typography.bodySmall
                        )
                        InfoRow(
                            label = "حالات تاريخية مطابقة",
                            value = "${decision.matchedHistoricalCasesCount}"
                        )
                        decision.chosenAction.targetId?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            InfoRow(label = "الهدف", value = it, monospace = true)
                        }
                    }
                }
            }

            // ---- Alternatives ----
            if (decision.evaluatedAlternatives.isNotEmpty()) {
                item {
                    SectionHeader(
                        icon = Icons.Default.AutoAwesome,
                        title = "الأفعال المرشحة البديلة (${decision.evaluatedAlternatives.size})"
                    )
                }
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            decision.evaluatedAlternatives.take(6).forEach { alt ->
                                MetricBar(
                                    fraction = alt.finalScore.coerceIn(0f, 1f),
                                    label = alt.action.type.displayName,
                                    valueText = "Q=${"%.2f".format(alt.mdpValue)} • CBR=${"%.2f".format(alt.cbrScore)}",
                                    barTint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Case base ----
        item {
            SectionHeader(
                icon = Icons.Default.Refresh,
                title = "قاعدة الحالات المكتسبة (${state.caseBaseList.size})"
            )
        }
        if (state.caseBaseList.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Psychology,
                    title = "قاعدة الحالات فارغة",
                    hint = "تتغذى من نتائج التنفيذ الحقيقية — كل مهمة مكتملة تضيف خبرة للمحرك."
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
                        val avgReward = state.caseBaseList.map { it.outcomeReward }.average()
                        val positives = state.caseBaseList.count { it.outcomeReward > 0 }
                        InfoRow(label = "متوسط المكافأة", value = "%.3f".format(avgReward))
                        InfoRow(label = "حالات بمكافأة موجبة", value = "$positives / ${state.caseBaseList.size}")
                    }
                }
            }
            items(state.caseBaseList.take(20), key = { it.id }) { case ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${case.taskType} → ${case.chosenAction.type.displayName}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "حالة ${case.id.take(8)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusBadge(
                            text = "مكافأة ${"%.2f".format(case.outcomeReward)}",
                            tint = if (case.outcomeReward > 0) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // ---- Explainer ----
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "كيف يُتخذ القرار؟",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    InfoRow(
                        label = "1. البوابات",
                        value = "الأمن والسياسات ثم القدرة ثم الميزانية — قبل أي اختيار"
                    )
                    InfoRow(
                        label = "2. CBR",
                        value = "استرجاع أقرب الحالات المشابهة من الخبرة المكتسبة"
                    )
                    InfoRow(
                        label = "3. MDP/Q",
                        value = "قيم Q مكتسبة من المكافآت الفعلية للتنفيذات السابقة"
                    )
                    InfoRow(
                        label = "4. التعلّم",
                        value = "كل ملاحظة رصدت تُحدّث قيم Q — الأداء يتحسن بالاستخدام"
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}
