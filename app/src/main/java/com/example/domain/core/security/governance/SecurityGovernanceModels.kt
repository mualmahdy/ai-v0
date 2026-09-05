package com.example.domain.core.security.governance

import com.example.domain.core.security.SecurityDecision
import com.example.domain.core.security.RiskLevel

/**
 * ============================================================================
 * Security Governance Domain Models — Phase 5 (P1)
 * ============================================================================
 *
 * Closes the Security Governance gap (audit: 40–45% → ~55%) by adding
 * fine-grained authorization, capability-based security, and a persistent
 * audit trail — none of which existed before (the audit found
 * `SecurityPolicy` was a single global policy with no per-agent or
 * per-workspace policy, no `AuditLog` entity, and `ToolDeclaration.
 * requiredPermissions` was unchecked).
 */

enum class PrincipalType(val code: String) {
    AGENT("AGENT"),
    WORKSPACE("WORKSPACE"),
    USER("USER"),
    EXTENSION("EXTENSION")
}

enum class Permission(val code: String, val displayLabelAr: String) {
    EXECUTE("EXECUTE", "تنفيذ"),
    READ("READ", "قراءة"),
    WRITE("WRITE", "كتابة"),
    ADMIN("ADMIN", "إدارة")
}

enum class SecurableResourceType(val code: String) {
    TOOL("TOOL"),
    RESOURCE("RESOURCE"),
    CAPABILITY("CAPABILITY"),
    KNOWLEDGE_DOCUMENT("KNOWLEDGE_DOCUMENT"),
    MEMORY("MEMORY"),
    WORKSPACE("WORKSPACE")
}

/**
 * A single permission grant: principal X has permission Y on resource Z.
 */
data class PermissionGrant(
    val id: Long = 0L,
    val principalType: PrincipalType,
    val principalId: String,
    val resourceType: SecurableResourceType,
    val resourceId: String,
    val permission: Permission,
    val isAllowed: Boolean,
    val grantedBy: String,
    val grantedAtEpochMs: Long = System.currentTimeMillis(),
    val expiresAtEpochMs: Long? = null
)

/**
 * Capability-based security check result.
 */
data class CapabilityAuthorization(
    val agentId: String,
    val requiredCapability: String,
    val isGranted: Boolean,
    val reason: String,
    val riskLevel: RiskLevel = RiskLevel.LOW
)

/**
 * Structured security audit event. Persisted to `audit_trail`.
 */
data class SecurityAuditEvent(
    val id: String,
    val severity: AuditSeverity,
    val actor: String,
    val action: String,
    val resourceType: String,
    val resourceId: String,
    val decision: String,
    val reason: String,
    val workspaceId: String? = null,
    val occurredAtEpochMs: Long = System.currentTimeMillis()
)

enum class AuditSeverity(val code: String) {
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    CRITICAL("CRITICAL")
}

/**
 * Secret rotation request. The audit found
 * `EncryptedSecretStorageAdapter` had no rotation API; one master key
 * alias `AI_V0_MASTER_CREDENTIAL_KEY` was used indefinitely.
 */
data class SecretRotationRequest(
    val secretAlias: String,
    val rotatedBy: String,
    val reason: String,
    val requestedAtEpochMs: Long = System.currentTimeMillis()
)

data class SecretRotationResult(
    val secretAlias: String,
    val isSuccessful: Boolean,
    val newVersionId: String?,
    val previousVersionArchived: Boolean,
    val reason: String
)
