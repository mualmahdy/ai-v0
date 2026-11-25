package com.example.presentation.ui.screens.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import com.example.presentation.state.CapabilityKind
import com.example.presentation.ui.navigation.navWidthClassForWidthDp
import com.example.presentation.viewmodel.ChatCapabilitiesViewModel
import com.example.presentation.viewmodel.MainViewModel
import com.example.presentation.viewmodel.SessionsViewModel

/**
 * ============================================================================
 * StudioScreen — the route-facing entry (thin adapter)
 * ============================================================================
 *
 * The Studio destination composes its feature ViewModels (ADR-6 owner-VM
 * composition, unchanged) and delegates ALL rendering to [ChatWorkspace] —
 * the conversation-first surface (Chat Workspace Task 1 + Chat Capabilities
 * Task 2 + FUNCTIONAL CLOSURE Phase 1). Keeping this adapter separate from
 * the workspace keeps the navigation graph stable while the conversation UI
 * evolves behind it.
 *
 *  - the conversation runtime state → StudioViewModel (its owner);
 *  - the capability layer (availability catalog, attachment drafts,
 *    tool/skill/MCP/search/knowledge invocation state) →
 *    ChatCapabilitiesViewModel (Task 2);
 *  - the durable-session registry rows + browser flag → SessionsViewModel;
 *  - the model picker resources + connect-LLM gate → ProvidersViewModel;
 *  - the agent catalog (picker/builder/delete/selection seam) →
 *    AgentsViewModel;
 *  - the autonomy-policy DISPLAY mirror stays on MainViewModel (its owner —
 *    the workspace-scoped mirror); mutations route to SettingsViewModel
 *    (authoritative service routing — ADR-6 slice 1);
 *  - the honest current-project mirror → ProjectsViewModel (D-02).
 *
 * FUNCTIONAL CLOSURE (Phase 1) seams owned by this adapter:
 *  - §4 AGENT RESTORATION: a reopened session's agent
 *    ([com.example.presentation.viewmodel.StudioViewModel.StudioUiState.
 *    restoredAgentId]) is routed into the agent-catalog owner's selection —
 *    the ACTUAL agent executes continuations, not just a display name.
 *  - §22 CAPABILITY LIFECYCLE: every direct invocation first lands a PENDING
 *    block in the conversation (the honest kind + title), then resolves it
 *    with the real outcome — the sheets can close immediately without the
 *    user losing track of what ran.
 *  - §14 ABORTED SENDS: when the ViewModel aborts a send pre-execution
 *    (grounding failure), the attachment drafts are handed BACK to the
 *    capability layer — the user's picks are never silently consumed.
 */
