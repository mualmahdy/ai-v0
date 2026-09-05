package com.example.application.provider

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.provider.offering.ServiceOffering
import com.example.domain.core.provider.routing.FailoverDecision
import com.example.domain.core.provider.routing.LoadBalancerSnapshot
import com.example.domain.core.provider.routing.RoutingPolicy
import com.example.domain.core.provider.routing.RoutingStrategy
import com.example.domain.core.provider.routing.ScoredCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ============================================================================
 * ProviderRoutingService — Phase 5 Provider Intelligence (P1)
 * ============================================================================
 *
 * Closes the Provider Ecosystem gap (audit: ~45% → ~55%) by adding:
 *
 *   1. Routing policies — cost/latency/quality/capability-aware
 *      scoring of candidate resources (the audit found
 *      `DecisionService` produced one candidate per action with no
 *      scoring).
 *
 *   2. Dynamic load balancing — per-resource in-flight request
 *      counter; routing prefers resources with lower load.
 *
 *   3. Automatic failover — when the primary candidate fails, the
 *      next-best candidate is selected automatically.
 *
 *   4. Routing strategy selection — the caller picks a strategy
 *      (LOWEST_COST / LOWEST_LATENCY / etc.) and the service scores
 *      accordingly.
 */
class ProviderRoutingService {

    private val inFlightCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val totalRequestsToday = ConcurrentHashMap<String, AtomicLong>()
    private val failureCounts = ConcurrentHashMap<String, AtomicLong>()
    private val latencySamples = ConcurrentHashMap<String, MutableList<Long>>()
    private val lastRequestAt = ConcurrentHashMap<String, Long>()
    private val mutex = Mutex()

    private val _loadSnapshots = MutableStateFlow<Map<String, LoadBalancerSnapshot>>(emptyMap())
    val loadSnapshots: StateFlow<Map<String, LoadBalancerSnapshot>> = _loadSnapshots.asStateFlow()

    /**
     * Score and rank candidate resources according to a routing policy.
     * Returns candidates sorted by descending total score.
     */
    fun score(candidates: List<ServiceOffering>, policy: RoutingPolicy): List<ScoredCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val maxCost = candidates.maxOf { (it.pricingInputTokensPerMillion ?: 0.0) + (it.pricingOutputTokensPerMillion ?: 0.0) }.coerceAtLeast(0.01)
        val maxLatency = candidates.maxOf { it.latencyScoreMs ?: 1000L }.coerceAtLeast(1L)

