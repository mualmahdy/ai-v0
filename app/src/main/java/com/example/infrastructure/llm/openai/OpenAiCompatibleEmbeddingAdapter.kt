package com.example.infrastructure.llm.openai

import com.example.domain.core.Outcome
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
import java.util.concurrent.TimeUnit

/**
 * ============================================================================
 * OpenAiCompatibleEmbeddingAdapter — REAL remote embedding adapter
 * ============================================================================
 *
 * Serves OPENAI_COMPATIBLE / OPENAI_NATIVE / OLLAMA_NATIVE embedding services
 * by POSTing to `${baseUrl}/embeddings` (OpenAI embeddings wire format).
 * All network runs on Dispatchers.IO; failures map to the domain
 * EmbeddingFailure taxonomy.
 */
class OpenAiCompatibleEmbeddingAdapter(
    private val baseUrl: String,
    private val apiKeyProvider: suspend () -> String?,
    private val model: String = "text-embedding-3-small",
    override val providerId: String = "openai_compatible_embedding",
    override val dimension: Int = 1536,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) : EmbeddingProviderPort {

    override val metadata: SafeEmbeddingProviderMetadata
        get() = SafeEmbeddingProviderMetadata(
            id = providerId,
            name = "OpenAI-Compatible Embeddings",
            providerType = "OPENAI_COMPATIBLE",
            dimension = dimension,
            isLocal = false,
            isEnabled = true
        )

    override suspend fun generateEmbeddings(texts: List<String>): Outcome<List<EmbeddingVector>, EmbeddingFailure> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) {
                return@withContext Outcome.Success(emptyList())
            }
            try {
                val input = JSONArray()
                texts.forEach { input.put(it) }
                val body = JSONObject().put("model", model).put("input", input)

                val url = OpenAiCompatibleLlmAdapter.normalizeBaseUrl(baseUrl) + "/embeddings"
                val builder = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                apiKeyProvider()?.takeIf { it.isNotBlank() }?.let { key ->
                    builder.addHeader("Authorization", "Bearer $key")
                }

                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Outcome.Error(
                            EmbeddingFailure.EmbeddingUnavailable(
                                providerId,
                                "HTTP ${response.code} from $url"
                            )
                        )
                    }
                    val text = response.body?.string()
                        ?: return@withContext Outcome.Error(
                            EmbeddingFailure.EmbeddingUnavailable(providerId, "Empty body")
                        )
                    val data = JSONObject(text).optJSONArray("data")
                        ?: return@withContext Outcome.Error(
                            EmbeddingFailure.ModelNotFound(model)
                        )
                    val vectors = (0 until data.length()).mapNotNull { i ->
                        val values = data.getJSONObject(i).optJSONArray("embedding") ?: return@mapNotNull null
                        val floats = FloatArray(values.length()) { j -> values.optDouble(j).toFloat() }
                        EmbeddingVector(values = floats)
                    }
                    if (vectors.size != texts.size) {
                        return@withContext Outcome.Error(
                            EmbeddingFailure.EmbeddingUnavailable(
                                providerId,
                                "Expected ${texts.size} vectors, got ${vectors.size}"
                            )
                        )
                    }
                    Outcome.Success(vectors)
                }
            } catch (e: java.net.SocketTimeoutException) {
                Outcome.Error(EmbeddingFailure.EmbeddingUnavailable(providerId, "timeout: ${e.message}"))
            } catch (e: java.io.IOException) {
                Outcome.Error(EmbeddingFailure.EmbeddingUnavailable(providerId, e.message ?: "io error"))
            } catch (e: Exception) {
                Outcome.Error(EmbeddingFailure.EmbeddingUnavailable(providerId, e.message ?: "parse error"))
            }
        }
}
