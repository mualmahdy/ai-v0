package com.example.runtime.providers

import com.example.runtime.events.EventBus
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

data class GenerationOutcome(
    val status: String, // "success", "degraded", "error"
    val output: String,
    val providerUsed: String,
    val modelUsed: String,
    val fallbackTriggered: Boolean = false,
    val degradedReason: String? = null,
    val errorMessage: String? = null
)

class CircuitBreaker(
    val providerName: String,
    private val failureThreshold: Int = 3,
    private val resetTimeoutMs: Long = 30000L
) {
    private var failureCount = 0
    private var lastFailureTime = 0L
    var isOpen = false
        private set

    fun allowRequest(): Boolean {
        if (!isOpen) return true
        if (System.currentTimeMillis() - lastFailureTime > resetTimeoutMs) {
            // Half-open trial
            return true
        }
        return false
    }

    fun recordSuccess() {
        failureCount = 0
        isOpen = false
    }

    fun recordFailure() {
        failureCount++
        lastFailureTime = System.currentTimeMillis()
        if (failureCount >= failureThreshold) {
            isOpen = true
        }
    }
}

class ModelOrchestrator(
    private val localProvider: LocalHeuristicProvider = LocalHeuristicProvider(),
    private val geminiProvider: GeminiCloudProvider = GeminiCloudProvider(),
    var isOfflineModeEnforced: Boolean = false
) {
    private val breakers = ConcurrentHashMap<String, CircuitBreaker>()
    private val providers = mutableMapOf<String, BaseModelProvider>()

    init {
        providers[localProvider.name] = localProvider
        providers[geminiProvider.name] = geminiProvider
    }

    fun getBreaker(providerName: String): CircuitBreaker {
        return breakers.getOrPut(providerName) { CircuitBreaker(providerName) }
    }

    suspend fun generateWithResilience(
        role: String,
        prompt: String,
        systemInstruction: String? = null
    ): GenerationOutcome {
        val primaryProvider = if (isOfflineModeEnforced) localProvider else geminiProvider
        val fallbackProvider = localProvider

        val primaryBreaker = getBreaker(primaryProvider.name)

        if (primaryProvider.isOnlineOnly && isOfflineModeEnforced) {
            EventBus.publishModelFallback(role, primaryProvider.name, "offline_mode_enforced")
            val output = fallbackProvider.generate(prompt, systemInstruction)
            return GenerationOutcome(
                status = "degraded",
                output = output,
                providerUsed = fallbackProvider.name,
                modelUsed = "native-cbr-engine",
                fallbackTriggered = true,
                degradedReason = "offline_mode_enforced"
            )
        }

        if (primaryBreaker.allowRequest()) {
            try {
                val output = primaryProvider.generate(prompt, systemInstruction)
                primaryBreaker.recordSuccess()
                return GenerationOutcome(
                    status = "success",
                    output = output,
                    providerUsed = primaryProvider.name,
                    modelUsed = "gemini-3.5-flash",
                    fallbackTriggered = false
                )
            } catch (e: Exception) {
                primaryBreaker.recordFailure()
                EventBus.publishModelFallback(role, primaryProvider.name, "primary_error: ${e.message}")
            }
        } else {
            EventBus.publishModelFallback(role, primaryProvider.name, "circuit_breaker_open")
        }

        // Fallback to local heuristic provider
        return try {
            val output = fallbackProvider.generate(prompt, systemInstruction)
            GenerationOutcome(
                status = "degraded",
                output = output,
                providerUsed = fallbackProvider.name,
                modelUsed = "native-cbr-engine",
                fallbackTriggered = true,
                degradedReason = "primary_unavailable_fallback_to_local"
            )
        } catch (e: Exception) {
            GenerationOutcome(
                status = "error",
                output = "",
                providerUsed = fallbackProvider.name,
                modelUsed = "native-cbr-engine",
                errorMessage = e.message
            )
        }
    }

    fun streamGenerateWithResilience(
        role: String,
        prompt: String,
        systemInstruction: String? = null
    ): Flow<String> {
        val provider = if (isOfflineModeEnforced) localProvider else geminiProvider
        val breaker = getBreaker(provider.name)
        return if (!isOfflineModeEnforced && breaker.allowRequest()) {
            geminiProvider.streamGenerate(prompt, systemInstruction)
        } else {
            localProvider.streamGenerate(prompt, systemInstruction)
        }
    }

    suspend fun generateWithToolsResilient(
        role: String,
        prompt: String,
        availableTools: List<String>,
        systemInstruction: String? = null
    ): ModelToolResult {
        return localProvider.generateWithTools(prompt, availableTools, systemInstruction)
    }
}
