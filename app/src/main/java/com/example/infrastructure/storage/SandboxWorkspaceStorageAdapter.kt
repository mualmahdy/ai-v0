package com.example.infrastructure.storage

import android.content.Context
import com.example.domain.core.Outcome
import com.example.domain.core.rethrowIfCancellation
import com.example.domain.core.security.governance.PathContainment
import com.example.domain.core.storage.StorageFailure
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.domain.ports.storage.WorkspaceStoragePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Clean Infrastructure Adapter for isolated workspace file system operations
 * conforming to Android internal sandbox security policies.
 *
 * P0 CONVERGENCE (audit step 12 §6):
 *  - The adapter no longer implements `SessionRepositoryPort` — that whole
 *    project-scoped surface (getActiveProject / listProjects / createProject /
 *    listSessions / saveSession) had ZERO production callers and lazily
 *    bootstrapped the legacy shared project id=1L (a cross-workspace data
 *    bleed). It was removed with the `sessions` table (MIGRATION_11_TO_12).
 *  - The remaining `WorkspaceStoragePort` file operations are keyed by the
 *    OWNING WORKSPACE's sandbox project id, which callers resolve explicitly
 *    (workspaceRuntimeService.activeProjectIdOrNull / the pinned execution
 *    context) — never an implicit default inside this adapter.
 */
