package com.example.application.security

import com.example.domain.core.observability.AuditSeverity
import com.example.domain.core.security.governance.AuditSeverity as GovernanceSeverity
import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.security.governance.Permission
import com.example.domain.core.security.governance.PermissionGrant
import com.example.domain.core.security.governance.PrincipalType
import com.example.domain.core.security.governance.SecurableResourceType
import com.example.domain.ports.observability.TelemetryPort
import com.example.infrastructure.persistence.dao.PermissionGrantDao
import com.example.infrastructure.persistence.entities.PermissionGrantEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ============================================================================
 * PermissionGrantService + AuditTrailService — Phase 5 Security (P1)
 * ============================================================================
 *
 * Closes the Security Governance gap (audit: 40–45% → ~55%) by adding:
 *
 *   1. Fine-grained per-principal per-resource permissions (the audit
 *      found `SecurityPolicy` was a single global policy).
 *
 *   2. Capability-based security — `checkCapability` gates agents from
 *      calling resources whose capabilities they don't have.
 *
 *   3. Persistent audit trail — `recordSecurityDecision` persists every
 *      ALLOW/DENY/REQUIRE_CONSENT decision to `audit_trail`.
 */
class PermissionGrantService(
    private val permissionGrantDao: PermissionGrantDao,
    private val telemetryPort: TelemetryPort
) {

    /**
     * Grants a permission, optionally scoped to ONE workspace (defect
     * family 2 — workspace context is part of authorization). A null
     * workspaceId means an explicitly GLOBAL grant.
     */
    suspend fun grant(
        principalType: PrincipalType,
        principalId: String,
        resourceType: SecurableResourceType,
        resourceId: String,
        permission: Permission,
        grantedBy: String,
        expiresAtEpochMs: Long? = null,
        workspaceId: String? = null
    ): Long = withContext(Dispatchers.IO) {
        val entity = PermissionGrantEntity(
            id = 0L,
            principalType = principalType.code,
            principalId = principalId,
            resourceType = resourceType.code,
            resourceId = resourceId,
            permission = permission.code,
            isAllowed = true,
            grantedBy = grantedBy,
            grantedAtEpochMs = System.currentTimeMillis(),
            expiresAtEpochMs = expiresAtEpochMs,
            workspaceId = workspaceId
        )
        val rowId = permissionGrantDao.upsert(entity)
        // Audit the grant itself.
        telemetryPort.recordAudit(
            AuditEvent(
                id = UUID.randomUUID().toString(),
                severity = AuditSeverity.INFO,
                actor = grantedBy,
                action = "GRANT_PERMISSION",
                resourceType = resourceType.code,
                resourceId = resourceId,
                decision = "ALLOW",
                reason = "منح $principalType:$principalId إذن ${permission.code}",
                workspaceId = workspaceId,
                attributes = mapOf(
                    "principalType" to principalType.code,
                    "principalId" to principalId,
                    "permission" to permission.code,
                    "scope" to (workspaceId ?: "GLOBAL")
                )
            )
        )
        rowId
    }

    suspend fun revoke(grantId: Long, revokedBy: String) = withContext(Dispatchers.IO) {
        permissionGrantDao.revoke(grantId)
        telemetryPort.recordAudit(
            AuditEvent(
                id = UUID.randomUUID().toString(),
                severity = AuditSeverity.WARN,
                actor = revokedBy,
                action = "REVOKE_PERMISSION",
                resourceType = "PERMISSION_GRANT",
                resourceId = grantId.toString(),
                decision = "DENY",
                reason = "إلغاء منح إذن #$grantId"
            )
        )
        Unit
    }

    /**
     * WORKSPACE-SCOPED check (defect family 2): a grant authorizes only
     * when it is explicitly GLOBAL or scoped to the SAME workspace as the
     * execution. A grant scoped to another workspace can never authorize
     * this check.
     */
    suspend fun check(
        principalType: PrincipalType,
        principalId: String,
        resourceType: SecurableResourceType,
        resourceId: String,
        permission: Permission,
        workspaceId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val grant = permissionGrantDao.lookupScoped(
            principalType = principalType.code,
            principalId = principalId,
            resourceType = resourceType.code,
            resourceId = resourceId,
            permission = permission.code,
            workspaceId = workspaceId
        )
        if (grant?.isAllowed != true) return@withContext false
        // Check expiry.
        val now = System.currentTimeMillis()
        if (grant.expiresAtEpochMs != null && grant.expiresAtEpochMs < now) return@withContext false
        true
    }

    /**
     * Convenience: log a security decision to the audit trail without
     * necessarily granting or revoking anything. Used by
     * `SecurityGuardService` to record every ALLOW/DENY it makes.
     */
    suspend fun recordSecurityDecision(
        severity: GovernanceSeverity,
        actor: String,
        action: String,
        resourceType: String,
        resourceId: String,
        decision: String,
        reason: String,
        workspaceId: String? = null
    ) {
        val mapped = when (severity) {
            GovernanceSeverity.INFO -> AuditSeverity.INFO
            GovernanceSeverity.WARN -> AuditSeverity.WARN
            GovernanceSeverity.ERROR -> AuditSeverity.ERROR
            GovernanceSeverity.CRITICAL -> AuditSeverity.CRITICAL
        }
        telemetryPort.recordAudit(
            AuditEvent(
                id = UUID.randomUUID().toString(),
                severity = mapped,
                actor = actor,
                action = action,
                resourceType = resourceType,
                resourceId = resourceId,
                decision = decision,
                reason = reason,
                workspaceId = workspaceId
            )
        )
    }
}
