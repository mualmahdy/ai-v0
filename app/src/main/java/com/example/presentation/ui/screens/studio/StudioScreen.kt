package com.example.presentation.ui.screens.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.application.agent.CanonicalAgentCatalog
import com.example.domain.core.session.ChatMode
import com.example.domain.core.session.ConversationSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.task.AutonomyPolicy
import com.example.presentation.state.StudioTurn
import com.example.presentation.state.UiState
import com.example.presentation.ui.components.ExecutionEventTimelineItem
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.ui.components.TokenBudgetGauge
import com.example.presentation.ui.navigation.WorkspaceRoutes
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * StudioScreen — the conversation console (real chat experience)
 * ============================================================================
 *
 * The Studio is no longer a one-shot prompt box: it is a session transcript
 * (user turns + agent answers + live streaming), with runtime policy
 * controls (network + autonomy — real decision-engine inputs), a durable
 * agent catalog (create/delete), per-turn metadata (tokens, duration,
 * events) and copy-to-clipboard. Every element maps to a real backend
 * capability of the closed-loop agentic execution engine.
 */
@Composable
fun StudioScreen(
    viewModel: MainViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    var agentBuilderOpen by rememberSaveable { mutableStateOf(false) }
    var deleteAgentTarget by rememberSaveable { mutableStateOf<String?>(null) }

    // Auto-scroll the transcript as turns and stream chunks arrive.
    val lastTurnCount = state.studioSession.size
    val streamLength = state.streamText.length
    LaunchedEffect(lastTurnCount, streamLength, state.isExecuting) {
        if (lastTurnCount > 0 || streamLength > 0) {
            runCatching { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) }
        }
    }

    val hasActiveLlm = state.materializedResources.any {
        it.resourceType == ResourceType.LLM && it.lifecycleState == ResourceLifecycleState.ENABLED
    }

    Column(modifier = modifier.testTag("agent_studio_screen")) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ---- Connect-LLM guidance (first-run golden path) ----
            if (!hasActiveLlm) {
                item {
                    ConnectLlmBanner(onNavigate = { onNavigate(WorkspaceRoutes.PROVIDERS) })
                }
            }

            // ---- Conversation mode + durable session browser ----
            // (report gap-closure: Quick Chat is a REAL agent-independent
            // mode; sessions are durable, browsable and resumable)
            item {
                ChatModeAndSessionsBar(
                    mode = state.chatMode,
                    onModeChange = viewModel::setChatMode,
                    onOpenSessions = { viewModel.setSessionBrowserOpen(true) },
                    onNewSession = viewModel::startNewSession,
                    activeSessionTitle = state.sessions
                        .firstOrNull { it.id.value == state.activeSessionId }?.title
                )
            }

            // ---- Runtime policy controls (decision-engine inputs) ----
            item {
                PolicyControlBar(
                    networkPolicy = state.networkPolicy,
                    autonomyPolicy = state.autonomyPolicy,
                    onNetworkPolicy = viewModel::setNetworkPolicy,
                    onAutonomyPolicy = viewModel::setAutonomyPolicy
                )
            }

            // ---- Model picker (QUICK_CHAT) / Agent catalog (AGENT) ----
            when (state.chatMode) {
                ChatMode.QUICK_CHAT -> item {
                    ModelPickerRow(
                        resources = state.materializedResources.filter {
                            it.resourceType == ResourceType.LLM &&
                                (it.lifecycleState == ResourceLifecycleState.ENABLED ||
                                    it.lifecycleState == ResourceLifecycleState.ACTIVE)
                        },
                        selectedResourceId = state.selectedModelResourceId,
                        onSelect = viewModel::selectModel
                    )
                }

                ChatMode.AGENT -> {
                    item {
                        AgentCatalogRow(
                            agents = state.availableAgents,
                            activeAgentId = state.activeAgent?.identity?.id?.value,
                            onSelect = viewModel::selectAgent,
                            onDeleteRequest = { deleteAgentTarget = it },
                            onBuildAgent = { agentBuilderOpen = true }
                        )
                    }

                    // ---- Active agent card ----
                    state.activeAgent?.let { agent ->
                        item { ActiveAgentCard(agent) }
                    }
                }
            }

            // ---- Token budget (live, honest) ----
            item {
                TokenBudgetGauge(
                    consumedTokens = state.currentTokensConsumed,
                    remainingBudget = state.remainingBudget,
                    totalSession = state.sessionTotalTokens
                )
            }

            // ---- Session transcript ----
            if (state.studioSession.isEmpty() && !state.isExecuting) {
                item {
                    SessionIntroCard(agentName = state.activeAgent?.identity?.name ?: "الوكيل")
                }
            } else {
                items(state.studioSession, key = { it.id }) { turn ->
                    TurnBubble(
                        turn = turn,
                        onCopy = { clipboard.setText(AnnotatedString(turn.answer)) }
                    )
                }

                // ---- Live execution turn ----
                if (state.isExecuting || state.streamText.isNotBlank()) {
                    item {
                        LiveExecutionCard(
                            streamText = state.streamText,
                            isExecuting = state.isExecuting,
                            events = state.executionLog.takeLast(6),
                            onCopy = { clipboard.setText(AnnotatedString(state.streamText)) }
                        )
                    }
                }
            }
        }

        // ---- Prompt composer ----
        PromptComposer(
            value = state.promptInput,
            isExecuting = state.isExecuting,
            onValueChange = viewModel::updatePromptInput,
            onExecute = viewModel::executePrompt,
            onCancel = viewModel::cancelExecution,
            onClearSession = viewModel::clearStudioSession,
            hasSession = state.studioSession.isNotEmpty()
        )
    }

    // ---- Durable session browser ----
    if (state.isSessionBrowserOpen) {
        SessionBrowserDialog(
            sessions = state.sessions,
            activeSessionId = state.activeSessionId,
            onOpen = viewModel::openSession,
            onDelete = viewModel::deleteSession,
            onNewSession = viewModel::startNewSession,
            onDismiss = { viewModel.setSessionBrowserOpen(false) }
        )
    }

    // ---- Agent builder dialog ----
    if (agentBuilderOpen) {
        AgentBuilderDialog(
            onConfirm = { name, role, description, systemPrompt, capabilities ->
                viewModel.createAgent(
                    name = name,
                    role = role,
                    description = description,
                    systemPrompt = systemPrompt,
                    // §23 FIX (audit 2026 — Agent Builder capabilities were
                    // hard-coded to LLM_GENERATION+STREAMING): the user now
                    // AUTHORS the agent's execution capabilities here (the
                    // contract surface), instead of an implicit cap.
                    capabilities = capabilities
                )
                agentBuilderOpen = false
            },
            onDismiss = { agentBuilderOpen = false }
        )
    }

    // ---- Delete-agent confirmation ----
    deleteAgentTarget?.let { targetId ->
        val agentName = state.availableAgents.firstOrNull {
            it.identity.id.value == targetId
        }?.identity?.name ?: ""
        com.example.presentation.ui.components.ConfirmDialog(
            title = "حذف الوكيل",
            message = "سيُحذف الوكيل «$agentName» من السجل الدائم نهائياً. هل تريد المتابعة؟",
            confirmLabel = "حذف",
            onConfirm = {
                viewModel.deleteAgent(targetId)
                deleteAgentTarget = null
            },
            onDismiss = { deleteAgentTarget = null }
        )
    }
}

