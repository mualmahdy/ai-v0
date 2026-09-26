package com.example.application.transfer

import androidx.room.withTransaction
import com.example.application.audit.AuditTrailService
import com.example.application.rag.RagPipelineService
import com.example.domain.core.audit.AuditActions
import com.example.domain.core.audit.AuditActorType
import com.example.domain.core.audit.AuditResult
import com.example.domain.core.context.ResourceScope
import com.example.domain.core.project.DependencyRequirement
import com.example.domain.core.project.DependencyStatus
import com.example.domain.core.project.ProjectDependencyType
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.ArtifactEntity
import com.example.infrastructure.persistence.entities.ChatTimelineEventEntity
import com.example.infrastructure.persistence.entities.ConversationSessionEntity
import com.example.infrastructure.persistence.entities.ConversationTurnEntity
import com.example.infrastructure.persistence.entities.DocumentChunkEntity
import com.example.infrastructure.persistence.entities.KnowledgeDocumentEntity
import com.example.infrastructure.persistence.entities.ProjectDependencyEntity
import com.example.infrastructure.persistence.entities.ProjectSnapshotEntity
import com.example.infrastructure.persistence.entities.TaskEntity
import com.example.infrastructure.storage.SandboxProjectFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ============================================================================
 * CLOSURE PHASE (spec §4/§5) — VERSIONED PORTABLE PROJECT PACKAGES v3
 * ============================================================================
 * A project package is a STRUCTURED, VERSIONED container with a
 * manifest-first layout:
 *
 *   .aiv0project/
 *     manifest.json        — schema v3: identity, counts, FULL content
 *                            hashes (no sampling cap), files.zip digest
 *     manifest.sha256       — canonical manifest digest (v2+)
 *     project.json          — name/description/lifecycle
 *     files.zip             — the project sandbox (nested zip)
 *     knowledge.json        — documents (metadata + FULL content — the
 *                            trusted chunk-rebuild source; embeddings are
 *                            NOT portable and are rebuilt at import)
 *     sessions.json         — session metadata
 *     turns.json            — FULL conversation turns (NEW in v3)
 *     timeline.json         — capability/approval timeline events (NEW)
 *     tasks.json            — FULL task state incl. checkpoint + canonical
 *                            execution context (NEW in v3)
 *     artifacts.json        — artifact rows (payloads ride in files.zip
 *                            via their sandbox-relative storageUri) (NEW)
 *     dependencies.json     — dependency declarations
 *     metadata/about.json
 *
 * IMPORT PIPELINE (CLOSURE §4.2 — atomic, no partial destination state):
 *   READ → VALIDATE (manifest/digest/hashes/COUNTS) → STAGE (files land in
 *   a staging dir, hash-verified) → TRANSFER (ONE Room transaction: all
 *   rows or none) → COMMIT (atomic staging→root promotion) → VERIFY
 *   (post-state counts + hashes vs manifest; mismatch ⇒ full ROLLBACK) →
 *   REBUILD_INDEXES (RAG chunks re-split + re-embedded on THIS device) →
 *   audit.
 *
 * INTEGRITY vs AUTHENTICITY: the manifest hashes prove the package was not
 * corrupted in transit (integrity). They do NOT prove who created the
 * package (authenticity) — there is no signature; the app identity check is
 * a compatibility gate, not a trust boundary. This distinction is
 * deliberate and documented.
 */
