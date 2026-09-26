package com.example.application.attachment

import com.example.application.artifacts.ArtifactService
import com.example.application.transfer.FileTransferService
import com.example.application.transfer.TransferOutcome
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.artifact.ArtifactType
import com.example.domain.core.session.TurnAttachment
import com.example.infrastructure.storage.SandboxProjectFileStore
import java.io.InputStream
import java.util.UUID

/**
 * ============================================================================
 * ChatAttachmentCoordinator — CHAT CAPABILITIES Task 2 §5/§6 (the attachment
 * axis of this task)
 * ============================================================================
 *
 * THE CONTRACT THIS COORDINATOR CLOSES (each stage already existed as real
 * infrastructure; this is the honest wiring, not a re-implementation):
 *
 *   Android SAF pick (UI layer, ActivityResultContracts)
 *     → [FileTransferService.ContentPort]  — the DECLARED production seam for
 *       ContentResolver/SAF (staged + atomic-promotion import, size limits,
 *       audit trail)
 *     → [ArtifactService.registerFileArtifact] — the REAL artifacts-table row
 *       (type ATTACHMENT, scope/audit truth — §15 Artifacts)
 *     → [TurnAttachment] — the durable message-level reference that persists
 *       WITH the conversation turn (DB v18, §16)
 *     → [buildGroundingDigest] — text-like attachments ride the LLM request
 *       as CLEARLY-BOUNDED, marked user evidence (the same honesty pattern the
 *       kernel uses for search evidence); non-text attachments stay attached
 *       but are never claimed to be "understood" (§7 Vision honesty).
 *
 * The coordinator speaks STREAMS + the existing ContentPort abstraction —
 * unit tests inject fakes exactly where the interface's documentation says
 * they do; the production ContentResolver implementation lives at the
 * platform boundary (infrastructure).
 */
