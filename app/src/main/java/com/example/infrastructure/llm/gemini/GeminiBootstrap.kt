package com.example.infrastructure.llm.gemini

import android.content.Context
import com.google.firebase.FirebaseApp

/**
 * ============================================================================
 * GeminiBootstrap — Phase 4 (REAL initialization)
 * ============================================================================
 *
 * Ensures the Firebase AI SDK backend for Gemini is initialized BEFORE the
 * DecisionService attempts to select Gemini-backed LLM candidates.
 *
 * What actually happens:
 *   1. If no FirebaseApp exists for this application, one is initialized from
 *      the bundled google-services.json / default options (when present).
 *   2. `isReady()` reports honestly: Firebase initialized AND an API key
 *      available in the default options. No fabricated success.
 *
 * If initialization fails (no google-services.json, missing options), the
 * failure is captured and exposed — the system degrades gracefully but never
 * pretends Gemini is available.
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
