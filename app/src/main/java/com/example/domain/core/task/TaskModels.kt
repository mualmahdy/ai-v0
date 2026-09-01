package com.example.domain.core.task

import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentId
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.network.NetworkPolicy

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
 * Lifecycle states of an autonomous task as mandated by the Master Directive:
 * CREATED, READY, PLANNING, RUNNING, WAITING, BLOCKED, REPLANNING, COMPLETED, FAILED, CANCELLED
 */
enum class TaskLifecycleState {
    CREATED,
    READY,
    PLANNING,
    RUNNING,
    WAITING,
    BLOCKED,
    REPLANNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Verification strategies for evaluating task completion.
 */
enum class VerificationStrategy {
    STRICT,
    PERMISSIVE,
    EVIDENCE_BASED,
    CRITERIA_MATCH
}

/**
 * Single acceptance criterion defining verifiable condition.
 */
data class AcceptanceCriterion(
    val id: String,
    val description: String,
    val requiredKey: String? = null,
    val validatorType: String = "EXISTS", // EXISTS, NOT_BLANK, MIN_LENGTH, REGEX, NUMERIC_RANGE
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val regexPattern: String? = null
)

/**
 * First-class domain representation of all capabilities, constraints, and requirements for a task.
 */
data class TaskCapabilityRequirements(
    val requiredCapabilities: Set<CapabilityType> = emptySet(),
    val optionalCapabilities: Set<CapabilityType> = emptySet(),
    val prohibitedCapabilities: Set<CapabilityType> = emptySet(),
    val requiredResourceTypes: List<String> = emptyList(),
    val requiredModelCapabilities: List<String> = emptyList(),
    val requiredAgentCapabilities: Set<CapabilityType> = emptySet(),
    val networkRequirement: NetworkPolicy = NetworkPolicy.HYBRID,
    val requiresLocalInference: Boolean = false,
    val securityRequirements: List<String> = emptyList(),
    val requiredEvidenceKeys: List<String> = emptyList(),
    val expectedOutputType: String = "TEXT",
    val acceptanceCriteria: List<AcceptanceCriterion> = emptyList()
)

/**
 * Autonomy policies governing agent execution permission boundaries.
 */
enum class AutonomyPolicy(val code: String, val displayName: String) {
    ASSISTED("assisted", "مساعد (Assisted - يتطلب موافقة لكل إجراء)"),
    SUPERVISED("supervised", "مُشرف عليه (Supervised - موافقة فقط للإجراءات الحساسة)"),
    AUTONOMOUS("autonomous", "مستقل تماماً (Autonomous - تنفيذ تلقائي كامل ضمن الميزانية)")
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
    val timeoutMs: Long = 60000L,
    val maxRetries: Int = 3,
    val allowDegradedExecution: Boolean = true,
    val autonomyPolicy: AutonomyPolicy = AutonomyPolicy.SUPERVISED,
    val requireHumanConsentForSensitiveTools: Boolean = true
)

/**
 * Dedicated budget allocated specifically for a task.
 */
data class TaskBudget(
    val tokenLimit: Int = 30000,
    val maxCostEstimatedUsd: Double = 0.10,
    val consumedTokens: Int = 0
)

/**
 * Criteria that must be satisfied for a task to be marked SUCCESS.
 */
data class TaskSuccessCriteria(
    val requiredOutputKeys: List<String> = emptyList(),
    val minOutputLengthChars: Int = 1,
    val acceptanceCriteria: List<AcceptanceCriterion> = emptyList(),
    val requiredEvidenceKeys: List<String> = emptyList(),
    val verificationStrategy: VerificationStrategy = VerificationStrategy.STRICT
)

/**
 * Complete immutable definition and state of an atomic task.
 */
data class TaskDefinition(
    val id: TaskId,
    val assignedAgentId: AgentId,
    val goal: String = "",
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val input: TaskInput,
    val requirements: TaskCapabilityRequirements = TaskCapabilityRequirements(),
    val constraints: TaskConstraints = TaskConstraints(),
    val budget: TaskBudget = TaskBudget(),
    val successCriteria: TaskSuccessCriteria = TaskSuccessCriteria(),
    val state: TaskLifecycleState = TaskLifecycleState.CREATED,
    val assignedModelId: String? = null,
    val activeTools: List<String> = emptyList(),
    val currentStepIndex: Int = 0,
    val executionLog: List<String> = emptyList(),
    val outcomeSummary: String? = null,
    val createdAtTimestampMs: Long = System.currentTimeMillis(),
    val lastUpdatedTimestampMs: Long = System.currentTimeMillis()
)
