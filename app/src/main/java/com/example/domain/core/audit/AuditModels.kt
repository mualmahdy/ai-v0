package com.example.domain.core.audit

import com.example.domain.core.context.ResourceScope

/**
 * REPAIR ORDER §30 — unified audit trail for security-sensitive actions.
 * Every audited event answers: WHO / WHAT / WHEN / SOURCE SCOPE /
 * TARGET SCOPE / RESOURCE / POLICY / RESULT.
 *
 * NEVER stores secrets or unnecessary sensitive payloads.
 */
enum class AuditActorType { USER, AGENT, SYSTEM, IMPORTER }

enum class AuditResult { SUCCESS, FAILURE, DENIED, DEGRADED }

data class AuditEvent(
    val id: Long = 0L,
    val actorType: AuditActorType,
    val actorId: String,
    val action: String,
    val resourceType: String,
    val resourceId: String?,
    val sourceScope: ResourceScope,
    val targetScope: ResourceScope?,
    val policy: String?,
    val result: AuditResult,
    val reason: String? = null,
    val occurredAtEpochMs: Long,
    val metadataJson: String = "{}"
)

/** Canonical audited action names (grep-able, stable). */
object AuditActions {
    const val PROJECT_CREATED = "PROJECT_CREATED"
    const val PROJECT_ARCHIVED = "PROJECT_ARCHIVED"
    const val PROJECT_RESTORED = "PROJECT_RESTORED"
    const val PROJECT_TRASHED = "PROJECT_TRASHED"
    const val PROJECT_DELETED = "PROJECT_DELETED"
    const val PROJECT_PURGED = "PROJECT_PURGED"
    const val PROJECT_RENAMED = "PROJECT_RENAMED"
    const val PROJECT_CLONED = "PROJECT_CLONED"
    const val PROJECT_MOVED = "PROJECT_MOVED"
    const val PROJECT_IMPORTED = "PROJECT_IMPORTED"
    const val PROJECT_EXPORTED = "PROJECT_EXPORTED"
    const val WORKSPACE_CREATED = "WORKSPACE_CREATED"
    const val WORKSPACE_DELETED = "WORKSPACE_DELETED"
    const val WORKSPACE_POLICY_CHANGED = "WORKSPACE_POLICY_CHANGED"
    const val FILE_IMPORTED = "FILE_IMPORTED"
    const val FILE_EXPORTED = "FILE_EXPORTED"
    const val FOLDER_IMPORTED = "FOLDER_IMPORTED"
    const val FOLDER_EXPORTED = "FOLDER_EXPORTED"
    const val SESSION_EXPORTED = "SESSION_EXPORTED"
    const val SNAPSHOT_CREATED = "SNAPSHOT_CREATED"
    const val SNAPSHOT_RESTORED = "SNAPSHOT_RESTORED"
    const val SNAPSHOT_DELETED = "SNAPSHOT_DELETED"
    const val REPAIR_EXECUTED = "REPAIR_EXECUTED"
    const val GRANT_CREATED = "GRANT_CREATED"
    const val GRANT_REVOKED = "GRANT_REVOKED"
    const val TOOL_EXECUTION = "TOOL_EXECUTION"
    const val POLICY_PROMOTED = "POLICY_PROMOTED"
    const val POLICY_ROLLED_BACK = "POLICY_ROLLED_BACK"
    const val APPROVAL_GRANTED = "APPROVAL_GRANTED"
    const val APPROVAL_REJECTED = "APPROVAL_REJECTED"
}
