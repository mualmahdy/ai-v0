package com.example.domain.core.resource

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.provider.ServiceProtocolId

/**
 * ============================================================================
 * OperationalResourceSnapshot — CLOSURE P1-1 (audit 2026 §5/D1 + §5 item 8)
 * ============================================================================
 *
 * The audit's finding (two halves of one gap):
 *
 *  1. OFFERING-LEVEL DECLARATIONS WERE ASSUMPTIONS: the connect wizard
 *     (ConnectProviderUseCase) declared REASONING + STREAMING for EVERY LLM
 *     offering as unverified defaults — gemini-2.0-flash received REASONING
 *     even though the curated static table itself says that family has no
 *     thinking config. The declaration surface had no notion of EVIDENCE.
 *  2. THE STATIC TABLE IS A FLOOR, NOT A CEILING: P0-5 gated thinkingConfig
 *     behind [GeminiThinkingCapability]'s curated families and honestly
 *     deferred the runtime probe to this stage — "the runtime probe per
 *     (model × mode) whose verdict overrides this table".
 *
 * This type is the OPERATIONAL TRUTH SURFACE that closes both halves: a
 * per-(resource × model × protocol) record of what was PROVEN at runtime —
 * generation via a real round-trip ([GenerationProbe]), optional-feature
 * (thinking) acceptance via the adapter's own bounded probe — with an
 * explicit verdict taxonomy that can never claim more than was exercised:
 *
 *  - [CapabilityVerdict.VERIFIED]    — runtime round-trip PROVED it works.
 *  - [CapabilityVerdict.UNSUPPORTED] — runtime round-trip PROVED it is
 *                                      rejected for THIS endpoint+model
 *                                      (the override that beats the static
 *                                      table — a proxy that strips
 *                                      thinkingConfig, an endpoint whose
 *                                      model family lacks the feature).
 *  - [CapabilityVerdict.UNPROBED]    — no runtime evidence. NEVER upgraded
 *                                      by assumption; only the DOCUMENTED
 *                                      floor (curated table / adapter
 *                                      implementation facts) may declare
 *                                      the capability, and only while no
 *                                      runtime verdict contradicts it.
 *
 * Declaration rule ([declaredCapabilities]) — one policy for every caller:
 *
 *   declared = floor ∪ verified − runtime-rejected
 *
 * i.e. a capability is declared iff it is runtime-VERIFIED, OR documented
 * (floor) while the runtime verdict is not UNSUPPORTED. This is exactly the
 * honest upgrade path the audit demanded: the wizard no longer ASSUMES
 * REASONING/STREAMING — it declares the documented floor at registration
 * and corrects it with runtime evidence as validation probes run.
 */
enum class CapabilityVerdict {
    /** Runtime round-trip proved the capability works. */
    VERIFIED,

    /** Runtime round-trip proved the provider rejects it for this resource. */
    UNSUPPORTED,

    /** No runtime evidence — never claimed, never flipped by assumption. */
    UNPROBED
}

/**
 * The per-resource operational capability snapshot — see the file KDoc for
 * the closure semantics. Instances are produced by the validation path
 * (the ONLY place runtime evidence exists) and are immutable value objects.
 */
data class OperationalResourceSnapshot(
    /** The operational resource this snapshot describes ("" until the control plane keys it). */
    val resourceId: String = "",
    /** The model/point name the probes ran against (config.defaultOfferingId). */
    val modelName: String,
    /** The protocol the probes spoke (verdicts are protocol-specific). */
    val protocolId: ServiceProtocolId,
    /** Generation round-trip verdict (the P0-4 probe). */
    val generation: CapabilityVerdict = CapabilityVerdict.UNPROBED,
    /** Thinking-config acceptance verdict (the P1-1 runtime override probe). */
    val thinking: CapabilityVerdict = CapabilityVerdict.UNPROBED,
    /** Streaming (SSE) verdict — UNPROBED this stage; only the documented floor declares it. */
    val streaming: CapabilityVerdict = CapabilityVerdict.UNPROBED,
    val probedAtEpochMs: Long = 0L,
    val probeLatencyMs: Long = 0L,
    /** Human-readable evidence trail of what was actually exercised. */
    val evidence: String = ""
) {

    /** Keys the snapshot to an operational resource id (control-plane side). */
    fun withResourceId(resourceId: String): OperationalResourceSnapshot =
        if (resourceId == this.resourceId) this else copy(resourceId = resourceId)

    /**
     * The SINGLE declaration policy (floor ∪ verified − runtime-rejected).
     *
     * @param documentedFloor capabilities documented for this (protocol,
     *        model) — e.g. the curated Gemini thinking table for REASONING,
     *        the adapters' implemented SSE paths for STREAMING. The floor is
     *        evidence, but WEAKER evidence than a runtime verdict: a runtime
     *        UNSUPPORTED always wins over it, and a runtime VERIFIED may add
     *        capabilities the floor never documented.
     */
    fun declaredCapabilities(documentedFloor: Set<CapabilityType>): Set<CapabilityType> {
        val declared = mutableSetOf(CapabilityType.LLM_GENERATION)
        if (thinking == CapabilityVerdict.VERIFIED ||
            (thinking != CapabilityVerdict.UNSUPPORTED && CapabilityType.REASONING in documentedFloor)
        ) {
            declared += CapabilityType.REASONING
        }
        if (streaming == CapabilityVerdict.VERIFIED ||
            (streaming != CapabilityVerdict.UNSUPPORTED && CapabilityType.STREAMING in documentedFloor)
        ) {
            declared += CapabilityType.STREAMING
        }
        return declared
    }

    /**
     * Field-wise merge for re-validation runs: a NEWER probe's verdict wins
     * whenever it is not UNPROBED; an UNPROBED field honestly INHERITS the
     * previous run's verdict (a probe that could not run must not erase
     * earlier runtime evidence). The previous snapshot's identity fields
     * survive only if the new one is empty.
     */
    fun mergedWith(previous: OperationalResourceSnapshot?): OperationalResourceSnapshot {
        if (previous == null) return this
        return copy(
            resourceId = resourceId.ifBlank { previous.resourceId },
            generation = if (generation != CapabilityVerdict.UNPROBED) generation else previous.generation,
            thinking = if (thinking != CapabilityVerdict.UNPROBED) thinking else previous.thinking,
            streaming = if (streaming != CapabilityVerdict.UNPROBED) streaming else previous.streaming
        )
    }
}
