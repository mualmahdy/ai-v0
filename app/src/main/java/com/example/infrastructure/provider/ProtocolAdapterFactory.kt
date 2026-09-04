package com.example.infrastructure.provider

import com.example.infrastructure.storage.SandboxWorkspaceStorageAdapter

/**
 * Factory for protocol-specific adapters.
 * Phase 4: Resolves ProviderService configurations to concrete protocol adapters
 * (Gemini, OpenAI, Ollama, etc.) for LLM, Embedding, and Search providers.
 */
class ProtocolAdapterFactory(
    private val workspaceStoragePort: SandboxWorkspaceStorageAdapter,
    private val defaultProjectId: Long,
    private val geminiBootstrap: GeminiBootstrap
) {
    
    /**
     * Resolves a protocol-specific adapter for the given service configuration.
     */
    fun createAdapter(protocolName: String, config: Map<String, Any>): Any? {
        return when (protocolName.uppercase()) {
            "GEMINI" -> {
                geminiBootstrap.ensureInitialized()
                GeminiLlmAdapterStub(config)
            }
            "OPENAI_COMPATIBLE" -> {
                OpenAiCompatibleAdapterStub(config)
            }
            "OLLAMA" -> {
                OllamaAdapterStub(config)
            }
            else -> null
        }
    }
    
    // Stub adapter classes for Phase 4
    private class GeminiLlmAdapterStub(config: Map<String, Any>)
    
    private class OpenAiCompatibleAdapterStub(config: Map<String, Any>)
    
    private class OllamaAdapterStub(config: Map<String, Any>)
}
