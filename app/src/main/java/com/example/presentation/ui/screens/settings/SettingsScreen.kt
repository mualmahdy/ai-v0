package com.example.presentation.ui.screens.settings

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Radar
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.AutonomyPolicy
import com.example.infrastructure.persistence.AppDatabase
import com.example.presentation.ui.components.InfoRow
import com.example.presentation.ui.components.SectionHeader
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.viewmodel.MainViewModel
import com.example.presentation.viewmodel.SettingsViewModel

/**
 * ============================================================================
 * SettingsScreen — policies, workspaces, semantic engine, about
 * ============================================================================
 *
 * ADR-6 slice 1 (Design Closure 2026 UI-redesign track) — REDESIGNED on the
 * decomposed state:
 *
 *  - the WORKSPACE MANAGER (list / switch / create / per-workspace network
 *    policy / authoritative autonomy policy) now lives in the extracted
 *    SettingsViewModel (mutations route to WorkspaceRuntimeService);
 *  - the SESSION execution policies and the semantic-model provisioning stay
 *    on the shared MainViewModel state (documented next-slice deferral);
 *  - HONESTY FIX: the About card previously hardcoded "Room v12" while the
 *    database was already at v17 — it now reads AppDatabase.SCHEMA_VERSION,
 *    the single source of truth;
 *  - every policy option row carries a plain-language hint of what the
 *    policy actually governs (was a bare radio list).
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    settingsViewModel: SettingsViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val allWorkspaces by settingsViewModel.allWorkspaces.collectAsState()
    val activeWorkspace by settingsViewModel.activeWorkspace.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    var createWorkspaceOpen by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    LaunchedEffect(settingsState.errorMessage) {
        settingsState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            settingsViewModel.dismissError()
        }
    }

    androidx.compose.material3.Scaffold(
        modifier = modifier.testTag("screen_settings"),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ===================== Execution policies (session) =====================
            item {
                SectionHeader(
                    icon = Icons.Default.Security,
                    title = "سياسات التنفيذ (الجلسة)",
                    subtitle = "مدخلات حقيقية لمحرك القرار — تسري على الجلسة الحالية"
                )
            }
            item {
                SettingsCard {
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
                            selected = state.networkPolicy == policy,
                            onClick = { viewModel.setNetworkPolicy(policy) }
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
                }
            }

            // ===================== Workspace manager =====================
            item {
                SectionHeader(
                    icon = Icons.Default.Workspaces,
                    title = "إدارة مساحات العمل",
                    subtitle = "كل مساحة: ملعب + معرفة + ذاكرة + ميزانية مستقلة",
                    trailing = {
                        TextButton(onClick = { createWorkspaceOpen = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("جديدة")
                        }
                    }
                )
            }
            item {
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

            // Active workspace network policy quick control
            item {
                SettingsCard {
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

            // ===================== Semantic engine =====================
            item {
                SectionHeader(
                    icon = Icons.Default.Psychology,
                    title = "النموذج الدلالي المحلي",
                    subtitle = "ONNX MiniLM — تشغيل دلالي كامل على الجهاز"
                )
            }
            item {
                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("الحالة: ", style = MaterialTheme.typography.bodyMedium)
                        if (state.semanticModelReady) {
                            StatusBadge("جاهز", MaterialTheme.colorScheme.tertiary)
                        } else {
                            StatusBadge("غير مُجهّز", MaterialTheme.colorScheme.secondary)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::provisionLocalSemanticModel,
                        enabled = !state.isProvisioningSemanticModel && !state.semanticModelReady,
                        modifier = Modifier.fillMaxWidth().testTag("btn_settings_provision_semantic")
                    ) {
                        if (state.isProvisioningSemanticModel) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري التجهيز…")
                        } else {
                            Text(if (state.semanticModelReady) "النموذج جاهز" else "تجهيز النموذج (~23MB)")
                        }
                    }
                }
            }

            // ===================== About (honest) =====================
            item {
                SectionHeader(
                    icon = Icons.Default.Settings,
                    title = "حول التطبيق",
                    subtitle = "AI-V0 Ultimate — مرشح الإنتاج"
                )
            }
            item {
                SettingsCard {
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

            item {
                SettingsCard(container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "إدارة الموارد والمزودين",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "لربط مزوّد LLM جديد أو إدارة الموارد المفعّلة، انتقل إلى مركز المزودين.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { onNavigate(com.example.presentation.ui.navigation.WorkspaceRoutes.PROVIDERS) }) {
                        Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("فتح مركز المزودين")
                    }
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

/** Consistent settings card surface. */
@Composable
private fun SettingsCard(
    container: androidx.compose.ui.graphics.Color =
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun PolicyGroupHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
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
                    Icons.Default.ChevronLeft,
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
