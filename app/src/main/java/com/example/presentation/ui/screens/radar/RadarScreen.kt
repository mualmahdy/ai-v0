package com.example.presentation.ui.screens.radar

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.evolution.EvolutionStage
import com.example.domain.core.radar.RadarCategory
import com.example.presentation.ui.components.EmptyState
import com.example.presentation.ui.components.SectionHeader
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * RadarScreen — evolution pipeline + ecosystem feed, now with filters
 * ============================================================================
 *
 * Refresh with a live spinner (previously missing), stage FILTERING of
 * evolution candidates, category FILTERING of the ecosystem feed, richer
 * candidate cards (target type, security audit + governance gates, proven
 * confidence), and the full promotion lifecycle actions.
 */
@Composable
fun RadarScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var stageFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }

    val filteredCandidates = state.evolutionCandidates.filter {
        stageFilter == null || it.stage.code == stageFilter
    }
    val filteredItems = state.radarItems.filter {
        categoryFilter == null || it.category.code == categoryFilter
    }

    LazyColumn(
        modifier = modifier.testTag("screen_radar_evolution"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SectionHeader(
                icon = Icons.Default.Radar,
                title = "رادار التطور التقني",
                subtitle = "خط اكتشاف → تصنيف → تدقيق → دمج — بدورة حياة مُحكمة",
                trailing = {
                    IconButton(
                        onClick = viewModel::refreshRadar,
                        modifier = Modifier.testTag("btn_refresh_radar")
                    ) {
                        if (state.isRadarRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "تحديث",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }

        // ===================== Candidates =====================
        item {
            SectionHeader(
                icon = Icons.Default.TrendingUp,
                title = "مرشحات التطوير (${filteredCandidates.size}/${state.evolutionCandidates.size})"
            )
        }

        if (state.evolutionCandidates.isNotEmpty()) {
            item {
                StageFilterRow(
                    selected = stageFilter,
                    onSelect = { stageFilter = if (stageFilter == it) null else it }
                )
            }
        }

        if (filteredCandidates.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Radar,
                    title = if (state.evolutionCandidates.isEmpty()) "لا مرشحات تطوير بعد"
                    else "لا مرشحات في هذه المرحلة",
                    hint = "اضغط زر التحديث لمسح منظومة التقنيات (GitHub / RSS) واشتقاق مرشحات جديدة."
                )
            }
        } else {
            items(filteredCandidates, key = { it.id }) { candidate ->
                CandidateCard(
                    candidate = candidate,
                    onAdvance = { next -> viewModel.advanceCandidateStage(candidate.id, next) },
                    onAudit = { passed -> viewModel.recordCandidateSecurityAudit(candidate.id, passed) },
                    onGovernance = { approved -> viewModel.recordCandidateGovernanceApproval(candidate.id, approved) },
                    onMeasure = { viewModel.measureRegisteredCapability(candidate.id) },
                    onRetire = { viewModel.retireRegisteredCapability(candidate.id, "تقادم/بديل أفضل") }
                )
            }
        }

        // ===================== Ecosystem feed =====================
        item {
            SectionHeader(
                icon = Icons.Default.Public,
                title = "تغذية المنظومة (${filteredItems.size}/${state.radarItems.size})",
                subtitle = "إصدارات، مستودعات، MCP، أطر عمل، أوراق بحثية"
            )
        }

        if (state.radarItems.isNotEmpty()) {
            item {
                CategoryFilterRow(
                    selected = categoryFilter,
                    onSelect = { categoryFilter = if (categoryFilter == it) null else it }
                )
            }
        }

        if (filteredItems.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Public,
                    title = "لا عناصر في التغذية",
                    hint = "اسحب التغذية من مصادر الرادار المتاحة."
                )
            }
        } else {
            items(filteredItems, key = { it.id }) { item ->
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
                            StatusBadge(
                                text = item.category.displayName,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "مصدر: ${item.sourceName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3
                        )
                        item.extractedCapability?.let { profile ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                StatusBadge(
                                    text = "توافق ${(profile.compatibilityScore * 100).toInt()}%",
                                    tint = if (profile.compatibilityScore > 0.7f)
                                        MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.secondary
                                )
                                if (profile.isOfflineCompatible) {
                                    StatusBadge(
                                        text = "متوافق مع العمل دون اتصال",
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                }
                                if (profile.requiresCloudAuth) {
                                    StatusBadge(
                                        text = "يتطلب مصادقة سحابية",
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Candidate card with the full lifecycle actions
// ---------------------------------------------------------------------------

@Composable
private fun CandidateCard(
    candidate: com.example.domain.core.evolution.EvolutionCandidate,
    onAdvance: (EvolutionStage) -> Unit,
    onAudit: (Boolean) -> Unit,
    onGovernance: (Boolean) -> Unit,
    onMeasure: () -> Unit,
    onRetire: () -> Unit
) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "النوع: ${candidate.targetType} • ثقة ${(candidate.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(
                    text = candidate.stage.displayName,
                    tint = stageTint(candidate.stage)
                )
            }

            if (candidate.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = candidate.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBadge(
                    text = if (candidate.securityAuditPassed) "تدقيق أمني: ناجح" else "تدقيق أمني: لم ينجح بعد",
                    tint = if (candidate.securityAuditPassed) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.outline
                )
                StatusBadge(
                    text = if (candidate.governanceApproved) "حوكمة: ممنوحة" else "حوكمة: معلّقة",
                    tint = if (candidate.governanceApproved) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.secondary
                )
            }

            // ---- Lifecycle actions by stage ----
            Spacer(modifier = Modifier.height(10.dp))
            when (candidate.stage) {
                EvolutionStage.DISCOVERED -> {
                    LifecycleAction("فهم وتحليل دلالي", Icons.Default.TrendingUp) {
                        onAdvance(EvolutionStage.UNDERSTOOD)
                    }
                }
                EvolutionStage.UNDERSTOOD -> {
                    LifecycleAction("تصنيف المرشح", Icons.Default.TrendingUp) {
                        onAdvance(EvolutionStage.CLASSIFIED)
                    }
                }
                EvolutionStage.CLASSIFIED -> {
                    LifecycleAction("تقييم أمني وتقني", Icons.Default.TrendingUp) {
                        onAdvance(EvolutionStage.EVALUATED)
                    }
                }
                EvolutionStage.EVALUATED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onAudit(true) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_audit_${candidate.id}")
                        ) { Text("تدقيق ناجح", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(
                            onClick = { onAudit(false) },
                            modifier = Modifier.weight(1f)
                        ) { Text("تدقيق فاشل", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                EvolutionStage.CANDIDATE -> {
                    LifecycleAction("ترقية لبانتظار الحوكمة", Icons.Default.TrendingUp) {
                        onAdvance(EvolutionStage.APPROVAL_PENDING)
                    }
                }
                EvolutionStage.APPROVAL_PENDING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onGovernance(true) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_governance_${candidate.id}")
                        ) { Text("موافقة الحوكمة", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(
                            onClick = { onGovernance(false) },
                            modifier = Modifier.weight(1f)
                        ) { Text("رفض", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                EvolutionStage.INTEGRATED -> {
                    LifecycleAction("تحقق واختبار", Icons.Default.CheckCircle) {
                        onAdvance(EvolutionStage.VERIFIED)
                    }
                }
                EvolutionStage.VERIFIED -> {
                    LifecycleAction("تسجيل بمصفوفة القدرات", Icons.Default.CheckCircle) {
                        onAdvance(EvolutionStage.REGISTERED)
                    }
                }
                EvolutionStage.REGISTERED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onMeasure,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_measure_${candidate.id}")
                        ) { Text("قياس أساس القدرة", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(
                            onClick = onRetire,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_retire_${candidate.id}")
                        ) { Text("تقاعد القدرة", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                EvolutionStage.REJECTED -> {
                    Text(
                        text = "مرفوض — لا أفعال متاحة.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun LifecycleAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StageFilterRow(selected: String?, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(EvolutionStage.entries) { stage ->
            FilterChip(
                selected = selected == stage.code,
                onClick = { onSelect(stage.code) },
                label = {
                    Text(
                        stage.displayName.substringBefore(" ("),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
    }
}

@Composable
private fun CategoryFilterRow(selected: String?, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(RadarCategory.entries) { category ->
            FilterChip(
                selected = selected == category.code,
                onClick = { onSelect(category.code) },
                label = {
                    Text(
                        category.displayName,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
    }
}

@Composable
private fun stageTint(stage: EvolutionStage) = when (stage) {
    EvolutionStage.REGISTERED, EvolutionStage.VERIFIED, EvolutionStage.INTEGRATED ->
        MaterialTheme.colorScheme.tertiary
    EvolutionStage.REJECTED -> MaterialTheme.colorScheme.error
    EvolutionStage.APPROVAL_PENDING, EvolutionStage.EVALUATED -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}
