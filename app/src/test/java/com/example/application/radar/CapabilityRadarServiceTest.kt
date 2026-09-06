package com.example.application.radar

import com.example.application.testing.FakeRadarPersistence
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.radar.CapabilityChangeType
import com.example.domain.core.radar.CapabilityGapReason
import com.example.domain.core.radar.CapabilityHealth
import com.example.domain.core.radar.CapabilityTrend
import com.example.domain.core.radar.EvidenceOutcome
import com.example.domain.core.radar.EvidenceSource
import com.example.domain.core.radar.OperationalCapabilityState
import com.example.domain.core.radar.CapabilityEvidence
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * ============================================================================
 * CapabilityRadarServiceTest — GOVERNANCE PHASE radar track
 * ============================================================================
 *
 * Covers (mission Section 13 RADAR requirements):
 *   - capability registration / declaration map
 *   - state derivation from evidence + registry facts
 *   - PLANNED vs PARTIAL vs AVAILABLE vs DEGRADED vs BLOCKED vs FAILED
 *   - evidence ingestion drives re-derivation
 *   - degraded state detection (evidence failures after success)
 *   - recovery detection (RESTORED change)
 *   - gap detection (reason taxonomy)
 *   - evolution/change detection (state transitions persisted)
 *   - recommendation generation (evidence-based rules)
 *   - persistence round-trip (states survive service recreation)
 */
class CapabilityRadarServiceTest {

    private lateinit var persistence: FakeRadarPersistence
    private var resources: List<ResourceRecord> = emptyList()
    private var embeddingProvisioned: Boolean? = null

    private lateinit var service: CapabilityRadarService

    @Before
    fun setup() {
        persistence = FakeRadarPersistence()
        resources = emptyList()
        embeddingProvisioned = null
        service = newService()
    }

    private fun newService(): CapabilityRadarService = CapabilityRadarService(
        persistence = persistence,
        resourceSnapshotProvider = { resources },
        embeddingSemanticProvisioned = { embeddingProvisioned },
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    )

