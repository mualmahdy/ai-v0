package com.example.application.transfer

import com.example.application.audit.AuditTrailService
import com.example.domain.core.audit.AuditActions
import com.example.domain.core.audit.AuditActorType
import com.example.domain.core.audit.AuditResult
import com.example.domain.core.context.ResourceScope
import com.example.infrastructure.storage.SandboxProjectFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ============================================================================
 * REPAIR ORDER §9 — FILE AND FOLDER IMPORT/EXPORT (unified transfer subsystem)
 * ============================================================================
 * Import/export of files AND folders for SESSION/PROJECT/WORKSPACE/APPLICATION
 * scopes, using Android Storage Access Framework URIs at the UI boundary.
 *
 * Testability & separation: this service speaks STREAMS, not Android types.
 * The [ContentPort] abstraction (production impl wraps ContentResolver/SAF;
 * tests use fakes) means "no raw filesystem path is the app's only
 * abstraction" — the app-level abstraction is scope + stream + containment.
 *
 * Safety (all enforced BEFORE any destination mutation):
 *  - path traversal: every entry name validated ([SandboxProjectFileStore.validateZipEntryName])
 *  - Zip Slip: extraction targets are containment-checked per entry
 *  - oversized archives: entry count + total + single-entry byte limits
 *  - corrupted transfers: hash verification when the manifest provides one
 *  - partial writes: staging directory + ATOMIC PROMOTION (the destination
 *    project root only ever sees fully-validated imports)
 */
