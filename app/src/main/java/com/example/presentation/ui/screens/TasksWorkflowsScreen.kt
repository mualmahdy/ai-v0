package com.example.presentation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.task.TaskId
import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.StepStatus
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import com.example.presentation.state.UiState
import com.example.presentation.viewmodel.MainViewModel

@Composable
fun TasksWorkflowsScreen(
    state: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val sampleWorkflowPlan = WorkflowPlan(
        id = WorkflowId("wf_scaffold_feature"),
        goal = "بناء ونشر وحدة معمارية متكاملة (Full Feature Scaffold DAG)",
        executionMode = ExecutionMode.DIRECTED_ACYCLIC_GRAPH,
        steps = listOf(
            StepNode(
                id = "step_1_plan",
                taskId = TaskId("task_plan_1"),
                agentRole = AgentRole.PLANNER,
                description = "1. تحليل المتطلبات والتخطيط المعماري للوحدة النمطية"
            ),
            StepNode(
                id = "step_2_code",
                taskId = TaskId("task_code_2"),
                agentRole = AgentRole.CODER,
                dependencies = setOf("step_1_plan"),
                description = "2. كتابة الشيفرات ونماذج النطاق ومنافذ Ports"
            ),
            StepNode(
                id = "step_3_security",
                taskId = TaskId("task_sec_3"),
                agentRole = AgentRole.SECURITY_GUARD,
                dependencies = setOf("step_2_code"),
                description = "3. التدقيق الأمني وفحص تنقيح البيانات والسياسات"
            )
        )
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("screen_tasks_workflows"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "إدارة المهام ومسارات العمل (DAG Workflows)",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "تنفيذ المخططات الموجهة غير الدائرية مع تتبع الحالات وإدارة التبعيات",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // DAG Execution Controller
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = sampleWorkflowPlan.goal,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "يتكون مسار العمل من 3 خطوات مترابطة وفق رسم بياني موجه غير دائري (DAG).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    sampleWorkflowPlan.steps.forEach { step ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = step.description,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (step.dependencies.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surface) {
                                    Text(
                                        text = "يعتمد على: ${step.dependencies.joinToString()}",
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.executeWorkflow(sampleWorkflowPlan) },
                        enabled = !state.isExecutingWorkflow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_execute_workflow_dag")
                    ) {
                        if (state.isExecutingWorkflow) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري تنفيذ مسار العمل...")
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تشغيل مسار العمل (Execute DAG)")
                        }
                    }
                }
            }
        }

        // Workflow Execution Report
        state.workflowReport?.let { report ->
            item {
                val isSuccess = report.overallOutcome is Outcome.Success
                val isDegraded = report.overallOutcome is Outcome.Degraded
                val statusText = when (report.overallOutcome) {
                    is Outcome.Success -> "اكتمل بنجاح كامل"
                    is Outcome.Degraded -> "اكتمل مع تدهور تشغيلي"
                    is Outcome.Error -> "فشل في تنفيذ المخطط"
                }

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "تقرير التنفيذ: $statusText",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isSuccess -> MaterialTheme.colorScheme.primary
                                    isDegraded -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.error
                                }
                            )
                            Text(
                                text = "المدة: ${report.totalDurationMs}ms | الرموز: ${report.totalTokensConsumed}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        report.stepStatuses.forEach { (stepId, status) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val icon = when (status) {
                                        StepStatus.COMPLETED -> Icons.Default.CheckCircle
                                        StepStatus.DEGRADED -> Icons.Default.Warning
                                        StepStatus.FAILED -> Icons.Default.Error
                                        else -> Icons.Default.Schedule
                                    }
                                    val tint = when (status) {
                                        StepStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                                        StepStatus.DEGRADED -> MaterialTheme.colorScheme.tertiary
                                        StepStatus.FAILED -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }

                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = tint,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = stepId, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                }
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = status.name,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall
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
