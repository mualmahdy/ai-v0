package com.example.presentation.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.AutonomyPolicy
import com.example.infrastructure.persistence.AppDatabase
import com.example.presentation.ui.components.InfoRow
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.ui.navigation.WorkspaceRoutes
import com.example.presentation.viewmodel.MainViewModel
import com.example.presentation.viewmodel.SettingsViewModel

/**
 * ============================================================================
 * SettingsScreen — the 12-CATEGORY settings hub (UI Design Closure, phase
 * C — defect D-07)
 * ============================================================================
 *
 * The previous flat four-section list is organized into the package's
 * twelve categories. Every category is honest about what it actually
 * governs:
 *
 *  - Categories with REAL controls (chat & AI policies / semantic engine,
 *    workspace manager, network policy, providers, tools & extensions,
 *    about) expose exactly their existing mutations — nothing new is
 *    faked, nothing existing is deleted;
 *  - Categories whose capability is NOT a settings surface (account,
 *    appearance, language & accessibility, budget & usage, data) render an
 *    HONEST state note — "لا شيء يُضبط هنا حاليًا" or a deep link to the
 *    real owner surface — instead of decorative toggles (the no-fabricated-
 *    data rule).
 *
 * One category expands at a time (accordion). All new copy is
 * resource-backed Arabic (the D-12 rule).
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    settingsViewModel: SettingsViewModel,
    onNavigate: (String) -> Unit,
    /** ADR-6 slice 2: the SESSION policy value (owned by StudioViewModel). */
    sessionNetworkPolicy: NetworkPolicy,
    /** ADR-6 slice 2: delegates the mutation to the studio feature VM. */
    onSessionNetworkPolicy: (NetworkPolicy) -> Unit,
    /**
     * ADR-6 slice 3: the SEMANTIC ENGINE readiness (owned by
     * KnowledgeViewModel) — passed as value + lambda, same delegation as
     * the session policy above.
     */
    semanticModelReady: Boolean,
    /** ADR-6 slice 3: honest in-flight flag for the provisioning button. */
    isProvisioningSemanticModel: Boolean,
    /** ADR-6 slice 3: delegates the mutation to the knowledge feature VM. */
    onProvisionSemanticModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val allWorkspaces by settingsViewModel.allWorkspaces.collectAsState()
    val activeWorkspace by settingsViewModel.activeWorkspace.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    var createWorkspaceOpen by rememberSaveable { mutableStateOf(false) }
    // One expanded category at a time (accordion).
    var expandedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(settingsState.errorMessage) {
        settingsState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            settingsViewModel.dismissError()
        }
    }

    Scaffold(
        modifier = modifier.testTag("screen_settings"),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ===================== 1. Account (honest) =====================
            item {
                SettingsCategory(
                    icon = Icons.Default.AccountCircle,
                    title = stringResource(R.string.settings_cat_account),
                    tag = "settings_cat_account",
                    expanded = expandedCategory == "account",
                    onToggle = { expandedCategory = if (expandedCategory == "account") null else "account" }
                ) {
                    HonestNote(text = stringResource(R.string.settings_cat_account_honest))
                }
            }

            // ===================== 2. Privacy & security =====================
            item {
                SettingsCategory(
                    icon = Icons.Default.Security,
                    title = stringResource(R.string.settings_cat_privacy),
                    tag = "settings_cat_privacy",
                    expanded = expandedCategory == "privacy",
                    onToggle = { expandedCategory = if (expandedCategory == "privacy") null else "privacy" }
                ) {
                    HonestNote(text = stringResource(R.string.settings_cat_privacy_body))
                }
            }

            // ===================== 3. Appearance =====================
            item {
                SettingsCategory(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.settings_cat_appearance),
                    tag = "settings_cat_appearance",
                    expanded = expandedCategory == "appearance",
                    onToggle = { expandedCategory = if (expandedCategory == "appearance") null else "appearance" }
                ) {
                    HonestNote(text = stringResource(R.string.settings_cat_appearance_body))
                }
            }

            // ===================== 4. Language & accessibility =====================
            item {
                SettingsCategory(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.settings_cat_language),
                    tag = "settings_cat_language",
                    expanded = expandedCategory == "language",
                    onToggle = { expandedCategory = if (expandedCategory == "language") null else "language" }
                ) {
                    HonestNote(text = stringResource(R.string.settings_cat_language_body))
                }
            }

            // ===================== 5. Chat & AI (real controls) =====================
            item {
                SettingsCategory(
                    icon = Icons.Default.Psychology,
                    title = stringResource(R.string.settings_cat_chat_ai),
                    tag = "settings_cat_chat_ai",
                    expanded = expandedCategory == "chat_ai",
                    onToggle = { expandedCategory = if (expandedCategory == "chat_ai") null else "chat_ai" }
                ) {
                    PolicyGroupHeader(
                        icon = Icons.Default.Language,
                        title = "سياسة الشبكة للجلسة"
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    NetworkPolicy.entries.forEach { policy ->
                        PolicyOptionRow(
                            label = policy.displayName,
                            hint = when {
                                !policy.allowsCloud -> "لا اتصال بأي مزود سحابي — محلي فقط"
                                policy == NetworkPolicy.CLOUD_FIRST -> "يُفضَّل السحابي عند توفره"
                                else -> "محلي أولاً ثم السحابي عند الحاجة"
                            },
                            selected = sessionNetworkPolicy == policy,
                            onClick = { onSessionNetworkPolicy(policy) }
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    PolicyGroupHeader(
                        icon = Icons.Default.Security,
                        title = "سياسة الاستقلالية (المساحة)"
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    AutonomyPolicy.entries.forEach { policy ->
                        PolicyOptionRow(
                            label = policy.displayName.substringBefore(" ("),
                            hint = policy.displayName.substringAfter("(").substringBefore(")"),
                            selected = state.autonomyPolicy == policy,
                            onClick = { settingsViewModel.setAutonomyPolicy(policy) }
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    PolicyGroupHeader(
                        icon = Icons.Default.Psychology,
                        title = "النموذج الدلالي المحلي"
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("الحالة: ", style = MaterialTheme.typography.bodyMedium)
                        if (semanticModelReady) {
                            StatusBadge("جاهز", MaterialTheme.colorScheme.tertiary)
                        } else {
                            StatusBadge("غير مُجهّز", MaterialTheme.colorScheme.secondary)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onProvisionSemanticModel,
                        enabled = !isProvisioningSemanticModel && !semanticModelReady,
                        modifier = Modifier.fillMaxWidth().testTag("btn_settings_provision_semantic")
                    ) {
                        if (isProvisioningSemanticModel) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري التجهيز…")
                        } else {
                            Text(if (semanticModelReady) "النموذج جاهز" else "تجهيز النموذج (~23MB)")
                        }
                    }
                }
            }

            // ===================== 6. Workspace (real controls) =====================
            item {
                SettingsCategory(
                    icon = Icons.Default.Workspaces,
                    title = stringResource(R.string.settings_cat_workspace),
                    tag = "settings_cat_workspace",
                    expanded = expandedCategory == "workspace",
                    onToggle = { expandedCategory = if (expandedCategory == "workspace") null else "workspace" },
                    trailing = {
                        TextButton(onClick = { createWorkspaceOpen = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("جديدة")
                        }
                    }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        allWorkspaces.forEach { workspace ->
                            val isActive = workspace.id == activeWorkspace?.id
                            WorkspaceCard(
                                workspaceName = workspace.name,
                                workspaceDescription = workspace.description,
                                networkPolicyLabel = workspace.networkPolicy.displayName.substringBefore(" ("),
                                projectLabel = if (workspace.activeProjectId > 0) "معرّف #${workspace.activeProjectId}" else "لا مشروع مرتبط",
                                isActive = isActive,
                                isSwitching = isActive.not() && settingsState.isSwitchingWorkspace,
                                onClick = { settingsViewModel.switchWorkspace(workspace.id) }
                            )
                        }
                    }
                }
            }

            // ===================== 7. Network (real controls) =====================
            item {
                SettingsCategory(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.settings_cat_network),
                    tag = "settings_cat_network",
                    expanded = expandedCategory == "network",
                    onToggle = { expandedCategory = if (expandedCategory == "network") null else "network" }
                ) {
                    Text(
                        text = "سياسة شبكة مساحة العمل النشطة (تُحفَظ دائماً)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    NetworkPolicy.entries.forEach { policy ->
                        PolicyOptionRow(
                            label = policy.displayName,
                            hint = "محفوظة في عمود مساحة العمل — تحكم كل عملاء الخروج",
                            selected = activeWorkspace?.networkPolicy == policy,
                            onClick = { settingsViewModel.updateWorkspaceNetworkPolicy(policy) }
                        )
                    }
                }
            }

            // ===================== 8. Budget & usage =====================
            item {
                SettingsCategory(
                    icon = Icons.Default.AttachMoney,
                    title = stringResource(R.string.settings_cat_budget),
                    tag = "settings_cat_budget",
                    expanded = expandedCategory == "budget",
                    onToggle = { expandedCategory = if (expandedCategory == "budget") null else "budget" }
                ) {
                    HonestNote(text = stringResource(R.string.settings_cat_budget_body))
                    DeepLinkRow(
                        label = stringResource(R.string.settings_cat_budget_open),
                        tag = "btn_settings_open_governance"
                    ) { onNavigate(WorkspaceRoutes.GOVERNANCE) }
                }
            }

            // ===================== 9. Providers =====================
            item {
                SettingsCategory(
                    icon = Icons.Default.Dns,
                    title = stringResource(R.string.settings_cat_providers),
                    tag = "settings_cat_providers",
                    expanded = expandedCategory == "providers",
                    onToggle = { expandedCategory = if (expandedCategory == "providers") null else "providers" }
                ) {
                    HonestNote(text = stringResource(R.string.settings_cat_providers_body))
                    DeepLinkRow(
                        label = stringResource(R.string.settings_cat_providers_open),
                        tag = "btn_settings_open_providers"
                    ) { onNavigate(WorkspaceRoutes.PROVIDERS) }
                }
            }

            // ===================== 10. Tools & extensions =====================
            item {
                SettingsCategory(
                    icon = Icons.Default.Construction,
                    title = stringResource(R.string.settings_cat_tools),
                    tag = "settings_cat_tools",
                    expanded = expandedCategory == "tools",
                    onToggle = { expandedCategory = if (expandedCategory == "tools") null else "tools" }
                ) {
                    HonestNote(text = stringResource(R.string.settings_cat_tools_body))
                    DeepLinkRow(
                        label = stringResource(R.string.settings_cat_tools_open),
                        tag = "btn_settings_open_extensions"
                    ) { onNavigate(WorkspaceRoutes.EXTENSIONS) }
                }
            }

            // ===================== 11. Data (honest) =====================
            item {
                SettingsCategory(
                    icon = Icons.Default.Download,
                    title = stringResource(R.string.settings_cat_data),
                    tag = "settings_cat_data",
                    expanded = expandedCategory == "data",
                    onToggle = { expandedCategory = if (expandedCategory == "data") null else "data" }
                ) {
                    HonestNote(text = stringResource(R.string.settings_cat_data_honest))
                }
            }

            // ===================== 12. About (real, honest) =====================
            item {
                SettingsCategory(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_cat_about),
                    tag = "settings_cat_about",
                    expanded = expandedCategory == "about",
                    onToggle = { expandedCategory = if (expandedCategory == "about") null else "about" }
                ) {
                    InfoRow(label = "التطبيق", value = "AI-V0 Ultimate")
                    InfoRow(label = "الهوية", value = "مساحة عمل ذكية ذاتية متعددة الوكلاء")
                    InfoRow(label = "المعمارية", value = "Clean Architecture (Domain / Application / Ports / Infrastructure)")
                    // TRUTH FIX (ADR-6 slice 1): reads the single source of
                    // truth — the card previously hardcoded "Room v12" while
                    // the database had already reached v17.
                    InfoRow(label = "قاعدة البيانات", value = "Room v${AppDatabase.SCHEMA_VERSION} — كل حالة حقيقية قابلة للتحقق")
                    InfoRow(label = "الخصوصية", value = "شاشة محمية (FLAG_SECURE) — لا لقطات للبيانات")
                    InfoRow(
                        label = "المحرك",
                        value = "حلقة قرار CBR-MDP + بوابات أمن وقدرة وميزانية قبل كل فعل"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "مبدأ الصدق: كل حالة غير معروفة تُعرض كـ «غير معروف» ولا تُفبرك أبداً؛ " +
                            "التعذّر يُعرض بسببه الحقيقي.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (createWorkspaceOpen) {
        CreateWorkspaceSettingsDialog(
            onConfirm = { name, description ->
                settingsViewModel.createWorkspace(name, description)
                createWorkspaceOpen = false
            },
            onDismiss = { createWorkspaceOpen = false }
        )
    }
}

// ---------------------------------------------------------------------------
// Category chrome
// ---------------------------------------------------------------------------

/**
 * One expandable settings category (accordion row). One category is open
 * at a time; the expand/collapse affordance is screen-reader described.
 */
@Composable
private fun SettingsCategory(
    icon: ImageVector,
    title: String,
    tag: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag(tag),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (trailing != null) {
                trailing()
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "إخفاء القسم" else "إظهار القسم",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                content()
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** An honest, non-fabricated state note for capability-less categories. */
@Composable
private fun HonestNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** A deep link to the surface that actually owns the capability. */
@Composable
private fun DeepLinkRow(label: String, tag: String, onClick: () -> Unit) {
    Spacer(modifier = Modifier.height(6.dp))
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// Existing sub-composables (kept from the slice-1 redesign)
// ---------------------------------------------------------------------------

@Composable
private fun PolicyGroupHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Redesigned workspace row: structured info + active badge + chevron. */
@Composable
private fun WorkspaceCard(
    workspaceName: String,
    workspaceDescription: String,
    networkPolicyLabel: String,
    projectLabel: String,
    isActive: Boolean,
    isSwitching: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(enabled = !isActive, onClick = onClick)
            .testTag("workspace_card_$workspaceName"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = workspaceName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isActive) {
                        StatusBadge("النشطة", MaterialTheme.colorScheme.primary, filled = false)
                    } else if (isSwitching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                if (workspaceDescription.isNotBlank()) {
                    Text(
                        text = workspaceDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBadge(networkPolicyLabel, MaterialTheme.colorScheme.secondary, filled = false)
                    StatusBadge(projectLabel, MaterialTheme.colorScheme.outline, filled = false)
                }
            }
            if (!isActive) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PolicyOptionRow(
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            if (hint.isNotBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CreateWorkspaceSettingsDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مساحة عمل جديدة", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("الاسم") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("الوصف (اختياري)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), description.trim()) },
                enabled = name.isNotBlank()
            ) { Text("إنشاء", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
