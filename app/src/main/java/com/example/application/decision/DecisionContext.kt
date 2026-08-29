package com.example.application.decision

import com.example.domain.core.capability.CapabilityDescriptor
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.workspace.ResourceGraph
import com.example.domain.core.workspace.Workspace

/**
 * Rich, multi-dimensional execution and reasoning context supplied to the CBR-MDP Decision Engine.
 * Represents the complete state across Workspace, Resource Graph, Task, Capabilities, Memory, and Environment.
 */
data class DecisionContext(
    val task: TaskDefinition,
    val workspace: Workspace? = null,
    val resourceGraph: ResourceGraph = ResourceGraph(),
    val capabilities: List<CapabilityDescriptor> = emptyList(),
    val availableTools: List<String> = emptyList(),
    val networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
    val isNetworkAvailable: Boolean = true,
    val remainingTokenBudget: Int = 30000,
    val consecutiveFailures: Int = 0,
    val uncertaintyScore: Float = 0.2f,
    val conversationHistoryCount: Int = 0,
    val retrievedMemoriesCount: Int = 0,
    val taskComplexity: Float = 0.5f,
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * Projects this rich context into the normalized mathematical DecisionState vector
     * required by the CBR-MDP Decision Engine.
     */
    fun toDecisionState(): DecisionState {
        val prompt = task.input.rawPrompt
        val requiresVision = prompt.contains("صورة", ignoreCase = true) ||
                prompt.contains("vision", ignoreCase = true) ||
                prompt.contains("image", ignoreCase = true)
        val requiresToolCalling = prompt.contains("ملف", ignoreCase = true) ||
                prompt.contains("بحث", ignoreCase = true) ||
                prompt.contains("أداة", ignoreCase = true) ||
                prompt.contains("file", ignoreCase = true) ||
                prompt.contains("tool", ignoreCase = true)
        val requiresLargeContext = prompt.length > 500 || conversationHistoryCount > 5
        val requiresWebSearch = prompt.contains("بحث", ignoreCase = true) ||
                prompt.contains("search", ignoreCase = true) ||
                prompt.contains("أحدث", ignoreCase = true) ||
                prompt.contains("latest", ignoreCase = true)
        val requiresCoding = prompt.contains("كود", ignoreCase = true) ||
                prompt.contains("برمج", ignoreCase = true) ||
                prompt.contains("kotlin", ignoreCase = true) ||
                prompt.contains("code", ignoreCase = true) ||
                prompt.contains("function", ignoreCase = true)

        return DecisionState(
            taskId = task.id,
            taskComplexity = taskComplexity,
            requiresVision = requiresVision,
            requiresToolCalling = requiresToolCalling,
            requiresLargeContext = requiresLargeContext,
            requiresWebSearch = requiresWebSearch,
            requiresCoding = requiresCoding,
            currentStep = task.currentStepIndex,
            totalSteps = (task.currentStepIndex + 1).coerceAtLeast(1),
            networkPolicy = networkPolicy,
            isNetworkAvailable = isNetworkAvailable,
            remainingTokenBudget = remainingTokenBudget,
            consecutiveFailures = consecutiveFailures,
            uncertaintyScore = uncertaintyScore,
            contextFeatures = mapOf(
                "memoriesCount" to retrievedMemoriesCount.toFloat(),
                "historyCount" to conversationHistoryCount.toFloat(),
                "availableToolsCount" to availableTools.size.toFloat()
            )
        )
    }
}
