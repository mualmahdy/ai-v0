package com.example.presentation.ui.screens.tasks

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.task.TaskId
import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.StepStatus
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import com.example.presentation.ui.components.DonutChart
import com.example.presentation.ui.components.SectionHeader
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * TasksScreen — the real Workflow Builder + execution console
 * ============================================================================
 *
 * Previously a hardcoded 3-step demo. Now a REAL plan constructor: dynamic
 * steps (add / remove / reorder), per-step agent role, dependency wiring
 * (DAG edges), execution mode selection (sequential / DAG / fan-out),
 * CLIENT-SIDE cycle validation before submission, quick templates, and an
 * enriched execution report with a completion donut. Everything feeds the
 * real WorkflowEngine + WorkflowPersistenceService backend.
 */

/** Immutable builder step — copy-on-write updates trigger recomposition. */
private data class BuilderStep(
    val id: String,
    val description: String,
    val role: AgentRole,
    val dependencies: Set<String>
)

@Composable
fun TasksScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    var goal by remember { mutableStateOf("بناء ونشر وحدة معمارية متكاملة") }
    var executionMode by remember { mutableStateOf(ExecutionMode.DIRECTED_ACYCLIC_GRAPH) }
    val steps = remember {
        mutableStateListOf(
            BuilderStep("step_1_plan", "تحليل المتطلبات والتخطيط المعماري للوحدة", AgentRole.PLANNER, setOf()),
            BuilderStep("step_2_code", "كتابة الشيفرات ونماذج النطاق ومنافذ Ports", AgentRole.CODER, setOf("step_1_plan")),
            BuilderStep("step_3_security", "التدقيق الأمني وفحص تنقيح البيانات والسياسات", AgentRole.SECURITY_GUARD, setOf("step_2_code"))
        )
    }

    fun updateStep(index: Int, transform: (BuilderStep) -> BuilderStep) {
        steps[index] = transform(steps[index])
    }

    val cycleError = detectCycle(steps.toList())
    val canExecute = goal.isNotBlank() && steps.isNotEmpty() && cycleError == null

    Column(modifier = modifier.testTag("screen_tasks_workflows")) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp)
        ) {
            SectionHeader(
                icon = Icons.Default.AccountTree,
                title = "منشئ خطط العمل",
                subtitle = "خطة ديناميكية: خطوات + أدوار وكلاء + تبعيات DAG — تُنفَّذ بالمحرك الحقيقي"
            )

            // ---- Goal + mode ----
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
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        label = { Text("هدف خطة العمل") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_workflow_goal")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "نمط التنفيذ",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExecutionMode.entries.forEach { mode ->
                            FilterChip(
                                selected = mode == executionMode,
                                onClick = { executionMode = mode },
                                label = {
                                    Text(
                                        when (mode) {
                                            ExecutionMode.SEQUENTIAL -> "تسلسلي"
                                            ExecutionMode.DIRECTED_ACYCLIC_GRAPH -> "رسم DAG"
                                            ExecutionMode.FAN_OUT_PARALLEL -> "توزيع متوازٍ"
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                steps.clear()
                                steps.addAll(
                                    listOf(
                                        BuilderStep("step_1_plan", "تحليل المتطلبات والتخطيط المعماري", AgentRole.PLANNER, setOf()),
                                        BuilderStep("step_2_code", "كتابة الشيفرات ونماذج النطاق", AgentRole.CODER, setOf("step_1_plan")),
                                        BuilderStep("step_3_security", "التدقيق الأمني وفحص السياسات", AgentRole.SECURITY_GUARD, setOf("step_2_code"))
                                    )
                                )
                                executionMode = ExecutionMode.DIRECTED_ACYCLIC_GRAPH
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("قالب الهيكلة الكاملة", style = MaterialTheme.typography.labelSmall) }
                        Button(
                            onClick = {
                                steps.clear()
                                steps.addAll(
                                    listOf(
                                        BuilderStep("step_1_research", "البحث وجمع المصادر", AgentRole.RESEARCHER, setOf()),
                                        BuilderStep("step_2_review", "مراجعة النتائج وتقييمها", AgentRole.REVIEWER, setOf("step_1_research"))
                                    )
                                )
                                executionMode = ExecutionMode.SEQUENTIAL
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("قالب بحث + مراجعة", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---- Steps ----
            steps.forEachIndexed { index, step ->
                StepEditorCard(
                    index = index,
                    step = step,
                    allSteps = steps.toList(),
                    isFirst = index == 0,
                    isLast = index == steps.lastIndex,
                    onDescriptionChange = { text -> updateStep(index) { it.copy(description = text) } },
                    onRoleChange = { role -> updateStep(index) { it.copy(role = role) } },
                    onToggleDependency = { depId ->
                        updateStep(index) { current ->
                            current.copy(
                                dependencies = if (depId in current.dependencies)
                                    current.dependencies - depId
                                else current.dependencies + depId
                            )
                        }
                    },
                    onMoveUp = {
                        if (index > 0) {
                            val moved = steps.removeAt(index)
                            steps.add(index - 1, moved)
                        }
                    },
                    onMoveDown = {
                        if (index < steps.lastIndex) {
                            val moved = steps.removeAt(index)
                            steps.add(index + 1, moved)
                        }
                    },
                    onRemove = { steps.removeAt(index) }
                )
            }

            // ---- Add step ----
            Surface(
                onClick = {
                    steps.add(
                        BuilderStep(
                            id = "step_${steps.size + 1}_${System.currentTimeMillis() % 1000}",
                            description = "",
                            role = AgentRole.GENERAL_ASSISTANT,
                            dependencies = setOf()
                        )
                    )
                },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("btn_add_workflow_step")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "إضافة خطوة جديدة",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (cycleError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "تبعية دائرية مكتشفة ($cycleError) — لا يمكن تنفيذ الرسم كـ DAG.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // ---- Execution report ----
            state.workflowReport?.let { report ->
                Spacer(modifier = Modifier.height(10.dp))
                WorkflowReportCard(report)
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // ---- Execute (pinned) ----
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Button(
                onClick = {
                    val plan = WorkflowPlan(
                        id = WorkflowId("wf_${System.currentTimeMillis()}"),
                        goal = goal.trim(),
                        executionMode = executionMode,
                        steps = steps.map { s ->
                            StepNode(
                                id = s.id,
                                taskId = TaskId("task_${s.id}"),
                                agentRole = s.role,
                                description = s.description.ifBlank { "${s.role.displayName} — خطوة ${s.id}" },
                                dependencies = s.dependencies.toSet()
                            )
                        }
                    )
                    viewModel.executeWorkflow(plan)
                },
                enabled = canExecute && !state.isExecutingWorkflow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("btn_execute_workflow_dag")
            ) {
                if (state.isExecutingWorkflow) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("جاري تنفيذ خطة العمل (${steps.size} خطوات)…")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تنفيذ خطة العمل")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step editor
// ---------------------------------------------------------------------------

@Composable
private fun StepEditorCard(
    index: Int,
    step: BuilderStep,
    allSteps: List<BuilderStep>,
    isFirst: Boolean,
    isLast: Boolean,
    onDescriptionChange: (String) -> Unit,
    onRoleChange: (AgentRole) -> Unit,
    onToggleDependency: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("workflow_step_${step.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = "خطوة ${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onMoveUp, enabled = !isFirst, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = "أعلى",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onMoveDown, enabled = !isLast, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = "أسفل",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp).testTag("btn_remove_step_${step.id}")) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "إزالة",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = step.description,
                onValueChange = onDescriptionChange,
                label = { Text("وصف الخطوة") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Role dropdown
            var roleMenuOpen by remember { mutableStateOf(false) }
            Surface(
                onClick = { roleMenuOpen = true },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Timeline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "الوكيل: ${step.role.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(expanded = roleMenuOpen, onDismissRequest = { roleMenuOpen = false }) {
                    AgentRole.entries.forEach { role ->
                        DropdownMenuItem(
                            text = { Text(role.displayName) },
                            onClick = {
                                onRoleChange(role)
                                roleMenuOpen = false
                            }
                        )
                    }
                }
            }

            // Dependencies (edges from other steps)
            val candidates = allSteps.filter { it.id != step.id }
            if (candidates.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "يعتمد على:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    candidates.take(4).forEach { candidate ->
                        FilterChip(
                            selected = candidate.id in step.dependencies,
                            onClick = { onToggleDependency(candidate.id) },
                            label = {
                                Text(
                                    candidate.id.substringBefore("_"),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Report
// ---------------------------------------------------------------------------

@Composable
private fun WorkflowReportCard(report: com.example.domain.core.workflow.WorkflowExecutionReport) {
    val isSuccess = report.overallOutcome is com.example.domain.core.Outcome.Success
    val isDegraded = report.overallOutcome is com.example.domain.core.Outcome.Degraded
    val completed = report.stepStatuses.values.count {
        it == StepStatus.COMPLETED || it == StepStatus.DEGRADED
    }
    val completionFraction = if (report.stepStatuses.isEmpty()) null
    else completed.toFloat() / report.stepStatuses.size

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSuccess) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
            else if (isDegraded) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutChart(
                    fraction = completionFraction,
                    centerValue = "${completed}/${report.stepStatuses.size}",
                    centerLabel = "خطوات مكتملة",
                    diameter = 84.dp,
                    ringTint = if (isSuccess) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isSuccess -> "اكتملت الخطة بنجاح كامل"
                            isDegraded -> "اكتملت مع تدهور تشغيلي"
                            else -> "فشل تنفيذ المخطط"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "الهدف: ${report.goal}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2
                    )
                    Text(
                        text = "المدة: ${report.totalDurationMs / 1000.0}s • الرموز: ${report.totalTokensConsumed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            report.stepStatuses.forEach { (stepId, status) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (icon, tint) = when (status) {
                        StepStatus.COMPLETED -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.tertiary
                        StepStatus.DEGRADED -> Icons.Default.WarningAmber to MaterialTheme.colorScheme.secondary
                        StepStatus.FAILED -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
                        StepStatus.RUNNING -> Icons.Default.Schedule to MaterialTheme.colorScheme.primary
                        else -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stepId,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    StatusBadge(
                        text = when (status) {
                            StepStatus.PENDING -> "معلّق"
                            StepStatus.RUNNING -> "قيد التنفيذ"
                            StepStatus.COMPLETED -> "مكتمل"
                            StepStatus.DEGRADED -> "مكتمل بتدهور"
                            StepStatus.FAILED -> "فاشل"
                            StepStatus.SKIPPED -> "متجاوز"
                        },
                        tint = tint
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Cycle detection (client-side DAG validation)
// ---------------------------------------------------------------------------

private fun detectCycle(steps: List<BuilderStep>): String? {
    val ids = steps.map { it.id }.toSet()
    // Unknown dependency references are dropped silently here (engine is the
    // authority); we only detect true cycles.
    val edges = steps.associate { it.id to it.dependencies.filter { d -> d in ids }.toSet() }
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()

    fun dfs(node: String): String? {
        if (node in visiting) return node
        if (node in visited) return null
        visiting.add(node)
        for (dep in edges[node].orEmpty()) {
            dfs(dep)?.let { return it }
        }
        visiting.remove(node)
        visited.add(node)
        return null
    }

    for (id in ids) {
        dfs(id)?.let { return "عبر الخطوة $it" }
    }
    return null
}
