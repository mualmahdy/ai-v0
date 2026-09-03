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
 *
 * FIX DOM-P2-24: Previously defaulted to `discoverySource = "AUTOMATIC_DISCOVERY"` and
 * `confidence = 0.95f`. A descriptor that wasn't actually discovered shouldn't claim
 * "AUTOMATIC_DISCOVERY", and 0.95 confidence without measurement was synthetic. Now:
 *   - discoverySource = "UNSPECIFIED" (caller must set the real source)
 *   - confidence = 0.0f (caller must set based on real measurement)
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
    val discoverySource: String = "UNSPECIFIED",
    val confidence: Float = 0.0f,
    val lastDiscoveredTimestampMs: Long = System.currentTimeMillis()
)
