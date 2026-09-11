package com.example.infrastructure.memory.semantic

import android.content.Context
import com.example.domain.core.Outcome
import com.example.domain.core.memory.EmbeddingFailure
import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.SafeEmbeddingProviderMetadata
import com.example.domain.ports.memory.EmbeddingProviderPort
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.infrastructure.network.GovernedHttpClientFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * ============================================================================
 * OnnxSemanticEmbeddingAdapter — REAL on-device semantic embeddings
 * ============================================================================
 *
 * Closes the local-first RAG gap (audit 2026): previously the ONLY local
 * embedding was `LocalDeterministicEmbeddingAdapter` — a hash-based pseudo
 * vector generator with no semantics. This adapter runs a REAL
 * sentence-transformer model (all-MiniLM-L6-v2, int8-quantized ONNX,
 * 384-dim) fully on-device via ONNX Runtime.
 *
 * Honest provisioning model (no fake success):
 *  - The model + vocab are provisioned ONCE by [provision] (downloaded from
 *    HuggingFace with progress; stored in the app's private filesDir).
 *  - Before provisioning, [isProvisioned] is false and [generateEmbeddings]
 *    returns an explicit `EmbeddingUnavailable` — callers fall back to
 *    provider-backed (cloud) embeddings or the honestly-labeled LEXICAL
 *    fallback. The adapter NEVER fabricates semantic vectors.
 *
 * Inference pipeline:
 *   text → WordPieceTokenizer → inputIds/attentionMask/tokenTypeIds tensors
 *   → OrtSession.run → last_hidden_state → mean pooling (attention-masked)
 *   → L2 normalization → EmbeddingVector(384).
 */
class OnnxSemanticEmbeddingAdapter(
    private val appContext: Context,
    override val providerId: String = "onnx_minilm_l6_local",
    private val modelSourceUrl: String =
        "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx",
    private val vocabSourceUrl: String =
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt",
    override val dimension: Int = 384,
    /**
     * GAP-03 (Design Closure 2026, ADR-3): governed client — EgressControl
     * interceptor always installed, so provisioning the ~23MB ONNX model is
     * subject to workspace egress policy (an OFFLINE workspace denies the
     * download BEFORE any socket is opened — previously this private client
     * bypassed egress governance entirely, which the audit called out as a
     * live egress bypass activatable from the Knowledge screen).
     */
    private val client: OkHttpClient = GovernedHttpClientFactory()
        .create(connectTimeoutSeconds = 20, readTimeoutSeconds = 120, writeTimeoutSeconds = 120)
) : EmbeddingProviderPort {

    override val metadata: SafeEmbeddingProviderMetadata
        get() = SafeEmbeddingProviderMetadata(
            id = providerId,
            name = if (isProvisioned) "MiniLM-L6 Semantic (on-device)" else "MiniLM-L6 Semantic (نموذج غير مُحمَّل بعد)",
            providerType = "ONNX_ON_DEVICE_SEMANTIC",
            dimension = dimension,
            isLocal = true,
            isEnabled = isProvisioned
        )

    private val modelDir: File
        get() = File(appContext.filesDir, "semantic_embedding").apply { mkdirs() }
    private val modelFile: File get() = File(modelDir, "minilm_l6_int8.onnx")
    private val vocabFile: File get() = File(modelDir, "vocab.txt")

    @Volatile
    private var tokenizer: WordPieceTokenizer? = null

    @Volatile
    private var session: OrtSession? = null

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /** TRUE when the real semantic model is provisioned and loadable. */
    val isProvisioned: Boolean
        get() = modelFile.exists() && modelFile.length() > 1_000_000L && vocabFile.exists()

    fun isSemanticModelAvailable(): Boolean = isProvisioned

    /**
     * Downloads the ONNX model (~23MB int8) + WordPiece vocab. Idempotent —
     * returns Success immediately when already provisioned. Honest failure
     * on any network/IO error (no fabricated state).
     */
    suspend fun provision(): Outcome<Unit, String> = withContext(Dispatchers.IO) {
        if (isProvisioned) return@withContext Outcome.Success(Unit)
        try {
            modelDir.mkdirs()
            val tmpModel = File(modelDir, modelFile.name + ".tmp")
            val modelRequest = Request.Builder().url(modelSourceUrl).build()
            client.newCall(modelRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Outcome.Error(
                        "فشل تحميل نموذج التضمين الدلالي (HTTP ${response.code})."
                    )
                }
                response.body?.byteStream()?.use { input ->
                    tmpModel.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext Outcome.Error("استجابة فارغة أثناء تحميل نموذج التضمين.")
            }
            val vocabRequest = Request.Builder().url(vocabSourceUrl).build()
            client.newCall(vocabRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    tmpModel.delete()
                    return@withContext Outcome.Error(
                        "فشل تحميل مفردات المُجزِّئ (HTTP ${response.code})."
                    )
                }
                response.body?.string()?.let { vocabText ->
                    vocabFile.writeText(vocabText)
                } ?: run {
                    tmpModel.delete()
                    return@withContext Outcome.Error("استجابة فارغة أثناء تحميل المفردات.")
                }
            }
            // Validate the vocab actually parses before swapping it in.
            if (loadVocab().isEmpty()) {
                vocabFile.delete()
                tmpModel.delete()
                return@withContext Outcome.Error("ملف المفردات غير صالح.")
            }
            if (!tmpModel.renameTo(modelFile)) {
                tmpModel.copyTo(modelFile, overwrite = true)
                tmpModel.delete()
            }
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error("تعذر تهيئة النموذج الدلالي المحلي: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Removes the provisioned model (user-initiated storage reclaim). */
    fun unprovision() {
        modelFile.delete()
        vocabFile.delete()
        tokenizer = null
        runCatching { session?.close() }
        session = null
    }

    override suspend fun generateEmbeddings(texts: List<String>): Outcome<List<EmbeddingVector>, EmbeddingFailure> =
        withContext(Dispatchers.Default) {
            if (texts.isEmpty()) return@withContext Outcome.Success(emptyList())
            if (!isProvisioned) {
                return@withContext Outcome.Error(
                    EmbeddingFailure.EmbeddingUnavailable(
                        providerId,
                        "النموذج الدلالي المحلي غير مُحمَّل بعد — حمّله من شاشة المعرفة أو استخدم مضمّناً سحابياً."
                    ),
                    "Model not provisioned — honest unavailability (no fake vectors)."
                )
            }
            val tok = ensureTokenizer()
                ?: return@withContext Outcome.Error(
                    EmbeddingFailure.EmbeddingUnavailable(providerId, "تعذر تحميل مفردات المُجزِّئ.")
                )
            val ses = ensureSession()
                ?: return@withContext Outcome.Error(
                    EmbeddingFailure.EmbeddingUnavailable(providerId, "تعذر تحميل جلسة ONNX للنموذج الدلالي.")
                )
            try {
                val encodings = tok.encodeBatch(texts)
                val batch = encodings.size
                val seqLen = tok.maxSequenceLength

                // Flatten to a contiguous [batch × seq] int64 buffer — the
                // overload ONNX Runtime exposes for LongBuffers.
                val flatIds = LongArray(batch * seqLen)
                val flatMask = LongArray(batch * seqLen)
                val flatTypes = LongArray(batch * seqLen)
                for (b in 0 until batch) {
                    System.arraycopy(encodings[b].inputIds, 0, flatIds, b * seqLen, seqLen)
                    System.arraycopy(encodings[b].attentionMask, 0, flatMask, b * seqLen, seqLen)
                    System.arraycopy(encodings[b].tokenTypeIds, 0, flatTypes, b * seqLen, seqLen)
                }
                val shape = longArrayOf(batch.toLong(), seqLen.toLong())

                val idTensor = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(flatIds), shape)
                val maskTensor = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(flatMask), shape)
                val inputs = mutableMapOf<String, OnnxTensor>()
                inputs[inputIdsInputName(ses) ?: "input_ids"] = idTensor
                inputs[attentionMaskInputName(ses) ?: "attention_mask"] = maskTensor
                tokenTypeInputName(ses)?.let { name ->
                    inputs[name] = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(flatTypes), shape)
                }

                ses.run(inputs).use { results ->
                    val outputName = ses.outputNames.firstOrNull { it.contains("last_hidden_state") }
                        ?: ses.outputNames.first()
                    val rawAny = results[outputName].get().value
                    @Suppress("UNCHECKED_CAST")
                    val raw = rawAny as Array<Array<FloatArray>>
                    // raw: [batch][seq][hidden]
                    val vectors = Array(batch) { b ->
                        meanPool(raw[b], encodings[b].attentionMask)
                    }
                    val out = vectors.map { EmbeddingVector(it) }
                    Outcome.Success(out)
                }
            } catch (e: Exception) {
                Outcome.Error(
                    EmbeddingFailure.EmbeddingUnavailable(
                        providerId,
                        "فشل الاستدلال المحلي للنموذج الدلالي: ${e.message ?: e.javaClass.simpleName}"
                    ),
                    "Inference failure: ${e.message}"
                )
            }
        }

    /** Attention-masked mean pooling + L2 normalization (sentence-transformers semantics). */
    private fun meanPool(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val hiddenDim = if (hidden.isNotEmpty()) hidden[0].size else dimension
        val summed = FloatArray(hiddenDim)
        var maskSum = 0f
        for (t in hidden.indices) {
            val m = mask.getOrElse(t) { 0L }.toFloat()
            if (m > 0f) {
                maskSum += m
                val tokenVec = hidden[t]
                for (d in 0 until hiddenDim) summed[d] += tokenVec[d] * m
            }
        }
        if (maskSum <= 0f) maskSum = 1f
        var sq = 0.0
        for (d in summed.indices) {
            summed[d] /= maskSum
            sq += (summed[d] * summed[d]).toDouble()
        }
        val norm = kotlin.math.sqrt(sq).toFloat()
        if (norm > 1e-6f) {
            for (d in summed.indices) summed[d] /= norm
        }
        return summed
    }

    private fun inputIdsInputName(ses: OrtSession): String? =
        ses.inputNames.firstOrNull { it.contains("input_ids") }
    private fun attentionMaskInputName(ses: OrtSession): String? =
        ses.inputNames.firstOrNull { it.contains("attention_mask") }
    private fun tokenTypeInputName(ses: OrtSession): String? =
        ses.inputNames.firstOrNull { it.contains("token_type") }

    private fun ensureTokenizer(): WordPieceTokenizer? {
        tokenizer?.let { return it }
        return synchronized(this) {
            tokenizer?.let { return@synchronized it }
            val vocab = loadVocab()
            if (vocab.isEmpty()) return@synchronized null
            WordPieceTokenizer(vocab = vocab, maxSequenceLength = 256).also { tokenizer = it }
        }
    }

    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        return synchronized(this) {
            session?.let { return@synchronized it }
            if (!isProvisioned) return@synchronized null
            runCatching {
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                }
                env.createSession(modelFile.absolutePath, opts)
            }.getOrNull()?.also { session = it }
        }
    }

    private fun loadVocab(): Map<String, Int> {
        if (!vocabFile.exists()) return emptyMap()
        return try {
            val map = HashMap<String, Int>(32_000)
            vocabFile.useLines { lines ->
                var idx = 0
                lines.forEach { line ->
                    map[line] = idx
                    idx++
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
