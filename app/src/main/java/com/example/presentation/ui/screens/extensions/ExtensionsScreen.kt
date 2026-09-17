package com.example.presentation.ui.screens.extensions

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.extension.SkillState
import com.example.presentation.ui.components.EmptyState
import com.example.presentation.ui.components.SectionHeader
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.viewmodel.ExtensionsViewModel

/**
 * ============================================================================
 * ExtensionsScreen — MCP + Skills + Plugins + Integrations, all OPERABLE
 * ============================================================================
 *
 * Previously read-mostly toggle lists. Now every backend capability has a
 * real control: PING an MCP server (health check + tool discovery), RUN an
 * executable skill with its parameters, CONNECT an integration with a token,
 * register new MCP servers — plus the honest enable/disable toggles.
 *
 * ADR-6 slice 7: the screen composes on the EXTENSIONS feature ViewModel
 * (its owner — the four ecosystem lists + the seven operations left the
 * shared UiState with the extraction), with a local dismissible diagnostic
 * banner for the direct skill-execution results (the slice-3/4 pattern).
 */
@Composable
fun ExtensionsScreen(
    viewModel: ExtensionsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var addMcpOpen by rememberSaveable { mutableStateOf(false) }
    var runSkillTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var connectIntegrationTarget by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = modifier) {
        // ADR-6 slice 7: the feature's own transient diagnostic banner
        // (skill-execution results) — the slice-3/4 local-banner pattern.
        state.diagnosticBanner?.let { banner ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = banner,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = viewModel::dismissDiagnosticBanner) {
                        Text("إخفاء")
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .testTag("screen_extensions"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        // ===================== MCP =====================
        item {
            SectionHeader(
                icon = Icons.Default.Public,
                title = "خوادم MCP (${state.mcpServers.size})",
                subtitle = "بروتوكول سياق النموذج — اكتشاف أدوات حي عند الفحص",
                trailing = {
                    IconButton(
                        onClick = { addMcpOpen = true },
                        modifier = Modifier.testTag("fab_add_mcp_server")
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "إضافة خادم",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }

        if (state.mcpServers.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Public,
                    title = "لا خوادم MCP مسجلة",
                    hint = "سجّل خادم MCP (SSE) ليكتشف النظام أدواته ويسجلها في مصفوفة القدرات عند نجاح الفحص."
                )
            }
        } else {
            items(state.mcpServers, key = { it.id }) { server ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = server.endpointUri,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            StatusBadge(
                                text = when (server.health.name) {
                                    "HEALTHY" -> "سليم"
                                    "DEGRADED" -> "متراجع"
                                    "UNAVAILABLE" -> "غير متاح"
                                    else -> "غير معروف"
                                },
                                tint = when (server.health.name) {
                                    "HEALTHY" -> MaterialTheme.colorScheme.tertiary
                                    "DEGRADED" -> MaterialTheme.colorScheme.secondary
                                    "UNAVAILABLE" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.pingMcpServer(server.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_ping_mcp_${server.id}")
                            ) {
                                Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("فحص واكتشاف الأدوات", style = MaterialTheme.typography.labelSmall)
                            }
                            Switch(
                                checked = server.isEnabled,
                                onCheckedChange = { viewModel.toggleMcpServer(server.id) },
                                modifier = Modifier.testTag("switch_mcp_${server.id}")
                            )
                        }
                        if (server.exposedTools.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "الأدوات المكتشفة: ${server.exposedTools.joinToString { it.name }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }

        // ===================== SKILLS =====================
        item {
            SectionHeader(
                icon = Icons.Default.Psychology,
                title = "المهارات القابلة للتنفيذ (${state.skills.size})",
                subtitle = "مهارات مدمجة مُدقّقة — تعمل مباشرة على ملعب مساحة العمل"
            )
        }

        if (state.skills.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Psychology,
                    title = "لا مهارات مثبتة",
                    hint = "تُدرج المهارات المدمجة تلقائياً عند تهيئة النظام."
                )
            }
        } else {
            items(state.skills, key = { it.id }) { skill ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (skill.isVerified) {
                                Icon(
                                    Icons.Default.Verified,
                                    contentDescription = "موثّقة",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = skill.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    text = "v${skill.version} • ${skill.category}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = skill.state == SkillState.ENABLED,
                                onCheckedChange = { viewModel.toggleSkill(skill.id) },
                                modifier = Modifier.testTag("switch_skill_${skill.id}")
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { runSkillTarget = skill.id },
                            enabled = skill.state == SkillState.ENABLED,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_run_skill_${skill.id}")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تنفيذ المهارة", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // ===================== PLUGINS =====================
        if (state.plugins.isNotEmpty()) {
            item {
                SectionHeader(
                    icon = Icons.Default.Extension,
                    title = "الإضافات (${state.plugins.size})"
                )
            }
            items(state.plugins, key = { it.id }) { plugin ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = plugin.name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "الثقة: ${plugin.trustLevel} • أدوات: ${plugin.declaredTools.joinToString().ifBlank { "—" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Switch(
                            checked = plugin.state != com.example.domain.core.extension.PluginState.DISABLED,
                            onCheckedChange = { viewModel.togglePlugin(plugin.id) },
                            modifier = Modifier.testTag("switch_plugin_${plugin.id}")
                        )
                    }
                }
            }
        }

        // ===================== INTEGRATIONS =====================
        item {
            SectionHeader(
                icon = Icons.Default.Link,
                title = "التكاملات الخارجية (${state.integrations.size})",
                subtitle = "ربط حي مع تحقق من الرمز"
            )
        }

        if (state.integrations.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Link,
                    title = "لا تكاملات مُعرّفة",
                    hint = "تكاملات GitHub / Drive / Notion تُفعّل برمز وصول بعد التحقق الحي."
                )
            }
        } else {
            items(state.integrations, key = { it.id }) { integration ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = integration.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                integration.accountIdentifier?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            StatusBadge(
                                text = if (integration.isConnected) "متصل" else "غير متصل",
                                tint = if (integration.isConnected) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                        if (integration.supportedOperations.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "العمليات: ${integration.supportedOperations.joinToString()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!integration.isConnected) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { connectIntegrationTarget = integration.id },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_connect_integration_${integration.id}")
                            ) {
                                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ربط برمز وصول", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }

    // ---- Add MCP dialog ----
    if (addMcpOpen) {
        AddMcpDialog(
            onConfirm = { name, uri ->
                viewModel.registerMcpServer(name, uri)
                addMcpOpen = false
            },
            onDismiss = { addMcpOpen = false }
        )
    }

    // ---- Run skill dialog ----
    // GAP-19 (ADR-6 step 6): the form GENERATES from the manifest's declared
    // parameters (no skill-id string branching).
    runSkillTarget?.let { skillId ->
        val manifest = state.skills.firstOrNull { it.id == skillId }
        RunSkillDialog(
            skillId = skillId,
            skillName = manifest?.name ?: skillId,
            parameters = manifest?.parameters ?: emptyList(),
            onConfirm = { params ->
                viewModel.executeSkillDirectly(skillId, params)
                runSkillTarget = null
            },
            onDismiss = { runSkillTarget = null }
        )
    }

    // ---- Connect integration dialog ----
    connectIntegrationTarget?.let { integrationId ->
        ConnectIntegrationDialog(
            integrationName = state.integrations.firstOrNull { it.id == integrationId }?.name ?: integrationId,
            onConfirm = { token ->
                viewModel.connectIntegration(integrationId, token)
                connectIntegrationTarget = null
            },
            onDismiss = { connectIntegrationTarget = null }
        )
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun AddMcpDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var uri by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسجيل خادم MCP", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم الخادم") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_mcp_name")
                )
                OutlinedTextField(
                    value = uri,
                    onValueChange = { uri = it },
                    label = { Text("عنوان URI") },
                    placeholder = { Text("https://example.com/mcp/sse") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_mcp_uri")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), uri.trim()) },
                enabled = name.isNotBlank() && uri.isNotBlank()
            ) {
                Text("تسجيل", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun RunSkillDialog(
    skillId: String,
    skillName: String,
    parameters: List<com.example.domain.core.extension.SkillParameterDefinition>,
    onConfirm: (Map<String, Any?>) -> Unit,
    onDismiss: () -> Unit
) {
    // GAP-19 (ADR-6 step 6): the form GENERATES from the manifest's DECLARED
    // parameters (previously hardcoded skillId.contains("scaffold") branches
    // — a new skill could never expose its parameters without UI code).
    val values = remember(parameters) {
        mutableMapOf<String, String>().also { map ->
            parameters.forEach { p -> map[p.name] = p.defaultValue ?: "" }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تنفيذ: $skillName", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (parameters.isEmpty()) {
                    Text(
                        text = "هذه المهارة لا تعلن معاملات — ستُنفَّذ مباشرة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                parameters.forEach { param ->
                    val current = values[param.name] ?: ""
                    OutlinedTextField(
                        value = current,
                        onValueChange = { values[param.name] = it },
                        label = { Text(param.label) },
                        singleLine = !param.isMultiline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (param.isMultiline) Modifier.heightIn(min = 120.dp) else Modifier
                            )
                            .testTag("input_skill_${param.name}")
                    )
                    param.description?.let { hint ->
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(values.toMap())
                },
                enabled = parameters.all { p -> !p.isRequired || (values[p.name] ?: "").isNotBlank() }
            ) {
                Text("تنفيذ", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun ConnectIntegrationDialog(
    integrationName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var token by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ربط: $integrationName", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("رمز الوصول (Token)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_integration_token")
                )
                Text(
                    text = "يجري النظام تحققاً حياً من الرمز قبل تفعيل التكامل — الرفض يُعرض بصدق.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(token.trim()) },
                enabled = token.isNotBlank()
            ) {
                Text("تحقق وربط", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
