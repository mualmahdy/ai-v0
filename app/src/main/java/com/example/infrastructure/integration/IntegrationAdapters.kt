package com.example.infrastructure.integration

import com.example.domain.core.Outcome
import com.example.domain.core.extension.IntegrationDescriptor
import com.example.domain.core.provider.HealthStatus
import com.example.infrastructure.network.EgressControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Real Integration Gateway validating actual credentials against external APIs
 * (GitHub User/Repo APIs, Google Drive About API, Dropbox User Info).
 *
 * P1-15 / SECRETS FIXES (audit 2026 §28):
 *  - The client is now EGRESS-CONTROLLED (the [EgressControl] interceptor is
 *    installed on this client exactly like every other outbound transport —
 *    previously this gateway dialled out on a private OkHttpClient, outside
 *    the workspace network-policy authority).
 *  - Google verification no longer passes the OAuth access token in the URL
 *    query string (it leaked into URLs/logs/proxies). It now verifies via the
 *    Drive v3 `about` endpoint with the token in the Authorization HEADER —
 *    a REAL credential check, not a weaker one.
 *  - Generic verification no longer accepts any non-blank token as valid:
 *    there is no authority that can verify an arbitrary custom token, so the
 *    gateway now fails CLOSED with an explicit unsupported-verification
 *    error instead of fabricating a HEALTHY connected state.
 */
class IntegrationGateway(
    egressControl: EgressControl? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .apply {
            egressControl?.let { control ->
                addInterceptor(control.interceptor())
            }
        }
        .build()
) {

    /**
     * Verifies the provided auth token against the respective third-party provider API.
     */
    suspend fun verifyIntegration(
        descriptor: IntegrationDescriptor,
        authToken: String
    ): Outcome<IntegrationDescriptor, String> = withContext(Dispatchers.IO) {
        if (authToken.isBlank()) {
            return@withContext Outcome.Error("رمز التفويض (Auth Token) فارغ.")
        }

        when (descriptor.serviceType) {
            "GITHUB" -> verifyGitHub(descriptor, authToken)
            "GOOGLE_DRIVE" -> verifyGoogleDrive(descriptor, authToken)
            "DROPBOX" -> verifyDropbox(descriptor, authToken)
            else -> verifyGeneric(descriptor, authToken)
        }
    }

    private fun verifyGitHub(
        descriptor: IntegrationDescriptor,
        token: String
    ): Outcome<IntegrationDescriptor, String> {
        return try {
            val request = Request.Builder()
                .url("https://api.github.com/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AI-V0-Android-Platform")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                val login = json.optString("login", "github_user")
                Outcome.Success(
                    descriptor.copy(
                        isConnected = true,
                        accountIdentifier = login,
                        health = HealthStatus.HEALTHY,
                        lastSyncTimestampMs = System.currentTimeMillis()
                    )
                )
            } else {
                Outcome.Error("فشل التحقق من مفتاح GitHub: خطأ ${response.code}")
            }
        } catch (e: Exception) {
            Outcome.Error("تعذر الاتصال بـ GitHub API: ${e.localizedMessage}")
        }
    }

    private fun verifyGoogleDrive(
        descriptor: IntegrationDescriptor,
        token: String
    ): Outcome<IntegrationDescriptor, String> {
        return try {
            // P1-15 FIX: the OAuth token is sent in the Authorization HEADER
            // (never in the URL query where it leaks into logs/proxies). The
            // Drive v3 `about?fields=user` endpoint both AUTHENTICATES the
            // token and identifies the account — a REAL verification.
            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,displayName)")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "AI-V0-Android-Platform")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                val user = json.optJSONObject("user")
                val email = user?.optString("emailAddress")?.takeIf { it.isNotBlank() }
                    ?: user?.optString("displayName")?.takeIf { it.isNotBlank() }
                    ?: "google_drive_user"
                Outcome.Success(
                    descriptor.copy(
                        isConnected = true,
                        accountIdentifier = email,
                        health = HealthStatus.HEALTHY,
                        lastSyncTimestampMs = System.currentTimeMillis()
                    )
                )
            } else {
                Outcome.Error("رمز وصول Google Drive غير صالح أو منتهي الصلاحية.")
            }
        } catch (e: Exception) {
            Outcome.Error("تعذر الاتصال بـ Google Drive API: ${e.localizedMessage}")
        }
    }

    private fun verifyDropbox(
        descriptor: IntegrationDescriptor,
        token: String
    ): Outcome<IntegrationDescriptor, String> {
        return try {
            val request = Request.Builder()
                .url("https://api.dropboxapi.com/2/users/get_current_account")
                .post(ByteArray(0).toRequestBody(null))
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "AI-V0-Android-Platform")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                val email = json.optString("email", "dropbox_user")
                Outcome.Success(
                    descriptor.copy(
                        isConnected = true,
                        accountIdentifier = email,
                        health = HealthStatus.HEALTHY,
                        lastSyncTimestampMs = System.currentTimeMillis()
                    )
                )
            } else {
                Outcome.Error("رمز Dropbox غير صالح.")
            }
        } catch (e: Exception) {
            Outcome.Error("تعذر الاتصال بـ Dropbox API: ${e.localizedMessage}")
        }
    }

    private fun verifyGeneric(
        descriptor: IntegrationDescriptor,
        token: String
    ): Outcome<IntegrationDescriptor, String> {
        // P1-15 FIX (audit 2026 §28 — generic verification accepted ANY
        // non-blank token as valid): there is no authority that can verify an
        // arbitrary custom token, so the gateway FAILS CLOSED with an
        // explicit error instead of fabricating a HEALTHY/connected state.
        // The operator must verify such credentials against their real
        // provider endpoint before the integration can be considered live.
        return Outcome.Error(
            "GENERIC_VERIFICATION_UNSUPPORTED: لا توجد آلية موثوقة للتحقق من رمز تفويض مخصص لخدمة " +
                "'${descriptor.serviceType}'. أُضيفت الخدمة كتكوين مُسجَّل (غير متصل) — التحقق الفعلي يتطلب نقطة نهاية مخصصة من المزود."
        )
    }
}
