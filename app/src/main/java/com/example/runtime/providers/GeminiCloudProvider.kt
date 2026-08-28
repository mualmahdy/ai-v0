package com.example.runtime.providers

import com.example.BuildConfig
import com.example.domain.models.ToolCallInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiCloudProvider(
    private var customApiKey: String? = null,
    override val name: String = "Google Gemini Cloud",
    override val providerType: String = "gemini",
    override val isOnlineOnly: Boolean = true
) : BaseModelProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun getApiKey(): String {
        if (!customApiKey.isNullOrBlank()) return customApiKey!!
        return try {
            val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
            field.get(null) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    override suspend fun generate(prompt: String, systemInstruction: String?, model: String?): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            throw IllegalStateException("مفتاح Gemini API غير مهيأ. يرجى إضافته من صفحة المزودين أو لوحة الأسرار.")
        }

        val targetModel = model ?: "gemini-3.5-flash"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=$apiKey"

        val payload = JSONObject()
        val contentsArray = JSONArray()
        val contentObj = JSONObject()
        val partsArray = JSONArray()
        partsArray.put(JSONObject().put("text", prompt))
        contentObj.put("parts", partsArray)
        contentsArray.put(contentObj)
        payload.put("contents", contentsArray)

        if (!systemInstruction.isNullOrBlank()) {
            val sysObj = JSONObject()
            val sysParts = JSONArray()
            sysParts.put(JSONObject().put("text", systemInstruction))
            sysObj.put("parts", sysParts)
            payload.put("systemInstruction", sysObj)
        }

        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw RuntimeException("Gemini API Error (${response.code}): $responseBody")
        }

        val json = JSONObject(responseBody)
        val candidates = json.optJSONArray("candidates")
        val firstCandidate = candidates?.optJSONObject(0)
        val parts = firstCandidate?.optJSONObject("content")?.optJSONArray("parts")
        parts?.optJSONObject(0)?.optString("text") ?: "لا يوجد نص في الرد"
    }

    override fun streamGenerate(prompt: String, systemInstruction: String?, model: String?): Flow<String> = flow {
        val result = generate(prompt, systemInstruction, model)
        val chunks = result.split(" ")
        for (c in chunks) {
            emit("$c ")
            kotlinx.coroutines.delay(20)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun generateWithTools(
        prompt: String,
        availableTools: List<String>,
        systemInstruction: String?
    ): ModelToolResult = withContext(Dispatchers.IO) {
        try {
            val text = generate(prompt, systemInstruction)
            ModelToolResult(content = text, requestedToolCalls = emptyList(), status = "success")
        } catch (e: Exception) {
            ModelToolResult(
                content = null,
                requestedToolCalls = emptyList(),
                status = "error"
            )
        }
    }

    override suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) return@withContext false
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash?key=$apiKey"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}
