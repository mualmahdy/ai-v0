package com.example.workspace

import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.network.NetworkPolicy
import com.example.infrastructure.persistence.dao.WorkspaceDao
import com.example.infrastructure.persistence.entities.WorkspaceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 Verification Tests — proves WorkspaceRuntimeService correctly manages
 * multi-workspace lifecycle: bootstrap, create, switch, delete, and that the
 * default workspace is auto-created on first launch.
 *
 * Uses an in-memory FakeWorkspaceDao instead of MockK (which isn't in the
 * project dependencies) so the tests run on the JVM without Android instrumentation.
 */
class WorkspaceRuntimeServiceTest {

    /**
     * In-memory fake of WorkspaceDao that records all interactions.
     * Implements every method WorkspaceRuntimeService actually calls.
     */
    private class FakeWorkspaceDao : WorkspaceDao {
        val stored = mutableMapOf<String, WorkspaceEntity>()
        var insertOrUpdateCount = 0
        var deactivateAllCount = 0
        val setActiveCalls = mutableListOf<Pair<String, Long>>()
        val deleteByIdCalls = mutableListOf<String>()

        override fun observeAllWorkspaces(): Flow<List<WorkspaceEntity>> = MutableStateFlow(stored.values.toList())
        override suspend fun getAllWorkspaces(): List<WorkspaceEntity> = stored.values.toList()
        override suspend fun getWorkspaceById(id: String): WorkspaceEntity? = stored[id]
        override suspend fun getActiveWorkspace(): WorkspaceEntity? = stored.values.firstOrNull { it.isActive }
        override fun observeActiveWorkspace(): Flow<WorkspaceEntity?> = MutableStateFlow(stored.values.firstOrNull { it.isActive })

        override suspend fun insertOrUpdate(workspace: WorkspaceEntity) {
            stored[workspace.id] = workspace
            insertOrUpdateCount++
        }

        override suspend fun update(workspace: WorkspaceEntity) {
            stored[workspace.id] = workspace
        }

        override suspend fun deactivateAll() {
            stored.forEach { (id, entity) -> stored[id] = entity.copy(isActive = false) }
            deactivateAllCount++
        }

        override suspend fun setActive(id: String, now: Long) {
            stored[id]?.let { stored[id] = it.copy(isActive = true, lastAccessedEpochMs = now) }
            setActiveCalls.add(id to now)
        }

        override suspend fun setActiveProject(workspaceId: String, projectId: Long?, now: Long) {
            stored[workspaceId]?.let {
                stored[workspaceId] = it.copy(lastActiveProjectId = projectId, lastAccessedEpochMs = now)
            }
        }

        override suspend fun deleteById(id: String) {
            stored.remove(id)
            deleteByIdCalls.add(id)
        }
    }

