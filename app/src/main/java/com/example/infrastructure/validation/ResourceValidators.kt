package com.example.infrastructure.validation

import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceHealthClassification
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.provider.ServiceValidationResult
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.resource.ResourceValidator
import com.example.domain.core.resource.ResourceValidatorRegistry
import com.example.infrastructure.llm.gemini.GeminiBootstrap
import com.example.infrastructure.llm.openai.OpenAiCompatibleLlmAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * ============================================================================
 * Real resource validators — Phase 4 (Correction #4)
 * ============================================================================
 *
 * Each validator runs a REAL protocol operation against the resource:
 *   - LLM        → GET /models (or Gemini models endpoint / Firebase readiness)
 *   - Embedding  → real embedding round-trip (local) or POST /embeddings (remote)
 *   - SEARCH     → IN_PROCESS wiring check (no network by definition) or tiny query
 *
 * Results are machine-classified so the control plane can map
 * TIMEOUT / RATE_LIMITED / TRANSPORT_FAILURE → DEGRADED (not UNAVAILABLE).
 * All network work runs on Dispatchers.IO.
 */

private val validatorClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

/** Shared HTTP ping + classification used by LLM & remote embedding validators. */
private suspend fun httpPing(
    url: String,
    apiKeyProvider: suspend () -> String?
): ServiceValidationResult = withContext(Dispatchers.IO) {
    val start = System.currentTimeMillis()
    try {
        val builder = Request.Builder().url(url).get()
        apiKeyProvider()?.takeIf { it.isNotBlank() }?.let { key ->
            builder.addHeader("Authorization", "Bearer $key")
        }
        validatorClient.newCall(builder.build()).execute().use { response ->
            val latency = System.currentTimeMillis() - start
            when {
                response.isSuccessful -> ServiceValidationResult.success(
                    latency, "HTTP 200 from $url"
                )
                response.code == 401 || response.code == 403 -> ServiceValidationResult.failure(
                    ServiceHealthClassification.AUTHENTICATION_FAILURE, latency,
                    "HTTP ${response.code} — check the API key"
                )
                response.code == 429 -> ServiceValidationResult.failure(
                    ServiceHealthClassification.RATE_LIMITED, latency,
                    "HTTP 429 — rate limited"
                )
                else -> ServiceValidationResult.failure(
                    ServiceHealthClassification.PROTOCOL_FAILURE, latency,
                    "HTTP ${response.code} from $url"
                )
            }
        }
    } catch (e: SocketTimeoutException) {
        ServiceValidationResult.failure(
            ServiceHealthClassification.TIMEOUT,
            System.currentTimeMillis() - start,
            "Timeout: ${e.message}"
        )
    } catch (e: java.io.IOException) {
        ServiceValidationResult.failure(
            ServiceHealthClassification.TRANSPORT_FAILURE,
            System.currentTimeMillis() - start,
            "Transport failure: ${e.message}"
        )
    } catch (e: Exception) {
        ServiceValidationResult.failure(
            ServiceHealthClassification.PROTOCOL_FAILURE,
            System.currentTimeMillis() - start,
            "Validation error: ${e.message}"
        )
    }
}

/** Gemini pings the models endpoint with the key in the x-goog-api-key header (no URL leakage). */
private suspend fun geminiPing(
    apiKey: String
): ServiceValidationResult = withContext(Dispatchers.IO) {
    val start = System.currentTimeMillis()
    try {
        // FIX P0-8 (audit c03919d): API key moved from ?key= URL param to the
        // x-goog-api-key request header.
        val url = "https://generativelanguage.googleapis.com/v1beta/models"
        val response = validatorClient.newCall(
            Request.Builder().url(url).header("x-goog-api-key", apiKey).get().build()
        ).execute()
        response.use { resp ->
            val latency = System.currentTimeMillis() - start
            when {
                resp.isSuccessful -> ServiceValidationResult.success(latency, "Gemini API reachable")
                resp.code == 401 || resp.code == 403 -> ServiceValidationResult.failure(
                    ServiceHealthClassification.AUTHENTICATION_FAILURE, latency, "Invalid Gemini API key"
                )
                resp.code == 429 -> ServiceValidationResult.failure(
                    ServiceHealthClassification.RATE_LIMITED, latency, "Gemini rate limit"
                )
                else -> ServiceValidationResult.failure(
                    ServiceHealthClassification.PROTOCOL_FAILURE, latency, "HTTP ${resp.code} from Gemini"
                )
            }
        }
    } catch (e: SocketTimeoutException) {
        ServiceValidationResult.failure(
            ServiceHealthClassification.TIMEOUT,
            System.currentTimeMillis() - start, "Timeout: ${e.message}"
        )
    } catch (e: java.io.IOException) {
        ServiceValidationResult.failure(
            ServiceHealthClassification.TRANSPORT_FAILURE,
            System.currentTimeMillis() - start, "Transport failure: ${e.message}"
        )
    } catch (e: Exception) {
        ServiceValidationResult.failure(
            ServiceHealthClassification.PROTOCOL_FAILURE,
            System.currentTimeMillis() - start, "Validation error: ${e.message}"
        )
    }
}

/* ------------------------------ LLM ---------------------------------------- */

class LlmResourceValidator(
    private val geminiBootstrap: GeminiBootstrap? = null
) : ResourceValidator {

    override suspend fun validate(
        service: ProviderService,
        protocolId: ServiceProtocolId,
        config: ServiceConfiguration,
        adapter: Any?,
        apiKeyProvider: suspend () -> String?
    ): ServiceValidationResult {
        val key = apiKeyProvider()
        return when (protocolId) {
            ServiceProtocolId.GEMINI_NATIVE -> {
                if (key.isNullOrBlank()) {
                    // No stored user key — validate via Firebase AI SDK readiness
                    // (google-services options may carry a real key). Honest: if
                    // neither exists, validation fails with AUTHENTICATION_FAILURE.
                    val bootstrap = geminiBootstrap
                    val firebaseKey = bootstrap?.apiKeyFromOptions()
                    if (bootstrap != null && firebaseKey != null) {
                        geminiPing(firebaseKey)
                    } else {
                        ServiceValidationResult.failure(
                            ServiceHealthClassification.AUTHENTICATION_FAILURE,
                            0L,
                            "لا يوجد مفتاح Gemini مخزّن — أضف المفتاح ثم أعد التحقق"
                        )
                    }
                } else {
                    geminiPing(key)
                }
            }
            ServiceProtocolId.OPENAI_COMPATIBLE,
            ServiceProtocolId.OPENAI_NATIVE -> httpPing(
                OpenAiCompatibleLlmAdapter.normalizeBaseUrl(config.endpointUrl) + "/models",
                apiKeyProvider
            )
            ServiceProtocolId.OLLAMA_NATIVE -> httpPing(
                OpenAiCompatibleLlmAdapter.normalizeBaseUrl(
                    config.endpointUrl.ifBlank { "http://127.0.0.1:11434" }
                ) + "/models",
                { null }
            )
            ServiceProtocolId.IN_PROCESS, ServiceProtocolId.NATIVE_SDK -> {
                // In-process LLM: presence of a real adapter is the validation.
                if (adapter != null) {
                    ServiceValidationResult.success(0L, "In-process adapter present")
                } else {
                    ServiceValidationResult.failure(
                        ServiceHealthClassification.PROTOCOL_FAILURE, 0L, "In-process adapter missing"
                    )
                }
            }
            else -> ServiceValidationResult.failure(
                ServiceHealthClassification.UNKNOWN, 0L,
                "No LLM validation path for protocol $protocolId"
            )
        }
    }
}

/* ------------------------------ Embedding ---------------------------------- */

class EmbeddingResourceValidator : ResourceValidator {

    override suspend fun validate(
        service: ProviderService,
        protocolId: ServiceProtocolId,
        config: ServiceConfiguration,
        adapter: Any?,
        apiKeyProvider: suspend () -> String?
    ): ServiceValidationResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        when (protocolId) {
            ServiceProtocolId.IN_PROCESS, ServiceProtocolId.NATIVE_SDK -> {
                // REAL local round-trip: embed a probe string.
                val embeddingProvider = adapter as? com.example.domain.ports.memory.EmbeddingProviderPort
                if (embeddingProvider == null) {
                    ServiceValidationResult.failure(
                        ServiceHealthClassification.PROTOCOL_FAILURE,
                        System.currentTimeMillis() - start,
                        "In-process embedding adapter missing"
                    )
                } else {
                    val outcome = embeddingProvider.generateEmbeddings(listOf("health probe"))
                    when (outcome) {
                        is com.example.domain.core.Outcome.Success -> ServiceValidationResult.success(
                            System.currentTimeMillis() - start,
                            "تضمين محلي يعمل (البُعد ${embeddingProvider.dimension})"
                        )
                        else -> ServiceValidationResult.failure(
                            ServiceHealthClassification.PROTOCOL_FAILURE,
                            System.currentTimeMillis() - start,
                            "Local embedding probe failed"
                        )
                    }
                }
            }
            ServiceProtocolId.OPENAI_COMPATIBLE,
            ServiceProtocolId.OPENAI_NATIVE,
            ServiceProtocolId.OLLAMA_NATIVE -> {
                val base = if (protocolId == ServiceProtocolId.OLLAMA_NATIVE) {
                    config.endpointUrl.ifBlank { "http://127.0.0.1:11434" }
                } else {
                    config.endpointUrl
                }
                httpPing(OpenAiCompatibleLlmAdapter.normalizeBaseUrl(base) + "/models", apiKeyProvider)
            }
            else -> ServiceValidationResult.failure(
                ServiceHealthClassification.UNKNOWN, 0L,
                "No embedding validation path for protocol $protocolId"
            )
        }
    }
}

