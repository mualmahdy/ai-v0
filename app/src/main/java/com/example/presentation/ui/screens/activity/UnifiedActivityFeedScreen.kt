package com.example.presentation.ui.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.AuditSeverity
import com.example.domain.core.observability.ExecutionTraceNode
import com.example.domain.core.workspace.context.ProactiveSuggestion
import com.example.domain.core.workspace.context.SuggestionSeverity
import com.example.domain.core.workspace.context.WorkspaceEvent
import com.example.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * UnifiedActivityFeedScreen — Phase 5 Smart Workspace UI (P1)
 * ============================================================================
 *
 * Closes the Smart Workspace UI gap (audit: 40–45% → ~55%) by adding a
 * UNIFIED ACTIVITY SURFACE — a single timeline showing what the agent
 * is doing, what it has done, and what it suggests — instead of the
 * previous navigation-centric layout where each screen was siloed.
 *
 * The screen renders three sections in a single vertical feed:
 *
 *   1. Proactive Suggestions — actionable cards (unused resource,
 *      long-running task, approaching budget, etc.) emitted by
 *      `WorkspaceContextEngine`.
 *
 *   2. Execution Trace — the structured decision→action→observation
 *      chain for the active execution, pulled from the durable
 *      `execution_trace_nodes` table (previously only the in-memory
 *      stream was shown).
 *
 *   3. Recent Audit Events — security decisions, tool calls, and
 *      lifecycle transitions, pulled from the durable `audit_trail`
 *      table.
 */
@Composable
fun UnifiedActivityFeedScreen(viewModel: MainViewModel) {
    val suggestions by viewModel.activeSuggestions.collectAsState()
    val traceNodes by viewModel.activeExecutionTrace.collectAsState()
    val auditEvents by viewModel.recentAuditEvents.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // --- Section 1: Proactive Suggestions ---
        item {
            SectionHeader(title = "اقتراحات استباقية", count = suggestions.size)
        }
        if (suggestions.isEmpty()) {
            item { EmptyState("لا توجد اقتراحات حالياً — النظام يعمل ضمن الحدود الطبيعية.") }
        } else {
            items(suggestions) { suggestion ->
                ProactiveSuggestionCard(suggestion)
            }
        }

        // --- Section 2: Execution Trace ---
        item {
            Spacer(modifier = Modifier.height(8.dp))
            SectionHeader(title = "سلسلة التنفيذ النشطة", count = traceNodes.size)
        }
        if (traceNodes.isEmpty()) {
            item { EmptyState("لا يوجد تنفيذ نشط حالياً.") }
        } else {
            items(traceNodes) { node ->
                TraceNodeRow(node)
            }
        }

        // --- Section 3: Audit Events ---
        item {
            Spacer(modifier = Modifier.height(8.dp))
            SectionHeader(title = "أحداث التدقيق الأخيرة", count = auditEvents.size)
        }
        if (auditEvents.isEmpty()) {
            item { EmptyState("لا توجد أحداث تدقيق بعد.") }
        } else {
            items(auditEvents) { event ->
                AuditEventRow(event)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun ProactiveSuggestionCard(suggestion: ProactiveSuggestion) {
    val (icon, color) = suggestionVisual(suggestion.severity)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = suggestion.titleAr,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = suggestion.descriptionAr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (suggestion.recommendedAction != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = color.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "إجراء مقترح: ${suggestion.recommendedAction}",
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceNodeRow(node: ExecutionTraceNode) {
    val (icon, color) = traceVisual(node.outcome)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "خطوة ${if (node.stepIndex == Int.MAX_VALUE) "نهائية" else node.stepIndex.toString()}: ${node.actionType}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (node.durationMs != null) {
                    Text(
                        text = "${node.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = node.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (node.observationSummary != null) {
                Text(
                    text = "ملاحظة: ${node.observationSummary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
    Divider(modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun AuditEventRow(event: AuditEvent) {
    val (icon, color) = auditVisual(event.severity)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.action,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatTime(event.occurredAtEpochMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${event.resourceType}:${event.resourceId} — ${event.decision}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = event.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun suggestionVisual(severity: SuggestionSeverity): Pair<ImageVector, Color> = when (severity) {
    SuggestionSeverity.INFO -> Icons.Filled.Info to Color(0xFF2196F3)
    SuggestionSeverity.WARN -> Icons.Filled.Warning to Color(0xFFFF9800)
    SuggestionSeverity.ACTION_REQUIRED -> Icons.Filled.Lightbulb to Color(0xFFFFC107)
    SuggestionSeverity.CRITICAL -> Icons.Filled.Error to Color(0xFFE53935)
}

private fun traceVisual(outcome: String): Pair<ImageVector, Color> = when {
    outcome == "SUCCESS" -> Icons.Filled.CheckCircle to Color(0xFF4CAF50)
    outcome == "FAILURE" -> Icons.Filled.Error to Color(0xFFE53935)
    outcome == "DEGRADED" -> Icons.Filled.Warning to Color(0xFFFF9800)
    outcome == "CANCELLED" -> Icons.Filled.Stop to Color(0xFF9E9E9E)
    outcome == "STARTED" -> Icons.Filled.PlayArrow to Color(0xFF2196F3)
    outcome == "DECISION" -> Icons.Filled.AutoAwesome to Color(0xFF9C27B0)
    outcome == "REPLANNED" -> Icons.Filled.Bolt to Color(0xFFFF9800)
    else -> Icons.Filled.Info to Color(0xFF607D8B)
}

private fun auditVisual(severity: AuditSeverity): Pair<ImageVector, Color> = when (severity) {
    AuditSeverity.INFO -> Icons.Filled.Info to Color(0xFF2196F3)
    AuditSeverity.WARN -> Icons.Filled.Warning to Color(0xFFFF9800)
    AuditSeverity.ERROR -> Icons.Filled.Error to Color(0xFFE53935)
    AuditSeverity.CRITICAL -> Icons.Filled.Error to Color(0xFFB71C1C)
}

private fun formatTime(epochMs: Long): String {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(epochMs))
}
