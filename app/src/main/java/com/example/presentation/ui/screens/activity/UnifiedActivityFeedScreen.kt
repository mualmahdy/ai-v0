package com.example.presentation.ui.screens.activity

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
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.presentation.ui.components.EmptyState
import com.example.presentation.ui.components.SectionHeader
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.ui.navigation.WorkspaceRoutes
import com.example.presentation.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * UnifiedActivityFeedScreen — actionable, filterable workspace timeline
 * ============================================================================
 *
 * The feed now FILTERS (suggestions / execution trace / audit events), the
 * proactive suggestions are TAPPABLE (each kind routes to the screen where
 * the user can actually resolve it), audit decisions get semantic badges,
 * and every row shows formatted timestamps. TestTags added (previously
 * missing entirely).
 */
@Composable
fun UnifiedActivityFeedScreen(
    viewModel: MainViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestions by viewModel.activeSuggestions.collectAsState()
    val executionTrace by viewModel.activeExecutionTrace.collectAsState()
    val auditEvents by viewModel.recentAuditEvents.collectAsState()
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.ENGLISH) }

    Column(modifier = modifier.testTag("screen_unified_activity")) {
        // ---- Filters ----
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 6.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val filters = listOf(
                "الكل (${suggestions.size + executionTrace.size + auditEvents.size})",
                "اقتراحات (${suggestions.size})",
                "التنفيذ (${executionTrace.size})",
                "التدقيق (${auditEvents.size})"
            )
            items(filters.size) { index ->
                FilterChip(
                    selected = filterIndex == index,
                    onClick = { filterIndex = index },
                    label = { Text(filters[index], style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("activity_filter_$index")
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (suggestions.isEmpty() && executionTrace.isEmpty() && auditEvents.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.NotificationsActive,
                        title = "لا نشاط في مساحة العمل بعد",
                        hint = "يظهر هنا: اقتراحات استباقية من محرك السياق، آثار التنفيذ الحي، وأحداث التدقيق الأمني.",
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
            }

            // ===================== Suggestions =====================
            if (filterIndex == 0 || filterIndex == 1) {
                if (suggestions.isNotEmpty()) {
                    item {
                        SectionHeader(
                            icon = Icons.Default.NotificationsActive,
                            title = "اقتراحات استباقية",
                            subtitle = "مشتقة من محرك سياق مساحة العمل — اضغط للمعالجة"
                        )
                    }
                    items(suggestions, key = { it.id }) { suggestion ->
                        SuggestionCard(
                            title = suggestion.titleAr,
                            description = suggestion.descriptionAr,
                            kindIcon = suggestionKindIcon(suggestion.kind.name),
                            severityTint = severityTint(suggestion.severity.name),
                            severityText = severityText(suggestion.severity.name),
                            timeText = timeFormat.format(Date(suggestion.createdAtEpochMs)),
                            actionText = suggestion.recommendedAction ?: "معالجة",
                            onAction = { onNavigate(suggestionRoute(suggestion.kind.name)) },
                            tag = "suggestion_${suggestion.id}"
                        )
                    }
                }
            }

            // ===================== Execution trace =====================
            if (filterIndex == 0 || filterIndex == 2) {
                if (executionTrace.isNotEmpty()) {
                    item {
                        SectionHeader(
                            icon = Icons.Default.Timeline,
                            title = "آثار التنفيذ (Execution Trace)",
                            subtitle = "خطوات حقيقية من جدول التتبع الدائم"
                        )
                    }
                    items(executionTrace, key = { "${it.executionId}_${it.stepIndex}" }) { node ->
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
                                val (icon, tint) = when (node.outcome) {
                                    "SUCCESS", "COMPLETED" -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.tertiary
                                    "DEGRADED" -> Icons.Default.WarningAmber to MaterialTheme.colorScheme.secondary
                                    "FAILED", "ERROR" -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
                                    else -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "خطوة ${node.stepIndex + 1}: ${node.actionType}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        node.durationMs?.let {
                                            Text(
                                                text = "${it}ms",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                    Text(
                                        text = node.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                    node.observationSummary?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ===================== Audit events =====================
            if (filterIndex == 0 || filterIndex == 3) {
                if (auditEvents.isNotEmpty()) {
                    item {
                        SectionHeader(
                            icon = Icons.Default.Gavel,
                            title = "أحداث التدقيق (آخر ${auditEvents.size})",
                            subtitle = "قرارات ALLOW / DENY من حراس الأمن والسياسات"
                        )
                    }
                    items(auditEvents, key = { it.id }) { event ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Gavel,
                                    contentDescription = null,
                                    tint = if (event.decision.equals("ALLOW", ignoreCase = true))
                                        MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = event.action,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        StatusBadge(
                                            text = event.decision,
                                            tint = if (event.decision.equals("ALLOW", ignoreCase = true))
                                                MaterialTheme.colorScheme.tertiary
                                            else MaterialTheme.colorScheme.error
                                        )
                                    }
                                    Text(
                                        text = "${event.resourceType}/${event.resourceId}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                    if (event.reason.isNotBlank()) {
                                        Text(
                                            text = event.reason,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 2
                                        )
                                    }
                                    Text(
                                        text = timeFormat.format(Date(event.occurredAtEpochMs)) +
                                            " • ${event.actor}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Mapping helpers
// ---------------------------------------------------------------------------

private fun suggestionKindIcon(kind: String): ImageVector = when (kind) {
    "UNUSED_RESOURCE", "STALE_RESOURCE" -> Icons.Default.Bolt
    "FAILED_EXECUTION" -> Icons.Default.ErrorOutline
    "HIGH_LATENCY" -> Icons.Default.Schedule
    "APPROACHING_BUDGET" -> Icons.Default.Paid
    "CONFIGURATION_GAP" -> Icons.Default.AccountTree
    "KNOWLEDGE_GAP" -> Icons.Default.Info
    "AGENT_AVAILABILITY" -> Icons.Default.NotificationsActive
    "SECURITY_REMINDER" -> Icons.Default.Gavel
    else -> Icons.Default.Info
}

@Composable
private fun severityTint(severity: String) = when (severity) {
    "CRITICAL" -> MaterialTheme.colorScheme.error
    "ACTION_REQUIRED" -> MaterialTheme.colorScheme.secondary
    "WARN" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

private fun severityText(severity: String): String = when (severity) {
    "CRITICAL" -> "حرج"
    "ACTION_REQUIRED" -> "يتطلب إجراءً"
    "WARN" -> "تحذير"
    else -> "معلومة"
}

private fun suggestionRoute(kind: String): String = when (kind) {
    "UNUSED_RESOURCE", "STALE_RESOURCE", "CONFIGURATION_GAP" -> WorkspaceRoutes.PROVIDERS
    "FAILED_EXECUTION", "AGENT_AVAILABILITY" -> WorkspaceRoutes.STUDIO
    "APPROACHING_BUDGET" -> WorkspaceRoutes.GOVERNANCE
    "KNOWLEDGE_GAP" -> WorkspaceRoutes.KNOWLEDGE
    "SECURITY_REMINDER" -> WorkspaceRoutes.GOVERNANCE
    "HIGH_LATENCY" -> WorkspaceRoutes.PROVIDERS
    else -> WorkspaceRoutes.MORE
}

@Composable
private fun SuggestionCard(
    title: String,
    description: String,
    kindIcon: ImageVector,
    severityTint: androidx.compose.ui.graphics.Color,
    severityText: String,
    timeText: String,
    actionText: String,
    onAction: () -> Unit,
    tag: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag(tag),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = severityTint.copy(alpha = 0.10f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                kindIcon,
                contentDescription = null,
                tint = severityTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    StatusBadge(text = severityText, tint = severityTint)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            IconButton(onClick = onAction, modifier = Modifier.testTag("${tag}_action")) {
                Icon(
                    Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = actionText,
                    tint = severityTint
                )
            }
        }
    }
}
