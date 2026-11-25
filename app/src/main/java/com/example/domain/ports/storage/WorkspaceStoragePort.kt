package com.example.domain.ports.storage

import com.example.domain.core.Outcome
import com.example.domain.core.storage.StorageFailure
import com.example.domain.core.storage.WorkspaceFileEntry

/**
 * Standard Port for Project & Workspace File Storage.
 *
 * Operates strictly within the isolated Android sandbox directories.
 *
 * P0 CONVERGENCE (audit step 12 §6): the `projectId` parameter is the
 * sandbox-root key of the OWNING WORKSPACE's project — resolved by callers
 * from the workspace's bound project (never an implicit shared project).
 * The port is deliberately project-keyed at the storage layer (the project
 * IS the on-disk sandbox root, `workspaces/proj_<id>`); ownership authority
 * lives one level up: Workspace → Project → files. `SessionRepositoryPort`
 * (the project-scoped session/project legacy surface with zero production
 * callers and the implicit 1L bootstrap) was REMOVED as dead architecture
 * residue — see its removal note in the git history.
 */
interface WorkspaceStoragePort {
    /**
     * Reads file content as a string.
     */
    suspend fun readFile(projectId: Long, relativePath: String): Outcome<String, StorageFailure>

    /**
     * FIW Stage 2 — type-aware binary read: the file's REAL bytes.
     * `readFile` decodes UTF-8 (a lossy corruption for images/PDF/binary);
     * this is the honest reader for every non-textual consumer (previews,
     * exports, byte-exact moves). Same containment contract as readFile.
     */
    suspend fun readFileBytes(projectId: Long, relativePath: String): Outcome<ByteArray, StorageFailure>

    /**
     * Writes or overwrites file content.
     */
    suspend fun writeFile(projectId: Long, relativePath: String, content: String): Outcome<Unit, StorageFailure>

    /**
     * FIW Stage 2 — byte-exact MOVE/rename inside the project sandbox
     * (never a read-text→write→delete round trip, which silently corrupted
     * binary files). The destination must not exist (explicit rejection
     * instead of an overwrite).
     */
    suspend fun moveFile(projectId: Long, fromPath: String, toPath: String): Outcome<Unit, StorageFailure>

    /**
     * FIW Stage 2 — byte-exact COPY inside the project sandbox. The
     * destination must not exist (explicit rejection instead of an
     * overwrite).
     */
    suspend fun copyFile(projectId: Long, fromPath: String, toPath: String): Outcome<Unit, StorageFailure>

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
