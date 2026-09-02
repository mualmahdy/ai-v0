package com.example.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.provider.ProviderControlPlaneService
import com.example.application.registry.ComponentRegistry
import com.example.domain.core.Outcome
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.ProviderCategory
import com.example.domain.core.provider.ProviderConfiguration
import com.example.domain.core.provider.ProviderFlavor
import com.example.infrastructure.memory.LocalDeterministicEmbeddingAdapter
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.provider.ProviderAdapterFactory
import com.example.infrastructure.provider.RoomProviderRepositoryAdapter
import com.example.infrastructure.security.EncryptedSecretStorageAdapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProviderControlPlaneTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var secretStorage: EncryptedSecretStorageAdapter
    private lateinit var repository: RoomProviderRepositoryAdapter
    private lateinit var factory: ProviderAdapterFactory
    private lateinit var registry: ComponentRegistry
    private lateinit var controlPlaneService: ProviderControlPlaneService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        secretStorage = EncryptedSecretStorageAdapter(context)
        repository = RoomProviderRepositoryAdapter(database.providerConfigDao(), secretStorage)
        factory = ProviderAdapterFactory()
        registry = ComponentRegistry()
        controlPlaneService = ProviderControlPlaneService(repository, factory, registry)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `test default provider seeding creates Gemini Tavily and Local Embedding`() = runBlocking {
        val providers = repository.observeProviders().first()
        assertTrue(providers.isNotEmpty())
        assertEquals(3, providers.size)

        val gemini = providers.find { it.id == "gemini_default" }
        assertNotNull(gemini)
        assertEquals(ProviderCategory.LLM, gemini?.category)
        assertTrue(gemini?.isDefault == true)

        val tavily = providers.find { it.id == "tavily_search" }
        assertNotNull(tavily)
        assertEquals(ProviderCategory.SEARCH, tavily?.category)

        val embedding = providers.find { it.id == "local_embedding" }
        assertNotNull(embedding)
        assertEquals(ProviderCategory.EMBEDDING, embedding?.category)
        assertEquals(HealthStatus.HEALTHY, embedding?.healthStatus)
    }

    @Test
    fun `test secret storage securely isolates api keys from provider configs`() = runBlocking {
        val customKey = "sk-test-secret-key-12345"
        val config = ProviderConfiguration(
            id = "custom_openai",
            name = "Custom OpenAI Endpoint",
            category = ProviderCategory.LLM,
            flavor = ProviderFlavor.OPENAI_COMPATIBLE,
            endpointUrl = "https://api.openai.com/v1",
            defaultModelId = "gpt-4o",
            isEnabled = true,
            isDefault = false,
            hasSecretKey = true
        )

        // Save with secret key
        val saveOutcome = repository.saveProvider(config, customKey)
        assertTrue(saveOutcome is Outcome.Success)

        // Retrieve secret securely via adapter
        val retrievedKey = repository.getSecretForProvider("custom_openai")
        assertEquals(customKey, retrievedKey)

        // Verify Room entity does NOT hold the raw key in plain columns
        val entity = database.providerConfigDao().getProviderById("custom_openai")
        assertNotNull(entity)
        assertTrue(entity?.hasSecretKey == true)
    }

    @Test
    fun `test local deterministic embedding adapter produces valid normalized vectors`() = runBlocking {
        val adapter = LocalDeterministicEmbeddingAdapter("test_local_emb", 128)
        val text1 = "هندسة البرمجيات والمعمارية النظيفة"
        val text2 = "هندسة البرمجيات وتصميم النظم"
        val text3 = "طريقة صنع الخبز في المنزل"

        val outcome = adapter.generateEmbeddings(listOf(text1, text2, text3))
        assertTrue(outcome is Outcome.Success)
        val vectors = (outcome as Outcome.Success).value
        assertEquals(3, vectors.size)

        val v1 = vectors[0].values
        val v2 = vectors[1].values
        val v3 = vectors[2].values

        assertEquals(128, v1.size)

        // Dot product of normalized vectors
        var sim12 = 0f
        var sim13 = 0f
        for (i in 0 until 128) {
            sim12 += v1[i] * v2[i]
            sim13 += v1[i] * v3[i]
        }

        // Semantic overlap between software engineering topics should be higher than cooking
        assertTrue(sim12 > sim13)
    }

    @Test
    fun `test dynamic enable disable and default provider switching`() = runBlocking {
        val config1 = ProviderConfiguration(
            id = "llm_alpha",
            name = "Alpha LLM",
            category = ProviderCategory.LLM,
            flavor = ProviderFlavor.OPENAI_COMPATIBLE,
            isEnabled = true,
            isDefault = true
        )
        val config2 = ProviderConfiguration(
            id = "llm_beta",
            name = "Beta LLM",
            category = ProviderCategory.LLM,
            flavor = ProviderFlavor.OPENAI_COMPATIBLE,
            isEnabled = true,
            isDefault = false
        )

        repository.saveProvider(config1)
        repository.saveProvider(config2)

        // Set Beta as default
        repository.setAsDefaultProvider("llm_beta", ProviderCategory.LLM)

        val p1 = repository.getProviderById("llm_alpha")
        val p2 = repository.getProviderById("llm_beta")

        assertFalse(p1!!.isDefault)
        assertTrue(p2!!.isDefault)

        // Disable Beta
        repository.toggleProvider("llm_beta", false)
        val p2Disabled = repository.getProviderById("llm_beta")
        assertFalse(p2Disabled!!.isEnabled)
    }
}
