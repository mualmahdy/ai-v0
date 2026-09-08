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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * WorkspaceExplorerScreen — UNIFIED OBJECT EXPLORER (report gap: "unified
 * resource Explorer NOT FIXED — everything is scattered across screens")
 * ============================================================================
 *
 * ONE place discovers every workspace object and its live count:
 *
 *   Workspace Explorer
 *   ├ Agents          (durable registry)
 *   ├ Models          (enabled LLM resources — exact-boundable)
 *   ├ Resources       (all materialized resource types)
 *   ├ Tools/MCP/Skills (extensions ecosystem)
 *   ├ Knowledge       (RAG documents)
 *   ├ Files           (workspace sandbox files)
 *   ├ Tasks/Workflows (builder + durable library + resumable)
 *   └ Sessions        (durable conversation sessions)
 *
 * Every row deep-links into the owning surface. Counts are BACKEND TRUTH
 * (Room-backed flows through the UiState) — nothing is fabricated.
 */
@Composable
fun WorkspaceExplorerScreen(
    viewModel: MainViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

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
        Spacer(modifier = Modifier.height(10.dp))

        val llmResources = state.materializedResources.filter {
            it.resourceType == ResourceType.LLM &&
                (it.lifecycleState == ResourceLifecycleState.ENABLED ||
                    it.lifecycleState == ResourceLifecycleState.ACTIVE)
        }

        ExplorerRow(
            icon = Icons.Default.Psychology,
            title = "الوكلاء",
            subtitle = "${state.availableAgents.size} وكيل في السجل الدائم (نشط: ${state.activeAgent?.identity?.name ?: "—"})",
            route = WorkspaceRoutes.STUDIO,
            onNavigate = onNavigate,
            testTag = "explorer_agents"
        )
        ExplorerRow(
            icon = Icons.Default.Dns,
            title = "النماذج (LLM)",
            subtitle = if (llmResources.isEmpty()) "لا نماذج مفعّلة — اربط مزوداً أولاً"
            else "${llmResources.size} نموذج مفعّل (${llmResources.joinToString(", ") { it.metadata["displayName"] ?: it.metadata["offeringId"] ?: it.resourceId.value }.take(80)})",
            route = WorkspaceRoutes.PROVIDERS,
            onNavigate = onNavigate,
            testTag = "explorer_models"
        )
        ExplorerRow(
            icon = Icons.Default.Storage,
            title = "الموارد المادية (Runtime)",
            subtitle = "${state.materializedResources.size} مورد مسجّل — LLM / بحث / تضمين / أدوات",
            route = WorkspaceRoutes.PROVIDERS,
            onNavigate = onNavigate,
            testTag = "explorer_resources"
        )
        ExplorerRow(
            icon = Icons.Default.Extension,
            title = "الأدوات و MCP والمهارات",
            subtitle = "${state.mcpServers.size} خادم MCP • ${state.skills.size} مهارة • ${state.plugins.size} إضافة",
            route = WorkspaceRoutes.EXTENSIONS,
            onNavigate = onNavigate,
            testTag = "explorer_extensions"
        )
        ExplorerRow(
            icon = Icons.Default.MenuBook,
            title = "المعرفة (RAG)",
            subtitle = "${state.knowledgeDocuments.size} مستند معرفي" +
                if (state.semanticModelReady) " • تضمين دلالي محلي جاهز" else "",
            route = WorkspaceRoutes.KNOWLEDGE,
            onNavigate = onNavigate,
            testTag = "explorer_knowledge"
        )
        ExplorerRow(
            icon = Icons.Default.Folder,
            title = "الملفات",
            subtitle = "${state.workspaceFiles.size} ملف في صندوق مساحة العمل",
            route = WorkspaceRoutes.FILES,
            onNavigate = onNavigate,
            testTag = "explorer_files"
        )
        ExplorerRow(
            icon = Icons.Default.AccountTree,
            title = "المهام وخطط العمل",
            subtitle = "${state.workflowLibrary.size} خطة محفوظة • ${state.resumableWorkflows.size} تنفيذ قابل للاستئناف",
            route = WorkspaceRoutes.TASKS,
            onNavigate = onNavigate,
            testTag = "explorer_workflows"
        )
        ExplorerRow(
            icon = Icons.Default.Schedule,
            title = "الجلسات (محادثات دائمة)",
            subtitle = when {
                state.sessions.isEmpty() -> "لا جلسات بعد"
                else -> {
                    val quick = state.sessions.count { it.mode == ChatMode.QUICK_CHAT }
                    "${state.sessions.size} جلسة محفوظة ($quick محادثة سريعة) • ${state.sessions.sumOf { it.turnCount }} دورة"
                }
            },
            route = WorkspaceRoutes.STUDIO,
            onNavigate = onNavigate,
            testTag = "explorer_sessions"
        )
    }
}

@Composable
private fun ExplorerRow(
    icon: ImageVector,
    title: String,
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
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
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
        }
    }
}
