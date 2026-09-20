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
    private val folderZipSource: (suspend (treeUri: String) -> InputStream?)? = null
) {

    /** Per-attachment digest cap — keeps grounding bounded for context windows. */
    private val maxPerAttachmentDigestChars = 8_000
    private val maxTotalDigestChars = 24_000

    // ------------------------------------------------------------------
    // Import (SAF → sandbox → artifact → reference)
    // ------------------------------------------------------------------

    /**
     * Imports ONE picked file into the ACTIVE project sandbox and returns the
     * durable [TurnAttachment] reference. The import itself is the REAL
     * transfer path (staging + atomic promotion + limits + audit).
     */
    suspend fun importFileAttachment(
        uri: String,
        reportedMimeType: String? = null
    ): TurnAttachment {
        val (workspaceId, projectId) = requireActiveProject()
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
     * Imports a picked FOLDER (SAF document tree, serialized to the ZIP the
     * backend's folder-import contract expects) into the active project
     * sandbox. Unavailable honestly when no folder zipper is wired.
     */
    suspend fun importFolderAttachment(treeUri: String): TurnAttachment {
        val zipper = folderZipSource
            ?: throw AttachmentImportException("استيراد المجلدات غير متاح في هذا التكوين.")
        val (workspaceId, projectId) = requireActiveProject()
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
        return TurnAttachment(
            id = "attm_${UUID.randomUUID().toString().take(12)}",
            name = displayName,
            mimeType = "inode/directory",
            sizeBytes = artifact.sizeBytes,
            storageUri = targetDir,
            artifactId = artifact.id,
            provenance = "SAF_FOLDER_ZIP"
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
     * Builds the bounded, clearly-marked attachment evidence block that is
     * appended to the effective prompt sent to the execution kernel. The
     * block mirrors the kernel's own untrusted-evidence pattern: attachments
     * are USER-SUPPLIED content, marked as such, bounded so one huge file
     * cannot consume the context window, and never presented as system truth.
     */
    suspend fun buildGroundingDigest(
        workspaceId: String,
        attachments: List<TurnAttachment>
    ): String {
        if (attachments.isEmpty()) return ""
        val projectId = workspaceRuntimeService.activeProjectIdOrNull()
            ?: return "" // no project ⇒ no sandbox read — honest no-op
        val root = fileStore.projectRoot(projectId)
        val builder = StringBuilder()
        var totalChars = 0
        for (attachment in attachments) {
            if (totalChars >= maxTotalDigestChars) {
                builder.append("\n<user_attachment name=\"")
                    .append(attachment.name)
                    .append("\" note=\"تم تجاوز الحد الأقصى لحجم الأدلة — لم يُقرأ هذا المرفق.\"/>\n")
                continue
            }
            if (!isTextGroundable(attachment)) {
                builder.append("\n<user_attachment name=\"")
                    .append(attachment.name)
                    .append("\" type=\"")
                    .append(attachment.mimeType)
                    .append("\" note=\"مرفق غير نصي مرسل من المستخدم — لا يمكن تحليل محتواه في هذا الإصدار.\"/>\n")
                continue
            }
            val content = runCatching {
                fileStore.read(root, attachment.storageUri).toString(Charsets.UTF_8)
            }.getOrNull() ?: continue
            val budget = (maxTotalDigestChars - totalChars)
                .coerceAtMost(maxPerAttachmentDigestChars)
                .coerceAtLeast(0)
            val truncated = content.length > budget
            val slice = content.take(budget)
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
        return builder.toString().trim().ifBlank { "" }
    }

    /** Reads one attachment's text content (diagnostic/preview surface). */
    suspend fun readAttachmentText(attachment: TurnAttachment, maxChars: Int = 2_000): String? {
        if (!isTextGroundable(attachment)) return null
        val projectId = workspaceRuntimeService.activeProjectIdOrNull() ?: return null
        return runCatching {
            fileStore.read(fileStore.projectRoot(projectId), attachment.storageUri)
                .toString(Charsets.UTF_8)
                .take(maxChars)
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