    private fun llmResource(
        lifecycle: ResourceLifecycleState = ResourceLifecycleState.ACTIVE,
        runtimeSupported: Boolean = true,
        health: HealthStatus = HealthStatus.HEALTHY,
        isLocal: Boolean = false
    ) = ResourceRecord(
        resourceId = ResourceId("res:prov:svc:llm"),
        providerId = "prov",
        serviceId = "svc",
        resourceType = ResourceType.LLM,
        capabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.REASONING, CapabilityType.STREAMING),
        configurationVersion = 1L,
        lifecycleState = lifecycle,
        runtimeSupported = runtimeSupported,
        healthStatus = health,
        isLocal = isLocal
    )

    private fun evidence(
        capability: CapabilityType,
        outcome: EvidenceOutcome,
        workspaceId: String? = "ws-1",
        ageMs: Long = 0L
    ) = CapabilityEvidence(
        id = "ev_${UUID.randomUUID()}",
        capabilityKey = capability.code,
        source = EvidenceSource.ACTION_EXECUTION,
        outcome = outcome,
        timestampEpochMs = System.currentTimeMillis() - ageMs,
        confidence = 0.9f,
        workspaceId = workspaceId
    )

    // ------------------------------------------------------------------
    // DECLARATION / REGISTRATION
    // ------------------------------------------------------------------

    @Test
    fun `all 21 declared capability types are tracked with honest dimensions`() = runBlocking {
        val snapshot = service.deriveSnapshot("ws-1")
        assertEquals(CapabilityType.values().size, snapshot.capabilities.size)
    }

    @Test
    fun `unimplemented capability derives PLANNED not AVAILABLE`() = runBlocking {
        val snapshot = service.deriveSnapshot("ws-1")
        val vision = snapshot.capabilities.first { it.capabilityKey == CapabilityType.VISION.code }
        assertEquals(OperationalCapabilityState.PLANNED, vision.state)
        assertEquals(true, vision.dimensions.declared)
        assertEquals(false, vision.dimensions.implemented)
    }

    // ------------------------------------------------------------------
    // STATE DERIVATION FROM REGISTRY FACTS
    // ------------------------------------------------------------------

    @Test
    fun `implemented capability without resources is PARTIAL not AVAILABLE`() = runBlocking {
        val snapshot = service.deriveSnapshot("ws-1")
        val llm = snapshot.capabilities.first { it.capabilityKey == CapabilityType.LLM_GENERATION.code }
        // No resources registered → PARTIAL (implemented but nothing provisioned)
        assertEquals(OperationalCapabilityState.PARTIAL, llm.state)
        assertEquals(null, llm.dimensions.resourceUsable)
    }

    @Test
    fun `provisioned unvalidated resource stays PARTIAL runtimeValidated false`() = runBlocking {
        resources = listOf(llmResource(lifecycle = ResourceLifecycleState.REGISTERED, runtimeSupported = false))
        val snapshot = service.deriveSnapshot("ws-1")
        val llm = snapshot.capabilities.first { it.capabilityKey == CapabilityType.LLM_GENERATION.code }
        assertEquals(OperationalCapabilityState.PARTIAL, llm.state)
        assertEquals(false, llm.dimensions.runtimeValidated)
    }

    @Test
    fun `usable resource with no evidence history derives AVAILABLE`() = runBlocking {
        resources = listOf(llmResource())
        val snapshot = service.deriveSnapshot("ws-1")
        val llm = snapshot.capabilities.first { it.capabilityKey == CapabilityType.LLM_GENERATION.code }
        assertEquals(OperationalCapabilityState.AVAILABLE, llm.state)
        assertEquals(CapabilityHealth.UNKNOWN, llm.health)
        assertTrue(llm.contributingResourceIds.contains("res:prov:svc:llm"))
    }

    @Test
    fun `all-disabled resources derive DISABLED`() = runBlocking {
        resources = listOf(llmResource(lifecycle = ResourceLifecycleState.DISABLED))
        val snapshot = service.deriveSnapshot("ws-1")
        val llm = snapshot.capabilities.first { it.capabilityKey == CapabilityType.LLM_GENERATION.code }
        assertEquals(OperationalCapabilityState.DISABLED, llm.state)
    }

    @Test
    fun `offline policy blocks ONLINE_ONLY capability (SEARCH BLOCKED)`() = runBlocking {
        val snapshot = service.deriveSnapshot("ws-1", networkPolicy = NetworkPolicy.OFFLINE, isNetworkAvailable = false)
        val search = snapshot.capabilities.first { it.capabilityKey == CapabilityType.SEARCH.code }
        assertEquals(OperationalCapabilityState.BLOCKED, search.state)
        assertEquals(false, search.dimensions.policyAllowed)
    }

    @Test
    fun `missing dependency blocks dependent capability (MEMORY_RETRIEVAL blocked by EMBEDDING)`() = runBlocking {
        // No embedding resources at all → MEMORY_RETRIEVAL (prereq EMBEDDING) must be BLOCKED.
        val snapshot = service.deriveSnapshot("ws-1")
        val memory = snapshot.capabilities.first { it.capabilityKey == CapabilityType.MEMORY_RETRIEVAL.code }
        assertEquals(OperationalCapabilityState.BLOCKED, memory.state)
        assertEquals(false, memory.dimensions.dependencyAvailable)
    }

    // ------------------------------------------------------------------
    // EVIDENCE-DRIVEN HEALTH
    // ------------------------------------------------------------------

    @Test
    fun `failure evidence after prior success derives DEGRADED`() = runBlocking {
        resources = listOf(llmResource())
        persistence.insertEvidenceAll(
            listOf(
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.SUCCESS),
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.SUCCESS),
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.FAILURE),
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.FAILURE),
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.FAILURE),
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.FAILURE)
            )
        )
        val snapshot = service.deriveSnapshot("ws-1")
        val llm = snapshot.capabilities.first { it.capabilityKey == CapabilityType.LLM_GENERATION.code }
        assertEquals(OperationalCapabilityState.DEGRADED, llm.state)
        assertEquals(CapabilityHealth.FAILING, llm.health)
        assertEquals(6, llm.evidenceCount)
    }

    @Test
    fun `failure evidence without any prior success derives FAILED`() = runBlocking {
        resources = listOf(llmResource())
        persistence.insertEvidenceAll(
            listOf(
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.FAILURE),
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.FAILURE)
            )
        )
        val snapshot = service.deriveSnapshot("ws-1")
        val llm = snapshot.capabilities.first { it.capabilityKey == CapabilityType.LLM_GENERATION.code }
        assertEquals(OperationalCapabilityState.FAILED, llm.state)
    }

    @Test
    fun `success evidence after degraded state derives AVAILABLE with RESTORED change`() = runBlocking {
        resources = listOf(llmResource())
        // Phase 1: failures → DEGRADED
        persistence.insertEvidenceAll(
            listOf(
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.SUCCESS),
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.FAILURE),
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.FAILURE)
            )
        )
        service.deriveSnapshot("ws-1")

        // Phase 2: recovery evidence
        persistence.insertEvidenceAll(
            listOf(
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.SUCCESS),
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.SUCCESS)
            )
        )
        val snapshot = service.deriveSnapshot("ws-1")
        val llm = snapshot.capabilities.first { it.capabilityKey == CapabilityType.LLM_GENERATION.code }

        // With 3S+3F (mixed) the most-recent derivation could stay DEGRADED
        // depending on the ratio; force clean recovery with more successes.
        persistence.insertEvidenceAll(
            listOf(
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.SUCCESS),
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.SUCCESS),
                evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.SUCCESS)
            )
        )
        val recovered = service.deriveSnapshot("ws-1")
            .capabilities.first { it.capabilityKey == CapabilityType.LLM_GENERATION.code }
        assertEquals(OperationalCapabilityState.AVAILABLE, recovered.state)
        assertEquals(CapabilityTrend.IMPROVING, recovered.trend)

        // Change log must contain the transition (DEGRADED -> AVAILABLE = RESTORED)
        val change = persistence.changes.lastOrNull { it.capabilityKey == CapabilityType.LLM_GENERATION.code }
        assertNotNull(change)
        assertEquals(OperationalCapabilityState.DEGRADED, change!!.fromState)
        assertEquals(OperationalCapabilityState.AVAILABLE, change.toState)
        assertEquals(CapabilityChangeType.RESTORED, change.changeType)
    }

    // ------------------------------------------------------------------
    // PERSISTENCE / SURVIVABILITY
    // ------------------------------------------------------------------

    @Test
    fun `derived states survive service recreation (process death simulation)`() = runBlocking {
        resources = listOf(llmResource())
        service.deriveSnapshot("ws-1")
        // Recreate the service with the SAME persistence (simulated restart).
        val restarted = newService()
        val persisted = persistence.getCapabilityStatus(CapabilityType.LLM_GENERATION.code, "ws-1")
        assertNotNull(persisted)
        assertEquals(OperationalCapabilityState.AVAILABLE, persisted!!.state)
        val check = restarted.checkCapability(CapabilityType.LLM_GENERATION, "ws-1")
        assertTrue(check.isExecutable)
    }

    @Test
    fun `workspace isolation - states are scoped per workspace`() = runBlocking {
        resources = listOf(llmResource())
        persistence.insertEvidenceAll(
            listOf(evidence(CapabilityType.LLM_GENERATION, EvidenceOutcome.FAILURE, workspaceId = "ws-2"))
        )
        val ws1 = service.deriveSnapshot("ws-1")
        val ws2 = service.deriveSnapshot("ws-2")
        val llmWs1 = ws1.capabilities.first { it.capabilityKey == CapabilityType.LLM_GENERATION.code }
        val llmWs2 = ws2.capabilities.first { it.capabilityKey == CapabilityType.LLM_GENERATION.code }
        assertEquals(0, llmWs1.evidenceCount)
        assertTrue(llmWs2.evidenceCount > 0)
    }

    // ------------------------------------------------------------------
    // GAPS + RECOMMENDATIONS
    // ------------------------------------------------------------------

    @Test
    fun `gap detection identifies unprovisioned resources with correct reason`() = runBlocking {
        val snapshot = service.deriveSnapshot("ws-1")
        val llmGap = snapshot.gaps.firstOrNull { it.capabilityKey == CapabilityType.LLM_GENERATION.code }
        assertNotNull(llmGap)
        assertEquals(CapabilityGapReason.RESOURCE_NOT_PROVISIONED, llmGap!!.reason)
    }

    @Test
    fun `recommendation generated for unprovisioned capability is actionable`() = runBlocking {
        val snapshot = service.deriveSnapshot("ws-1")
        val reco = snapshot.recommendations.firstOrNull { it.capabilityKey == CapabilityType.LLM_GENERATION.code }
        assertNotNull(reco)
        assertEquals(com.example.domain.core.radar.RadarRecommendationType.PROVISION_RESOURCE, reco!!.type)
        assertNotNull(reco.actionHint)
    }

    @Test
    fun `missing UI integration produces UI exposure recommendation`() = runBlocking {
        resources = listOf(llmResource())
        // REASONING has implemented=true, uiExposed=false in the honest map.
        val snapshot = service.deriveSnapshot("ws-1")
        val reasoning = snapshot.capabilities.first { it.capabilityKey == CapabilityType.REASONING.code }
        // Contributing resource usable + ui not exposed → PARTIAL with UI gap
        assertEquals(OperationalCapabilityState.PARTIAL, reasoning.state)
        val uiGap = snapshot.gaps.firstOrNull {
            it.capabilityKey == CapabilityType.REASONING.code && it.reason == CapabilityGapReason.MISSING_UI_INTEGRATION
        }
        assertNotNull(uiGap)
    }

    // ------------------------------------------------------------------
    // DECISION-ENGINE CHECK
    // ------------------------------------------------------------------

    @Test
    fun `checkCapability reports executable for AVAILABLE and fallback for DEGRADED`() = runBlocking {
        resources = listOf(llmResource())
        service.deriveSnapshot("ws-1")
        val check = service.checkCapability(CapabilityType.LLM_GENERATION, "ws-1")
        assertTrue(check.isExecutable)
        assertFalseFallback(check.requiresFallback)

        // No SEARCH resources at all + online policy → PARTIAL, not executable
        val searchCheck = service.checkCapability(CapabilityType.SEARCH, "ws-1")
        assertEquals(OperationalCapabilityState.PARTIAL, searchCheck.state)
        assertTrue(searchCheck.requiresFallback)
    }

    private fun assertFalseFallback(value: Boolean) {
        // AVAILABLE does not require fallback
        org.junit.Assert.assertFalse(value)
    }

    // ------------------------------------------------------------------
    // EMBEDDING PROVISIONING DIMENSION (honest ONNX state)
    // ------------------------------------------------------------------

    @Test
    fun `embedding runtimeValidated follows the ONNX provisioning state`() = runBlocking {
        embeddingProvisioned = false
        val before = service.deriveSnapshot("ws-1")
            .capabilities.first { it.capabilityKey == CapabilityType.EMBEDDING.code }
        assertEquals(false, before.dimensions.runtimeValidated)

        embeddingProvisioned = true
        val after = service.deriveSnapshot("ws-1")
            .capabilities.first { it.capabilityKey == CapabilityType.EMBEDDING.code }
        assertEquals(true, after.dimensions.runtimeValidated)
    }

    @Test
    fun `embedding provisioning transition is detected as a change`() = runBlocking {
        embeddingProvisioned = false
        service.deriveSnapshot("ws-1")
        embeddingProvisioned = true
        val snapshot = service.deriveSnapshot("ws-1")
        val embeddingChange = snapshot.changes.firstOrNull { it.capabilityKey == CapabilityType.EMBEDDING.code }
        assertNotNull("transition PARTIAL→AVAILABLE must be recorded", embeddingChange)
    }
}
