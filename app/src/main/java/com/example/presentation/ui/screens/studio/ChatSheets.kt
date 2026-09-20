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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.application.agent.CanonicalAgentCatalog
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.session.ChatMode
import com.example.domain.core.task.AutonomyPolicy
import com.example.presentation.ui.components.TokenBudgetGauge

/**
 * ============================================================================
 * ChatSheets — the SECONDARY conversation surfaces (Chat Workspace Task 1
 * §6/§7/§8)
 * ============================================================================
 *
 * Everything that used to sit in cards ABOVE the transcript now lives behind
 * the header's two compact chips:
 *  - [ChatSettingsSheet]: the decision-engine inputs (network policy,
 *    autonomy policy — kept as SEPARATE scopes) + the live token budget +
 *    the honest session actions (new session / reset view);
 *  - [ChatContextSheet]: the conversation context (mode, exact model,
 *    agent catalog with builder/delete).
 */

/**
 * §6: the advanced-controls sheet. The scopes stay separate: the NETWORK
 * policy is a session execution input; the AUTONOMY policy is the workspace
 * governance contract — one sheet, two clearly-labelled controls, never
 * merged into a single "policy" impression.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsSheet(
    networkPolicy: NetworkPolicy,
    autonomyPolicy: AutonomyPolicy,
    consumedTokens: Int,
    remainingBudget: Int,
    totalSession: Int,
    onNetworkPolicy: (NetworkPolicy) -> Unit,
    onAutonomyPolicy: (AutonomyPolicy) -> Unit,
    onNewSession: () -> Unit,
    onResetView: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .testTag("chat_settings_sheet")
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(com.example.R.string.studio_advanced_controls),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = androidx.compose.ui.res.stringResource(com.example.R.string.studio_advanced_controls_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            PolicyDropdown(
                icon = Icons.Default.Dns,
                label = "سياسة الشبكة",
                options = NetworkPolicy.entries.map { it.displayName to it },
                selected = networkPolicy,
                onSelect = onNetworkPolicy,
                modifier = Modifier.fillMaxWidth(),
                tag = "policy_network"
            )
            Spacer(modifier = Modifier.height(8.dp))
            PolicyDropdown(
                icon = Icons.Default.Security,
                label = "سياسة الاستقلالية (حوكمة مساحة العمل)",
                options = AutonomyPolicy.entries.map { it.displayName to it },
                selected = autonomyPolicy,
                onSelect = onAutonomyPolicy,
                modifier = Modifier.fillMaxWidth(),
                tag = "policy_autonomy"
            )
            Spacer(modifier = Modifier.height(12.dp))

            TokenBudgetGauge(
                consumedTokens = consumedTokens,
                remainingBudget = remainingBudget,
                totalSession = totalSession
            )
            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "إجراءات الجلسة",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            // NEW SESSION: creates a real durable session immediately.
            OutlinedButton(
                onClick = onNewSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_sheet_new_session")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("جلسة جديدة (تُنشأ فوراً)")
            }
            Spacer(modifier = Modifier.height(6.dp))

            // RESET VIEW (§8): the honest label — clears THIS view only; the
            // durable history stays intact in the session browser.
            OutlinedButton(
                onClick = onResetView,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_sheet_reset_view")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("إعادة تعيين العرض")
            }
            Text(
                text = "يبدأ العرض محادثة جديدة عند رسالتك التالية. السجل الدائم لا يُحذف — تصفّحه من «الجلسات».",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * §7: the conversation-context sheet — the mode (Quick Chat / Agent), the
 * EXACT model binding (Quick Chat), or the canonical agent catalog
 * (Agent mode, with the durable builder + delete). One surface, one
 * selection concept; nothing duplicated on the main screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContextSheet(
    chatMode: ChatMode,
    onModeChange: (ChatMode) -> Unit,
    llmResources: List<ResourceRecord>,
    selectedModelResourceId: String?,
    selectedModelDisplayName: String?,
    onSelectModel: (String?, String?) -> Unit,
    agents: List<AgentDefinition>,
    activeAgentId: String?,
    onSelectAgent: (AgentDefinition) -> Unit,
    onDeleteAgentRequest: (String) -> Unit,
    onBuildAgent: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .testTag("chat_context_sheet")
        ) {
            Text(
                text = "سياق المحادثة",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            // ---- Mode (a semantic session boundary — §8) ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = chatMode == ChatMode.QUICK_CHAT,
                    onClick = { onModeChange(ChatMode.QUICK_CHAT) },
                    label = { Text("محادثة سريعة", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                )
                FilterChip(
                    selected = chatMode == ChatMode.AGENT,
                    onClick = { onModeChange(ChatMode.AGENT) },
                    label = { Text("وضع الوكيل", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                )
            }
            Text(
                text = "تبديل الوضع يبدأ جلسة جديدة (السجل الدائم السابق يبقى في متصفح الجلسات).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            when (chatMode) {
                ChatMode.QUICK_CHAT -> {
                    Text(
                        text = "النموذج المرتبط بالمحادثة",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "اختيار تلقائي يعني أن طبقة القرار تُعيّن النموذج لكل رسالة.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ModelPickerList(
                        resources = llmResources,
                        selectedResourceId = selectedModelResourceId,
                        selectedDisplayName = selectedModelDisplayName,
                        onSelect = onSelectModel
                    )
                }

                ChatMode.AGENT -> {
                    AgentCatalogSection(
                        agents = agents,
                        activeAgentId = activeAgentId,
                        onSelect = onSelectAgent,
                        onDeleteRequest = onDeleteAgentRequest,
                        onBuildAgent = onBuildAgent
                    )
                }
            }
        }
    }
}

/**
 * The exact-model list (§7): Auto (the decision layer) + every
 * ENABLED/ACTIVE LLM resource, with the current binding visibly marked.
 */
