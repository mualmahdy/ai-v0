@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.presentation.ui.screens.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.domain.core.extension.McpServerDescriptor
import com.example.domain.core.extension.SkillManifest
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolParameter
import com.example.presentation.state.CapabilityKind
import com.example.presentation.state.ChatCapabilityCategory
import com.example.presentation.state.ChatCapabilityItem
import com.example.presentation.state.ChatCapabilityKey
import com.example.presentation.state.ChatCapabilityStatus
import com.example.presentation.state.ChatInvocationCodec

/**
 * ============================================================================
 * ChatCapabilityMenu — the composer "+" entry point (CHAT CAPABILITIES Task
 * 2 §3/§4; FUNCTIONAL CLOSURE Phase 1 §17/§18/§19)
 * ============================================================================
 *
 * A CATEGORIZED, progressive-disclosure sheet — never a giant flat list:
 *
 *   الملفات والوسائط:  إرفاق ملف / إرفاق مجلد / تحليل صورة (Vision)
 *   المعرفة:           استرجاع قاعدة المعرفة / بحث ذكي
 *   الذكاء:            المهارات / الأدوات / خوادم MCP
 *
 * Availability is the PLATFORM policy (§4): Available rows are clickable;
 * Unavailable rows stay VISIBLE but faded with the REAL reason; Planned rows
 * (only genuinely planned capabilities like Vision) show "قريباً" — never a
 * "قريباً" for a temporary provider/network/dependency problem.
 *
 * FUNCTIONAL CLOSURE (Phase 1): every run form produces a VALIDATED, TYPED
 * payload through the pure [ChatInvocationCodec] BEFORE anything is invoked:
 *  - §17 skills: defaults are visible AND ride the payload; optional blanks
 *    are omitted;
 *  - §18 tools: enum → selection, boolean → switch, number → numeric
 *    validation, object/array → validated JSON, required → validation,
 *    optional → omission;
 *  - §19 MCP: malformed JSON is a VISIBLE error that blocks the invoke —
 *    never a silent conversion to empty arguments.
 */
