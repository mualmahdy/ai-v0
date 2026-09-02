package com.example.domain.ports.resource

/**
 * P0.8 — Observation state stores (APPROVED-BASELINE v2.1, Section I — LOCKED).
 *
 * ObservationService consumes ExecutionResult and produces classified observations.
 * Execution state and evidence are persisted to Room (Section I).
 */

/** Lifecycle of the execution itself (ExecutionStateEvent dimension). */
data class ExecutionStateRecord(
    val executionId: String,
    val decisionId: String,
    val taskId: String,
    val stepId: String,
    val resourceId: String,
    val outcome: String,
    val transportError: String?,
    val latencyMs: Long,
    val timestamp: Long
)

/** Evidence produced by an execution (EvidenceEvent dimension). */
data class EvidenceRecord(
    val evidenceId: String,
    val taskId: String,
    val stepId: String,
    val decisionId: String,
    val resourceId: String,
    val evidenceKeys: List<String>,
    val summary: String,
    val payloadJson: String,
    val createdAt: Long
)

/** Verification outcome dimension (verified/rejected per step). */
data class VerificationOutcomeRecord(
    val stepId: String,
    val taskId: String,
    val verified: Boolean,
    val confidence: Double,
    val summary: String,
    val createdAt: Long
)

/** Execution state store (Room in production). */
interface ExecutionStateStorePort {
    suspend fun save(record: ExecutionStateRecord)
    suspend fun get(executionId: String): ExecutionStateRecord?
    suspend fun getForTask(taskId: String): List<ExecutionStateRecord>
}

/** Evidence store (Room in production). */
interface EvidenceStorePort {
    suspend fun save(record: EvidenceRecord)
    suspend fun getForStep(stepId: String): List<EvidenceRecord>
    suspend fun getForTask(taskId: String): List<EvidenceRecord>
}

/** Verification outcome store (Room in production). */
interface VerificationOutcomeStorePort {
    suspend fun save(record: VerificationOutcomeRecord)
    suspend fun getForStep(stepId: String): List<VerificationOutcomeRecord>
}