@Composable
fun StudioScreen(
    viewModel: MainViewModel,
    studioViewModel: com.example.presentation.viewmodel.StudioViewModel,
    chatCapabilitiesViewModel: ChatCapabilitiesViewModel,
    sessionsViewModel: SessionsViewModel,
    providersViewModel: com.example.presentation.viewmodel.ProvidersViewModel,
    agentsViewModel: com.example.presentation.viewmodel.AgentsViewModel,
    projectsViewModel: com.example.presentation.viewmodel.ProjectsViewModel,
    onNavigate: (String) -> Unit,
    onAutonomyPolicy: (com.example.domain.core.task.AutonomyPolicy) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val providersState by providersViewModel.state.collectAsState()
    val studioState by studioViewModel.state.collectAsState()
    val sessionsState by sessionsViewModel.state.collectAsState()
    val agentsState by agentsViewModel.state.collectAsState()
    val projectsState by projectsViewModel.state.collectAsState()
    val capabilityState by chatCapabilitiesViewModel.state.collectAsState()

    // ------------------------------------------------------------------
    // FUNCTIONAL CLOSURE (§4): AGENT RESTORATION — when a session is
    // reopened, its OWN agent becomes the catalog owner's active selection
    // (the continuation then executes through the real agent, not just a
    // name in the header). A null/unknown agent keeps the current selection;
    // AGENT-mode sends without a resolvable selection fail honestly in the
    // ViewModel (the documented actionable error).
    // ------------------------------------------------------------------
    val restoredAgentId = studioState.restoredAgentId
    LaunchedEffect(restoredAgentId) {
        if (restoredAgentId != null) {
            agentsState.availableAgents.firstOrNull {
                it.identity.id.value == restoredAgentId
            }?.let { agent -> agentsViewModel.selectAgent(agent) }
        }
    }

    // The composer follows the same semantic boundary as the visible chat.
    val conversationKey = studioState.activeSessionId ?: "draft:${studioState.chatMode.name}"
    LaunchedEffect(conversationKey, projectsState.currentProject?.id) {
        chatCapabilitiesViewModel.bindConversationKey(conversationKey)
    }

    // §19 (Task 2): the adaptive width class (the same M3 breakpoints the
    // navigation shell uses — chat-first / sessions+chat / sessions+chat+context).
    val widthClass = navWidthClassForWidthDp(LocalConfiguration.current.screenWidthDp)

    // ------------------------------------------------------------------
    // UI POLISH (§4 — Creation group honesty): the LLM connection fact the
    // capability hub resolves its Creation rows on. The providers feature
    // owns the resources (owner-VM composition); the operational truth of
    // "an LLM is usable" = lifecycle ENABLED **or** ACTIVE (the runtime
    // promotes healthy resources to ACTIVE) AND health not UNAVAILABLE.
    // Pushed to the capability layer as value+lambda (the sessionNetworkPolicy
    // seam pattern — no new cross-VM dependency).
    // ------------------------------------------------------------------
    val hasActiveLlm = providersState.materializedResources.any {
        it.resourceType == com.example.domain.core.resource.ResourceType.LLM &&
            (it.lifecycleState == com.example.domain.core.resource.ResourceLifecycleState.ENABLED ||
                it.lifecycleState == com.example.domain.core.resource.ResourceLifecycleState.ACTIVE) &&
            it.healthStatus != com.example.domain.core.provider.HealthStatus.UNAVAILABLE
    }
    LaunchedEffect(hasActiveLlm) {
        chatCapabilitiesViewModel.setLlmConnected(hasActiveLlm)
    }

    // FRONTIER EXPORT: the platform share sheet target — hoisted OUT of the
    // callback (Compose locals are composable-context-only).
    val shareContext = androidx.compose.ui.platform.LocalContext.current

    // ------------------------------------------------------------------
    // FIW Stage 1/2 — the ONE device-save surface (SAF CreateDocument):
    // a REAL file save for artifacts, attachments and session exports
    // (byte-exact, no storage permission, honest failure feedback). The
    // launcher is keyed by the pending export's MIME so the picker filters
    // by the real type.
    // ------------------------------------------------------------------
    var pendingDeviceExport by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<PendingDeviceExport?>(null)
    }
    val exportMime = pendingDeviceExport?.mime ?: "application/octet-stream"
    val deviceExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(exportMime)
    ) { uri ->
        val pending = pendingDeviceExport
        if (uri != null && pending != null) {
            val written = com.example.presentation.ui.components.SandboxFileExchange
                .writeToSafTarget(shareContext, uri, pending.bytes)
            if (!written) {
                studioViewModel.reportArtifactExchangeUnavailable(
                    "تعذّرت كتابة الملف في الموقع المختار — تحقق من مساحة التخزين وحاول مجدداً."
                )
            }
        }
        pendingDeviceExport = null
    }
    androidx.compose.runtime.LaunchedEffect(pendingDeviceExport) {
        pendingDeviceExport?.let { deviceExportLauncher.launch(it.fileName) }
    }

    // ------------------------------------------------------------------
    // FIW Stage 1 — the UNIFIED session export flow: pick-format dialog →
    // the ONE export service → SAF save (primary) / share sheet (secondary).
    // ------------------------------------------------------------------
    var sessionExportFormatTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    fun runSessionExport(
        sessionId: String,
        format: com.example.application.transfer.SessionExportService.Format
    ) {
        sessionsViewModel.exportSession(
            sessionId,
            format,
            onReady = { _, fileName, mimeType, content ->
                pendingDeviceExport = PendingDeviceExport(fileName, mimeType, content.toByteArray())
            },
            onUnavailable = { sessionsViewModel.reportExportUnavailable() }
        )
    }
    // FIW Stage 1 — the session-package IMPORT (SAF OpenDocument pick).
    val sessionImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                shareContext.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
            if (text == null) {
                sessionsViewModel.reportExportUnavailable()
            } else {
                sessionsViewModel.importSessionPackage(
                    text,
                    onImported = { title, turns, payloads ->
                        studioViewModel.reportArtifactExchangeUnavailable(
                            "استُوردت الجلسة «$title» ($turns دوراً، $payloads مخرجاً مُستعاداً) — تجدها في متصفح الجلسات."
                        )
                    },
                    onRejected = { reason ->
                        studioViewModel.reportArtifactExchangeUnavailable("رُفض استيراد الحزمة: $reason")
                    }
                )
            }
        }
    }

    ChatWorkspace(
        state = studioState,
        shellAutonomyPolicy = state.autonomyPolicy,
        projectName = projectsState.currentProject?.name,
        activeSessionTitle = sessionsState.sessions
            .firstOrNull { it.id.value == studioState.activeSessionId }?.title,
        // FRONTIER SEARCH: the pure filter (title/agent/model, case-insensitive)
        // runs ONCE here — pane and sheet render the same filtered truth.
        sessions = com.example.presentation.state.SessionTranscriptExporter.filterSessions(
            sessionsState.sessions,
            sessionsState.searchQuery
        ),
        llmResources = providersState.materializedResources,
        agents = agentsState.availableAgents,
        activeAgent = agentsState.activeAgent,
        isSessionBrowserOpen = sessionsState.isSessionBrowserOpen,
        sessionSearchQuery = sessionsState.searchQuery,
        onSessionSearchQueryChange = sessionsViewModel::setSearchQuery,
        onRenameSession = { sessionId, title ->
            sessionsViewModel.renameSession(sessionId, title)
        },
        onExportSession = { sessionId ->
            // FIW Stage 1 — UNIFIED EXPORT: the pick-format dialog → the ONE
            // export service → SAF save-to-device (CreateDocument) with the
            // platform share sheet as the secondary route. The previous
            // Markdown-only ACTION_SEND path bypassed the canonical service
            // (the audit's "two implementations" finding).
            sessionExportFormatTarget = sessionId
        },
        capabilityState = capabilityState,
        widthClass = widthClass,
        onNavigate = onNavigate,
        onPromptInput = studioViewModel::updatePromptInput,
        onSend = {
            // §5 (Task 2) + FUNCTIONAL CLOSURE (§14): the send consumes the
            // attachment drafts only when the ViewModel really ACCEPTED it; a
            // send aborted at the grounding stage hands the drafts BACK (the
            // user's picks are never silently lost).
            val accepted = studioViewModel.executePrompt(
                agent = agentsState.activeAgent,
                attachments = capabilityState.attachmentDrafts,
                onSendAborted = { drafts ->
                    chatCapabilitiesViewModel.restoreAttachmentDrafts(drafts)
                }
            )
            if (accepted && capabilityState.attachmentDrafts.isNotEmpty()) {
                chatCapabilitiesViewModel.clearAttachmentDrafts()
            }
        },
        onCancelExecution = studioViewModel::cancelExecution,
        // FUNCTIONAL CLOSURE (§6): targeted regenerate — the tapped entry's id.
        onRegenerate = { assistantEntryId ->
            studioViewModel.regenerateFromAssistant(
                assistantEntryId = assistantEntryId,
                agent = agentsState.activeAgent
            )
        },
        onEditMessage = studioViewModel::editUserMessage,
        onResetView = studioViewModel::resetTranscriptView,
        onNewSession = {
            studioViewModel.startNewSession(agent = agentsState.activeAgent)
        },
        onOpenSession = studioViewModel::openSession,
        onDeleteSession = { sessionId ->
            sessionsViewModel.deleteSession(sessionId) { deleted ->
                studioViewModel.onSessionDeleted(deleted)
            }
        },
        onSessionBrowserOpen = sessionsViewModel::setSessionBrowserOpen,
        onModeChange = studioViewModel::setChatMode,
        onSelectModel = studioViewModel::selectModel,
        onSelectAgent = agentsViewModel::selectAgent,
        onNetworkPolicy = studioViewModel::setNetworkPolicy,
        onAutonomyPolicy = onAutonomyPolicy,
        onCreateAgent = { name, role, description, systemPrompt, capabilities ->
            // §23 (audit 2026): the user authors the agent's execution
            // capabilities — the contract surface.
            agentsViewModel.createAgent(
                name = name,
                role = role,
                description = description,
                systemPrompt = systemPrompt,
                capabilities = capabilities
            )
        },
        onDeleteAgent = agentsViewModel::deleteAgent,
        onOpenCapabilities = chatCapabilitiesViewModel::refreshCapabilities,
        onPickFiles = { uris, mimeTypes ->
            chatCapabilitiesViewModel.pickFiles(uris, mimeTypes)
        },
        onPickFolder = chatCapabilitiesViewModel::pickFolder,
        onRemoveAttachment = chatCapabilitiesViewModel::removeAttachment,
        onInvokeSearch = { query ->
            // FUNCTIONAL CLOSURE (§22): the PENDING block lands FIRST (the
            // sheet closes immediately), then the real outcome resolves it.
            val pendingId = studioViewModel.appendPendingCapability(
                kind = CapabilityKind.SEARCH,
                title = "بحث ذكي: $query"
            )
            // CHAT FINAL CLOSURE (§4/§5): the session the invocation belongs
            // to rides the invocation (pinned scope snapshot end-to-end).
            chatCapabilitiesViewModel.invokeSearch(
                query = query,
                agent = agentsState.activeAgent,
                sessionId = studioState.activeSessionId
            ) { entry ->
                studioViewModel.resolveCapabilityResult(pendingId, entry)
            }
        },
        onInvokeKnowledge = { query ->
            val pendingId = studioViewModel.appendPendingCapability(
                kind = CapabilityKind.KNOWLEDGE_RETRIEVAL,
                title = "استرجاع المعرفة: $query"
            )
            chatCapabilitiesViewModel.invokeKnowledgeRetrieval(
                query = query,
                sessionId = studioState.activeSessionId
            ) { entry ->
                studioViewModel.resolveCapabilityResult(pendingId, entry)
            }
        },
        onInvokeTool = { toolName, argumentsJson, kind ->
            val pendingId = studioViewModel.appendPendingCapability(
                kind = kind,
                title = toolName
            )
            chatCapabilitiesViewModel.invokeTool(
                toolName = toolName,
                argumentsJson = argumentsJson,
                agent = agentsState.activeAgent,
                isMcp = kind == CapabilityKind.MCP,
                sessionId = studioState.activeSessionId
            ) { entry ->
                studioViewModel.resolveCapabilityResult(pendingId, entry)
            }
        },
        onPingMcp = chatCapabilitiesViewModel::pingMcpServer,
        onApprove = studioViewModel::approveApproval,
        onReject = studioViewModel::rejectApproval,
        // P4: the tapped approval block's OWN id — the retry re-executes
        // exactly that block's message, never "the last approved one".
        onRetryAfterApproval = { approvalId ->
            studioViewModel.retryAfterApproval(
                approvalId = approvalId,
                agent = agentsState.activeAgent
            )
        },
        onGrantAlways = studioViewModel::grantAlwaysForApproval,
        // ARTIFACT CANVAS (§10): the conversation's artifact cards open the
        // scope-aware preview surface (pane at expanded width, sheet below).
        onOpenArtifact = studioViewModel::openArtifact,
        // CLOSURE §8 + FIW Stage 1: the message action opens the naming
        // dialog; the confirm rides the REAL versioned-artifact pipeline
        // (linked to the producing turn, card rendered in the timeline).
        onRequestResultSave = studioViewModel::requestResultSave,
        onConfirmResultSave = { studioViewModel.confirmResultSave(it) },
        onDismissResultSave = studioViewModel::dismissResultSaveDialog,
        // FIW Stage 1: the attachment OPEN cycle — the chips open the shared
        // canvas (real artifact row → preview/edit/version/export/share).
        onOpenAttachment = studioViewModel::openChatAttachment,
        // FIW Stage 1: the artifact EXPORT cycle — SAF save-to-device with
        // the REAL bytes read through the scope-aware service.
        onExportActiveArtifact = {
            studioViewModel.exportActiveArtifact(
                onReady = { name, mime, bytes ->
                    pendingDeviceExport = PendingDeviceExport(name, mime, bytes)
                },
                onUnavailable = studioViewModel::reportArtifactExchangeUnavailable
            )
        },
        // FIW Stage 1: the artifact SHARE cycle — a REAL file stream through
        // the FileProvider (containment-checked inside the helper).
        onShareActiveArtifact = {
            val activeArtifact = studioState.activeArtifact
            val projectId = projectsState.currentProject?.id
            if (activeArtifact == null || projectId == null) {
                studioViewModel.reportArtifactExchangeUnavailable(
                    "لا يوجد مخرج مفتوح للمشاركة، أو لا يوجد مشروع نشط يحدد نطاق الملف."
                )
            } else {
                val shareIntent = com.example.presentation.ui.components.SandboxFileExchange
                    .buildShareIntent(
                        context = shareContext,
                        projectId = projectId,
                        relativePath = activeArtifact.storageUri,
                        mimeType = activeArtifact.mimeType,
                        title = activeArtifact.name
                    )
                if (shareIntent == null) {
                    studioViewModel.reportArtifactExchangeUnavailable(
                        "تعذّر الوصول إلى ملف المخرج داخل مساحة المشروع — ربما حُذف أو تغيّر نطاقه."
                    )
                } else {
                    shareContext.startActivity(
                        android.content.Intent.createChooser(shareIntent, "مشاركة المخرج")
                    )
                }
            }
        },
        // CLOSURE §8: the artifact canvas's full version lifecycle.
        onLoadArtifactVersions = studioViewModel::loadArtifactVersions,
        onSaveArtifactVersion = { studioViewModel.saveArtifactCanvasVersion(it) },
        onRollbackArtifactVersion = studioViewModel::rollbackArtifactVersion,
        onCloseArtifact = studioViewModel::closeArtifact,
        onRequestArtifactEdit = studioViewModel::requestArtifactEdit,
        // ROUND-3 (the silent no-response): the conversation-level error
        // banner's dismiss path — the message channel finally has a surface.
        onDismissError = studioViewModel::dismissError,
        // ROUND-3 (the silent picker): a SAF picker that cannot even LAUNCH
        // (ROM without a DocumentsUI activity) now surfaces on the composer's
        // honest attachment-error line instead of dying as a log line.
        onPickLaunchFailed = { reason ->
            chatCapabilitiesViewModel.reportPickLaunchFailure(reason)
        },
        // FIW Stage 1: session-package import entry (pane + sheet).
        onImportSessionPackage = {
            sessionImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        },
        modifier = modifier
    )

    // ------------------------------------------------------------------
    // FIW Stage 1 — the export FORMAT PICKER: one honest dialog offering
    // the real capabilities (TXT/Markdown/JSON transcript + the importable
    // package + the platform share route). No format is offered that the
    // service cannot render.
    // ------------------------------------------------------------------
    sessionExportFormatTarget?.let { targetSessionId ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { sessionExportFormatTarget = null },
            title = { androidx.compose.material3.Text("تصدير الجلسة") },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        text = "اختر صيغة التصدير — سيُحفظ الملف على الجهاز عبر منتقي النظام.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.TextButton(
                        onClick = {
                            sessionExportFormatTarget = null
                            runSessionExport(targetSessionId, com.example.application.transfer.SessionExportService.Format.TXT)
                        },
                        modifier = Modifier.testTag("export_format_txt")
                    ) { androidx.compose.material3.Text("نص عادي (TXT)") }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            sessionExportFormatTarget = null
                            runSessionExport(targetSessionId, com.example.application.transfer.SessionExportService.Format.MARKDOWN)
                        },
                        modifier = Modifier.testTag("export_format_md")
                    ) { androidx.compose.material3.Text("Markdown — نص منسّق مع المرفقات والمصادر") }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            sessionExportFormatTarget = null
                            runSessionExport(targetSessionId, com.example.application.transfer.SessionExportService.Format.JSON)
                        },
                        modifier = Modifier.testTag("export_format_json")
                    ) { androidx.compose.material3.Text("JSON — التمثيل القانوني الكامل") }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            sessionExportFormatTarget = null
                            sessionsViewModel.exportSessionPackage(
                                targetSessionId,
                                onReady = { _, fileName, content, skipped ->
                                    pendingDeviceExport = PendingDeviceExport(
                                        fileName, "application/json", content.toByteArray()
                                    )
                                    if (skipped.isNotEmpty()) {
                                        studioViewModel.reportArtifactExchangeUnavailable(
                                            "الحزمة لا تتضمن محتوى ${skipped.size} مخرجاً (تجاوز حدود الحجم) — سيُصدَّر باقياهها."
                                        )
                                    }
                                },
                                onUnavailable = { reason ->
                                    studioViewModel.reportArtifactExchangeUnavailable(reason)
                                }
                            )
                        },
                        modifier = Modifier.testTag("export_format_package")
                    ) { androidx.compose.material3.Text("حزمة قابلة للاستيراد — تشمل المخرجات ومرفقاتها") }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            sessionExportFormatTarget = null
                            // The secondary route: the platform share sheet with
                            // the SAME canonical Markdown the service renders.
                            val context = shareContext
                            sessionsViewModel.exportSession(
                                targetSessionId,
                                com.example.application.transfer.SessionExportService.Format.MARKDOWN,
                                onReady = { title, _, _, markdown ->
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/markdown"
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, title)
                                        putExtra(android.content.Intent.EXTRA_TEXT, markdown)
                                    }
                                    context.startActivity(
                                        android.content.Intent.createChooser(send, "مشاركة الجلسة")
                                    )
                                },
                                onUnavailable = { sessionsViewModel.reportExportUnavailable() }
                            )
                        },
                        modifier = Modifier.testTag("export_format_share")
                    ) { androidx.compose.material3.Text("مشاركة كنص عبر تطبيقات الجهاز") }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { sessionExportFormatTarget = null }
                ) { androidx.compose.material3.Text("إلغاء") }
            }
        )
    }
}

/**
 * FIW Stage 1/2: one pending save-to-device request (artifact export,
 * attachment export, session export) — the CreateDocument launcher's
 * payload. The bytes are the REAL file content read through the
 * scope-aware services BEFORE the picker opened.
 */
private data class PendingDeviceExport(
    val fileName: String,
    val mime: String,
    val bytes: ByteArray
)