@Composable
fun ChatCapabilityMenu(
    capabilities: List<ChatCapabilityItem>,
    isInvoking: Boolean,
    onDismiss: () -> Unit,
    onCapabilityClick: (ChatCapabilityKey) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("capability_menu_sheet")
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Text(
                text = "القدرات",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            ChatCapabilityCategory.entries.forEach { category ->
                val rows = capabilities.filter { it.category == category }
                if (rows.isEmpty()) return@forEach
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                rows.forEach { item -> CapabilityRow(item = item, onClick = { onCapabilityClick(item.key) }) }
            }
            if (isInvoking) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("جارٍ التنفيذ…", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** One capability row with the honest §4 availability treatment. */
@Composable
private fun CapabilityRow(
    item: ChatCapabilityItem,
    onClick: () -> Unit
) {
    val clickable = item.status == ChatCapabilityStatus.AVAILABLE
    Surface(
        onClick = if (clickable) onClick else ({}),
        enabled = clickable,
        shape = RoundedCornerShape(14.dp),
        color = if (clickable) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .testTag("capability_${item.key.name}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = capabilityIcon(item.key),
                contentDescription = null,
                tint = if (clickable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (item.status == ChatCapabilityStatus.PLANNED) {
                        "${item.title} — قريباً"
                    } else {
                        item.title
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (clickable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    }
                )
                Text(
                    text = item.reason ?: item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.reason != null && item.status != ChatCapabilityStatus.AVAILABLE) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2
                )
            }
            if (item.isDegraded && clickable) {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = "متاح بنمط تراجعي",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun capabilityIcon(key: ChatCapabilityKey): ImageVector = when (key) {
    ChatCapabilityKey.ATTACH_FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
    ChatCapabilityKey.ATTACH_FOLDER -> Icons.Default.Folder
    ChatCapabilityKey.VISION_ANALYSIS -> Icons.Default.Visibility
    ChatCapabilityKey.KNOWLEDGE_RETRIEVAL -> Icons.Default.School
    ChatCapabilityKey.SEARCH_INTELLIGENCE -> Icons.Default.Search
    ChatCapabilityKey.SKILLS -> Icons.Default.Bolt
    ChatCapabilityKey.TOOLS -> Icons.Default.SettingsEthernet
    ChatCapabilityKey.MCP_SERVERS -> Icons.Default.Link
}

/**
 * §11 — the SEARCH invocation surface: a query in, a readable result out
 * (sources are part of the conversation block, not this sheet).
 */
@Composable
fun ChatSearchSheet(
    isInvoking: Boolean,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("search_sheet")) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            Text("بحث ذكي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "بحث متعدد المصادر مع ترتيب ومراجع — النتيجة تظهر داخل المحادثة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("عن ماذا تبحث؟") },
                modifier = Modifier.fillMaxWidth().testTag("search_query_field")
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                TextButton(
                    onClick = { onRun(query) },
                    enabled = query.isNotBlank() && !isInvoking,
                    modifier = Modifier.testTag("btn_run_search")
                ) {
                    if (isInvoking) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("تشغيل البحث")
                }
                TextButton(onClick = onDismiss) { Text("إلغاء") }
            }
        }
    }
}

/**
 * §12 — the KNOWLEDGE retrieval surface (real RAG pipeline; honest state).
 */
@Composable
fun ChatKnowledgeSheet(
    isInvoking: Boolean,
    documentCount: Int,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("knowledge_sheet")) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            Text("استرجاع من قاعدة المعرفة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (documentCount > 0) {
                    "بحث في $documentCount مستنداً مفهرساً — النتيجة تظهر داخل المحادثة."
                } else {
                    "لا توجد مستندات مفهرسة بعد."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("ما الذي تريد استرجاعه من المعرفة؟") },
                modifier = Modifier.fillMaxWidth().testTag("knowledge_query_field")
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                TextButton(
                    onClick = { onRun(query) },
                    enabled = query.isNotBlank() && !isInvoking && documentCount > 0,
                    modifier = Modifier.testTag("btn_run_knowledge")
                ) {
                    if (isInvoking) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("تشغيل الاسترجاع")
                }
                TextButton(onClick = onDismiss) { Text("إلغاء") }
            }
        }
    }
}

/**
 * §8 — the SKILL browser (FUNCTIONAL CLOSURE §17): the REAL extension-registry
 * manifests (never a duplicate catalog), availability from the real skill
 * state, and execution through the governed tool path. The run form's payload
 * is built by the pure [ChatInvocationCodec.buildSkillArguments] — the
 * manifest's DEFAULT values are visible in the fields AND ride the payload
 * (a default the user did not override is still sent); an optional parameter
 * with no value is OMITTED (never a fake empty string). Validation errors are
 * INLINE and block the run.
 */
