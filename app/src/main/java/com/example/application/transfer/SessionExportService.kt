package com.example.application.transfer

import com.example.application.audit.AuditTrailService
import com.example.domain.core.audit.AuditActions
import com.example.domain.core.audit.AuditActorType
import com.example.domain.core.audit.AuditResult
import com.example.domain.core.context.ResourceScope
import com.example.domain.core.session.ConversationSessionId
import com.example.infrastructure.persistence.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 * REPAIR ORDER §14 — SESSION TRANSCRIPT EXPORT
 * ============================================================================
 * TXT is a PRESENTATION format, not the canonical representation: internally
 * the session is built as a CANONICAL STRUCTURED representation (JSON:
 * metadata, messages, timestamps, roles, model/provider metadata) which also
 * renders to Markdown and TXT.
 *
 * Scope/security: export is WORKSPACE-AUTHORIZED (another workspace's
 * session is NOT FOUND) and project-aware. No secrets are persisted in
 * transcripts by the session store; the export applies the standard
 * redactor as last-line defense.
 */
class SessionExportService(
    private val database: AppDatabase,
    private val auditTrail: AuditTrailService? = null
) {
    enum class Format { TXT, MARKDOWN, JSON }

    private val sessionDao = database.conversationSessionDao()
    private val turnDao = database.conversationTurnDao()

    /**
     * Builds the CANONICAL structured representation of a session (all
     * metadata, turns, timestamps, roles, model pins, outcomes).
     * Returns null when the session is not owned by [workspaceId].
     */
    suspend fun canonicalSession(
        workspaceId: String,
        sessionId: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        val session = sessionDao.byIdAndWorkspace(sessionId, workspaceId) ?: return@withContext null
        val turns = turnDao.forSessionOnce(sessionId)
        JSONObject().apply {
            put("schema", "aiv0.session.canonical/v1")
            put("sessionId", session.sessionId)
            put("workspaceId", session.workspaceId)
            put("projectId", session.projectId ?: JSONObject.NULL)
            put("title", session.title)
            put("mode", session.mode)
            put("agentId", session.agentId ?: JSONObject.NULL)
            put("agentName", session.agentName ?: JSONObject.NULL)
            put("modelResourceId", session.modelResourceId ?: JSONObject.NULL)
            put("modelDisplayName", session.modelDisplayName ?: JSONObject.NULL)
            put("turnCount", session.turnCount)
            put("totalTokensConsumed", session.totalTokensConsumed)
            put("createdAtEpochMs", session.createdAtEpochMs)
            put("lastActiveAtEpochMs", session.lastActiveAtEpochMs)
            put("messages", JSONArray().apply {
                turns.forEach { turn ->
                    put(JSONObject().apply {
                        put("turnId", turn.turnId)
                        put("role", "user")
                        put("content", turn.prompt)
                        put("timestampEpochMs", turn.createdAtEpochMs)
                    })
                    put(JSONObject().apply {
                        put("turnId", turn.turnId)
                        put("role", "assistant")
                        put("content", turn.answer)
                        put("agentName", turn.agentName ?: JSONObject.NULL)
                        put("agentRole", turn.agentRole ?: JSONObject.NULL)
                        put("modelResourceId", turn.modelResourceId ?: JSONObject.NULL)
                        put("tokensConsumed", turn.tokensConsumed)
                        put("durationMs", turn.durationMs)
                        put("isSuccessful", turn.isSuccessful)
                        put("timestampEpochMs", turn.createdAtEpochMs)
                    })
                }
            })
        }
    }

    /** Renders the canonical representation in the requested format. */
    suspend fun exportSession(
        workspaceId: String,
        sessionId: String,
        format: Format = Format.TXT
    ): String? = withContext(Dispatchers.IO) {
        val canonical = canonicalSession(workspaceId, sessionId) ?: return@withContext null
        val projectId = canonical.optLong("projectId", -1L)
        audit(AuditActions.SESSION_EXPORTED, workspaceId, projectId, sessionId)
        // REPAIR ORDER §13/§14 — last-line secret redaction on export.
        val render: String = when (format) {
            Format.JSON -> canonical.toString(2)
            Format.MARKDOWN -> renderMarkdown(canonical)
            Format.TXT -> renderTxt(canonical)
        }
        AuditTrailService.defaultRedact(render)
    }

    // ------------------------------------------------------------------
    // Renderers (presentation formats over the canonical representation)
    // ------------------------------------------------------------------

    private fun renderTxt(canonical: JSONObject): String = buildString {
        appendLine("══════ ${canonical.optString("title")} ══════")
        appendLine("الوضع: ${canonical.optString("mode")} | الجلسة: ${canonical.optString("sessionId")}")
        if (!canonical.isNull("modelDisplayName")) {
            appendLine("النموذج: ${canonical.optString("modelDisplayName")}")
        }
        appendLine("عدد الأدوار: ${canonical.optInt("turnCount")} | التوكنات: ${canonical.optInt("totalTokensConsumed")}")
        appendLine()
        val messages = canonical.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = if (m.optString("role") == "user") "[المستخدم]" else "[المساعد]"
            appendLine("$role ${m.optString("content")}")
            appendLine()
        }
    }

    private fun renderMarkdown(canonical: JSONObject): String = buildString {
        appendLine("# ${canonical.optString("title")}")
        appendLine()
        appendLine("- **الوضع**: ${canonical.optString("mode")}")
        appendLine("- **الجلسة**: `${canonical.optString("sessionId")}`")
        if (!canonical.isNull("modelDisplayName")) {
            appendLine("- **النموذج**: ${canonical.optString("modelDisplayName")}")
        }
        appendLine("- **الأدوار**: ${canonical.optInt("turnCount")} | **التوكنات**: ${canonical.optInt("totalTokensConsumed")}")
        appendLine()
        val messages = canonical.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            if (m.optString("role") == "user") {
                appendLine("## المستخدم")
                appendLine()
                appendLine(m.optString("content"))
            } else {
                appendLine("## المساعد")
                appendLine()
                appendLine(m.optString("content"))
            }
            appendLine()
        }
    }

    private suspend fun audit(action: String, workspaceId: String, projectId: Long, sessionId: String) {
        auditTrail?.recordAsync(
            actorType = AuditActorType.USER,
            actorId = "user",
            action = action,
            resourceType = "SESSION",
            resourceId = sessionId,
            sourceScope = if (projectId > 0) ResourceScope.Project(workspaceId, projectId)
            else ResourceScope.Workspace(workspaceId),
            result = AuditResult.SUCCESS
        )
    }
}
