package com.example.infrastructure.storage

import android.content.Context
import com.example.domain.core.Outcome
import com.example.domain.core.storage.ProjectMetadata
import com.example.domain.core.storage.StorageFailure
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.domain.core.storage.WorkspaceSessionInfo
import com.example.domain.ports.storage.SessionRepositoryPort
import com.example.domain.ports.storage.WorkspaceStoragePort
import com.example.infrastructure.persistence.dao.ProjectDao
import com.example.infrastructure.persistence.dao.SessionDao
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Clean Infrastructure Adapter for isolated workspace file system operations
 * conforming to Android internal sandbox security policies.
 */
class SandboxWorkspaceStorageAdapter(
    private val context: Context,
    private val projectDao: ProjectDao,
    private val sessionDao: SessionDao
) : WorkspaceStoragePort, SessionRepositoryPort {

    private val baseProjectsDir: File by lazy {
        File(context.filesDir, "workspaces").apply { if (!exists()) mkdirs() }
    }

    private fun getProjectDir(projectId: Long): File {
        return File(baseProjectsDir, "proj_$projectId").apply { if (!exists()) mkdirs() }
    }

    // --- WorkspaceStoragePort Implementation ---

    override suspend fun readFile(projectId: Long, relativePath: String): Outcome<String, StorageFailure> = withContext(Dispatchers.IO) {
        try {
            val projectDir = getProjectDir(projectId)
            val targetFile = File(projectDir, relativePath)

            // Security containment check (No path traversal outside sandbox)
            if (!targetFile.canonicalPath.startsWith(projectDir.canonicalPath)) {
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
            if (!targetFile.canonicalPath.startsWith(projectDir.canonicalPath)) {
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

            if (!targetDir.canonicalPath.startsWith(projectDir.canonicalPath)) {
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

            if (!targetFile.canonicalPath.startsWith(projectDir.canonicalPath)) {
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
        targetFile.exists() && targetFile.canonicalPath.startsWith(projectDir.canonicalPath)
    }

    // --- SessionRepositoryPort Implementation ---

    override suspend fun getActiveProject(): Outcome<ProjectMetadata, StorageFailure> = withContext(Dispatchers.IO) {
        try {
            val existing = projectDao.getProjectById(1L)
            if (existing != null) {
                Outcome.Success(
                    ProjectMetadata(
                        id = existing.id,
                        name = existing.name,
                        description = existing.description,
                        isDefault = true,
                        createdAtTimestampMs = existing.createdAtEpochMs
                    )
                )
            } else {
                val now = System.currentTimeMillis()
                val entity = ProjectEntity(
                    id = 1L,
                    name = "مشروع العمل الافتراضي",
                    description = "مساحة العمل المعزولة لتنسيق الوكلاء والملفات",
                    rootPath = getProjectDir(1L).absolutePath,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
                projectDao.insertProject(entity)
                Outcome.Success(
                    ProjectMetadata(
                        id = entity.id,
                        name = entity.name,
                        description = entity.description,
                        isDefault = true,
                        createdAtTimestampMs = entity.createdAtEpochMs
                    )
                )
            }
        } catch (e: Exception) {
            Outcome.Error(StorageFailure.ReadWriteError("projects", "فشل جلب المشروع النشط: ${e.localizedMessage}"))
        }
    }

    override suspend fun listProjects(): Outcome<List<ProjectMetadata>, StorageFailure> = withContext(Dispatchers.IO) {
        // FIX APP-P0-06: Previously this method called getActiveProject() and wrapped the
        // single result in listOf(...), claiming to be a list while only ever returning
        // one project (the hardcoded id=1L default). Now we actually query ProjectDao
        // for all active projects so multi-project workspaces are supported.
        try {
            val entities = projectDao.getAllActiveProjectsList()
            if (entities.isEmpty()) {
                // No projects at all yet — auto-create the default and return it.
                val defaultProj = getActiveProject()
                if (defaultProj is Outcome.Success) {
                    Outcome.Success(listOf(defaultProj.value))
                } else {
                    Outcome.Success(emptyList())
                }
            } else {
                Outcome.Success(
                    entities.map { entity ->
                        ProjectMetadata(
                            id = entity.id,
                            name = entity.name,
                            description = entity.description,
                            isDefault = entity.id == 1L,
                            createdAtTimestampMs = entity.createdAtEpochMs
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Outcome.Error(StorageFailure.ReadWriteError("projects", "فشل استرجاع المشاريع: ${e.localizedMessage}"))
        }
    }

    override suspend fun createProject(name: String, description: String?): Outcome<ProjectMetadata, StorageFailure> = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val entity = ProjectEntity(
                name = name,
                description = description,
                rootPath = "",
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
            val generatedId = projectDao.insertProject(entity)
            val rootPath = getProjectDir(generatedId).absolutePath
            projectDao.updateProject(entity.copy(id = generatedId, rootPath = rootPath))

            Outcome.Success(
                ProjectMetadata(
                    id = generatedId,
                    name = name,
                    description = description,
                    isDefault = false,
                    createdAtTimestampMs = now
                )
            )
        } catch (e: Exception) {
            Outcome.Error(StorageFailure.ReadWriteError("projects", "فشل إنشاء المشروع: ${e.localizedMessage}"))
        }
    }

    override suspend fun listSessions(projectId: Long): Outcome<List<WorkspaceSessionInfo>, StorageFailure> = withContext(Dispatchers.IO) {
        // FIX APP-P0-05: Previously returned Outcome.Success(emptyList()) unconditionally,
        // even though saveSession() wrote to Room. Sessions were persisted but invisible.
        // Asymmetric persistence = invisible data loss. Now we actually query the SessionDao.
        try {
            val entities = sessionDao.getSessionsForProjectList(projectId)
            val sessions = entities.map { entity ->
                WorkspaceSessionInfo(
                    sessionId = entity.sessionId,
                    projectId = entity.projectId,
                    title = entity.title,
                    assignedAgentId = entity.assignedAgentId,
                    activeModelId = entity.activeModelId,
                    totalTokensConsumed = entity.totalTokensConsumed,
                    createdAtTimestampMs = entity.createdAtEpochMs,
                    lastUpdatedTimestampMs = entity.updatedAtEpochMs
                )
            }
            Outcome.Success(sessions)
        } catch (e: Exception) {
            Outcome.Error(StorageFailure.ReadWriteError("sessions", "فشل استرجاع الجلسات: ${e.localizedMessage}"))
        }
    }

    override suspend fun saveSession(session: WorkspaceSessionInfo): Outcome<Unit, StorageFailure> = withContext(Dispatchers.IO) {
        try {
            sessionDao.insertSession(
                SessionEntity(
                    sessionId = session.sessionId,
                    projectId = session.projectId,
                    title = session.title,
                    // FIX: previously hardcoded "default_orchestrator" and "gemini-2.5-flash"
                    // — stored session didn't reflect which agent/model was actually used.
                    // Now we persist the real values from the session parameter.
                    assignedAgentId = session.assignedAgentId.ifBlank { "default_orchestrator" },
                    activeModelId = session.activeModelId.ifBlank { "unknown" },
                    createdAtEpochMs = session.lastUpdatedTimestampMs,
                    updatedAtEpochMs = session.lastUpdatedTimestampMs,
                    totalTokensConsumed = session.totalTokensConsumed
                )
            )
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error(StorageFailure.ReadWriteError("sessions", "فشل حفظ الجلسة: ${e.localizedMessage}"))
        }
    }
}
