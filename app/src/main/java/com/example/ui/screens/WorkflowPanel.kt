package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.models.PlanStep
import com.example.domain.models.WorkflowExecutionResult
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.Slate850
import com.example.ui.viewmodel.WorkspaceViewModel
import java.util.Locale

@Composable
fun WorkflowPanel(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val plan by viewModel.currentWorkflowPlan.collectAsState()
    val result by viewModel.activeWorkflowResult.collectAsState()
    val isRunning by viewModel.isWorkflowRunning.collectAsState()
    var goalText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "محرك سير العمل التكيّفي (CBR-MDP Workflow Engine)",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "تخطيط استراتيجي ذاتي للأهداف، وتنفيذ مقاد بنظرية الاحتمالات البايزية (Bayesian Belief Updates).",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Goal Input Card
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = goalText,
                    onValueChange = { goalText = it },
                    label = { Text("أدخل الهدف المطلوب إنجازه بالكامل...") },
                    placeholder = { Text("مثال: بناء وحدة توثيق ومكتبة حسابات وفحصها برمجياً") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("workflow_goal_input"),
                    maxLines = 3
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.generateWorkflowPlan(goalText) },
                        enabled = goalText.isNotBlank() && !isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("generate_plan_button")
                    ) {
                        Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("توليد الخطة (Planner)", fontSize = 12.sp)
                    }

                    if (plan != null) {
                        Button(
                            onClick = { viewModel.runCurrentWorkflow() },
                            enabled = !isRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                            modifier = Modifier.testTag("run_workflow_button")
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تنفيذ الخطة", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Plan Execution View
        if (plan != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Text("خطة العمل المقترحة (${plan!!.steps.size} خطوات):", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))

            plan!!.steps.forEach { step ->
                StepCard(step)
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        // Execution Result & Belief Histogram Visualizer
        if (result != null) {
            Spacer(modifier = Modifier.height(14.dp))
            BeliefVisualizerCard(result!!)
        }
    }
}

@Composable
fun StepCard(step: PlanStep) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when (step.status) {
                            "done" -> EmeraldSuccess.copy(alpha = 0.2f)
                            "running" -> CyanPrimary.copy(alpha = 0.2f)
                            "error" -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                            "skipped" -> AmberWarning.copy(alpha = 0.2f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${step.id}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (step.status) {
                        "done" -> EmeraldSuccess
                        "running" -> CyanPrimary
                        "error" -> MaterialTheme.colorScheme.error
                        "skipped" -> AmberWarning
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = step.action, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = step.agent,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(text = step.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (!step.output.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "المخرجات: ${step.output}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
fun BeliefVisualizerCard(res: WorkflowExecutionResult) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("نتائج نموذج الاعتقاد البايزي (CBR-MDP Quality Belief)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when (res.quality) {
                        "SUCCESS" -> EmeraldSuccess.copy(alpha = 0.2f)
                        "DEGRADED" -> AmberWarning.copy(alpha = 0.2f)
                        else -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                    }
                ) {
                    Text(
                        text = res.quality,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (res.quality) {
                            "SUCCESS" -> EmeraldSuccess
                            "DEGRADED" -> AmberWarning
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "قيمة التوقع الرياضي E[Q] = ${String.format(Locale.US, "%.3f", res.beliefExpectedValue)} (الهدف المرجعي B0 = 0.850)",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "الحالة النهائية: ${res.status} | الخطوات المضافة تكيفياً: ${res.addedStepsCount} | الوقت: ${res.durationMs}ms",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Histogram Bins Graphic
            Text("توزيع كتل الاحتمال عبر مجالات الجودة (20 Bins):", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Slate850, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                res.beliefBins.forEach { weight ->
                    val barHeight = (weight * 120).coerceIn(4f, 44f).dp
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(barHeight)
                            .padding(horizontal = 1.dp)
                            .background(CyanPrimary, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    )
                }
            }
        }
    }
}
