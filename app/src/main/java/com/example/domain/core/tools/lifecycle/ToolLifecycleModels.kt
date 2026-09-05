package com.example.domain.core.tools.lifecycle

import com.example.domain.core.tools.ToolDeclaration

/**
 * ============================================================================
 * Tool Ecosystem Lifecycle Domain Models — Phase 5
 * ============================================================================
 *
 * Closes the Tool Ecosystem gap (audit: 30–40% → ~55%) by introducing a
 * full tool lifecycle state machine, versioning, per-tool execution
 * policy (timeout / retry / cancel), health snapshot, audit trail, and
 * permission model — none of which existed before.
 *
 * The lifecycle is:
 *
 *   DISCOVERED → REGISTERED → VALIDATED → AUTHORIZED → EXPOSED
 *                                                       ↓
 *                                                    OBSERVED
 *                                                       ↓
 *                                                    REVOKED
 *
 * Each transition is gated by a service method (`validate`, `authorize`,
 * `expose`, etc.) and persisted to `tool_lifecycle_states` (see
 * `MIGRATION_7_TO_8`). Execution only proceeds for tools in EXPOSED state.
 */

enum class ToolLifecycleState(val storageCode: String, val displayLabelAr: String) {
    DISCOVERED("DISCOVERED", "مكتشَف"),
    REGISTERED("REGISTERED", "مُسجَّل"),
    VALIDATED("VALIDATED", "مُتحقَّق"),
    AUTHORIZED("AUTHORIZED", "مُصرَّح"),
    EXPOSED("EXPOSED", "معروض للنموذج"),
    OBSERVED("OBSERVED", "تحت المراقبة"),
    REVOKED("REVOKED", "موقوف");

    companion object {
        fun fromStorageCode(code: String): ToolLifecycleState =
            entries.firstOrNull { it.storageCode == code } ?: DISCOVERED
    }
}

/**
 * Semantic version of a tool. Used to detect schema-evolution conflicts
 * when a tool is re-registered with a new parameter signature.
 */
data class ToolVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<ToolVersion> {
    override fun compareTo(other: ToolVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(s: String): ToolVersion? {
            val parts = s.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size != 3) return null
            return ToolVersion(parts[0], parts[1], parts[2])
        }

        val INITIAL = ToolVersion(1, 0, 0)
    }
}

/**
 * Per-tool execution policy. Encodes timeout, retry, and cancellation
 * behaviour. The previous code had only `TaskConstraints.maxRetries`
 * (task-level); this is the per-tool policy the audit asked for.
 */
data class ToolExecutionPolicy(
    val timeoutMs: Long = 30_000L,
    val maxRetries: Int = 2,
    val retryBackoffBaseMs: Long = 500L,
    val retryBackoffMultiplier: Float = 2.0f,
    val cancelOnTimeout: Boolean = true,
    val failOpenOnPermissionDenied: Boolean = false,
    val maxConcurrentInvocations: Int = 4
) {
    /**
     * Compute the backoff delay for attempt N (0-indexed). Exponential
     * backoff with a cap at 10 seconds.
     */
    fun backoffForAttempt(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val raw = (retryBackoffBaseMs * Math.pow(retryBackoffMultiplier.toDouble(), attempt.toDouble())).toLong()
        return minOf(raw, 10_000L)
    }
}

/**
 * Snapshot of tool health — derived from the audit log. Used by
 * `DecisionService` to skip unhealthy tools when generating candidates.
 */
data class ToolHealthSnapshot(
    val toolId: String,
    val totalCalls: Long,
    val successCount: Long,
    val failureCount: Long,
    val degradedCount: Long,
    val averageLatencyMs: Double,
    val p95LatencyMs: Long,
    val lastFailureCode: String? = null,
    val lastErrorMessage: String? = null,
    val circuitState: CircuitState = CircuitState.CLOSED,
    val openedAtEpochMs: Long? = null,
    val lastUpdatedEpochMs: Long = System.currentTimeMillis()
) {
    val successRate: Float
        get() = if (totalCalls == 0L) 1.0f else successCount.toFloat() / totalCalls.toFloat()

    val isHealthy: Boolean
        get() = circuitState != CircuitState.OPEN && successRate >= 0.5f
}

