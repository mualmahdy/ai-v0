package com.example.domain.core.resource

/**
 * P0.3 — ResourceHealth (APPROVED-BASELINE v2.1, Section G — LOCKED, corrected
 * transcription per the document).
 *
 * Health measures TRANSPORT/RUNTIME metrics ONLY (Review Point 5):
 * measured    -> HTTP status codes, connection errors, timeouts, auth transport
 *                errors, latency, repeated transport failure patterns, probes.
 * NOT measured -> semantic correctness of AI output, whether the answer addresses
 *                the task, evidence quality, verification confidence, task
 *                success/failure semantics (those belong to Verification).
 *
 * RULE RH-1: HTTP 200 + semantically wrong answer = healthy resource + failed
 *            verification. Separate observations feeding separate state.
 * RULE RH-2: Health data is one INPUT to candidate evaluation; it does not gate
 *            execution by itself.
 */
data class ResourceHealth(
    val resourceId: ResourceId,
    /** Sliding-window success ratio over the last N transport outcomes (0.0–1.0). */
    val successRate: Double,
    /** Sliding-window mean transport round-trip latency in milliseconds. */
    val averageLatencyMs: Long,
    /** Sliding-window 95th percentile latency in milliseconds. */
    val p95LatencyMs: Long,
    /** Ratio of timeout failures within the sliding window (0.0–1.0). */
    val timeoutRate: Double,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val lastFailureReason: String?,
    /** Timestamp until which the resource is in cooldown; null = not in cooldown. */
    val inCooldownUntil: Long?,
    /** Number of observations currently inside the sliding window. */
    val sampleSize: Int,
    /** Derived deterministic score in 0.0–1.0 (formula documented on the service impl). */
    val healthScore: Double
) {
    val isInCooldown: Boolean
        get() = inCooldownUntil != null && inCooldownUntil > 0L
}
