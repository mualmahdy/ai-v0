package com.example.infrastructure.provider

/**
 * ServiceProtocolId — Phase 4
 * 
 * Type-safe identifier for a protocol (Gemini, OpenAI, Ollama, etc.).
 */
@JvmInline
value class ServiceProtocolId(val value: String) {
    override fun toString(): String = value
}
