package com.example.infrastructure.memory

import com.example.domain.core.Outcome
import com.example.domain.core.OutcomeMetadata
import com.example.domain.core.memory.EmbeddingFailure
import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.SafeEmbeddingProviderMetadata
import com.example.domain.ports.memory.EmbeddingProviderPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * P0.6 — REAL external embedding adapter for OpenAI-compatible /v1/embeddings
 * endpoints (APPROVED-BASELINE v2.1, Section J).
 *
 * Before P0.6, ProviderAdapterFactory.createEmbeddingAdapter silently returned
 * LocalDeterministicEmbeddingAdapter for EVERY flavor — including configured
 * external providers — which is exactly the adapter-internal substitution path
 * forbidden by RULE AD-3. This adapter provides the real external path so the
 * factory no longer needs any substitution.
 *
 * On any transport failure it returns an explicit Outcome.Error — it NEVER falls
 * back to a local/lexical embedder (RULE AD-3).
 */
class OpenAiCompatibleEmbeddingAdapter(
    override val providerId: String,
    private val endpointUrl: String,
    private val modelName: String,
    private val apiKeyProvider: () -> String?,
    private val client: OkHttpClient,
    override val dimension: Int = 1536
) : EmbeddingProviderPort {

    override val metadata: SafeEmbeddingProviderMetadata
        get() = SafeEmbeddingProviderMetadata(
            id = providerId,
            name = "OpenAI-Compatible Embeddings ($modelName)",
            providerType = "REMOTE_OPENAI_COMPATIBLE_EMBEDDING",
            dimension = dimension,
            isLocal = false,
            isEnabled = true
        )

    override suspend fun generateEmbeddings(texts: List<String>): Outcome<List<EmbeddingVector>, EmbeddingFailure> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext Outcome.Success(emptyList())

            val startTime = System.currentTimeMillis()
            try {
                val requestBody = JSONObject()
                    .put("model", modelName)
                    .put("input", JSONArray(texts))
                    .toString()

                val requestBuilder = Request.Builder()
                    .url(endpointUrl)
                    .post(requestBody.toRequestBody("application/json".toMediaType()))

                val apiKey = apiKeyProvider()
                if (!apiKey.isNullOrBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    val latency = System.currentTimeMillis() - startTime
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // Explicit transport failure — NO local substitution (RULE AD-3).
                        return@withContext Outcome.Error(
                            failure = EmbeddingFailure.EmbeddingUnavailable(
                                providerId,
                                "HTTP ${response.code} من مزود التضمين الخارجي: ${body.take(200)}"
                            ),
                            diagnosticMessage = "External embedding transport failure (HTTP ${response.code}) in ${latency}ms"
                        )
                    }

                    val json = JSONObject(body)
                    val dataArray = json.optJSONArray("data")
                        ?: return@withContext Outcome.Error(
                            failure = EmbeddingFailure.EmbeddingUnavailable(
                                providerId,
                                "استجابة مزود التضمين لا تحتوي على مصفوفة data."
                            ),
                            diagnosticMessage = "Malformed embedding response: missing data array"
                        )

                    val vectors = mutableListOf<EmbeddingVector>()
                    for (i in 0 until dataArray.length()) {
                        val entry = dataArray.getJSONObject(i)
                        val embeddingArray = entry.optJSONArray("embedding")
                            ?: return@withContext Outcome.Error(
                                failure = EmbeddingFailure.EmbeddingUnavailable(
                                    providerId,
                                    "عنصر التضمين لا يحتوي على مصفوفة embedding."
                                ),
                                diagnosticMessage = "Malformed embedding entry: missing embedding array"
                            )
                        val values = FloatArray(embeddingArray.length()) { j ->
                            embeddingArray.getDouble(j).toFloat()
                        }
                        vectors.add(EmbeddingVector(dimension = values.size, values = values))
                    }

                    Outcome.Success(
                        value = vectors,
                        metadata = OutcomeMetadata(durationMs = latency, providerId = providerId)
                    )
                }
            } catch (e: Exception) {
                // Explicit transport failure — NO local substitution (RULE AD-3).
                Outcome.Error(
                    failure = EmbeddingFailure.EmbeddingUnavailable(
                        providerId,
                        "فشل الاتصال بمزود التضمين الخارجي: ${e.localizedMessage}"
                    ),
                    diagnosticMessage = e.message ?: "External embedding error"
                )
            }
        }
}