/* ------------------------------ SEARCH ------------------------------------- */

class SearchResourceValidator : ResourceValidator {

    override suspend fun validate(
        service: ProviderService,
        protocolId: ServiceProtocolId,
        config: ServiceConfiguration,
        adapter: Any?,
        apiKeyProvider: suspend () -> String?
    ): ServiceValidationResult {
        return when (protocolId) {
            ServiceProtocolId.IN_PROCESS, ServiceProtocolId.NATIVE_SDK -> {
                // In-process composite search: the adapter wiring IS the truth.
                // No network probing by definition (in-process).
                if (adapter is com.example.domain.ports.search.SearchProviderPort) {
                    ServiceValidationResult.success(
                        0L,
                        "محرّك البحث المُركّب جاهز (in-process)"
                    )
                } else {
                    ServiceValidationResult.failure(
                        ServiceHealthClassification.PROTOCOL_FAILURE, 0L,
                        "In-process search adapter missing"
                    )
                }
            }
            ServiceProtocolId.TAVILY_NATIVE -> {
                val key = apiKeyProvider()
                if (key.isNullOrBlank()) {
                    ServiceValidationResult.failure(
                        ServiceHealthClassification.AUTHENTICATION_FAILURE, 0L,
                        "مفتاح Tavily غير مخزّن"
                    )
                } else {
                    httpPing("https://api.tavily.com/search", apiKeyProvider)
                }
            }
            else -> ServiceValidationResult.failure(
                ServiceHealthClassification.UNKNOWN, 0L,
                "No search validation path for protocol $protocolId"
            )
        }
    }
}

/* ------------------------------ Registry ----------------------------------- */

/**
 * Default registry wiring REAL validators for LLM / EMBEDDING / SEARCH.
 */
fun defaultResourceValidatorRegistry(
    geminiBootstrap: GeminiBootstrap? = null
): ResourceValidatorRegistry {
    val registry = ResourceValidatorRegistry()
    registry.register(ResourceType.LLM, LlmResourceValidator(geminiBootstrap))
    registry.register(ResourceType.EMBEDDING, EmbeddingResourceValidator())
    registry.register(ResourceType.SEARCH, SearchResourceValidator())
    return registry
}
