package com.example.domain.ports.storage

import com.example.domain.core.Outcome
import com.example.domain.core.storage.ProjectMetadata
import com.example.domain.core.storage.StorageFailure
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.domain.core.storage.WorkspaceSessionInfo

/**
 * Standard Port for Project & Workspace File Storage.
 *
 * Operates strictly within the isolated Android sandbox directories.
 */
interface WorkspaceStoragePort {
    /**
     * Reads file content as a string.
     */
    suspend fun readFile(projectId: Long, relativePath: String): Outcome<String, StorageFailure>

    /**
     * Writes or overwrites file content.
     */
    suspend fun writeFile(projectId: Long, relativePath: String, content: String): Outcome<Unit, StorageFailure>

    /**
     * Lists all files under the given project directory.
     */
    suspend fun listFiles(projectId: Long, subDirectory: String? = null): Outcome<List<WorkspaceFileEntry>, StorageFailure>

    /**
     * Deletes a file or directory.
     */
    suspend fun deleteFile(projectId: Long, relativePath: String): Outcome<Unit, StorageFailure>

    /**
     * Checks if a file exists.
     */
    suspend fun fileExists(projectId: Long, relativePath: String): Boolean
}

/**
 * Standard Port for Persistence of Workspace Sessions and Projects.
 */
interface SessionRepositoryPort {
    suspend fun getActiveProject(): Outcome<ProjectMetadata, StorageFailure>
    suspend fun listProjects(): Outcome<List<ProjectMetadata>, StorageFailure>
    suspend fun createProject(name: String, description: String?): Outcome<ProjectMetadata, StorageFailure>
    suspend fun listSessions(projectId: Long): Outcome<List<WorkspaceSessionInfo>, StorageFailure>
    suspend fun saveSession(session: WorkspaceSessionInfo): Outcome<Unit, StorageFailure>
}