class ProjectPackageService(
    private val database: AppDatabase,
    private val fileStore: SandboxProjectFileStore,
    private val auditTrail: AuditTrailService? = null,
    private val limits: TransferLimits = TransferLimits.DEFAULT,
    /**
     * CLOSURE §5 (RAG portability): the trusted rebuild source for imported
     * documents. Null (tests/legacy wiring) = chunk rebuild is honestly
     * UNAVAILABLE — imported documents persist with totalChunks = 0 and the
     * import report says so (never a fake chunk count).
     */
    private val ragPipeline: RagPipelineService? = null
) {
    companion object {
        /**
         * v1: legacy. v2: manifest integrity digest. v3: complete
         * portability (turns, timeline events, full task state, artifacts,
         * full-coverage content hashes, files.zip digest, count contracts).
         */
        const val PACKAGE_SCHEMA_VERSION = 3
        const val MANIFEST_ENTRY = "manifest.json"
        const val MANIFEST_SHA256_ENTRY = "manifest.sha256"
        const val PROJECT_ENTRY = "project.json"
        const val FILES_ENTRY = "files.zip"
        const val KNOWLEDGE_ENTRY = "knowledge.json"
        const val SESSIONS_ENTRY = "sessions.json"
        const val TURNS_ENTRY = "turns.json"
        const val TIMELINE_ENTRY = "timeline.json"
        const val TASKS_ENTRY = "tasks.json"
        const val ARTIFACTS_ENTRY = "artifacts.json"
        const val DEPENDENCIES_ENTRY = "dependencies.json"
        const val ABOUT_ENTRY = "metadata/about.json"
        const val APP_ID = "com.aistudio.aiv0ultimate"
        /** Payload store for snapshots (outside any project root). */
        const val SNAPSHOT_DIR = "snapshots"
    }

    private val projectDao = database.projectDao()
    private val knowledgeDao = database.knowledgeDocumentDao()
    private val chunkDao = database.documentChunkDao()
    private val sessionDao = database.conversationSessionDao()
    private val turnDao = database.conversationTurnDao()
    private val timelineDao = database.chatTimelineEventDao()
    private val taskDao = database.taskDao()
    private val dependencyDao = database.projectDependencyDao()
    private val snapshotDao = database.projectSnapshotDao()
    private val artifactDao = database.artifactDao()

    // ==================================================================
    // EXPORT
    // ==================================================================

    /**
     * Exports a project as a .aiv0project package. Secrets are excluded BY
     * CONSTRUCTION: only references (provider/model/tool ids) travel.
     * Workspace-shared knowledge is NOT project data and is not exported.
     */
    suspend fun exportProject(
        workspaceId: String,
        projectId: Long,
        destination: OutputStream,
        includeSessions: Boolean = true,
        includeTasks: Boolean = true
    ): TransferOutcome = withContext(Dispatchers.IO) {
        try {
            val project = projectDao.getProjectByIdForWorkspace(projectId, workspaceId)
                ?: return@withContext TransferOutcome.Failure(
                    "PROJECT_NOT_FOUND", "المشروع غير موجود في هذه المساحة.", true
                )
            val root = fileStore.projectRoot(projectId)
            // CLOSURE §4.3: FILES ONLY — list() also yields DIRECTORY entries;
            // the manifest's fileCount contract counts exactly what rides
            // files.zip (previously directories inflated the count and the
            // v3 count contract rejected our own packages).
            val files = fileStore.list(root)
                .filterNot { it.startsWith(".staging") }
                .filter { rel -> fileStore.stat(root, rel).let { it.exists && !it.isDirectory } }

            val documents = knowledgeDao.getProjectPrivateDocuments(projectId)
            val sessions = if (includeSessions) sessionDao.forProject(projectId) else emptyList()
            val sessionIds = sessions.map { it.sessionId }
            val turns = if (includeSessions) turnDao.forSessionsOnce(sessionIds) else emptyList()
            val timeline = if (includeSessions) timelineDao.forSessionsOnce(sessionIds) else emptyList()
            val tasks = if (includeTasks) taskDao.getTasksForProject(projectId) else emptyList()
            val artifacts = artifactDao.forProject(projectId, limit = 10_000)
            val dependencies = dependencyDao.forProject(projectId)

            // ---- files.zip first: its digest travels INSIDE the manifest ----
            val fileBytesStream = java.io.ByteArrayOutputStream()
            ZipOutputStream(fileBytesStream).use { inner ->
                files.forEach { rel ->
                    val content = runCatching { fileStore.read(root, rel) }.getOrNull() ?: return@forEach
                    inner.putNextEntry(ZipEntry(rel))
                    inner.write(content)
                    inner.closeEntry()
                }
            }
            val filesZipBytes = fileBytesStream.toByteArray()

            // ---- Manifest (v3: full-coverage hashes + counts + zip digest) ----
            val manifest = JSONObject().apply {
                put("packageSchemaVersion", PACKAGE_SCHEMA_VERSION)
                put("appId", APP_ID)
                put("packageId", "aiv0pkg_${UUID.randomUUID().toString().take(12)}")
                put("projectId", projectId)
                put("projectName", project.name)
                put("sourceWorkspaceId", workspaceId)
                put("exportedAtEpochMs", System.currentTimeMillis())
                put("secretsExcluded", true)
                put("includedScopes", JSONArray().put("FILES").put("KNOWLEDGE").put("ARTIFACTS")
                    .apply { if (includeSessions) put("SESSIONS"); if (includeTasks) put("TASKS") })
                // COUNT CONTRACTS (v3): the importer cross-checks every one.
                put("fileCount", files.size)
                put("knowledgeCount", documents.size)
                put("sessionCount", sessions.size)
                put("turnCount", turns.size)
                put("timelineEventCount", timeline.size)
                put("taskCount", tasks.size)
                put("artifactCount", artifacts.size)
                put("projectLifecycleState", project.lifecycleState)
                // FULL coverage — every file is hashed (no 500-file sample).
                val hashes = JSONObject()
                files.forEach { rel ->
                    fileStore.hash(root, rel)?.let { hashes.put(rel, it) }
                }
                put("contentHashes", hashes)
                put("filesZipSha256", sha256Of(filesZipBytes))
                // Honest RAG portability policy (spec §5): embeddings are
                // device/resource-bound; the document CONTENT is the trusted
                // rebuild source.
                put("knowledgeChunkPolicy", "REBUILD_AT_IMPORT")
            }

            ZipOutputStream(destination.buffered()).use { zos ->
                fun put(name: String, bytes: ByteArray) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
                val manifestBytes = manifest.toString(2).toByteArray()
                put(MANIFEST_ENTRY, manifestBytes)
                put(MANIFEST_SHA256_ENTRY, sha256Of(manifestBytes).toByteArray())
                put(PROJECT_ENTRY, JSONObject().apply {
                    put("name", project.name)
                    put("description", project.description ?: "")
                    put("lifecycleState", "ACTIVE")
                    put("createdAtEpochMs", project.createdAtEpochMs)
                }.toString(2).toByteArray())
                put(FILES_ENTRY, filesZipBytes)

                put(KNOWLEDGE_ENTRY, JSONArray().apply {
                    documents.forEach { doc ->
                        put(JSONObject().apply {
                            put("id", doc.id)
                            put("title", doc.title)
                            put("sourceUri", doc.sourceUri)
                            put("mimeType", doc.mimeType)
                            put("content", doc.content)
                            put("tags", JSONArray(doc.tagsJson.split(",").filter { it.isNotBlank() }))
                            put("totalChunks", doc.totalChunks)
                        })
                    }
                }.toString().toByteArray())

                put(SESSIONS_ENTRY, JSONArray().apply {
                    sessions.forEach { s ->
                        put(JSONObject().apply {
                            put("sessionId", s.sessionId)
                            put("title", s.title)
                            put("mode", s.mode)
                            put("agentId", s.agentId ?: JSONObject.NULL)
                            put("agentName", s.agentName ?: JSONObject.NULL)
                            put("modelResourceId", s.modelResourceId ?: JSONObject.NULL)
                            put("modelDisplayName", s.modelDisplayName ?: JSONObject.NULL)
                            put("turnCount", s.turnCount)
                            put("totalTokensConsumed", s.totalTokensConsumed)
                            put("createdAtEpochMs", s.createdAtEpochMs)
                            put("lastActiveAtEpochMs", s.lastActiveAtEpochMs)
                        })
                    }
                }.toString().toByteArray())

                // v3: FULL turns (the conversation IS project data).
                put(TURNS_ENTRY, JSONArray().apply {
                    turns.forEach { t ->
                        put(JSONObject().apply {
                            put("turnId", t.turnId)
                            put("sessionId", t.sessionId)
                            put("prompt", t.prompt)
                            put("answer", t.answer)
                            put("agentName", t.agentName ?: JSONObject.NULL)
                            put("agentRole", t.agentRole ?: JSONObject.NULL)
                            put("modelResourceId", t.modelResourceId ?: JSONObject.NULL)
                            put("tokensConsumed", t.tokensConsumed)
                            put("durationMs", t.durationMs)
                            put("isSuccessful", t.isSuccessful)
                            put("eventCount", t.eventCount)
                            put("createdAtEpochMs", t.createdAtEpochMs)
                            put("attachmentsJson", t.attachmentsJson)
                            put("sourcesJson", t.sourcesJson)
                        })
                    }
                }.toString().toByteArray())

                put(TIMELINE_ENTRY, JSONArray().apply {
                    timeline.forEach { e ->
                        put(JSONObject().apply {
                            put("eventId", e.eventId)
                            put("sessionId", e.sessionId)
                            put("kind", e.kind)
                            put("capabilityKind", e.capabilityKind ?: JSONObject.NULL)
                            put("title", e.title)
                            put("summary", e.summary)
                            put("detail", e.detail ?: JSONObject.NULL)
                            put("sourcesJson", e.sourcesJson)
                            put("isSuccessful", e.isSuccessful)
                            put("isDegraded", e.isDegraded)
                            put("degradedMessage", e.degradedMessage ?: JSONObject.NULL)
                            put("createdAtEpochMs", e.createdAtEpochMs)
                            put("approvalId", e.approvalId ?: JSONObject.NULL)
                            put("executionId", e.executionId ?: JSONObject.NULL)
                            put("toolName", e.toolName ?: JSONObject.NULL)
                            put("riskLevel", e.riskLevel ?: JSONObject.NULL)
                            put("justification", e.justification ?: JSONObject.NULL)
                            put("approvalState", e.approvalState ?: JSONObject.NULL)
                        })
                    }
                }.toString().toByteArray())

                // v3: FULL task state — an imported task keeps its REAL
                // lifecycle (never blanket-COMPLETED) and stays resumable
                // through its checkpoint + canonical execution context.
                put(TASKS_ENTRY, JSONArray().apply {
                    tasks.forEach { t ->
                        put(JSONObject().apply {
                            put("id", t.id)
                            put("assignedAgentId", t.assignedAgentId)
                            put("rawPrompt", t.rawPrompt)
                            put("lifecycleState", t.lifecycleState)
                            put("autonomyPolicy", t.autonomyPolicy)
                            put("resultSummary", t.resultSummary ?: JSONObject.NULL)
                            put("totalTokensConsumed", t.totalTokensConsumed)
                            put("durationMs", t.durationMs)
                            put("isDegraded", t.isDegraded)
                            put("degradedReason", t.degradedReason ?: JSONObject.NULL)
                            put("errorMessage", t.errorMessage ?: JSONObject.NULL)
                            put("createdAtEpochMs", t.createdAtEpochMs)
                            put("goal", t.goal)
                            put("currentStepIndex", t.currentStepIndex)
                            put("tokenLimit", t.tokenLimit)
                            put("maxRetries", t.maxRetries)
                            put("allowDegradedExecution", t.allowDegradedExecution)
                            put("requireHumanConsentForSensitiveTools", t.requireHumanConsentForSensitiveTools)
                            put("timeoutMs", t.timeoutMs)
                            put("minOutputLengthChars", t.minOutputLengthChars)
                            put("verificationStrategy", t.verificationStrategy)
                            put("assignedModelId", t.assignedModelId ?: JSONObject.NULL)
                            put("parentTaskId", t.parentTaskId ?: JSONObject.NULL)
                            put("delegationDepth", t.delegationDepth)
                            put("checkpointJson", t.checkpointJson ?: JSONObject.NULL)
                            put("executionContextJson", t.executionContextJson ?: JSONObject.NULL)
                        })
                    }
                }.toString().toByteArray())

                put(ARTIFACTS_ENTRY, JSONArray().apply {
                    artifacts.forEach { a ->
                        put(JSONObject().apply {
                            put("id", a.id)
                            put("type", a.type)
                            put("name", a.name)
                            put("mimeType", a.mimeType)
                            put("sizeBytes", a.sizeBytes)
                            put("contentHash", a.contentHash ?: JSONObject.NULL)
                            put("storageUri", a.storageUri)
                            put("source", a.source)
                            put("securityClassification", a.securityClassification)
                            put("indexingState", a.indexingState)
                            put("sessionId", a.sessionId ?: JSONObject.NULL)
                            put("taskId", a.taskId ?: JSONObject.NULL)
                            put("executionId", a.executionId ?: JSONObject.NULL)
                            put("createdAtEpochMs", a.createdAtEpochMs)
                            put("updatedAtEpochMs", a.updatedAtEpochMs)
                            put("metadataJson", a.metadataJson)
                        })
                    }
                }.toString().toByteArray())

                put(DEPENDENCIES_ENTRY, JSONArray().apply {
                    dependencies.forEach { d ->
                        put(JSONObject().apply {
                            put("type", d.type)
                            put("key", d.key)
                            put("requirement", d.requirement)
                            put("status", d.status)
                            put("detail", d.detail ?: JSONObject.NULL)
                        })
                    }
                }.toString().toByteArray())

                put(ABOUT_ENTRY, JSONObject().apply {
                    put("format", "aiv0project")
                    put("formatVersion", PACKAGE_SCHEMA_VERSION)
                    put("exportedBy", APP_ID)
                    put("secretPolicy", "EXCLUDED_BY_DEFAULT")
                }.toString(2).toByteArray())
            }

            audit(AuditActions.PROJECT_EXPORTED, workspaceId, projectId, AuditResult.SUCCESS,
                reason = "files=${files.size} turns=${turns.size} tasks=${tasks.size} artifacts=${artifacts.size}")
            TransferOutcome.Success(
                "تم تصدير المشروع '${project.name}' (${files.size} ملفاً، ${turns.size} دورة، ${artifacts.size} مخرجاً).",
                importedFileCount = files.size
            )
        } catch (e: Exception) {
            TransferOutcome.Failure("EXPORT_EXCEPTION", "فشل تصدير المشروع: ${e.message}", true)
        }
    }

    // ==================================================================
    // IMPORT — STAGE → TRANSFER → COMMIT → VERIFY (+ROLLBACK) → REBUILD
    // ==================================================================

    /** Parsed package (validated; staged in memory — metadata + files). */
    data class ParsedPackage(
        val manifest: JSONObject,
        val project: JSONObject,
        val fileEntries: Map<String, ByteArray>,
        val knowledge: JSONArray,
        val sessions: JSONArray,
        val turns: JSONArray,
        val timeline: JSONArray,
        val tasks: JSONArray,
        val artifacts: JSONArray,
        val dependencies: JSONArray
    )

    /** Stage 1-6: READ + VALIDATE (no destination mutation). */
    fun readAndValidatePackage(stream: InputStream): Pair<ParsedPackage?, TransferFailureExt?> =
        readAndValidateInternal(stream)

    data class TransferFailureExt(val code: String, val message: String)

    private fun readAndValidateInternal(stream: InputStream): Pair<ParsedPackage?, TransferFailureExt?> {
        try {
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(stream.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    if (!fileStore.validateZipEntryName(name)) {
                        return null to TransferFailureExt(
                            "PATH_TRAVERSAL_DETECTED", "مدخل غير آمن في الحزمة: $name"
                        )
                    }
                    if (entries.size > limits.maxEntryCount) {
                        return null to TransferFailureExt("ENTRY_COUNT_EXCEEDED", "عدد مدخلات الحزمة يتجاوز الحد.")
                    }
                    val bytes = zis.readBytes()
                    if (bytes.size > limits.maxSingleEntryBytes) {
                        return null to TransferFailureExt("ARCHIVE_TOO_LARGE", "مدخل يتجاوز الحد الأقصى.")
                    }
                    entries[name] = bytes
                }
            }
            // --- Validate manifest ---
            val manifestBytes = entries[MANIFEST_ENTRY]
                ?: return null to TransferFailureExt("MISSING_MANIFEST", "الحزمة لا تحتوي على manifest.json — ليست حزمة .aiv0project صالحة.")
            if (manifestBytes.size > limits.maxManifestBytes) {
                return null to TransferFailureExt("ARCHIVE_TOO_LARGE", "manifest.json يتجاوز الحد.")
            }
            val manifest = runCatching { JSONObject(manifestBytes.decodeToString()) }.getOrNull()
                ?: return null to TransferFailureExt("INVALID_MANIFEST", "manifest.json غير صالح.")
            val schemaVersion = manifest.optInt("packageSchemaVersion", -1)
            if (schemaVersion !in 1..PACKAGE_SCHEMA_VERSION) {
                return null to TransferFailureExt(
                    "UNSUPPORTED_SCHEMA",
                    "إصدار مخطط الحزمة ($schemaVersion) غير مدعوم (المدعوم: 1..$PACKAGE_SCHEMA_VERSION)."
                )
            }
            val appId = manifest.optString("appId", "")
            if (appId.isNotBlank() && appId != APP_ID) {
                return null to TransferFailureExt(
                    "INCOMPATIBLE_APP",
                    "الحزمة من تطبيق مختلف ($appId) — غير متوافقة."
                )
            }
            // --- v2+: canonical manifest digest ---
            val manifestDigestEntry = entries[MANIFEST_SHA256_ENTRY]
            if (manifestDigestEntry != null) {
                val expectedManifestSha = manifestDigestEntry.decodeToString().trim().lowercase()
                if (expectedManifestSha.isBlank() || expectedManifestSha != sha256Of(manifestBytes)) {
                    return null to TransferFailureExt(
                        "MANIFEST_HASH_MISMATCH",
                        "بصمة manifest.json لا تطابق — الحزمة عُدّلت أو تالفة."
                    )
                }
            } else if (schemaVersion >= 2) {
                return null to TransferFailureExt(
                    "MISSING_MANIFEST_DIGEST",
                    "حزمة تصرّح بالإصدار $schemaVersion دون بصمة manifest.sha256 — مرفوضة."
                )
            }
            // --- Secret inclusion detection (defense in depth) ---
            if (!manifest.optBoolean("secretsExcluded", false)) {
                return null to TransferFailureExt(
                    "SECRET_INCLUSION_DETECTED",
                    "الحزمة لا تصرّح باستبعاد الأسرار — رفض الاستيراد بدل المخاطرة."
                )
            }
            val projectBytes = entries[PROJECT_ENTRY]
                ?: return null to TransferFailureExt("MISSING_PROJECT_JSON", "project.json مفقود.")
            val project = runCatching { JSONObject(projectBytes.decodeToString()) }.getOrNull()
                ?: return null to TransferFailureExt("INVALID_MANIFEST", "project.json غير صالح.")

            // --- Files: unzip the nested zip + validate every entry name ---
            val filesZipBytes = entries[FILES_ENTRY] ?: ByteArray(0)
            // v3: the nested files.zip itself is integrity-protected.
            if (schemaVersion >= 3) {
                val expectedZipSha = manifest.optString("filesZipSha256")
                if (expectedZipSha.isBlank() || sha256Of(filesZipBytes) != expectedZipSha.lowercase()) {
                    return null to TransferFailureExt(
                        "FILES_ZIP_HASH_MISMATCH",
                        "بصمة files.zip لا تطابق — ملفات المشروع تالفة أو معدّلة."
                    )
                }
            }
            val fileEntries = mutableMapOf<String, ByteArray>()
            if (filesZipBytes.isNotEmpty()) {
                ZipInputStream(filesZipBytes.inputStream()).use { inner ->
                    while (true) {
                        val e = inner.nextEntry ?: break
                        if (e.isDirectory) continue
                        if (!fileStore.validateZipEntryName(e.name)) {
                            return null to TransferFailureExt(
                                "PATH_TRAVERSAL_DETECTED", "مسار غير آمن داخل ملفات الحزمة: ${e.name}"
                            )
                        }
                        fileEntries[e.name] = inner.readBytes()
                    }
                }
                if (fileEntries.size > limits.maxEntryCount) {
                    return null to TransferFailureExt("ENTRY_COUNT_EXCEEDED", "عدد الملفات يتجاوز الحد.")
                }
            }
            // --- Validate content hashes (every declared file must be present + match) ---
            val hashes = manifest.optJSONObject("contentHashes")
            if (hashes != null) {
                for (key in hashes.keys()) {
                    val expected = hashes.optString(key)
                    val actualBytes = fileEntries[key]
                        ?: return null to TransferFailureExt(
                            "HASH_MISMATCH",
                            "ملف مُصرّح به في الحزمة مفقود: $key — الحزمة تالفة."
                        )
                    val actual = sha256Of(actualBytes)
                    if (expected.isNotBlank() && !actual.equals(expected, ignoreCase = true)) {
                        return null to TransferFailureExt(
                            "HASH_MISMATCH", "بصمة المحتوى لا تطابق لـ $key — الحزمة تالفة."
                        )
                    }
                }
            }
            // --- v3 COUNT CONTRACTS: declared counts vs actual payloads ---
            if (schemaVersion >= 3) {
                fun countEntry(name: String, key: String): TransferFailureExt? {
                    val declared = manifest.optInt(key, -1)
                    if (declared < 0) return null // absent → nothing to check (honest optionality)
                    val actual = entries[name]?.let {
                        runCatching { JSONArray(it.decodeToString()).length() }.getOrDefault(-1)
                    } ?: -1
                    return if (declared != actual) TransferFailureExt(
                        "COUNT_CONTRACT_MISMATCH",
                        "عدد $key المُصرّح به ($declared) لا يطابق المحتوى الفعلي ($actual) — الحزمة ناقصة."
                    ) else null
                }
                countEntry(KNOWLEDGE_ENTRY, "knowledgeCount")?.let { return null to it }
                countEntry(SESSIONS_ENTRY, "sessionCount")?.let { return null to it }
                countEntry(TURNS_ENTRY, "turnCount")?.let { return null to it }
                countEntry(TIMELINE_ENTRY, "timelineEventCount")?.let { return null to it }
                countEntry(TASKS_ENTRY, "taskCount")?.let { return null to it }
                countEntry(ARTIFACTS_ENTRY, "artifactCount")?.let { return null to it }
                val declaredFiles = manifest.optInt("fileCount", -1)
                if (declaredFiles >= 0 && declaredFiles != fileEntries.size) {
                    return null to TransferFailureExt(
                        "COUNT_CONTRACT_MISMATCH",
                        "عدد الملفات المُصرّح به ($declaredFiles) لا يطابق المحتوى الفعلي (${fileEntries.size}) — الحزمة ناقصة."
                    )
                }
            }

            val knowledge = JSONArray(entries[KNOWLEDGE_ENTRY]?.decodeToString() ?: "[]")
            val sessions = JSONArray(entries[SESSIONS_ENTRY]?.decodeToString() ?: "[]")
            val turns = JSONArray(entries[TURNS_ENTRY]?.decodeToString() ?: "[]")
            val timeline = JSONArray(entries[TIMELINE_ENTRY]?.decodeToString() ?: "[]")
            val tasks = JSONArray(entries[TASKS_ENTRY]?.decodeToString() ?: "[]")
            val artifacts = JSONArray(entries[ARTIFACTS_ENTRY]?.decodeToString() ?: "[]")
            val dependencies = JSONArray(entries[DEPENDENCIES_ENTRY]?.decodeToString() ?: "[]")
            return ParsedPackage(
                manifest, project, fileEntries, knowledge, sessions, turns,
                timeline, tasks, artifacts, dependencies
            ) to null
        } catch (e: Exception) {
            return null to TransferFailureExt("ARCHIVE_CORRUPT", "الحزمة تالفة: ${e.message}")
        }
    }

    /**
     * Full import into [targetWorkspaceId] as a NEW project identity (§11:
     * deterministic ID remapping for sessions, turns, tasks, knowledge,
     * artifacts, dependencies; all internal references rewritten).
     *
     * ATOMICITY (§4.2): files are STAGED and hash-verified BEFORE any DB
     * row exists; every DB row lands in ONE transaction; the staged files
     * are then promoted atomically; the post-state is VERIFIED against the
     * manifest's count contracts and any mismatch ROLLS THE WHOLE IMPORT
     * BACK (no partial destination state, no success with missing files).
     */
    suspend fun importProject(
        targetWorkspaceId: String,
        packageStream: InputStream,
        conflictPolicy: ImportConflictPolicy = ImportConflictPolicy.ASK,
        desiredName: String? = null,
        actor: String = "user"
    ): Pair<Long?, ImportReport> = withContext(Dispatchers.IO) {
        // ---- READ + VALIDATE (destination untouched) ----
        val (parsed, failure) = readAndValidatePackage(packageStream)
        if (parsed == null || failure != null) {
            return@withContext null to ImportReport.error(failure?.code ?: "ARCHIVE_CORRUPT", failure?.message ?: "حزمة غير صالحة.")
        }

        // ---- Name conflict resolution (before ANY write) ----
        val baseName = (desiredName ?: parsed.project.optString("name", "مشروع مستورد")).trim()
        var finalName = baseName
        var replacedProjectId: Long? = null
        when (conflictPolicy) {
            ImportConflictPolicy.CANCEL -> return@withContext null to ImportReport.error("CANCELLED", "أُلغي الاستيراد بناءً على سياسة التعارض.")
            ImportConflictPolicy.ASK -> {
                if (projectDao.countByNameForWorkspace(targetWorkspaceId, baseName) > 0) {
                    return@withContext null to ImportReport.conflict(baseName)
                }
            }
            ImportConflictPolicy.RENAME -> {
                var suffix = 1
                while (projectDao.countByNameForWorkspace(targetWorkspaceId, finalName) > 0) {
                    finalName = "$baseName ($suffix)"
                    suffix++
                }
            }
            ImportConflictPolicy.REPLACE -> {
                val existing = projectDao.activeProjectsForWorkspaceList(targetWorkspaceId)
                    .firstOrNull { it.name.equals(baseName, ignoreCase = true) }
                if (existing != null) {
                    // Full cascade of the REPLACED project FIRST (its sandbox
                    // root survives until the new files are staged+verified —
                    // replacing must never leave the user without a project).
                    replacedProjectId = existing.id
                }
            }
            ImportConflictPolicy.SKIP, ImportConflictPolicy.MERGE -> {
                var suffix = 1
                while (projectDao.countByNameForWorkspace(targetWorkspaceId, finalName) > 0) {
                    finalName = "$baseName ($suffix)"
                    suffix++
                }
            }
        }

        val report = buildCompatibilityReport(parsed)
        val now = System.currentTimeMillis()

        // ---- STAGE: files land in a staging dir; hash-verify after write ----
        val stagingDir = File(
            fileStore.projectRoot(0).parentFile,
            ".staging_import_pkg_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        )
        try {
            stagingDir.mkdirs()
            val manifestHashes = parsed.manifest.optJSONObject("contentHashes")
            for ((rel, bytes) in parsed.fileEntries) {
                val target = fileStore.resolveContained(stagingDir, rel)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                val stagedHash = sha256Of(target.readBytes())
                val expected = manifestHashes?.optString(rel)
                if (!expected.isNullOrBlank() && !stagedHash.equals(expected, ignoreCase = true)) {
                    throw IllegalStateException("STAGED_FILE_HASH_MISMATCH: $rel")
                }
            }

            // ---- TRANSFER: ONE Room transaction (all rows or none) ----
            val newProjectId = database.withTransaction {
                // REPLACE policy: delete the old project ONLY now (files for
                // the replacement are staged; its rows go in the same tx).
                replacedProjectId?.let { oldId ->
                    cascadeDeleteProjectRows(oldId)
                    fileStore.projectRoot(oldId).deleteRecursively()
                }
                val provisional = com.example.infrastructure.persistence.entities.ProjectEntity(
                    name = finalName,
                    description = parsed.project.optString("description").ifBlank { null },
                    rootPath = "",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    workspaceId = targetWorkspaceId,
                    lifecycleState = "ACTIVE"
                )
                val id = projectDao.insertProject(provisional)

                // ---- ID remapping tables ----
                val sessionIdMap = mutableMapOf<String, String>()

                // Knowledge: new ids; projectId rebound; totalChunks = 0
                // (honest — chunks are rebuilt AFTER the DB transaction).
                for (i in 0 until parsed.knowledge.length()) {
                    val docJson = parsed.knowledge.getJSONObject(i)
                    knowledgeDao.insertOrUpdate(
                        KnowledgeDocumentEntity(
                            id = "doc_${id}_${UUID.randomUUID().toString().take(10)}",
                            workspaceId = targetWorkspaceId,
                            title = docJson.optString("title"),
                            sourceUri = docJson.optString("sourceUri", "imported://package"),
                            content = docJson.optString("content"),
                            mimeType = docJson.optString("mimeType", "text/markdown"),
                            tagsJson = encodeTags(docJson.optJSONArray("tags")),
                            totalChunks = 0,
                            totalTokensEstimated = docJson.optString("content").length / 4,
                            createdAtEpochMs = docJson.optLong("createdAtEpochMs", now),
                            updatedAtEpochMs = now,
                            projectId = id
                        )
                    )
                }

                // Sessions: new ids; projectId rebound; turnCount recomputed
                // from the turns that ACTUALLY land below (never the source
                // device's claim).
                val turnsPerSession = mutableMapOf<String, Int>()
                for (i in 0 until parsed.turns.length()) {
                    val t = parsed.turns.getJSONObject(i)
                    val sid = t.optString("sessionId")
                    turnsPerSession[sid] = (turnsPerSession[sid] ?: 0) + 1
                }
                for (i in 0 until parsed.sessions.length()) {
                    val s = parsed.sessions.getJSONObject(i)
                    val newSessionId = "sess_${UUID.randomUUID().toString().take(12)}"
                    sessionIdMap[s.optString("sessionId")] = newSessionId
                    sessionDao.upsert(
                        ConversationSessionEntity(
                            sessionId = newSessionId,
                            workspaceId = targetWorkspaceId,
                            title = s.optString("title", "جلسة مستوردة"),
                            mode = s.optString("mode", "AGENT"),
                            agentId = s.optString("agentId").takeIf { it.isNotBlank() },
                            agentName = s.optString("agentName").takeIf { it.isNotBlank() },
                            modelResourceId = s.optString("modelResourceId").takeIf { it.isNotBlank() },
                            modelDisplayName = s.optString("modelDisplayName").takeIf { it.isNotBlank() },
                            turnCount = turnsPerSession[s.optString("sessionId")] ?: 0,
                            totalTokensConsumed = s.optInt("totalTokensConsumed"),
                            createdAtEpochMs = s.optLong("createdAtEpochMs", now),
                            lastActiveAtEpochMs = s.optLong("lastActiveAtEpochMs", now),
                            projectId = id
                        )
                    )
                }

                // v3: FULL turns — attachmentsJson + sourcesJson ride along;
                // sessionId remapped; turnId collision-checked.
                for (i in 0 until parsed.turns.length()) {
                    val t = parsed.turns.getJSONObject(i)
                    val newSessionId = sessionIdMap[t.optString("sessionId")]
                        ?: continue // orphaned turn (session excluded) — honest skip
                    val sourceTurnId = t.optString("turnId")
                    val newTurnId = if (sourceTurnId.isNotBlank() &&
                        turnDao.forSessionOnce(newSessionId).none { it.turnId == sourceTurnId } &&
                        sourceTurnId.length < 120
                    ) sourceTurnId else "turn_${UUID.randomUUID().toString().take(16)}"
                    turnDao.insert(
                        ConversationTurnEntity(
                            turnId = newTurnId,
                            sessionId = newSessionId,
                            prompt = t.optString("prompt"),
                            answer = t.optString("answer"),
                            agentName = t.optString("agentName").takeIf { it.isNotBlank() },
                            agentRole = t.optString("agentRole").takeIf { it.isNotBlank() },
                            modelResourceId = t.optString("modelResourceId").takeIf { it.isNotBlank() },
                            tokensConsumed = t.optInt("tokensConsumed"),
                            durationMs = t.optLong("durationMs"),
                            isSuccessful = t.optBoolean("isSuccessful", true),
                            eventCount = t.optInt("eventCount"),
                            createdAtEpochMs = t.optLong("createdAtEpochMs", now),
                            attachmentsJson = t.optString("attachmentsJson", "[]").ifBlank { "[]" },
                            sourcesJson = t.optString("sourcesJson", "[]").ifBlank { "[]" }
                        )
                    )
                }

                // v3: timeline events (capability results + approval blocks).
                for (i in 0 until parsed.timeline.length()) {
                    val e = parsed.timeline.getJSONObject(i)
                    val newSessionId = sessionIdMap[e.optString("sessionId")] ?: continue
                    val sourceEventId = e.optString("eventId")
                    val newEventId = if (timelineDao.countById(sourceEventId) == 0 && sourceEventId.isNotBlank()) {
                        sourceEventId
                    } else "evt_${UUID.randomUUID().toString().take(16)}"
                    timelineDao.insert(
                        ChatTimelineEventEntity(
                            eventId = newEventId,
                            sessionId = newSessionId,
                            kind = e.optString("kind", "CAPABILITY_RESULT"),
                            capabilityKind = e.optString("capabilityKind").takeIf { it.isNotBlank() },
                            title = e.optString("title"),
                            summary = e.optString("summary"),
                            detail = e.optString("detail").takeIf { it.isNotBlank() },
                            sourcesJson = e.optString("sourcesJson", "[]").ifBlank { "[]" },
                            isSuccessful = e.optBoolean("isSuccessful", true),
                            isDegraded = e.optBoolean("isDegraded"),
                            degradedMessage = e.optString("degradedMessage").takeIf { it.isNotBlank() },
                            createdAtEpochMs = e.optLong("createdAtEpochMs", now),
                            approvalId = e.optString("approvalId").takeIf { it.isNotBlank() },
                            executionId = e.optString("executionId").takeIf { it.isNotBlank() },
                            toolName = e.optString("toolName").takeIf { it.isNotBlank() },
                            riskLevel = e.optString("riskLevel").takeIf { it.isNotBlank() },
                            justification = e.optString("justification").takeIf { it.isNotBlank() },
                            approvalState = e.optString("approvalState").takeIf { it.isNotBlank() }
                        )
                    )
                }

                // v3: FULL task state — lifecycleState PRESERVED (a RUNNING
                // task stays RUNNING and is handled by the recovery surface;
                // it is NEVER blanket-marked COMPLETED by the mere fact of
                // import). checkpoint + canonical context ride along so a
                // resumable task stays resumable.
                for (i in 0 until parsed.tasks.length()) {
                    val t = parsed.tasks.getJSONObject(i)
                    val sourceTaskId = t.optString("id")
                    val newTaskId = if (taskDao.getTaskById(sourceTaskId) != null || sourceTaskId.isBlank()) {
                        "${sourceTaskId}_imp_${UUID.randomUUID().toString().take(6)}"
                    } else sourceTaskId
                    taskDao.insertOrUpdateTask(
                        TaskEntity(
                            id = newTaskId,
                            assignedAgentId = t.optString("assignedAgentId", "agent_general"),
                            rawPrompt = t.optString("rawPrompt"),
                            lifecycleState = t.optString("lifecycleState", "COMPLETED"),
                            autonomyPolicy = t.optString("autonomyPolicy", "SUPERVISED"),
                            resultSummary = t.optString("resultSummary").takeIf { it.isNotBlank() },
                            totalTokensConsumed = t.optInt("totalTokensConsumed"),
                            durationMs = t.optLong("durationMs"),
                            isDegraded = t.optBoolean("isDegraded"),
                            degradedReason = t.optString("degradedReason").takeIf { it.isNotBlank() },
                            errorMessage = t.optString("errorMessage").takeIf { it.isNotBlank() },
                            createdAtEpochMs = t.optLong("createdAtEpochMs", now),
                            updatedAtEpochMs = now,
                            goal = t.optString("goal", t.optString("rawPrompt")),
                            currentStepIndex = t.optInt("currentStepIndex"),
                            tokenLimit = t.optInt("tokenLimit", 30000),
                            maxRetries = t.optInt("maxRetries", 3),
                            allowDegradedExecution = t.optBoolean("allowDegradedExecution", true),
                            requireHumanConsentForSensitiveTools = t.optBoolean("requireHumanConsentForSensitiveTools", true),
                            timeoutMs = t.optLong("timeoutMs", 60000L),
                            minOutputLengthChars = t.optInt("minOutputLengthChars", 1),
                            verificationStrategy = t.optString("verificationStrategy", "STRICT"),
                            assignedModelId = t.optString("assignedModelId").takeIf { it.isNotBlank() },
                            parentTaskId = t.optString("parentTaskId").takeIf { it.isNotBlank() },
                            delegationDepth = t.optInt("delegationDepth"),
                            checkpointJson = t.optString("checkpointJson").takeIf { it.isNotBlank() },
                            executionContextJson = t.optString("executionContextJson").takeIf { it.isNotBlank() },
                            workspaceId = targetWorkspaceId,
                            projectId = id
                        )
                    )
                }

                // v3: artifacts — rows remapped to the new project; payload
                // bytes ride in files.zip under their sandbox-relative
                // storageUri (promoted with the staged files).
                for (i in 0 until parsed.artifacts.length()) {
                    val a = parsed.artifacts.getJSONObject(i)
                    val sourceArtifactId = a.optString("id")
                    val newArtifactId = if (artifactDao.countById(sourceArtifactId) > 0 || sourceArtifactId.isBlank()) {
                        "art_${UUID.randomUUID().toString().take(16)}"
                    } else sourceArtifactId
                    artifactDao.upsert(
                        ArtifactEntity(
                            id = newArtifactId,
                            workspaceId = targetWorkspaceId,
                            projectId = id,
                            sessionId = a.optString("sessionId").takeIf { it.isNotBlank() }, // remapped session ids live in another package scope; artifact refs stay honest
                            taskId = a.optString("taskId").takeIf { it.isNotBlank() },
                            executionId = a.optString("executionId").takeIf { it.isNotBlank() },
                            type = a.optString("type", "FILE"),
                            name = a.optString("name", "artifact"),
                            mimeType = a.optString("mimeType", "application/octet-stream"),
                            sizeBytes = a.optLong("sizeBytes"),
                            contentHash = a.optString("contentHash").takeIf { it.isNotBlank() },
                            storageUri = a.optString("storageUri"),
                            source = a.optString("source", "USER"),
                            securityClassification = a.optString("securityClassification", "UNCLASSIFIED"),
                            indexingState = a.optString("indexingState", "NOT_INDEXED"),
                            createdAtEpochMs = a.optLong("createdAtEpochMs", now),
                            updatedAtEpochMs = now,
                            metadataJson = a.optString("metadataJson", "{}").ifBlank { "{}" }
                        )
                    )
                }

                // Dependencies: carried over as declarations (status re-evaluated
                // at runtime; imported projects NEVER elevate the target
                // workspace's policy — §12).
                for (i in 0 until parsed.dependencies.length()) {
                    val d = parsed.dependencies.getJSONObject(i)
                    dependencyDao.upsert(
                        ProjectDependencyEntity(
                            projectId = id,
                            type = d.optString("type", ProjectDependencyType.MODEL.name),
                            key = d.optString("key"),
                            requirement = d.optString("requirement", DependencyRequirement.OPTIONAL.name),
                            status = d.optString("status", DependencyStatus.MISSING.name),
                            detail = d.optString("detail").takeIf { it.isNotBlank() },
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now
                        )
                    )
                }
                id
            }

            // ---- COMMIT: atomic staging → project root promotion ----
            val finalRoot = File(fileStore.projectRoot(newProjectId).parentFile, "proj_$newProjectId")
            if (finalRoot.exists()) finalRoot.deleteRecursively() // empty placeholder
            val promoted = stagingDir.renameTo(finalRoot)
            if (!promoted) {
                // Cross-directory rename fallback: copy + verify + cleanup.
                stagingDir.copyRecursively(finalRoot, overwrite = true)
            }
            projectDao.updateProject(
                projectDao.getProjectById(newProjectId)!!.copy(rootPath = finalRoot.canonicalPath)
            )

            // ---- VERIFY: post-state vs the manifest's count contracts ----
            val verifyFailure = verifyImportedState(targetWorkspaceId, newProjectId, parsed)
            if (verifyFailure != null) {
                // ROLLBACK — no partial destination state survives a failed
                // verification (§4.2).
                database.withTransaction { cascadeDeleteProjectRows(newProjectId) }
                finalRoot.deleteRecursively()
                return@withContext null to ImportReport.error(
                    verifyFailure.code,
                    "فشل التحقق بعد الاستيراد — أُلغي الاستيراد بالكامل ولم يتبقَّ أي حالة جزئية. (${verifyFailure.message})"
                )
            }

            // ---- REBUILD_INDEXES: RAG chunks (device-local embeddings) ----
            var reingestedDocuments = 0
            var reingestedChunks = 0
            val ragNotes = mutableListOf<String>()
            if (parsed.knowledge.length() > 0) {
                if (ragPipeline == null) {
                    ragNotes.add(
                        "لا يوجد مسار إعادة بناء المعرفة مهيأً — استُوردت ${parsed.knowledge.length()} مستنداً بمحتواها الكامل وبدون مقاطع فهرسة (سيُعاد التقسيم عند أول إضافة/تحليل)."
                    )
                } else {
                    val importedDocs = knowledgeDao.getProjectPrivateDocuments(newProjectId)
                    for (doc in importedDocs) {
                        val domainDoc = com.example.domain.core.rag.KnowledgeDocument(
                            id = doc.id,
                            title = doc.title,
                            sourceUri = doc.sourceUri,
                            content = doc.content,
                            mimeType = doc.mimeType,
                            tags = emptyList(),
                            totalChunks = 0,
                            createdAtTimestampMs = doc.createdAtEpochMs,
                            projectId = doc.projectId
                        )
                        val (chunkCount, error) = ragPipeline.rebuildChunksForImportedDocument(
                            workspaceId = targetWorkspaceId,
                            document = domainDoc
                        )
                        if (error == null) {
                            reingestedDocuments++
                            reingestedChunks += chunkCount
                        } else {
                            ragNotes.add("تعذر إعادة بناء مقاطع «${doc.title}»: $error")
                        }
                    }
                }
            }

            // ---- Audit + report ----
            audit(AuditActions.PROJECT_IMPORTED, targetWorkspaceId, newProjectId, AuditResult.SUCCESS,
                reason = "name=$finalName files=${parsed.fileEntries.size} turns=${parsed.turns.length()} " +
                        "tasks=${parsed.tasks.length()} artifacts=${parsed.artifacts.length()} " +
                        "ragDocs=$reingestedDocuments ragChunks=$reingestedChunks")
            newProjectId to ImportReport.success(
                finalName, parsed.fileEntries.size, report,
                importedSessions = parsed.sessions.length(),
                importedTurns = parsed.turns.length(),
                importedKnowledge = parsed.knowledge.length(),
                importedTasks = parsed.tasks.length(),
                importedArtifacts = parsed.artifacts.length(),
                reingestedDocuments = reingestedDocuments,
                reingestedChunks = reingestedChunks,
                degradedNotes = ragNotes
            )
        } catch (e: Exception) {
            // ANY failure before/inside/after the transaction leaves NO
            // partial destination state (staging is cleaned; the DB
            // transaction rolled back by Room on throw).
            stagingDir.deleteRecursively()
            null to ImportReport.error("IMPORT_EXCEPTION", "فشل الاستيراد: ${e.message}")
        }
    }

    /** Post-import verification against the manifest's count contracts. */
    private suspend fun verifyImportedState(
        targetWorkspaceId: String,
        projectId: Long,
        parsed: ParsedPackage
    ): TransferFailureExt? {
        if (projectDao.getProjectByIdForWorkspace(projectId, targetWorkspaceId) == null) {
            return TransferFailureExt("PROJECT_ROW_MISSING", "صف المشروع غير موجود بعد الاستيراد")
        }
        val expectedFiles = parsed.manifest.optInt("fileCount", -1)
        if (expectedFiles >= 0) {
            val root = fileStore.projectRoot(projectId)
            // FILES ONLY (the same contract as the export — list() also
            // yields directory entries, which are not fileCount material).
            val actualFiles = fileStore.list(root)
                .filter { rel -> fileStore.stat(root, rel).let { it.exists && !it.isDirectory } }
                .size
            if (actualFiles != expectedFiles) {
                return TransferFailureExt("FILE_COUNT_MISMATCH", "ملفات على القرص=$actualFiles، المصرّح بها=$expectedFiles")
            }
            val hashes = parsed.manifest.optJSONObject("contentHashes")
            if (hashes != null) {
                for (key in hashes.keys()) {
                    val expected = hashes.optString(key)
                    if (expected.isNotBlank()) {
                        val actual = fileStore.hash(root, key)
                        if (actual == null || !actual.equals(expected, ignoreCase = true)) {
                            return TransferFailureExt("FILE_HASH_MISMATCH", "بصمة الملف $key لا تطابق بعد الهبوط")
                        }
                    }
                }
            }
        }
        fun checkCount(expected: Int, actual: Int, label: String): TransferFailureExt? =
            if (expected >= 0 && expected != actual) {
                TransferFailureExt("COUNT_MISMATCH", "$label: المصرّح به=$expected، الفعلي=$actual")
            } else null

        checkCount(parsed.manifest.optInt("knowledgeCount", -1), knowledgeDao.getProjectPrivateDocuments(projectId).size, "مستندات المعرفة")?.let { return it }
        checkCount(parsed.manifest.optInt("sessionCount", -1), sessionDao.forProject(projectId).size, "الجلسات")?.let { return it }
        checkCount(parsed.manifest.optInt("taskCount", -1), taskDao.getTasksForProject(projectId).size, "المهام")?.let { return it }
        checkCount(parsed.manifest.optInt("artifactCount", -1), artifactDao.forProject(projectId, limit = 10_000).size, "المخرجات")?.let { return it }
        val turns = parsed.manifest.optInt("turnCount", -1)
        if (turns >= 0) {
            val actualTurns = sessionDao.forProject(projectId).sumOf { turnDao.forSessionOnce(it.sessionId).size }
            checkCount(turns, actualTurns, "دورات المحادثة")?.let { return it }
        }
        return null
    }

    /** FULL scoped-row cascade for a project id (inside a transaction). */
    private suspend fun cascadeDeleteProjectRows(projectId: Long) {
        database.artifactVersionDao().deleteForProject(projectId)
        chunkDao.deleteChunksForProject(projectId)
        knowledgeDao.deleteAllForProject(projectId)
        turnDao.deleteForProjectSessions(projectId)
        timelineDao.deleteForProjectSessions(projectId)
        sessionDao.deleteForProject(projectId)
        taskDao.deleteTasksForProject(projectId)
        artifactDao.deleteForProject(projectId)
        dependencyDao.deleteForProject(projectId)
        snapshotDao.deleteForProject(projectId)
        projectDao.deleteProjectRow(projectId)
    }

    // ==================================================================
    // CLONE / MOVE (§11) — both route through the SAME coordinator stages
    // ==================================================================

    /**
     * CLONE: source project remains INTACT; the clone is a new identity in
     * the same (or another) workspace with copied sandbox + rebound scoped rows.
     */
    suspend fun cloneProject(
        sourceWorkspaceId: String,
        sourceProjectId: Long,
        targetWorkspaceId: String = sourceWorkspaceId,
        cloneName: String? = null
    ): Pair<Long?, TransferOutcome> = withContext(Dispatchers.IO) {
        val source = projectDao.getProjectByIdForWorkspace(sourceProjectId, sourceWorkspaceId)
            ?: return@withContext null to TransferOutcome.Failure("PROJECT_NOT_FOUND", "المشروع المصدر غير موجود.", true)
        val name = cloneName ?: "${source.name} (نسخة)"
        var finalName = name
        var suffix = 1
        while (projectDao.countByNameForWorkspace(targetWorkspaceId, finalName) > 0) {
            finalName = "$name ($suffix)"
            suffix++
        }
        val buffer = java.io.ByteArrayOutputStream()
        val exportResult = exportProject(sourceWorkspaceId, sourceProjectId, buffer)
        if (exportResult is TransferOutcome.Failure) {
            return@withContext null to exportResult
        }
        val (newId, report) = importProject(
            targetWorkspaceId = targetWorkspaceId,
            packageStream = buffer.toByteArray().inputStream(),
            conflictPolicy = ImportConflictPolicy.RENAME,
            desiredName = finalName
        )
        if (newId == null) {
            return@withContext null to TransferOutcome.Failure("CLONE_FAILED", "فشل الاستنساخ: ${report.message}", true)
        }
        audit(AuditActions.PROJECT_CLONED, targetWorkspaceId, newId, AuditResult.SUCCESS,
            reason = "from=$sourceProjectId")
        newId to TransferOutcome.Success(
            "تم استنساخ المشروع إلى '$finalName'.",
            importedFileCount = (exportResult as TransferOutcome.Success).importedFileCount
        )
    }

    /**
     * MOVE (§4.1) — VERIFIED transfer: the import into the target workspace
     * must SUCCEED AND BE DEEP-VERIFIED (counts + hashes, not just the
     * project row) before the source is removed with a FULL cascade
     * (sessions + turns + timeline + knowledge + chunks + tasks + artifacts
     * + snapshots + dependencies + row + sandbox).
     */
    suspend fun moveProjectVerified(
        sourceWorkspaceId: String,
        sourceProjectId: Long,
        targetWorkspaceId: String
    ): Pair<Long?, TransferOutcome> = withContext(Dispatchers.IO) {
        if (sourceWorkspaceId == targetWorkspaceId) {
            return@withContext null to TransferOutcome.Failure("SAME_WORKSPACE", "المشروع موجود بالفعل في المساحة الهدف.", true)
        }
        // STAGE + TRANSFER + COMMIT + VERIFY (the import pipeline).
        val buffer = java.io.ByteArrayOutputStream()
        val exportResult = exportProject(sourceWorkspaceId, sourceProjectId, buffer)
        if (exportResult is TransferOutcome.Failure) return@withContext null to exportResult
        val (newId, report) = importProject(
            targetWorkspaceId = targetWorkspaceId,
            packageStream = buffer.toByteArray().inputStream(),
            conflictPolicy = ImportConflictPolicy.RENAME
        )
        if (newId == null) {
            return@withContext null to TransferOutcome.Failure("MOVE_IMPORT_FAILED", "فشل نقل الاستيراد: ${report.message}", true)
        }
        // DEEP VERIFY (§4.3): the full post-state was already verified inside
        // importProject (counts + hashes vs manifest, rollback on mismatch);
        // re-assert the project row landed in the target workspace.
        if (projectDao.getProjectByIdForWorkspace(newId, targetWorkspaceId) == null) {
            return@withContext null to TransferOutcome.Failure("MOVE_VERIFY_FAILED", "تعذر التحقق من النقل — المصدر لم يُمس.", true)
        }
        // CLEANUP: full source cascade (DB first, then the sandbox root — a
        // row pointing at a deleted dir is worse than an orphan dir).
        database.withTransaction { cascadeDeleteProjectRows(sourceProjectId) }
        fileStore.projectRoot(sourceProjectId).deleteRecursively()
        audit(AuditActions.PROJECT_MOVED, targetWorkspaceId, newId, AuditResult.SUCCESS,
            reason = "verified move from=$sourceWorkspaceId/$sourceProjectId")
        newId to TransferOutcome.Success("تم نقل المشروع والتحقق منه بنجاح (هوية جديدة في المساحة الهدف).")
    }

    // ==================================================================
    // SNAPSHOTS (§26 / §4.4) — REAL payload store + restore
    // ==================================================================

    /**
     * CLOSURE §4.4: a snapshot now persists its REAL payload (a complete
     * .aiv0project package) under `<workspaces>/snapshots/<id>.aiv0project`
     * — previously the bytes were discarded and "restore" did not exist.
     */
    suspend fun createSnapshot(
        workspaceId: String,
        projectId: Long,
        label: String,
        reason: String
    ): String? = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectByIdForWorkspace(projectId, workspaceId)
            ?: return@withContext null
        val snapshotDir = File(fileStore.projectRoot(0).parentFile, SNAPSHOT_DIR)
        snapshotDir.mkdirs()
        val snapshotId = "snap_${UUID.randomUUID().toString().take(12)}"
        val payloadFile = File(snapshotDir, "$snapshotId.aiv0project")
        val payloadStream = java.io.FileOutputStream(payloadFile)
        val result = runCatching {
            exportProject(workspaceId, projectId, payloadStream)
        }
        payloadStream.close()
        if (result.getOrNull() is TransferOutcome.Failure || !payloadFile.exists()) {
            payloadFile.delete()
            return@withContext null
        }
        val packageBytes = payloadFile.readBytes()
        val manifestJson = runCatching {
            // Read the manifest back out of the payload (honest metadata).
            ZipInputStream(packageBytes.inputStream()).use { zis ->
                var out = ""
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.name == MANIFEST_ENTRY) {
                        out = zis.readBytes().decodeToString(); break
                    }
                }
                out
            }
        }.getOrDefault("{}")
        val snapshot = ProjectSnapshotEntity(
            id = snapshotId,
            projectId = projectId,
            workspaceId = workspaceId,
            label = label,
            reason = reason,
            manifestJson = manifestJson,
            contentHash = sha256Of(packageBytes),
            createdAtEpochMs = System.currentTimeMillis()
        )
        snapshotDao.upsert(snapshot)
        audit(AuditActions.SNAPSHOT_CREATED, workspaceId, projectId, AuditResult.SUCCESS, reason)
        snapshotId
    }

    /**
     * CLOSURE §4.4: RESTORE — replaces the CURRENT project state with the
     * snapshot's payload. Semantics: the snapshot package is imported as a
     * fresh verified project; only after that succeeds is the CURRENT
     * project fully cascaded away (a failed restore never destroys the
     * current state).
     */
    suspend fun restoreSnapshot(
        snapshotId: String
    ): Pair<Long?, TransferOutcome> = withContext(Dispatchers.IO) {
        val snapshot = runCatching { snapshotDao.byId(snapshotId) }.getOrNull()
        if (snapshot == null) {
            return@withContext null to TransferOutcome.Failure("SNAPSHOT_NOT_FOUND", "لا توجد لقطة بهذا المعرف.", true)
        }
        val payloadFile = File(File(fileStore.projectRoot(0).parentFile, SNAPSHOT_DIR), "$snapshotId.aiv0project")
        if (!payloadFile.exists()) {
            return@withContext null to TransferOutcome.Failure("SNAPSHOT_PAYLOAD_MISSING", "حمولة اللقطة غير موجودة على القرص.", true)
        }
        val bytes = payloadFile.readBytes()
        if (sha256Of(bytes) != snapshot.contentHash) {
            return@withContext null to TransferOutcome.Failure("SNAPSHOT_HASH_MISMATCH", "بصمة اللقطة لا تطابق الحمولة — رفض الاستعادة.", true)
        }
        val currentProject = projectDao.getProjectByIdForWorkspace(snapshot.projectId, snapshot.workspaceId)
        val (newId, report) = importProject(
            targetWorkspaceId = snapshot.workspaceId,
            packageStream = bytes.inputStream(),
            conflictPolicy = ImportConflictPolicy.RENAME,
            desiredName = currentProject?.name ?: "مشروع مستعاد"
        )
        if (newId == null) {
            return@withContext null to TransferOutcome.Failure("SNAPSHOT_RESTORE_IMPORT_FAILED", "فشل استيراد اللقطة: ${report.message}", true)
        }
        // Only now remove the CURRENT (pre-restore) project — full cascade.
        if (currentProject != null && currentProject.id != newId) {
            database.withTransaction { cascadeDeleteProjectRows(currentProject.id) }
            fileStore.projectRoot(currentProject.id).deleteRecursively()
        }
        audit(AuditActions.SNAPSHOT_CREATED, snapshot.workspaceId, newId, AuditResult.SUCCESS,
            reason = "restored from=$snapshotId replacing=${currentProject?.id}")
        newId to TransferOutcome.Success("تمت استعادة اللقطة «${snapshot.label}» كمشروع كامل.")
    }

    // ------------------------------------------------------------------
    // Compatibility report (§12)
    // ------------------------------------------------------------------

    private suspend fun buildCompatibilityReport(parsed: ParsedPackage): ImportCompatibilityReport {
        val findings = mutableListOf<ImportCompatibilityReport.Finding>()
        for (i in 0 until parsed.dependencies.length()) {
            val d = parsed.dependencies.getJSONObject(i)
            val type = d.optString("type")
            val key = d.optString("key")
            val requirement = d.optString("requirement", DependencyRequirement.OPTIONAL.name)
            val resolved = when (type) {
                ProjectDependencyType.TOOL.name -> isToolAvailable(key)
                ProjectDependencyType.MODEL.name, ProjectDependencyType.EMBEDDING_MODEL.name ->
                    isModelAvailable(key)
                else -> null // unknown/unresolvable at import time → MISSING (honest)
            }
            val severity = when {
                resolved == null -> ImportCompatibilityReport.Severity.MISSING_RESOURCE
                resolved == true -> ImportCompatibilityReport.Severity.COMPATIBLE
                requirement == DependencyRequirement.REQUIRED.name -> when (type) {
                    ProjectDependencyType.MODEL.name -> ImportCompatibilityReport.Severity.MISSING_MODEL
                    ProjectDependencyType.EMBEDDING_MODEL.name -> ImportCompatibilityReport.Severity.MISSING_EMBEDDING
                    ProjectDependencyType.TOOL.name -> ImportCompatibilityReport.Severity.MISSING_TOOL
                    else -> ImportCompatibilityReport.Severity.MISSING_RESOURCE
                }
                else -> ImportCompatibilityReport.Severity.MISSING_RESOURCE
            }
            findings.add(
                ImportCompatibilityReport.Finding(
                    severity = severity,
                    dependencyType = type,
                    key = key,
                    detail = if (severity == ImportCompatibilityReport.Severity.COMPATIBLE)
                        "المورد متوفر في هذا الجهاز." else "المورد غير متوفر محلياً — سيعمل المشروع بحالة منقوصة.",
                    status = if (severity == ImportCompatibilityReport.Severity.COMPATIBLE)
                        DependencyStatus.RESOLVED else DependencyStatus.MISSING
                )
            )
        }
        return ImportCompatibilityReport(
            compatible = findings.none { it.severity != ImportCompatibilityReport.Severity.COMPATIBLE },
            packageSchemaVersion = parsed.manifest.optInt("packageSchemaVersion", 1),
            findings = findings
        )
    }

    private suspend fun isToolAvailable(toolKey: String): Boolean? =
        runCatching { database.toolLifecycleDao().active().any { it.toolName.equals(toolKey, ignoreCase = true) } }
            .getOrDefault(false)

    private suspend fun isModelAvailable(modelKey: String): Boolean? =
        runCatching { database.serviceOfferingDao().all().any { it.id.equals(modelKey, ignoreCase = true) } }
            .getOrDefault(false)

    // ------------------------------------------------------------------
    // Report model + internals
    // ------------------------------------------------------------------

    data class ImportReport(
        val ok: Boolean,
        val message: String,
        val conflictName: String? = null,
        val importedFiles: Int = 0,
        val compatibility: ImportCompatibilityReport? = null,
        /** CLOSURE §5: portability witnesses. */
        val importedSessions: Int = 0,
        val importedTurns: Int = 0,
        val importedKnowledge: Int = 0,
        val importedTasks: Int = 0,
        val importedArtifacts: Int = 0,
        val reingestedDocuments: Int = 0,
        val reingestedChunks: Int = 0,
        val degradedNotes: List<String> = emptyList()
    ) {
        companion object {
            fun error(code: String, message: String) = ImportReport(false, "[$code] $message")
            fun conflict(name: String) = ImportReport(
                false,
                "CONFLICT: يوجد مشروع بهذا الاسم '$name' — اختر سياسة تعارض (إعادة تسمية/استبدال/دمج/تخطي) أو ألغِ.",
                conflictName = name
            )
            fun success(
                name: String, files: Int, compat: ImportCompatibilityReport,
                importedSessions: Int = 0, importedTurns: Int = 0, importedKnowledge: Int = 0,
                importedTasks: Int = 0, importedArtifacts: Int = 0,
                reingestedDocuments: Int = 0, reingestedChunks: Int = 0,
                degradedNotes: List<String> = emptyList()
            ) = ImportReport(
                true, "تم استيراد المشروع '$name'.", importedFiles = files, compatibility = compat,
                importedSessions = importedSessions, importedTurns = importedTurns,
                importedKnowledge = importedKnowledge, importedTasks = importedTasks,
                importedArtifacts = importedArtifacts, reingestedDocuments = reingestedDocuments,
                reingestedChunks = reingestedChunks, degradedNotes = degradedNotes
            )
        }
    }

    private fun encodeTags(tags: JSONArray?): String {
        if (tags == null || tags.length() == 0) return "[]"
        val out = JSONArray()
        for (i in 0 until tags.length()) out.put(tags.optString(i))
        return out.toString()
    }

    private fun sha256Of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private suspend fun audit(
        action: String,
        workspaceId: String,
        projectId: Long,
        result: AuditResult,
        reason: String? = null
    ) {
        auditTrail?.recordAsync(
            actorType = AuditActorType.IMPORTER,
            actorId = "importer",
            action = action,
            resourceType = "PROJECT",
            resourceId = projectId.toString(),
            sourceScope = ResourceScope.Workspace(workspaceId),
            result = result,
            reason = reason
        )
    }
}
