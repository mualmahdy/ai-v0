package com.example.application.usecases

import com.example.domain.core.Outcome
import com.example.domain.core.storage.StorageFailure
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.domain.ports.storage.WorkspaceStoragePort

/**
 * High-level Use Case: Secure file management within project workspaces.
 */
class ManageWorkspaceFilesUseCase(
    private val storagePort: WorkspaceStoragePort
) {

    suspend fun listProjectFiles(projectId: Long, subDir: String? = null): Outcome<List<WorkspaceFileEntry>, StorageFailure> {
        return storagePort.listFiles(projectId, subDir)
    }

    suspend fun readProjectFile(projectId: Long, relativePath: String): Outcome<String, StorageFailure> {
        return storagePort.readFile(projectId, relativePath)
    }

    suspend fun writeProjectFile(projectId: Long, relativePath: String, content: String): Outcome<Unit, StorageFailure> {
        return storagePort.writeFile(projectId, relativePath, content)
    }

    suspend fun deleteProjectFile(projectId: Long, relativePath: String): Outcome<Unit, StorageFailure> {
        return storagePort.deleteFile(projectId, relativePath)
    }
}
