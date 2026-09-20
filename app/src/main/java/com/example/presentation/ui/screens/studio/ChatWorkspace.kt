package com.example.presentation.ui.screens.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceType
import com.example.presentation.ui.navigation.WorkspaceRoutes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * ChatWorkspace — the conversation-first Studio surface (Chat Workspace
 * Task 1)
 * ============================================================================
 *
 * The screen is a CONVERSATION now, in this order:
 *
 *   ChatHeader → ConversationTimeline (User → ExecutionLifecycle →
 *   Assistant) → ChatComposer
 *
 * No engine cards above the transcript: the mode/model/agent selection and
 * the advanced policy controls live behind the header's two compact chips
 * (sheets), and the execution lifecycle is part of the message stream — a
 * projection of the REAL kernel events, never raw telemetry.
 */
@Composable
fun ChatWorkspace(
    state: com.example.presentation.viewmodel.StudioViewModel.StudioUiState,
    shellAutonomyPolicy: com.example.domain.core.task.AutonomyPolicy,
    projectName: String?,
    activeSessionTitle: String?,
    sessions: List<com.example.domain.core.session.ConversationSession>,
    llmResources: List<com.example.domain.core.resource.ResourceRecord>,
    agents: List<com.example.domain.core.agent.AgentDefinition>,
    activeAgent: com.example.domain.core.agent.AgentDefinition?,
    isSessionBrowserOpen: Boolean,
    onNavigate: (String) -> Unit,
    onPromptInput: (String) -> Unit,
    onSend: () -> Unit,
    onCancelExecution: () -> Unit,
    onRegenerate: () -> Unit,
    onEditMessage: (String) -> Unit,
    onResetView: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onSessionBrowserOpen: (Boolean) -> Unit,
    onModeChange: (com.example.domain.core.session.ChatMode) -> Unit,
    onSelectModel: (String?, String?) -> Unit,
    onSelectAgent: (com.example.domain.core.agent.AgentDefinition) -> Unit,
    onNetworkPolicy: (com.example.domain.core.network.NetworkPolicy) -> Unit,
    onAutonomyPolicy: (com.example.domain.core.task.AutonomyPolicy) -> Unit,
    onCreateAgent: (String, com.example.domain.core.agent.AgentRole, String, String, Set<com.example.domain.core.capability.CapabilityType>) -> Unit,
    onDeleteAgent: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    var contextSheetOpen by rememberSaveable { mutableStateOf(false) }
    var settingsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var agentBuilderOpen by rememberSaveable { mutableStateOf(false) }
    var deleteAgentTarget by rememberSaveable { mutableStateOf<String?>(null) }

    // §9: the intentional post-send scroll signal (a plain counter).
    var sendSignal by remember { mutableIntStateOf(0) }

    val hasActiveLlm = llmResources.any {
        it.resourceType == ResourceType.LLM && it.lifecycleState == ResourceLifecycleState.ENABLED
    }

    Column(modifier = modifier.testTag("agent_studio_screen")) {
        // ---- 1. The compact conversation header ----
        ChatHeader(
            sessionTitle = activeSessionTitle,
            projectName = projectName,
            chatMode = state.chatMode,
            selectedModelDisplayName = state.selectedModelDisplayName,
            activeAgentName = activeAgent?.identity?.name,
            networkPolicy = state.networkPolicy,
            autonomyPolicy = shellAutonomyPolicy,
            onOpenContext = { contextSheetOpen = true },
            onOpenSettings = { settingsSheetOpen = true },
            onNewSession = onNewSession,
            onOpenSessions = { onSessionBrowserOpen(true) }
        )

        // ---- 2. The conversation timeline (the hero of the screen) ----
        ConversationTimeline(
            timeline = state.timeline,
            liveExecution = state.liveExecution,
            streamText = state.streamText,
            sendSignal = sendSignal,
            emptyContent = {
                if (!hasActiveLlm) {
                    ConnectLlmBanner(onNavigate = { onNavigate(WorkspaceRoutes.PROVIDERS) })
                }
                ChatEmptyState(
                    hasSessions = sessions.isNotEmpty(),
                    onStarterPrompt = { onPromptInput(it) },
                    onResumeSessions = { onSessionBrowserOpen(true) }
                )
            },
            onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) },
            onEdit = onEditMessage,
            onRegenerate = onRegenerate,
            onRetry = onRegenerate
        )

        // ---- 3. The composer (owns the screen's single IME inset) ----
        ChatComposer(
            value = state.promptInput,
            isExecuting = state.isExecuting,
            onValueChange = onPromptInput,
            onSend = {
                sendSignal++
                onSend()
            },
            onCancel = onCancelExecution,
            onClearDraft = { onPromptInput("") }
        )
    }

    // ---- Secondary surfaces (§6: nothing stacks above the transcript) ----
    if (contextSheetOpen) {
        ChatContextSheet(
            chatMode = state.chatMode,
            onModeChange = onModeChange,
            llmResources = llmResources.filter {
                it.resourceType == ResourceType.LLM &&
                    (it.lifecycleState == ResourceLifecycleState.ENABLED ||
                        it.lifecycleState == ResourceLifecycleState.ACTIVE)
            },
            selectedModelResourceId = state.selectedModelResourceId,
            selectedModelDisplayName = state.selectedModelDisplayName,
            onSelectModel = onSelectModel,
            agents = agents,
            activeAgentId = activeAgent?.identity?.id?.value,
            onSelectAgent = onSelectAgent,
            onDeleteAgentRequest = { deleteAgentTarget = it },
            onBuildAgent = { agentBuilderOpen = true },
            onDismiss = { contextSheetOpen = false }
        )
    }

    if (settingsSheetOpen) {
        ChatSettingsSheet(
            networkPolicy = state.networkPolicy,
            autonomyPolicy = shellAutonomyPolicy,
            consumedTokens = state.currentTokensConsumed,
            remainingBudget = state.remainingBudget,
            totalSession = state.sessionTotalTokens,
            onNetworkPolicy = onNetworkPolicy,
            onAutonomyPolicy = onAutonomyPolicy,
            onNewSession = onNewSession,
            onResetView = {
                // §8: honest semantics — view reset only, no durable claim.
                settingsSheetOpen = false
                onResetView()
            },
            onDismiss = { settingsSheetOpen = false }
        )
    }

    if (isSessionBrowserOpen) {
        SessionBrowserDialog(
            sessions = sessions,
            activeSessionId = state.activeSessionId,
            onOpen = { sessionId ->
                onOpenSession(sessionId)
                onSessionBrowserOpen(false)
            },
            onDelete = onDeleteSession,
            onNewSession = {
                onNewSession()
                onSessionBrowserOpen(false)
            },
            onDismiss = { onSessionBrowserOpen(false) }
        )
    }

    if (agentBuilderOpen) {
        AgentBuilderDialog(
            onConfirm = { name, role, description, systemPrompt, capabilities ->
                onCreateAgent(name, role, description, systemPrompt, capabilities)
                agentBuilderOpen = false
            },
            onDismiss = { agentBuilderOpen = false }
        )
    }

    deleteAgentTarget?.let { targetId ->
        val agentName = agents.firstOrNull {
            it.identity.id.value == targetId
        }?.identity?.name ?: ""
        com.example.presentation.ui.components.ConfirmDialog(
            title = "حذف الوكيل",
            message = "سيُحذف الوكيل «$agentName» من السجل الدائم نهائياً. هل تريد المتابعة؟",
            confirmLabel = "حذف",
            onConfirm = {
                onDeleteAgent(targetId)
                deleteAgentTarget = null
            },
            onDismiss = { deleteAgentTarget = null }
        )
    }
}

