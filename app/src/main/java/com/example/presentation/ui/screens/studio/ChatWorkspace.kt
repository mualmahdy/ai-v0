package com.example.presentation.ui.screens.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.session.ConversationSession
import com.example.presentation.state.ChatCapabilityKey
import com.example.presentation.state.ChatCapabilityStatus
import com.example.presentation.ui.navigation.NavWidthClass
import com.example.presentation.ui.navigation.WorkspaceRoutes
import com.example.presentation.viewmodel.ChatCapabilitiesViewModel
import com.example.presentation.viewmodel.StudioViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * ChatWorkspace — the conversation-first Studio surface (Chat Workspace Task
 * 1, extended by CHAT CAPABILITIES Task 2 §18/§19)
 * ============================================================================
 *
 * ADAPTIVE CHAT (§19 — no single fixed layout):
 *   COMPACT  → chat-first (sessions behind the header's browser button,
 *              opened as a full-height bottom sheet).
 *   MEDIUM   → sessions pane | chat.
 *   EXPANDED → sessions pane | chat | context/execution pane.
 *
 * The same controls are NEVER duplicated across panes: the sessions list is
 * EITHER a pane (medium+) OR a sheet (compact); the context/execution pane
 * exists only at expanded width. Chat stays a CHAT — the panes surface the
 * conversation's real data, never administration screens (§20).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatWorkspace(
    state: StudioViewModel.StudioUiState,
    shellAutonomyPolicy: com.example.domain.core.task.AutonomyPolicy,
    projectName: String?,
    activeSessionTitle: String?,
    sessions: List<ConversationSession>,
    llmResources: List<com.example.domain.core.resource.ResourceRecord>,
    agents: List<com.example.domain.core.agent.AgentDefinition>,
    activeAgent: com.example.domain.core.agent.AgentDefinition?,
    isSessionBrowserOpen: Boolean,
    /** Task-2: the capability layer's state (menu facts + drafts + mirrors). */
    capabilityState: ChatCapabilitiesViewModel.ChatCapabilitiesUiState,
    /** Task-2 §19: the adaptive width class (COMPACT / MEDIUM / EXPANDED). */
    widthClass: NavWidthClass,
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
    /** ---- Task-2 capability wiring ---- */
    onOpenCapabilities: () -> Unit,
    onPickFiles: (List<String>, List<String?>) -> Unit,
    onPickFolder: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onInvokeSearch: (String) -> Unit,
    onInvokeKnowledge: (String) -> Unit,
    onInvokeTool: (toolName: String, argumentsJson: String, isMcp: Boolean) -> Unit,
    onPingMcp: (String) -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onRetryAfterApproval: () -> Unit,
    /** §13: "allow always" — the standing EXECUTE grant path. */
    onGrantAlways: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current

    var contextSheetOpen by rememberSaveable { mutableStateOf(false) }
    var settingsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var agentBuilderOpen by rememberSaveable { mutableStateOf(false) }
    var deleteAgentTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteSessionTarget by rememberSaveable { mutableStateOf<String?>(null) }

    // Task-2 capability surfaces (progressive disclosure — one at a time).
    var capabilityMenuOpen by rememberSaveable { mutableStateOf(false) }
    var searchSheetOpen by rememberSaveable { mutableStateOf(false) }
    var knowledgeSheetOpen by rememberSaveable { mutableStateOf(false) }
    var skillsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var toolsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var mcpSheetOpen by rememberSaveable { mutableStateOf(false) }

    // §9: the intentional post-send scroll signal (a plain counter).
    var sendSignal by remember { mutableIntStateOf(0) }

    // SAF pickers (§5): the platform boundary — resolved uris are handed to
    // the capability layer; the import itself runs on the real transfer path.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            onPickFiles(
                uris.map { it.toString() },
                uris.map { runCatching { context.contentResolver.getType(it) }.getOrNull() }
            )
        }
    }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onPickFolder(uri.toString())
    }

    val hasActiveLlm = llmResources.any {
        it.resourceType == ResourceType.LLM && it.lifecycleState == ResourceLifecycleState.ENABLED
    }

    // §19: the adaptive shell — sessions pane (medium+), chat, context pane
    // (expanded). COMPACT stays chat-first.
    Row(modifier = modifier.testTag("agent_studio_screen")) {

        if (widthClass != NavWidthClass.COMPACT) {
            ChatSessionsPane(
                sessions = sessions,
                activeSessionId = state.activeSessionId,
                onOpen = onOpenSession,
                onDeleteRequest = { deleteSessionTarget = it },
                onNewSession = onNewSession,
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
            )
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
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
                // §19: the browser button exists ONLY in compact (medium+
                // panes already show the sessions list — no duplicates).
                onOpenSessions = if (widthClass == NavWidthClass.COMPACT) {
                    { onSessionBrowserOpen(true) }
                } else {
                    null
                }
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
                        onResumeSessions = {
                            if (widthClass == NavWidthClass.COMPACT) {
                                onSessionBrowserOpen(true)
                            }
                        }
                    )
                },
                onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) },
                onEdit = onEditMessage,
                onRegenerate = onRegenerate,
                onRetry = onRegenerate,
                onApprove = onApprove,
                onReject = onReject,
                onRetryAfterApproval = onRetryAfterApproval,
                onGrantAlways = onGrantAlways
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
                onClearDraft = { onPromptInput("") },
                onOpenCapabilities = {
                    // §3: the "+" opens the categorized menu (internal state)
                    // and the capability layer refreshes its facts.
                    onOpenCapabilities()
                    capabilityMenuOpen = true
                },
                attachmentDrafts = capabilityState.attachmentDrafts,
                onRemoveAttachment = onRemoveAttachment,
                isImportingAttachment = capabilityState.isImportingAttachment
            )
        }

        if (widthClass == NavWidthClass.EXPANDED) {
            ChatContextPane(
                state = state,
                capabilityState = capabilityState,
                agentName = activeAgent?.identity?.name,
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
            )
        }
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

    // ---- Task-2 §18: the scalable session browser surface ----
    if (widthClass == NavWidthClass.COMPACT && isSessionBrowserOpen) {
        SessionBrowserSheet(
            sessions = sessions,
            activeSessionId = state.activeSessionId,
            onOpen = { sessionId ->
                onOpenSession(sessionId)
                onSessionBrowserOpen(false)
            },
            onDeleteRequest = { deleteSessionTarget = it },
            onNewSession = {
                onNewSession()
                onSessionBrowserOpen(false)
            },
            onDismiss = { onSessionBrowserOpen(false) }
        )
    }

    // ---- Task-2 §3/§4: the capability entry point's surfaces ----
    if (capabilityMenuOpen) {
        ChatCapabilityMenu(
            capabilities = capabilityState.capabilities,
            isInvoking = capabilityState.isInvoking,
            onDismiss = { capabilityMenuOpen = false },
            onCapabilityClick = { key ->
                capabilityMenuOpen = false
                when (key) {
                    ChatCapabilityKey.ATTACH_FILE -> filePicker.launch(arrayOf("*/*"))
                    ChatCapabilityKey.ATTACH_FOLDER -> folderPicker.launch(null)
                    ChatCapabilityKey.VISION_ANALYSIS -> Unit // Planned — not clickable (§7).
                    ChatCapabilityKey.KNOWLEDGE_RETRIEVAL -> knowledgeSheetOpen = true
                    ChatCapabilityKey.SEARCH_INTELLIGENCE -> searchSheetOpen = true
                    ChatCapabilityKey.SKILLS -> skillsSheetOpen = true
                    ChatCapabilityKey.TOOLS -> toolsSheetOpen = true
                    ChatCapabilityKey.MCP_SERVERS -> mcpSheetOpen = true
                }
            }
        )
    }

    if (searchSheetOpen) {
        ChatSearchSheet(
            isInvoking = capabilityState.isInvoking,
            onDismiss = { searchSheetOpen = false },
            onRun = { query ->
                searchSheetOpen = false
                onInvokeSearch(query)
            }
        )
    }

    if (knowledgeSheetOpen) {
        ChatKnowledgeSheet(
            isInvoking = capabilityState.isInvoking,
            documentCount = capabilityState.knowledgeDocumentCount,
            onDismiss = { knowledgeSheetOpen = false },
            onRun = { query ->
                knowledgeSheetOpen = false
                onInvokeKnowledge(query)
            }
        )
    }

    if (skillsSheetOpen) {
        ChatSkillBrowserSheet(
            skills = capabilityState.skills,
            isInvoking = capabilityState.isInvoking,
            onDismiss = { skillsSheetOpen = false },
            onRunSkill = { skill, parameters ->
                skillsSheetOpen = false
                val argsJson = org.json.JSONObject().apply {
                    parameters.forEach { (k, v) -> put(k, v) }
                }.toString()
                onInvokeTool(skill.id, argsJson, false)
            }
        )
    }

    if (toolsSheetOpen) {
        ChatToolBrowserSheet(
            tools = capabilityState.tools,
            isInvoking = capabilityState.isInvoking,
            onDismiss = { toolsSheetOpen = false },
            onRunTool = { tool, parameters ->
                toolsSheetOpen = false
                val argsJson = org.json.JSONObject().apply {
                    parameters.forEach { (k, v) -> put(k, v) }
                }.toString()
                onInvokeTool(tool.name, argsJson, false)
            }
        )
    }

    if (mcpSheetOpen) {
        ChatMcpBrowserSheet(
            servers = capabilityState.mcpServers,
            isDiscovering = capabilityState.isDiscoveringMcp,
            isInvoking = capabilityState.isInvoking,
            onDismiss = { mcpSheetOpen = false },
            onPing = onPingMcp,
            onRunTool = { server, toolName, arguments ->
                mcpSheetOpen = false
                val argsJson = org.json.JSONObject().apply {
                    arguments.forEach { (k, v) -> put(k, v) }
                }.toString()
                // The registered MCP tool name is "<server>__<tool>" (the
                // HEALTHY-only registration contract).
                onInvokeTool("${server.id}__$toolName", argsJson, true)
            }
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

    // §18: destructive session deletion confirms.
    deleteSessionTarget?.let { targetId ->
        val sessionTitle = sessions.firstOrNull {
            it.id.value == targetId
        }?.title ?: ""
        com.example.presentation.ui.components.ConfirmDialog(
            title = "حذف الجلسة",
            message = "سيُحذف «$sessionTitle» مع كل دوراتها نهائياً من هذا الجهاز. هل تريد المتابعة؟",
            confirmLabel = "حذف",
            onConfirm = {
                onDeleteSession(targetId)
                deleteSessionTarget = null
            },
            onDismiss = { deleteSessionTarget = null }
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

// ---------------------------------------------------------------------------
// §18 — the SCALABLE session browser surfaces
// ---------------------------------------------------------------------------

/**
 * The sessions PANE (medium/expanded): the real durable sessions, ordered
 * most-recently-active first (the repository's ordering), with open +
 * delete-with-confirmation. No session management beyond that — §20.
 */
@Composable
private fun ChatSessionsPane(
    sessions: List<ConversationSession>,
    activeSessionId: String?,
    onOpen: (String) -> Unit,
    onDeleteRequest: (String) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        modifier = modifier.testTag("sessions_pane")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "جلسات المحادثة",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onNewSession, modifier = Modifier.testTag("pane_btn_new_session")) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "جلسة جديدة",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sessions.isEmpty()) {
                    item(key = "pane_empty") {
                        Text(
                            "لا جلسات محفوظة بعد — أرسل أول رسالة لتُنشأ جلسة دائمة تلقائياً.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(sessions, key = { it.id.value }) { session ->
                    SessionRow(
                        session = session,
                        isActive = session.id.value == activeSessionId,
                        onOpen = { onOpen(session.id.value) },
                        onDelete = { onDeleteRequest(session.id.value) }
                    )
                }
            }
        }
    }
}

/** The COMPACT session browser (§18: a full-height sheet, not a tiny dialog). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionBrowserSheet(
    sessions: List<ConversationSession>,
    activeSessionId: String?,
    onOpen: (String) -> Unit,
    onDeleteRequest: (String) -> Unit,
    onNewSession: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("session_browser_sheet")) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .heightIn(max = 560.dp)
        ) {
            Text("جلسات المحادثة الدائمة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            if (sessions.isEmpty()) {
                Text(
                    "لا جلسات محفوظة بعد — أرسل أول رسالة لتُنشأ جلسة دائمة تلقائياً.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sessions, key = { it.id.value }) { session ->
                        SessionRow(
                            session = session,
                            isActive = session.id.value == activeSessionId,
                            onOpen = { onOpen(session.id.value) },
                            onDelete = { onDeleteRequest(session.id.value) }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onNewSession, modifier = Modifier.testTag("sheet_btn_new_session")) {
                Text("جلسة جديدة")
            }
        }
    }
}

/** One session row shared by the pane and the sheet (§18: title/mode/time/model). */
@Composable
private fun SessionRow(
    session: ConversationSession,
    isActive: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm • dd/MM", Locale.getDefault()) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
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
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = "حذف الجلسة «${session.title}»",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// §19 — the CONTEXT/EXECUTION pane (expanded only)
// ---------------------------------------------------------------------------

/**
 * The context pane shows the conversation's REAL runtime context: the
 * session binding (mode/model/agent), the live execution's honest phase +
 * counters, and the capability layer's availability summary. It READS state
 * only — every mutation stays in the chat itself (§20: no administration
 * clutter inside the conversation surface).
 */
@Composable
private fun ChatContextPane(
    state: StudioViewModel.StudioUiState,
    capabilityState: ChatCapabilitiesViewModel.ChatCapabilitiesUiState,
    agentName: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        modifier = modifier.testTag("context_pane")
    ) {
        Column(
            modifier = Modifier
                .padding(10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "السياق والتنفيذ",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            // The session binding card (all real state).
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.padding(10.dp)) {
                    ContextRow("الوضع", if (state.chatMode == com.example.domain.core.session.ChatMode.QUICK_CHAT) "محادثة سريعة" else "وكيل")
                    ContextRow("النموذج", state.selectedModelDisplayName ?: "تلقائي (طبقة القرار)")
                    ContextRow("الوكيل", agentName ?: "—")
                    ContextRow("الرموز (الجلسة)", "${state.sessionTotalTokens}")
                    ContextRow("الميزانية المتبقية", "${state.remainingBudget}")
                    ContextRow("سياسة الشبكة", state.networkPolicy.name)
                }
            }

            // The live execution card (the REAL lifecycle projection).
            val live = state.liveExecution
            if (live != null) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        ContextRow("المرحلة", lifecycleLabel(live))
                        live.phaseDetail?.let { ContextRow("التفصيل", it) }
                        ContextRow("إجراءات", "${live.actionCount}")
                        ContextRow("أدوات", "${live.toolCount}")
                        ContextRow("المدة", "%.1fs".format((System.currentTimeMillis() - live.startedAtMs) / 1000.0))
                    }
                }
            }

            // The capability availability summary (§4 — real states only).
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.padding(10.dp)) {
                    val available = capabilityState.capabilities.count {
                        it.status == ChatCapabilityStatus.AVAILABLE
                    }
                    val planned = capabilityState.capabilities.count {
                        it.status == ChatCapabilityStatus.PLANNED
                    }
                    ContextRow("قدرات متاحة", "$available")
                    if (planned > 0) ContextRow("مخططة (قريباً)", "$planned")
                    ContextRow("أدوات مسجلة", "${capabilityState.tools.size}")
                    ContextRow("مهارات مفعّلة", "${capabilityState.skills.count { it.state == com.example.domain.core.extension.SkillState.ENABLED }}")
                }
            }
        }
    }
}

@Composable
private fun ContextRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
