package com.example.application.orchestration

import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskInput
import com.example.domain.core.task.TaskSpecification
import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.StepStatus
import com.example.domain.core.workflow.WorkflowExecutionReport
import com.example.domain.core.workflow.WorkflowFailure
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Deterministic Workflow Engine resolving Directed Acyclic Graphs (DAG).
 *
 * ============================================================================
 * GAP-CLOSURE P0-07 / P1-05 / P1-06 / P1-07
 * ============================================================================
 *
 *  - DAG SCHEDULER (P1-05): DIRECTED_ACYCLIC_GRAPH and FAN_OUT_PARALLEL plans
 *    now execute independent branches CONCURRENTLY (bounded by
 *    [maxConcurrentSteps]). SEQUENTIAL keeps strict in-order semantics.
 *
 *  - HONEST SKIPPED SEMANTICS (P0-07): a step that cannot run (dependency
 *    failed, blocked, or references an unknown step) is SKIPPED and the
 *    workflow CANNOT report success — required-but-blocked steps surface as
 *    an explicit failure naming them. "Successful" now means every required
 *    step actually ran.
 *
 *  - AUTHORITATIVE PERSISTENCE (P1-06): persistence failures are NOT
 *    swallowed. start/step/terminal write failures are tracked and degrade
 *    the overall outcome honestly — the runtime never claims a durable
 *    state it could not write.
 *
 *  - REAL TOKEN ACCOUNTING (P1-07): totalTokensConsumed comes from the
 *    orchestrator's measured usage (the same numbers the economic ledger
 *    records), NOT the legacy `output.length / 4` estimate.
 */
