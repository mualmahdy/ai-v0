package com.example.application.resource

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceType
import com.example.domain.ports.resource.ResourceRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * DurableResourceRegistryHonestyTest — P1-13 (audit 2026 §19)
 * ============================================================================
 *
 * RAM/Room non-atomicity: previously EVERY repository failure was swallowed
 * by `runCatching { ... }` — the registry reported success while memory and
 * Room had silently DIVERGED (the class doc even claimed "the failure is
 * never silently reported as success", which the code did not honor).
 *
 * Contract under test (honest degradation):
 *  - the in-memory registry stays authoritative (the runtime keeps working
 *    when the disk fails);
 *  - every persistence failure is OBSERVABLE: recorded in the bounded
 *    [DurableResourceRegistryService.persistenceFailures] flow AND routed to
 *    the `persistenceFailureHandler` hook;
 *  - a failing store on startup (eagerLoad) degrades honestly to empty
 *    memory + a recorded failure (never a silent empty registry);
 *  - the failure window is bounded (no unbounded growth).
 */
class DurableResourceRegistryHonestyTest {

    /** Fake repository whose writes FAIL on demand. */
    private class FailingRepository : ResourceRecordRepository {
        var failSaves = false
        var failLoads = false
        var failDeletes = false
        val stored = mutableMapOf<ResourceId, ResourceRecord>()
        private val all = MutableStateFlow<List<ResourceRecord>>(emptyList())

        override suspend fun saveResource(record: ResourceRecord) {
            if (failSaves) throw IllegalStateException("disk full")
            stored[record.resourceId] = record
            all.value = stored.values.toList()
        }

        override suspend fun getResourceById(resourceId: ResourceId): ResourceRecord? = stored[resourceId]

        override suspend fun getAllResources(): List<ResourceRecord> {
            if (failLoads) throw IllegalStateException("corrupt store")
            return stored.values.toList()
        }

        override fun observeAllResources(): Flow<List<ResourceRecord>> = all

        override suspend fun updateRuntimeState(
            resourceId: ResourceId,
            lifecycleState: ResourceLifecycleState,
            runtimeSupported: Boolean,
            healthStatus: HealthStatus
        ) {
            if (failSaves) throw IllegalStateException("disk full")
            stored[resourceId] = stored[resourceId]!!.copy(
                lifecycleState = lifecycleState,
                runtimeSupported = runtimeSupported,
                healthStatus = healthStatus
            )
        }

        override suspend fun deleteResource(resourceId: ResourceId) {
            if (failDeletes) throw IllegalStateException("locked")
            stored.remove(resourceId)
        }

        override suspend fun deleteResourcesForService(serviceId: String) {
            if (failDeletes) throw IllegalStateException("locked")
            stored.values.filter { it.serviceId == serviceId }
                .forEach { stored.remove(it.resourceId) }
        }
    }

    private fun record(id: String) = ResourceRecord(
        resourceId = ResourceId(id),
        providerId = "p",
        serviceId = "s",
        resourceType = ResourceType.LLM,
        capabilities = setOf(CapabilityType.LLM_GENERATION),
        lifecycleState = ResourceLifecycleState.ENABLED,
        runtimeSupported = true,
        healthStatus = HealthStatus.HEALTHY
    )

    @Test
    fun `saveResource records the failure when the store write fails (never silent success)`() = runBlocking {
        val repo = FailingRepository()
        repo.failSaves = true
        val seen = mutableListOf<String>()
        val durable = DurableResourceRegistryService(
            repository = repo,
            persistenceFailureHandler = { entry, _ -> seen.add(entry) }
        )

        val saved = durable.saveResource(record("r1"))

        // Memory stays authoritative (honest degradation, runtime keeps working)
        assertNotNull("record must live in memory even when the disk write fails", saved)
        assertEquals(saved, durable.getResource(ResourceId("r1")))
        // ... and the divergence is OBSERVABLE (recorded + handler invoked)
        assertEquals(1, durable.persistenceFailures.value.size)
        assertTrue(
            "failure entry must identify the failed operation",
            durable.persistenceFailures.value[0].contains("saveResource")
        )
        assertEquals("handler must be invoked exactly once", 1, seen.size)
    }

    @Test
    fun `saveResource persists when the store is healthy`() = runBlocking {
        val repo = FailingRepository()
        val durable = DurableResourceRegistryService(repository = repo)

        durable.saveResource(record("r_ok"))

        assertEquals(
            "record must be persisted on the healthy path",
            record("r_ok"),
            repo.stored[ResourceId("r_ok")]
        )
        assertTrue("no failures recorded on the healthy path", durable.persistenceFailures.value.isEmpty())
    }

    @Test
    fun `eagerLoad degrades HONESTLY when the store is unreadable (recorded, not empty-by-accident)`() = runBlocking {
        val repo = FailingRepository()
        repo.failLoads = true
        val durable = DurableResourceRegistryService(repository = repo)

        durable.eagerLoad()

        assertTrue("memory registry is empty after the failed load", durable.listResources().isEmpty())
        assertEquals(
            "the unreadable store must be RECORDED as a failure",
            1,
            durable.persistenceFailures.value.size
        )
        assertTrue(durable.persistenceFailures.value[0].contains("eagerLoad"))
    }

    @Test
    fun `deleteResource records the failure when the delete fails`() = runBlocking {
        val repo = FailingRepository()
        val durable = DurableResourceRegistryService(repository = repo)
        durable.saveResource(record("r_del"))
        repo.failDeletes = true

        durable.deleteResource(ResourceId("r_del"))

        assertEquals("delete failure must be recorded", 1, durable.persistenceFailures.value.size)
        assertTrue(durable.persistenceFailures.value[0].contains("deleteResource"))
    }

    @Test
    fun `the failure window is BOUNDED`() = runBlocking {
        val repo = FailingRepository()
        repo.failSaves = true
        val durable = DurableResourceRegistryService(repository = repo)

        repeat(60) { durable.saveResource(record("r$it")) }

        assertTrue(
            "failure window must stay bounded (was ${durable.persistenceFailures.value.size})",
            durable.persistenceFailures.value.size <= 32
        )
    }
}