// ---------------------------------------------------------------------------
// Sub-composables
// ---------------------------------------------------------------------------

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
                    text = "اربط مزوّد LLM (Gemini / OpenAI / Ollama المحلي…) لتفعيل حلقة التنفيذ الذاتية.",
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

@Composable
private fun PolicyControlBar(
    networkPolicy: NetworkPolicy,
    autonomyPolicy: AutonomyPolicy,
    onNetworkPolicy: (NetworkPolicy) -> Unit,
    onAutonomyPolicy: (AutonomyPolicy) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PolicyDropdown(
            icon = Icons.Default.Language,
            label = "الشبكة: ${networkPolicy.displayName.substringBefore(" (")}",
            options = NetworkPolicy.entries.map { it.displayName to it },
            selected = networkPolicy,
            onSelect = onNetworkPolicy,
            modifier = Modifier.weight(1f),
            tag = "policy_network"
        )
        PolicyDropdown(
            icon = Icons.Default.Security,
            label = "الاستقلالية: ${autonomyPolicy.displayName.substringBefore(" (")}",
            options = AutonomyPolicy.entries.map { it.displayName to it },
            selected = autonomyPolicy,
            onSelect = onAutonomyPolicy,
            modifier = Modifier.weight(1f),
            tag = "policy_autonomy"
        )
    }
}

@Composable
private fun <T> PolicyDropdown(
    icon: ImageVector,
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tag: String
) {
    var open by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { open = true }
            .testTag(tag),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (name, value) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            name,
                            fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        onSelect(value)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AgentCatalogRow(
    agents: List<AgentDefinition>,
    activeAgentId: String?,
    onSelect: (AgentDefinition) -> Unit,
    onDeleteRequest: (String) -> Unit,
    onBuildAgent: () -> Unit
) {
    val canonicalIds = remember {
        CanonicalAgentCatalog.defaults.map { it.identity.id.value }.toSet()
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "كتالوج الوكلاء (${agents.size})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onBuildAgent, modifier = Modifier.testTag("btn_open_agent_builder")) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("بناء وكيل")
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(agents, key = { it.identity.id.value }) { agent ->
                AgentChip(
                    agent = agent,
                    isSelected = agent.identity.id.value == activeAgentId,
                    isDeletable = agent.identity.id.value !in canonicalIds,
                    onSelect = { onSelect(agent) },
                    onDelete = { onDeleteRequest(agent.identity.id.value) }
                )
            }
        }
    }
}

private fun roleIcon(role: AgentRole): ImageVector = when (role) {
    AgentRole.PLANNER -> Icons.Default.AccountTree
    AgentRole.CODER -> Icons.Default.Code
    AgentRole.REVIEWER -> Icons.Default.RateReview
    AgentRole.SECURITY_GUARD -> Icons.Default.Security
    AgentRole.RESEARCHER -> Icons.Default.TravelExplore
    AgentRole.EXECUTOR -> Icons.Default.PlayArrow
    AgentRole.GENERAL_ASSISTANT -> Icons.Default.Psychology
}

@Composable
private fun AgentChip(
    agent: AgentDefinition,
    isSelected: Boolean,
    isDeletable: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (isSelected) Modifier.border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(14.dp)
                ) else Modifier
            )
            .clickable(onClick = onSelect)
            .testTag("agent_chip_${agent.identity.id.value}"),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = if (isDeletable) 4.dp else 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    roleIcon(agent.identity.role),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = agent.identity.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = agent.identity.role.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isDeletable) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(26.dp)
                        .testTag("btn_delete_agent_${agent.identity.id.value}")
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "حذف الوكيل",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveAgentCard(agent: AgentDefinition) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    roleIcon(agent.identity.role),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${agent.identity.name} — ${agent.identity.role.displayName}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                StatusBadge(
                    text = "سقف الرموز: ${agent.budget.maxTokens}",
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
            if (agent.identity.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = agent.identity.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun SessionIntroCard(agentName: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "ابدأ جلسة عمل مع «$agentName»",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "كل أمر تطلقه يمر بحلقة قرار ذاتية كاملة: اختيار النموذج والأدوات (CBR-MDP)، " +
                    "تنفيذ مُدار ببوابات أمن وميزانية، ثم ملاحظة النتيجة وتعلّمها. تابع سجل الأحداث " +
                    "الحية تحت كل إجابة، وانسخ النتائج بضغطة واحدة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun TurnBubble(turn: StudioTurn, onCopy: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // User prompt bubble
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp, 14.dp, 3.dp, 14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Text(
                    text = turn.prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Agent answer bubble
        Surface(
            shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 3.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = turn.agentName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (!turn.isSuccessful) {
                        StatusBadge("فشل التنفيذ", MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    IconButton(onClick = onCopy, modifier = Modifier.size(26.dp)) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "نسخ الإجابة",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                SelectionContainer {
                    Text(
                        text = turn.answer.ifBlank { "— لا يوجد نص متدفق لهذا الدور —" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("⏱ ${turn.durationMs / 1000.0}s")
                        append("   •   ")
                        append("رموز: ${turn.tokensConsumed}")
                        append("   •   ")
                        append("أحداث: ${turn.eventCount}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun LiveExecutionCard(
    streamText: String,
    isExecuting: Boolean,
    events: List<com.example.domain.core.events.ExecutionEvent>,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 3.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("output_stream_card")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isExecuting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "تنفيذ حي — ${events.size}+ حدثاً",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "آخر تدفق",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (streamText.isNotBlank()) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(26.dp)) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "نسخ",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            if (streamText.isNotBlank()) {
                SelectionContainer {
                    Text(
                        text = streamText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (events.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "أحدث الأحداث (سجل التنفيذ الحي)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                events.forEach { event ->
                    ExecutionEventTimelineItem(event = event)
                }
            }
        }
    }
}

@Composable
private fun PromptComposer(
    value: String,
    isExecuting: Boolean,
    onValueChange: (String) -> Unit,
    onExecute: () -> Unit,
    onCancel: () -> Unit,
    onClearSession: () -> Unit,
    hasSession: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (hasSession && !isExecuting) {
                TextButton(
                    onClick = onClearSession,
                    modifier = Modifier.testTag("btn_clear_session")
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تفريغ سجل الجلسة", style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("اكتب أمراً للوكيل…") },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .testTag("prompt_text_field"),
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 5
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (isExecuting) {
                    Surface(
                        onClick = onCancel,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("cancel_execution_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "إلغاء التنفيذ",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                } else {
                    Surface(
                        onClick = { if (value.isNotBlank()) onExecute() },
                        shape = CircleShape,
                        color = if (value.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("execute_prompt_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "تنفيذ",
                                tint = if (value.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentBuilderDialog(
    onConfirm: (String, AgentRole, String, String, Set<CapabilityType>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(AgentRole.GENERAL_ASSISTANT) }
    var description by rememberSaveable { mutableStateOf("") }
    var systemPrompt by rememberSaveable { mutableStateOf("") }
    // §23 (audit 2026 — Agent Builder = Execution Contract Builder): the
    // user authors the capability contract. LLM generation + streaming are
    // pre-selected sensible defaults; the rest are explicit opt-ins.
    var selectedCapabilities by rememberSaveable {
        mutableStateOf(setOf(CapabilityType.LLM_GENERATION.name, CapabilityType.STREAMING.name))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("بناء وكيل جديد (يُحفظ في السجل الدائم)", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم الوكيل") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("agent_builder_name")
                )
                Text(
                    text = "الدور الوظيفي",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRoleChips(selected = role, onSelect = { role = it })
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("وصف مختصر") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("موجه النظام (System Prompt)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp)
                        .testTag("agent_builder_prompt"),
                    placeholder = { Text(role.defaultSystemPrompt) }
                )
                Text(
                    text = "عقد التنفيذ — القدرات المسموحة",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "حدّد ما يستطيع هذا الوكيل فعله فعلياً. القدرات غير المحددة ستُرفض عند التنفيذ (fail-closed).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowCapabilityChips(
                    selected = selectedCapabilities,
                    onToggle = { capName ->
                        selectedCapabilities = if (capName in selectedCapabilities) {
                            selectedCapabilities - capName
                        } else {
                            selectedCapabilities + capName
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val capabilities = selectedCapabilities
                        .mapNotNull { capName -> runCatching { CapabilityType.valueOf(capName) }.getOrNull() }
                        .toSet()
                    onConfirm(name.trim(), role, description.trim(), systemPrompt.trim(), capabilities)
                },
                enabled = name.isNotBlank() && selectedCapabilities.isNotEmpty()
            ) {
                Text("إنشاء وتفعيل", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

/**
 * §23 (audit 2026 — Agent Builder capabilities): multi-select capability
 * chips authoring the agent's execution contract.
 */
@Composable
private fun FlowCapabilityChips(
    selected: Set<String>,
    onToggle: (String) -> Unit
) {
    val buildable = listOf(
        CapabilityType.LLM_GENERATION,
        CapabilityType.STREAMING,
        CapabilityType.SEARCH,
        CapabilityType.TOOL_EXECUTION,
        CapabilityType.FILE_STORAGE,
        CapabilityType.FILE_READ,
        CapabilityType.FILE_WRITE,
        CapabilityType.MEMORY_RETRIEVAL,
        CapabilityType.MCP_INVOCATION,
        CapabilityType.AGENT_DELEGATION,
        CapabilityType.CODE_ANALYSIS,
        CapabilityType.CODE_ENGINEERING,
        CapabilityType.SECURITY_AUDIT
    )
    Column {
        buildable.chunked(3).forEach { rowCaps ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                rowCaps.forEach { cap ->
                    FilterChip(
                        selected = cap.name in selected,
                        onClick = { onToggle(cap.name) },
                        label = {
                            Text(
                                cap.displayName.substringBefore(" ("),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowRoleChips(selected: AgentRole, onSelect: (AgentRole) -> Unit) {
    Column {
        AgentRole.entries.chunked(2).forEach { rowRoles ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                rowRoles.forEach { r ->
                    FilterChip(
                        selected = r == selected,
                        onClick = { onSelect(r) },
                        label = {
                            Text(
                                r.displayName.substringBefore(" ("),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                roleIcon(r),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DURABLE SESSIONS + QUICK CHAT + MODEL PICKER (report gap-closure)
// ---------------------------------------------------------------------------

/**
 * Conversation mode bar: QUICK_CHAT (agent-independent, binds to the selected
 * model) vs AGENT (canonical agent catalog) — plus the durable session
 * browser trigger and "new session" action.
 */
@Composable
private fun ChatModeAndSessionsBar(
    mode: ChatMode,
    onModeChange: (ChatMode) -> Unit,
    onOpenSessions: () -> Unit,
    onNewSession: () -> Unit,
    activeSessionTitle: String?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("chat_mode_bar"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = mode == ChatMode.QUICK_CHAT,
                    onClick = { onModeChange(ChatMode.QUICK_CHAT) },
                    label = { Text("محادثة سريعة", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
                FilterChip(
                    selected = mode == ChatMode.AGENT,
                    onClick = { onModeChange(ChatMode.AGENT) },
                    label = { Text("وضع الوكيل", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSessions) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = "تصفح الجلسات",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onNewSession) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "جلسة جديدة",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            activeSessionTitle?.let { title ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "الجلسة النشطة: $title",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * USER-FACING EXACT MODEL PICKER (report gap: "direct Model Picker missing"):
 * lists the ENABLED/ACTIVE LLM resources and binds the conversation to the
 * exact chosen resource (durable runtime binding, not a floating preference).
 */
@Composable
private fun ModelPickerRow(
    resources: List<com.example.domain.core.resource.ResourceRecord>,
    selectedResourceId: String?,
    onSelect: (String?, String?) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selected = resources.firstOrNull { it.resourceId.value == selectedResourceId }
    val selectedLabel = selected?.let { record ->
        record.metadata["displayName"] ?: record.metadata["offeringId"] ?: record.resourceId.value
    } ?: "اختيار تلقائي (طبقة القرار)"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("model_picker_row"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable { menuOpen = true }
        ) {
            Icon(
                Icons.Default.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "النموذج المرتبط بالمحادثة",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("اختيار تلقائي (طبقة القرار)") },
                    onClick = {
                        onSelect(null, null)
                        menuOpen = false
                    }
                )
                resources.forEach { record ->
                    val label = record.metadata["displayName"]
                        ?: record.metadata["offeringId"]
                        ?: record.resourceId.value
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = buildString {
                                        append(if (record.isLocal) "محلي" else "سحابي")
                                        append(" • ")
                                        append(record.providerId)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onSelect(record.resourceId.value, label)
                            menuOpen = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * DURABLE SESSION BROWSER (report gaps: "Session browser / Session retrieval
 * after restart / Resume conversation"): lists the workspace sessions with
 * turn/token aggregates; open resumes the conversation with its full history.
 */
@Composable
private fun SessionBrowserDialog(
    sessions: List<ConversationSession>,
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
                                    if (session.mode == ChatMode.QUICK_CHAT) Icons.Default.PlayArrow
                                    else Icons.Default.Psychology,
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
