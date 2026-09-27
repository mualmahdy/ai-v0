package com.example.infrastructure.validation

import com.example.domain.core.Outcome
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.MessageRole
import com.example.domain.core.llm.GenerationConfig
import com.example.domain.core.provider.ServiceHealthClassification
import com.example.domain.core.provider.ServiceValidationResult
import com.example.domain.ports.llm.LlmProviderPort

/**
 * ============================================================================
 * GenerationProbe — CLOSURE P0-4 (audit §5.5/C1: the generation layer)
 * ============================================================================
 *
 * The audit's layering for provider readiness:
 *
 *   Configured → Reachable → Model validated → Capability validated →
 *   Generation validated
 *
 * Before this probe, "HEALTHY" for an LLM resource meant ONLY that a
 * `GET /models` listing returned HTTP 200 with a valid key
 * (ResourceValidators' LLM path). The audit's finding — "/models works ≠
 * generateContent works" — was structurally true: the selected model was
 * NEVER asked to generate anything during validation.
 *
 * This probe closes the LAST gap honestly: a REAL minimal generation
 * round-trip through the resource's OWN adapter (the exact adapter + model
 * + auth path production uses), with a bounded token budget
 * ([MAX_OUTPUT_TOKENS], temperature 0). One tiny paid request per
 * validation run is the honest price of a HEALTHY verdict that actually
 * means "this model can generate" — the metadata-only alternative is
 * precisely what the audit rejected.
 *
 * Result mapping (via ProviderControlPlaneService.classificationToHealth):
 *   - Success/Degraded generation → probe SUCCESS → resource stays HEALTHY
 *     (Degraded still PROVES the generation round-trip works).
 *   - RateLimited / NetworkTimeout / AuthenticationFailed → the honest
 *     transient/auth classifications (→ DEGRADED / UNAVAILABLE).
 *   - Any other failure (404 model-not-found, invalid model, protocol
 *     rejection…) → PROTOCOL_FAILURE → UNAVAILABLE: this resource, as
 *     configured, cannot generate — HEALTHY is no longer reachable through
 *     reachability alone.
 */
object GenerationProbe {

    /** Bounded probe: the whole point is existence, not content. */
    const val MAX_OUTPUT_TOKENS = 8
    const val PROBE_PROMPT = "Reply with the single word: ok"

    /** The minimal honest generation request (bounded, deterministic). */
    fun request(): LlmRequest = LlmRequest(
        messages = listOf(
            LlmMessage(role = MessageRole.USER, content = PROBE_PROMPT)
        ),
        config = GenerationConfig(
            temperature = 0.0f,
            maxOutputTokens = MAX_OUTPUT_TOKENS
        )
    )

    /**
     * Runs the generation probe against the resource's REAL adapter.
     * Returns the validation result that REPLACES the reachability-only
     * verdict when the adapter is available.
     */
    suspend fun probe(adapter: LlmProviderPort): ServiceValidationResult {
        val start = System.currentTimeMillis()
        return when (val outcome = adapter.generate(request())) {
            is Outcome.Success -> ServiceValidationResult.success(
                System.currentTimeMillis() - start,
                "generation round-trip verified (model responded)"
            )
            is Outcome.Degraded -> ServiceValidationResult.success(
                System.currentTimeMillis() - start,
                "generation round-trip verified (degraded: ${outcome.diagnosticMessage.take(80)})"
            )
            is Outcome.Error -> ServiceValidationResult.failure(
                classificationFor(outcome.failure),
                System.currentTimeMillis() - start,
                "REACHABLE but GENERATION PROBE FAILED: ${outcome.diagnosticMessage.take(160)}"
            )
        }
    }

    /**
     * Honest failure taxonomy: transient/auth failures keep their
     * classifications (rate limit / timeout / auth); anything else means
     * THIS resource, as configured, cannot generate — PROTOCOL_FAILURE.
     */
    fun classificationFor(failure: LlmFailure): ServiceHealthClassification = when (failure) {
        is LlmFailure.RateLimitExceeded -> ServiceHealthClassification.RATE_LIMITED
        is LlmFailure.NetworkTimeout -> ServiceHealthClassification.TIMEOUT
        is LlmFailure.AuthenticationFailed -> ServiceHealthClassification.AUTHENTICATION_FAILURE
        is LlmFailure.ProviderUnavailable,
        is LlmFailure.ContextLengthExceeded,
        is LlmFailure.InvalidResponse -> ServiceHealthClassification.PROTOCOL_FAILURE
    }
}
