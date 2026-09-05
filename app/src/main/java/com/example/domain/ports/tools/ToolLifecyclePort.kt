package com.example.domain.ports.tools

import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.lifecycle.ToolAuditEntry
import com.example.domain.core.tools.lifecycle.ToolAuthorizationResult
import com.example.domain.core.tools.lifecycle.ToolDescriptor
import com.example.domain.core.tools.lifecycle.ToolHealthSnapshot
import com.example.domain.core.tools.lifecycle.ToolPermissionGrant
import com.example.domain.core.tools.lifecycle.ToolValidationResult
import com.example.domain.core.tools.lifecycle.PrincipalType
import com.example.domain.core.tools.lifecycle.ToolPermission
import com.example.domain.core.tools.lifecycle.ToolExecutionPolicy
import kotlinx.coroutines.flow.Flow

/**
 * Tool Lifecycle Port — full discover→register→validate→authorize→
 * expose→execute→observe→audit→revoke lifecycle.
 *
 * This is the port the audit explicitly asked for. The pre-existing
 * `ToolPort` only models `execute()`; this port models everything that
 * should happen BEFORE and AFTER execution.
 */
interface ToolLifecyclePort {

    /** DISCOVERED → REGISTERED: register a new tool declaration. */
    suspend fun register(
        declaration: ToolDeclaration,
        version: String = "1.0.0",
        policy: ToolExecutionPolicy = ToolExecutionPolicy()
    ): String

    /** REGISTERED → VALIDATED: validate the declaration schema and security posture. */
    suspend fun validate(toolId: String): ToolValidationResult

    /** VALIDATED → AUTHORIZED: authorize a principal to execute the tool. */
    suspend fun authorize(
        toolId: String,
        principalType: PrincipalType,
        principalId: String,
        permission: ToolPermission = ToolPermission.EXECUTE
    ): ToolAuthorizationResult

    /** AUTHORIZED → EXPOSED: expose the tool to the LLM tool catalog. */
    suspend fun expose(toolId: String): Boolean

    /** OBSERVED → REVOKED: revoke the tool (cannot be called anymore). */
    suspend fun revoke(toolId: String, reason: String): Boolean

    /** Persist a permission grant (used by authorize() and admin APIs). */
    suspend fun grantPermission(grant: ToolPermissionGrant): Long

    /** Revoke a previously-granted permission. */
    suspend fun revokePermission(grantId: Long)

    /** Check whether a principal has a specific permission on a tool. */
    suspend fun checkPermission(
        toolName: String,
        principalType: PrincipalType,
        principalId: String,
        permission: ToolPermission = ToolPermission.EXECUTE
    ): Boolean

    /** Record an audit entry after a tool call. */
    suspend fun recordAudit(entry: ToolAuditEntry)

    /** Get the current health snapshot for a tool. */
    suspend fun healthFor(toolId: String): ToolHealthSnapshot?

    /** Update the health snapshot after a call. */
    suspend fun updateHealth(
        toolId: String,
        outcome: String,
        latencyMs: Long,
        failureCode: String? = null,
        errorMessage: String? = null
    )

    /** List all tools currently in EXPOSED state (the LLM-facing catalog). */
    suspend fun listExposedTools(): List<ToolDescriptor>

    /** Stream of health snapshots — drives the dashboard. */
    fun healthSnapshots(): Flow<List<ToolHealthSnapshot>>

    /** Stream of audit entries — drives the activity feed. */
    fun auditTrail(limit: Int = 100): Flow<List<ToolAuditEntry>>
}
