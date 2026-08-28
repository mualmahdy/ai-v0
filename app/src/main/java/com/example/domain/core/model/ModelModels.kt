package com.example.domain.core.model

import com.example.domain.core.provider.HealthStatus

/**
 * Modalities supported by models.
 */
enum class Modality {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
    EMBEDDING
}

/**
 * Tri-state boolean representation where UNKNOWN is distinct from TRUE and FALSE.
 */
enum class TriStateCapability {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN
}

/**
 * Normalized model descriptor representing capabilities discovered or registered in AI-V0 Platform.
 */
data class ModelDescriptor(
    val id: String,
    val providerId: String,
    val name: String,
    val version: String,
    val contextWindowTokens: Int?, // null if unknown
    val maxOutputTokens: Int?,
    val inputModalities: Set<Modality> = setOf(Modality.TEXT),
    val outputModalities: Set<Modality> = setOf(Modality.TEXT),
    val supportsReasoning: TriStateCapability = TriStateCapability.UNKNOWN,
    val supportsVision: TriStateCapability = TriStateCapability.UNKNOWN,
    val supportsToolCalling: TriStateCapability = TriStateCapability.UNKNOWN,
    val supportsStructuredOutput: TriStateCapability = TriStateCapability.UNKNOWN,
    val supportsStreaming: TriStateCapability = TriStateCapability.SUPPORTED,
    val isLocalOnDevice: Boolean = false,
    val health: HealthStatus = HealthStatus.UNKNOWN,
    val estimatedCostPer1kTokensUsd: Double? = null,
    val averageLatencyMs: Long? = null,
    val discoverySource: String = "AUTOMATIC_DISCOVERY",
    val confidence: Float = 0.95f,
    val lastDiscoveredTimestampMs: Long = System.currentTimeMillis()
)
