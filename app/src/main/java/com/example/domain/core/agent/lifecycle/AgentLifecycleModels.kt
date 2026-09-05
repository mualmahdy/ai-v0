package com.example.domain.core.agent.lifecycle

import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.task.AutonomyPolicy

/**
 * ============================================================================
 * Agent Intelligence Lifecycle Domain Models — Phase 5
 * ============================================================================
 *
 * Closes the Agent Intelligence gap (audit: 35–40% → ~55%) by introducing:
 *
 *   1. A proper agent lifecycle state machine:
 *      CREATED → INITIALIZED → READY → RUNNING → PAUSED → TERMINATED
 *      (the audit found `AgentDefinition.enabled` was a boolean with no
 *      state enum, no `AgentRuntimeService` managing instances).
 *
 *   2. Per-agent versioning so config changes are auditable and rollback-
 *      able (the audit found no `version` field on `AgentDefinition`).
 *
 *   3. Per-agent memory namespace reference (the audit found all agents
 *      shared the global `MemoryRepositoryPort`).
 *
 *   4. Autonomy policy evaluation result — a structured decision rather
 *      than the current "ignored enum" pattern.
 *
 *   5. Budget enforcement hooks — the audit found `AgentBudget.isDepleted`
 *      existed but `ExecutionService` never checked it.
 */

enum class AgentLifecycleState(val storageCode: String, val displayLabelAr: String) {
    CREATED("CREATED", "منشأ"),
    INITIALIZED("INITIALIZED", "مهيأ"),
    READY("READY", "جاهز"),
    RUNNING("RUNNING", "قيد التشغيل"),
    PAUSED("PAUSED", "متوقف مؤقتاً"),
    TERMINATED("TERMINATED", "منهي");

    companion object {
        fun fromStorageCode(code: String): AgentLifecycleState =
            entries.firstOrNull { it.storageCode == code } ?: CREATED
    }
}

/**
 * Agent version. Bumped whenever the agent's definition changes.
 */
data class AgentVersion(
    val major: Int = 1,
    val minor: Int = 0,
    val patch: Int = 0,
    val revisionId: String = "" // git-style short hash for traceability
) : Comparable<AgentVersion> {
    override fun compareTo(other: AgentVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }
    override fun toString(): String = "$major.$minor.$patch${if (revisionId.isNotBlank()) "-$revisionId" else ""}"

    companion object {
        fun parse(s: String): AgentVersion? {
            val main = s.substringBefore("-")
            val rev = if (s.contains("-")) s.substringAfter("-") else ""
            val parts = main.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size != 3) return null
            return AgentVersion(parts[0], parts[1], parts[2], rev)
        }
    }
}

/**
 * Runtime state of one agent instance. Kept in memory by
 * `AgentLifecycleService`; not persisted directly (state is
 * reconstructable from `AgentDefinition` + the agent's persisted
 * task history).
 */
data class AgentRuntimeState(
    val agentId: AgentId,
    val version: AgentVersion,
    val lifecycleState: AgentLifecycleState,
    val currentExecutionId: String?,
    val lastActivatedAtEpochMs: Long,
    val totalExecutions: Long,
    val memoryNamespaceId: String?
)

/**
 * Result of an autonomy policy evaluation. The orchestrator consults
 * this before executing an action.
 */
data class AutonomyPolicyEvaluation(
    val agentId: AgentId,
    val policy: AutonomyPolicy,
    val actionType: String,
    val isAllowed: Boolean,
    val requireHumanConsent: Boolean,
    val reason: String
)

/**
 * Result of a budget check.
 */
data class BudgetEvaluation(
    val agentId: AgentId,
    val remainingTokens: Int,
    val isDepleted: Boolean,
    val isApproachingLimit: Boolean,
    val recommendedAction: BudgetRecommendedAction
)

enum class BudgetRecommendedAction(val displayLabelAr: String) {
    PROCEED("المتابعة"),
    THROTTLE("تقليل الاستهلاك"),
    REQUEST_BUDGET_EXTENSION("طلب تمديد الميزانية"),
    BLOCK("حظر التنفيذ")
}

/**
 * Versioned agent definition — wraps `AgentDefinition` with version
 * metadata so we can detect config drift and roll back.
 */
data class VersionedAgentDefinition(
    val definition: AgentDefinition,
    val version: AgentVersion,
    val previousVersionId: String?,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val createdBy: String = "system"
)

/**
 * Result of validating a new agent definition before deployment.
 */
data class AgentValidationResult(
    val isValid: Boolean,
    val identityIssues: List<String> = emptyList(),
    val capabilityIssues: List<String> = emptyList(),
    val budgetIssues: List<String> = emptyList(),
    val autonomyIssues: List<String> = emptyList()
) {
    val allIssues: List<String>
        get() = identityIssues + capabilityIssues + budgetIssues + autonomyIssues
}

/**
 * Pre-deployment test result — the audit asked for "test agent before
 * deploy". This is a structured result from running the agent against
 * a sandboxed dry-run.
 */
data class AgentDryRunResult(
    val agentId: AgentId,
    val testPrompt: String,
    val isSuccessful: Boolean,
    val responseSummary: String,
    val durationMs: Long,
    val tokensConsumed: Int,
    val issues: List<String> = emptyList()
)
