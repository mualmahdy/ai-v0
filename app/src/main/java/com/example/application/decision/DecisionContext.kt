package com.example.application.decision

import com.example.domain.core.capability.CapabilityDescriptor
import com.example.domain.core.capability.CapabilityGapAnalysis
import com.example.domain.core.capability.CapabilityResourceGraph
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.decision.EnvironmentObservation
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
    val capabilityGraph: CapabilityResourceGraph = CapabilityResourceGraph(capabilities),
    val capabilityGap: CapabilityGapAnalysis = CapabilityGapAnalysis(
        targetTaskId = task.id.value,
        requiredCapabilities = task.requirements.requiredCapabilities,
        optionalCapabilities = task.requirements.optionalCapabilities,
        prohibitedCapabilities = task.requirements.prohibitedCapabilities
    ),
    val satisfiedCapabilities: Set<CapabilityType> = emptySet(),
    val missingCapabilities: Set<CapabilityType> = emptySet(),
    val availableTools: List<String> = emptyList(),
    val networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
    val isNetworkAvailable: Boolean = true,
    val remainingTokenBudget: Int = 30000,
    val consecutiveFailures: Int = 0,
    val uncertaintyScore: Float = 0.2f,
    val conversationHistoryCount: Int = 0,
    val retrievedMemoriesCount: Int = 0,
    val taskComplexity: Float = 0.5f,
    val accumulatedEvidence: Map<String, Any?> = emptyMap(),
    val lastAction: DecisionAction? = null,
    val lastObservation: EnvironmentObservation? = null,
    val decisionHistory: List<DecisionResult> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    val hasSearchEvidence: Boolean
        get() = accumulatedEvidence.containsKey("searchResults") || (lastAction?.type == DecisionActionType.SEARCH && lastObservation?.isSuccess == true)

    val hasMemoryEvidence: Boolean
        get() = accumulatedEvidence.containsKey("memorySnippets") || ((lastAction?.type == DecisionActionType.RETRIEVE_MEMORY || lastAction?.type == DecisionActionType.RETRIEVE_KNOWLEDGE) && lastObservation?.isSuccess == true)

    val hasToolExecutionEvidence: Boolean
        get() = accumulatedEvidence.containsKey("toolOutput") || ((lastAction?.type == DecisionActionType.EXECUTE_TOOL || lastAction?.type == DecisionActionType.SELECT_TOOL || lastAction?.type == DecisionActionType.EXECUTE_MCP || lastAction?.type == DecisionActionType.EXECUTE_SKILL) && lastObservation?.isSuccess == true)

    /**
     * Projects this rich context into the normalized mathematical DecisionState vector
     * required by the CBR-MDP Decision Engine.
     * Uses structured capability requirements as the authoritative source of truth (Rule 4 & Rule 20).
     */
    fun toDecisionState(): DecisionState {
        val reqs = task.requirements
        val allTargetCaps = reqs.requiredCapabilities + reqs.optionalCapabilities
        val prompt = task.input.rawPrompt

        val requiresVision = if (allTargetCaps.isNotEmpty()) {
            allTargetCaps.contains(CapabilityType.VISION)
        } else {
            prompt.contains("صورة", ignoreCase = true) ||
                    prompt.contains("vision", ignoreCase = true) ||
                    prompt.contains("image", ignoreCase = true)
        }

        val requiresToolCalling = if (allTargetCaps.isNotEmpty()) {
            allTargetCaps.contains(CapabilityType.TOOL_EXECUTION) ||
                    allTargetCaps.contains(CapabilityType.FILE_STORAGE) ||
                    allTargetCaps.contains(CapabilityType.FILE_READ) ||
                    allTargetCaps.contains(CapabilityType.FILE_WRITE) ||
                    allTargetCaps.contains(CapabilityType.SHELL_EXECUTION) ||
                    allTargetCaps.contains(CapabilityType.SYSTEM_EXECUTION) ||
                    allTargetCaps.contains(CapabilityType.CODE_ENGINEERING) ||
                    allTargetCaps.contains(CapabilityType.SECURITY_AUDIT) ||
                    allTargetCaps.contains(CapabilityType.HASH_COMPUTATION) ||
                    allTargetCaps.contains(CapabilityType.MCP_INVOCATION)
        } else {
            prompt.contains("ملف", ignoreCase = true) ||
                    prompt.contains("أداة", ignoreCase = true) ||
                    prompt.contains("file", ignoreCase = true) ||
                    prompt.contains("tool", ignoreCase = true)
        }

        val requiresLargeContext = prompt.length > 1000 || conversationHistoryCount > 5

        val requiresWebSearch = if (allTargetCaps.isNotEmpty()) {
            allTargetCaps.contains(CapabilityType.SEARCH)
        } else {
            prompt.contains("بحث", ignoreCase = true) ||
                    prompt.contains("search", ignoreCase = true)
        }

        val requiresCoding = if (allTargetCaps.isNotEmpty()) {
            allTargetCaps.contains(CapabilityType.CODE_ANALYSIS) ||
                    allTargetCaps.contains(CapabilityType.CODE_ENGINEERING)
        } else {
            prompt.contains("كود", ignoreCase = true) ||
                    prompt.contains("code", ignoreCase = true)
        }

        val hasSearch = hasSearchEvidence
        val hasMemory = hasMemoryEvidence
        val hasTool = hasToolExecutionEvidence

        val coverageRatio = if (reqs.requiredCapabilities.isEmpty()) 1.0f else {
            satisfiedCapabilities.intersect(reqs.requiredCapabilities).size.toFloat() / reqs.requiredCapabilities.size.toFloat()
        }

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
            hasSearchEvidence = hasSearch,
            hasMemoryEvidence = hasMemory,
            hasToolExecutionEvidence = hasTool,
            lastActionType = lastAction?.type,
            lastActionSuccess = lastObservation?.isSuccess,
            contextFeatures = mapOf(
                "memoriesCount" to retrievedMemoriesCount.toFloat(),
                "historyCount" to conversationHistoryCount.toFloat(),
                "availableToolsCount" to availableTools.size.toFloat(),
                "evidenceKeysCount" to accumulatedEvidence.size.toFloat(),
                "capabilityCoverageRatio" to coverageRatio,
                "missingCapabilitiesCount" to missingCapabilities.size.toFloat(),
                "satisfiedCapabilitiesCount" to satisfiedCapabilities.size.toFloat(),
                "capabilityStatusOrdinal" to capabilityGap.status.ordinal.toFloat()
            )
        )
    }
}

