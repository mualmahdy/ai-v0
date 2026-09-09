package com.example.infrastructure.storage

import android.content.Context
import com.example.domain.core.Outcome
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
            Outcome.Error(
                StorageFailure.ReadWriteError(subDirectory ?: "", "فشل سرد الملفات: ${e.localizedMessage}")
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
            Outcome.Error(StorageFailure.ReadWriteError(relativePath, "استثناء أثناء الحذف: ${e.localizedMessage}"))
        }
    }

    override suspend fun fileExists(projectId: Long, relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val projectDir = getProjectDir(projectId)
        val targetFile = File(projectDir, relativePath)
        targetFile.exists() && isContainedInProject(targetFile.canonicalPath, projectDir)
    }
}
