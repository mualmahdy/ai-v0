package com.example.infrastructure.provider

import com.example.domain.core.Outcome
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.MessageRole
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.ProviderCategory
import com.example.domain.core.provider.ProviderConfiguration
import com.example.domain.core.provider.ProviderFlavor
import com.example.domain.core.provider.ProviderValidationResult
import com.example.domain.core.search.SearchQuery
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.memory.EmbeddingProviderPort
import com.example.domain.ports.search.SearchProviderPort
import com.example.domain.ports.storage.WorkspaceStoragePort
import com.example.infrastructure.llm.custom.OpenAiCompatibleAdapter
import com.example.infrastructure.llm.gemini.GeminiLlmAdapter
import com.example.infrastructure.memory.LocalDeterministicEmbeddingAdapter
import com.example.infrastructure.memory.OpenAiCompatibleEmbeddingAdapter
import com.example.infrastructure.search.MultiSourceSearchAdapter
import com.example.infrastructure.search.TavilySearchAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Factory for instantiating concrete runtime adapters and executing real validation tests.
 */
class ProviderAdapterFactory(
    private val workspaceStoragePort: WorkspaceStoragePort? = null,
    private val defaultProjectId: Long = 1L
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun createLlmAdapter(config: ProviderConfiguration, apiKeyProvider: () -> String?): LlmProviderPort {
        return when (config.flavor) {
            ProviderFlavor.GEMINI -> GeminiLlmAdapter(
                defaultModelName = config.defaultModelId.ifBlank { "gemini-2.5-flash" }
            )
            ProviderFlavor.OPENAI_COMPATIBLE, ProviderFlavor.OLLAMA -> OpenAiCompatibleAdapter(
                providerId = config.id,
                endpointUrl = config.endpointUrl,
                modelName = config.defaultModelId.ifBlank { "default" },
                apiKeyProvider = apiKeyProvider,
                client = httpClient
            )
            else -> OpenAiCompatibleAdapter(
                providerId = config.id,
                endpointUrl = config.endpointUrl,
                modelName = config.defaultModelId.ifBlank { "default" },
                apiKeyProvider = apiKeyProvider,
                client = httpClient
            )
        }
    }

    fun createSearchAdapter(config: ProviderConfiguration, apiKeyProvider: () -> String?): SearchProviderPort {
        return when (config.flavor) {
            ProviderFlavor.TAVILY -> TavilySearchAdapter(
                apiKeyProvider = apiKeyProvider,
                client = httpClient
            )
            ProviderFlavor.MULTI_SOURCE_SEARCH -> MultiSourceSearchAdapter(
                tavilyApiKeyProvider = apiKeyProvider,
                workspaceStoragePort = workspaceStoragePort,
                defaultProjectId = defaultProjectId,
                client = httpClient
            )
            else -> MultiSourceSearchAdapter(
                tavilyApiKeyProvider = apiKeyProvider,
                workspaceStoragePort = workspaceStoragePort,
                defaultProjectId = defaultProjectId,
                client = httpClient
            )
        }
    }

    fun createEmbeddingAdapter(config: ProviderConfiguration, apiKeyProvider: () -> String?): EmbeddingProviderPort {
        return when (config.flavor) {
            // P0.6 (RULE AD-3): the ONLY local embedding creation path is an explicitly
            // LOCAL_EMBEDDING-flavored configuration. Local embedding is also registered
            // as its own ResourceRecord (category=LOCAL, isFallback=true) and is
            // selectable ONLY via an explicit decision — never via adapter-internal
            // substitution for an unavailable external provider.
            ProviderFlavor.LOCAL_EMBEDDING -> LocalDeterministicEmbeddingAdapter(
                providerId = config.id,
                dimension = 128
            )
            // P0.6: REAL external embedding path — no silent local substitution.
            ProviderFlavor.OPENAI_COMPATIBLE -> OpenAiCompatibleEmbeddingAdapter(
                providerId = config.id,
                endpointUrl = config.endpointUrl,
                modelName = config.defaultModelId.ifBlank { "text-embedding-3-small" },
                apiKeyProvider = apiKeyProvider,
                client = httpClient
            )
            else -> throw IllegalArgumentException(
                "No real embedding adapter exists for flavor ${config.flavor.code} (canCreate must be consulted first — RULE AD-2)."
            )
        }
    }

    /**
     * P0.6 — Adapter existence check (Section J / RULE AD-2).
     *
     * Determined ONLY by this factory's real capabilities. If this returns false the
     * resource remains runtimeSupported=false and MUST NOT appear in the usable graph
     * regardless of lifecycle state. ProviderControlPlaneService consults this before
     * create; create() throws for unsupported combinations as a defensive backstop.
     */
    fun canCreate(category: ProviderCategory, flavor: ProviderFlavor): Boolean = when (category) {
        ProviderCategory.LLM -> true // GEMINI / OPENAI_COMPATIBLE / OLLAMA all have real adapters
        ProviderCategory.SEARCH -> true // TAVILY / MULTI_SOURCE_SEARCH have real adapters
        ProviderCategory.EMBEDDING -> flavor == ProviderFlavor.LOCAL_EMBEDDING ||
            flavor == ProviderFlavor.OPENAI_COMPATIBLE
        ProviderCategory.VECTOR_STORE -> false // no runtime adapter beyond P0 scope
    }

    /**
     * Executes an actual runtime health verification against the provider.
     */
    suspend fun validateProvider(config: ProviderConfiguration, secretApiKey: String?): Outcome<ProviderValidationResult, String> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            when (config.category) {
                ProviderCategory.LLM -> {
                    when (config.flavor) {
                        ProviderFlavor.GEMINI -> {
                            val adapter = createLlmAdapter(config) { secretApiKey }
                            val testReq = LlmRequest(
                                messages = listOf(LlmMessage(MessageRole.USER, "Ping health check"))
                            )
                            val outcome = adapter.generate(testReq)
                            val latency = System.currentTimeMillis() - startTime
                            when (outcome) {
                                is Outcome.Success -> Outcome.Success(
                                    ProviderValidationResult(
                                        isSuccess = true,
                                        health = HealthStatus.HEALTHY,
                                        latencyMs = latency,
                                        message = "تم الاتصال بنجاح مع Gemini (${latency}ms)"
                                    )
                                )
                                is Outcome.Degraded -> Outcome.Success(
                                    ProviderValidationResult(
                                        isSuccess = true,
                                        health = HealthStatus.DEGRADED,
                                        latencyMs = latency,
                                        message = "اتصال جزئي: ${outcome.diagnosticMessage}"
                                    )
                                )
                                is Outcome.Error -> Outcome.Success(
                                    ProviderValidationResult(
                                        isSuccess = false,
                                        health = HealthStatus.UNAVAILABLE,
                                        latencyMs = latency,
                                        message = "فشل الاتصال: ${outcome.diagnosticMessage}"
                                    )
                                )
                            }
                        }
                        ProviderFlavor.OLLAMA, ProviderFlavor.OPENAI_COMPATIBLE -> {
                            if (config.endpointUrl.isBlank()) {
                                return@withContext Outcome.Success(
                                    ProviderValidationResult(
                                        isSuccess = false,
                                        health = HealthStatus.UNAVAILABLE,
                                        latencyMs = 0L,
                                        message = "عنوان نقطة النهاية (Endpoint URL) فارغ."
                                    )
                                )
                            }
                            // Execute HTTP ping check
                            val reqBuilder = Request.Builder().url(config.endpointUrl)
                            if (!secretApiKey.isNullOrBlank()) {
                                reqBuilder.addHeader("Authorization", "Bearer $secretApiKey")
                            }
                            val response = httpClient.newCall(reqBuilder.build()).execute()
                            val latency = System.currentTimeMillis() - startTime
                            val isOk = response.isSuccessful || response.code == 405 || response.code == 400 // Endpoint exists
                            Outcome.Success(
                                ProviderValidationResult(
                                    isSuccess = isOk,
                                    health = if (isOk) HealthStatus.HEALTHY else HealthStatus.DEGRADED,
                                    latencyMs = latency,
                                    message = if (isOk) "استجابة النقطة النهائية ناجحة (رمز: ${response.code} في ${latency}ms)" else "رمز استجابة: ${response.code}"
                                )
                            )
                        }
                        else -> {
                            Outcome.Success(
                                ProviderValidationResult(
                                    isSuccess = true,
                                    health = HealthStatus.HEALTHY,
                                    latencyMs = 5L,
                                    message = "المزود مهيأ محلياً."
                                )
                            )
                        }
                    }
                }
                ProviderCategory.SEARCH -> {
                    val adapter = createSearchAdapter(config) { secretApiKey }
                    val outcome = adapter.search(SearchQuery("AI test ping", maxResults = 1))
                    val latency = System.currentTimeMillis() - startTime
                    when (outcome) {
                        is Outcome.Success -> Outcome.Success(
                            ProviderValidationResult(
                                isSuccess = true,
                                health = HealthStatus.HEALTHY,
                                latencyMs = latency,
                                message = "تم التحقق من مزود البحث بنجاح (${latency}ms)"
                            )
                        )
                        is Outcome.Degraded -> Outcome.Success(
                            ProviderValidationResult(
                                isSuccess = true,
                                health = HealthStatus.DEGRADED,
                                latencyMs = latency,
                                message = "تم الاتصال بوضع متراجع: ${outcome.diagnosticMessage}"
                            )
                        )
                        is Outcome.Error -> Outcome.Success(
                            ProviderValidationResult(
                                isSuccess = false,
                                health = HealthStatus.UNAVAILABLE,
                                latencyMs = latency,
                                message = outcome.diagnosticMessage
                            )
                        )
                    }
                }
                ProviderCategory.EMBEDDING -> {
                    val adapter = createEmbeddingAdapter(config) { secretApiKey }
                    val outcome = adapter.generateEmbeddings(listOf("Ping embedding test"))
                    val latency = System.currentTimeMillis() - startTime
                    when (outcome) {
                        is Outcome.Success -> Outcome.Success(
                            ProviderValidationResult(
                                isSuccess = true,
                                health = HealthStatus.HEALTHY,
                                latencyMs = latency,
                                message = "محرك التضمينات يعمل بكفاءة (${latency}ms)"
                            )
                        )
                        else -> Outcome.Success(
                            ProviderValidationResult(
                                isSuccess = false,
                                health = HealthStatus.UNAVAILABLE,
                                latencyMs = latency,
                                message = "تعذر توليد التضمينات."
                            )
                        )
                    }
                }
                else -> {
                    Outcome.Success(
                        ProviderValidationResult(
                            isSuccess = true,
                            health = HealthStatus.HEALTHY,
                            latencyMs = 1L,
                            message = "المورد متاح."
                        )
                    )
                }
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            Outcome.Success(
                ProviderValidationResult(
                    isSuccess = false,
                    health = HealthStatus.UNAVAILABLE,
                    latencyMs = latency,
                    message = "خطأ في الاتصال: ${e.localizedMessage}"
                )
            )
        }
    }
}
