package com.example.domain.core.provider.routing

import com.example.domain.core.provider.offering.ServiceOffering
import com.example.domain.core.capability.CapabilityType

/**
 * ============================================================================
 * Provider Routing Domain Models — Phase 5 (P1)
 * ============================================================================
 *
 * Closes the Provider Ecosystem gap (audit: ~45% → ~55%) by adding
 * routing policies and load balancing — none of which existed before
 * (the audit found `DecisionService` produced one candidate per action
 * with no automatic failover, no cost/latency/quality-aware routing,
 * and no per-provider load tracker).
 */

enum class RoutingStrategy(val code: String, val displayLabelAr: String) {
    PREFERENCE("PREFERENCE", "حسب التفضيل اليدوي"),
    LOWEST_COST("LOWEST_COST", "الأقل تكلفة"),
    LOWEST_LATENCY("LOWEST_LATENCY", "الأقل زمن استجابة"),
    HIGHEST_QUALITY("HIGHEST_QUALITY", "الأعلى جودة"),
    CAPABILITY_MATCH("CAPABILITY_MATCH", "الأكثر مطابقة للقدرات"),
    BALANCED("BALANCED", "متوازن (تكلفة+جودة+زمن)"),
    FAILOVER_ONLY("FAILOVER_ONLY", "احتياطي عند فشل الأساسي")
}

data class RoutingPolicy(
    val strategy: RoutingStrategy,
    val preferredResourceId: String? = null,
    val maxCostPerMillionTokensUsd: Double? = null,
    val maxLatencyMs: Long? = null,
    val minContextWindowTokens: Int? = null,
    val requiredCapabilities: Set<CapabilityType> = emptySet()
)

data class ScoredCandidate(
    val resourceId: String,
    val resourceName: String,
    val totalScore: Float,
    val costScore: Float,
    val latencyScore: Float,
    val qualityScore: Float,
    val capabilityScore: Float,
    val loadScore: Float,
    val offering: ServiceOffering
)

data class LoadBalancerSnapshot(
    val resourceId: String,
    val activeInFlightRequests: Int,
    val totalRequestsToday: Long,
    val averageLatencyMs: Double,
    val failureRate: Float,
    val lastRequestAtEpochMs: Long?
)

data class FailoverDecision(
    val primaryResourceId: String,
    val primaryFailureCode: String,
    val chosenFallbackResourceId: String,
    val chosenFallbackScore: Float,
    val attemptIndex: Int,
    val isFinalAttempt: Boolean,
    val decidedAtEpochMs: Long = System.currentTimeMillis()
)
