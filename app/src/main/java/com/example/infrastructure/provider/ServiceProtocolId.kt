package com.example.infrastructure.provider

/**
 * ServiceProtocolId — Phase 4
 * 
 * Type-safe identifier for a protocol (Gemini, OpenAI, Ollama, etc.).
 */
@JvmInline
value class ServiceProtocolId(val value: String) {
    override fun toString(): String = value
    
    companion object {
        val IN_PROCESS = ServiceProtocolId("IN_PROCESS")
        val NATIVE_SDK = ServiceProtocolId("NATIVE_SDK")
        val GEMINI = ServiceProtocolId("GEMINI")
        val OPENAI_COMPATIBLE = ServiceProtocolId("OPENAI_COMPATIBLE")
        val OLLAMA = ServiceProtocolId("OLLAMA")
        val TAVILY = ServiceProtocolId("TAVILY")
    }
}
