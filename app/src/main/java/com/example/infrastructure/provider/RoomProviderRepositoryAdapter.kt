package com.example.infrastructure.provider

import com.example.domain.core.Outcome
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.ProviderCategory
import com.example.domain.core.provider.ProviderConfiguration
import com.example.domain.core.provider.ProviderFlavor
import com.example.domain.ports.provider.ProviderRepositoryPort
import com.example.domain.ports.provider.SecureCredentialStoragePort
import com.example.infrastructure.persistence.dao.ProviderConfigDao
import com.example.infrastructure.persistence.entities.ProviderConfigEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * Room-backed persistent repository for managing runtime Provider Configurations.
 * Combines Room database with AES-GCM secure credential storage.
 */
class RoomProviderRepositoryAdapter(
    private val providerDao: ProviderConfigDao,
    private val secureCredentialStorage: SecureCredentialStoragePort
) : ProviderRepositoryPort {

    private suspend fun ensureDefaultProvidersSeeded() {
        val existing = providerDao.getAllProviders()
        if (existing.isEmpty()) {
            val defaults = listOf(
                ProviderConfigEntity(
                    id = "gemini_google",
                    name = "Google Gemini AI",
                    category = ProviderCategory.LLM.name,
                    flavor = ProviderFlavor.GEMINI.name,
                    endpointUrl = ProviderFlavor.GEMINI.defaultEndpoint,
                    defaultModelId = "gemini-2.5-flash",
                    isEnabled = true,
                    isDefault = true,
                    healthStatus = HealthStatus.HEALTHY.name,
                    lastValidatedEpochMs = System.currentTimeMillis(),
                    lastLatencyMs = 120L,
                    lastErrorMessage = null,
                    extraHeadersJson = null,
                    timeoutSeconds = 30,
                    createdAtEpochMs = System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis()
                ),
                ProviderConfigEntity(
                    id = "tavily_search",
                    name = "Tavily Web Search",
                    category = ProviderCategory.SEARCH.name,
                    flavor = ProviderFlavor.TAVILY.name,
                    endpointUrl = ProviderFlavor.TAVILY.defaultEndpoint,
                    defaultModelId = "",
                    isEnabled = true,
                    isDefault = true,
                    healthStatus = HealthStatus.HEALTHY.name,
                    lastValidatedEpochMs = System.currentTimeMillis(),
                    lastLatencyMs = 250L,
                    lastErrorMessage = null,
                    extraHeadersJson = null,
                    timeoutSeconds = 15,
                    createdAtEpochMs = System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis()
                ),
                ProviderConfigEntity(
                    id = "local_embedding",
                    name = "Built-in Local Embedding",
                    category = ProviderCategory.EMBEDDING.name,
                    flavor = ProviderFlavor.LOCAL_EMBEDDING.name,
                    endpointUrl = ProviderFlavor.LOCAL_EMBEDDING.defaultEndpoint,
                    defaultModelId = "dense-semantic-128",
                    isEnabled = true,
                    isDefault = true,
                    healthStatus = HealthStatus.HEALTHY.name,
                    lastValidatedEpochMs = System.currentTimeMillis(),
                    lastLatencyMs = 5L,
                    lastErrorMessage = null,
                    extraHeadersJson = null,
                    timeoutSeconds = 5,
                    createdAtEpochMs = System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis()
                ),
                ProviderConfigEntity(
                    id = "local_ollama",
                    name = "Ollama Local Engine",
                    category = ProviderCategory.LLM.name,
                    flavor = ProviderFlavor.OLLAMA.name,
                    endpointUrl = ProviderFlavor.OLLAMA.defaultEndpoint,
                    defaultModelId = "llama3.2",
                    isEnabled = false,
                    isDefault = false,
                    healthStatus = HealthStatus.UNKNOWN.name,
                    lastValidatedEpochMs = 0L,
                    lastLatencyMs = 0L,
                    lastErrorMessage = null,
                    extraHeadersJson = null,
                    timeoutSeconds = 30,
                    createdAtEpochMs = System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
            providerDao.insertAll(defaults)
        }
    }

    override fun observeProviders(): Flow<List<ProviderConfiguration>> {
        return providerDao.getAllProvidersFlow()
            .onStart { ensureDefaultProvidersSeeded() }
            .map { entities ->
                entities.map { entity -> toDomain(entity) }
            }
    }

    override suspend fun getAllProviders(): List<ProviderConfiguration> = withContext(Dispatchers.IO) {
        ensureDefaultProvidersSeeded()
        providerDao.getAllProviders().map { toDomain(it) }
    }

    override suspend fun getProviderById(id: String): ProviderConfiguration? = withContext(Dispatchers.IO) {
        providerDao.getProviderById(id)?.let { toDomain(it) }
    }

    override suspend fun saveProvider(
        config: ProviderConfiguration,
        secretApiKey: String?
    ): Outcome<Unit, String> = withContext(Dispatchers.IO) {
        try {
            if (!secretApiKey.isNullOrBlank()) {
                val secretOutcome = secureCredentialStorage.storeSecret(config.id, secretApiKey)
                if (secretOutcome is Outcome.Error) {
                    return@withContext Outcome.Error(secretOutcome.diagnosticMessage)
                }
            }

            val entity = toEntity(config)
            providerDao.insertOrUpdate(entity)
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error("فشل حفظ إعدادات المزود: ${e.localizedMessage}")
        }
    }

    override suspend fun deleteProvider(id: String): Outcome<Unit, String> = withContext(Dispatchers.IO) {
        try {
            providerDao.deleteById(id)
            secureCredentialStorage.deleteSecret(id)
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error("فشل حذف المزود: ${e.localizedMessage}")
        }
    }

    override suspend fun toggleProvider(id: String, isEnabled: Boolean): Outcome<Unit, String> = withContext(Dispatchers.IO) {
        try {
            providerDao.updateEnabled(id, isEnabled, System.currentTimeMillis())
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error("فشل تعديل حالة المزود: ${e.localizedMessage}")
        }
    }

    override suspend fun setAsDefaultProvider(
        id: String,
        category: ProviderCategory
    ): Outcome<Unit, String> = withContext(Dispatchers.IO) {
        try {
            providerDao.setDefault(id, category.name, System.currentTimeMillis())
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error("فشل تعيين المزود الافتراضي: ${e.localizedMessage}")
        }
    }

    override suspend fun updateProviderHealth(
        id: String,
        health: HealthStatus,
        lastValidatedMs: Long,
        latencyMs: Long,
        error: String?
    ): Outcome<Unit, String> = withContext(Dispatchers.IO) {
        try {
            providerDao.updateHealth(
                id = id,
                healthStatus = health.name,
                validatedMs = lastValidatedMs,
                latencyMs = latencyMs,
                error = error,
                now = System.currentTimeMillis()
            )
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error("فشل تحديث فحص الحالة: ${e.localizedMessage}")
        }
    }

    override suspend fun getSecretForProvider(id: String): String? {
        return when (val outcome = secureCredentialStorage.getSecret(id)) {
            is Outcome.Success -> outcome.value
            else -> null
        }
    }

    private fun toDomain(entity: ProviderConfigEntity): ProviderConfiguration {
        val category = try { ProviderCategory.valueOf(entity.category) } catch (_: Exception) { ProviderCategory.LLM }
        val flavor = try { ProviderFlavor.valueOf(entity.flavor) } catch (_: Exception) { ProviderFlavor.OPENAI_COMPATIBLE }
        val health = try { HealthStatus.valueOf(entity.healthStatus) } catch (_: Exception) { HealthStatus.UNKNOWN }

        return ProviderConfiguration(
            id = entity.id,
            name = entity.name,
            category = category,
            flavor = flavor,
            endpointUrl = entity.endpointUrl,
            defaultModelId = entity.defaultModelId,
            isEnabled = entity.isEnabled,
            isDefault = entity.isDefault,
            healthStatus = health,
            lastValidatedEpochMs = entity.lastValidatedEpochMs,
            lastLatencyMs = entity.lastLatencyMs,
            lastErrorMessage = entity.lastErrorMessage,
            extraHeadersJson = entity.extraHeadersJson,
            timeoutSeconds = entity.timeoutSeconds,
            hasSecretKey = secureCredentialStorage.hasSecret(entity.id),
            createdAtEpochMs = entity.createdAtEpochMs,
            updatedAtEpochMs = entity.updatedAtEpochMs
        )
    }

    private fun toEntity(domain: ProviderConfiguration): ProviderConfigEntity {
        return ProviderConfigEntity(
            id = domain.id,
            name = domain.name,
            category = domain.category.name,
            flavor = domain.flavor.name,
            endpointUrl = domain.endpointUrl,
            defaultModelId = domain.defaultModelId,
            isEnabled = domain.isEnabled,
            isDefault = domain.isDefault,
            healthStatus = domain.healthStatus.name,
            lastValidatedEpochMs = domain.lastValidatedEpochMs,
            lastLatencyMs = domain.lastLatencyMs,
            lastErrorMessage = domain.lastErrorMessage,
            extraHeadersJson = domain.extraHeadersJson,
            timeoutSeconds = domain.timeoutSeconds,
            createdAtEpochMs = domain.createdAtEpochMs,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }
}
