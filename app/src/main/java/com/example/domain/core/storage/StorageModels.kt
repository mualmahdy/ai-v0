package com.example.domain.core.storage

/**
 * Metadata representation of a file in the workspace directory.
 */
data class WorkspaceFileEntry(
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModifiedMs: Long = System.currentTimeMillis()
)

/**
 * High-level project metadata.
 */
data class ProjectMetadata(
    val id: Long,
    val name: String,
    val description: String?,
    val isDefault: Boolean = false,
    val createdAtTimestampMs: Long = System.currentTimeMillis()
)

/**
 * Persistent session summary.
 */
data class WorkspaceSessionInfo(
    val sessionId: String,
    val projectId: Long,
    val title: String,
    val messageCount: Int,
    val totalTokensConsumed: Int,
    val lastUpdatedTimestampMs: Long = System.currentTimeMillis()
)

/**
 * Storage and file system failures.
 */
sealed interface StorageFailure {
    data class FileNotFound(val path: String) : StorageFailure
    data class AccessDenied(val path: String, val reason: String) : StorageFailure
    data class DiskSpaceExceeded(val requiredBytes: Long, val availableBytes: Long) : StorageFailure
    data class ReadWriteError(val path: String, val message: String) : StorageFailure
    data class CorruptedData(val path: String, val message: String) : StorageFailure
}
