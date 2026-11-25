package com.example.application.transfer

import androidx.room.withTransaction
import com.example.application.artifacts.ArtifactService
import com.example.application.audit.AuditTrailService
import com.example.domain.core.audit.AuditActions
import com.example.domain.core.audit.AuditActorType
import com.example.domain.core.audit.AuditResult
import com.example.domain.core.context.ResourceScope
import com.example.domain.core.session.ConversationSessionId
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.TurnAttachmentJsonCodec
import com.example.infrastructure.persistence.TurnSourceJsonCodec
import com.example.infrastructure.persistence.entities.ConversationSessionEntity
import com.example.infrastructure.persistence.entities.ConversationTurnEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/**
 * ============================================================================
 * REPAIR ORDER §14 — SESSION TRANSCRIPT EXPORT (FIW Stage 1 — UNIFIED)
 * ============================================================================
 * TXT is a PRESENTATION format, not the canonical representation: internally
 * the session is built as a CANONICAL STRUCTURED representation (JSON:
 * metadata, messages, timestamps, roles, model/provider metadata, attachment
 * references, citation chains, produced artifacts) which also renders to
 * Markdown and TXT.
 *
 * FIW STAGE 1 (Functional Integration & Workflow Closure) — the audit found
 * TWO export implementations living side by side (this service — wired but
 * UI-unused — and SessionTranscriptExporter feeding the share sheet), and NO
 * importable session package at all. This service is now the ONE export
 * authority:
 *
 *  - [exportSession] renders TXT / MARKDOWN / JSON;
 *  - [buildSessionPackage] produces the IMPORTABLE package
 *    (`aiv0.session.package/v1`: session + turns + attachment references +
 *    citation chains + produced artifacts WITH payloads, size-capped);
 *  - [importSessionPackage] restores a package as a NEW session (id remap,
 *    schema validation, explicit unsupported-version rejection, sanitized
 *    paths, artifact payload restore into the target project).
 *
 * Scope/security: export is WORKSPACE-AUTHORIZED (another workspace's
 * session is NOT FOUND) and project-aware. No secrets are persisted in
 * transcripts by the session store; the export applies the standard
 * redactor as last-line defense. Import NEVER overwrites an existing
 * session — a package always lands as a new identity.
 */
