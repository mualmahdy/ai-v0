package com.example.infrastructure.llm.gemini

import android.content.Context
import com.google.firebase.FirebaseApp

/**
 * ============================================================================
 * GeminiBootstrap — OPTIONAL Firebase options detection
 * ============================================================================
 *
 * STATUS (fix "firebase not initiated"): this class is NO LONGER a runtime
 * dependency of Gemini generation. GeminiLlmAdapter now talks to the
 * Generative Language REST API directly with the user's stored key, so the
 * app runs perfectly WITHOUT google-services.json / FirebaseApp.
 *
 * What remains here is opportunistic key discovery: IF a Firebase project is
 * bundled later (google-services.json present), `apiKeyFromOptions()` can
 * surface the API key from the Firebase default options so validation can
 * use it. When Firebase is absent everything degrades honestly — the last
 * error is captured for diagnostics and the REST path is unaffected.
 */
class GeminiBootstrap(private val context: Context) {

    @Volatile
    private var initialized = false

    @Volatile
    private var lastError: String? = null

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    FirebaseApp.initializeApp(context)
                }
                initialized = FirebaseApp.getApps(context).isNotEmpty()
                lastError = if (initialized) null else "FirebaseApp.initializeApp returned no app"
            } catch (e: IllegalStateException) {
                // google-services.json missing/invalid — expected in debug builds
                initialized = false
                lastError = e.message
            } catch (e: Exception) {
                initialized = false
                lastError = e.message
            }
        }
    }

    /** Honest readiness: Firebase app initialized with usable default options. */
    fun isReady(): Boolean {
        ensureInitialized()
        return initialized
    }

    /** API key from the Firebase default options, if present (never fabricates). */
    fun apiKeyFromOptions(): String? {
        ensureInitialized()
        if (!initialized) return null
        return runCatching {
            FirebaseApp.getInstance(context.packageName).options.apiKey
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun lastInitializationError(): String? = lastError
}
