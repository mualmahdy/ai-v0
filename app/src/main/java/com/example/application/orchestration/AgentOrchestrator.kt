package com.example.application.orchestration

import com.example.application.decision.DecisionService
import com.example.application.execution.ExecutionResult
import com.example.application.execution.ExecutionService
import com.example.application.observation.ObservationService
import com.example.application.outcome.OutcomeService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.security.SecurityPolicy
import com.example.domain.core.agent.AgentId
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.core.task.TaskLifecycleState
import com.example.domain.core.task.TaskBudget
import com.example.domain.core.task.TaskConstraints
import com.example.domain.core.task.TaskSuccessCriteria
import com.example.domain.core.task.VerificationStrategy
import com.example.domain.core.task.AutonomyPolicy
import com.example.infrastructure.persistence.dao.TaskDao
import com.example.infrastructure.persistence.entities.TaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID

/**
 * Core Orchestrator coordinating Closed-Loop Autonomous Execution:
 * DECIDE (CBR-MDP) -> EXECUTE -> OBSERVE -> BELIEF UPDATE -> RE-DECIDE -> COMPLETE.
 */
class AgentOrchestrator(
    private val registry: ComponentRegistry,
    private val securityGuard: SecurityGuardService,
    private val decisionService: DecisionService,
    private val executionService: ExecutionService = ExecutionService(registry, securityGuard),
    private val observationService: ObservationService = ObservationService(),
    private val outcomeService: OutcomeService = OutcomeService(),
    private val taskDao: TaskDao? = null,
    private val defaultSecurityPolicy: SecurityPolicy = SecurityPolicy(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    /**
     * Executes a task synchronously returning a comprehensive Outcome for workflow engines.
     */
    suspend fun executeTask(
        agent: AgentDefinition,
        task: TaskDefinition,
        conversationHistory: List<LlmMessage> = emptyList(),
        preferredProviderId: String? = null,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true
    ): Outcome<String, String> {
        var finalResult = ""
        var isDegraded = false
        var degradedReason: DegradedReason? = null
        var isError = false
        var errorMessage = ""

        executeTaskStream(
            agent = agent,
            task = task,
            conversationHistory = conversationHistory,
            preferredProviderId = preferredProviderId,
            networkPolicy = networkPolicy,
            isNetworkAvailable = isNetworkAvailable
        ).collect { event ->
            when (event) {
                is ExecutionEvent.Completed -> {
                    finalResult = event.finalText
                    isDegraded = event.isDegraded
                    degradedReason = event.degradedReason
                }
                is ExecutionEvent.Error -> {
                    if (event.isFatal) {
                        isError = true
                        errorMessage = event.message
                    }
                }
                is ExecutionEvent.Degraded -> {
                    isDegraded = true
                    degradedReason = event.reason
                }
                else -> Unit
            }
        }

        return when {
            isError -> Outcome.Error(failure = errorMessage, diagnosticMessage = errorMessage)
            isDegraded -> Outcome.Degraded(
                partialValue = finalResult,
                reason = degradedReason ?: DegradedReason.UNKNOWN_DEGRADATION,
                diagnosticMessage = "تم التنفيذ بوضع متراجع"
            )
            else -> Outcome.Success(value = finalResult)
        }
    }

    /**
     * Executes an agent task via the autonomous closed loop governed by CBR-MDP Decision Intelligence.
     */
    fun executeTaskStream(
        agent: AgentDefinition,
        task: TaskDefinition,
        conversationHistory: List<LlmMessage> = emptyList(),
        preferredProviderId: String? = null,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true,
        includeWebSearch: Boolean = false
    ): Flow<ExecutionEvent> = flow {
        val executionId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        // 0. Persist Initial Task State in Room DB
        var currentTask = task.copy(state = TaskLifecycleState.RUNNING)
        persistTaskInitial(currentTask, agent)

        val maxSteps = (task.constraints.maxRetries + 4).coerceIn(3, 8)
        var stepIndex = 0
        var consecutiveFailures = 0
        var accumulatedTokens = 0
        val accumulatedOutputText = StringBuilder()
        val accumulatedEvidence = mutableMapOf<String, Any?>()
        val decisionHistory = mutableListOf<DecisionResult>()
        val observationHistory = mutableListOf<EnvironmentObservation>()

        var currentDecisionState = decisionService.buildDecisionContext(
            task = currentTask,
            networkPolicy = networkPolicy,
            isNetworkAvailable = isNetworkAvailable,
            remainingTokens = currentTask.budget.tokenLimit - accumulatedTokens,
            consecutiveFailures = 0,
            uncertaintyScore = 0.2f
        ).toDecisionState()

        var isTerminal = false
        var isDegraded = false
        var degradedReason: DegradedReason? = null
        var finalResultText = ""

        emit(
            ExecutionEvent.Started(
                executionId = executionId,
                agentId = agent.identity.id,
                modelId = "cbr_mdp_orchestrator"
            )
        )

        while (!isTerminal && stepIndex < maxSteps) {
            // 1. Rebuild DecisionContext dynamically before every decision
            val decisionContext = decisionService.buildDecisionContext(
                task = currentTask.copy(currentStepIndex = stepIndex),
                networkPolicy = networkPolicy,
                isNetworkAvailable = isNetworkAvailable,
                remainingTokens = (currentTask.budget.tokenLimit - accumulatedTokens).coerceAtLeast(0),
                consecutiveFailures = consecutiveFailures,
                uncertaintyScore = currentDecisionState.uncertaintyScore,
                historyCount = conversationHistory.size,
                memoriesCount = (accumulatedEvidence["memorySnippets"] as? List<*>)?.size ?: 0,
                complexity = when {
                    currentTask.requirements.requiredCapabilities.size > 2 -> 0.8f
                    currentTask.requirements.requiredCapabilities.isNotEmpty() -> 0.6f
                    currentTask.goal.length > 200 -> 0.6f
                    else -> 0.5f
                },
                accumulatedEvidence = accumulatedEvidence,
                lastAction = decisionHistory.lastOrNull()?.chosenAction,
                lastObservation = observationHistory.lastOrNull(),
                decisionHistory = decisionHistory
            )

            // 2. CBR-MDP Evaluation
            val decisionResult = decisionService.evaluate(decisionContext)
            decisionHistory.add(decisionResult)
            val chosenAction = decisionResult.chosenAction

            emit(
                ExecutionEvent.DecisionMade(
                    executionId = executionId,
                    decision = decisionResult
                )
            )

            // Handle early pause / termination actions
            if (chosenAction.type == DecisionActionType.COMPLETE || chosenAction.type == DecisionActionType.STOP) {
                val verification = outcomeService.verifyTaskCompletion(
                    task = currentTask,
                    accumulatedEvidence = accumulatedEvidence,
                    finalOutputText = accumulatedOutputText.toString(),
                    lastAction = chosenAction
                )
                if (verification.isSatisfied) {
                    val summaryText = accumulatedOutputText.toString().ifBlank { chosenAction.payload["summary"] ?: "تم إكمال المهمة بنجاح." }
                    finalResultText = summaryText
                    isTerminal = true
                    break
                } else {
                    consecutiveFailures++
                    accumulatedOutputText.append("\n[حوكمة]: لم تُستوفَ معايير الاكتمال: ${verification.missingCriteria.joinToString(", ")}")
                }
            } else if (chosenAction.type == DecisionActionType.ASK_USER) {
                val reason = chosenAction.payload["reason"] ?: "مطلوب تأكيد أو مدخلات من المستخدم."
                persistTaskFinal(currentTask.id.value, "WAITING", reason, accumulatedTokens, System.currentTimeMillis() - startTime, isDegraded, degradedReason?.name, null)
                emit(
                    ExecutionEvent.Degraded(
                        executionId = executionId,
                        reason = DegradedReason.UNKNOWN_DEGRADATION,
                        message = reason
                    )
                )
                isTerminal = true
                return@flow
            }

            emit(
                ExecutionEvent.ActionStarted(
                    executionId = executionId,
                    action = chosenAction,
                    stepIndex = stepIndex
                )
            )

            // 3. Execute Action via ExecutionService
            val execResult = executionService.executeAction(
                action = chosenAction,
                context = decisionContext,
                agent = agent,
                conversationHistory = conversationHistory,
                executionId = executionId,
                onEvent = { event -> emit(event) }
            )

            // 4. Update Token and Output Tracking
            accumulatedTokens += execResult.tokensConsumed
            if (execResult.outputText.isNotBlank()) {
                if (accumulatedOutputText.isNotEmpty() && !accumulatedOutputText.endsWith("\n")) {
                    accumulatedOutputText.append("\n")
                }
                accumulatedOutputText.append(execResult.outputText)
            }
            if (execResult.isDegraded) {
                isDegraded = true
                degradedReason = execResult.degradedReason
            }

            // 5. Merge Evidence into context memory
            accumulatedEvidence.putAll(execResult.outputData)
            if (execResult.outputText.isNotBlank()) {
                accumulatedEvidence["step_${stepIndex}_output"] = execResult.outputText
            }

            // 6. Normalize Observation
            val observation = observationService.createObservation(chosenAction, execResult, stepIndex)
            observationHistory.add(observation)

            // 7. Feed Observation into CBR-MDP Engine -> updates belief state and retains case
            currentDecisionState = decisionService.recordObservation(currentDecisionState, observation)
            emit(
                ExecutionEvent.ObservationRecorded(
                    executionId = executionId,
                    observation = observation,
                    updatedUncertainty = currentDecisionState.uncertaintyScore
                )
            )

            if (execResult.isSuccess) {
                consecutiveFailures = 0
                emit(
                    ExecutionEvent.ActionCompleted(
                        executionId = executionId,
                        action = chosenAction,
                        outputSummary = observation.outputSummary,
                        observation = observation
                    )
                )
            } else {
                consecutiveFailures++
                emit(
                    ExecutionEvent.ActionFailed(
                        executionId = executionId,
                        action = chosenAction,
                        errorDescription = execResult.errorDescription ?: "فشل في تنفيذ الإجراء",
                        observation = observation
                    )
                )
            }

            // 8. Outcome & Objective Verification
            val isObjectiveSatisfied = outcomeService.isTaskObjectiveSatisfied(
                task = currentTask,
                accumulatedEvidence = accumulatedEvidence,
                finalOutputText = accumulatedOutputText.toString(),
                lastAction = chosenAction
            )

            stepIndex++
            currentTask = currentTask.copy(
                currentStepIndex = stepIndex,
                state = if (isObjectiveSatisfied) TaskLifecycleState.COMPLETED else TaskLifecycleState.RUNNING,
                outcomeSummary = accumulatedOutputText.toString().take(200)
            )

            persistTaskUpdate(
                taskId = currentTask.id.value,
                stateStr = currentTask.state.name,
                outcomeSummary = currentTask.outcomeSummary,
                tokensConsumed = accumulatedTokens,
                durationMs = System.currentTimeMillis() - startTime,
                isDegraded = isDegraded,
                degradedReason = degradedReason?.name,
                errorMsg = if (!execResult.isSuccess) execResult.errorDescription else null
            )

            val isTerminalCondition = outcomeService.isTerminalConditionReached(
                task = currentTask,
                stepCount = stepIndex,
                maxSteps = maxSteps,
                consecutiveFailures = consecutiveFailures,
                isObjectiveMet = isObjectiveSatisfied
            )

            if (isTerminalCondition) {
                finalResultText = accumulatedOutputText.toString()
                isTerminal = true
                if (!isObjectiveSatisfied && consecutiveFailures > task.constraints.maxRetries) {
                    val failureMsg = "تجاوزت المهمة الحد الأقصى للمحاولات (${task.constraints.maxRetries}) دون الوصول للهدف: ${execResult.errorDescription}"
                    emit(
                        ExecutionEvent.Error(
                            executionId = executionId,
                            failureCode = "MAX_RETRIES_EXCEEDED",
                            message = failureMsg,
                            isFatal = true
                        )
                    )
                    persistTaskFinal(currentTask.id.value, "FAILED", null, accumulatedTokens, System.currentTimeMillis() - startTime, isDegraded, degradedReason?.name, failureMsg)
                    return@flow
                }
            }
        }

        // 9. Final Objective Verification before emitting Completed Event
        val isFinalObjectiveMet = outcomeService.isTaskObjectiveSatisfied(
            task = currentTask,
            accumulatedEvidence = accumulatedEvidence,
            finalOutputText = accumulatedOutputText.toString(),
            lastAction = decisionHistory.lastOrNull()?.chosenAction ?: DecisionAction(DecisionActionType.STOP)
        )

        val totalDuration = System.currentTimeMillis() - startTime
        val finalOutput = if (finalResultText.isNotBlank()) finalResultText else accumulatedOutputText.toString().ifBlank { "اكتملت معالجة المهمة." }

        if (!isFinalObjectiveMet && !task.constraints.allowDegradedExecution) {
            val failureMsg = "فشلت المهمة في استيفاء معايير القبول المحددة بعد $stepIndex خطوات."
            persistTaskFinal(currentTask.id.value, "FAILED", finalOutput.take(200), accumulatedTokens, totalDuration, isDegraded, degradedReason?.name, failureMsg)
            emit(
                ExecutionEvent.Error(
                    executionId = executionId,
                    failureCode = "OBJECTIVE_NOT_SATISFIED",
                    message = failureMsg,
                    isFatal = true
                )
            )
            return@flow
        }

        val stateStr = if (isDegraded || !isFinalObjectiveMet) "DEGRADED" else "COMPLETED"
        val effectiveDegradedReason = if (!isFinalObjectiveMet) DegradedReason.PARTIAL_EVIDENCE else degradedReason

        persistTaskFinal(
            taskId = currentTask.id.value,
            stateStr = stateStr,
            outcomeSummary = finalOutput.take(200),
            tokensConsumed = accumulatedTokens,
            durationMs = totalDuration,
            isDegraded = isDegraded || !isFinalObjectiveMet,
            degradedReason = effectiveDegradedReason?.name,
            errorMsg = null
        )

        emit(
            ExecutionEvent.Completed(
                executionId = executionId,
                finalText = finalOutput,
                totalDurationMs = totalDuration,
                isDegraded = isDegraded || !isFinalObjectiveMet,
                degradedReason = effectiveDegradedReason
            )
        )
    }

    /**
     * Resumes execution of a previously persisted task from Room database.
     *
     * FIX APP-P0-07: Previously this method reconstructed TaskDefinition with ONLY 4 fields
     * (id, agentId, rawPrompt, state), dropping specification/requirements/constraints/budget/
     * successCriteria/currentStepIndex/outcomeSummary. The resumed task started fresh from
     * step 0 with default constraints — effectively a re-execution, not a resume. Now we
     * reconstruct the full TaskDefinition from the extended TaskEntity.
     */
    fun resumeTask(taskId: String): Flow<ExecutionEvent> = flow {
        val dao = taskDao
        if (dao == null) {
            emit(ExecutionEvent.Error("resume_err", "NO_PERSISTENCE", "قاعدة البيانات غير متاحة لاستئناف المهمة."))
            return@flow
        }
        val taskEntity = dao.getTaskById(taskId)
        if (taskEntity == null) {
            emit(ExecutionEvent.Error("resume_err", "TASK_NOT_FOUND", "المهمة ذات المعرف $taskId غير موجودة."))
            return@flow
        }

        val restoredState = try {
            TaskLifecycleState.valueOf(taskEntity.lifecycleState)
        } catch (_: IllegalArgumentException) {
            // Legacy state strings that aren't valid enum values — default to CREATED
            // so the orchestrator can re-run the task instead of crashing.
            TaskLifecycleState.CREATED
        }

        // Reconstruct full TaskDefinition from the extended TaskEntity.
        val taskDef = TaskDefinition(
            id = TaskId(taskEntity.id),
            assignedAgentId = AgentId(taskEntity.assignedAgentId),
            goal = taskEntity.goal.ifBlank { taskEntity.rawPrompt },
            input = TaskInput(rawPrompt = taskEntity.rawPrompt),
            state = restoredState,
            budget = TaskBudget(
                tokenLimit = taskEntity.tokenLimit,
                consumedTokens = taskEntity.totalTokensConsumed
            ),
            constraints = TaskConstraints(
                timeoutMs = taskEntity.timeoutMs,
                maxRetries = taskEntity.maxRetries,
                allowDegradedExecution = taskEntity.allowDegradedExecution,
                autonomyPolicy = try {
                    AutonomyPolicy.valueOf(taskEntity.autonomyPolicy)
                } catch (_: IllegalArgumentException) {
                    AutonomyPolicy.SUPERVISED
                },
                requireHumanConsentForSensitiveTools = taskEntity.requireHumanConsentForSensitiveTools
            ),
            successCriteria = TaskSuccessCriteria(
                minOutputLengthChars = taskEntity.minOutputLengthChars,
                verificationStrategy = try {
                    VerificationStrategy.valueOf(taskEntity.verificationStrategy)
                } catch (_: IllegalArgumentException) {
                    VerificationStrategy.STRICT
                },
                requiredOutputKeys = parseJsonStringArray(taskEntity.requiredOutputKeysJson),
                requiredEvidenceKeys = parseJsonStringArray(taskEntity.requiredEvidenceKeysJson)
            ),
            assignedModelId = taskEntity.assignedModelId,
            activeTools = parseJsonStringArray(taskEntity.activeToolsJson),
            currentStepIndex = taskEntity.currentStepIndex,
            executionLog = parseJsonStringArray(taskEntity.executionLogJson),
            outcomeSummary = taskEntity.resultSummary
        )

        val assignedAgent = registry.listAgents().firstOrNull { it.identity.id.value == taskEntity.assignedAgentId }
            ?: decisionService.selectSuitableAgent(taskDef, registry.listAgents())
            ?: registry.listAgents().firstOrNull()

        if (assignedAgent == null) {
            emit(ExecutionEvent.Error("resume_err", "NO_AGENT_FOUND", "لا يوجد عميل متاح لاستئناف المهمة."))
            return@flow
        }

        executeTaskStream(assignedAgent, taskDef).collect { emit(it) }
    }

    /**
     * Parses a JSON-encoded string array back to List<String>. Returns emptyList on any failure
     * so resume is resilient to legacy / null / malformed rows.
     */
    private fun parseJsonStringArray(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                out.add(arr.getString(i))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Encodes a List<String> as a JSON array string for Room storage. */
    private fun encodeStringArray(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    private suspend fun persistTaskInitial(task: TaskDefinition, agent: AgentDefinition) {
        val dao = taskDao ?: return
        try {
            dao.insertOrUpdateTask(
                TaskEntity(
                    id = task.id.value,
                    assignedAgentId = agent.identity.id.value,
                    rawPrompt = task.input.rawPrompt,
                    lifecycleState = "INITIALIZED",
                    autonomyPolicy = task.constraints.autonomyPolicy.name,
                    resultSummary = null,
                    totalTokensConsumed = 0,
                    durationMs = 0L,
                    isDegraded = false,
                    degradedReason = null,
                    errorMessage = null,
                    createdAtEpochMs = System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                    // FIX APP-P0-07: persist full-fidelity fields for resume round-trip
                    goal = task.goal,
                    currentStepIndex = task.currentStepIndex,
                    tokenLimit = task.budget.tokenLimit,
                    maxRetries = task.constraints.maxRetries,
                    allowDegradedExecution = task.constraints.allowDegradedExecution,
                    requireHumanConsentForSensitiveTools = task.constraints.requireHumanConsentForSensitiveTools,
                    timeoutMs = task.constraints.timeoutMs,
                    minOutputLengthChars = task.successCriteria.minOutputLengthChars,
                    verificationStrategy = task.successCriteria.verificationStrategy.name,
                    assignedModelId = task.assignedModelId,
                    activeToolsJson = encodeStringArray(task.activeTools),
                    requiredCapabilitiesJson = null, // Set<CapabilityType> not serializable here; deferred to Phase 2
                    requiredEvidenceKeysJson = encodeStringArray(task.successCriteria.requiredEvidenceKeys),
                    requiredOutputKeysJson = encodeStringArray(task.successCriteria.requiredOutputKeys),
                    executionLogJson = encodeStringArray(task.executionLog)
                )
            )
        } catch (_: Exception) {
            // Safe fallback
        }
    }

    private suspend fun persistTaskUpdate(
        taskId: String,
        stateStr: String,
        outcomeSummary: String?,
        tokensConsumed: Int,
        durationMs: Long,
        isDegraded: Boolean,
        degradedReason: String?,
        errorMsg: String?
    ) {
        val dao = taskDao ?: return
        try {
            dao.updateTaskStatus(
                id = taskId,
                state = stateStr,
                summary = outcomeSummary,
                tokens = tokensConsumed,
                duration = durationMs,
                isDegraded = isDegraded,
                degradedReason = degradedReason,
                errorMsg = errorMsg,
                now = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            // Safe fallback
        }
    }

    private suspend fun persistTaskFinal(
        taskId: String,
        stateStr: String,
        outcomeSummary: String?,
        tokensConsumed: Int,
        durationMs: Long,
        isDegraded: Boolean,
        degradedReason: String?,
        errorMsg: String?
    ) {
        val dao = taskDao ?: return
        try {
            dao.updateTaskStatus(
                id = taskId,
                state = stateStr,
                summary = outcomeSummary,
                tokens = tokensConsumed,
                duration = durationMs,
                isDegraded = isDegraded,
                degradedReason = degradedReason,
                errorMsg = errorMsg,
                now = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            // Safe fallback
        }
    }
}
