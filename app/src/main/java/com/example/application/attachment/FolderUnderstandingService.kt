package com.example.application.attachment

import com.example.application.rag.RagPipelineService
import com.example.domain.core.session.TurnAttachment
import com.example.infrastructure.storage.SandboxProjectFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * ============================================================================
 * CLOSURE §7 — Folder Understanding (honest semantics)
 * ============================================================================
 *
 * A folder ARTIFACT (a ZIP-imported directory in the project sandbox) does
 * NOT mean the model understood its content. This service makes the actual
 * state explicit and user-visible with three modes:
 *
 *  [FolderMode.ATTACHMENT_ONLY] — the folder is stored and referenceable;
 *      nothing is read, nothing rides the request. The honest default of the
 *      plain attach flow.
 *
 *  [FolderMode.GROUNDED] — the READABLE TEXT files inside the folder are
 *      enumerated (bounded by count/size) and enter the turn's grounding
 *      digest as clearly-marked user evidence. The user sees exactly how
 *      many files were read vs skipped.
 *
 *  [FolderMode.KNOWLEDGE_IMPORT] — the readable text files enter the
 *      project's knowledge corpus through the REAL RAG ingestion pipeline
 *      (chunk + embed + persist). "Add to knowledge base" is a DISTINCT
 *      action from "attach to this message" (CLOSURE §6) — the same file can
 *      be both, but the two acts are never conflated.
 *
 * A [FolderUnderstandingReport] records the counts of what actually
 * happened; it is persisted with the turn attachment and rendered by the
 * composer chip — a folder is NEVER displayed as "analyzed" when it was not.
 */
