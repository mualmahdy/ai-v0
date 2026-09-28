package com.example.infrastructure.llm.gemini

/**
 * ============================================================================
 * GeminiThinkingCapability — CLOSURE P0-5 (audit §5.5/C2)
 * ============================================================================
 *
 * The audit's finding: the Gemini adapter attached
 * `thinkingConfig.includeThoughts = true` to EVERY request
 * unconditionally — "generateContent detected → THINKING = SUPPORTED" was
 * fabricated capability. For a model without thinking support the provider
 * REJECTS the whole request; the failure then surfaced as a generic
 * generation error the runtime misattributed to the model itself.
 *
 * This gate is deliberately STATIC + CONSERVATIVE (curated model-family
 * knowledge, no fabrication):
 *   - [GeminiThinkingSupport.SUPPORTED] — model families documented by the
 *     provider to expose thinking (Gemini 2.5 family, explicit thinking
 *     variants). ONLY these get `thinkingConfig` attached.
 *   - [GeminiThinkingSupport.UNSUPPORTED] — families known NOT to expose a
 *     thinking config (Gemini 1.x, plain 2.0-flash, Gemma, LearnLM).
 *   - [GeminiThinkingSupport.UNKNOWN] — everything else: the adapter omits
 *     `thinkingConfig` entirely (the safe default — an unproven capability
 *     is never sent to the provider).
 *
 * HONEST BOUNDARY → CLOSED (P1-1 / audit §5 D1): static curation is the
 * floor, not the ceiling. The runtime probe now EXISTS: validation runs a
 * bounded thinking-config acceptance probe through the resource's own
 * adapter (LlmCapabilityProbePort) and registers its verdict here via
 * [registerRuntimeVerdict]. [forModel] consults the RUNTIME override
 * registry FIRST — a probe-verified verdict beats the table in BOTH
 * directions:
 *
 *   - endpoint REJECTS thinkingConfig for a documented-SUPPORTED family
 *     (proxy stripping, API version drift) → runtime UNSUPPORTED wins;
 *   - endpoint ACCEPTS it for an UNKNOWN family → runtime SUPPORTED wins.
 *
 * Transient probe failures never register anything (INCONCLUSIVE keeps the
 * static floor — a network blip must not manufacture capability truth).
 */
enum class GeminiThinkingSupport {
    /** Thinking config MAY be attached (documented, curated families only). */
    SUPPORTED,

    /** Thinking config MUST NOT be attached (known non-thinking family). */
    UNSUPPORTED,

    /** Unknown — thinking config omitted (no capability fabrication). */
    UNKNOWN
}

object GeminiThinkingCapability {

    // ------------------------------------------------------------------
    // P1-1 — the RUNTIME OVERRIDE registry (per normalized model id).
    // Written ONLY by the validation path's acceptance probe; consulted
    // FIRST by [forModel]. Test seam: [clearRuntimeVerdicts] restores the
    // pure static floor.
    // ------------------------------------------------------------------
    private val runtimeVerdicts =
        java.util.concurrent.ConcurrentHashMap<String, GeminiThinkingSupport>()

    /** Normalizes a model id to the registry's key form. */
    fun normalize(modelName: String): String = modelName.trim().lowercase()

    /**
     * Registers a RUNTIME probe verdict for a model id. This is the ONLY
     * write path, and it is reserved for probe outcomes (ACCEPTED →
     * SUPPORTED, REJECTED → UNSUPPORTED); INCONCLUSIVE probes must NOT
     * register anything.
     */
    fun registerRuntimeVerdict(modelName: String, support: GeminiThinkingSupport) {
        val key = normalize(modelName)
        if (key.isNotEmpty()) runtimeVerdicts[key] = support
    }

    /** The registered runtime verdict for a model id, if any. */
    fun runtimeVerdict(modelName: String): GeminiThinkingSupport? =
        runtimeVerdicts[normalize(modelName)]

    /** Test seam: drops every runtime override (back to the static floor). */
    fun clearRuntimeVerdicts() = runtimeVerdicts.clear()

    /**
     * Resolves the thinking support of a Gemini model id: the RUNTIME
     * override first (probe evidence beats curation), then the static
     * curated table. Matching is prefix/substring-based on the NORMALIZED
     * id (lowercased, trimmed) so versioned variants (`gemini-2.5-flash-001`,
     * `gemini-2.5-pro-preview`) inherit their family's verdict.
     */
    fun forModel(modelName: String): GeminiThinkingSupport {
        runtimeVerdict(modelName)?.let { return it }
        val model = normalize(modelName)
        if (model.isEmpty()) return GeminiThinkingSupport.UNKNOWN
        return when {
            // 2.5 family (flash / pro / flash-lite and variants): thinking
            // is a documented generation-time config.
            model.contains("gemini-2.5") -> GeminiThinkingSupport.SUPPORTED
            // Explicit thinking variants (e.g. gemini-2.0-flash-thinking-001).
            model.contains("thinking") -> GeminiThinkingSupport.SUPPORTED
            // Gemini 3 projections keep the thinking contract.
            model.contains("gemini-3") -> GeminiThinkingSupport.SUPPORTED
            // 1.x era, plain 2.0 flash, and derivative families: no
            // thinkingConfig support.
            model.contains("gemini-1.0") ||
                model.contains("gemini-1.5") ||
                model.contains("gemini-2.0-flash") ||
                model.contains("gemma") ||
                model.contains("learnlm") ||
                model.contains("imagen") -> GeminiThinkingSupport.UNSUPPORTED
            else -> GeminiThinkingSupport.UNKNOWN
        }
    }
}
