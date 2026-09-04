package com.example.domain.core.provider

/**
 * ============================================================================
 * ServiceValidationResult + ServiceHealthRecord + ServiceHealthClassification
 * ============================================================================
 *
 * Result contract of an explicit connection test / resource validation
 * (Correction #4) and the persisted health snapshot (Correction #2).
 *
 * The classification is machine-readable so callers (control plane, UI, health
 * repositories) can map TIMEOUT / RATE_LIMITED / TRANSPORT failures to
 * DEGRADED instead of UNAVAILABLE without string matching.
 */
enum class ServiceHealthClassification {
    SUCCESS,
    AUTHENTICATION_FAILURE,
    RATE_LIMITED,
    TIMEOUT,
    TRANSPORT_FAILURE,
    PROTOCOL_FAILURE,
    UNKNOWN
}

data class ServiceValidationResult(
    val isSuccess: Boolean,
    val classification: ServiceHealthClassification,
    val latencyMs: Long,
    val message: String
) {
    companion object {
        fun success(latencyMs: Long, message: String) = ServiceValidationResult(
            isSuccess = true,
            classification = ServiceHealthClassification.SUCCESS,
            latencyMs = latencyMs,
            message = message
        )

        fun failure(
            classification: ServiceHealthClassification,
            latencyMs: Long,
            message: String
        ) = ServiceValidationResult(
            isSuccess = false,
            classification = classification,
            latencyMs = latencyMs,
            message = message
        )
    }
}

/**
 * Persisted health snapshot for a ServiceConfiguration at a point in time.
 * Stored via [com.example.domain.ports.provider.ServiceHealthRepository].
 */
data class ServiceHealthRecord(
    val id: String,
    val serviceConfigurationId: String,
    val healthStatus: HealthStatus,
    val lastHealthClassification: ServiceHealthClassification,
    val lastValidatedEpochMs: Long,
    val lastLatencyMs: Long,
    val lastErrorMessage: String? = null,
    val validatedAtEpochMs: Long = System.currentTimeMillis()
)
