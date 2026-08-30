package com.example.application.orchestration

import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskInput
import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.StepStatus
import com.example.domain.core.workflow.WorkflowExecutionReport
import com.example.domain.core.workflow.WorkflowFailure
import com.example.domain.core.workflow.WorkflowPlan

/**
 * Deterministic Workflow Engine resolving Directed Acyclic Graphs (DAG) and Sequential Plans.
 */
class WorkflowEngine(
    private val orchestrator: AgentOrchestrator
) {

    /**
     * Validates a workflow plan for circular dependencies (DAG integrity).
     */
    fun validatePlan(plan: WorkflowPlan): Outcome<Unit, WorkflowFailure> {
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        val adjacency = plan.steps.associate { it.id to it.dependencies }

        for (step in plan.steps) {
            if (hasCycle(step.id, adjacency, visited, recursionStack)) {
                return Outcome.Error(
                    failure = WorkflowFailure.CyclicDependencyDetected(recursionStack.toList()),
                    diagnosticMessage = "تم اكتشاف تبعية دائرية غير صالحة في مخطط سير العمل."
                )
            }
        }
        return Outcome.Success(Unit)
    }

    /**
     * Executes a validated workflow plan step-by-step with dependency resolution.
     */
    suspend fun executePlan(plan: WorkflowPlan): WorkflowExecutionReport {
        val startTime = System.currentTimeMillis()

        // 1. Validation Gate
        when (val validation = validatePlan(plan)) {
            is Outcome.Error -> {
                return WorkflowExecutionReport(
                    workflowId = plan.id,
                    goal = plan.goal,
                    overallOutcome = Outcome.Error(validation.failure, validation.diagnosticMessage),
                    stepStatuses = plan.steps.associate { it.id to StepStatus.FAILED },
                    totalDurationMs = System.currentTimeMillis() - startTime,
                    totalTokensConsumed = 0
                )
            }
            else -> { /* Plan is valid DAG */ }
        }

        val stepStatuses = mutableMapOf<String, StepStatus>()
        plan.steps.forEach { stepStatuses[it.id] = StepStatus.PENDING }

        var totalTokens = 0
        var hasDegradedStep = false
        var hasFailedStep = false
        var failureReason = ""

        val outputs = mutableMapOf<String, String>()

        for (step in plan.steps) {
            // Check if all upstream dependencies completed or degraded
            val depsSatisfied = step.dependencies.all { depId ->
                stepStatuses[depId] == StepStatus.COMPLETED || stepStatuses[depId] == StepStatus.DEGRADED
            }

            if (!depsSatisfied) {
                stepStatuses[step.id] = StepStatus.SKIPPED
                continue
            }

            stepStatuses[step.id] = StepStatus.RUNNING

            // Build context including upstream outputs
            val upstreamContext = step.dependencies.mapNotNull { depId ->
                outputs[depId]?.let { "مخرجات خطوة ($depId): $it" }
            }.joinToString("\n")

            val combinedPrompt = if (upstreamContext.isNotBlank()) {
                "${step.description}\n\nالسياق من الخطوات السابقة:\n$upstreamContext"
            } else {
                step.description
            }

            val stepAgent = AgentDefinition(
                identity = AgentIdentity(
                    id = AgentId("workflow_agent_${step.id}"),
                    name = "منفذ خطوة ${step.id}",
                    role = step.agentRole,
                    description = "وكيل تنفيذ خطوة ${step.id}",
                    systemPrompt = step.agentRole.defaultSystemPrompt
                ),
                allowedCapabilities = setOf(
                    com.example.domain.core.capability.CapabilityType.LLM_GENERATION,
                    com.example.domain.core.capability.CapabilityType.TOOL_EXECUTION
                ),
                budget = com.example.domain.core.agent.AgentBudget(maxTokens = 30000)
            )

            val stepTask = TaskDefinition(
                id = step.taskId,
                assignedAgentId = stepAgent.identity.id,
                input = TaskInput(rawPrompt = combinedPrompt)
            )

            val executionOutcome = orchestrator.executeTask(stepAgent, stepTask)

            when (executionOutcome) {
                is Outcome.Success -> {
                    stepStatuses[step.id] = StepStatus.COMPLETED
                    outputs[step.id] = executionOutcome.value
                    totalTokens += executionOutcome.value.length / 4
                }
                is Outcome.Degraded -> {
                    stepStatuses[step.id] = StepStatus.DEGRADED
                    hasDegradedStep = true
                    executionOutcome.partialValue?.let {
                        outputs[step.id] = it
                        totalTokens += it.length / 4
                    }
                }
                is Outcome.Error -> {
                    stepStatuses[step.id] = StepStatus.FAILED
                    hasFailedStep = true
                    failureReason = executionOutcome.diagnosticMessage.ifBlank { executionOutcome.failure }
                }
            }
        }

        val totalDuration = System.currentTimeMillis() - startTime
        val overallOutcome: Outcome<String, WorkflowFailure> = when {
            hasFailedStep -> {
                Outcome.Error(
                    failure = WorkflowFailure.StepExecutionFailed(plan.steps.firstOrNull { stepStatuses[it.id] == StepStatus.FAILED }?.id ?: "unknown", failureReason),
                    diagnosticMessage = "فشلت خطوة أثناء تنفيذ سير العمل: $failureReason"
                )
            }
            hasDegradedStep -> {
                Outcome.Degraded(
                    partialValue = "اكتمل سير العمل مع وجود خطوات في وضع منخفض الأداء (Degraded).",
                    reason = DegradedReason.UNKNOWN_DEGRADATION,
                    diagnosticMessage = "بعض الخطوات نفذت عبر المسار البديل."
                )
            }
            else -> {
                Outcome.Success("اكتمل تنفيذ مخطط سير العمل بنجاح تام.")
            }
        }

        return WorkflowExecutionReport(
            workflowId = plan.id,
            goal = plan.goal,
            overallOutcome = overallOutcome,
            stepStatuses = stepStatuses,
            totalDurationMs = totalDuration,
            totalTokensConsumed = totalTokens
        )
    }

    private fun hasCycle(
        nodeId: String,
        adjacency: Map<String, Set<String>>,
        visited: MutableSet<String>,
        stack: MutableSet<String>
    ): Boolean {
        if (stack.contains(nodeId)) return true
        if (visited.contains(nodeId)) return false

        visited.add(nodeId)
        stack.add(nodeId)

        val deps = adjacency[nodeId] ?: emptySet()
        for (dep in deps) {
            if (hasCycle(dep, adjacency, visited, stack)) return true
        }

        stack.remove(nodeId)
        return false
    }
}