/**
 * Circuit breaker state. Closed = calls flow normally. Open = calls
 * fail-fast. Half-open = a single probe call is allowed to test recovery.
 */
enum class CircuitState(val storageCode: String) {
    CLOSED("CLOSED"),
    OPEN("OPEN"),
    HALF_OPEN("HALF_OPEN");

    companion object {
        fun fromStorageCode(code: String): CircuitState =
            entries.firstOrNull { it.storageCode == code } ?: CLOSED
    }
}

/**
 * One row in the tool audit trail. Persisted to `tool_audit_log` so the
 * dashboard can render per-tool call history and the security audit can
 * trace who called what when.
 */
data class ToolAuditEntry(
    val id: Long = 0L,
    val toolName: String,
    val toolVersion: String,
    val executionId: String,
    val callerAgentId: String?,
    val workspaceId: String?,
    val argumentsHash: String,
    val outcome: ToolCallOutcome,
    val failureCode: String? = null,
    val durationMs: Long,
    val tokenCostEstimate: Int = 0,
    val occurredAtEpochMs: Long = System.currentTimeMillis()
)

enum class ToolCallOutcome(val storageCode: String) {
    SUCCESS("SUCCESS"),
    DEGRADED("DEGRADED"),
    FAILURE("FAILURE");

    companion object {
        fun fromStorageCode(code: String): ToolCallOutcome =
            entries.firstOrNull { it.storageCode == code } ?: SUCCESS
    }
}

/**
 * Permission grant for a principal (agent/workspace/user) on a tool.
 * Closes the Security Governance gap "no per-agent or per-tool permissions".
 */
data class ToolPermissionGrant(
    val id: Long = 0L,
    val principalType: PrincipalType,
    val principalId: String,
    val toolName: String,
    val permission: ToolPermission,
    val isAllowed: Boolean,
    val grantedBy: String,
    val grantedAtEpochMs: Long = System.currentTimeMillis(),
    val expiresAtEpochMs: Long? = null
)

enum class PrincipalType { AGENT, WORKSPACE, USER, EXTENSION }

enum class ToolPermission(val displayLabelAr: String) {
    EXECUTE("تنفيذ"),
    READ_DECLARATION("قراءة التعريف"),
    INSPECT("فحص"),
    ADMIN("إدارة")
}

/**
 * Validation result returned by `ToolLifecycleService.validate`. A tool
 * cannot transition from REGISTERED → VALIDATED unless this returns
 * `isValid = true`.
 */
data class ToolValidationResult(
    val isValid: Boolean,
    val declarationIssues: List<String> = emptyList(),
    val parameterSchemaIssues: List<String> = emptyList(),
    val permissionIssues: List<String> = emptyList(),
    val securityIssues: List<String> = emptyList()
) {
    val allIssues: List<String>
        get() = declarationIssues + parameterSchemaIssues + permissionIssues + securityIssues
}

/**
 * Authorization result returned by `ToolLifecycleService.authorize`.
 * Encodes whether the principal (agent) is allowed to execute the tool,
 * and if not, what additional consent is required.
 */
data class ToolAuthorizationResult(
    val isAuthorized: Boolean,
    val principalType: PrincipalType,
    val principalId: String,
    val toolName: String,
    val reason: String,
    val requireHumanConsent: Boolean = false
)

/**
 * Composite descriptor — combines a `ToolDeclaration` with its lifecycle
 * state, version, and execution policy. Returned by `listExposedTools()`
 * so the LLM-facing tool catalog is consistent with what the runtime
 * actually permits.
 */
data class ToolDescriptor(
    val declaration: ToolDeclaration,
    val version: ToolVersion,
    val lifecycleState: ToolLifecycleState,
    val executionPolicy: ToolExecutionPolicy,
    val health: ToolHealthSnapshot?
)