    private fun newService(dao: FakeWorkspaceDao): WorkspaceRuntimeService {
        return WorkspaceRuntimeService(
            workspaceDao = dao,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
    }

    @Test
    fun `bootstrapDefaultWorkspaceIfNeeded creates default workspace when none exist`() = runBlocking {
        val dao = FakeWorkspaceDao()
        val service = newService(dao)
        // Wait for init coroutine to complete
        Thread.sleep(50)

        assertTrue("Default workspace should be created", dao.stored.containsKey("default"))
        val default = dao.stored["default"]!!
        assertEquals("مساحة العمل الافتراضية", default.name)
        assertTrue("Default workspace must be active", default.isActive)
        assertEquals(NetworkPolicy.HYBRID.name, default.networkPolicy)
        assertEquals("SUPERVISED", default.autonomyPolicy)
        // lastActiveProjectId should default to 1L so the legacy single-project flow keeps working
        assertEquals(1L, default.lastActiveProjectId)
    }

    @Test
    fun `bootstrapDefaultWorkspaceIfNeeded activates most recent when no active workspace exists`() = runBlocking {
        val dao = FakeWorkspaceDao()
        val now = System.currentTimeMillis()
        dao.stored["ws_a"] = WorkspaceEntity(
            id = "ws_a", name = "A", description = "",
            networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED",
            settingsJson = "{}", isActive = false,
            lastActiveProjectId = null,
            createdAtEpochMs = now - 1000, lastAccessedEpochMs = now - 100
        )
        dao.stored["ws_b"] = WorkspaceEntity(
            id = "ws_b", name = "B", description = "",
            networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED",
            settingsJson = "{}", isActive = false,
            lastActiveProjectId = null,
            createdAtEpochMs = now - 2000, lastAccessedEpochMs = now - 50
        )
        val service = newService(dao)
        Thread.sleep(50)

        // ws_b has the most recent lastAccessedEpochMs (now-50 vs now-100)
        assertTrue("ws_b should be activated", dao.stored["ws_b"]!!.isActive)
        assertFalse("ws_a should remain inactive", dao.stored["ws_a"]!!.isActive)
        assertEquals(1, dao.setActiveCalls.size)
        assertEquals("ws_b", dao.setActiveCalls[0].first)
    }

    @Test
    fun `createWorkspace deactivates all then inserts new active workspace`() = runBlocking {
        val dao = FakeWorkspaceDao()
        dao.stored["default"] = WorkspaceEntity(
            id = "default", name = "Default", description = "",
            networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED",
            settingsJson = "{}", isActive = true,
            lastActiveProjectId = 1L,
            createdAtEpochMs = 0, lastAccessedEpochMs = 0
        )
        val service = newService(dao)
        Thread.sleep(50)

        val created = service.createWorkspace(
            name = "Research",
            description = "Research workspace",
            networkPolicy = NetworkPolicy.OFFLINE
        )

        assertEquals("Research", created.name)
        assertEquals(NetworkPolicy.OFFLINE, created.networkPolicy)
        assertTrue("deactivateAll should be called", dao.deactivateAllCount >= 1)
        assertTrue("New workspace should be in storage", dao.stored.values.any { it.name == "Research" && it.isActive })
    }

    @Test
    fun `switchWorkspace returns false for non-existent workspace`() = runBlocking {
        val dao = FakeWorkspaceDao()
        val service = newService(dao)
        Thread.sleep(50)

        val result = service.switchWorkspace("nonexistent")
        assertFalse("Switch should fail for non-existent workspace", result)
    }

    @Test
    fun `switchWorkspace returns true and activates target workspace`() = runBlocking {
        val dao = FakeWorkspaceDao()
        val target = WorkspaceEntity(
            id = "ws_target", name = "Target", description = "",
            networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED",
            settingsJson = "{}", isActive = false,
            lastActiveProjectId = null,
            createdAtEpochMs = 0, lastAccessedEpochMs = 0
        )
        dao.stored["ws_target"] = target
        val service = newService(dao)
        Thread.sleep(50)

        val result = service.switchWorkspace("ws_target")
        assertTrue("Switch should succeed for existing workspace", result)
        assertTrue("Target workspace should now be active", dao.stored["ws_target"]!!.isActive)
    }

    @Test
    fun `deleteWorkspace refuses to delete the last remaining workspace`() = runBlocking {
        val dao = FakeWorkspaceDao()
        dao.stored["default"] = WorkspaceEntity(
            id = "default", name = "Default", description = "",
            networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED",
            settingsJson = "{}", isActive = true,
            lastActiveProjectId = 1L,
            createdAtEpochMs = 0, lastAccessedEpochMs = 0
        )
        val service = newService(dao)
        Thread.sleep(50)

        val result = service.deleteWorkspace("default")
        assertFalse("Should refuse to delete the last workspace", result)
        assertEquals("deleteById should NOT be called", 0, dao.deleteByIdCalls.size)
    }

    @Test
    fun `deleteWorkspace activates next workspace when deleting the active one`() = runBlocking {
        val dao = FakeWorkspaceDao()
        val now = System.currentTimeMillis()
        dao.stored["ws_active"] = WorkspaceEntity(
            id = "ws_active", name = "Active", description = "",
            networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED",
            settingsJson = "{}", isActive = true,
            lastActiveProjectId = 1L,
            createdAtEpochMs = now - 2000, lastAccessedEpochMs = now - 100
        )
        dao.stored["ws_other"] = WorkspaceEntity(
            id = "ws_other", name = "Other", description = "",
            networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED",
            settingsJson = "{}", isActive = false,
            lastActiveProjectId = null,
            createdAtEpochMs = now - 1000, lastAccessedEpochMs = now - 50
        )
        val service = newService(dao)
        Thread.sleep(50)

        val result = service.deleteWorkspace("ws_active")
        assertTrue("Delete should succeed", result)
        assertFalse("ws_active should be deleted", dao.stored.containsKey("ws_active"))
        // Should activate the most recently accessed remaining workspace (ws_other)
        assertTrue("ws_other should now be active", dao.stored["ws_other"]!!.isActive)
    }

    @Test
    fun `requireActiveWorkspaceId returns default when no workspace is active yet`() = runBlocking {
        val dao = FakeWorkspaceDao()
        val service = newService(dao)
        // Don't wait for bootstrap — simulate cold start before init completes
        // (the bootstrap may have already run, but requireActiveWorkspaceId should
        // still return a non-null stable key either way)
        val id = service.requireActiveWorkspaceId()
        assertTrue("Should return a non-null workspace id", id.isNotBlank())
    }

    @Test
    fun `requireActiveWorkspaceId returns the active workspace id after bootstrap`() = runBlocking {
        val dao = FakeWorkspaceDao()
        val service = newService(dao)
        Thread.sleep(50)

        val id = service.requireActiveWorkspaceId()
        // After bootstrap, the default workspace should be active
        assertEquals("default", id)
    }

    @Test
    fun `updateNetworkPolicy propagates to active workspace`() = runBlocking {
        val dao = FakeWorkspaceDao()
        val service = newService(dao)
        Thread.sleep(50)

        service.updateNetworkPolicy(NetworkPolicy.OFFLINE)

        val active = dao.getActiveWorkspace()
        assertNotNull("Active workspace should exist", active)
        assertEquals(NetworkPolicy.OFFLINE.name, active!!.networkPolicy)
    }

    @Test
    fun `renameWorkspace updates name and description`() = runBlocking {
        val dao = FakeWorkspaceDao()
        dao.stored["ws_test"] = WorkspaceEntity(
            id = "ws_test", name = "Old", description = "Old desc",
            networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED",
            settingsJson = "{}", isActive = true,
            lastActiveProjectId = null,
            createdAtEpochMs = 0, lastAccessedEpochMs = 0
        )
        val service = newService(dao)
        Thread.sleep(50)

        val result = service.renameWorkspace("ws_test", newName = "New Name", newDescription = "New desc")
        assertTrue("Rename should succeed", result)
        assertEquals("New Name", dao.stored["ws_test"]!!.name)
        assertEquals("New desc", dao.stored["ws_test"]!!.description)
    }

    @Test
    fun `renameWorkspace returns false for non-existent workspace`() = runBlocking {
        val dao = FakeWorkspaceDao()
        val service = newService(dao)
        Thread.sleep(50)

        val result = service.renameWorkspace("nonexistent", newName = "X")
        assertFalse("Rename should fail for non-existent workspace", result)
    }
}
