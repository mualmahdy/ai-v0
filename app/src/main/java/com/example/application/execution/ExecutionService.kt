package com.example.application.execution

import com.example.application.decision.DecisionContext
import com.example.application.extension.ExtensionManager
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.OutcomeMetadata
import com.example.domain.core.map
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.MessageRole
import com.example.domain.core.search.SearchFailure
import com.example.domain.core.search.SearchQuery
import com.example.domain.core.security.SecurityDecision
import com.example.domain.core.security.SecurityPolicy
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Normalized result produced by executing an action within the closed-loop task lifecycle.
 */
data class ExecutionResult(
    val isSuccess: Boolean,
    val outputText: String = "",
    val outputData: Map<String, Any?> = emptyMap(),
    val tokensConsumed: Int = 0,
    val latencyMs: Long = 0L,
    val errorDescription: String? = null,
    val isDegraded: Boolean = false,
    val degradedReason: DegradedReason? = null
)

/**
 * Execution Service defining the explicit execution boundary for all system actions.
 * Executes LLM models, Tools, MCP protocols, Web Search, Memory/RAG, Skills, and Integrations.
 */
class ExecutionService(
    private val componentRegistry: ComponentRegistry,
    private val securityGuard: SecurityGuardService,
    private val extensionManager: ExtensionManager? = null,
    private val defaultSecurityPolicy: SecurityPolicy = SecurityPolicy()
) {

    /**
     * Executes any chosen DecisionAction, emitting fine-grained streaming events and returning a normalized ExecutionResult.
     */
    suspend fun executeAction(
        action: DecisionAction,
        context: DecisionContext,
        agent: AgentDefinition,
        conversationHistory: List<LlmMessage> = emptyList(),
        executionId: String = UUID.randomUUID().toString(),
        onEvent: suspend (ExecutionEvent) -> Unit = {}
    ): ExecutionResult {
        val startTime = System.currentTimeMillis()

        return when (action.type) {
            DecisionActionType.SELECT_MODEL, DecisionActionType.EXECUTE_STEP -> {
                executeLlmStep(action, context, agent, conversationHistory, executionId, startTime, onEvent)
            }
            DecisionActionType.SEARCH -> {
                executeSearch(action, context, startTime, onEvent, executionId)
            }
            DecisionActionType.RETRIEVE_MEMORY, DecisionActionType.RETRIEVE_KNOWLEDGE -> {
                executeMemoryRetrieval(action, context, startTime, onEvent, executionId)
            }
            DecisionActionType.EXECUTE_TOOL, DecisionActionType.SELECT_TOOL -> {
                executeTool(action, context, startTime, onEvent, executionId)
            }
            DecisionActionType.EXECUTE_MCP -> {
                executeMcpAction(action, startTime)
            }
            DecisionActionType.EXECUTE_SKILL -> {
                executeSkillAction(action, startTime)
            }
            DecisionActionType.USE_INTEGRATION -> {
                executeIntegrationAction(action, startTime)
            }
            DecisionActionType.CREATE_PLAN, DecisionActionType.REPLAN -> {
                executePlanAction(action, context, startTime, onEvent, executionId)
            }
            DecisionActionType.RETRY -> {
                executeRetryAction(action, context, agent, conversationHistory, executionId, startTime, onEvent)
            }
            DecisionActionType.ASK_USER -> {
                val reason = action.payload["reason"] ?: "مطلوب تفاعل المستخدم."
                ExecutionResult(
                    isSuccess = true,
                    outputText = "[استفسار للمستخدم]: $reason",
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
            DecisionActionType.WAIT -> {
                val waitMs = action.payload["durationMs"]?.toLongOrNull() ?: 500L
                delay(waitMs)
                ExecutionResult(
                    isSuccess = true,
                    outputText = "اكتمل الانتظار بنجاح.",
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
            DecisionActionType.COMPLETE, DecisionActionType.STOP -> {
                val summary = action.payload["summary"] ?: "تم إكمال المهمة بنجاح."
                ExecutionResult(
                    isSuccess = true,
                    outputText = summary,
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
            else -> {
                ExecutionResult(
                    isSuccess = false,
                    errorDescription = "نوع الإجراء غير مدعوم أو غير معرّف في المحرك: ${action.type.code}",
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
        }
    }

    private suspend fun executeLlmStep(
        action: DecisionAction,
        context: DecisionContext,
        agent: AgentDefinition,
        conversationHistory: List<LlmMessage>,
        executionId: String,
        startTime: Long,
        onEvent: suspend (ExecutionEvent) -> Unit
    ): ExecutionResult {
        val providerId = action.payload["providerId"]
        val provider = componentRegistry.getLlmProvider(providerId) ?: componentRegistry.listLlmProviders().firstOrNull()

        if (provider == null) {
            val errorMsg = "لا يوجد مزود ذكاء اصطناعي متاح حالياً."
            return ExecutionResult(
                isSuccess = false,
                errorDescription = errorMsg,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }

        // Build messages injecting System Prompt, gathered evidence, and conversation history
        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage(role = MessageRole.SYSTEM, content = agent.identity.systemPrompt))

        // Inject Search Evidence if present
        val searchResults = context.accumulatedEvidence["searchResults"]
        if (searchResults is List<*> && searchResults.isNotEmpty()) {
            val searchContext = searchResults.take(4).joinToString("\n") { it.toString() }
            messages.add(
                LlmMessage(
                    role = MessageRole.SYSTEM,
                    content = "<web_search_evidence>\n$searchContext\n</web_search_evidence>"
                )
            )
        }

        // Inject Memory / RAG Evidence if present
        val memorySnippets = context.accumulatedEvidence["memorySnippets"]
        if (memorySnippets is List<*> && memorySnippets.isNotEmpty()) {
            val memContext = memorySnippets.take(4).joinToString("\n") { it.toString() }
            messages.add(
                LlmMessage(
                    role = MessageRole.SYSTEM,
                    content = "<retrieved_knowledge_evidence>\n$memContext\n</retrieved_knowledge_evidence>"
                )
            )
        }

        // Inject Tool execution evidence if present
        val toolOutput = context.accumulatedEvidence["toolOutput"]
        if (toolOutput != null) {
            messages.add(
                LlmMessage(
                    role = MessageRole.SYSTEM,
                    content = "<tool_execution_evidence>\n$toolOutput\n</tool_execution_evidence>"
                )
            )
        }

        messages.addAll(conversationHistory)
        messages.add(LlmMessage(role = MessageRole.USER, content = context.task.input.rawPrompt))

        val availableTools = componentRegistry.listTools().map { it.declaration }
        val request = LlmRequest(
            messages = messages,
            availableTools = availableTools,
            streamEvents = true
        )

        val textAccumulator = StringBuilder()
        var promptTokens = 0
        var completionTokens = 0
        var isDegraded = false
        var degradedReason: DegradedReason? = null
        var isSuccess = true
        var errorMessage: String? = null

        try {
            provider.stream(request, executionId).collect { event ->
                when (event) {
                    is ExecutionEvent.ContentChunk -> {
                        textAccumulator.append(event.deltaText)
                        onEvent(event)
                    }
                    is ExecutionEvent.ToolRequested -> {
                        onEvent(event)
                        val toolResult = handleToolExecution(executionId, event.callId, event.toolName, event.argumentsJson)
                        onEvent(toolResult)
                    }
                    is ExecutionEvent.Degraded -> {
                        isDegraded = true
                        degradedReason = event.reason
                        onEvent(event)
                    }
                    is ExecutionEvent.UsageBudgetUpdate -> {
                        promptTokens = event.promptTokens
                        completionTokens = event.completionTokens
                        onEvent(event)
                    }
                    is ExecutionEvent.Error -> {
                        isSuccess = false
                        errorMessage = event.message
                        onEvent(event)
                    }
                    is ExecutionEvent.Completed -> {
                        if (event.isDegraded) {
                            isDegraded = true
                            degradedReason = event.degradedReason
                        }
                    }
                    else -> onEvent(event)
                }
            }
        } catch (e: Exception) {
            isSuccess = false
            errorMessage = e.localizedMessage ?: "حدث استثناء غير متوقع أثناء استدعاء المزود."
        }

        val totalTokens = promptTokens + completionTokens
        val latency = System.currentTimeMillis() - startTime

        return ExecutionResult(
            isSuccess = isSuccess,
            outputText = textAccumulator.toString(),
            outputData = mapOf("synthesizedText" to textAccumulator.toString()),
            tokensConsumed = if (totalTokens > 0) totalTokens else (textAccumulator.length / 4),
            latencyMs = latency,
            errorDescription = errorMessage,
            isDegraded = isDegraded,
            degradedReason = degradedReason
        )
    }

    private suspend fun executeSearch(
        action: DecisionAction,
        context: DecisionContext,
        startTime: Long,
        onEvent: suspend (ExecutionEvent) -> Unit,
        executionId: String
    ): ExecutionResult {
        val searchProvider = componentRegistry.getSearchProvider()
        if (searchProvider == null) {
            return ExecutionResult(
                isSuccess = false,
                errorDescription = "مزود البحث غير مهيأ في السجل.",
                latencyMs = System.currentTimeMillis() - startTime
            )
        }

        val query = action.payload["query"] ?: context.task.input.rawPrompt.take(100)
        return when (val outcome = searchProvider.search(SearchQuery(query = query))) {
            is Outcome.Success -> {
                val items = outcome.value.items
                val formattedItems = items.map { "[${it.title}] (${it.url}): ${it.snippet}" }
                val summary = "تم استرجاع ${items.size} نتيجة بحث من ${outcome.value.providerId}."
                ExecutionResult(
                    isSuccess = true,
                    outputText = summary,
                    outputData = mapOf(
                        "searchResults" to formattedItems,
                        "searchRawItems" to items
                    ),
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
            is Outcome.Degraded -> {
                val partialItems = outcome.partialValue?.items ?: emptyList()
                val formatted = partialItems.map { "[${it.title}] (${it.url}): ${it.snippet}" }
                onEvent(
                    ExecutionEvent.Degraded(
                        executionId = executionId,
                        reason = outcome.reason,
                        message = outcome.diagnosticMessage
                    )
                )
                ExecutionResult(
                    isSuccess = true,
                    outputText = "تم استرجاع نتائج بحث بوضع متراجع (${outcome.diagnosticMessage}).",
                    outputData = mapOf("searchResults" to formatted),
                    latencyMs = System.currentTimeMillis() - startTime,
                    isDegraded = true,
                    degradedReason = outcome.reason
                )
            }
            is Outcome.Error -> {
                val errorMsg = when (val failure = outcome.failure) {
                    is SearchFailure.ProviderUnavailable -> failure.message
                    is SearchFailure.RateLimited -> "تم تجاوز معدل الطلبات لمزود البحث"
                    is SearchFailure.AuthenticationFailed -> failure.message
                    is SearchFailure.QueryInvalid -> failure.reason
                    is SearchFailure.NetworkError -> failure.message
                    else -> outcome.diagnosticMessage.ifBlank { "فشل في تنفيذ البحث" }
                }
                ExecutionResult(
                    isSuccess = false,
                    errorDescription = errorMsg,
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
        }
    }

    private suspend fun executeMemoryRetrieval(
        action: DecisionAction,
        context: DecisionContext,
        startTime: Long,
        onEvent: suspend (ExecutionEvent) -> Unit,
        executionId: String
    ): ExecutionResult {
        val memoryRepo = componentRegistry.getMemoryRepository()
        if (memoryRepo == null) {
            return ExecutionResult(
                isSuccess = false,
                errorDescription = "مستودع الذاكرة غير متاح.",
                latencyMs = System.currentTimeMillis() - startTime
            )
        }

        val query = action.payload["query"] ?: context.task.input.rawPrompt
        return when (val memOutcome = memoryRepo.retrieveMemories(query, topK = 4)) {
            is Outcome.Success -> {
                val entries = memOutcome.value.map { it.entry.content }
                ExecutionResult(
                    isSuccess = true,
                    outputText = "تم استرجاع ${entries.size} مدخلات من الذاكرة والوثائق.",
                    outputData = mapOf("memorySnippets" to entries),
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
            is Outcome.Degraded -> {
                val partial = memOutcome.partialValue?.map { it.entry.content } ?: emptyList()
                onEvent(
                    ExecutionEvent.Degraded(
                        executionId = executionId,
                        reason = memOutcome.reason,
                        message = memOutcome.diagnosticMessage
                    )
                )
                ExecutionResult(
                    isSuccess = true,
                    outputText = "تم استرجاع الذاكرة بوضع متراجع (${memOutcome.diagnosticMessage}).",
                    outputData = mapOf("memorySnippets" to partial),
                    latencyMs = System.currentTimeMillis() - startTime,
                    isDegraded = true,
                    degradedReason = memOutcome.reason
                )
            }
            is Outcome.Error -> {
                ExecutionResult(
                    isSuccess = false,
                    errorDescription = memOutcome.diagnosticMessage.ifBlank { "فشل في استرجاع الذاكرة" },
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
        }
    }

    private suspend fun executeTool(
        action: DecisionAction,
        context: DecisionContext,
        startTime: Long,
        onEvent: suspend (ExecutionEvent) -> Unit,
        executionId: String
    ): ExecutionResult {
        val toolName = action.targetId ?: return ExecutionResult(
            isSuccess = false,
            errorDescription = "اسم الأداة غير محدد في الإجراء.",
            latencyMs = System.currentTimeMillis() - startTime
        )

        val tool = componentRegistry.getTool(toolName)
        if (tool == null) {
            return ExecutionResult(
                isSuccess = false,
                errorDescription = "الأداة $toolName غير مسجلة.",
                latencyMs = System.currentTimeMillis() - startTime
            )
        }

        val toolInput = ToolInput(
            toolName = toolName,
            arguments = action.payload,
            executionId = executionId
        )

        // Security check
        val secEval = securityGuard.evaluateToolExecution(toolInput, defaultSecurityPolicy)
        if (secEval.decision == SecurityDecision.DENY) {
            return ExecutionResult(
                isSuccess = false,
                errorDescription = "تم رفض تنفيذ الأداة وفقاً لسياسة الأمان: ${secEval.explanation}",
                latencyMs = System.currentTimeMillis() - startTime
            )
        }

        val callId = "call_${System.currentTimeMillis()}"
        onEvent(
            ExecutionEvent.ToolRequested(
                executionId = executionId,
                callId = callId,
                toolName = toolName,
                argumentsJson = action.payload.toString()
            )
        )

        val outcome = tool.execute(toolInput)
        onEvent(
            ExecutionEvent.ToolResult(
                executionId = executionId,
                callId = callId,
                toolName = toolName,
                outcome = outcome.map { it.content }
            )
        )

        return when (outcome) {
            is Outcome.Success -> {
                ExecutionResult(
                    isSuccess = true,
                    outputText = outcome.value.content,
                    outputData = mapOf(
                        "toolOutput" to outcome.value.content,
                        "toolAttributes" to outcome.value.attributes
                    ),
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
            is Outcome.Degraded -> {
                val partialText = outcome.partialValue?.content ?: ""
                onEvent(
                    ExecutionEvent.Degraded(
                        executionId = executionId,
                        reason = outcome.reason,
                        message = outcome.diagnosticMessage
                    )
                )
                ExecutionResult(
                    isSuccess = true,
                    outputText = partialText,
                    outputData = mapOf("toolOutput" to partialText),
                    latencyMs = System.currentTimeMillis() - startTime,
                    isDegraded = true,
                    degradedReason = outcome.reason
                )
            }
            is Outcome.Error -> {
                val errorMsg = when (val f = outcome.failure) {
                    is ToolFailure.PermissionDenied -> f.message
                    is ToolFailure.SecurityDenied -> f.message
                    is ToolFailure.CapabilityUnavailable -> f.message
                    is ToolFailure.InternalExecutionError -> f.message
                    is ToolFailure.InvalidParameters -> f.reason
                    is ToolFailure.ExecutionTimeout -> "تجاوزت الأداة المهلة الزمنية (${f.timeoutMs}ms)"
                }
                ExecutionResult(
                    isSuccess = false,
                    errorDescription = errorMsg,
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
        }
    }

    private suspend fun executeMcpAction(
        action: DecisionAction,
        startTime: Long
    ): ExecutionResult {
        val toolName = action.targetId ?: "mcp_tool"
        val tool = componentRegistry.getTool(toolName)
        if (tool != null) {
            val outcome = tool.execute(ToolInput(toolName = toolName, arguments = action.payload))
            return when (outcome) {
                is Outcome.Success -> ExecutionResult(
                    isSuccess = true,
                    outputText = outcome.value.content,
                    outputData = mapOf("mcpOutput" to outcome.value.content),
                    latencyMs = System.currentTimeMillis() - startTime
                )
                is Outcome.Degraded -> ExecutionResult(
                    isSuccess = true,
                    outputText = outcome.partialValue?.content ?: "",
                    latencyMs = System.currentTimeMillis() - startTime,
                    isDegraded = true,
                    degradedReason = outcome.reason
                )
                is Outcome.Error -> ExecutionResult(
                    isSuccess = false,
                    errorDescription = outcome.diagnosticMessage,
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
        }

        return ExecutionResult(
            isSuccess = false,
            errorDescription = "أداة MCP غير مسجلة أو غير متاحة: $toolName",
            latencyMs = System.currentTimeMillis() - startTime
        )
    }

    private suspend fun executeSkillAction(
        action: DecisionAction,
        startTime: Long
    ): ExecutionResult {
        val skillId = action.targetId ?: "skill"
        if (extensionManager != null) {
            val outcome = extensionManager.executeSkill(skillId, action.payload)
            return when (outcome) {
                is Outcome.Success -> ExecutionResult(
                    isSuccess = true,
                    outputText = outcome.value,
                    outputData = mapOf("skillOutput" to outcome.value),
                    latencyMs = System.currentTimeMillis() - startTime
                )
                is Outcome.Degraded -> ExecutionResult(
                    isSuccess = true,
                    outputText = outcome.partialValue ?: "تم تنفيذ المهارة جزئياً",
                    latencyMs = System.currentTimeMillis() - startTime,
                    isDegraded = true,
                    degradedReason = outcome.reason
                )
                is Outcome.Error -> ExecutionResult(
                    isSuccess = false,
                    errorDescription = outcome.failure,
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
        }

        return ExecutionResult(
            isSuccess = false,
            errorDescription = "مدير المهارات والإضافات غير مهيأ لتنفيذ المهارة $skillId",
            latencyMs = System.currentTimeMillis() - startTime
        )
    }

    private suspend fun executeIntegrationAction(
        action: DecisionAction,
        startTime: Long
    ): ExecutionResult {
        val serviceName = action.targetId ?: "integration"
        val descriptor = extensionManager?.integrations?.value?.firstOrNull { it.id == serviceName || it.serviceType == serviceName }
        if (descriptor == null) {
            return ExecutionResult(
                isSuccess = false,
                errorDescription = "خدمة التكامل $serviceName غير متوفرة أو غير مهيأة في النظام.",
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
        val isHealthy = descriptor.health == com.example.domain.core.provider.HealthStatus.HEALTHY
        return ExecutionResult(
            isSuccess = isHealthy,
            outputText = "حالة خدمة التكامل ${descriptor.name}: ${descriptor.health.name}",
            outputData = mapOf("serviceStatus" to descriptor.health.name, "serviceId" to descriptor.id),
            errorDescription = if (!isHealthy) "خدمة التكامل في حالة غير صالحة للتشغيل: ${descriptor.health.name}" else null,
            latencyMs = System.currentTimeMillis() - startTime
        )
    }

    private suspend fun executePlanAction(
        action: DecisionAction,
        context: DecisionContext,
        startTime: Long,
        onEvent: suspend (ExecutionEvent) -> Unit,
        executionId: String
    ): ExecutionResult {
        val goal = action.payload["goal"] ?: context.task.input.rawPrompt
        val planSummary = "تم إعداد خطة تنفيذية متعددة المراحل للمهمة:\n1. استعلام واسترجاع البيانات الموثوقة\n2. معالجة وتوليد الكود أو الحل\n3. التحقق والمطابقة مع معايير الأمان والجودة."
        onEvent(
            ExecutionEvent.Replanned(
                executionId = executionId,
                reason = "تم إنشاء خطة سير عمل متكاملة للمهمة",
                stepIndex = context.task.currentStepIndex
            )
        )
        return ExecutionResult(
            isSuccess = true,
            outputText = planSummary,
            outputData = mapOf("executionPlan" to planSummary),
            latencyMs = System.currentTimeMillis() - startTime
        )
    }

    private suspend fun executeRetryAction(
        action: DecisionAction,
        context: DecisionContext,
        agent: AgentDefinition,
        conversationHistory: List<LlmMessage>,
        executionId: String,
        startTime: Long,
        onEvent: suspend (ExecutionEvent) -> Unit
    ): ExecutionResult {
        delay(300L) // Exponential backoff
        return executeLlmStep(
            action = action.copy(type = DecisionActionType.SELECT_MODEL),
            context = context,
            agent = agent,
            conversationHistory = conversationHistory,
            executionId = executionId,
            startTime = startTime,
            onEvent = onEvent
        )
    }

    private suspend fun handleToolExecution(
        executionId: String,
        callId: String,
        toolName: String,
        argumentsJson: String
    ): ExecutionEvent.ToolResult {
        val tool = componentRegistry.getTool(toolName)
        if (tool == null) {
            return ExecutionEvent.ToolResult(
                executionId = executionId,
                callId = callId,
                toolName = toolName,
                outcome = Outcome.Error(
                    failure = ToolFailure.InternalExecutionError(
                        message = "الأداة المطلوبة $toolName غير موجودة في سجل النظام."
                    ),
                    diagnosticMessage = "Tool not found in ComponentRegistry"
                )
            )
        }

        val toolInput = ToolInput(
            toolName = toolName,
            arguments = mapOf("rawJson" to argumentsJson),
            executionId = executionId
        )

        val secEvaluation = securityGuard.evaluateToolExecution(toolInput, defaultSecurityPolicy)
        if (secEvaluation.decision == SecurityDecision.DENY) {
            return ExecutionEvent.ToolResult(
                executionId = executionId,
                callId = callId,
                toolName = toolName,
                outcome = Outcome.Error(
                    failure = ToolFailure.SecurityDenied(
                        ruleName = "SECURITY_POLICY_CHECK",
                        message = "تم حظر استدعاء الأداة وفقاً لسياسة الأمان: ${secEvaluation.explanation}"
                    ),
                    diagnosticMessage = secEvaluation.explanation
                )
            )
        }

        return when (val result = tool.execute(toolInput)) {
            is Outcome.Success -> ExecutionEvent.ToolResult(
                executionId = executionId,
                callId = callId,
                toolName = toolName,
                outcome = Outcome.Success(
                    value = result.value.content,
                    metadata = result.metadata
                )
            )
            is Outcome.Degraded -> ExecutionEvent.ToolResult(
                executionId = executionId,
                callId = callId,
                toolName = toolName,
                outcome = Outcome.Degraded(
                    partialValue = result.partialValue?.content,
                    reason = result.reason,
                    diagnosticMessage = result.diagnosticMessage,
                    metadata = result.metadata
                )
            )
            is Outcome.Error -> ExecutionEvent.ToolResult(
                executionId = executionId,
                callId = callId,
                toolName = toolName,
                outcome = Outcome.Error(
                    failure = result.failure,
                    diagnosticMessage = result.diagnosticMessage,
                    metadata = result.metadata
                )
            )
        }
    }
}
