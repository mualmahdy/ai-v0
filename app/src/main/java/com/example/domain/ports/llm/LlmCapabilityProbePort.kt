package com.example.domain.ports.llm

/**
 * ============================================================================
 * LlmCapabilityProbePort — CLOSURE P1-1 (audit 2026 §5/D1)
 * ============================================================================
 *
 * The runtime capability-probe seam for LLM adapters. This is the port the
 * validation layer uses to ask an adapter: "does THIS (model × endpoint), as
 * configured RIGHT NOW, ACCEPT the optional request features your family
 * documents?" — the probe whose verdict OVERRIDES the curated static table
 * (P0-5's honest boundary: "static curation is the floor, not the ceiling").
 *
 * Contract (the honesty taxonomy the audit demanded):
 *  - [CapabilityProbeOutcome.ACCEPTED]    — the provider accepted the
 *    optional feature on a REAL bounded round-trip (the feature may be
 *    declared VERIFIED).
 *  - [CapabilityProbeOutcome.REJECTED]    — the provider rejected the
 *    request BECAUSE OF the optional feature (invalid-argument family) on a
 *    round-trip that otherwise mirrors the generation probe (the capability
 *    is UNSUPPORTED for this resource — the runtime override).
 *  - [CapabilityProbeOutcome.INCONCLUSIVE] — transient conditions (auth,
 *    rate limit, timeout, transport, 5xx). NEVER flips a verdict: a network
 *    blip must not manufacture capability truth in either direction.
 *
 * Implementations MUST bound the probe exactly like [GenerationProbe]
 * (single-digit token budget, temperature 0) — one tiny paid request is the
 * honest price of a VERIFIED capability declaration, the same price the
 * audit already accepted for generation.
 */
enum class CapabilityProbeOutcome {
    /** Real bounded round-trip accepted the optional feature. */
    ACCEPTED,

    /** Real bounded round-trip rejected the optional feature itself. */
    REJECTED,

    /** Transient failure — no verdict may be derived from this probe. */
    INCONCLUSIVE
}

interface LlmCapabilityProbePort {

    /**
     * Runs the bounded optional-feature acceptance probe through this
     * adapter's OWN protocol path (the exact model + auth + endpoint
     * production uses), with the optional feature FORCED on — bypassing the
     * adapter's static capability gate, because testing the gate is the
     * probe's whole purpose.
     *
     * Adapters that document no optional features simply report ACCEPTED
     * without a network round-trip (nothing to disprove). Adapters whose
     * protocol has no probe path this stage report INCONCLUSIVE.
     */
    suspend fun probeOptionalFeatureAcceptance(): CapabilityProbeOutcome
}