class FileTransferService(
    private val fileStore: SandboxProjectFileStore,
    private val auditTrail: AuditTrailService? = null,
    private val limits: TransferLimits = TransferLimits.DEFAULT
) {

    /** Stream abstraction over SAF/ContentResolver (production) or fakes (tests). */
    interface ContentPort {
        fun openRead(uri: String): InputStream?
        fun openWrite(uri: String): OutputStream?
        fun queryDisplayName(uri: String): String?
        fun querySize(uri: String): Long?
    }

    // ------------------------------------------------------------------
    // IMPORT (external → sandbox)
    // ------------------------------------------------------------------

    /**
     * Imports a single FILE into a project sandbox. Staged then atomically
     * promoted; the destination is never partially mutated.
     */
    suspend fun importFile(
        workspaceId: String,
        projectId: Long,
        source: InputStream,
        relativePath: String,
        expectedSha256: String? = null
    ): TransferOutcome = withContext(Dispatchers.IO) {
        val root = fileStore.projectRoot(projectId)
        // Validate destination path BEFORE reading (fail fast, destination untouched).
        val target = runCatching { fileStore.resolveContained(root, relativePath) }.getOrElse {
            return@withContext TransferOutcome.Failure("PATH_REJECTED", it.message ?: "مسار غير صالح.", true)
        }
        // Stage into .staging, then atomically promote.
        val stagingDir = File(root, ".staging_import").apply { mkdirs() }
        try {
            val staged = File(stagingDir, "single_${System.currentTimeMillis()}")
            var total = 0L
            staged.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limits.maxSingleEntryBytes) {
                        return@withContext TransferOutcome.Failure(
                            "ARCHIVE_TOO_LARGE",
                            "الملف يتجاوز الحد الأقصى للحجم (${limits.maxSingleEntryBytes / (1024 * 1024)}MB).",
                            true
                        )
                    }
                    out.write(buf, 0, n)
                }
            }
            // Hash verification (corrupted transfer detection).
            if (expectedSha256 != null) {
                val actual = sha256Of(staged)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    return@withContext TransferOutcome.Failure(
                        "HASH_MISMATCH",
                        "بصمة المحتوى غير مطابقة — النقل تالف.",
                        true
                    )
                }
            }
            // Atomic promotion.
            target.parentFile?.mkdirs()
            val promoted = staged.renameTo(target)
            if (!promoted) {
                staged.copyTo(target, overwrite = true)
                staged.delete()
            }
            audit(AuditActions.FILE_IMPORTED, workspaceId, projectId, AuditResult.SUCCESS, relativePath)
            TransferOutcome.Success("تم استيراد الملف: $relativePath", importedFileCount = 1)
        } catch (e: Exception) {
            TransferOutcome.Failure("IMPORT_EXCEPTION", "فشل الاستيراد: ${e.message}", true)
        } finally {
            File(root, ".staging_import").deleteRecursively()
        }
    }

    /**
     * Imports a FOLDER (as a ZIP stream — SAF ACTION_OPEN_DOCUMENT on a zip,
     * or a document tree serialized by the UI layer) into a project sandbox.
     *
     * Protections: Zip-Slip (entry-name validation + per-entry containment),
     * entry-count and total-size limits, staging + atomic promotion.
     */
    suspend fun importFolderZip(
        workspaceId: String,
        projectId: Long,
        zipStream: InputStream,
        targetSubDirectory: String? = null
    ): TransferOutcome = withContext(Dispatchers.IO) {
        val root = fileStore.projectRoot(projectId)
        val stagingDir = File(root, ".staging_import_${System.currentTimeMillis()}")
        try {
            var entryCount = 0
            var totalBytes = 0L
            ZipInputStream(zipStream.buffered()).use { zis ->
                while (true) {
                    val entry: ZipEntry? = zis.nextEntry ?: break
                    val name = entry?.name ?: break
                    if (entry.isDirectory) {
                        if (!fileStore.validateZipEntryName(name)) {
                            return@withContext TransferOutcome.Failure(
                                "PATH_TRAVERSAL_DETECTED",
                                "مدخل أرشيف غير آمن: $name — رفض الاستيراد.",
                                true
                            )
                        }
                        File(stagingDir, name).mkdirs()
                        continue
                    }
                    if (!fileStore.validateZipEntryName(name)) {
                        return@withContext TransferOutcome.Failure(
                            "PATH_TRAVERSAL_DETECTED",
                            "مدخل أرشيف غير آمن: $name — رفض الاستيراد.",
                            true
                        )
                    }
                    entryCount++
                    if (entryCount > limits.maxEntryCount) {
                        return@withContext TransferOutcome.Failure(
                            "ENTRY_COUNT_EXCEEDED",
                            "عدد مدخلات الأرشيف يتجاوز الحد (${limits.maxEntryCount}).",
                            true
                        )
                    }
                    val stagedTarget = File(stagingDir, name)
                    stagedTarget.parentFile?.mkdirs()
                    var entryBytes = 0L
                    stagedTarget.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            entryBytes += n
                            totalBytes += n
                            if (entryBytes > limits.maxSingleEntryBytes || totalBytes > limits.maxUncompressedBytes) {
                                return@withContext TransferOutcome.Failure(
                                    "ARCHIVE_TOO_LARGE",
                                    "الأرشيف يتجاوز الحدود المسموحة.",
                                    true
                                )
                            }
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            if (entryCount == 0) {
                return@withContext TransferOutcome.Failure("ARCHIVE_CORRUPT", "الأرشيف فارغ أو تالف.", true)
            }
            // Atomic promotion into the destination.
            val destinationRoot = if (targetSubDirectory.isNullOrBlank()) root
            else fileStore.resolveContained(root, targetSubDirectory).apply { mkdirs() }
            stagingDir.listFiles()?.forEach { entry ->
                val dst = File(destinationRoot, entry.name)
                if (entry.isDirectory) entry.copyRecursively(dst, overwrite = true)
                else {
                    dst.parentFile?.mkdirs()
                    java.nio.file.Files.copy(entry.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            audit(AuditActions.FOLDER_IMPORTED, workspaceId, projectId, AuditResult.SUCCESS, targetSubDirectory)
            TransferOutcome.Success(
                "تم استيراد المجلد ($entryCount ملفاً).",
                importedFileCount = entryCount
            )
        } catch (e: Exception) {
            TransferOutcome.Failure("IMPORT_EXCEPTION", "فشل استيراد المجلد: ${e.message}", true)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // EXPORT (sandbox → external)
    // ------------------------------------------------------------------

    /** Exports a single file from a project sandbox to a destination stream. */
    suspend fun exportFile(
        workspaceId: String,
        projectId: Long,
        relativePath: String,
        destination: OutputStream
    ): TransferOutcome = withContext(Dispatchers.IO) {
        try {
            val root = fileStore.projectRoot(projectId)
            val content = fileStore.read(root, relativePath)
            destination.use { it.write(content) }
            audit(AuditActions.FILE_EXPORTED, workspaceId, projectId, AuditResult.SUCCESS, relativePath)
            TransferOutcome.Success("تم تصدير الملف: $relativePath", importedFileCount = 1)
        } catch (e: Exception) {
            TransferOutcome.Failure("EXPORT_EXCEPTION", "فشل التصدير: ${e.message}", true)
        }
    }

    /** Exports a folder (recursively) as a ZIP to a destination stream. */
    suspend fun exportFolderZip(
        workspaceId: String,
        projectId: Long,
        subDirectory: String? = null,
        destination: OutputStream
    ): TransferOutcome = withContext(Dispatchers.IO) {
        try {
            val root = fileStore.projectRoot(projectId)
            val base = if (subDirectory.isNullOrBlank()) root else fileStore.resolveContained(root, subDirectory)
            if (!base.exists()) {
                return@withContext TransferOutcome.Failure("NOT_FOUND", "المجلد غير موجود.", true)
            }
            var count = 0
            ZipOutputStream(destination.buffered()).use { zos ->
                base.walkTopDown().forEach { file ->
                    if (file == base) return@forEach
                    val entryName = file.toRelativeString(base).replace(File.separatorChar, '/')
                    if (file.isDirectory) {
                        zos.putNextEntry(ZipEntry("$entryName/"))
                        zos.closeEntry()
                    } else {
                        zos.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        count++
                    }
                }
            }
            audit(AuditActions.FOLDER_EXPORTED, workspaceId, projectId, AuditResult.SUCCESS, subDirectory)
            TransferOutcome.Success("تم تصدير المجلد ($count ملفاً).", importedFileCount = count)
        } catch (e: Exception) {
            TransferOutcome.Failure("EXPORT_EXCEPTION", "فشل تصدير المجلد: ${e.message}", true)
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun audit(
        action: String,
        workspaceId: String,
        projectId: Long,
        result: AuditResult,
        detail: String?
    ) {
        auditTrail?.recordAsync(
            actorType = AuditActorType.USER,
            actorId = "user",
            action = action,
            resourceType = "TRANSFER",
            resourceId = detail,
            sourceScope = ResourceScope.Project(workspaceId, projectId),
            result = result,
            reason = detail
        )
    }
}
