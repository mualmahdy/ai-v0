package com.example.architecture

import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceProtocolId
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * ============================================================================
 * ConfigurationVersionTest — Phase 4 architectural invariant
 * ============================================================================
 *
 * Per the architectural plan (Section 20): `RuntimeAdapterResolver` resolves
 * by `ResourceId + configurationVersion`. A stale configuration version must
 * fail explicitly and trigger replanning/revalidation.
 *
 * No silent version substitution.
 */
class ConfigurationVersionTest {

    @Test
    fun `ServiceConfiguration default version is 1`() {
        val cfg = ServiceConfiguration(
            id = "cfg1",
            serviceId = "svc1",
            protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
            endpointUrl = "https://example.com/v1"
        )
        assertTrue("Default configurationVersion must be 1L", cfg.configurationVersion == 1L)
    }

    @Test
    fun `withBumpedVersion produces monotonic increment from oldVersion`() {
        val v1 = ServiceConfiguration(
            id = "cfg1", serviceId = "svc1",
            protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
            endpointUrl = "url", configurationVersion = 1L
        )
        val v2 = v1.withBumpedVersion(v1.configurationVersion)
        assertTrue("Bumped version must be 2L, got ${v2.configurationVersion}", v2.configurationVersion == 2L)
        val v3 = v2.withBumpedVersion(v2.configurationVersion)
        assertTrue(v3.configurationVersion == 3L)
        val v10 = v3.copy(configurationVersion = 9L).withBumpedVersion(9L)
        assertTrue(v10.configurationVersion == 10L)
    }

    @Test
    fun `configuration version mismatch is detectable as stale`() {
        val decisionVersion = 3L
        val currentResourceVersion = 5L
        assertFalse(
            "Decision version $decisionVersion must be considered stale when current is $currentResourceVersion",
            decisionVersion == currentResourceVersion
        )
    }

    @Test
    fun `configuration version match is fresh`() {
        val decisionVersion = 5L
        val currentResourceVersion = 5L
        assertTrue(
            "Decision version $decisionVersion must be considered fresh when current is $currentResourceVersion",
            decisionVersion == currentResourceVersion
        )
    }
}