@Composable
private fun ModelPickerList(
    resources: List<ResourceRecord>,
    selectedResourceId: String?,
    selectedDisplayName: String?,
    onSelect: (String?, String?) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            ModelPickerRowItem(
                title = "اختيار تلقائي (طبقة القرار)",
                subtitle = "يُعيَّن النموذج ديناميكياً حسب المهمة والسياسة",
                selected = selectedResourceId == null,
                onClick = { onSelect(null, null) },
                tag = "model_option_auto"
            )
            resources.forEach { record ->
                val label = record.metadata["displayName"]
                    ?: record.metadata["offeringId"]
                    ?: record.resourceId.value
                ModelPickerRowItem(
                    title = label,
                    subtitle = buildString {
                        append(if (record.isLocal) "محلي" else "سحابي")
                        append(" • ")
                        append(record.providerId)
                    },
                    selected = record.resourceId.value == selectedResourceId,
                    onClick = { onSelect(record.resourceId.value, label) },
                    tag = "model_option_${record.resourceId.value}"
                )
            }
        }
    }
    if (selectedDisplayName != null && resources.none { it.resourceId.value == selectedResourceId }) {
        // The pinned model is no longer materialized — say so honestly
        // instead of silently showing "Auto".
        Text(
            text = "النموذج المثبّت («$selectedDisplayName») غير متاح حالياً في الموارد — سيُستخدم الاختيار التلقائي حتى يعود.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun ModelPickerRowItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Dns,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "النموذج الحالي",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** The agent catalog section (AGENT mode): chips + builder + delete. */
@Composable
private fun AgentCatalogSection(
    agents: List<AgentDefinition>,
    activeAgentId: String?,
    onSelect: (AgentDefinition) -> Unit,
    onDeleteRequest: (String) -> Unit,
    onBuildAgent: () -> Unit
) {
    val canonicalIds = remember {
        CanonicalAgentCatalog.defaults.map { it.identity.id.value }.toSet()
    }
    val active = agents.firstOrNull { it.identity.id.value == activeAgentId }

    Row(
        modifier = Modifier.fillMaxWidth(),
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
        contentPadding = PaddingValues(horizontal = 2.dp),
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

    // The ONE active-agent summary (no duplication above the transcript).
    active?.let { agent ->
        Spacer(modifier = Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
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
                    Text(
                        text = "سقف الرموز: ${agent.budget.maxTokens}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (agent.identity.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = agent.identity.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3
                    )
                }
            }
        }
    }
}

internal fun roleIcon(role: AgentRole): ImageVector = when (role) {
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

/** A labelled policy dropdown (one scope per control — never merged). */
@Composable
private fun <T : Enum<T>> PolicyDropdown(
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = options.firstOrNull { it.second == selected }?.first ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
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

/**
 * The durable agent builder dialog (carried over from the old Studio surface
 * — §23 audit 2026: the user authors the execution contract).
 */
@Composable
fun AgentBuilderDialog(
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

/** §23 (audit 2026 — Agent Builder capabilities): multi-select chips. */
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