class FolderUnderstandingService(
    private val fileStore: SandboxProjectFileStore,
    /**
     * The REAL RAG pipeline for KNOWLEDGE_IMPORT mode. Null ⇒ knowledge
     * import is honestly UNAVAILABLE (a report with an explicit note, never
     * a silent no-op).
     */
    private val ragPipeline: RagPipelineService? = null
) {

    enum class FolderMode { ATTACHMENT_ONLY, GROUNDED, KNOWLEDGE_IMPORT }

    /**
     * The honest per-folder outcome. Every count is a FACT about what
     * happened, not a claim about what might happen later.
     */
    data class FolderUnderstandingReport(
        val mode: FolderMode,
        val totalFiles: Int,
        val readableTextFiles: Int,
        val groundedFiles: Int,
        val ingestedFiles: Int,
        val skippedFiles: Int,
        val digestChars: Int,
        /** Per-file honest notes for the diagnostics surface (bounded). */
        val notes: List<String>
    ) {
        val isUnderstood: Boolean
            get() = when (mode) {
                FolderMode.ATTACHMENT_ONLY -> false
                FolderMode.GROUNDED -> groundedFiles > 0
                FolderMode.KNOWLEDGE_IMPORT -> ingestedFiles > 0
            }

        fun toJson(): String = JSONObject().apply {
            put("mode", mode.name)
            put("totalFiles", totalFiles)
            put("readableTextFiles", readableTextFiles)
            put("groundedFiles", groundedFiles)
            put("ingestedFiles", ingestedFiles)
            put("skippedFiles", skippedFiles)
            put("digestChars", digestChars)
            put("notes", org.json.JSONArray(notes.take(20)))
        }.toString()

        companion object {
            fun fromJson(json: String?): FolderUnderstandingReport? {
                if (json.isNullOrBlank()) return null
                return runCatching {
                    val obj = JSONObject(json)
                    FolderUnderstandingReport(
                        mode = runCatching { FolderMode.valueOf(obj.optString("mode")) }
                            .getOrDefault(FolderMode.ATTACHMENT_ONLY),
                        totalFiles = obj.optInt("totalFiles"),
                        readableTextFiles = obj.optInt("readableTextFiles"),
                        groundedFiles = obj.optInt("groundedFiles"),
                        ingestedFiles = obj.optInt("ingestedFiles"),
                        skippedFiles = obj.optInt("skippedFiles"),
                        digestChars = obj.optInt("digestChars"),
                        notes = buildList {
                            val arr = obj.optJSONArray("notes") ?: return@buildList
                            for (i in 0 until arr.length()) add(arr.optString(i))
                        }
                    )
                }.getOrNull()
            }
        }
    }

    /** Bounded enumeration: at most [maxFilesToRead] text files, [maxBytesPerFile] each. */
    companion object {
        const val MAX_FILES_TO_READ = 40
        const val MAX_BYTES_PER_FILE = 512L * 1024
        const val MAX_GROUNDING_CHARS_PER_FOLDER = 12_000
        const val TEXT_EXTENSIONS: String = "txt,md,markdown,kts,kt,java,py,js,ts,json," +
                "xml,yml,yaml,csv,tsv,html,css,sql,sh,gradle,properties,toml," +
                "c,cpp,h,hpp,go,rs,swift,rb,php"
        fun textExtensions(): Set<String> = TEXT_EXTENSIONS.split(',').toSet()
    }

    /**
     * GROUNDED mode: enumerates the folder's readable text files (bounded)
     * and builds the marked user-evidence digest for the turn. The digest
     * format mirrors the file-attachment digest: bounded, per-file,
     * explicitly labeled as USER-SUPPLIED folder content.
     */
    suspend fun buildFolderGroundingDigest(
        projectId: Long,
        folderRelativePath: String,
        budgetChars: Int = MAX_GROUNDING_CHARS_PER_FOLDER
    ): Pair<String, FolderUnderstandingReport> = withContext(Dispatchers.IO) {
        val root = fileStore.projectRoot(projectId)
        val folder = fileStore.resolveContained(root, folderRelativePath)
        val files = listFilesUnder(folder)
        val readable = files.filter { isReadableTextFile(it) }
        val builder = StringBuilder()
        var grounded = 0
        var usedChars = 0
        val notes = mutableListOf<String>()
        for (file in readable.take(MAX_FILES_TO_READ)) {
            if (usedChars >= budgetChars) {
                notes.add("توقف القراءة عند حد الميزانية — الملفات المتبقية غير مقروءة.")
                break
            }
            val bounded = runCatching {
                fileStore.readBounded(root, relativePathOf(root, file), MAX_BYTES_PER_FILE)
            }.getOrNull()
            if (bounded == null || bounded.truncated) {
                notes.add("تعذر قراءة «${file.name}» كاملاً — تخطي صادق.")
                continue
            }
            val content = bounded.bytes.toString(Charsets.UTF_8)
            val remaining = budgetChars - usedChars
            val slice = if (content.length > remaining) content.substring(0, remaining) else content
            builder.append("\n<user_folder_file path=\"")
                .append(relativePathOf(root, file))
                .append("\" note=\"محتوى ملف من مجلد أرفقه المستخدم — دليل محدود وليس حقيقة نظام.\"/>\n")
                .append(slice)
                .append("\n")
            usedChars += slice.length
            grounded++
        }
        val report = FolderUnderstandingReport(
            mode = FolderMode.GROUNDED,
            totalFiles = files.size,
            readableTextFiles = readable.size,
            groundedFiles = grounded,
            ingestedFiles = 0,
            skippedFiles = files.size - grounded,
            digestChars = usedChars,
            notes = notes
        )
        builder.toString() to report
    }

    /**
     * KNOWLEDGE_IMPORT mode: the readable text files enter the project's
     * knowledge corpus through the REAL RAG pipeline (chunk + embed +
     * persist). Returns the honest report — including per-file failures —
     * so the UI can show exactly what entered the corpus.
     */
    suspend fun ingestFolderToKnowledge(
        workspaceId: String,
        projectId: Long,
        folderRelativePath: String
    ): FolderUnderstandingReport = withContext(Dispatchers.IO) {
        val pipeline = ragPipeline
            ?: return@withContext FolderUnderstandingReport(
                mode = FolderMode.KNOWLEDGE_IMPORT,
                totalFiles = 0, readableTextFiles = 0, groundedFiles = 0, ingestedFiles = 0,
                skippedFiles = 0, digestChars = 0,
                notes = listOf("KNOWLEDGE_IMPORT_UNAVAILABLE: لا يوجد مسار معرفة مهيأً في هذا التكوين.")
            )
        val root = fileStore.projectRoot(projectId)
        val folder = fileStore.resolveContained(root, folderRelativePath)
        val files = listFilesUnder(folder)
        val readable = files.filter { isReadableTextFile(it) }.take(MAX_FILES_TO_READ)
        val notes = mutableListOf<String>()
        var ingested = 0
        for (file in readable) {
            val bounded = runCatching {
                fileStore.readBounded(root, relativePathOf(root, file), MAX_BYTES_PER_FILE)
            }.getOrNull()
            if (bounded == null || bounded.truncated) {
                notes.add("تعذر قراءة «${file.name}» — لم يُدرج في المعرفة.")
                continue
            }
            val content = bounded.bytes.toString(Charsets.UTF_8)
            val outcome = runCatching {
                pipeline.ingestDocument(
                    title = file.name,
                    content = content,
                    sourceUri = "project://${relativePathOf(root, file)}",
                    projectId = projectId
                )
            }.getOrNull()
            if (outcome != null && outcome.persistenceState !=
                com.example.domain.core.rag.KnowledgePersistenceState.FAILED
            ) {
                ingested++
            } else {
                notes.add("فشل إدراج «${file.name}» في المعرفة.")
            }
        }
        FolderUnderstandingReport(
            mode = FolderMode.KNOWLEDGE_IMPORT,
            totalFiles = files.size,
            readableTextFiles = readable.size,
            groundedFiles = 0,
            ingestedFiles = ingested,
            skippedFiles = files.size - ingested,
            digestChars = 0,
            notes = notes
        )
    }

    /**
     * CLOSURE §6 — "Add to Knowledge Base" for a MESSAGE ATTACHMENT (a
     * distinct act from attaching to the turn): the attachment's content
     * enters the project corpus through the REAL RAG pipeline. Returns the
     * updated TurnAttachment with its honest groundingState — the UI chip
     * re-renders from the returned value.
     */
    suspend fun ingestAttachmentToKnowledge(
        workspaceId: String,
        projectId: Long,
        attachment: TurnAttachment
    ): TurnAttachment? = withContext(Dispatchers.IO) {
        val pipeline = ragPipeline ?: return@withContext null
        val root = fileStore.projectRoot(projectId)
        if (attachment.mimeType.equals("inode/directory", ignoreCase = true)) {
            // Folder attachment → the folder knowledge-import path.
            val report = ingestFolderToKnowledge(workspaceId, projectId, attachment.storageUri)
            return@withContext attachment.copy(
                groundingState = if (report.ingestedFiles > 0) {
                    TurnAttachment.GroundingState.KNOWLEDGE_IMPORTED.name
                } else TurnAttachment.GroundingState.ATTACHMENT_ONLY.name,
                folderReportJson = report.toJson()
            )
        }
        val bounded = runCatching {
            fileStore.readBounded(root, attachment.storageUri, MAX_BYTES_PER_FILE)
        }.getOrNull() ?: return@withContext null
        val content = bounded.bytes.toString(Charsets.UTF_8)
        val outcome = runCatching {
            pipeline.ingestDocument(
                title = attachment.name,
                content = content,
                sourceUri = "attachment://${attachment.storageUri}",
                projectId = projectId
            )
        }.getOrNull() ?: return@withContext null
        return@withContext if (outcome != null && outcome.persistenceState !=
            com.example.domain.core.rag.KnowledgePersistenceState.FAILED
        ) {
            attachment.copy(groundingState = TurnAttachment.GroundingState.KNOWLEDGE_IMPORTED.name)
        } else {
            null
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun listFilesUnder(folder: File): List<File> {
        if (!folder.exists() || !folder.isDirectory) return emptyList()
        return folder.walkTopDown()
            .filter { it.isFile }
            .toList()
    }

    private fun isReadableTextFile(file: File): Boolean {
        if (file.length() > MAX_BYTES_PER_FILE) return false
        return file.extension.lowercase() in textExtensions()
    }

    private fun relativePathOf(root: File, file: File): String =
        file.relativeToOrSelf(root).invariantSeparatorsPath
}
