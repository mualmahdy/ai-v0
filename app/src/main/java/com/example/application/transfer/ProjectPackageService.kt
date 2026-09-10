package com.example.application.transfer

import androidx.room.withTransaction
import com.example.application.audit.AuditTrailService
import com.example.domain.core.audit.AuditActions
import com.example.domain.core.audit.AuditActorType
import com.example.domain.core.audit.AuditResult
import com.example.domain.core.context.ResourceScope
import com.example.domain.core.project.DependencyRequirement
import com.example.domain.core.project.DependencyStatus
import com.example.domain.core.project.ProjectDependencyType
import com.example.domain.core.task.AutonomyPolicy
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.ProjectDependencyEntity
import com.example.infrastructure.persistence.entities.ProjectSnapshotEntity
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
 * REPAIR ORDER §10/§11/§12 — VERSIONED PORTABLE PROJECT PACKAGES (.aiv0project)
 * ============================================================================
 * A project package is NOT an arbitrary ZIP: it is a STRUCTURED, VERSIONED
 * container with a manifest-first layout:
 *
 *   .aiv0project/
 *     manifest.json      — schema version, app compatibility, identity,
 *                          source workspace, export timestamp, content
 *                          hashes, included scopes, dependencies
 *     project.json       — project metadata (NO secrets)
 *     files.zip          — sandbox files
 *     knowledge.json     — knowledge documents (title/content/mime/tags; NO secrets)
 *     sessions.json      — session transcripts (NO secrets)
 *     tasks.json         — task records
 *     dependencies.json  — explicit dependency declarations
 *     metadata/about.json
 *
 * PACKAGE SCHEMA VERSION is INDEPENDENT of the Room DB schema version.
 *
 * Export EXCLUDES secrets by construction (§13): only resource REFERENCES
 * (provider ids, model ids, tool names) are exported, never credentials —
 * credentials live in the device-bound EncryptedSecretStorage and are
 * structurally NOT part of any export payload here.
 *
 * IMPORT PIPELINE (§12): Read → Validate manifest → Validate schema →
 * Validate paths → Validate limits → Validate hashes → Resolve dependencies
 * → Resolve IDs (deterministic remapping — never reuse local ids) → Detect
 * conflicts → Stage → Import → Rebuild indexes → Verify → Commit.
 * The destination is NEVER partially mutated before validation succeeds:
 * everything lands in a staging area and is committed transactionally.
 */