@Composable
fun ChatSkillBrowserSheet(
    skills: List<SkillManifest>,
    isInvoking: Boolean,
    onDismiss: () -> Unit,
    onRunSkill: (SkillManifest, String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("skills_sheet")) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .heightIn(max = 520.dp)
        ) {
            Text("المهارات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "مهارات مسجلة فعلياً — التنفيذ عبر مسار الأدوات المحكوم.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (skills.isEmpty()) {
                Text(
                    "لا توجد مهارات مفعّلة حالياً.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(skills, key = { it.id }) { skill ->
                        SkillRunCard(skill = skill, isInvoking = isInvoking, onRun = onRunSkill)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillRunCard(
    skill: SkillManifest,
    isInvoking: Boolean,
    onRun: (SkillManifest, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val parameterValues = remember { mutableStateMapOf<String, String>() }
    // FUNCTIONAL CLOSURE (§17): the inline validation error — visible, and
    // it BLOCKS the run (the payload never leaves with a violation).
    var validationError by remember { mutableStateOf<String?>(null) }
    val enabled = skill.state == com.example.domain.core.extension.SkillState.ENABLED
    Surface(
        onClick = { if (enabled) expanded = !expanded },
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        },
        modifier = Modifier.fillMaxWidth().testTag("skill_card_${skill.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(skill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        skill.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
            if (expanded && enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                skill.parameters.forEach { parameter ->
                    OutlinedTextField(
                        value = parameterValues[parameter.name] ?: parameter.defaultValue ?: "",
                        onValueChange = {
                            parameterValues[parameter.name] = it
                            validationError = null
                        },
                        label = { Text(parameter.label) },
                        placeholder = parameter.description?.let { { Text(it) } },
                        minLines = if (parameter.isMultiline) 3 else 1,
                        isError = validationError != null && parameter.isRequired &&
                            (parameterValues[parameter.name] ?: parameter.defaultValue ?: "").isBlank(),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .testTag("skill_param_${parameter.name}")
                    )
                }
                validationError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("skill_validation_error")
                    )
                }
                Row {
                    TextButton(
                        onClick = {
                            when (val outcome = ChatInvocationCodec.buildSkillArguments(
                                skill.parameters,
                                parameterValues.toMap()
                            )) {
                                is ChatInvocationCodec.BuildOutcome.Ok ->
                                    onRun(skill, outcome.argumentsJson)
                                is ChatInvocationCodec.BuildOutcome.Invalid ->
                                    validationError = outcome.reason
                            }
                        },
                        enabled = !isInvoking,
                        modifier = Modifier.testTag("btn_run_skill_${skill.id}")
                    ) {
                        Text("تشغيل المهارة")
                    }
                }
            }
        }
    }
}

/**
 * §9 — the TOOL browser (FUNCTIONAL CLOSURE §18): the REAL runtime-registry
 * declarations with a DECLARATION-DERIVED TYPED form — enum → selection,
 * boolean → switch, number → numeric validation, object/array → validated
 * JSON, required → validation, optional → omission. The payload is built by
 * the pure [ChatInvocationCodec.buildToolArguments] and carries REAL types
 * (numbers as numbers, booleans as booleans) — the UI never lets a payload
 * that violates the declaration reach the backend.
 */
@Composable
fun ChatToolBrowserSheet(
    tools: List<ToolDeclaration>,
    isInvoking: Boolean,
    onDismiss: () -> Unit,
    onRunTool: (ToolDeclaration, String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("tools_sheet")) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .heightIn(max = 520.dp)
        ) {
            Text("الأدوات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "أدوات مسجلة فعلياً — التنفيذ عبر بوابة القبول والتفويض نفسها.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (tools.isEmpty()) {
                Text(
                    "لا توجد أدوات مسجلة حالياً.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(tools, key = { it.name }) { tool ->
                        ToolRunCard(tool = tool, isInvoking = isInvoking, onRun = onRunTool)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRunCard(
    tool: ToolDeclaration,
    isInvoking: Boolean,
    onRun: (ToolDeclaration, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val parameterValues = remember { mutableStateMapOf<String, String>() }
    // FUNCTIONAL CLOSURE (§18): the inline validation error — visible, and
    // it BLOCKS the run.
    var validationError by remember { mutableStateOf<String?>(null) }
    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().testTag("tool_card_${tool.name}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(tool.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (tool.description.isNotBlank()) {
                Text(
                    tool.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                tool.parameters.forEach { parameter ->
                    TypedParameterField(
                        parameter = parameter,
                        value = parameterValues[parameter.name] ?: "",
                        onValueChange = {
                            parameterValues[parameter.name] = it
                            validationError = null
                        },
                        isError = validationError != null &&
                            parameter.isRequired &&
                            (parameterValues[parameter.name] ?: "").isBlank()
                    )
                }
                validationError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("tool_validation_error")
                    )
                }
                Row {
                    TextButton(
                        onClick = {
                            when (val outcome = ChatInvocationCodec.buildToolArguments(
                                tool,
                                parameterValues.toMap()
                            )) {
                                is ChatInvocationCodec.BuildOutcome.Ok ->
                                    onRun(tool, outcome.argumentsJson)
                                is ChatInvocationCodec.BuildOutcome.Invalid ->
                                    validationError = outcome.reason
                            }
                        },
                        enabled = !isInvoking,
                        modifier = Modifier.testTag("btn_run_tool_${tool.name}")
                    ) {
                        Text("تنفيذ الأداة")
                    }
                }
            }
        }
    }
}

/**
 * FUNCTIONAL CLOSURE (§18): the TYPED control a parameter's declaration
 * demands — never a plain text field for everything:
 *  - enum (declared values) → a dropdown SELECTION;
 *  - boolean → a switch;
 *  - number → a numeric keyboard + numeric validation;
 *  - object/array → a multiline JSON field (validated by the codec at run);
 *  - string → a text field. Required-ness shows as a "（مطلوب）" marker.
 */
@Composable
private fun TypedParameterField(
    parameter: ToolParameter,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean
) {
    val type = parameter.type.lowercase().trim()
    val requiredMarker = if (parameter.isRequired) " (مطلوب)" else " (اختياري)"
    val label = parameter.name + requiredMarker

    when {
        // §18: enum → SELECTION (no free-typing a value outside the set).
        parameter.enumValues.isNotEmpty() -> {
            var expanded by remember { mutableStateOf(false) }
            val selected = parameter.enumValues.firstOrNull { it == value }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .testTag("tool_param_${parameter.name}")
            ) {
                OutlinedTextField(
                    value = selected ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(label) },
                    placeholder = parameter.description.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    isError = isError,
                    modifier = Modifier.menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    parameter.enumValues.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onValueChange(option)
                                expanded = false
                            },
                            modifier = Modifier.testTag("tool_param_option_${parameter.name}_$option")
                        )
                    }
                }
            }
        }
        // §18: boolean → a BOOLEAN control (switch), tri-state absent — the
        // user's explicit true/false; blank optional stays omitted.
        type == "boolean" -> {
            val checked = value.toBooleanStrictOrNull()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .testTag("tool_param_${parameter.name}")
            ) {
                Switch(
                    checked = checked == true,
                    onCheckedChange = { onValueChange(if (it) "true" else "false") },
                    modifier = Modifier.testTag("tool_param_switch_${parameter.name}")
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    parameter.description.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        // §18: number → NUMERIC input (numeric keyboard + the codec's
        // numeric validation at run).
        type == "number" -> {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                placeholder = parameter.description.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                isError = isError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .testTag("tool_param_${parameter.name}")
            )
        }
        // §18: object/array → structured JSON input (multiline; the codec
        // validates the syntax before anything is invoked).
        type == "object" || type == "array" -> {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("$label — ${if (type == "object") "كائن JSON" else "مصفوفة JSON"}") },
                placeholder = { Text(if (type == "object") """{"key": "value"}""" else """["a", "b"]""") },
                isError = isError,
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .testTag("tool_param_${parameter.name}")
            )
        }
        // string (and any undeclared type stays a string — the declaration
        // is the truth).
        else -> {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                placeholder = parameter.description.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                isError = isError,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .testTag("tool_param_${parameter.name}")
            )
        }
    }
}

