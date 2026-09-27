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
 * HONEST BOUNDARY (this stage): static curation is the floor, not the
 * ceiling. The audit's full design — a per-(model × request-mode) runtime
 * probe whose verdict overrides this table — lands with the capability-
 * probe stage (OperationalResourceSnapshot / D1); the runtime override
 * hook is [forModel]'s single seam.
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

    /**
     * Resolves the thinking support of a Gemini model id. Matching is
     * prefix/substring-based on the NORMALIZED id (lowercased, trimmed) so
     * versioned variants (`gemini-2.5-flash-001`, `gemini-2.5-pro-preview`)
     * inherit their family's verdict.
     */
    fun forModel(modelName: String): GeminiThinkingSupport {
        val model = modelName.trim().lowercase()
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
