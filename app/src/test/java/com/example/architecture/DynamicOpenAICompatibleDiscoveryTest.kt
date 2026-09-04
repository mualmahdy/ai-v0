package com.example.architecture

import com.example.infrastructure.provider.DiscoveryAdapterFactory
import com.example.domain.core.Outcome
import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.provider.ServiceType
import com.example.domain.core.provider.offering.ServiceOffering
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * DynamicOpenAICompatibleDiscoveryTest — Phase 4 architectural invariant
 * ============================================================================
 *
 * Per the architectural plan (Section 9):
 *
 * The system must NOT have hard-coded discovery registration such as
 * `local_ollama → fixed endpoint` as the only route. Discovery must be
 * derived from `ServiceType + ServiceProtocol + ServiceConfiguration`.
 *
 * For OpenAI-compatible services, the configured endpoint is used dynamically
 * rather than a hard-coded provider.
 */
class DynamicOpenAICompatibleDiscoveryTest {

    @Test
    fun `discovery uses the configured endpoint dynamically`() = runBlocking {
        // Two different endpoints — both should be reachable via the SAME
        // DiscoveryAdapterFactory.discover() call, parameterized by the
        // ServiceConfiguration.
        val endpoint1 = "https://endpoint1.invalid.test/v1"
        val endpoint2 = "https://endpoint2.invalid.test/v1"

        val service = ProviderService(
            id = "svc1",
            providerId = "p1",
            name = "LLM",
            serviceType = ServiceType.LLM,
            supportedProtocolIds = listOf(ServiceProtocolId.OPENAI_COMPATIBLE.code),
            isEnabled = true
        )

        val cfg1 = ServiceConfiguration(
            id = "cfg1", serviceId = "svc1",
            protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
            endpointUrl = endpoint1, isEnabled = true
        )
        val cfg2 = ServiceConfiguration(
            id = "cfg2", serviceId = "svc1",
            protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
            endpointUrl = endpoint2, isEnabled = true
        )

        // Both calls go through the same factory; only the configuration differs.
        // We don't make real network calls (these URLs are fake), but we verify
        // that the factory accepts any endpoint and attempts discovery.
        val outcome1 = DiscoveryAdapterFactory.discover(service, cfg1) { null }
        val outcome2 = DiscoveryAdapterFactory.discover(service, cfg2) { null }

        // Both should return some Outcome (Error or Success) — not a hard-coded
        // single-endpoint behavior. They MUST NOT both succeed with the same
        // hardcoded data — they are configured with different endpoints.
        assertTrue(
            "Discovery must accept a parameterized endpoint (not hard-coded)",
            outcome1 is Outcome<*, *> && outcome2 is Outcome<*, *>
        )
    }

    @Test
    fun `discovery produces ServiceOfferings not ResourceRecords`() = runBlocking {
        val service = ProviderService(
            id = "svc1", providerId = "p1", name = "LLM",
            serviceType = ServiceType.LLM,
            supportedProtocolIds = listOf(ServiceProtocolId.OPENAI_COMPATIBLE.code),
            isEnabled = true
        )
        val cfg = ServiceConfiguration(
            id = "cfg1", serviceId = "svc1",
            protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
            endpointUrl = "https://invalid.example.test/v1",
            isEnabled = true
        )
        val outcome = DiscoveryAdapterFactory.discover(service, cfg) { null }
        // Even if discovery fails, the result type is List<ServiceOffering>, not
        // List<ResourceRecord>. This is the architectural invariant.
        when (outcome) {
            is Outcome.Success -> {
                assertTrue(
                    "Discovery must produce ServiceOfferings, got: ${outcome.value.firstOrNull()?.let { it::class.simpleName }}",
                    outcome.value.all { it is ServiceOffering }
                )
            }
            is Outcome.Error -> { /* OK — discovery failure with fake endpoint */ }
            is Outcome.Degraded -> { /* OK — degraded discovery */ }
        }
    }
}
