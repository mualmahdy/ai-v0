package com.example.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.core.events.ExecutionEvent

@Composable
fun DiagnosticBanner(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    isDegraded: Boolean = true
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("diagnostic_banner"),
        shape = RoundedCornerShape(12.dp),
        color = if (isDegraded) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isDegraded) Icons.Default.WarningAmber else Icons.Default.ErrorOutline,
                contentDescription = "Diagnostic Alert",
                tint = if (isDegraded) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isDegraded) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDegraded) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun TokenBudgetGauge(
    consumedTokens: Int,
    remainingBudget: Int,
    totalSession: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("token_budget_gauge"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = "Token Usage",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "ميزانية الرموز (Token Budget)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "المتبقي: $remainingBudget توكن",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$consumedTokens Tkn",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "إجمالي الجلسة: $totalSession",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun ExecutionEventTimelineItem(
    event: ExecutionEvent,
    modifier: Modifier = Modifier
) {
    val (icon: ImageVector, iconTint: Color, title: String, subtitle: String) = when (event) {
        is ExecutionEvent.DecisionMade -> {
            Quadruple(
                Icons.Default.Psychology,
                MaterialTheme.colorScheme.tertiary,
                "قرار المحرك الذكي: ${event.decision.chosenAction.type.displayName}",
                "${event.decision.rationale.take(60)} (ثقة: ${"%.0f".format(event.decision.confidence * 100)}%)"
            )
        }
        is ExecutionEvent.ObservationRecorded -> {
            Quadruple(
                Icons.Default.CheckCircle,
                Color(0xFF00897B),
                "تغذية راجعة للمحرك (Observation)",
                "زمن: ${event.observation.actualLatencyMs}ms | توكنز: ${event.observation.tokensConsumed} | عدم يقين: ${"%.2f".format(event.updatedUncertainty)}"
            )
        }
        is ExecutionEvent.Started -> {
            Quadruple(
                Icons.Default.Psychology,
                MaterialTheme.colorScheme.primary,
                "بدء مهمة الوكيل (${event.agentId.value})",
                "المعرف: ${event.executionId.take(8)}... | النموذج: ${event.modelId}"
            )
        }
        is ExecutionEvent.ToolRequested -> {
            Quadruple(
                Icons.Default.DataObject,
                MaterialTheme.colorScheme.primary,
                "طلب استدعاء أداة: ${event.toolName}",
                event.argumentsJson.take(60)
            )
        }
        is ExecutionEvent.ToolResult -> {
            Quadruple(
                Icons.Default.CheckCircle,
                Color(0xFF2E7D32),
                "نتيجة الأداة: ${event.toolName}",
                when (event.outcome) {
                    is com.example.domain.core.Outcome.Success -> "تمت بنجاح: ${event.outcome.value.take(40)}"
                    is com.example.domain.core.Outcome.Degraded -> "تمت بنمط تراجع: ${event.outcome.diagnosticMessage}"
                    is com.example.domain.core.Outcome.Error -> "فشل: ${event.outcome.diagnosticMessage}"
                }
            )
        }
        is ExecutionEvent.Degraded -> {
            Quadruple(
                Icons.Default.WarningAmber,
                MaterialTheme.colorScheme.tertiary,
                "تراجع تشغيلي متحكم به",
                event.message
            )
        }
        is ExecutionEvent.Completed -> {
            Quadruple(
                Icons.Default.CheckCircle,
                Color(0xFF1B5E20),
                "اكتملت المهمة",
                "في ${event.totalDurationMs}ms"
            )
        }
        is ExecutionEvent.Error -> {
            Quadruple(
                Icons.Default.ErrorOutline,
                MaterialTheme.colorScheme.error,
                "خطأ في التنفيذ (${event.failureCode})",
                event.message
            )
        }
        is ExecutionEvent.ContentChunk -> {
            Quadruple(
                Icons.Default.Info,
                MaterialTheme.colorScheme.outline,
                "تدفق الإجابة",
                event.deltaText.take(40)
            )
        }
        is ExecutionEvent.UsageBudgetUpdate -> {
            Quadruple(
                Icons.Default.Bolt,
                MaterialTheme.colorScheme.primary,
                "تحديث استهلاك التوكنز",
                "الاستهلاك: ${event.promptTokens + event.completionTokens} | المتبقي: ${event.remainingBudgetTokens}"
            )
        }
        is ExecutionEvent.Cancelled -> {
            Quadruple(
                Icons.Default.Cancel,
                MaterialTheme.colorScheme.outline,
                "إلغاء المهمة",
                event.reason
            )
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
