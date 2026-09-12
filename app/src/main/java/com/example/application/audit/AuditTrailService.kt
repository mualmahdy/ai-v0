package com.example.application.audit

import com.example.domain.core.audit.AuditActorType
import com.example.domain.core.audit.AuditEvent
import com.example.domain.core.audit.AuditResult
import com.example.domain.core.context.ResourceScope
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.AuditEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * REPAIR ORDER §30 — UNIFIED AUDIT TRAIL
 * ============================================================================
 * One typed writer for security-sensitive actions. Every event answers:
 * WHO (actor) / WHAT (action + resource) / WHEN / SOURCE SCOPE /
 * TARGET SCOPE / POLICY / RESULT.
 *
 * Invariants:
 *  - NEVER stores secrets or sensitive payloads (redaction hook as last
 *    line of defense).
 *  - Fire-and-forget write failures are logged, NEVER thrown into the
 *    audited business path (an audit failure must not corrupt the
 *    operation), but the write is attempted synchronously on the caller's
 *    dispatcher when awaited.
 */
class AuditTrailService(
    private val database: AppDatabase,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val dao = database.auditEventDao()

    /** Redaction applied to reason/metadata before persisting (S-13). */
    var redactor: (String) -> String = ::defaultRedact

    /**
     * GAP-13 (Design Closure 2026): honest audit-write observability. The
     * `runCatching` on the insert stays (an audit failure must never break
     * the audited business path), but the failure is now COUNTED and
     * LOGGED — the KDoc always claimed "logged"; previously it was not.
     * A silently failing audit writer is exactly the invisible-degradation
     * class the gap register calls out (recordAudit → swallowed -1).
     */
    val consecutiveWriteFailures = java.util.concurrent.atomic.AtomicInteger(0)

    /** Last write failure description (null = none / last write succeeded). */
    @Volatile
    var lastWriteFailure: String? = null
        private set

    /**
     * Records an audit event (suspend — deterministic persistence for
     * critical paths). Scope ids are derived from the ResourceScope.
     */
    suspend fun record(
        actorType: AuditActorType,
        actorId: String,
        action: String,
        resourceType: String,
        resourceId: String?,
        sourceScope: ResourceScope,
        targetScope: ResourceScope?,
        policy: String?,
        result: AuditResult,
        reason: String? = null,
        metadataJson: String = "{}"
    ) {
        val entity = AuditEventEntity(
            actorType = actorType.name,
            actorId = actorId,
            action = action,
            resourceType = resourceType,
            resourceId = resourceId,
            sourceScopeType = sourceScope.type.name,
            sourceScopeId = sourceScope.describe(),
            targetScopeType = targetScope?.type?.name,
            targetScopeId = targetScope?.describe(),
            policy = policy,
            result = result.name,
            reason = reason?.let { redactor(it) },
            workspaceId = sourceScope.workspaceIdOrNull,
            projectId = sourceScope.projectIdOrNull,
            occurredAtEpochMs = System.currentTimeMillis(),
            metadataJson = redactor(metadataJson)
        )
        runCatching { dao.insert(entity) }
            .onFailure { failure ->
                consecutiveWriteFailures.incrementAndGet()
                lastWriteFailure = "${failure::class.simpleName}: ${failure.message?.take(160)}"
                // Honest log (goes to logcat on Android, stderr on the JVM):
                // the audit trail is the LAST line of accountability — its
                // own failures must be visible somewhere, not nowhere.
                System.err.println("AUDIT_WRITE_FAILED action=$action resource=$resourceType/$resourceId: $lastWriteFailure")
            }
            .onSuccess { consecutiveWriteFailures.set(0); lastWriteFailure = null }
    }

    /** Non-blocking variant for hot paths. */
    fun recordAsync(
        actorType: AuditActorType,
        actorId: String,
        action: String,
        resourceType: String,
        resourceId: String?,
        sourceScope: ResourceScope,
        targetScope: ResourceScope? = null,
        policy: String? = null,
        result: AuditResult,
        reason: String? = null
    ) {
        scope.launch {
            record(actorType, actorId, action, resourceType, resourceId, sourceScope, targetScope, policy, result, reason)
        }
    }

    suspend fun recentForWorkspace(workspaceId: String, limit: Int = 100): List<AuditEvent> =
        dao.forWorkspace(workspaceId, limit).map { it.toDomain() }

    suspend fun recentForProject(projectId: Long, limit: Int = 100): List<AuditEvent> =
        dao.forProject(projectId, limit).map { it.toDomain() }

    private fun AuditEventEntity.toDomain() = AuditEvent(
        id = id,
        actorType = AuditActorType.valueOf(actorType),
        actorId = actorId,
        action = action,
        resourceType = resourceType,
        resourceId = resourceId,
        sourceScope = parseScope(sourceScopeType, sourceScopeId),
        targetScope = targetScopeType?.let { parseScope(it, targetScopeId ?: "?") },
        policy = policy,
        result = AuditResult.valueOf(result),
        reason = reason,
        occurredAtEpochMs = occurredAtEpochMs,
        metadataJson = metadataJson
    )

    private fun parseScope(type: String, id: String): ResourceScope = try {
        val parts = id.split(":", limit = 2).getOrNull(1)?.split("/") ?: emptyList()
        when (type) {
            "APPLICATION" -> ResourceScope.Application
            "WORKSPACE" -> ResourceScope.Workspace(id.substringAfter("WORKSPACE:"))
            "PROJECT" -> ResourceScope.Project(
                parts.getOrNull(0) ?: "?",
                parts.getOrNull(1)?.toLongOrNull() ?: -1L
            )
            "SESSION" -> ResourceScope.Session(parts.getOrNull(0) ?: "?", parts.getOrNull(1)?.toLongOrNull(), parts.getOrNull(2) ?: "?")
            else -> ResourceScope.Task(parts.getOrNull(0) ?: "?", parts.getOrNull(1)?.toLongOrNull(), parts.getOrNull(2), parts.getOrNull(3) ?: "?")
        }
    } catch (_: Exception) {
        ResourceScope.Application
    }

    companion object {
        /** Last-line redaction of accidental secret material (§13). */
        internal fun defaultRedact(input: String): String = input
            .replace(Regex("(sk|tvly|AIza|Bearer)[A-Za-z0-9_\\-]{8,}"), "[REDACTED]")
            .replace(Regex("(?i)(api[_-]?key|token|secret|password)\"?\\s*[:=]\\s*\"?[^\",}]+"), "$1=[REDACTED]")
    }
}