class SandboxWorkspaceStorageAdapter(
    private val context: Context
) : WorkspaceStoragePort {

    private val baseProjectsDir: File by lazy {
        File(context.filesDir, "workspaces").apply { if (!exists()) mkdirs() }
    }

    private fun getProjectDir(projectId: Long): File {
        return File(baseProjectsDir, "proj_$projectId").apply { if (!exists()) mkdirs() }
    }

    /**
     * P0-2 FIX (audit 2026 §6 — workspace filesystem containment flaw):
     * the legacy check `canonicalPath.startsWith(projectDir.canonicalPath)`
     * accepted SIBLING directories that share a string prefix — a path under
     * `proj_10` passed the containment check of `proj_1`. Containment is now
     * boundary-safe: the candidate must equal the root or continue AFTER the
     * root's path-separator boundary (see [PathContainment]).
     */
    private fun isContainedInProject(targetCanonical: String, projectDir: File): Boolean =
        PathContainment.isContained(
            candidateCanonical = targetCanonical.replace('\\', '/'),
            rootCanonical = projectDir.canonicalPath.replace('\\', '/')
        )

    // --- WorkspaceStoragePort Implementation ---

    override suspend fun readFile(projectId: Long, relativePath: String): Outcome<String, StorageFailure> = withContext(Dispatchers.IO) {
        try {
            val projectDir = getProjectDir(projectId)
            val targetFile = File(projectDir, relativePath)

            // Security containment check (No path traversal outside sandbox)
            if (!isContainedInProject(targetFile.canonicalPath, projectDir)) {
                return@withContext Outcome.Error(
                    StorageFailure.AccessDenied(relativePath, "تم حظر محاولة الوصول خارج نطاق مساحة العمل.")
                )
            }

            if (!targetFile.exists() || !targetFile.isFile) {
                return@withContext Outcome.Error(
                    StorageFailure.FileNotFound(relativePath)
                )
            }

            val text = targetFile.readText(Charsets.UTF_8)
            Outcome.Success(text)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Outcome.Error(
                StorageFailure.ReadWriteError(relativePath, "فشل قراءة الملف: ${e.localizedMessage}")
            )
        }
    }

    override suspend fun writeFile(projectId: Long, relativePath: String, content: String): Outcome<Unit, StorageFailure> = withContext(Dispatchers.IO) {
        try {
            val projectDir = getProjectDir(projectId)
            val targetFile = File(projectDir, relativePath)

            // Containment check
            if (!isContainedInProject(targetFile.canonicalPath, projectDir)) {
                return@withContext Outcome.Error(
                    StorageFailure.AccessDenied(relativePath, "محاولة كتابة خارج نطاق مساحة العمل.")
                )
            }

            targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            targetFile.writeText(content, Charsets.UTF_8)
            Outcome.Success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Outcome.Error(
                StorageFailure.ReadWriteError(relativePath, "فشل كتابة الملف: ${e.localizedMessage}")
            )
        }
    }

    override suspend fun listFiles(projectId: Long, subDirectory: String?): Outcome<List<WorkspaceFileEntry>, StorageFailure> = withContext(Dispatchers.IO) {
        try {
            val projectDir = getProjectDir(projectId)
            val targetDir = if (subDirectory.isNullOrBlank()) projectDir else File(projectDir, subDirectory)

            if (!isContainedInProject(targetDir.canonicalPath, projectDir)) {
                return@withContext Outcome.Error(
                    StorageFailure.AccessDenied(subDirectory ?: "", "محاولة وصول غير مصرح خارج مساحة العمل.")
                )
            }

            if (!targetDir.exists()) {
                return@withContext Outcome.Success(emptyList())
            }

            val files = targetDir.listFiles() ?: emptyArray()
            val entries = files.map { file ->
                WorkspaceFileEntry(
                    relativePath = file.relativeTo(projectDir).path,
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isFile) file.length() else 0L,
                    lastModifiedMs = file.lastModified()
                )
            }.sortedWith(compareByDescending<WorkspaceFileEntry> { it.isDirectory }.thenBy { it.relativePath })

            Outcome.Success(entries)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Outcome.Error(
                StorageFailure.ReadWriteError(subDirectory ?: "", "فشل سرد الملفات: ${e.localizedMessage}")
            )
        }
    }

    // FIW Stage 2: one containment-checked file resolution (shared by the
    // binary read / move / copy arms — the same boundary check readFile
    // applies, never repeated inline).
    private fun resolveContainedFile(projectId: Long, relativePath: String): Pair<File, File>? {
        val projectDir = getProjectDir(projectId)
        val targetFile = File(projectDir, relativePath)
        if (!isContainedInProject(targetFile.canonicalPath, projectDir)) return null
        return projectDir to targetFile
    }

    override suspend fun readFileBytes(projectId: Long, relativePath: String): Outcome<ByteArray, StorageFailure> = withContext(Dispatchers.IO) {
        try {
            val resolved = resolveContainedFile(projectId, relativePath)
                ?: return@withContext Outcome.Error(
                    StorageFailure.AccessDenied(relativePath, "تم حظر محاولة الوصول خارج نطاق مساحة العمل.")
                )
            val (projectDir, targetFile) = resolved
            if (!targetFile.exists() || !targetFile.isFile) {
                return@withContext Outcome.Error(StorageFailure.FileNotFound(relativePath))
            }
            Outcome.Success(targetFile.readBytes())
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Outcome.Error(
                StorageFailure.ReadWriteError(relativePath, "فشل قراءة الملف (بايتات): ${e.localizedMessage}")
            )
        }
    }

    override suspend fun moveFile(projectId: Long, fromPath: String, toPath: String): Outcome<Unit, StorageFailure> = withContext(Dispatchers.IO) {
        try {
            val source = resolveContainedFile(projectId, fromPath)
                ?: return@withContext Outcome.Error(
                    StorageFailure.AccessDenied(fromPath, "محاولة نقل من خارج مساحة العمل.")
                )
            val destination = resolveContainedFile(projectId, toPath)
                ?: return@withContext Outcome.Error(
                    StorageFailure.AccessDenied(toPath, "محاولة نقل إلى خارج مساحة العمل.")
                )
            val (_, sourceFile) = source
            val (_, destinationFile) = destination
            if (!sourceFile.exists()) {
                return@withContext Outcome.Error(StorageFailure.FileNotFound(fromPath))
            }
            if (destinationFile.exists()) {
                return@withContext Outcome.Error(
                    StorageFailure.AccessDenied(toPath, "الوجهة موجودة بالفعل — رُفض النقل بدل الكتابة فوقها.")
                )
            }
            destinationFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            // Byte-exact move: renameTo first (atomic on the same volume);
            // the stream copy fallback preserves EVERY byte (the old
            // readText→writeText path in the toolchain corrupted binaries).
            val renamed = sourceFile.renameTo(destinationFile)
            if (renamed) {
                Outcome.Success(Unit)
            } else {
                val copied = runCatching {
                    sourceFile.inputStream().use { input ->
                        destinationFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }.isSuccess
                if (!copied) {
                    destinationFile.delete()
                    return@withContext Outcome.Error(
                        StorageFailure.ReadWriteError(fromPath, "فشل نقل $fromPath إلى $toPath")
                    )
                }
                if (!sourceFile.delete()) {
                    // The copy landed; the stale source is an honest degraded
                    // state — surfaced, never silently ignored.
                    return@withContext Outcome.Error(
                        StorageFailure.ReadWriteError(fromPath, "تم النقل لكن تعذر حذف المصدر $fromPath — تحقق يدوي.")
                    )
                }
                Outcome.Success(Unit)
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Outcome.Error(
                StorageFailure.ReadWriteError(fromPath, "فشل النقل: ${e.localizedMessage}")
            )
        }
    }

    override suspend fun copyFile(projectId: Long, fromPath: String, toPath: String): Outcome<Unit, StorageFailure> = withContext(Dispatchers.IO) {
        try {
            val source = resolveContainedFile(projectId, fromPath)
                ?: return@withContext Outcome.Error(
                    StorageFailure.AccessDenied(fromPath, "محاولة نسخ من خارج مساحة العمل.")
                )
            val destination = resolveContainedFile(projectId, toPath)
                ?: return@withContext Outcome.Error(
                    StorageFailure.AccessDenied(toPath, "محاولة نسخ إلى خارج مساحة العمل.")
                )
            val (_, sourceFile) = source
            val (_, destinationFile) = destination
            if (!sourceFile.exists() || !sourceFile.isFile) {
                return@withContext Outcome.Error(StorageFailure.FileNotFound(fromPath))
            }
            if (destinationFile.exists()) {
                return@withContext Outcome.Error(
                    StorageFailure.AccessDenied(toPath, "الوجهة موجودة بالفعل — رُفض النسخ بدل الكتابة فوقها.")
                )
            }
            destinationFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val copied = runCatching {
                sourceFile.inputStream().use { input ->
                    destinationFile.outputStream().use { output -> input.copyTo(output) }
                }
            }.isSuccess
            if (copied) Outcome.Success(Unit) else Outcome.Error(
                StorageFailure.ReadWriteError(fromPath, "فشل نسخ $fromPath إلى $toPath")
            )
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Outcome.Error(
                StorageFailure.ReadWriteError(fromPath, "فشل النسخ: ${e.localizedMessage}")
            )
        }
    }

    override suspend fun deleteFile(projectId: Long, relativePath: String): Outcome<Unit, StorageFailure> = withContext(Dispatchers.IO) {
        try {
            val projectDir = getProjectDir(projectId)
            val targetFile = File(projectDir, relativePath)

            if (!isContainedInProject(targetFile.canonicalPath, projectDir)) {
                return@withContext Outcome.Error(
                    StorageFailure.AccessDenied(relativePath, "محاولة حذف ملف خارج مساحة العمل.")
                )
            }

            if (!targetFile.exists()) {
                return@withContext Outcome.Error(StorageFailure.FileNotFound(relativePath))
            }

            val deleted = targetFile.deleteRecursively()
            if (deleted) Outcome.Success(Unit) else Outcome.Error(StorageFailure.ReadWriteError(relativePath, "فشل حذف $relativePath"))
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Outcome.Error(StorageFailure.ReadWriteError(relativePath, "استثناء أثناء الحذف: ${e.localizedMessage}"))
        }
    }

    override suspend fun fileExists(projectId: Long, relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val projectDir = getProjectDir(projectId)
        val targetFile = File(projectDir, relativePath)
        targetFile.exists() && isContainedInProject(targetFile.canonicalPath, projectDir)
    }
}
