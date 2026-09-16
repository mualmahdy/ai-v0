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
import androidx.compose.material.icons.filled.Psychology
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
import androidx.compose.material3.OutlinedButton
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
    // ADR-6 slice 6: the AGENT CATALOG still reads the MainViewModel (the
    // studio/catalog seam — the same composition StudioScreen uses), while
    // the workflow feature composes on its OWN feature VM.
    viewModel: MainViewModel,
    tasksViewModel: com.example.presentation.viewmodel.TasksViewModel? = null,
    // ADR-6 slice 6: the WORKFLOWS feature ViewModel — the plan builder,
    // the durable library and the resume surface left the shared UiState;
    // this screen composes on the feature's own state (its owner).
    workflowsViewModel: com.example.presentation.viewmodel.WorkflowsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val wfState by workflowsViewModel.state.collectAsState()

    // WORKFLOW BUILDER STATE LIVES IN THE VIEWMODEL (report gap: the
    // authored definition must survive navigation and be save/load/edit-able
    // as a durable library asset — Compose `remember` state previously died
    // with the screen).
    val builder = wfState.workflowBuilder
    val goal = builder.goal
    val executionMode = builder.executionMode
    val steps = builder.steps

    fun updateStep(index: Int, transform: (com.example.presentation.state.WorkflowBuilderStep) -> com.example.presentation.state.WorkflowBuilderStep) {
        workflowsViewModel.updateWorkflowStep(index, transform)
    }

    // (GAP-19 / ADR-6 step 6 — DELETED: the client-side detectCycle DFS.
    // The WorkflowEngine's validatePlan is the cycle authority
    // (CyclicDependencyDetected, test-pinned in WorkflowEngineTest) — a
    // SECOND detector in Compose could only drift from it. Cyclic plans
    // still surface honestly: executeWorkflow fails through the engine
    // and the report card renders the failure.)
    val canExecute = goal.isNotBlank() && steps.isNotEmpty()

    Column(modifier = modifier.testTag("screen_tasks_workflows")) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp)
        ) {
            // ---- GAP-11 (Design Closure 2026): the task board ----
            // A live, workspace-scoped list of task rows. Timed-out tasks
            // show as degraded + RESUMABLE (TIMED_OUT state, distinct from
            // user-input WAITING); resuming replays the idempotency ledger
            // instead of re-executing completed steps.
            if (tasksViewModel != null) {
                val board by tasksViewModel.board.collectAsState()
                val resumingTaskId by tasksViewModel.resumingTaskId.collectAsState()
                if (board.isNotEmpty()) {
                    TaskBoardCard(
                        board = board,
                        resumingTaskId = resumingTaskId,
                        onResume = tasksViewModel::resume
                    )
                }
            }

            SectionHeader(
                icon = Icons.Default.AccountTree,
                title = "منشئ خطط العمل",
                subtitle = "خطة ديناميكية: خطوات + وكلاء قانونيون + تبعيات DAG — تُنفَّذ بالمحرك الحقيقي"
            )

            // ---- Workflow library (durable, re-editable assets) ----
            WorkflowLibraryCard(
                library = wfState.workflowLibrary,
                onLoad = workflowsViewModel::loadWorkflowDefinitionIntoBuilder,
                onRun = workflowsViewModel::runWorkflowDefinition,
                onClone = workflowsViewModel::cloneWorkflowDefinition,
                onDelete = workflowsViewModel::deleteWorkflowDefinition
            )

            // ---- Resumable executions (durable resume) ----
            if (wfState.resumableWorkflows.isNotEmpty()) {
                ResumableWorkflowsCard(
                    resumable = wfState.resumableWorkflows,
                    onResume = workflowsViewModel::resumeWorkflow
                )
            }

            // ---- Goal + name + mode ----
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
                        value = builder.name,
                        onValueChange = workflowsViewModel::updateWorkflowName,
                        label = { Text("اسم خطة العمل (للمكتبة)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_workflow_name")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = goal,
                        onValueChange = workflowsViewModel::updateWorkflowGoal,
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
                                onClick = { workflowsViewModel.updateWorkflowMode(mode) },
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
                                workflowsViewModel.applyWorkflowTemplate(
                                    com.example.presentation.state.WorkflowBuilderState(
                                        name = "قالب الهيكلة الكاملة",
                                        goal = "بناء ونشر وحدة معمارية متكاملة",
                                        executionMode = ExecutionMode.DIRECTED_ACYCLIC_GRAPH,
                                        steps = listOf(
                                            com.example.presentation.state.WorkflowBuilderStep("step_1_plan", "تحليل المتطلبات والتخطيط المعماري", AgentRole.PLANNER, setOf()),
                                            com.example.presentation.state.WorkflowBuilderStep("step_2_code", "كتابة الشيفرات ونماذج النطاق", AgentRole.CODER, setOf("step_1_plan")),
                                            com.example.presentation.state.WorkflowBuilderStep("step_3_security", "التدقيق الأمني وفحص السياسات", AgentRole.SECURITY_GUARD, setOf("step_2_code"))
                                        )
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("قالب الهيكلة الكاملة", style = MaterialTheme.typography.labelSmall) }
                        Button(
                            onClick = {
                                workflowsViewModel.applyWorkflowTemplate(
                                    com.example.presentation.state.WorkflowBuilderState(
                                        name = "قالب بحث + مراجعة",
                                        goal = "بحث موثوق ومراجعة النتائج",
                                        executionMode = ExecutionMode.SEQUENTIAL,
                                        steps = listOf(
                                            com.example.presentation.state.WorkflowBuilderStep("step_1_research", "البحث وجمع المصادر", AgentRole.RESEARCHER, setOf()),
                                            com.example.presentation.state.WorkflowBuilderStep("step_2_review", "مراجعة النتائج وتقييمها", AgentRole.REVIEWER, setOf("step_1_research"))
                                        )
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("قالب بحث + مراجعة", style = MaterialTheme.typography.labelSmall) }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // SAVE AS LIBRARY ASSET (report gap: durable workflow
                    // library): the authored plan becomes a versioned,
                    // re-editable, clonable workspace asset.
                    OutlinedButton(
                        onClick = workflowsViewModel::saveWorkflowDefinition,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_save_workflow_definition"),
                        enabled = goal.isNotBlank() && steps.isNotEmpty()
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (builder.editingDefinitionId == null) "حفظ الخطة في المكتبة"
                            else "تحديث التعريف المحفوظ (إصدار جديد)",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---- Steps ----
            steps.forEachIndexed { index, step ->
                StepEditorCard(
                    index = index,
                    step = step,
                    allSteps = steps,
                    availableAgents = state.availableAgents,
                    isFirst = index == 0,
                    isLast = index == steps.lastIndex,
                    onDescriptionChange = { text -> updateStep(index) { it.copy(description = text) } },
                    onRoleChange = { role -> updateStep(index) { it.copy(role = role) } },
                    onToggleDependency = { depId ->
                        workflowsViewModel.toggleWorkflowStepDependency(index, depId)
                    },
                    onAssignAgent = { agentId -> workflowsViewModel.assignWorkflowStepAgent(index, agentId) },
                    onMoveUp = { workflowsViewModel.moveWorkflowStep(index, -1) },
                    onMoveDown = { workflowsViewModel.moveWorkflowStep(index, +1) },
                    onRemove = { workflowsViewModel.removeWorkflowStep(index) }
                )
            }

            // ---- Add step ----
            Surface(
                onClick = workflowsViewModel::addWorkflowStep,
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

            // ---- Execution report ----
            wfState.workflowReport?.let { report ->
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
                        id = WorkflowId(
                            builder.editingDefinitionId ?: "wf_${System.currentTimeMillis()}"
                        ),
                        goal = goal.trim(),
                        executionMode = executionMode,
                        steps = steps.map { s ->
                            StepNode(
                                id = s.id,
                                taskId = TaskId("task_${s.id}"),
                                agentRole = s.role,
                                description = s.description.ifBlank { "${s.role.displayName} — خطوة ${s.id}" },
                                dependencies = s.dependencies.toSet(),
                                assignedAgentId = s.assignedAgentId
                            )
                        }
                    )
                    workflowsViewModel.executeWorkflow(plan)
                },
                enabled = canExecute && !wfState.isExecutingWorkflow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("btn_execute_workflow_dag")
            ) {
                if (wfState.isExecutingWorkflow) {
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
    step: com.example.presentation.state.WorkflowBuilderStep,
    allSteps: List<com.example.presentation.state.WorkflowBuilderStep>,
    availableAgents: List<com.example.domain.core.agent.AgentDefinition>,
    isFirst: Boolean,
    isLast: Boolean,
    onDescriptionChange: (String) -> Unit,
    onRoleChange: (AgentRole) -> Unit,
    onToggleDependency: (String) -> Unit,
    onAssignAgent: (String?) -> Unit,
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
            // CANONICAL AGENT BINDING (report gap: steps execute through
            // DURABLE registry agents — assigned explicitly or resolved by
            // role; never synthetic throwaways).
            var agentMenuOpen by remember { mutableStateOf(false) }
            val assignedAgent = availableAgents.firstOrNull {
                it.identity.id.value == step.assignedAgentId
            }
            Column {
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
                            text = "الدور: ${step.role.displayName}",
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
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    onClick = { agentMenuOpen = true },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f),
                    modifier = Modifier.testTag("agent_binding_${step.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = assignedAgent?.let { "الوكيل: ${it.identity.name}" }
                                ?: "الوكيل: حسب الدور (تلقائي)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    DropdownMenu(expanded = agentMenuOpen, onDismissRequest = { agentMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("حسب الدور (تلقائي)") },
                            onClick = {
                                onAssignAgent(null)
                                agentMenuOpen = false
                            }
                        )
                        availableAgents.forEach { agent ->
                            DropdownMenuItem(
                                text = {
                                    Text("${agent.identity.name} (${agent.identity.role.displayName})")
                                },
                                onClick = {
                                    onAssignAgent(agent.identity.id.value)
                                    agentMenuOpen = false
                                }
                            )
                        }
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
// WORKFLOW LIBRARY + RESUMABLE (report gap-closure: durable assets)
// ---------------------------------------------------------------------------

/**
 * WORKFLOW LIBRARY (report gap: "workflow library/history NOT FIXED —
 * durable execution exists, but the USER-AUTHORED definition is not a
 * re-editable asset"): lists the saved definitions with version/run history;
 * each row supports load-into-builder (edit), run, clone and delete.
 */
@Composable
private fun WorkflowLibraryCard(
    library: List<com.example.domain.core.workflow.WorkflowDefinitionSummary>,
    onLoad: (String) -> Unit,
    onRun: (String) -> Unit,
    onClone: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("workflow_library_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.30f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "مكتبة خطط العمل المحفوظة",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (library.isEmpty()) {
                Text(
                    text = "لا خطط محفوظة بعد — حرّر الخطة أعلاه ثم اضغط «حفظ الخطة في المكتبة» لتصبح أصلاً دائماً قابلاً للتحرير والنسخ والتشغيل.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                library.forEach { def ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .testTag("workflow_definition_${def.workflowId.value}")
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = def.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = buildString {
                                            append(def.stepCount)
                                            append(" خطوات • إصدار ")
                                            append(def.version)
                                            append(" • تشغيلات ")
                                            append(def.runCount)
                                            append(" • ")
                                            append(def.executionMode.name)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onLoad(def.workflowId.value) }, modifier = Modifier.size(30.dp)) {
                                    Icon(
                                        Icons.Default.AccountTree,
                                        contentDescription = "تحرير",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { onRun(def.workflowId.value) }, modifier = Modifier.size(30.dp)) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "تشغيل",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                IconButton(onClick = { onClone(def.workflowId.value) }, modifier = Modifier.size(30.dp)) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "نسخ",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                IconButton(onClick = { onDelete(def.workflowId.value) }, modifier = Modifier.size(30.dp)) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "حذف",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * RESUMABLE WORKFLOWS (report gap: "Resume later"): executions left
 * RUNNING/PAUSED/COMPENSATING by a killed process — resume skips the
 * already-completed steps and continues from the durable checkpoint.
 */
@Composable
private fun ResumableWorkflowsCard(
    resumable: List<com.example.domain.core.workflow.ResumableWorkflow>,
    onResume: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("resumable_workflows_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "تنفيذات قابلة للاستئناف",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            resumable.forEach { wf ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = wf.plan.goal.ifBlank { wf.workflowId.value },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = "مكتمل: ${wf.completedStepIds.size}/${wf.plan.steps.size} خطوة — استئناف من نقطة التوقف",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { onResume(wf.workflowId.value) },
                        modifier = Modifier.testTag("btn_resume_workflow")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("استئناف", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * GAP-11 (Design Closure 2026) — the live task board card: workspace-scoped
 * task rows with state, degradation, token accounting, and a Resume action
 * for resumable states (TIMED_OUT / WAITING / FAILED / BLOCKED / DEGRADED).
 * A timed-out task shows as "منتهية المهلة" — DISTINCT from a task waiting
 * for user input (previously both were the invisible "WAITING").
 */
@Composable
private fun TaskBoardCard(
    board: List<com.example.presentation.viewmodel.TasksViewModel.TaskRow>,
    resumingTaskId: String?,
    onResume: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("task_board_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Timeline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "لوحة المهام",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            board.forEach { row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.title.ifBlank { row.id },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = taskRowSubline(row),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (row.isResumable) {
                        Button(
                            onClick = { onResume(row.id) },
                            enabled = resumingTaskId == null,
                            modifier = Modifier.testTag("btn_resume_task")
                        ) {
                            Icon(
                                if (resumingTaskId == row.id) Icons.Default.Schedule else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (resumingTaskId == row.id) "جارٍ…" else "استئناف",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Honest one-line status: state label + degradation + tokens. */
private fun taskRowSubline(
    row: com.example.presentation.viewmodel.TasksViewModel.TaskRow
): String {
    val stateLabel = when (row.state) {
        "TIMED_OUT" -> "منتهية المهلة — قابلة للاستئناف من آخر خطوة محفوظة"
        "WAITING" -> "بانتظار مدخل — قابلة للاستئناف"
        "RUNNING" -> "قيد التنفيذ"
        "COMPLETED" -> "مكتملة"
        "FAILED" -> "فاشلة — قابلة لإعادة المحاولة"
        "CANCELLED" -> "ملغاة"
        "BLOCKED" -> "محجوبة — قابلة للاستئناف"
        "DEGRADED" -> "متدهورة — قابلة للاستئناف"
        else -> row.state
    }
    val tokens = if (row.totalTokensConsumed > 0) " · ${row.totalTokensConsumed} توكن" else ""
    val degraded = if (row.isDegraded && row.state != "TIMED_OUT") " · متدهورة" else ""
    return stateLabel + tokens + degraded
}
