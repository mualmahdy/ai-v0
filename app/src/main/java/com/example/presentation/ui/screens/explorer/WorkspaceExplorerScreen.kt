package com.example.presentation.ui.screens.explorer

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.session.ChatMode
import com.example.presentation.ui.navigation.WorkspaceRoutes
import com.example.presentation.viewmodel.FilesViewModel
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * WorkspaceExplorerScreen — UNIFIED OBJECT EXPLORER
 * ============================================================================
 *
 * ONE place discovers every workspace object and its live count. Every row
 * deep-links into the owning surface; counts are BACKEND TRUTH (Room-backed
 * flows), nothing is fabricated.
 *
 * ADR-6 slice 1 (Design Closure 2026 UI-redesign track) — REDESIGNED:
 *  - rows are grouped into three labeled sections (ذكاء التنفيذ / الموارد
 *    والأدوات / المعرفة والمحتوى / الخطط والجلسات) instead of one flat list;
 *  - each row carries a count chip on the trailing edge + a chevron
 *    affordance (RTL);
 *  - the FILES count now comes from the extracted FilesViewModel (the
 *    workspaceFiles field left the shared UiState).
 */
@Composable
fun WorkspaceExplorerScreen(
    viewModel: MainViewModel,
    filesViewModel: FilesViewModel,
    // ADR-6 slice 2: the durable-session registry list (the sessions row
    // count/subtitle) — read from the sessions feature VM, its owner.
    sessionsViewModel: com.example.presentation.viewmodel.SessionsViewModel,
    // ADR-6 slice 3: the knowledge feature state (the RAG row count + the
    // semantic-readiness subtitle) — read from the knowledge feature VM,
    // its owner.
    knowledgeViewModel: com.example.presentation.viewmodel.KnowledgeViewModel,
    // ADR-6 slice 5: the providers feature state (the models row subtitle +
    // the resources row count) — read from the providers feature VM, its
    // owner (same pattern as the files/sessions/knowledge rows).
    providersViewModel: com.example.presentation.viewmodel.ProvidersViewModel,
    // ADR-6 slice 6: the workflows feature state (the plans row count +
    // the resumable subtitle) — read from the workflows feature VM, its
    // owner (same pattern as the files/sessions/knowledge/providers rows).
    workflowsViewModel: com.example.presentation.viewmodel.WorkflowsViewModel,
    // ADR-6 slice 7: the agents feature state (the agents row count + the
    // active-agent subtitle) — read from the agents feature VM, its owner.
    agentsViewModel: com.example.presentation.viewmodel.AgentsViewModel,
    // ADR-6 slice 7: the extensions feature state (the tools row counts) —
    // read from the extensions feature VM, its owner.
    extensionsViewModel: com.example.presentation.viewmodel.ExtensionsViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val providersState by providersViewModel.state.collectAsState()
    val filesState by filesViewModel.state.collectAsState()
    val sessionsState by sessionsViewModel.state.collectAsState()
    val knowledgeState by knowledgeViewModel.state.collectAsState()
    val workflowsState by workflowsViewModel.state.collectAsState()
    // ADR-6 slice 7: the agents + extensions features' own flows (the
    // catalog + ecosystem lists left the shared UiState).
    val agentsState by agentsViewModel.state.collectAsState()
    val extensionsState by extensionsViewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp)
            .testTag("screen_workspace_explorer")
    ) {
        Text(
            text = "مستكشف مساحة العمل",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Text(
            text = "كل كائنات مساحة العمل الحالية في مكان واحد — الوكلاء، النماذج، الموارد، الأدوات، المعرفة، الملفات، خطط العمل والجلسات.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))

        val llmResources = providersState.materializedResources.filter {
            it.resourceType == ResourceType.LLM &&
                (it.lifecycleState == ResourceLifecycleState.ENABLED ||
                    it.lifecycleState == ResourceLifecycleState.ACTIVE)
        }

        // ===================== Section 1: ذكاء التنفيذ =====================
        ExplorerSectionLabel("ذكاء التنفيذ")
        ExplorerRow(
            icon = Icons.Default.Psychology,
            title = "الوكلاء",
            count = agentsState.availableAgents.size,
            countLabel = "وكيل",
            subtitle = "في السجل الدائم (نشط: ${agentsState.activeAgent?.identity?.name ?: "—"})",
            route = WorkspaceRoutes.STUDIO,
            onNavigate = onNavigate,
            testTag = "explorer_agents"
        )
        ExplorerRow(
            icon = Icons.Default.Dns,
            title = "النماذج (LLM)",
            count = llmResources.size,
            countLabel = "نموذج",
            subtitle = if (llmResources.isEmpty()) "لا نماذج مفعّلة — اربط مزوداً أولاً"
            else llmResources.joinToString(", ") { it.metadata["displayName"] ?: it.metadata["offeringId"] ?: it.resourceId.value }.take(80),
            route = WorkspaceRoutes.PROVIDERS,
            onNavigate = onNavigate,
            testTag = "explorer_models"
        )

        // ===================== Section 2: الموارد والأدوات =====================
        ExplorerSectionLabel("الموارد والأدوات")
        ExplorerRow(
            icon = Icons.Default.Storage,
            title = "الموارد المادية (Runtime)",
            count = providersState.materializedResources.size,
            countLabel = "مورد",
            subtitle = "مسجّل — LLM / بحث / تضمين / أدوات",
            route = WorkspaceRoutes.PROVIDERS,
            onNavigate = onNavigate,
            testTag = "explorer_resources"
        )
        ExplorerRow(
            icon = Icons.Default.Extension,
            title = "الأدوات و MCP والمهارات",
            count = extensionsState.mcpServers.size + extensionsState.skills.size + extensionsState.plugins.size,
            countLabel = "عنصر",
            subtitle = "${extensionsState.mcpServers.size} خادم MCP • ${extensionsState.skills.size} مهارة • ${extensionsState.plugins.size} إضافة",
            route = WorkspaceRoutes.EXTENSIONS,
            onNavigate = onNavigate,
            testTag = "explorer_extensions"
        )

        // ===================== Section 3: المعرفة والمحتوى =====================
        ExplorerSectionLabel("المعرفة والمحتوى")
        ExplorerRow(
            icon = Icons.Default.MenuBook,
            title = "المعرفة (RAG)",
            count = knowledgeState.knowledgeDocuments.size,
            countLabel = "مستند",
            subtitle = if (knowledgeState.semanticModelReady) "تضمين دلالي محلي جاهز" else "تضمين معجمي (النموذج الدلالي غير مُجهّز)",
            route = WorkspaceRoutes.KNOWLEDGE,
            onNavigate = onNavigate,
            testTag = "explorer_knowledge"
        )
        ExplorerRow(
            icon = Icons.Default.Folder,
            title = "الملفات",
            count = filesState.files.size,
            countLabel = "ملف",
            subtitle = "في ملعب مساحة العمل",
            route = WorkspaceRoutes.FILES,
            onNavigate = onNavigate,
            testTag = "explorer_files"
        )

        // ===================== Section 4: الخطط والجلسات =====================
        ExplorerSectionLabel("الخطط والجلسات")
        ExplorerRow(
            icon = Icons.Default.AccountTree,
            title = "المهام وخطط العمل",
            count = workflowsState.workflowLibrary.size,
            countLabel = "خطة",
            subtitle = "${workflowsState.resumableWorkflows.size} تنفيذ قابل للاستئناف",
            route = WorkspaceRoutes.TASKS,
            onNavigate = onNavigate,
            testTag = "explorer_workflows"
        )
        ExplorerRow(
            icon = Icons.Default.Schedule,
            title = "الجلسات (محادثات دائمة)",
            count = sessionsState.sessions.size,
            countLabel = "جلسة",
            subtitle = when {
                sessionsState.sessions.isEmpty() -> "لا جلسات بعد"
                else -> {
                    val quick = sessionsState.sessions.count { it.mode == ChatMode.QUICK_CHAT }
                    "${quick} محادثة سريعة • ${sessionsState.sessions.sumOf { it.turnCount }} دورة"
                }
            },
            route = WorkspaceRoutes.STUDIO,
            onNavigate = onNavigate,
            testTag = "explorer_sessions"
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ExplorerSectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
    )
}

@Composable
private fun ExplorerRow(
    icon: ImageVector,
    title: String,
    count: Int,
    countLabel: String,
    subtitle: String,
    route: String,
    onNavigate: (String) -> Unit,
    testTag: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onNavigate(route) }
            .testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = countLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.ChevronLeft,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
