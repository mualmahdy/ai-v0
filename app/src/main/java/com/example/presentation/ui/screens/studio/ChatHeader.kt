package com.example.presentation.ui.screens.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.session.ChatMode
import com.example.domain.core.task.AutonomyPolicy

/**
 * ============================================================================
 * ChatHeader — the compact conversation header (Chat Workspace Task 1 §6,
 * UI POLISH §6 — identity + context + IMPORTANT STATE only)
 * ============================================================================
 *
 * A SMALL header that carries only the conversation's context: the session
 * title, the current project, ONE compact context chip (mode + model, or the
 * agent — never duplicated in multiple cards), ONE policy summary chip (the
 * scopes stay SEPARATE: network ≠ autonomy), the session actions, and the
 * live EXECUTION STATE while the assistant works (§6 "important states" —
 * real phases only, never engine internals or debug identifiers). All deeper
 * controls live behind the chips (sheets), keeping the timeline the visual
 * core of the screen.
 */
@Composable
fun ChatHeader(
    sessionTitle: String?,
    projectName: String?,
    chatMode: ChatMode,
    selectedModelDisplayName: String?,
    activeAgentName: String?,
    networkPolicy: NetworkPolicy,
    autonomyPolicy: AutonomyPolicy,
    onOpenContext: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewSession: () -> Unit,
    /**
     * §19 (Task 2): the sessions browse button exists ONLY at compact width
     * (medium+ show the sessions PANE — the same control is never duplicated).
     * Null = the button is not composed at all.
     */
    onOpenSessions: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * UI POLISH §6: the live execution indicator — composed only while an
     * execution is running (the honest phase label; null = idle/hidden).
     */
    isExecuting: Boolean = false,
    executionPhaseLabel: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("chat_header")
    ) {
        // ---- Row 1: identity + session actions ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sessionTitle ?: "محادثة جديدة",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            onOpenSessions?.let { openSessions ->
                IconButton(onClick = openSessions, modifier = Modifier.testTag("btn_open_sessions")) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = "تصفح الجلسات الدائمة",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onNewSession, modifier = Modifier.testTag("btn_new_session")) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "جلسة جديدة",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // ---- Row 2: the two context chips (compact, one line each scope) ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContextChip(
                icon = if (chatMode == ChatMode.AGENT) Icons.Default.Psychology else Icons.Default.AutoAwesome,
                text = contextChipLabel(chatMode, selectedModelDisplayName, activeAgentName),
                onClick = onOpenContext,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag("chip_conversation_context")
            )
            Spacer(modifier = Modifier.width(8.dp))
            ContextChip(
                icon = Icons.Default.Tune,
                text = policySummaryLabel(networkPolicy, autonomyPolicy),
                onClick = onOpenSettings,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag("chip_advanced_summary")
            )
        }

        // ---- UI POLISH §6: the live EXECUTION STATE (the header's one
        // "important state") — a compact, honest indicator while the
        // assistant works: spinner + the real phase label. Hidden when idle.
        if (isExecuting && executionPhaseLabel != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .testTag("header_execution_indicator")
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = executionPhaseLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * §7: the compact mode/model/agent selection summary — "الوكيل: X" or
 * "محادثة سريعة • النموذج: Y" (exact model when pinned, honest AUTO label
 * when the decision layer picks). In AGENT mode the model stays an internal
 * binding of the agent's execution — showing the AGENT summary keeps state
 * and UI consistent instead of hiding the whole context.
 */
internal fun contextChipLabel(
    chatMode: ChatMode,
    selectedModelDisplayName: String?,
    activeAgentName: String?
): String = when (chatMode) {
    ChatMode.QUICK_CHAT ->
        "محادثة سريعة • النموذج: ${selectedModelDisplayName ?: "تلقائي"}"
    ChatMode.AGENT ->
        "الوكيل: ${activeAgentName ?: "غير محدد"}"
}

/**
 * §6: the advanced-controls summary — the network and autonomy scopes stay
 * SEPARATE labels (the backend treats them as different inputs; the summary
 * must not merge them into one policy-looking value).
 */
internal fun policySummaryLabel(networkPolicy: NetworkPolicy, autonomyPolicy: AutonomyPolicy): String {
    val network = networkPolicy.displayName.substringBefore(" (")
    val autonomy = autonomyPolicy.displayName.substringBefore(" (")
    return "$network • $autonomy"
}

/** A compact one-line context chip that opens its owning sheet. */
@Composable
private fun ContextChip(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
