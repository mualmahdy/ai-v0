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
 *
 * FIX APP-P0-05: Added assignedAgentId, activeModelId, createdAtTimestampMs fields so
 * saveSession/listSessions can round-trip the real agent+model used during the session
 * instead of hardcoding "default_orchestrator" / "gemini-2.5-flash" on read-back.
 * messageCount is now optional (defaults to 0) since callers may not always know it.
 */
data class WorkspaceSessionInfo(
    val sessionId: String,
    val projectId: Long,
    val title: String,
    val messageCount: Int = 0,
    val totalTokensConsumed: Int,
    val lastUpdatedTimestampMs: Long = System.currentTimeMillis(),
    val createdAtTimestampMs: Long = lastUpdatedTimestampMs,
    val assignedAgentId: String = "",
    val activeModelId: String = ""
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