/**
 * First-run guidance (carried over from the old surface — task-oriented
 * help, not engine internals): connect an LLM provider to activate
 * conversations.
 */
@Composable
private fun ConnectLlmBanner(onNavigate: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("studio_connect_llm_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "لا يوجد ذكاء نشط في مساحة العمل",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "اربط مزوّد LLM (Gemini / OpenAI / Ollama المحلي…) لتفعيل المحادثة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onNavigate) {
                Text("ربط الآن")
            }
        }
    }
}

/**
 * DURABLE SESSION BROWSER (report gaps: "Session browser / Session retrieval
 * after restart / Resume conversation"): lists the workspace sessions with
 * turn/token aggregates; open resumes the conversation with its full
 * history. (Carried over verbatim from the old Studio surface.)
 */
@Composable
private fun SessionBrowserDialog(
    sessions: List<com.example.domain.core.session.ConversationSession>,
    activeSessionId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNewSession: () -> Unit,
    onDismiss: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm • dd/MM", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("جلسات المحادثة الدائمة") },
        text = {
            if (sessions.isEmpty()) {
                Text(
                    "لا جلسات محفوظة بعد — أرسل أول رسالة لتُنشأ جلسة دائمة تلقائياً.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sessions, key = { it.id.value }) { session ->
                        val isActive = session.id.value == activeSessionId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(session.id.value) }
                                .testTag("session_item_${session.id.value}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(10.dp)
                            ) {
                                Icon(
                                    if (session.mode == com.example.domain.core.session.ChatMode.QUICK_CHAT) {
                                        Icons.Default.PlayArrow
                                    } else {
                                        Icons.Default.Psychology
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = session.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = buildString {
                                            append(session.turnCount)
                                            append(" دورة • ")
                                            append(session.totalTokensConsumed)
                                            append(" توكن • ")
                                            append(timeFormat.format(Date(session.lastActiveAtEpochMs)))
                                            session.modelDisplayName?.let { append(" • $it") }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onDelete(session.id.value) }) {
                                    Icon(
                                        Icons.Default.DeleteSweep,
                                        contentDescription = "حذف الجلسة",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNewSession) { Text("جلسة جديدة") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}