class WorkflowEngine(
    private val orchestrator: AgentOrchestrator,
    /**
     * Durable workflow state (audit 2026 fix): when wired, every execution
     * persists start/checkpoint/step/terminal state so partial workflow
     * progress survives process death and can be inspected after the fact.
     */
    private val persistenceService: com.example.application.workflow.WorkflowPersistenceService? = null,
    private val workspaceIdProvider: suspend () -> String = { "default" },
    /** Upper bound on concurrently RUNNING steps (P1-05 resource safety). */
    private val maxConcurrentSteps: Int = 3
) {

    /** Per-run mutable state (guarded by [stateMutex]). */
    private class RunState(plan: WorkflowPlan) {
        val stepStatuses: MutableMap<String, StepStatus> =
            plan.steps.associate { it.id to StepStatus.PENDING }.toMutableMap()
        val outputs = mutableMapOf<String, String>()
        var totalTokens = 0
        var hasDegradedStep = false
        var hasFailedStep = false
        var failureReason = ""
        var persistenceFailures = 0
    }

    /**
     * Validates a workflow plan for circular dependencies (DAG integrity)
     * and for dangling dependency references (a step depending on an id that
     * does not exist in the plan — previously such steps were silently
     * SKIPPED and the workflow still reported SUCCESS; see P0-07).
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

        // P0-07: unknown dependency references are plan defects — steps that
        // can never be scheduled must not hide behind a "successful" run.
        val knownIds = plan.steps.map { it.id }.toSet()
        val dangling = plan.steps.filter { step -> step.dependencies.any { it !in knownIds } }
        if (dangling.isNotEmpty()) {
            return Outcome.Error(
                failure = WorkflowFailure.StepExecutionFailed(
                    stepId = dangling.first().id,
                    reason = "DANGLING_DEPENDENCY: خطوات تعتمد على معرفات غير موجودة في المخطط: " +
                        dangling.joinToString(", ") { s ->
                            "${s.id} -> ${s.dependencies.filter { it !in knownIds }.joinToString("/")}"
                        }
                ),
                diagnosticMessage = "مخطط سير العمل يحتوي تبعيات غير قابلة للتحقق."
            )
        }

        return Outcome.Success(Unit)
    }

    /**
     * Executes a validated workflow plan with dependency resolution and
     * (for DAG / fan-out topologies) bounded concurrent branch execution.
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

        // ------------------------------------------------------------
        // P0-02 (workflow level): the workspace is PINNED once at plan
        // start; every step execution binds to it, so a mid-run workspace
        // switch cannot scatter step attribution across workspaces.
        // ------------------------------------------------------------
        val pinnedWorkspaceId = runCatching { workspaceIdProvider() }.getOrNull()

        val stateMutex = Mutex()
        val state = RunState(plan)

        // ------------------------------------------------------------
        // AUTHORITATIVE PERSISTENCE (P1-06): a failed start-write is
        // tracked (not swallowed); execution proceeds but the final report
        // degrades honestly so nobody trusts a durable state that is not
        // actually written.
        // ------------------------------------------------------------
        persistTracked(state) { persistenceService?.start(plan.id, pinnedWorkspaceId ?: "unknown", plan) }

        when (plan.executionMode) {
            ExecutionMode.SEQUENTIAL -> executeSequential(plan, pinnedWorkspaceId, state, stateMutex)
            else -> executeDag(plan, pinnedWorkspaceId, state, stateMutex)
        }

        // P0-07: steps that never ran (blocked by failed/skipped deps).
        val skippedSteps = stateMutex.withLock {
            state.stepStatuses.filter { it.value == StepStatus.SKIPPED }.keys.toList()
        }

        val totalDuration = System.currentTimeMillis() - startTime

        // Durable terminal state (P1-06 — tracked, not swallowed).
        persistTracked(state) {
            if (state.hasFailedStep || skippedSteps.isNotEmpty()) {
                persistenceService?.fail(
                    plan.id,
                    state.failureReason.ifBlank { "خطوات مطلوبة لم تُنفّذ: ${skippedSteps.joinToString(", ")}" }
                )
            } else {
                persistenceService?.complete(plan.id, state.hasDegradedStep)
            }
        }

        val overallOutcome: Outcome<String, WorkflowFailure> = when {
            state.hasFailedStep -> Outcome.Error(
                failure = WorkflowFailure.StepExecutionFailed(
                    plan.steps.firstOrNull { stateMutex.withLock { state.stepStatuses[it.id] == StepStatus.FAILED } }?.id ?: "unknown",
                    state.failureReason
                ),
                diagnosticMessage = "فشلت خطوة أثناء تنفيذ سير العمل: ${state.failureReason}"
            )
            // ------------------------------------------------------------
            // P0-07: a workflow with REQUIRED steps that were skipped
            // (blocked) is NOT successful — it never completed its plan.
            // ------------------------------------------------------------
            skippedSteps.isNotEmpty() -> Outcome.Error(
                failure = WorkflowFailure.StepExecutionFailed(
                    stepId = skippedSteps.first(),
                    reason = "BLOCKED_STEPS: خطوات مطلوبة لم تُنفّذ لأن تبعياتها لم تكتمل: ${skippedSteps.joinToString(", ")}"
                ),
                diagnosticMessage = "لم يكتمل تنفيذ المخطط — خطوات محجوبة: ${skippedSteps.joinToString(", ")}"
            )
            // ------------------------------------------------------------
            // P1-06: durable-state write failures degrade the outcome
            // honestly (runtime truth ≠ DB truth is exactly what this gap
            // forbids claiming).
            // ------------------------------------------------------------
            state.persistenceFailures > 0 -> Outcome.Degraded(
                partialValue = "اكتمل تنفيذ المخطط لكن تعذّر تدوين حالته الدائمة في قاعدة البيانات " +
                    "(${state.persistenceFailures} عمليات كتابة فشلت) — قد تظهر الحالة قديمة بعد إعادة التشغيل.",
                reason = DegradedReason.UNKNOWN_DEGRADATION,
                diagnosticMessage = "WORKFLOW_PERSISTENCE_DEGRADED: فشل تدوين الحالة الدائمة."
            )
            state.hasDegradedStep -> Outcome.Degraded(
                partialValue = "اكتمل سير العمل مع وجود خطوات في وضع منخفض الأداء (Degraded).",
                reason = DegradedReason.UNKNOWN_DEGRADATION,
                diagnosticMessage = "بعض الخطوات نفذت عبر المسار البديل."
            )
            else -> Outcome.Success("اكتمل تنفيذ مخطط سير العمل بنجاح تام.")
        }

        return WorkflowExecutionReport(
            workflowId = plan.id,
            goal = plan.goal,
            overallOutcome = overallOutcome,
            stepStatuses = stateMutex.withLock { state.stepStatuses.toMap() },
            totalDurationMs = totalDuration,
            // P1-07: MEASURED usage from the orchestrator's accounting (the
            // same source the economic ledger records), not chars/4.
            totalTokensConsumed = stateMutex.withLock { state.totalTokens }
        )
    }

    // ------------------------------------------------------------------
    // SEQUENTIAL topology: strict in-order execution with dependency gates.
    // ------------------------------------------------------------------
    private suspend fun executeSequential(
        plan: WorkflowPlan,
        pinnedWorkspaceId: String?,
        state: RunState,
        stateMutex: Mutex
    ) {
        for (step in plan.steps) {
            val depsSatisfied = stateMutex.withLock {
                step.dependencies.all { depId ->
                    state.stepStatuses[depId] == StepStatus.COMPLETED || state.stepStatuses[depId] == StepStatus.DEGRADED
                }
            }
            if (!depsSatisfied) {
                stateMutex.withLock { state.stepStatuses[step.id] = StepStatus.SKIPPED }
                persistTracked(state) {
                    persistenceService?.markStepStatus(plan.id, step.id, StepStatus.SKIPPED, null, null)
                }
                continue
            }
            executeOneStep(plan, step, pinnedWorkspaceId, state, stateMutex)
        }
    }

    // ------------------------------------------------------------------
    // DAG / FAN-OUT topology (P1-05): ready-set scheduling with bounded
    // concurrency. Independent branches run in parallel; a step launches
    // only when ALL its dependencies reached COMPLETED/DEGRADED.
    // ------------------------------------------------------------------
    private suspend fun executeDag(
        plan: WorkflowPlan,
        pinnedWorkspaceId: String?,
        state: RunState,
        stateMutex: Mutex
    ) = coroutineScope {
        val semaphore = Semaphore(maxConcurrentSteps.coerceAtLeast(1))
        val pending = plan.steps.associate { it.id to it }.toMutableMap()
        val finished = Channel<String>(Channel.UNLIMITED)
        var inflight = 0

        while (true) {
            // 1. Launch every step whose dependencies are satisfied.
            val ready = stateMutex.withLock {
                pending.values.filter { step ->
                    step.dependencies.all { depId ->
                        state.stepStatuses[depId] == StepStatus.COMPLETED ||
                            state.stepStatuses[depId] == StepStatus.DEGRADED
                    }
                }.map { it.id }
            }
            for (stepId in ready) {
                val step = pending.remove(stepId)!!
                inflight++
                launch {
                    semaphore.withPermit {
                        executeOneStep(plan, step, pinnedWorkspaceId, state, stateMutex)
                    }
                    finished.send(stepId)
                }
            }

            if (pending.isEmpty() && inflight == 0) break

            // 2. No progress possible and nothing in flight → every
            // remaining step is BLOCKED (its deps failed/skipped) — P0-07:
            // mark them SKIPPED so the overall outcome cannot lie.
            if (ready.isEmpty() && inflight == 0) {
                val blocked = pending.keys.toList()
                stateMutex.withLock { for (id in blocked) state.stepStatuses[id] = StepStatus.SKIPPED }
                for (id in blocked) {
                    persistTracked(state) {
                        persistenceService?.markStepStatus(plan.id, id, StepStatus.SKIPPED, null, null)
                    }
                }
                break
            }

            // 3. Wait for at least one running step to finish, then reschedule.
            if (inflight > 0) {
                finished.receive()
                inflight--
            }
        }
    }

    /** Executes ONE step (any topology) and routes its outcome. */
    private suspend fun executeOneStep(
        plan: WorkflowPlan,
        step: StepNode,
        pinnedWorkspaceId: String?,
        state: RunState,
        stateMutex: Mutex
    ) {
        stateMutex.withLock { state.stepStatuses[step.id] = StepStatus.RUNNING }

        // Build context including upstream outputs
        val upstreamContext = stateMutex.withLock {
            step.dependencies.mapNotNull { depId ->
                state.outputs[depId]?.let { "مخرجات خطوة ($depId): $it" }
            }
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
            input = TaskInput(rawPrompt = combinedPrompt),
            specification = TaskSpecification(
                objective = step.description,
                requirements = step.requirements,
                expectedOutputs = step.expectedOutputs,
                evidenceRequirements = step.evidenceRequirements,
                acceptanceCriteria = step.acceptanceCriteria,
                provenance = com.example.domain.core.task.TaskSpecificationProvenance.PLANNER_DECOMPOSITION
            ),
            requirements = step.requirements
        )

        // P1-07: the detailed result carries MEASURED token usage.
        val summary = orchestrator.executeTaskDetailed(
            agent = stepAgent,
            task = stepTask,
            pinnedWorkspaceId = pinnedWorkspaceId
        )

        val status: StepStatus
        val stepOutput: String
        when (val outcome = summary.outcome) {
            is Outcome.Success -> {
                status = StepStatus.COMPLETED
                stepOutput = outcome.value
                stateMutex.withLock {
                    state.stepStatuses[step.id] = status
                    state.outputs[step.id] = stepOutput
                    state.totalTokens += summary.totalTokensConsumed
                }
            }
            is Outcome.Degraded -> {
                status = StepStatus.DEGRADED
                stepOutput = outcome.partialValue.orEmpty()
                stateMutex.withLock {
                    state.stepStatuses[step.id] = status
                    if (stepOutput.isNotBlank()) state.outputs[step.id] = stepOutput
                    state.hasDegradedStep = true
                    state.totalTokens += summary.totalTokensConsumed
                }
            }
            is Outcome.Error -> {
                status = StepStatus.FAILED
                stepOutput = outcome.diagnosticMessage.ifBlank { outcome.failure }
                stateMutex.withLock {
                    state.stepStatuses[step.id] = status
                    state.hasFailedStep = true
                    state.failureReason = stepOutput
                    state.totalTokens += summary.totalTokensConsumed
                }
            }
        }

        // Persist the step outcome + checkpoint (P1-06 — tracked, not swallowed).
        persistTracked(state) {
            persistenceService?.markStepStatus(
                workflowId = plan.id,
                stepId = step.id,
                status = status,
                outputSummary = stepOutput.take(200),
                durationMs = null
            )
            if (status == StepStatus.COMPLETED || status == StepStatus.DEGRADED) {
                persistenceService?.checkpoint(plan.id, plan.steps.indexOf(step) + 1)
            }
        }
    }

    /**
     * Runs a persistence write, counting failures into the run state
     * (P1-06 — failures are surfaced through the degraded overall outcome,
     * never swallowed silently). No-ops when no persistence service is
     * wired (pure JVM tests).
     */
    private suspend fun persistTracked(state: RunState, block: suspend () -> Unit) {
        if (persistenceService == null) return
        try {
            block()
        } catch (_: Exception) {
            state.persistenceFailures++
        }
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
