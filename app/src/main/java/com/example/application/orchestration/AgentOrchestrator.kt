package com.example.application.orchestration

import com.example.application.decision.DecisionService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.OutcomeMetadata
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.MessageRole
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.search.SearchQuery
import com.example.domain.core.security.SecurityDecision
import com.example.domain.core.security.SecurityPolicy
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskLifecycleState
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.infrastructure.persistence.dao.TaskDao
import com.example.infrastructure.persistence.entities.TaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Core Orchestrator coordinating Agent Execution, CBR-MDP Decision Intelligence,
 * Tool Invocations, Security Gateways, Memory/RAG Augmentation, Web Search, and Room Persistence.
 */
class AgentOrchestrator(
    private val registry: ComponentRegistry,
    private val securityGuard: SecurityGuardService,
    private val decisionService: DecisionService,
    private val taskDao: TaskDao? = null,
    private val defaultSecurityPolicy: SecurityPolicy = SecurityPolicy(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    /**
     * Executes an agent task via reactive event streaming governed by the CBR-MDP Decision Engine.
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
        persistTaskInitial(task, agent)

        // 1. Construct DecisionContext & Evaluate with CBR-MDP Decision Engine
        val decisionContext = decisionService.buildDecisionContext(
            task = task,
            networkPolicy = networkPolicy,
            isNetworkAvailable = isNetworkAvailable,
            remainingTokens = task.budget.tokenLimit - task.budget.consumedTokens,
            consecutiveFailures = 0,
            uncertaintyScore = 0.2f,
            historyCount = conversationHistory.size,
            memoriesCount = 0,
            complexity = 0.5f
        )
        val decisionResult = decisionService.evaluate(decisionContext)
        val chosenAction = decisionResult.chosenAction
        val initialDecisionState = decisionContext.toDecisionState()

        // Emit DecisionMade Event
        emit(
            ExecutionEvent.DecisionMade(
                executionId = executionId,
                decision = decisionResult
            )
        )

        val resolvedProviderId = if (chosenAction.type == DecisionActionType.SELECT_MODEL && chosenAction.payload.containsKey("providerId")) {
            chosenAction.payload["providerId"]
        } else {
            preferredProviderId
        }

        val provider = registry.getLlmProvider(resolvedProviderId)
        if (provider == null) {
            val errorMsg = "لا يوجد مزود ذكاء اصطناعي متاح حالياً لتنفيذ المهمة."
            persistTaskFinal(task.id.value, "FAILED", null, 0, 0L, false, null, errorMsg)
            val failedObs = EnvironmentObservation(
                action = chosenAction,
                isSuccess = false,
                actualLatencyMs = System.currentTimeMillis() - startTime,
                tokensConsumed = 0,
                errorDescription = errorMsg,
                feedbackReward = -1.0f
            )
            decisionService.recordObservation(initialDecisionState, failedObs)
            emit(
                ExecutionEvent.Error(
                    executionId = executionId,
                    failureCode = "PROVIDER_NOT_FOUND",
                    message = errorMsg
                )
            )
            return@flow
        }

        val modelId = if (chosenAction.type == DecisionActionType.SELECT_MODEL && chosenAction.targetId != null) {
            chosenAction.targetId
        } else {
            provider.metadata.defaultModel ?: "default"
        }

        emit(
            ExecutionEvent.Started(
                executionId = executionId,
                agentId = agent.identity.id,
                modelId = modelId
            )
        )

        var isDegraded = false
        var degradedReason: DegradedReason? = null

        // 2. Prepare Messages and inject System Prompt
        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage(role = MessageRole.SYSTEM, content = agent.identity.systemPrompt))

        // 3. Web Search Ingestion if requested by user or decided by CBR-MDP
        val shouldSearch = includeWebSearch ||
                chosenAction.type == DecisionActionType.SEARCH ||
                task.input.rawPrompt.contains("بحث", ignoreCase = true) ||
                task.input.rawPrompt.contains("search", ignoreCase = true)

        if (shouldSearch && (networkPolicy != NetworkPolicy.OFFLINE || isNetworkAvailable)) {
            val searchProvider = registry.getSearchProvider()
            if (searchProvider != null) {
                val searchQuery = chosenAction.payload["query"] ?: task.input.rawPrompt.take(100)
                when (val searchResult = searchProvider.search(SearchQuery(query = searchQuery))) {
                    is Outcome.Success -> {
                        if (searchResult.value.items.isNotEmpty()) {
                            val searchContext = searchResult.value.items.take(3).joinToString("\n") {
                                "- [${it.title}] (${it.url}): ${it.snippet}"
                            }
                            messages.add(
                                LlmMessage(
                                    role = MessageRole.SYSTEM,
                                    content = "<web_search_results>\n$searchContext\n</web_search_results>"
                                )
                            )
                        }
                    }
                    is Outcome.Degraded -> {
                        isDegraded = true
                        degradedReason = searchResult.reason
                        val partialContext = searchResult.partialValue?.items?.joinToString("\n") { "- ${it.title}: ${it.snippet}" } ?: ""
                        if (partialContext.isNotBlank()) {
                            messages.add(
                                LlmMessage(
                                    role = MessageRole.SYSTEM,
                                    content = "<web_search_results degraded=\"true\">\n$partialContext\n</web_search_results>"
                                )
                            )
                        }
                        emit(
                            ExecutionEvent.Degraded(
                                executionId = executionId,
                                reason = searchResult.reason,
                                message = searchResult.diagnosticMessage
                            )
                        )
                    }
                    else -> Unit
                }
            }
        }

        // 4. Memory / RAG Context Augmentation
        val memoryRepo = registry.getMemoryRepository()
        if (memoryRepo != null) {
            when (val memResult = memoryRepo.retrieveMemories(task.input.rawPrompt, topK = 3)) {
                is Outcome.Success -> {
                    if (memResult.value.isNotEmpty()) {
                        val context = memResult.value.joinToString("\n") { "- ${it.entry.content}" }
                        messages.add(
                            LlmMessage(
                                role = MessageRole.SYSTEM,
                                content = "<retrieved_memory>\n$context\n</retrieved_memory>"
                            )
                        )
                    }
                }
                is Outcome.Degraded -> {
                    isDegraded = true
                    degradedReason = memResult.reason
                    if (!memResult.partialValue.isNullOrEmpty()) {
                        val context = memResult.partialValue.joinToString("\n") { "- ${it.entry.content}" }
                        messages.add(
                            LlmMessage(
                                role = MessageRole.SYSTEM,
                                content = "<retrieved_memory degraded=\"true\">\n$context\n</retrieved_memory>"
                            )
                        )
                    }
                    emit(
                        ExecutionEvent.Degraded(
                            executionId = executionId,
                            reason = memResult.reason,
                            message = memResult.diagnosticMessage
                        )
                    )
                }
                is Outcome.Error -> {
                    isDegraded = true
                    degradedReason = DegradedReason.UNKNOWN_DEGRADATION
                }
            }
        }

        messages.addAll(conversationHistory)
        messages.add(LlmMessage(role = MessageRole.USER, content = task.input.rawPrompt))

        // 5. Prepare Tools
        val availableTools = registry.listTools().map { it.declaration }

        val request = LlmRequest(
            messages = messages,
            availableTools = availableTools,
            streamEvents = true
        )

        // 6. Delegate to Provider Stream
        val textAccumulator = StringBuilder()
        var totalPromptTokens = 0
        var totalCompletionTokens = 0

        try {
            provider.stream(request, executionId).collect { event ->
                when (event) {
                    is ExecutionEvent.ContentChunk -> {
                        textAccumulator.append(event.deltaText)
                        emit(event)
                    }
                    is ExecutionEvent.ToolRequested -> {
                        emit(event)
                        // Execute Tool with Pre-Execution Security Check
                        val toolResultEvent = handleToolExecution(
                            executionId = executionId,
                            callId = event.callId,
                            toolName = event.toolName,
                            argumentsJson = event.argumentsJson
                        )
                        emit(toolResultEvent)
                    }
                    is ExecutionEvent.Degraded -> {
                        isDegraded = true
                        degradedReason = event.reason
                        emit(event)
                    }
                    is ExecutionEvent.UsageBudgetUpdate -> {
                        totalPromptTokens = event.promptTokens
                        totalCompletionTokens = event.completionTokens
                        emit(event)
                    }
                    is ExecutionEvent.Error -> {
                        val duration = System.currentTimeMillis() - startTime
                        persistTaskFinal(task.id.value, "FAILED", null, totalPromptTokens + totalCompletionTokens, duration, isDegraded, degradedReason?.name, event.message)
                        val errorObs = EnvironmentObservation(
                            action = chosenAction,
                            isSuccess = false,
                            actualLatencyMs = duration,
                            tokensConsumed = totalPromptTokens + totalCompletionTokens,
                            errorDescription = event.message,
                            feedbackReward = -0.5f
                        )
                        val updatedState = decisionService.recordObservation(initialDecisionState, errorObs)
                        emit(
                            ExecutionEvent.ObservationRecorded(
                                executionId = executionId,
                                observation = errorObs,
                                updatedUncertainty = updatedState.uncertaintyScore
                            )
                        )
                        emit(event)
                    }
                    is ExecutionEvent.Completed -> {
                        val duration = System.currentTimeMillis() - startTime
                        val finalText = if (event.finalText.isNotEmpty()) event.finalText else textAccumulator.toString()
                        val stateStr = if (isDegraded) "DEGRADED" else "COMPLETED"
                        persistTaskFinal(task.id.value, stateStr, finalText.take(200), totalPromptTokens + totalCompletionTokens, duration, isDegraded, degradedReason?.name, null)

                        // Record Successful Observation to CBR-MDP Engine
                        val successObs = EnvironmentObservation(
                            action = chosenAction,
                            isSuccess = true,
                            actualLatencyMs = duration,
                            tokensConsumed = totalPromptTokens + totalCompletionTokens,
                            outputSummary = finalText.take(150),
                            feedbackReward = if (isDegraded) 0.6f else 1.0f
                        )
                        val updatedState = decisionService.recordObservation(initialDecisionState, successObs)
                        emit(
                            ExecutionEvent.ObservationRecorded(
                                executionId = executionId,
                                observation = successObs,
                                updatedUncertainty = updatedState.uncertaintyScore
                            )
                        )

                        emit(
                            event.copy(
                                finalText = finalText,
                                isDegraded = isDegraded,
                                degradedReason = degradedReason
                            )
                        )
                    }
                    else -> emit(event)
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            val msg = e.localizedMessage ?: "حدث خطأ غير متوقع أثناء تنسيق تنفيذ الوكيل."
            persistTaskFinal(task.id.value, "FAILED", null, totalPromptTokens + totalCompletionTokens, duration, isDegraded, degradedReason?.name, msg)
            val excObs = EnvironmentObservation(
                action = chosenAction,
                isSuccess = false,
                actualLatencyMs = duration,
                tokensConsumed = totalPromptTokens + totalCompletionTokens,
                errorDescription = msg,
                feedbackReward = -0.8f
            )
            decisionService.recordObservation(initialDecisionState, excObs)
            emit(
                ExecutionEvent.Error(
                    executionId = executionId,
                    failureCode = "ORCHESTRATION_EXCEPTION",
                    message = msg
                )
            )
        }
    }

    /**
     * Executes a standard non-streaming agent task with complete security and memory guarantees.
     */
    suspend fun executeTask(
        agent: AgentDefinition,
        task: TaskDefinition,
        conversationHistory: List<LlmMessage> = emptyList(),
        preferredProviderId: String? = null
    ): Outcome<LlmResponse, LlmFailure> {
        val startTime = System.currentTimeMillis()
        val decisionContext = decisionService.buildDecisionContext(
            task = task,
            historyCount = conversationHistory.size
        )
        val decisionResult = decisionService.evaluate(decisionContext)
        val chosenAction = decisionResult.chosenAction
        val initialDecisionState = decisionContext.toDecisionState()

        val provider = registry.getLlmProvider(preferredProviderId)
            ?: return Outcome.Error(
                failure = LlmFailure.ProviderUnavailable(preferredProviderId ?: "default", "لا يوجد مزود ذكاء اصطناعي متاح."),
                diagnosticMessage = "المزود غير مسجل."
            )

        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage(role = MessageRole.SYSTEM, content = agent.identity.systemPrompt))

        // Memory RAG
        val memoryRepo = registry.getMemoryRepository()
        var isDegraded = false
        var degradedReason: DegradedReason? = null
        if (memoryRepo != null) {
            when (val memResult = memoryRepo.retrieveMemories(task.input.rawPrompt, topK = 3)) {
                is Outcome.Success -> {
                    if (memResult.value.isNotEmpty()) {
                        val context = memResult.value.joinToString("\n") { "- ${it.entry.content}" }
                        messages.add(LlmMessage(role = MessageRole.SYSTEM, content = "<retrieved_memory>\n$context\n</retrieved_memory>"))
                    }
                }
                is Outcome.Degraded -> {
                    isDegraded = true
                    degradedReason = memResult.reason
                    if (!memResult.partialValue.isNullOrEmpty()) {
                        val context = memResult.partialValue.joinToString("\n") { "- ${it.entry.content}" }
                        messages.add(LlmMessage(role = MessageRole.SYSTEM, content = "<retrieved_memory degraded=\"true\">\n$context\n</retrieved_memory>"))
                    }
                }
                is Outcome.Error -> {
                    isDegraded = true
                    degradedReason = DegradedReason.UNKNOWN_DEGRADATION
                }
            }
        }

        messages.addAll(conversationHistory)
        messages.add(LlmMessage(role = MessageRole.USER, content = task.input.rawPrompt))

        val request = LlmRequest(
            messages = messages,
            availableTools = registry.listTools().map { it.declaration }
        )

        return when (val outcome = provider.generate(request)) {
            is Outcome.Success -> {
                val duration = System.currentTimeMillis() - startTime
                val successObs = EnvironmentObservation(
                    action = chosenAction,
                    isSuccess = true,
                    actualLatencyMs = duration,
                    tokensConsumed = outcome.value.usage.totalTokens,
                    outputSummary = outcome.value.text.take(100),
                    feedbackReward = 1.0f
                )
                decisionService.recordObservation(initialDecisionState, successObs)
                if (isDegraded) {
                    Outcome.Degraded(
                        partialValue = outcome.value,
                        reason = degradedReason ?: DegradedReason.UNKNOWN_DEGRADATION,
                        diagnosticMessage = "تم التنفيذ مع تراجع في استرجاع الذاكرة.",
                        metadata = outcome.metadata
                    )
                } else {
                    outcome
                }
            }
            is Outcome.Degraded -> {
                val duration = System.currentTimeMillis() - startTime
                val degradedObs = EnvironmentObservation(
                    action = chosenAction,
                    isSuccess = true,
                    actualLatencyMs = duration,
                    tokensConsumed = outcome.partialValue?.usage?.totalTokens ?: 0,
                    outputSummary = outcome.partialValue?.text?.take(100) ?: "",
                    feedbackReward = 0.5f
                )
                decisionService.recordObservation(initialDecisionState, degradedObs)
                outcome
            }
            is Outcome.Error -> {
                val duration = System.currentTimeMillis() - startTime
                val errorObs = EnvironmentObservation(
                    action = chosenAction,
                    isSuccess = false,
                    actualLatencyMs = duration,
                    tokensConsumed = 0,
                    errorDescription = outcome.diagnosticMessage,
                    feedbackReward = -0.5f
                )
                decisionService.recordObservation(initialDecisionState, errorObs)
                outcome
            }
        }
    }

    private suspend fun handleToolExecution(
        executionId: String,
        callId: String,
        toolName: String,
        argumentsJson: String
    ): ExecutionEvent.ToolResult {
        val tool = registry.getTool(toolName)
        if (tool == null) {
            return ExecutionEvent.ToolResult(
                executionId = executionId,
                callId = callId,
                toolName = toolName,
                outcome = Outcome.Error(
                    failure = ToolFailure.CapabilityUnavailable(toolName, "الأداة المطلوبة غير مسجلة في النظام.")
                )
            )
        }

        val toolInput = ToolInput(
            toolName = toolName,
            arguments = parseSimpleArguments(argumentsJson),
            executionId = executionId
        )

        // 1. Mandatory Pre-Execution Security Evaluation
        val evaluation = securityGuard.evaluateToolExecution(toolInput, defaultSecurityPolicy)
        if (evaluation.decision == SecurityDecision.DENY) {
            return ExecutionEvent.ToolResult(
                executionId = executionId,
                callId = callId,
                toolName = toolName,
                outcome = Outcome.Error(
                    failure = ToolFailure.SecurityDenied(
                        ruleName = evaluation.matchedRule ?: "SECURITY_DENIED",
                        message = evaluation.explanation
                    )
                )
            )
        }

        // 2. Execute Tool
        val executionOutcome = tool.execute(toolInput)

        // 3. Sanitize and Frame Untrusted Output
        val finalOutcome = when (executionOutcome) {
            is Outcome.Success -> {
                val sanitized = securityGuard.sanitizeUntrustedOutput(executionOutcome.value.content)
                Outcome.Success(sanitized, executionOutcome.metadata)
            }
            is Outcome.Degraded -> {
                val sanitized = executionOutcome.partialValue?.content?.let { securityGuard.sanitizeUntrustedOutput(it) } ?: ""
                Outcome.Degraded(
                    partialValue = sanitized,
                    reason = executionOutcome.reason,
                    diagnosticMessage = executionOutcome.diagnosticMessage,
                    underlyingFailure = executionOutcome.underlyingFailure,
                    metadata = executionOutcome.metadata
                )
            }
            is Outcome.Error -> Outcome.Error(executionOutcome.failure, executionOutcome.diagnosticMessage)
        }

        return ExecutionEvent.ToolResult(
            executionId = executionId,
            callId = callId,
            toolName = toolName,
            outcome = finalOutcome
        )
    }

    private fun persistTaskInitial(task: TaskDefinition, agent: AgentDefinition) {
        if (taskDao == null) return
        coroutineScope.launch {
            try {
                taskDao.insertOrUpdateTask(
                    TaskEntity(
                        id = task.id.value,
                        assignedAgentId = agent.identity.id.value,
                        rawPrompt = task.input.rawPrompt,
                        lifecycleState = "RUNNING",
                        autonomyPolicy = "SUPERVISED",
                        resultSummary = null,
                        totalTokensConsumed = 0,
                        durationMs = 0L,
                        isDegraded = false,
                        degradedReason = null,
                        errorMessage = null,
                        createdAtEpochMs = System.currentTimeMillis(),
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private fun persistTaskFinal(
        taskId: String,
        state: String,
        summary: String?,
        tokens: Int,
        duration: Long,
        isDegraded: Boolean,
        degradedReason: String?,
        errorMsg: String?
    ) {
        if (taskDao == null) return
        coroutineScope.launch {
            try {
                taskDao.updateTaskStatus(
                    id = taskId,
                    state = state,
                    summary = summary,
                    tokens = tokens,
                    duration = duration,
                    isDegraded = isDegraded,
                    degradedReason = degradedReason,
                    errorMsg = errorMsg,
                    now = System.currentTimeMillis()
                )
            } catch (_: Exception) {}
        }
    }

    private fun parseSimpleArguments(json: String): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val clean = json.trim().removeSurrounding("{", "}").trim()
        if (clean.isEmpty()) return result

        clean.split(",").forEach { pair ->
            val parts = pair.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim().removeSurrounding("\"")
                val value = parts[1].trim().removeSurrounding("\"")
                result[key] = value
            }
        }
        return result
    }
}
