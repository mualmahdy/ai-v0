package com.example.application.orchestration

import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.MessageRole
import com.example.domain.core.security.SecurityDecision
import com.example.domain.core.security.SecurityPolicy
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * Core Orchestrator coordinating Agent Execution, LLM Calls, Tool Invocations,
 * Security Gateways, and Operational Event Streams.
 */
class AgentOrchestrator(
    private val registry: ComponentRegistry,
    private val securityGuard: SecurityGuardService,
    private val defaultSecurityPolicy: SecurityPolicy = SecurityPolicy()
) {

    /**
     * Executes an agent task via reactive event streaming.
     */
    fun executeTaskStream(
        agent: AgentDefinition,
        task: TaskDefinition,
        conversationHistory: List<LlmMessage> = emptyList(),
        preferredProviderId: String? = null
    ): Flow<ExecutionEvent> = flow {
        val executionId = UUID.randomUUID().toString()
        val provider = registry.getLlmProvider(preferredProviderId)

        if (provider == null) {
            emit(
                ExecutionEvent.Error(
                    executionId = executionId,
                    failureCode = "PROVIDER_NOT_FOUND",
                    message = "لا يوجد مزود ذكاء اصطناعي متاح حالياً لتنفيذ المهمة."
                )
            )
            return@flow
        }

        emit(
            ExecutionEvent.Started(
                executionId = executionId,
                agentId = agent.identity.id,
                modelId = provider.metadata.defaultModel ?: "default"
            )
        )

        // 1. Prepare Messages
        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage(role = MessageRole.SYSTEM, content = agent.identity.systemPrompt))
        messages.addAll(conversationHistory)
        messages.add(LlmMessage(role = MessageRole.USER, content = task.input.rawPrompt))

        // 2. Prepare Tools
        val availableTools = registry.listTools().map { it.declaration }

        val request = LlmRequest(
            messages = messages,
            availableTools = availableTools,
            streamEvents = true
        )

        // 3. Delegate to Provider Stream
        val textAccumulator = StringBuilder()
        var isDegraded = false
        var degradedReason: DegradedReason? = null

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
                        emit(event)
                    }
                    is ExecutionEvent.Error -> {
                        emit(event)
                    }
                    is ExecutionEvent.Completed -> {
                        emit(
                            event.copy(
                                finalText = if (event.finalText.isNotEmpty()) event.finalText else textAccumulator.toString(),
                                isDegraded = isDegraded,
                                degradedReason = degradedReason
                            )
                        )
                    }
                    else -> emit(event)
                }
            }
        } catch (e: Exception) {
            emit(
                ExecutionEvent.Error(
                    executionId = executionId,
                    failureCode = "ORCHESTRATION_EXCEPTION",
                    message = e.localizedMessage ?: "حدث خطأ غير متوقع أثناء تنسيق تنفيذ الوكيل."
                )
            )
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

        // 3. Sanitize Output if required
        val finalOutcome = when (executionOutcome) {
            is Outcome.Success -> {
                val sanitized = securityGuard.sanitizeUntrustedOutput(executionOutcome.value.content)
                Outcome.Success(sanitized, executionOutcome.metadata)
            }
            is Outcome.Degraded -> {
                val sanitized = executionOutcome.partialValue?.content?.let { securityGuard.sanitizeUntrustedOutput(it) }
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

    private fun parseSimpleArguments(json: String): Map<String, Any?> {
        // Safe key-value argument mapping for basic primitives
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
