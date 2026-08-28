package com.example.runtime.workflow

import com.example.domain.models.PlanStep
import com.example.domain.models.WorkflowExecutionResult
import com.example.domain.models.WorkflowPlan
import com.example.runtime.agents.AgentRegistry
import com.example.runtime.agents.PlannerAgent
import com.example.runtime.budget.TokenBudgetTracker
import com.example.runtime.decision.CbrMdpEngine
import com.example.runtime.decision.DiscreteBelief
import com.example.runtime.events.EventBus
import kotlinx.coroutines.delay

class WorkflowExecutor(
    private val agentRegistry: AgentRegistry,
    private val tokenBudgetTracker: TokenBudgetTracker,
    private val plannerAgent: PlannerAgent
) {
    suspend fun execute(
        projectId: Long,
        plan: WorkflowPlan,
        onStepUpdate: ((PlanStep) -> Unit)? = null
    ): WorkflowExecutionResult {
        val startTime = System.currentTimeMillis()
        val steps = plan.steps.map { it.copy() }.toMutableList()
        val belief = DiscreteBelief(numBins = 20)
        val referenceBelief = DiscreteBelief.peakedAt(0.85f, numBins = 20)

        var stoppedEarly = false
        var hasObservedAnything = false
        var i = 0

        while (i < steps.size) {
            val step = steps[i]
            step.status = "running"
            onStepUpdate?.invoke(step)
            EventBus.publishWorkflowStep(step.id, step.action, "running")

            val agent = agentRegistry.get(step.agent)
            if (agent == null) {
                step.status = "error"
                step.output = "الوكيل ${step.agent} غير موجود في السجل"
                onStepUpdate?.invoke(step)
                i++
                continue
            }

            tokenBudgetTracker.markStarted(projectId, step.agent)
            val stepStart = System.currentTimeMillis()

            try {
                // T3 queue threshold check
                val queueLength = tokenBudgetTracker.getInFlight(projectId, step.agent)
                if (CbrMdpEngine.exceedsQueueThreshold(queueLength, threshold = 5)) {
                    step.status = "error"
                    step.output = "تم حظر الوكيل ${step.agent} لتجاوز عتبة الحمل (T3 Threshold)"
                    onStepUpdate?.invoke(step)
                    i++
                    continue
                }

                val taskMap = mapOf(
                    "prompt" to step.description,
                    "action" to step.action,
                    "projectId" to projectId
                )

                val result = agent.execute(taskMap)
                step.status = if (result.status == "error") "error" else "done"
                step.output = result.response

                // Token accounting
                val inTokens = tokenBudgetTracker.estimateTokens(step.description)
                val outTokens = tokenBudgetTracker.estimateTokens(result.response)
                tokenBudgetTracker.recordUsage(projectId, step.agent, inTokens, outTokens)

                // Observed quality computation for CBR-MDP belief update
                val quality = CbrMdpEngine.computeObservedQuality(
                    reviewerScore = result.score,
                    toolSuccess = if (result.toolTrace.isNotEmpty()) 1.0f else null,
                    searchRelevance = if (step.agent == "search") 0.85f else null
                )

                if (quality != null) {
                    belief.update(quality)
                    hasObservedAnything = true
                }
            } catch (e: Exception) {
                step.status = "error"
                step.output = "خطأ في التنفيذ: ${e.message}"
            } finally {
                tokenBudgetTracker.markFinished(projectId, step.agent)
            }

            onStepUpdate?.invoke(step)
            EventBus.publishWorkflowStep(step.id, step.action, step.status)
            i++

            // D1 Stopping condition check: If belief is confident enough, stop early to save resources
            if (i < steps.size && hasObservedAnything && !CbrMdpEngine.isQueryingWorthwhile(belief, referenceBelief)) {
                stoppedEarly = true
                for (k in i until steps.size) {
                    steps[k].status = "skipped"
                    steps[k].output = "تم التخطي تلقائياً (Cbr-Mdp Early Stop: تم الوصول إلى اليقين المطلوب)"
                    onStepUpdate?.invoke(steps[k])
                }
                break
            }
        }

        // Adaptive ADD_NODE action: If still not confident and plan is exhausted, add steps adaptively
        var addedSteps = 0
        while (!stoppedEarly && addedSteps < 2 && CbrMdpEngine.isQueryingWorthwhile(belief, referenceBelief)) {
            val summary = steps.joinToString("\n") { "خطوة #${it.id}: ${it.action} -> ${it.output?.take(80)}" }
            val nextStep = plannerAgent.proposeNextStep(plan.goal, summary, steps.size + 1) ?: break
            addedSteps++
            steps.add(nextStep)

            nextStep.status = "running"
            onStepUpdate?.invoke(nextStep)
            EventBus.publishWorkflowStep(nextStep.id, nextStep.action, "running (ADD_NODE)")

            val agent = agentRegistry.get(nextStep.agent) ?: agentRegistry.get("direct")!!
            val res = agent.execute(mapOf("prompt" to nextStep.description, "projectId" to projectId))
            nextStep.status = if (res.status == "error") "error" else "done"
            nextStep.output = res.response
            onStepUpdate?.invoke(nextStep)

            if (res.score != null) {
                belief.update(res.score)
            }
        }

        val allErrors = steps.all { it.status == "error" }
        val hasErrors = steps.any { it.status == "error" }
        val overallStatus = when {
            allErrors -> "failed"
            stoppedEarly -> "stopped_early"
            hasErrors -> "completed_with_errors"
            else -> "completed"
        }

        val overallQuality = when {
            allErrors -> "FAILED"
            hasErrors -> "DEGRADED"
            else -> "SUCCESS"
        }

        return WorkflowExecutionResult(
            goal = plan.goal,
            status = overallStatus,
            quality = overallQuality,
            steps = steps,
            beliefExpectedValue = belief.expectedValue(),
            beliefBins = belief.weights.toList(),
            addedStepsCount = addedSteps,
            durationMs = System.currentTimeMillis() - startTime
        )
    }
}
