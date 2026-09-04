package com.example.domain.core.provider

/**
 * ============================================================================
 * ServiceProtocolId — Phase 4 (canonical protocol registry)
 * ============================================================================
 *
 * Type-safe identifier for the wire-level protocol used to communicate with a
 * service. This is the single authoritative declaration of the protocol enum:
 * it lives in the domain layer and is referenced by the application layer,
 * infrastructure adapters, and the UI.
 *
 * `code` is the stable machine identifier persisted in Room and exchanged with
 * the UI. `fromCode` resolves a persisted/serialized code back to the enum and
 * returns `null` for unknown codes (callers must reject them explicitly —
 * never silently default).
 */
enum class ServiceProtocolId(val code: String, val displayName: String) {
    IN_PROCESS("in_process", "مكوّن داخلي (In-Process)"),
    NATIVE_SDK("native_sdk", "SDK أصلي (Native SDK)"),
    GEMINI_NATIVE("gemini_native", "بروتوكول Gemini الرسمي"),
    OPENAI_COMPATIBLE("openai_compatible", "متوافق مع OpenAI API"),
    OPENAI_NATIVE("openai_native", "بروتوكول OpenAI الرسمي"),
    ANTHROPIC_NATIVE("anthropic_native", "بروتوكول Anthropic الرسمي"),
    OLLAMA_NATIVE("ollama_native", "بروتوكول Ollama المحلي"),
    TAVILY_NATIVE("tavily_native", "بروتوكول Tavily للبحث");

    companion object {
        /**
         * Resolves a protocol code to the enum. Returns null for unknown codes —
         * the caller must surface an explicit error (Correction #5: no silent
         * protocol substitution).
         */
        fun fromCode(code: String): ServiceProtocolId? {
            return entries.firstOrNull { it.code == code || it.name == code }
        }
    }

    override fun toString(): String = code
}
