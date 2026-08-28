package com.example.domain.core.task

import com.example.domain.core.agent.AgentId

/**
 * Unique identifier for a task.
 */
@JvmInline
value class TaskId(val value: String)

/**
 * Task priority in the execution queue.
 */
enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Lifecycle states of an atomic task.
 */
enum class TaskLifecycleState {
    PENDING,
    RUNNING,
    COMPLETED,
    DEGRADED,
    FAILED,
    CANCELLED
}

/**
 * Input parameters and context for a task.
 */
data class TaskInput(
    val rawPrompt: String,
    val parameters: Map<String, Any?> = emptyMap(),
    val contextVariables: Map<String, String> = emptyMap()
)

/**
 * Execution constraints for a task.
 */
data class TaskConstraints(
    val timeoutMs: Long = 30000L,
    val maxRetries: Int = 2,
    val allowDegradedExecution: Boolean = true,
    val requireHumanConsentForSensitiveTools: Boolean = true
)

/**
 * Dedicated budget allocated specifically for a task.
 */
data class TaskBudget(
    val tokenLimit: Int = 8000,
    val maxCostEstimatedUsd: Double = 0.05
)

/**
 * Criteria that must be satisfied for a task to be marked SUCCESS.
 */
data class TaskSuccessCriteria(
    val requiredOutputKeys: List<String> = emptyList(),
    val minOutputLengthChars: Int = 1
)

/**
 * Complete immutable definition and state of an atomic task.
 */
data class TaskDefinition(
    val id: TaskId,
    val assignedAgentId: AgentId,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val input: TaskInput,
    val constraints: TaskConstraints = TaskConstraints(),
    val budget: TaskBudget = TaskBudget(),
    val successCriteria: TaskSuccessCriteria = TaskSuccessCriteria(),
    val state: TaskLifecycleState = TaskLifecycleState.PENDING,
    val createdAtTimestampMs: Long = System.currentTimeMillis()
)
