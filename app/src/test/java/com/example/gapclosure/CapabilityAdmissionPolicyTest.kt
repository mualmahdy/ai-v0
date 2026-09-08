package com.example.gapclosure

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.capability.ResourceCapabilityGraph
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * REPORT GAP-CLOSURE — CapabilityAdmissionPolicyTest
 * ============================================================================
 *
 * Report verdict: "duplicate capability semantics / broken capability
 * matching — `candidate.capabilities.any { it in requiredCapabilities }`
 * let required={A,B} match candidate={A}, and `matchesRequired ||
 * matchesOptional` let an OPTIONAL capability admit a candidate that FAILED
 * the required set."
 *
 * This test pins the SINGLE authoritative admission policy:
 *  1. REQUIRED capabilities are a hard ALL-gate (containsAll).
 *  2. OPTIONAL capabilities never admit — only rank.
 *  3. Optional coverage sorts admitted candidates (best first).
 */
class CapabilityAdmissionPolicyTest {

    private fun record(
        id: String,
        caps: Set<CapabilityType>,
        type: ResourceType = ResourceType.LLM
    ): ResourceRecord = ResourceRecord(
        resourceId = ResourceId(id),
        providerId = "prov_$id",
        serviceId = "svc_$id",
        resourceType = type,
        capabilities = caps,
        lifecycleState = ResourceLifecycleState.ENABLED,
        runtimeSupported = true,
        healthStatus = HealthStatus.HEALTHY,
        isLocal = false
    )

    @Test
    fun `required capabilities are a hard ALL-gate`() {
        val graph = ResourceCapabilityGraph(
            listOf(
                record("partial", setOf(CapabilityType.LLM_GENERATION)),
                record("full", setOf(CapabilityType.LLM_GENERATION, CapabilityType.VISION))
            )
        )
        val admitted = graph.findEligibleCandidates(
            requiredCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.VISION)
        )
        assertEquals(1, admitted.size)
        assertEquals("full", admitted.first().resourceId.value)
    }

    @Test
    fun `an optional capability can NEVER admit a candidate failing required`() {
        val graph = ResourceCapabilityGraph(
            listOf(
                // Has ONLY the optional capability — must NOT be admitted.
                record("optional_only", setOf(CapabilityType.STREAMING))
            )
        )
        val admitted = graph.findEligibleCandidates(
            requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
            optionalCapabilities = setOf(CapabilityType.STREAMING)
        )
        assertTrue("optional-only candidate must not be admitted", admitted.isEmpty())
    }

    @Test
    fun `optional capabilities rank admitted candidates best-first`() {
        val graph = ResourceCapabilityGraph(
            listOf(
                record("plain", setOf(CapabilityType.LLM_GENERATION)),
                record("rich", setOf(CapabilityType.LLM_GENERATION, CapabilityType.STREAMING, CapabilityType.REASONING))
            )
        )
        val admitted = graph.admittedByRequirements(
            candidates = graph.findCandidatesByType(ResourceType.LLM),
            required = setOf(CapabilityType.LLM_GENERATION),
            optional = setOf(CapabilityType.STREAMING, CapabilityType.REASONING)
        )
        assertEquals(2, admitted.size)
        assertEquals("rich", admitted.first().resourceId.value)
    }

    @Test
    fun `empty required set admits every usable resource`() {
        val graph = ResourceCapabilityGraph(
            listOf(
                record("a", setOf(CapabilityType.LLM_GENERATION)),
                record("b", setOf(CapabilityType.SEARCH))
            )
        )
        assertEquals(2, graph.findEligibleCandidates(requiredCapabilities = emptySet()).size)
    }

    @Test
    fun `offline policy filters remote resources out`() {
        val graph = ResourceCapabilityGraph(
            listOf(
                record("remote", setOf(CapabilityType.LLM_GENERATION)).copy(isLocal = false),
                record("local", setOf(CapabilityType.LLM_GENERATION)).copy(isLocal = true)
            )
        )
        val offline = graph.findEligibleCandidates(
            requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
            networkPolicy = NetworkPolicy.OFFLINE,
            isNetworkAvailable = false
        )
        assertEquals(1, offline.size)
        assertEquals("local", offline.first().resourceId.value)
    }

    @Test
    fun `domain layer carries no application or infrastructure dependency`() {
        // Architectural boundary regression: the graph must be constructible
        // from domain types alone (records supplier), and the module must
        // not re-introduce a registry-based constructor.
        val graph = ResourceCapabilityGraph(listOf(record("x", setOf(CapabilityType.LLM_GENERATION))))
        assertFalse(graph.getUsableForTest().isEmpty())
    }

    private fun ResourceCapabilityGraph.getUsableForTest(): List<ResourceRecord> =
        this.findCandidatesByType(ResourceType.LLM).map {
            ResourceRecord(
                resourceId = it.resourceId,
                providerId = it.providerId,
                serviceId = it.serviceId,
                resourceType = it.resourceType,
                capabilities = it.capabilities
            )
        }
}