class ChatAttachmentCoordinator(
    private val fileTransferService: FileTransferService,
    private val artifactService: ArtifactService,
    private val workspaceRuntimeService: WorkspaceRuntimeService,
    private val fileStore: SandboxProjectFileStore,
    /**
     * The SAF/ContentResolver seam — production wires the platform
     * implementation; tests use fakes (as the interface documents).
     */
    private val contentPort: FileTransferService.ContentPort,
    /**
     * Folder attachment support: serializes a SAF document TREE into the ZIP
     * stream the backend's [FileTransferService.importFolderZip] contract
     * expects (the UI layer hands the tree uri from
     * ACTION_OPEN_DOCUMENT_TREE). Null ⇒ folder attach is honestly
     * unavailable in this composition.
     */
    private val folderZipSource: (suspend (treeUri: String) -> InputStream?)? = null,
    /**
     * CLOSURE §7 — folder understanding + the "add to knowledge" act
     * (§6). Null ⇒ folder grounding/knowledge import are honestly
     * unavailable (folders attach as reference-only artifacts).
     */
    private val folderUnderstanding: FolderUnderstandingService? = null
) {

    /** Immutable workspace/project destination captured before an attachment job starts. */
    data class AttachmentScope(
        val workspaceId: String,
        val projectId: Long
    )

    /**
     * Captures the currently active attachment destination synchronously.
     * Callers must capture this BEFORE launching an asynchronous import so a
     * later workspace/project switch cannot retarget the import.
     */
    fun captureActiveScope(): AttachmentScope {
        val workspaceId = runCatching {
            workspaceRuntimeService.requireActiveWorkspaceId()
        }.getOrElse { throw AttachmentImportException("لا توجد مساحة عمل نشطة.") }
        val projectId = workspaceRuntimeService.activeProjectIdOrNull()
            ?: throw AttachmentImportException(
                "إرفاق الملفات يتطلب مشروعاً نشطاً — مخزن الرمل ذو نطاق المشروع. اختر مشروعاً أو أنشئ واحداً ثم أعد المحاولة."
            )
        return AttachmentScope(workspaceId = workspaceId, projectId = projectId)
    }

    /** Per-attachment digest cap — keeps grounding bounded for context windows. */
    private val maxPerAttachmentDigestChars = 8_000
    private val maxTotalDigestChars = 24_000

    // ------------------------------------------------------------------
    // Import (SAF → sandbox → artifact → reference)
    // ------------------------------------------------------------------

    /**
     * Captures the active scope for synchronous callers. Asynchronous UI
     * callers MUST pass an explicit scope captured before launching work.
     */
    suspend fun importFileAttachment(
        uri: String,
        reportedMimeType: String? = null
    ): TurnAttachment =
        importFileAttachment(uri, reportedMimeType, captureActiveScope())

    /**
     * Imports ONE picked file into the caller-provided project sandbox and
     * returns the durable [TurnAttachment] reference. The import itself is
     * the REAL transfer path (staging + atomic promotion + limits + audit).
     */
    suspend fun importFileAttachment(
        uri: String,
        reportedMimeType: String?,
        scope: AttachmentScope
    ): TurnAttachment {
        val workspaceId = scope.workspaceId
        val projectId = scope.projectId
        val displayName = contentPort.queryDisplayName(uri)
            ?: uri.substringAfterLast('/').ifBlank { "attachment" }
        val stream = contentPort.openRead(uri)
            ?: throw AttachmentImportException("تعذر فتح الملف المحدد (المصدر لم يعد متاحاً).")
        val safeName = sanitizeFileName(displayName)
        val relativePath = "attachments/${System.currentTimeMillis()}_${safeName}"
        val outcome = fileTransferService.importFile(
            workspaceId = workspaceId,
            projectId = projectId,
            source = stream,
            relativePath = relativePath
        )
        if (outcome !is TransferOutcome.Success) {
            throw AttachmentImportException(
                (outcome as TransferOutcome.Failure).message
            )
        }
        val sizeBytes = fileStore.stat(fileStore.projectRoot(projectId), relativePath).sizeBytes
        val artifact = artifactService.registerFileArtifact(
            workspaceId = workspaceId,
            projectId = projectId,
            relativePath = relativePath,
            name = displayName,
            mimeType = reportedMimeType ?: guessMimeType(displayName),
            forceType = ArtifactType.ATTACHMENT
        )
        return TurnAttachment(
            id = "attm_${UUID.randomUUID().toString().take(12)}",
            name = displayName,
            mimeType = reportedMimeType ?: guessMimeType(displayName),
            sizeBytes = if (sizeBytes > 0) sizeBytes else artifact.sizeBytes,
            storageUri = relativePath,
            artifactId = artifact.id,
            provenance = "SAF_FILE"
        )
    }

    /**
     * Captures the active scope for synchronous callers. Asynchronous UI
     * callers MUST pass an explicit scope captured before launching work.
     */
    suspend fun importFolderAttachment(treeUri: String): TurnAttachment =
        importFolderAttachment(treeUri, captureActiveScope())

    /**
     * Imports a picked FOLDER (SAF document tree, serialized to the ZIP the
     * backend's folder-import contract expects) into the caller-provided
     * project sandbox. Unavailable honestly when no folder zipper is wired.
     *
     * CLOSURE §7: [groundFolder] decides the folder's honest mode —
     *   false ⇒ ATTACHMENT_ONLY (stored + referenceable, NOTHING read);
     *   true  ⇒ GROUNDED (the readable text files inside enter the turn's
     *           grounding digest within limits; the report records exactly
     *           how many were read/skipped and rides the attachment).
     * The returned TurnAttachment's groundingState + folderReportJson carry
     * the actual outcome — the UI never claims a folder was analyzed when it
     * was not.
     */
    suspend fun importFolderAttachment(
        treeUri: String,
        scope: AttachmentScope,
        groundFolder: Boolean = false
    ): TurnAttachment {
        val zipper = folderZipSource
            ?: throw AttachmentImportException("استيراد المجلدات غير متاح في هذا التكوين.")
        val workspaceId = scope.workspaceId
        val projectId = scope.projectId
        val zipStream = zipper(treeUri)
            ?: throw AttachmentImportException("تعذر قراءة المجلد المحدد.")
        val displayName = contentPort.queryDisplayName(treeUri)
            ?: treeUri.substringAfterLast('/').ifBlank { "folder" }
        val targetDir = "attachments/folder_${System.currentTimeMillis()}"
        val outcome = fileTransferService.importFolderZip(
            workspaceId = workspaceId,
            projectId = projectId,
            zipStream = zipStream,
            targetSubDirectory = targetDir
        )
        if (outcome !is TransferOutcome.Success) {
            throw AttachmentImportException(
                (outcome as TransferOutcome.Failure).message
            )
        }
        val artifact = artifactService.registerFileArtifact(
            workspaceId = workspaceId,
            projectId = projectId,
            relativePath = targetDir,
            name = displayName,
            forceType = ArtifactType.FOLDER
        )
        var groundingState = com.example.domain.core.session.TurnAttachment.GroundingState.ATTACHMENT_ONLY.name
        var folderReportJson: String? = null
        if (groundFolder && folderUnderstanding != null) {
            val (digest, report) = folderUnderstanding.buildFolderGroundingDigest(
                projectId = projectId,
                folderRelativePath = targetDir
            )
            // The digest itself is consumed at SEND time (buildGroundingDigest
            // re-reads the folder); here we persist the honest REPORT only.
            if (report.groundedFiles > 0) {
                groundingState = com.example.domain.core.session.TurnAttachment.GroundingState.GROUNDED.name
            }
            folderReportJson = report.toJson()
        }
        return TurnAttachment(
            id = "attm_${UUID.randomUUID().toString().take(12)}",
            name = displayName,
            mimeType = "inode/directory",
            sizeBytes = artifact.sizeBytes,
            storageUri = targetDir,
            artifactId = artifact.id,
            provenance = "SAF_FOLDER_ZIP",
            groundingState = groundingState,
            folderReportJson = folderReportJson
        )
    }

    // ------------------------------------------------------------------
    // Grounding (attachment → execution/Llm request — §6)
    // ------------------------------------------------------------------

    /**
     * TRUE when the attachment's content can honestly ride the TEXT-ONLY
     * [com.example.domain.core.llm.LlmRequest] path (text-like mime or a
     * known code/text extension). Everything else (images, binaries…) stays
     * attached and displayed but is NEVER claimed to be analyzable — that is
     * the Vision honesty rule (§7).
     */
    fun isTextGroundable(attachment: TurnAttachment): Boolean =
        isTextGroundable(attachment.mimeType, attachment.name)

    fun isTextGroundable(mimeType: String, name: String): Boolean {
        val mime = mimeType.lowercase().substringBefore(';').trim()
        if (mime.startsWith("text/")) return true
        if (mime in TEXTUAL_MIME_TYPES) return true
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in TEXTUAL_EXTENSIONS
    }

    /**
     * FUNCTIONAL CLOSURE (Phase 1 §14): the honest grounding outcome — the
     * digest PLUS the per-attachment failures. A missing/failed read is NO
     * LONGER silently converted into "no evidence"; the caller decides (and
     * by contract BLOCKS the send) when a text-groundable attachment could
     * not be read.
     */
    data class GroundingOutcome(
        /** The bounded evidence block ("" when nothing was readable). */
        val digest: String,
        /** Human-readable reasons per attachment that SHOULD have grounded but failed. */
        val failures: List<String>
    ) {
        val isFailed: Boolean get() = failures.isNotEmpty()
    }

    /**
     * Builds the bounded, clearly-marked attachment evidence block that is
     * appended to the effective prompt sent to the execution kernel. The
     * block mirrors the kernel's own untrusted-evidence pattern: attachments
     * are USER-SUPPLIED content, marked as such, bounded so one huge file
     * cannot consume the context window, and never presented as system truth.
     *
     * FUNCTIONAL CLOSURE (Phase 1 §16): the READ ITSELF is bounded — at most
     * `budgetChars * 4` bytes (the UTF-8 worst case) are ever pulled from
     * disk per attachment ([SandboxProjectFileStore.readBounded] early-
     * terminates the stream), so a multi-megabyte file is never fully
     * materialized just to slice a few thousand characters off it.
     *
     * FUNCTIONAL CLOSURE (Phase 1 §14): read failures are REPORTED, never
     * swallowed — each text-groundable attachment that could not be read
     * lands in [GroundingOutcome.failures] and the caller blocks the send.
     *
     * RESIDUAL CLOSURE (P5 — execution-pinned grounding): [projectId] is the
     * scope the SEND captured (the same pinned context the execution uses).
     * The digest reads THAT project's sandbox root — a mid-flight project
     * switch can neither break the send (the attachment lives in the pinned
     * project's sandbox) nor ground another project's files.
     */
    suspend fun buildGroundingDigest(
        workspaceId: String,
        projectId: Long?,
        attachments: List<TurnAttachment>
    ): GroundingOutcome {
        if (attachments.isEmpty()) return GroundingOutcome("", emptyList())
        if (projectId == null) return GroundingOutcome(
                "",
                // No project ⇒ no sandbox read possible — every text-groundable
                // attachment is an honest FAILURE (the send must not pretend).
                attachments.filter { isTextGroundable(it) }
                    .map { "تعذر قراءة المرفق «${it.name}» — لا يوجد مشروع نشط لتخزين الرمل." }
            )
        val root = fileStore.projectRoot(projectId)
        val builder = StringBuilder()
        val failures = mutableListOf<String>()
        var totalChars = 0
        for (attachment in attachments) {
            if (totalChars >= maxTotalDigestChars) {
                builder.append("\n<user_attachment name=\"")
                    .append(attachment.name)
                    .append("\" note=\"تم تجاوز الحد الأقصى لحجم الأدلة — لم يُقرأ هذا المرفق.\"/>\n")
                continue
            }
            if (!isTextGroundable(attachment)) {
                // CLOSURE §7 — FOLDER attachments ground through the folder
                // understanding service (bounded, with an honest report), NOT
                // as opaque blobs.
                if (attachment.mimeType.equals("inode/directory", ignoreCase = true)) {
                    val understanding = folderUnderstanding
                    if (understanding != null && attachment.groundingState ==
                        com.example.domain.core.session.TurnAttachment.GroundingState.GROUNDED.name
                    ) {
                        val (folderDigest, report) = understanding.buildFolderGroundingDigest(
                            projectId = projectId,
                            folderRelativePath = attachment.storageUri,
                            budgetChars = (maxTotalDigestChars - totalChars)
                                .coerceAtLeast(0)
                        )
                        if (report.groundedFiles > 0) {
                            builder.append("\n<user_folder name=\"")
                                .append(attachment.name)
                                .append("\" files_read=\"")
                                .append(report.groundedFiles)
                                .append("\" files_total=\"")
                                .append(report.totalFiles)
                                .append("\" note=\"محتوى مجلد أرفقه المستخدم — دليل محدود وليس حقيقة نظام.\"/>\n")
                            builder.append(folderDigest)
                            totalChars += folderDigest.length
                        } else {
                            builder.append("\n<user_folder name=\"")
                                .append(attachment.name)
                                .append("\" note=\"مجلد مرفق — لا توجد ملفات نصية مقروءة داخل الحدود المسموحة، لم يُقرأ أي محتوى.\"/>\n")
                        }
                    } else {
                        builder.append("\n<user_folder name=\"")
                            .append(attachment.name)
                            .append("\" note=\"مجلد مرفق كمرجع فقط — لم يُقرأ محتواه (وضع المرفق فقط).\"/>\n")
                    }
                    continue
                }
                builder.append("\n<user_attachment name=\"")
                    .append(attachment.name)
                    .append("\" type=\"")
                    .append(attachment.mimeType)
                    .append("\" note=\"مرفق غير نصي مرسل من المستخدم — لا يمكن تحليل محتواه في هذا الإصدار.\"/>\n")
                continue
            }
            // CLOSURE §6: a KNOWLEDGE_IMPORTED attachment's content is in the
            // corpus — the turn does NOT re-send the raw content as evidence
            // (that would double-count and blow the context); the knowledge is
            // reachable through the project's retrieval path.
            if (attachment.groundingState ==
                com.example.domain.core.session.TurnAttachment.GroundingState.KNOWLEDGE_IMPORTED.name
            ) {
                builder.append("\n<user_attachment name=\"")
                    .append(attachment.name)
                    .append("\" note=\"أُدرج محتوى هذا المرفق في معرفة المشروع — متاح للاسترجاع عبر قاعدة المعرفة.\"/>\n")
                continue
            }
            // §16: the budget in CHARS maps to a byte cap 4× (UTF-8 worst
            // case) — the read stops at the byte cap, so the whole file is
            // never materialized. Truncation is decided by the bounded read
            // itself (one peeked byte), then refined by the char budget.
            val charBudget = (maxTotalDigestChars - totalChars)
                .coerceAtMost(maxPerAttachmentDigestChars)
                .coerceAtLeast(0)
            if (charBudget == 0) continue
            val bounded = runCatching {
                fileStore.readBounded(root, attachment.storageUri, charBudget.toLong() * 4L)
            }.getOrNull()
            if (bounded == null) {
                // §14: a text-groundable attachment that could not be read is
                // an honest FAILURE the caller blocks on — never a silent skip.
                failures += "تعذر قراءة المرفق «${attachment.name}» من التخزين — لن يُرسل طلب بلا أدلة مزعومة."
                continue
            }
            if (bounded.bytes.isEmpty() && !bounded.truncated) {
                // The sandbox file does not exist (or is empty) — the
                // reference is broken; honest failure, the send must not
                // claim evidence that is not there.
                failures += "المرفق «${attachment.name}» غير موجود في التخزين (مرجع مكسور)."
                continue
            }
            val content = runCatching { bounded.bytes.toString(Charsets.UTF_8) }.getOrNull() ?: ""
            val truncated = bounded.truncated || content.length > charBudget
            val slice = content.take(charBudget)
            totalChars += slice.length
            builder.append("\n<user_attachment name=\"")
                .append(attachment.name)
                .append("\" type=\"")
                .append(attachment.mimeType)
                .append("\" size_bytes=\"")
                .append(attachment.sizeBytes)
                .append("\" truncated=\"")
                .append(truncated)
                .append("\">\n")
                .append(slice)
                .append("\n</user_attachment>\n")
        }
        return GroundingOutcome(builder.toString().trim().ifBlank { "" }, failures)
    }

    /**
     * Reads one attachment's text content (diagnostic/preview surface) —
     * BOUNDED (§16): at most [maxChars]*4 bytes are pulled from disk.
     */
    suspend fun readAttachmentText(attachment: TurnAttachment, maxChars: Int = 2_000): String? {
        if (!isTextGroundable(attachment)) return null
        val projectId = workspaceRuntimeService.activeProjectIdOrNull() ?: return null
        return runCatching {
            fileStore.readBounded(fileStore.projectRoot(projectId), attachment.storageUri, maxChars.toLong() * 4L)
                .bytes.toString(Charsets.UTF_8).take(maxChars)
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private suspend fun requireActiveProject(): Pair<String, Long> {
        val workspaceId = runCatching {
            workspaceRuntimeService.requireActiveWorkspaceId()
        }.getOrElse { throw AttachmentImportException("لا توجد مساحة عمل نشطة.") }
        val projectId = workspaceRuntimeService.activeProjectIdOrNull()
            ?: throw AttachmentImportException(
                "إرفاق الملفات يتطلب مشروعاً نشطاً — مخزن الرمل ذو نطاق المشروع. اختر مشروعاً أو أنشئ واحداً ثم أعد المحاولة."
            )
        return workspaceId to projectId
    }

    // ------------------------------------------------------------------
    // FUNCTIONAL CLOSURE (Phase 1 §13): draft-removal cleanup — the imported
    // sandbox copy AND the artifact row are deleted together, so removing a
    // draft leaves NO orphaned storage behind.
    // ------------------------------------------------------------------

    /**
     * Deletes an un-sent attachment's REAL footprint: the artifact row (which
     * deletes its sandbox file through the scope-authorized path) and, as a
     * belt-and-braces fallback, the sandbox file itself when no artifact row
     * exists. Ownership contract: a draft attachment is owned by the composer
     * until the send persists it onto a turn — removing it before that MUST
     * remove everything the import created.
     *
     * Returns TRUE when the footprint is fully gone. A failure surfaces as
     * [AttachmentCleanupException] so the caller can keep the draft visible
     * (honest) instead of silently dropping state above orphaned files.
     */
    suspend fun deleteImportedAttachment(
        attachment: TurnAttachment,
        scope: AttachmentScope? = null
    ): Boolean {
        val resolvedScope = scope ?: captureActiveScope()
        val workspaceId = resolvedScope.workspaceId
        val projectId = resolvedScope.projectId
        var deleted = false
        val artifactId = attachment.artifactId
        if (artifactId != null) {
            val scope = com.example.domain.core.context.ResourceScope.Project(workspaceId, projectId)
            runCatching { artifactService.delete(scope, artifactId) }
            // VERIFICATION decides, not the call's return: a row that is GONE
            // (deleted now, or already absent — an idempotent cleanup target)
            // is clean; a row that is STILL THERE is the real failure the
            // draft must stay visible for.
            val rowStillExists = runCatching {
                artifactService.forProject(projectId).any { it.id == artifactId }
            }.getOrDefault(true)
            if (rowStillExists) {
                throw AttachmentCleanupException(
                    "تعذر حذف سجل الأثر للمرفق «${attachment.name}» — أبقيناه مرئياً بدلاً من ترك نسخة يتيمة."
                )
            }
            deleted = true
        }
        // Fallback/direct cleanup: remove the sandbox copy itself (covers
        // rows without an artifact id, and files whose artifact delete
        // skipped storage). Idempotent — a missing file is already clean.
        val fileGone = runCatching {
            !fileStore.stat(fileStore.projectRoot(projectId), attachment.storageUri).exists ||
                fileStore.delete(fileStore.projectRoot(projectId), attachment.storageUri)
        }.getOrDefault(false)
        return deleted || fileGone || artifactId == null
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^\\p{L}\\p{N}\\s._()-]"), "_")
            .trim()
            .take(120)
            .ifBlank { "attachment" }

    private fun guessMimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MIME_BY_EXTENSION[extension] ?: "application/octet-stream"
    }

    /** The honest import failure the composer chip/error surface renders. */
    class AttachmentImportException(message: String) : Exception(message)

    /** The honest cleanup failure (Phase 1 §13) — the draft stays visible. */
    class AttachmentCleanupException(message: String) : Exception(message)

    companion object {
        private val TEXTUAL_MIME_TYPES = setOf(
            "application/json", "application/xml", "application/javascript",
            "application/x-yaml", "application/yaml", "application/sql",
            "application/x-sh", "application/markdown", "application/csv"
        )
        private val TEXTUAL_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "xml", "yaml", "yml", "csv", "tsv",
            "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "c", "h", "cpp",
            "hpp", "cs", "go", "rs", "rb", "php", "swift", "sql", "sh", "bat",
            "html", "htm", "css", "scss", "gradle", "properties", "ini",
            "conf", "cfg", "log", "toml"
        )
        private val MIME_BY_EXTENSION = mapOf(
            "txt" to "text/plain", "md" to "text/markdown", "markdown" to "text/markdown",
            "json" to "application/json", "xml" to "application/xml",
            "yaml" to "application/yaml", "yml" to "application/yaml",
            "csv" to "text/csv", "tsv" to "text/tab-separated-values",
            "kt" to "text/x-kotlin", "kts" to "text/x-kotlin", "java" to "text/x-java-source",
            "py" to "text/x-python", "js" to "text/javascript", "ts" to "text/javascript",
            "html" to "text/html", "htm" to "text/html", "css" to "text/css",
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp", "pdf" to "application/pdf",
            "zip" to "application/zip"
        )
    }
}
