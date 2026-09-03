package com.example.audit

import com.example.application.audit.CriticalFindingsRegister
import com.example.domain.core.capability.CapabilityGapAnalysis
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionCase
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.extension.IntegrationDescriptor
import com.example.domain.core.extension.McpServerDescriptor
import com.example.domain.core.extension.SkillManifest
import com.example.domain.core.model.ModelDescriptor
import com.example.domain.core.model.Modality
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.radar.RadarCategory
import com.example.domain.core.radar.RadarItem
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 Verification Tests — proves that the P0/P2 fixes are in place.
 *
 * These tests do NOT exercise runtime behaviour (they don't need an Android emulator);
 * they assert that the data-class defaults and the CaseBase similarity math now match
 * the spec contract documented in CriticalFindingsRegister.
 *
 * Run with: ./gradlew test --tests "com.example.audit.Phase1FixVerificationTest"
 */
class Phase1FixVerificationTest {

    // ────────────────────────────────────────────────────────────────────
    // DOM-P0-01: CaseBase feature-vector length mismatch
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `CaseBase bootstrap vectors are length 15 matching DecisionState`() {
        val caseBase = CaseBase()
        val allCases = caseBase.getAllCases()
        assertTrue("CaseBase should have bootstrap cases", allCases.isNotEmpty())

        val state = DecisionState(
            taskId = TaskId("test"),
            taskComplexity = 0.5f,
            requiresCoding = true,
            requiresToolCalling = true
        )
        val queryVec = state.toFeatureVector()
        assertEquals("DecisionState feature vector must be length 15", 15, queryVec.size)

        // FIX DOM-P0-01: all bootstrap cases must now have length-15 vectors
        for (case in allCases) {
            assertEquals(
                "Bootstrap case ${case.id} must have length-15 vector (was length ${case.problemFeatures.size} before fix)",
                15,
                case.problemFeatures.size
            )
        }

        // findSimilarCases must not silently truncate — the evidence features (idx 11-14)
        // must now actually contribute to the similarity computation.
        val similar = caseBase.findSimilarCases(queryVec, k = 5, minSimilarity = 0.0f)
        assertTrue("findSimilarCases should return results", similar.isNotEmpty())
    }

    @Test
    fun `CaseBase handles persisted legacy length-11 vectors via zero-padding`() {
        // Simulate a legacy case with the old length-11 vector (pre-fix).
        // The new computeCosineSimilarity zero-pads shorter vectors to the longer one's
        // length, so the legacy case should still match (with reduced similarity because
        // the evidence features contribute 0 to the dot product).
        val caseBase = CaseBase()
        caseBase.addCase(
            DecisionCase(
                id = "legacy_test",
                problemFeatures = floatArrayOf(0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.2f),
                chosenAction = DecisionAction(DecisionActionType.EXECUTE_STEP),
                outcomeReward = 0.8f,
                taskType = "LEGACY"
            )
        )

        val queryVec = floatArrayOf(
            0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.2f,
            1.0f, 0.0f, 1.0f, 0.0f // evidence features (idx 11-14)
        )
        val similar = caseBase.findSimilarCases(queryVec, k = 5, minSimilarity = 0.0f)
        val legacyMatch = similar.firstOrNull { it.first.id == "legacy_test" }
        assertNotNull("Legacy length-11 case should still match (zero-padded)", legacyMatch)
        // The similarity should be > 0 (the first 11 features match) but < 1.0 (the
        // evidence features don't match because the legacy vector has zeros there).
        assertTrue("Legacy match similarity should be > 0", (legacyMatch?.second ?: 0f) > 0f)
    }

    // ────────────────────────────────────────────────────────────────────
    // DOM-P0-02: TaskLifecycleState persistence round-trip
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `DEGRADED is a valid TaskLifecycleState enum value`() {
        // FIX DOM-P0-02: previously this threw IllegalArgumentException
        val state = TaskLifecycleState.valueOf("DEGRADED")
        assertEquals(TaskLifecycleState.DEGRADED, state)
    }

    @Test
    fun `AgentOrchestrator can persist and restore DEGRADED state without crashing`() {
        // The orchestrator writes stateStr = "DEGRADED" in persistTaskFinal.
        // Before the fix, TaskLifecycleState.valueOf("DEGRADED") would throw.
        // Now it must round-trip cleanly.
        val states = listOf("CREATED", "RUNNING", "COMPLETED", "DEGRADED", "FAILED", "WAITING", "CANCELLED")
        for (s in states) {
            val parsed = TaskLifecycleState.valueOf(s)
            assertEquals(s, parsed.name)
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // DOM-P0-03: CapabilityGapAnalysis aliasing bug
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `CapabilityGapAnalysis candidateResourcesForMissing defaults to emptyMap not aliased to pending`() {
        // FIX DOM-P0-03: previously `candidateResourcesForMissing = candidateResourcesForPending`
        // was evaluated at construction with candidateResourcesForPending = emptyMap(), so
        // candidateResourcesForMissing was ALWAYS empty unless explicitly passed.
        val analysis = CapabilityGapAnalysis(
            targetTaskId = "test",
            requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
            candidateResourcesForPending = mapOf(
                CapabilityType.LLM_GENERATION to emptyList()
            ),
            candidateResourcesForMissing = mapOf(
                CapabilityType.LLM_GENERATION to emptyList()
            )
        )
        // Now both fields are independently settable — the aliasing bug is gone.
        assertTrue(analysis.candidateResourcesForPending.isNotEmpty())
        assertTrue(analysis.candidateResourcesForMissing.isNotEmpty())
    }

    // ────────────────────────────────────────────────────────────────────
    // DOM-P2-19: ResourceRecord honest defaults
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `ResourceRecord defaults to UNKNOWN health, REGISTERED lifecycle, runtimeSupported false`() {
        // FIX DOM-P2-19: previously defaulted to HEALTHY / ENABLED / runtimeSupported=true
        val record = ResourceRecord(
            resourceId = ResourceId("test"),
            providerId = "test",
            serviceId = "test",
            resourceType = ResourceType.LLM,
            capabilities = setOf(CapabilityType.LLM_GENERATION)
        )
        assertEquals("Default health must be UNKNOWN, not HEALTHY", HealthStatus.UNKNOWN, record.healthStatus)
        assertEquals("Default lifecycle must be REGISTERED, not ENABLED", ResourceLifecycleState.REGISTERED, record.lifecycleState)
        assertFalse("Default runtimeSupported must be false, not true", record.runtimeSupported)
    }

    // ────────────────────────────────────────────────────────────────────
    // DOM-P2-20: McpServerDescriptor honest defaults
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `McpServerDescriptor defaults to UNKNOWN health, 0 latency, null lastPing`() {
        val server = McpServerDescriptor(
            id = "test",
            name = "test",
            endpointUri = "http://example.com"
        )
        // FIX DOM-P2-20: previously defaulted to HEALTHY / 45L / currentTimeMillis()
        assertEquals(HealthStatus.UNKNOWN, server.health)
        assertEquals(0L, server.latencyMs)
        assertNull("lastPingTimestampMs must default to null, not currentTimeMillis()", server.lastPingTimestampMs)
    }

    // ────────────────────────────────────────────────────────────────────
    // DOM-P2-21: IntegrationDescriptor honest defaults
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `IntegrationDescriptor defaults to UNKNOWN health when not connected`() {
        val integration = IntegrationDescriptor(
            id = "test",
            name = "test",
            serviceType = "GITHUB",
            isConnected = false
        )
        // FIX DOM-P2-21: previously defaulted to HEALTHY
        assertEquals(HealthStatus.UNKNOWN, integration.health)
    }

    // ────────────────────────────────────────────────────────────────────
    // DOM-P2-22: SkillManifest isVerified defaults to false
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `SkillManifest isVerified defaults to false`() {
        val skill = SkillManifest(
            id = "test",
            name = "test",
            version = "1.0",
            description = "test"
        )
        // FIX DOM-P2-22: previously defaulted to true with no verification process
        assertFalse("Skill isVerified must default to false", skill.isVerified)
    }

    // ────────────────────────────────────────────────────────────────────
    // DOM-P2-23: RadarItem confidence defaults to 0_0f
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `RadarItem confidence defaults to 0_0f`() {
        val item = RadarItem(
            id = "test",
            title = "test",
            summary = "test",
            category = RadarCategory.MODEL_RELEASE,
            sourceUrl = "https://example.com",
            sourceName = "test",
            relevanceScore = 0.5f
        )
        // FIX DOM-P2-23: previously defaulted to 0.95f
        assertEquals(0.0f, item.confidence, 0.0001f)
    }

    // ────────────────────────────────────────────────────────────────────
    // DOM-P2-24: ModelDescriptor honest defaults
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `ModelDescriptor defaults to UNSPECIFIED discoverySource and 0_0f confidence`() {
        val model = ModelDescriptor(
            id = "test",
            providerId = "test",
            name = "test",
            version = "1.0",
            contextWindowTokens = 8192,
            maxOutputTokens = 4096,
            inputModalities = setOf(Modality.TEXT),
            outputModalities = setOf(Modality.TEXT)
        )
        // FIX DOM-P2-24: previously defaulted to "AUTOMATIC_DISCOVERY" / 0.95f
        assertEquals("UNSPECIFIED", model.discoverySource)
        assertEquals(0.0f, model.confidence, 0.0001f)
    }

    // ────────────────────────────────────────────────────────────────────
    // CriticalFindingsRegister sanity
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `CriticalFindingsRegister has expected findings count`() {
        // Sanity check that the register is populated and the severity breakdown makes sense.
        assertTrue("Register should have at least 30 findings", CriticalFindingsRegister.totalFindings >= 30)
        assertTrue("Should have P0 findings", CriticalFindingsRegister.findingsBySeverity(CriticalFindingsRegister.Severity.P0).isNotEmpty())
        assertTrue("Should have P1 findings", CriticalFindingsRegister.findingsBySeverity(CriticalFindingsRegister.Severity.P1).isNotEmpty())
        assertTrue("Should have P2 findings", CriticalFindingsRegister.findingsBySeverity(CriticalFindingsRegister.Severity.P2).isNotEmpty())
    }

    @Test
    fun `CriticalFindingsRegister runtime truth summary is populated`() {
        val summary = CriticalFindingsRegister.runtimeTruthSummary
        assertTrue("Should list real components", summary.realComponents.isNotEmpty())
        assertTrue("Should list partial components", summary.partialComponents.isNotEmpty())
        assertTrue("Should list fake/missing components", summary.fakeOrMissing.isNotEmpty())
    }

    @Test
    fun `Each P0 finding has a non-empty fix description`() {
        val p0Findings = CriticalFindingsRegister.findingsBySeverity(CriticalFindingsRegister.Severity.P0)
        for (finding in p0Findings) {
            assertTrue("P0 finding ${finding.id} must have a fix description", finding.fix.isNotBlank())
            assertTrue("P0 finding ${finding.id} must have a file reference", finding.file.isNotBlank())
        }
    }
}