/**
 * §10 — the MCP browser: REAL discovery state (a server's tools appear only
 * after a successful handshake — the HEALTHY-only registration rule),
 * per-server ping (the existing discovery path), and tool invocation through
 * the governed boundary. No new MCP client — the sheet only surfaces the
 * existing abstractions.
 *
 * FUNCTIONAL CLOSURE (§19): the raw JSON arguments field is VALIDATED by the
 * pure [ChatInvocationCodec.parseMcpArgumentsJson] — malformed input shows a
 * VISIBLE error and BLOCKS the invoke; it can never be silently converted
 * into valid-empty arguments.
 */
@Composable
fun ChatMcpBrowserSheet(
    servers: List<McpServerDescriptor>,
    isDiscovering: Boolean,
    isInvoking: Boolean,
    onDismiss: () -> Unit,
    onPing: (String) -> Unit,
    onRunTool: (McpServerDescriptor, String, String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("mcp_sheet")) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .heightIn(max = 560.dp)
        ) {
            Text("خوادم MCP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "أدوات الخادم تظهر بعد اتصال ناجح (مصافحة فعلية) — الحالة معروضة كما هي.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (servers.isEmpty()) {
                Text(
                    "لا توجد خوادم MCP مسجلة.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(servers, key = { it.id }) { server ->
                        McpServerCard(
                            server = server,
                            isDiscovering = isDiscovering,
                            isInvoking = isInvoking,
                            onPing = onPing,
                            onRunTool = onRunTool
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun McpServerCard(
    server: McpServerDescriptor,
    isDiscovering: Boolean,
    isInvoking: Boolean,
    onPing: (String) -> Unit,
    onRunTool: (McpServerDescriptor, String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val healthy = server.health == HealthStatus.HEALTHY && server.isEnabled
    Surface(
        onClick = { if (healthy && server.exposedTools.isNotEmpty()) expanded = !expanded },
        enabled = healthy && server.exposedTools.isNotEmpty(),
        shape = RoundedCornerShape(14.dp),
        color = if (healthy) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        },
        modifier = Modifier.fillMaxWidth().testTag("mcp_card_${server.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (healthy) Icons.Default.Link else Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            !server.isEnabled -> "معطّل"
                            server.health == HealthStatus.HEALTHY ->
                                "متصل — ${server.exposedTools.size} أداة متاحة"
                            else -> "غير متصل — يتطلب اتصالاً (مصافحة) لاكتشاف الأدوات"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!healthy && server.isEnabled) {
                    TextButton(
                        onClick = { onPing(server.id) },
                        enabled = !isDiscovering,
                        modifier = Modifier.testTag("btn_ping_mcp_${server.id}")
                    ) {
                        if (isDiscovering) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        } else {
                            Text("الاتصال")
                        }
                    }
                }
            }
            if (expanded && healthy) {
                Spacer(modifier = Modifier.height(6.dp))
                server.exposedTools.forEach { mcpTool ->
                    var argsJson by remember { mutableStateOf("") }
                    // FUNCTIONAL CLOSURE (§19): the visible validation error —
                    // malformed JSON BLOCKS the invoke.
                    var argsError by remember { mutableStateOf<String?>(null) }
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            mcpTool.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("mcp_tool_${mcpTool.name}")
                        )
                        if (mcpTool.description.isNotBlank()) {
                            Text(
                                mcpTool.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedTextField(
                            value = argsJson,
                            onValueChange = {
                                argsJson = it
                                argsError = null
                            },
                            placeholder = { Text("""وسائط JSON (اختياري) — مثال: {"query": "…"}""") },
                            isError = argsError != null,
                            modifier = Modifier.fillMaxWidth().testTag("mcp_args_${mcpTool.name}")
                        )
                        argsError?.let { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("mcp_args_error_${mcpTool.name}")
                            )
                        }
                        TextButton(
                            onClick = {
                                when (val outcome = ChatInvocationCodec.parseMcpArgumentsJson(argsJson)) {
                                    is ChatInvocationCodec.McpArgsOutcome.Empty ->
                                        onRunTool(server, mcpTool.name, "{}")
                                    is ChatInvocationCodec.McpArgsOutcome.Ok ->
                                        onRunTool(server, mcpTool.name, outcome.rawJson)
                                    is ChatInvocationCodec.McpArgsOutcome.Malformed ->
                                        // §19: NEVER invoke with silently-emptied
                                        // arguments — the error is visible and
                                        // the invoke stays blocked.
                                        argsError = outcome.reason
                                }
                            },
                            enabled = !isInvoking,
                            modifier = Modifier.testTag("btn_run_mcp_${mcpTool.name}")
                        ) {
                            Text("استدعاء")
                        }
                    }
                }
            }
        }
    }
}