        return candidates.map { offering ->
            val cost = (offering.pricingInputTokensPerMillion ?: 0.0) + (offering.pricingOutputTokensPerMillion ?: 0.0)
            val costScore = (1.0f - (cost.toFloat() / maxCost.toFloat())).coerceIn(0f, 1f)
            val latencyScore = (1.0f - ((offering.latencyScoreMs ?: maxLatency).toFloat() / maxLatency.toFloat())).coerceIn(0f, 1f)
            val qualityScore = computeQualityScore(offering)
            val capabilityScore = computeCapabilityScore(offering, policy.requiredCapabilities)
            val loadScore = computeLoadScore(offering.id)

            val totalScore = when (policy.strategy) {
                RoutingStrategy.LOWEST_COST -> costScore * 0.8f + loadScore * 0.2f
                RoutingStrategy.LOWEST_LATENCY -> latencyScore * 0.8f + loadScore * 0.2f
                RoutingStrategy.HIGHEST_QUALITY -> qualityScore * 0.8f + capabilityScore * 0.2f
                RoutingStrategy.CAPABILITY_MATCH -> capabilityScore * 0.7f + qualityScore * 0.3f
                RoutingStrategy.BALANCED ->
                    costScore * 0.25f + latencyScore * 0.25f + qualityScore * 0.25f + capabilityScore * 0.15f + loadScore * 0.1f
                RoutingStrategy.PREFERENCE -> if (offering.id == policy.preferredResourceId) 1.0f else 0.1f
                RoutingStrategy.FAILOVER_ONLY -> loadScore // cheapest available
            }

            ScoredCandidate(
                resourceId = offering.id,
                resourceName = offering.name,
                totalScore = totalScore,
                costScore = costScore,
                latencyScore = latencyScore,
                qualityScore = qualityScore,
                capabilityScore = capabilityScore,
                loadScore = loadScore,
                offering = offering
            )
        }.sortedByDescending { it.totalScore }
    }

    private fun computeQualityScore(offering: ServiceOffering): Float {
        // Quality heuristic: bigger context window + isLocal (deterministic) =
        // higher quality baseline. Real implementations would use historical
        // success rates from the TelemetryService.
        val contextScore = ((offering.contextWindowTokens ?: 8000).toFloat() / 128_000f).coerceIn(0f, 1f)
        val localBonus = if (offering.isLocal) 0.1f else 0f
        return (contextScore + localBonus).coerceIn(0f, 1f)
    }

    private fun computeCapabilityScore(offering: ServiceOffering, required: Set<CapabilityType>): Float {
        if (required.isEmpty()) return 1.0f
        val provided = offering.supportedCapabilities ?: emptySet()
        val matched = required.count { it in provided }
        return matched.toFloat() / required.size.toFloat()
    }

    private fun computeLoadScore(resourceId: String): Float {
        val inFlight = inFlightCounts[resourceId]?.get() ?: 0
        val samples = latencySamples[resourceId]?.toList() ?: emptyList()
        val avgLatency = if (samples.isNotEmpty()) samples.average() else 0.0
        val failures = failureCounts[resourceId]?.get() ?: 0L
        val total = totalRequestsToday[resourceId]?.get() ?: 0L
        val failureRate = if (total > 0) failures.toFloat() / total.toFloat() else 0f
        // Lower load = higher score.
        val loadPenalty = (inFlight.toFloat() * 0.1f) + (failureRate * 0.5f) + (avgLatency.toFloat() / 100_000f)
        return (1.0f - loadPenalty).coerceIn(0f, 1f)
    }

    /**
     * Pick the next-best fallback candidate after a primary failure.
     * Skips the failed resource; returns null if no fallback remains.
     */
    fun selectFallback(
        allCandidates: List<ServiceOffering>,
        policy: RoutingPolicy,
        failedResourceIds: Set<String>,
        attemptIndex: Int
    ): FailoverDecision? {
        val remaining = allCandidates.filter { it.id !in failedResourceIds }
        if (remaining.isEmpty()) return null
        val scored = score(remaining, policy.copy(strategy = RoutingStrategy.BALANCED))
        val best = scored.firstOrNull() ?: return null
        val isFinal = attemptIndex >= allCandidates.size - 1
        return FailoverDecision(
            primaryResourceId = failedResourceIds.firstOrNull() ?: "",
            primaryFailureCode = "PRIMARY_FAILED",
            chosenFallbackResourceId = best.resourceId,
            chosenFallbackScore = best.totalScore,
            attemptIndex = attemptIndex,
            isFinalAttempt = isFinal
        )
    }

    /**
     * Increment the in-flight counter for a resource. Called when a
     * request starts.
     */
    suspend fun incrementInFlight(resourceId: String) = mutex.withLock {
        inFlightCounts.computeIfAbsent(resourceId) { AtomicInteger(0) }.incrementAndGet()
        totalRequestsToday.computeIfAbsent(resourceId) { AtomicLong(0) }.incrementAndGet()
        lastRequestAt[resourceId] = System.currentTimeMillis()
        publishSnapshot(resourceId)
        Unit
    }

    /**
     * Decrement the in-flight counter and record the outcome.
     */
    suspend fun decrementInFlight(resourceId: String, latencyMs: Long, isFailure: Boolean) = mutex.withLock {
        inFlightCounts[resourceId]?.decrementAndGet()
        val samples = latencySamples.computeIfAbsent(resourceId) { mutableListOf() }
        synchronized(samples) {
            samples.add(latencyMs)
            if (samples.size > 256) samples.removeAt(0)
        }
        if (isFailure) failureCounts.computeIfAbsent(resourceId) { AtomicLong(0) }.incrementAndGet()
        publishSnapshot(resourceId)
        Unit
    }

    private fun publishSnapshot(resourceId: String) {
        val current = _loadSnapshots.value.toMutableMap()
        val inFlight = inFlightCounts[resourceId]?.get() ?: 0
        val total = totalRequestsToday[resourceId]?.get() ?: 0L
        val failures = failureCounts[resourceId]?.get() ?: 0L
        val samples = latencySamples[resourceId]?.toList() ?: emptyList()
        val avgLatency = if (samples.isNotEmpty()) samples.average() else 0.0
        val failureRate = if (total > 0) failures.toFloat() / total.toFloat() else 0f
        current[resourceId] = LoadBalancerSnapshot(
            resourceId = resourceId,
            activeInFlightRequests = inFlight,
            totalRequestsToday = total,
            averageLatencyMs = avgLatency,
            failureRate = failureRate,
            lastRequestAtEpochMs = lastRequestAt[resourceId]
        )
        _loadSnapshots.value = current
    }
}
