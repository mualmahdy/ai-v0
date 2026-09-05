package com.example.application.evolution

import com.example.domain.core.evolution.runtime.PolicyEvaluationReport
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Phase 5 — PolicyVersionService unit tests.
 *
 * Closes the test-coverage aspect of P5-P1-12 (Evolution/Self-Improvement):
 * proves regression detection and safe promotion logic work as designed.
 *
 * Note: the createVersion/promote/rollback paths require a Room DAO; we
 * test the pure-logic parts (detectRegression, decidePromotion) which
 * don't need persistence.
 */
class PolicyVersionServiceTest {

    private val service = PolicyVersionService(NoOpPolicyVersionDao())

    @Test
    fun `detectRegression returns positive score when candidate is better`() {
        val baseline = makeReport(taskSuiteSize = 10, successCount = 7, degradedCount = 2, failureCount = 1, averageReward = 0.6f, p95LatencyMs = 1000)
        val candidate = makeReport(taskSuiteSize = 10, successCount = 9, degradedCount = 1, failureCount = 0, averageReward = 0.85f, p95LatencyMs = 800)
        val score = service.detectRegression(baseline, candidate)
        assertTrue("Expected positive regression score for better candidate, got $score", score > 0f)
    }

    @Test
    fun `detectRegression returns negative score when candidate is worse`() {
        val baseline = makeReport(taskSuiteSize = 10, successCount = 9, degradedCount = 1, failureCount = 0, averageReward = 0.85f, p95LatencyMs = 800)
        val candidate = makeReport(taskSuiteSize = 10, successCount = 5, degradedCount = 3, failureCount = 2, averageReward = 0.3f, p95LatencyMs = 1500)
        val score = service.detectRegression(baseline, candidate)
        assertTrue("Expected negative regression score for worse candidate, got $score", score < 0f)
    }

    @Test
    fun `decidePromotion rejects candidate below minimum sample size`() {
        val candidate = makeReport(taskSuiteSize = 5, successCount = 5, degradedCount = 0, failureCount = 0, averageReward = 1.0f, p95LatencyMs = 100)
        val decision = service.decidePromotion(candidate, baseline = null, minTaskSuiteSize = 10, minSuccessRate = 0.7f)
        assertFalse(decision.isApproved)
        assertTrue(decision.conditions.any { it.contains("حجم عينة") })
    }

    @Test
    fun `decidePromotion rejects candidate below minimum success rate`() {
        val candidate = makeReport(taskSuiteSize = 10, successCount = 5, degradedCount = 3, failureCount = 2, averageReward = 0.4f, p95LatencyMs = 1000)
        val decision = service.decidePromotion(candidate, baseline = null, minTaskSuiteSize = 10, minSuccessRate = 0.7f)
        assertFalse(decision.isApproved)
        assertTrue(decision.conditions.any { it.contains("معدل النجاح") })
    }

    @Test
    fun `decidePromotion rejects candidate with significant regression vs baseline`() {
        val baseline = makeReport(taskSuiteSize = 10, successCount = 9, degradedCount = 1, failureCount = 0, averageReward = 0.9f, p95LatencyMs = 800)
        val candidate = makeReport(taskSuiteSize = 10, successCount = 5, degradedCount = 3, failureCount = 2, averageReward = 0.3f, p95LatencyMs = 1500)
        val decision = service.decidePromotion(candidate, baseline, minTaskSuiteSize = 10, minSuccessRate = 0.4f)
        assertFalse(decision.isApproved)
        assertTrue(decision.conditions.any { it.contains("انحدار") })
    }

    @Test
    fun `decidePromotion approves well-formed candidate with no regression`() {
        val baseline = makeReport(taskSuiteSize = 10, successCount = 7, degradedCount = 2, failureCount = 1, averageReward = 0.6f, p95LatencyMs = 1000)
        val candidate = makeReport(taskSuiteSize = 10, successCount = 9, degradedCount = 1, failureCount = 0, averageReward = 0.85f, p95LatencyMs = 800)
        val decision = service.decidePromotion(candidate, baseline, minTaskSuiteSize = 10, minSuccessRate = 0.7f)
        assertTrue(decision.isApproved)
        assertTrue(decision.conditions.isEmpty())
    }

    private fun makeReport(
        taskSuiteSize: Int,
        successCount: Int,
        degradedCount: Int,
        failureCount: Int,
        averageReward: Float,
        p95LatencyMs: Long
    ): PolicyEvaluationReport {
        return PolicyEvaluationReport(
            versionId = "test_version",
            taskSuiteSize = taskSuiteSize,
            successCount = successCount,
            degradedCount = degradedCount,
            failureCount = failureCount,
            averageReward = averageReward,
            p95LatencyMs = p95LatencyMs,
            totalTokensConsumed = 0L,
            regressionDetected = false,
            regressionScore = 0f,
            notes = "test"
        )
    }

    private class NoOpPolicyVersionDao : com.example.infrastructure.persistence.dao.PolicyVersionDao {
        override suspend fun activeFor(kind: String): com.example.infrastructure.persistence.entities.PolicyVersionEntity? = null
        override fun historyFor(kind: String): kotlinx.coroutines.flow.Flow<List<com.example.infrastructure.persistence.entities.PolicyVersionEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override fun allFlow(): kotlinx.coroutines.flow.Flow<List<com.example.infrastructure.persistence.entities.PolicyVersionEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun upsert(entity: com.example.infrastructure.persistence.entities.PolicyVersionEntity) {}
        override suspend fun demoteAll(kind: String) {}
        override suspend fun promote(id: String, actor: String, now: Long) {}
    }
}
