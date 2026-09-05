package com.example.infrastructure.provider

import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.provider.ServiceType
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.memory.EmbeddingProviderPort
import com.example.domain.ports.search.SearchProviderPort
import com.example.infrastructure.llm.gemini.GeminiBootstrap
import com.example.infrastructure.llm.gemini.GeminiLlmAdapter
import com.example.infrastructure.llm.openai.OpenAiCompatibleEmbeddingAdapter
import com.example.infrastructure.llm.openai.OpenAiCompatibleLlmAdapter
import com.example.infrastructure.memory.LocalDeterministicEmbeddingAdapter
import com.example.infrastructure.search.MultiSourceSearchAdapter

/**
 * ============================================================================
 * ProtocolAdapterFactory — Phase 4 (REAL protocol adapters)
 * ============================================================================
 *
 * Resolves ProviderService + ServiceProtocol + ServiceConfiguration to the
 * concrete runtime adapter. Every branch returns a REAL working adapter
 * (previously these were empty private stub classes — replaced).
 *
 * GEMINI_NATIVE      → GeminiLlmAdapter (Generative Language REST API with the
 *                       user's stored key — NO Firebase/google-services.json
 *                       dependency; fixes "firebase not initiated" on real devices)
 * OPENAI_COMPATIBLE  → OpenAiCompatibleLlmAdapter (${endpoint}/chat/completions)
 * OPENAI_NATIVE      → OpenAiCompatibleLlmAdapter
 * OLLAMA_NATIVE      → OpenAiCompatibleLlmAdapter (Ollama /v1 compat layer)
 * IN_PROCESS (embed) → LocalDeterministicEmbeddingAdapter (on-device, free)
 * SEARCH (any)       → MultiSourceSearchAdapter (multi-source composite)
 *
 * Unknown combinations return null — the control plane fails explicitly with
 * ADAPTER_CREATION_FAILED (no silent fallback).
 */
class ProtocolAdapterFactory(
    // Optional: only used for opportunistic Firebase-options key discovery.
    // The Gemini REST path itself needs no Firebase (fix: firebase not initiated).
    private val geminiBootstrap: GeminiBootstrap? = null
) {

    fun createLlmAdapter(
        service: ProviderService,
        protocolId: ServiceProtocolId,
        config: ServiceConfiguration,
        apiKeyProvider: suspend () -> String?,
        offeringModelId: String? = null
    ): LlmProviderPort? {
        return when (protocolId) {
            ServiceProtocolId.GEMINI_NATIVE -> {
                // FIX (firebase not initiated): the Gemini adapter now speaks the
                // Generative Language REST API directly with the vault-backed key,
                // so it no longer requires FirebaseApp/google-services.json at
                // runtime. GeminiBootstrap stays only for optional Firebase-options
                // key discovery (apiKeyFromOptions) used by the validator.
                GeminiLlmAdapter(
                    defaultModelName = offeringModelId ?: config.defaultOfferingId.ifBlank { "gemini-2.5-flash" },
                    apiKeyProvider = apiKeyProvider,
                    baseUrl = config.endpointUrl.ifBlank { "https://generativelanguage.googleapis.com" }
                )
            }
            ServiceProtocolId.OPENAI_COMPATIBLE,
            ServiceProtocolId.OPENAI_NATIVE -> OpenAiCompatibleLlmAdapter(
                baseUrl = config.endpointUrl,
                apiKeyProvider = apiKeyProvider,
                defaultModel = offeringModelId ?: config.defaultOfferingId.ifBlank { "gpt-4o-mini" },
                providerId = "${service.providerId}_${service.id}"
            )
            ServiceProtocolId.OLLAMA_NATIVE -> OpenAiCompatibleLlmAdapter(
                baseUrl = config.endpointUrl.ifBlank { "http://127.0.0.1:11434" },
                apiKeyProvider = { null },
                defaultModel = offeringModelId ?: config.defaultOfferingId.ifBlank { "llama3" },
                providerId = "${service.providerId}_${service.id}"
            )
            else -> null
        }
    }

    fun createSearchAdapter(
        service: ProviderService,
        protocolId: ServiceProtocolId,
        config: ServiceConfiguration,
        apiKeyProvider: suspend () -> String?
    ): SearchProviderPort? {
        return when (protocolId) {
            ServiceProtocolId.TAVILY_NATIVE,
            ServiceProtocolId.IN_PROCESS,
            ServiceProtocolId.NATIVE_SDK -> MultiSourceSearchAdapter(
                tavilyApiKeyProvider = apiKeyProvider
            )
            else -> null
        }
    }

    fun createEmbeddingAdapter(
        service: ProviderService,
        protocolId: ServiceProtocolId,
        config: ServiceConfiguration,
        apiKeyProvider: suspend () -> String?,
        offeringModelId: String? = null
    ): EmbeddingProviderPort? {
        return when (protocolId) {
            ServiceProtocolId.IN_PROCESS,
            ServiceProtocolId.NATIVE_SDK -> LocalDeterministicEmbeddingAdapter(
                providerId = "${service.providerId}_${service.id}".lowercase()
            )
            ServiceProtocolId.OPENAI_COMPATIBLE,
            ServiceProtocolId.OPENAI_NATIVE -> OpenAiCompatibleEmbeddingAdapter(
                baseUrl = config.endpointUrl,
                apiKeyProvider = apiKeyProvider,
                model = offeringModelId ?: config.defaultOfferingId.ifBlank { "text-embedding-3-small" }
            )
            ServiceProtocolId.OLLAMA_NATIVE -> OpenAiCompatibleEmbeddingAdapter(
                baseUrl = config.endpointUrl.ifBlank { "http://127.0.0.1:11434" },
                apiKeyProvider = { null },
                model = offeringModelId ?: config.defaultOfferingId.ifBlank { "nomic-embed-text" }
            )
            else -> null
        }
    }
}