class SessionExportService(
    private val database: AppDatabase,
    private val auditTrail: AuditTrailService? = null,
    /**
     * FIW Stage 1: the artifact pipeline (payload read on export, payload
     * restore on import). Null (legacy tests) ⇒ packages carry metadata
     * only and import restores rows without payloads — the honest degraded
     * mode, never a fabricated "restored" claim.
     */
    private val artifactService: ArtifactService? = null
) {
    enum class Format { TXT, MARKDOWN, JSON }

    companion object {
        const val CANONICAL_SCHEMA = "aiv0.session.canonical/v1"
        const val PACKAGE_SCHEMA = "aiv0.session.package/v1"
        /** The package schema versions this build can import. */
        val SUPPORTED_PACKAGE_VERSIONS = setOf(1)
        /** Per-artifact payload cap (2 MiB) — larger payloads are skipped honestly. */
        const val MAX_ARTIFACT_PAYLOAD_BYTES = 2L * 1024 * 1024
        /** Whole-package payload cap (10 MiB) — the honest budget of a portable session. */
        const val MAX_PACKAGE_PAYLOAD_TOTAL_BYTES = 10L * 1024 * 1024
    }

    private val sessionDao = database.conversationSessionDao()
    private val turnDao = database.conversationTurnDao()
    private val artifactDao = database.artifactDao()

    /**
     * Builds the CANONICAL structured representation of a session (all
     * metadata, turns, timestamps, roles, model pins, outcomes, attachment
     * references, citation chains, produced artifacts).
     * Returns null when the session is not owned by [workspaceId].
     */
    suspend fun canonicalSession(
        workspaceId: String,
        sessionId: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        val session = sessionDao.byIdAndWorkspace(sessionId, workspaceId) ?: return@withContext null
        val turns = turnDao.forSessionOnce(sessionId)
        // FIW Stage 1: the session's produced artifacts (the v29 turnId
        // linkage gives per-message cards on restore; the export lists them
        // per turn with their real metadata).
        val artifactsByTurn = artifactDao.forSessionTurns(workspaceId, sessionId)
            .groupBy { it.turnId ?: "" }
        JSONObject().apply {
            put("schema", CANONICAL_SCHEMA)
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
                    val turnAttachments = TurnAttachmentJsonCodec.decode(turn.attachmentsJson)
                    val turnSources = TurnSourceJsonCodec.decode(turn.sourcesJson)
                    val turnArtifacts = artifactsByTurn[turn.turnId].orEmpty()
                    put(JSONObject().apply {
                        put("turnId", turn.turnId)
                        put("role", "user")
                        put("content", turn.prompt)
                        put("timestampEpochMs", turn.createdAtEpochMs)
                        if (turnAttachments.isNotEmpty()) {
                            put("attachments", JSONArray().apply {
                                turnAttachments.forEach { attachment ->
                                    put(JSONObject().apply {
                                        put("id", attachment.id)
                                        put("name", attachment.name)
                                        put("mimeType", attachment.mimeType)
                                        put("sizeBytes", attachment.sizeBytes)
                                        put("storageUri", attachment.storageUri)
                                        put("groundingState", attachment.groundingState)
                                        attachment.artifactId?.let { put("artifactId", it) }
                                    })
                                }
                            })
                        }
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
                        if (turnSources.isNotEmpty()) {
                            put("sources", JSONArray().apply {
                                turnSources.forEach { source ->
                                    put(JSONObject().apply {
                                        put("title", source.title)
                                        source.url?.let { put("url", it) }
                                        source.providerId?.let { put("providerId", it) }
                                        source.confidenceScore?.let { put("confidenceScore", it.toDouble()) }
                                    })
                                }
                            })
                        }
                        if (turnArtifacts.isNotEmpty()) {
                            put("artifacts", JSONArray().apply {
                                turnArtifacts.forEach { artifact ->
                                    put(JSONObject().apply {
                                        put("artifactId", artifact.id)
                                        put("name", artifact.name)
                                        put("type", artifact.type)
                                        put("mimeType", artifact.mimeType)
                                        put("sizeBytes", artifact.sizeBytes)
                                        put("storageUri", artifact.storageUri)
                                        put("createdAtEpochMs", artifact.createdAtEpochMs)
                                    })
                                }
                            })
                        }
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
    // FIW Stage 1 — the IMPORTABLE session package
    // ------------------------------------------------------------------

    sealed class PackageBuild {
        data class Built(val packageJson: JSONObject, val skippedPayloads: List<String>) : PackageBuild()
        data class Unavailable(val reason: String) : PackageBuild()
    }

    /**
     * Builds the importable `aiv0.session.package/v1`: the canonical session
     * PLUS the produced artifacts' payloads (base64, size-capped). Payloads
     * beyond the caps are skipped BY NAME in [PackageBuild.Built.skippedPayloads]
     * — the package says exactly what it does not carry.
     */
    suspend fun buildSessionPackage(
        workspaceId: String,
        sessionId: String
    ): PackageBuild = withContext(Dispatchers.IO) {
        val session = sessionDao.byIdAndWorkspace(sessionId, workspaceId)
            ?: return@withContext PackageBuild.Unavailable("الجلسة غير موجودة ضمن مساحة العمل الحالية.")
        val canonical = canonicalSession(workspaceId, sessionId)
            ?: return@withContext PackageBuild.Unavailable("تعذر بناء التمثيل القانوني للجلسة.")
        val turns = turnDao.forSessionOnce(sessionId)
        val artifactsByTurn = artifactDao.forSessionTurns(workspaceId, sessionId)
            .groupBy { it.turnId ?: "" }
        val accessorScope = ResourceScope.Project(workspaceId, session.projectId ?: -1L)

        val skipped = mutableListOf<String>()
        var payloadTotal = 0L
        val artifactsArray = JSONArray()
        turns.forEach { turn ->
            artifactsByTurn[turn.turnId].orEmpty().forEach { artifact ->
                val entry = JSONObject().apply {
                    put("sourceArtifactId", artifact.id)
                    put("turnId", turn.turnId)
                    put("name", artifact.name)
                    put("type", artifact.type)
                    put("mimeType", artifact.mimeType)
                    put("sizeBytes", artifact.sizeBytes)
                    put("contentHash", artifact.contentHash ?: JSONObject.NULL)
                    put("createdBy", "user")
                    put("createdAtEpochMs", artifact.createdAtEpochMs)
                }
                val bytes = artifactService?.let { service ->
                    runCatching { service.readContent(accessorScope, artifact.id) }.getOrNull()
                }
                when {
                    artifactService == null -> {
                        entry.put("payloadIncluded", false)
                        entry.put("payloadSkipReason", "SERVICE_UNAVAILABLE")
                        skipped += artifact.name
                    }
                    bytes == null -> {
                        entry.put("payloadIncluded", false)
                        entry.put("payloadSkipReason", "READ_FAILED")
                        skipped += artifact.name
                    }
                    artifact.sizeBytes > MAX_ARTIFACT_PAYLOAD_BYTES ||
                        payloadTotal + bytes.size > MAX_PACKAGE_PAYLOAD_TOTAL_BYTES -> {
                        entry.put("payloadIncluded", false)
                        entry.put("payloadSkipReason", "SIZE_CAP")
                        skipped += artifact.name
                    }
                    else -> {
                        entry.put("payloadIncluded", true)
                        entry.put("payloadBase64", Base64.getEncoder().encodeToString(bytes))
                        payloadTotal += bytes.size
                    }
                }
                artifactsArray.put(entry)
            }
        }
        val packageJson = JSONObject().apply {
            put("schema", PACKAGE_SCHEMA)
            put("schemaVersion", 1)
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("sourceAppId", "com.aistudio.aiv0ultimate.kxmjzv")
            put("session", canonical)
            put("artifacts", artifactsArray)
            put("payloadTotalBytes", payloadTotal)
        }
        audit(AuditActions.SESSION_EXPORTED, workspaceId, session.projectId ?: -1L, sessionId)
        PackageBuild.Built(packageJson, skipped)
    }

    sealed class ImportOutcome {
        /** The new session id + counts of what really landed. */
        data class Imported(
            val newSessionId: String,
            val turnCount: Int,
            val artifactsRestored: Int,
            val payloadsRestored: Int,
            val payloadsSkipped: List<String>
        ) : ImportOutcome()

        data class Rejected(val reason: String) : ImportOutcome()
    }

    /**
     * Imports a package as a NEW session under the target workspace/project.
     * Validation is explicit and total: schema identity, version support,
     * required fields, and path sanitization (an attachment/storage uri that
     * tries to escape the sandbox rejects the WHOLE import — the destination
     * is never partially mutated). Every id is remapped; nothing existing is
     * overwritten (the REMAINING distinction from project-package import and
     * full-app backup).
     */
    suspend fun importSessionPackage(
        workspaceId: String,
        projectId: Long?,
        packageJson: JSONObject
    ): ImportOutcome = withContext(Dispatchers.IO) {
        // ---- VALIDATION (before a single row is written) ----
        val schema = packageJson.optString("schema")
        if (schema != PACKAGE_SCHEMA) {
            return@withContext ImportOutcome.Rejected(
                "المخطط غير معروف («$schema») — هذا ليس ملف حزمة جلسة صالحاً."
            )
        }
        val version = packageJson.optInt("schemaVersion", -1)
        if (version !in SUPPORTED_PACKAGE_VERSIONS) {
            return@withContext ImportOutcome.Rejected(
                "إصدار الحزمة ($version) غير مدعوم في هذه النسخة — الإصدارات المدعومة: " +
                    SUPPORTED_PACKAGE_VERSIONS.joinToString(", ")
            )
        }
        val sessionJson = packageJson.optJSONObject("session")
            ?: return@withContext ImportOutcome.Rejected("الحزمة لا تحتوي على بيانات جلسة.")
        val messages = sessionJson.optJSONArray("messages")
            ?: return@withContext ImportOutcome.Rejected("الحزمة لا تحتوي على رسائل.")
        if (sessionJson.optString("title").isBlank()) {
            return@withContext ImportOutcome.Rejected("عنوان الجلسة مفقود من الحزمة.")
        }
        // Path sanitization: every attachment storageUri must be a relative,
        // traversal-free path (no leading /, no .., no drive letters).
        val turnAttachmentsByTurn = mutableMapOf<String, List<com.example.domain.core.session.TurnAttachment>>()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role") != "user") continue
            val turnId = message.optString("turnId")
            val attachments = message.optJSONArray("attachments") ?: continue
            val decoded = mutableListOf<com.example.domain.core.session.TurnAttachment>()
            for (a in 0 until attachments.length()) {
                val attachment = attachments.optJSONObject(a) ?: continue
                val storageUri = attachment.optString("storageUri")
                if (!isSafeRelativePath(storageUri)) {
                    return@withContext ImportOutcome.Rejected(
                        "مسار مرفق غير آمن في الحزمة («$storageUri») — رُفض الاستيراد بالكامل."
                    )
                }
                decoded += com.example.domain.core.session.TurnAttachment(
                    id = "impatt_${UUID.randomUUID().toString().take(10)}",
                    name = attachment.optString("name").ifBlank { storageUri.substringAfterLast('/') },
                    mimeType = attachment.optString("mimeType", "application/octet-stream"),
                    sizeBytes = attachment.optLong("sizeBytes", 0L),
                    storageUri = storageUri,
                    provenance = "PACKAGE_IMPORT",
                    groundingState = attachment.optString("groundingState", "ATTACHMENT_ONLY"),
                    artifactId = null // remapped below when a payload lands
                )
            }
            if (decoded.isNotEmpty()) turnAttachmentsByTurn[turnId] = decoded
        }
        val artifactsArray = packageJson.optJSONArray("artifacts") ?: JSONArray()

        // ---- RESTORE (one transaction: all rows or none) ----
        val newSessionId = "sess_${UUID.randomUUID().toString().take(12)}"
        val now = System.currentTimeMillis()
        val sessionEntity = ConversationSessionEntity(
            sessionId = newSessionId,
            workspaceId = workspaceId,
            title = sessionJson.optString("title") + " (مستوردة)",
            mode = sessionJson.optString("mode", "QUICK_CHAT"),
            agentId = sessionJson.optString("agentId").takeIf { it.isNotBlank() },
            agentName = sessionJson.optString("agentName").takeIf { it.isNotBlank() },
            modelResourceId = sessionJson.optString("modelResourceId").takeIf { it.isNotBlank() },
            modelDisplayName = sessionJson.optString("modelDisplayName").takeIf { it.isNotBlank() },
            turnCount = 0,
            totalTokensConsumed = 0,
            createdAtEpochMs = now,
            lastActiveAtEpochMs = now,
            projectId = projectId
        )
        // Group the canonical messages back into turns (user + assistant pairs
        // share turnId; ordering by timestamp).
        val turnIdsInOrder = mutableListOf<String>()
        val turnData = mutableMapOf<String, JSONObject>()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val turnId = message.optString("turnId")
            if (turnId.isBlank() || turnIdsInOrder.contains(turnId)) continue
            turnIdsInOrder += turnId
            turnData[turnId] = message
        }
        var restoredArtifacts = 0
        var restoredPayloads = 0
        val skippedPayloads = mutableListOf<String>()
        database.withTransaction {
            sessionDao.upsert(sessionEntity)
            var totalTokens = 0
            turnIdsInOrder.forEachIndexed { index, sourceTurnId ->
                val message = turnData[sourceTurnId] ?: return@forEachIndexed
                val prompt = message.optString("content")
                // The assistant twin rides the SAME source turnId (the next
                // message with the same turnId).
                val assistantMessage = (0 until messages.length())
                    .asSequence()
                    .map { messages.optJSONObject(it) }
                    .firstOrNull {
                        it != null && it.optString("turnId") == sourceTurnId &&
                            it.optString("role") == "assistant"
                    }
                val newTurnId = "turn_imp_${newSessionId.takeLast(6)}_${index}"
                val attachments = turnAttachmentsByTurn[sourceTurnId].orEmpty()
                val turnEntity = ConversationTurnEntity(
                    turnId = newTurnId,
                    sessionId = newSessionId,
                    prompt = prompt,
                    answer = assistantMessage?.optString("content") ?: "",
                    agentName = assistantMessage?.optString("agentId")?.takeIf { it.isNotBlank() },
                    agentRole = assistantMessage?.optString("agentRole")?.takeIf { it.isNotBlank() },
                    modelResourceId = assistantMessage?.optString("modelResourceId")?.takeIf { it.isNotBlank() },
                    tokensConsumed = assistantMessage?.optInt("tokensConsumed", 0) ?: 0,
                    durationMs = assistantMessage?.optLong("durationMs", 0L) ?: 0L,
                    isSuccessful = assistantMessage?.optBoolean("isSuccessful", true) ?: true,
                    eventCount = assistantMessage?.optInt("eventCount", 0) ?: 0,
                    createdAtEpochMs = now + index, // preserves package order deterministically
                    attachmentsJson = TurnAttachmentJsonCodec.encode(attachments),
                    sourcesJson = assistantMessage?.optJSONArray("sources")?.let { sources ->
                        // Re-encode through the lossless codec (unknown fields dropped).
                        val refs = mutableListOf<com.example.domain.core.session.TurnSourceRef>()
                        for (s in 0 until sources.length()) {
                            val source = sources.optJSONObject(s) ?: continue
                            refs += com.example.domain.core.session.TurnSourceRef(
                                title = source.optString("title"),
                                url = source.optString("url").takeIf { it.isNotBlank() },
                                providerId = source.optString("providerId").takeIf { it.isNotBlank() },
                                confidenceScore = source.optDouble("confidenceScore", Double.NaN)
                                    .takeIf { !it.isNaN() }?.toFloat()
                            )
                        }
                        TurnSourceJsonCodec.encode(refs)
                    } ?: "[]"
                )
                turnDao.insert(turnEntity)
                totalTokens += turnEntity.tokensConsumed
            }
            // Artifact payload restore — only entries whose turn REALLY landed.
            for (i in 0 until artifactsArray.length()) {
                val entry = artifactsArray.optJSONObject(i) ?: continue
                val sourceTurnId = entry.optString("turnId")
                val matchingIndex = turnIdsInOrder.indexOf(sourceTurnId)
                if (matchingIndex < 0) continue
                val newTurnId = "turn_imp_${newSessionId.takeLast(6)}_${matchingIndex}"
                val name = entry.optString("name").ifBlank { "artifact_import" }
                val payloadIncluded = entry.optBoolean("payloadIncluded", false)
                if (payloadIncluded && artifactService != null) {
                    val bytes = runCatching {
                        Base64.getDecoder().decode(entry.optString("payloadBase64"))
                    }.getOrNull()
                    if (bytes == null || bytes.isEmpty()) {
                        skippedPayloads += name
                        continue
                    }
                    val descriptor = runCatching {
                        artifactService!!.saveAssistantResultAsArtifact(
                            workspaceId = workspaceId,
                            projectId = projectId ?: continue,
                            sessionId = newSessionId,
                            executionId = null,
                            name = name,
                            content = bytes,
                            mimeType = entry.optString("mimeType", "application/octet-stream"),
                            createdBy = "package_import",
                            turnId = newTurnId
                        )
                    }.getOrNull()
                    if (descriptor == null) {
                        skippedPayloads += name
                    } else {
                        restoredArtifacts++
                        restoredPayloads++
                    }
                } else if (!payloadIncluded) {
                    skippedPayloads += name
                }
            }
            // Session aggregates reflect the REAL landed turns.
            val landedTurns = turnIdsInOrder.size
            sessionDao.upsert(
                sessionEntity.copy(
                    turnCount = landedTurns,
                    totalTokensConsumed = totalTokens,
                    lastActiveAtEpochMs = now
                )
            )
        }
        audit(AuditActions.SESSION_EXPORTED, workspaceId, projectId ?: -1L, newSessionId)
        ImportOutcome.Imported(
            newSessionId = newSessionId,
            turnCount = turnIdsInOrder.size,
            artifactsRestored = restoredArtifacts,
            payloadsRestored = restoredPayloads,
            payloadsSkipped = skippedPayloads
        )
    }

    /** A relative, traversal-free path (the import's path sanitization). */
    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.startsWith("\\")) return false
        if (path.contains("://") || path.contains(":")) return false
        val segments = path.split('/', '\\')
        return segments.all { it.isNotBlank() && it != "." && it != ".." }
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
            // FIW Stage 1: the attachments a message carried are part of the
            // transcript's truth — a name line, never a content claim.
            m.optJSONArray("attachments")?.let { attachments ->
                for (a in 0 until attachments.length()) {
                    val attachment = attachments.optJSONObject(a) ?: continue
                    appendLine("  [مرفق] ${attachment.optString("name")} (${attachment.optString("mimeType")})")
                }
            }
            m.optJSONArray("sources")?.let { sources ->
                for (s in 0 until sources.length()) {
                    val source = sources.optJSONObject(s) ?: continue
                    appendLine("  [مصدر] ${source.optString("title")}")
                }
            }
            m.optJSONArray("artifacts")?.let { artifacts ->
                for (a in 0 until artifacts.length()) {
                    val artifact = artifacts.optJSONObject(a) ?: continue
                    appendLine("  [مخرج] ${artifact.optString("name")}")
                }
            }
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
                m.optJSONArray("attachments")?.let { attachments ->
                    for (a in 0 until attachments.length()) {
                        val attachment = attachments.optJSONObject(a) ?: continue
                        appendLine("- 📎 مرفق: ${attachment.optString("name")} (${attachment.optString("mimeType")})")
                    }
                }
            } else {
                appendLine("## المساعد")
                appendLine()
                appendLine(m.optString("content"))
                m.optJSONArray("sources")?.let { sources ->
                    for (s in 0 until sources.length()) {
                        val source = sources.optJSONObject(s) ?: continue
                        val url = source.optString("url").takeIf { it.isNotBlank() }
                        appendLine("- 🔗 مصدر: ${source.optString("title")}${url?.let { " — $it" } ?: ""}")
                    }
                }
                m.optJSONArray("artifacts")?.let { artifacts ->
                    for (a in 0 until artifacts.length()) {
                        val artifact = artifacts.optJSONObject(a) ?: continue
                        appendLine("- 📦 مخرج: ${artifact.optString("name")}")
                    }
                }
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