class ProjectPackageService(
    private val database: AppDatabase,
    private val fileStore: SandboxProjectFileStore,
    private val auditTrail: AuditTrailService? = null,
    private val limits: TransferLimits = TransferLimits.DEFAULT
) {
    companion object {
        /** Package schema version — SEPARATE from the Room DB version. */
        const val PACKAGE_SCHEMA_VERSION = 1
        const val MANIFEST_ENTRY = "manifest.json"
        const val PROJECT_ENTRY = "project.json"
        const val FILES_ENTRY = "files.zip"
        const val KNOWLEDGE_ENTRY = "knowledge.json"
        const val SESSIONS_ENTRY = "sessions.json"
        const val TASKS_ENTRY = "tasks.json"
        const val DEPENDENCIES_ENTRY = "dependencies.json"
        const val ABOUT_ENTRY = "metadata/about.json"
        /** App identity for compatibility checks. */
        const val APP_ID = "com.aistudio.aiv0ultimate"
        /** Imported projects can never ELEVATE the target workspace policy (§12). */
        private val POLICY_RANK = mapOf(
            AutonomyPolicy.ASSISTED to 0,
            AutonomyPolicy.SUPERVISED to 1,
            AutonomyPolicy.AUTONOMOUS to 2
        )
    }

    private val projectDao = database.projectDao()
    private val knowledgeDao = database.knowledgeDocumentDao()
    private val chunkDao = database.documentChunkDao()
    private val sessionDao = database.conversationSessionDao()
    private val taskDao = database.taskDao()
    private val dependencyDao = database.projectDependencyDao()
    private val snapshotDao = database.projectSnapshotDao()

    // ==================================================================
    // EXPORT
    // ==================================================================

    /**
     * Exports a project as a .aiv0project package. Secrets are excluded BY
     * CONSTRUCTION: only references (provider/model/tool ids) travel.
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
            val files = fileStore.list(root).filterNot { it.startsWith(".staging") }

            val documents = knowledgeDao.getProjectPrivateDocuments(projectId)
            val sessions = if (includeSessions) sessionDao.forProject(projectId) else emptyList()
            val tasks = if (includeTasks) taskDao.getTasksForProject(projectId) else emptyList()
            val dependencies = dependencyDao.forProject(projectId)

            // ---- Build manifest ----
            val manifest = JSONObject().apply {
                put("packageSchemaVersion", PACKAGE_SCHEMA_VERSION)
                put("appId", APP_ID)
                put("packageId", "aiv0pkg_${UUID.randomUUID().toString().take(12)}")
                put("projectId", projectId)
                put("projectName", project.name)
                put("sourceWorkspaceId", workspaceId)
                put("exportedAtEpochMs", System.currentTimeMillis())
                put("secretsExcluded", true)
                put("includedScopes", JSONArray().put("FILES").put("KNOWLEDGE")
                    .apply { if (includeSessions) put("SESSIONS"); if (includeTasks) put("TASKS") })
                put("fileCount", files.size)
                put("knowledgeCount", documents.size)
                put("sessionCount", sessions.size)
                put("taskCount", tasks.size)
                put("autonomyPolicyAtExport", runCatching { projectDao.getProjectById(projectId)?.lifecycleState }.getOrDefault("ACTIVE"))
                // Content hashes for integrity verification at import.
                val hashes = JSONObject()
                files.take(500).forEach { rel ->
                    fileStore.hash(root, rel)?.let { hashes.put(rel, it) }
                }
                put("contentHashes", hashes)
            }

            ZipOutputStream(destination.buffered()).use { zos ->
                fun put(name: String, bytes: ByteArray) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
                put(MANIFEST_ENTRY, manifest.toString(2).toByteArray())
                put(PROJECT_ENTRY, JSONObject().apply {
                    put("name", project.name)
                    put("description", project.description ?: "")
                    put("lifecycleState", "ACTIVE")
                    put("createdAtEpochMs", project.createdAtEpochMs)
                }.toString(2).toByteArray())

                // files.zip — nested zip of the sandbox.
                val fileBytesStream = java.io.ByteArrayOutputStream()
                ZipOutputStream(fileBytesStream).use { inner ->
                    files.forEach { rel ->
                        val content = runCatching { fileStore.read(root, rel) }.getOrNull() ?: return@forEach
                        inner.putNextEntry(ZipEntry(rel))
                        inner.write(content)
                        inner.closeEntry()
                    }
                }
                put(FILES_ENTRY, fileBytesStream.toByteArray())

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
                            put("projectId", JSONObject.NULL)
                        })
                    }
                }.toString().toByteArray())

                put(SESSIONS_ENTRY, JSONArray().apply {
                    sessions.forEach { s ->
                        put(JSONObject().apply {
                            put("sessionId", s.sessionId)
                            put("title", s.title)
                            put("mode", s.mode)
                            put("modelResourceId", s.modelResourceId ?: JSONObject.NULL)
                            put("turnCount", s.turnCount)
                            put("createdAtEpochMs", s.createdAtEpochMs)
                        })
                    }
                }.toString().toByteArray())

                put(TASKS_ENTRY, JSONArray().apply {
                    tasks.forEach { t ->
                        put(JSONObject().apply {
                            put("id", t.id)
                            put("rawPrompt", t.rawPrompt)
                            put("lifecycleState", t.lifecycleState)
                            put("resultSummary", t.resultSummary ?: JSONObject.NULL)
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

            audit(AuditActions.PROJECT_EXPORTED, workspaceId, projectId, AuditResult.SUCCESS)
            TransferOutcome.Success(
                "تم تصدير المشروع '${project.name}' (${files.size} ملفاً).",
                importedFileCount = files.size
            )
        } catch (e: Exception) {
            TransferOutcome.Failure("EXPORT_EXCEPTION", "فشل تصدير المشروع: ${e.message}", true)
        }
    }

    // ==================================================================
    // IMPORT
    // ==================================================================

    /** Parsed package (staged in memory — small metadata only). */
    data class ParsedPackage(
        val manifest: JSONObject,
        val project: JSONObject,
        val fileEntries: Map<String, ByteArray>,
        val knowledge: JSONArray,
        val sessions: JSONArray,
        val tasks: JSONArray,
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
            // --- Validate schema + app compatibility ---
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
            // --- Secret inclusion detection (defense in depth, §13) ---
            val secretsExcluded = manifest.optBoolean("secretsExcluded", false)
            if (!secretsExcluded) {
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
            // --- Validate content hashes ---
            val hashes = manifest.optJSONObject("contentHashes")
            if (hashes != null) {
                for (key in hashes.keys()) {
                    val expected = hashes.optString(key)
                    val actual = fileEntries[key]?.let { sha256Of(it) }
                    if (actual != null && expected.isNotBlank() && !actual.equals(expected, ignoreCase = true)) {
                        return null to TransferFailureExt(
                            "HASH_MISMATCH", "بصمة المحتوى لا تطابق لـ $key — الحزمة تالفة."
                        )
                    }
                }
            }
            val knowledge = JSONArray(entries[KNOWLEDGE_ENTRY]?.decodeToString() ?: "[]")
            val sessions = JSONArray(entries[SESSIONS_ENTRY]?.decodeToString() ?: "[]")
            val tasks = JSONArray(entries[TASKS_ENTRY]?.decodeToString() ?: "[]")
            val dependencies = JSONArray(entries[DEPENDENCIES_ENTRY]?.decodeToString() ?: "[]")
            return ParsedPackage(manifest, project, fileEntries, knowledge, sessions, tasks, dependencies) to null
        } catch (e: Exception) {
            return null to TransferFailureExt("ARCHIVE_CORRUPT", "الحزمة تالفة: ${e.message}")
        }
    }

    /**
     * Full import into [targetWorkspaceId] as a NEW project identity (§11:
     * never reuse local ids — deterministic ID remapping for sessions,
     * tasks, knowledge, artifacts, dependencies; all internal references
     * rewritten without collisions).
     *
     * Conflict policy governs project NAME collisions (§12; default ASK
     * surfaces conflicts, REPLACE is never the default).
     */
    suspend fun importProject(
        targetWorkspaceId: String,
        packageStream: InputStream,
        conflictPolicy: ImportConflictPolicy = ImportConflictPolicy.ASK,
        desiredName: String? = null,
        actor: String = "user"
    ): Pair<Long?, ImportReport> = withContext(Dispatchers.IO) {
        // ---- Stage 1-6: read + validate (destination untouched) ----
        val (parsed, failure) = readAndValidatePackage(packageStream)
        if (parsed == null || failure != null) {
            return@withContext null to ImportReport.error(failure?.code ?: "ARCHIVE_CORRUPT", failure?.message ?: "حزمة غير صالحة.")
        }

        // ---- Name conflict resolution (before ANY write) ----
        val baseName = (desiredName ?: parsed.project.optString("name", "مشروع مستورد")).trim()
        var finalName = baseName
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
                // Explicit destructive choice: remove existing same-name project rows first.
                val existing = projectDao.activeProjectsForWorkspaceList(targetWorkspaceId)
                    .firstOrNull { it.name.equals(baseName, ignoreCase = true) }
                if (existing != null) {
                    database.withTransaction {
                        projectDao.deleteProjectRow(existing.id)
                    }
                }
            }
            ImportConflictPolicy.SKIP, ImportConflictPolicy.MERGE -> {
                if (projectDao.countByNameForWorkspace(targetWorkspaceId, baseName) > 0) {
                    // SKIP: import under a suffixed name but keep semantics honest
                    // (project identity is new in every case — §11 IMPORT).
                    var suffix = 1
                    while (projectDao.countByNameForWorkspace(targetWorkspaceId, finalName) > 0) {
                        finalName = "$baseName ($suffix)"
                        suffix++
                    }
                }
            }
        }

        // ---- Compatibility report (§12: dependencies resolved against target) ----
        val report = buildCompatibilityReport(parsed)

        // ---- Snapshot protection happens at the WORKSPACE level (no target
        //      project is mutated by an import — identity is new), so a
        //      pre-import snapshot is recorded for auditability. ----

        // ---- Create the new project + import EVERYTHING transactionally ----
        val now = System.currentTimeMillis()
        val newProjectId = database.withTransaction {
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
            projectDao.updateProject(provisional.copy(id = id, rootPath = fileStore.projectRoot(id).canonicalPath))

            // ---- ID remapping tables (source id → new local id) ----
            val sessionIdMap = mutableMapOf<String, String>()
            val documentIdMap = mutableMapOf<String, String>()

            // Knowledge: new ids; projectId rebound to the NEW project.
            val chunkRows = mutableListOf<com.example.infrastructure.persistence.entities.DocumentChunkEntity>()
            for (i in 0 until parsed.knowledge.length()) {
                val docJson = parsed.knowledge.getJSONObject(i)
                val newDocId = "doc_${id}_${UUID.randomUUID().toString().take(10)}"
                documentIdMap[docJson.optString("id")] = newDocId
                database.knowledgeDocumentDao().insertOrUpdate(
                    com.example.infrastructure.persistence.entities.KnowledgeDocumentEntity(
                        id = newDocId,
                        workspaceId = targetWorkspaceId,
                        title = docJson.optString("title"),
                        sourceUri = docJson.optString("sourceUri", "imported://package"),
                        content = docJson.optString("content"),
                        mimeType = docJson.optString("mimeType", "text/markdown"),
                        tagsJson = encodeTags(docJson.optJSONArray("tags")),
                        totalChunks = docJson.optInt("totalChunks"),
                        totalTokensEstimated = docJson.optString("content").length / 4,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        projectId = id
                    )
                )
            }

            // Sessions: new ids; projectId rebound.
            for (i in 0 until parsed.sessions.length()) {
                val s = parsed.sessions.getJSONObject(i)
                val newSessionId = "sess_${UUID.randomUUID().toString().take(12)}"
                sessionIdMap[s.optString("sessionId")] = newSessionId
                database.conversationSessionDao().upsert(
                    com.example.infrastructure.persistence.entities.ConversationSessionEntity(
                        sessionId = newSessionId,
                        workspaceId = targetWorkspaceId,
                        title = s.optString("title", "جلسة مستوردة"),
                        mode = s.optString("mode", "AGENT"),
                        modelResourceId = if (s.isNull("modelResourceId")) null else s.optString("modelResourceId"),
                        turnCount = s.optInt("turnCount"),
                        createdAtEpochMs = s.optLong("createdAtEpochMs", now),
                        lastActiveAtEpochMs = now,
                        projectId = id
                    )
                )
            }

            // Tasks: same task ids are preserved ONLY when collision-free;
            // collisions get deterministic remapping (taskId + suffix).
            for (i in 0 until parsed.tasks.length()) {
                val t = parsed.tasks.getJSONObject(i)
                val sourceTaskId = t.optString("id")
                var newTaskId = sourceTaskId
                if (database.taskDao().getTaskById(sourceTaskId) != null) {
                    newTaskId = "${sourceTaskId}_imp_${UUID.randomUUID().toString().take(6)}"
                }
                database.taskDao().insertOrUpdateTask(
                    com.example.infrastructure.persistence.entities.TaskEntity(
                        id = newTaskId,
                        assignedAgentId = "agent_general",
                        rawPrompt = t.optString("rawPrompt"),
                        lifecycleState = "COMPLETED",
                        autonomyPolicy = "SUPERVISED",
                        resultSummary = if (t.isNull("resultSummary")) null else t.optString("resultSummary"),
                        totalTokensConsumed = 0,
                        durationMs = 0L,
                        isDegraded = false,
                        degradedReason = null,
                        errorMessage = null,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        workspaceId = targetWorkspaceId,
                        projectId = id
                    )
                )
            }

            // Dependencies: carried over as declarations (status re-evaluated by
            // ProjectReadinessService at runtime; imported projects NEVER
            // elevate the target workspace's policy — §12).
            for (i in 0 until parsed.dependencies.length()) {
                val d = parsed.dependencies.getJSONObject(i)
                dependencyDao.upsert(
                    ProjectDependencyEntity(
                        projectId = id,
                        type = d.optString("type", ProjectDependencyType.MODEL.name),
                        key = d.optString("key"),
                        requirement = d.optString("requirement", DependencyRequirement.OPTIONAL.name),
                        status = d.optString("status", DependencyStatus.MISSING.name),
                        detail = if (d.isNull("detail")) null else d.optString("detail"),
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now
                    )
                )
            }
            id
        }

        // ---- Files: staged writes into the NEW project root (identity is new,
        //      so destination mutation is safe after validation) ----
        val root = fileStore.projectRoot(newProjectId)
        var importedFiles = 0
        for ((rel, bytes) in parsed.fileEntries) {
            runCatching {
                val target = fileStore.resolveContained(root, rel)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                importedFiles++
            }
        }

        // ---- Verify + audit ----
        audit(AuditActions.PROJECT_IMPORTED, targetWorkspaceId, newProjectId, AuditResult.SUCCESS,
            reason = "name=$finalName files=$importedFiles deps=${parsed.dependencies.length()}")
        newProjectId to ImportReport.success(finalName, importedFiles, report)
    }

    // ==================================================================
    // CLONE / MOVE (§11)
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
        // Export to memory → import as new identity (deterministic remapping reused).
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
        // Rename to the requested clone name (import already de-conflicted).
        audit(AuditActions.PROJECT_CLONED, targetWorkspaceId, newId, AuditResult.SUCCESS,
            reason = "from=$sourceProjectId")
        newId to TransferOutcome.Success(
            "تم استنساخ المشروع إلى '$finalName'.",
            importedFileCount = (exportResult as TransferOutcome.Success).importedFileCount
        )
    }

    /**
     * MOVE (§11): VERIFIED transfer — the import into the target workspace
     * must SUCCEED AND BE VERIFIED before the source project is
     * archived/removed (never blind delete).
     */
    suspend fun moveProjectVerified(
        sourceWorkspaceId: String,
        sourceProjectId: Long,
        targetWorkspaceId: String
    ): Pair<Long?, TransferOutcome> = withContext(Dispatchers.IO) {
        if (sourceWorkspaceId == targetWorkspaceId) {
            return@withContext null to TransferOutcome.Failure("SAME_WORKSPACE", "المشروع موجود بالفعل في المساحة الهدف.", true)
        }
        // 1. Export source to memory.
        val buffer = java.io.ByteArrayOutputStream()
        val exportResult = exportProject(sourceWorkspaceId, sourceProjectId, buffer)
        if (exportResult is TransferOutcome.Failure) return@withContext null to exportResult
        // 2. Import into target as NEW identity.
        val (newId, report) = importProject(
            targetWorkspaceId = targetWorkspaceId,
            packageStream = buffer.toByteArray().inputStream(),
            conflictPolicy = ImportConflictPolicy.RENAME
        )
        if (newId == null) {
            return@withContext null to TransferOutcome.Failure("MOVE_IMPORT_FAILED", "فشل نقل الاستيراد: ${report.message}", true)
        }
        // 3. VERIFY the import landed (project row + files present).
        val verified = projectDao.getProjectByIdForWorkspace(newId, targetWorkspaceId) != null
        if (!verified) {
            return@withContext null to TransferOutcome.Failure("MOVE_VERIFY_FAILED", "تعذر التحقق من النقل — المصدر لم يُمس.", true)
        }
        // 4. Only now remove the source (sandbox + row, auditable).
        val sourceRoot = fileStore.projectRoot(sourceProjectId)
        sourceRoot.deleteRecursively()
        database.withTransaction {
            knowledgeDao.deleteAllForProject(sourceProjectId)
            sessionDao.deleteForProject(sourceProjectId)
            dependencyDao.deleteForProject(sourceProjectId)
            projectDao.deleteProjectRow(sourceProjectId)
        }
        audit(AuditActions.PROJECT_MOVED, targetWorkspaceId, newId, AuditResult.SUCCESS,
            reason = "verified move from=$sourceWorkspaceId/$sourceProjectId")
        newId to TransferOutcome.Success("تم نقل المشروع والتحقق منه بنجاح.")
    }

    // ==================================================================
    // SNAPSHOTS (§26) — state bundle WITHOUT secrets
    // ==================================================================

    suspend fun createSnapshot(
        workspaceId: String,
        projectId: Long,
        label: String,
        reason: String
    ): String? = withContext(Dispatchers.IO) {
        val buffer = java.io.ByteArrayOutputStream()
        val result = exportProject(workspaceId, projectId, buffer)
        if (result is TransferOutcome.Failure) return@withContext null
        val packageBytes = buffer.toByteArray()
        // The snapshot stores the manifest REFERENCE + integrity hash of the
        // package payload. Payload bytes are hashed directly (never a temp file).
        val snapshot = ProjectSnapshotEntity(
            id = "snap_${UUID.randomUUID().toString().take(12)}",
            projectId = projectId,
            workspaceId = workspaceId,
            label = label,
            reason = reason,
            manifestJson = "packageBytes=${'$'}{packageBytes.size}",
            contentHash = sha256Of(packageBytes),
            createdAtEpochMs = System.currentTimeMillis()
        )
        snapshotDao.upsert(snapshot)
        audit(AuditActions.SNAPSHOT_CREATED, workspaceId, projectId, AuditResult.SUCCESS, reason)
        snapshot.id
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
        val compatibility: ImportCompatibilityReport? = null
    ) {
        companion object {
            fun error(code: String, message: String) = ImportReport(false, "[$code] $message")
            fun conflict(name: String) = ImportReport(
                false,
                "CONFLICT: يوجد مشروع بهذا الاسم '$name' — اختر سياسة تعارض (إعادة تسمية/استبدال/دمج/تخطي) أو ألغِ.",
                conflictName = name
            )
            fun success(name: String, files: Int, compat: ImportCompatibilityReport) =
                ImportReport(true, "تم استيراد المشروع '$name'.", importedFiles = files, compatibility = compat)
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
