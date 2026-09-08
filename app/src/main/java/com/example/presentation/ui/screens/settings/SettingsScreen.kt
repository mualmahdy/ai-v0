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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.AutonomyPolicy
import com.example.presentation.ui.components.InfoRow
import com.example.presentation.ui.components.SectionHeader
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * SettingsScreen — policies, workspaces, semantic engine, about
 * ============================================================================
 *
 * The settings surface the app NEVER had: execution-time network & autonomy
 * policies (real decision-engine inputs), the full multi-workspace manager
 * (list / switch / create / per-workspace network policy — backend
 * capability that had NO UI), the local semantic model status, and an
 * honest "about" section describing the real architecture.
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val allWorkspaces by viewModel.allWorkspaces.collectAsState()
    val activeWorkspace by viewModel.activeWorkspace.collectAsState()
    var createWorkspaceOpen by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.testTag("screen_settings"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ===================== Execution policies =====================
        item {
            SectionHeader(
                icon = Icons.Default.Security,
                title = "سياسات التنفيذ",
                subtitle = "مدخلات حقيقية لمحرك القرار — تسري على الجلسة الحالية"
            )
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "سياسة الشبكة",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    NetworkPolicy.entries.forEach { policy ->
                        PolicyOptionRow(
                            label = policy.displayName,
                            selected = state.networkPolicy == policy,
                            onClick = { viewModel.setNetworkPolicy(policy) }
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "سياسة الاستقلالية",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    AutonomyPolicy.entries.forEach { policy ->
                        PolicyOptionRow(
                            label = policy.displayName,
                            selected = state.autonomyPolicy == policy,
                            onClick = { viewModel.setAutonomyPolicy(policy) }
                        )
                    }
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
            allWorkspaces.forEach { workspace ->
                val isActive = workspace.id == activeWorkspace?.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp)
                        .clickable(enabled = !isActive) { viewModel.switchWorkspace(workspace.id) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = workspace.name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            if (isActive) {
                                StatusBadge("النشطة", MaterialTheme.colorScheme.primary, filled = false)
                            }
                        }
                        if (workspace.description.isNotBlank()) {
                            Text(
                                text = workspace.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        InfoRow(
                            label = "سياسة الشبكة",
                            value = workspace.networkPolicy.displayName.substringBefore(" (")
                        )
                        InfoRow(
                            label = "المشروع",
                            value = if (workspace.activeProjectId > 0) "معرّف #${workspace.activeProjectId}" else "لا مشروع مرتبط"
                        )
                    }
                }
            }
        }

        // Active workspace network policy quick control
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "سياسة شبكة مساحة العمل النشطة (تُحفَظ دائماً)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    NetworkPolicy.entries.forEach { policy ->
                        PolicyOptionRow(
                            label = policy.displayName,
                            selected = activeWorkspace?.networkPolicy == policy,
                            onClick = { viewModel.updateWorkspaceNetworkPolicy(policy) }
                        )
                    }
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
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
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
        }

        // ===================== About =====================
        item {
            SectionHeader(
                icon = Icons.Default.Settings,
                title = "حول التطبيق",
                subtitle = "AI-V0 Ultimate — مرشح الإنتاج"
            )
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    InfoRow(label = "التطبيق", value = "AI-V0 Ultimate")
                    InfoRow(label = "الهوية", value = "مساحة عمل ذكية ذاتية متعددة الوكلاء")
                    InfoRow(label = "المعمارية", value = "Clean Architecture (Domain / Application / Ports / Infrastructure)")
                    InfoRow(label = "قاعدة البيانات", value = "Room v12 — كل حالة حقيقية قابلة للتحقق")
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
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
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
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    if (createWorkspaceOpen) {
        CreateWorkspaceSettingsDialog(
            onConfirm = { name, description ->
                viewModel.createWorkspace(name, description)
                createWorkspaceOpen = false
            },
            onDismiss = { createWorkspaceOpen = false }
        )
    }
}

@Composable
private fun PolicyOptionRow(
    label: String,
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
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
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
