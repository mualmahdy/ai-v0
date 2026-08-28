package com.example.infrastructure.persistence.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val description: String?,
    val rootPath: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val isArchived: Boolean = false
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val sessionId: String,
    val projectId: Long,
    val title: String,
    val assignedAgentId: String,
    val activeModelId: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val totalTokensConsumed: Int = 0
)

@Entity(tableName = "memory_records")
data class MemoryEntity(
    @PrimaryKey
    val id: String,
    val text: String,
    val vectorDimension: Int,
    val vectorJson: String, // Normalized float array serialized as JSON
    val source: String,
    val confidence: Float,
    val createdAtEpochMs: Long,
    val lastAccessedEpochMs: Long,
    val accessCount: Int = 1,
    val isArchived: Boolean = false
)

@Entity(tableName = "execution_logs")
data class ExecutionLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val executionId: String,
    val sessionId: String,
    val eventType: String,
    val payloadJson: String,
    val timestampEpochMs: Long
)
