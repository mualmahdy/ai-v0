package com.example.workspace

import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.network.NetworkPolicy
import com.example.infrastructure.persistence.dao.ProjectDao
import com.example.infrastructure.persistence.dao.WorkspaceDao
import com.example.infrastructure.persistence.entities.ProjectEntity
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

    /** In-memory fake of ProjectDao (P0 convergence: workspace-owned projects). */
    private class FakeProjectDao : ProjectDao {
        val stored = mutableMapOf<Long, ProjectEntity>()
        private var nextId = 100L

        override fun getAllActiveProjects(): Flow<List<ProjectEntity>> =
            MutableStateFlow(stored.values.filter { !it.isArchived })

        override suspend fun getAllActiveProjectsList(): List<ProjectEntity> =
            stored.values.filter { !it.isArchived }

        override suspend fun getProjectById(id: Long): ProjectEntity? = stored[id]

        override suspend fun insertProject(project: ProjectEntity): Long {
            val id = nextId++
            stored[id] = project.copy(id = id)
            return id
        }

        override suspend fun updateProject(project: ProjectEntity) {
            stored[project.id] = project
        }

        override suspend fun archiveProject(id: Long) {
            stored[id]?.let { stored[id] = it.copy(isArchived = true) }
        }
    }

    private fun newService(dao: FakeWorkspaceDao): WorkspaceRuntimeService {
        return WorkspaceRuntimeService(
            workspaceDao = dao,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
    }

    /**
     * P0 CONVERGENCE: the default workspace binds NO project when no
     * ProjectDao is wired (pure-JVM test wiring) — the honest "not bound"
     * state. Previously it fail-OPENED to the legacy implicit projectId=1L.
     */
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
        // P0 CONVERGENCE: NEVER an implicit legacy 1L — without a ProjectDao
        // the workspace binds NO project (null = honestly not bound).
        assertEquals(null, default.lastActiveProjectId)
    }

    /**
     * P0 CONVERGENCE: with a ProjectDao wired (production wiring), the
     * default workspace gets its OWN real sandbox project row — explicitly
     * workspace-owned — instead of pointing at the legacy shared 1L.
     */
    @Test
    fun `bootstrap creates an OWNED real project for the default workspace (never 1L)`() = runBlocking {
        val dao = FakeWorkspaceDao()
        val projectDao = FakeProjectDao()
        val service = WorkspaceRuntimeService(
            workspaceDao = dao,
            projectDao = projectDao,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        Thread.sleep(50)

        val default = dao.stored["default"]
        assertNotNull("Default workspace should be created", default)
        val boundProjectId = default!!.lastActiveProjectId
        assertNotNull("Default workspace should own a real project", boundProjectId)
        assertTrue(
            "The default workspace's project must NOT be the legacy implicit 1L",
            boundProjectId != 1L
        )
        val owned = projectDao.stored[boundProjectId]
        assertNotNull("The bound project row must actually exist", owned)
        assertEquals(
            "The project row must be explicitly workspace-owned",
            "default",
            owned!!.workspaceId
        )
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
    fun `createWorkspace binds the workspace to its OWN project (never legacy 1L)`() = runBlocking {
        // GAP-CLOSURE P0-04: a fake ProjectDao that returns generated ids.
        val dao = FakeWorkspaceDao()
        dao.stored["default"] = WorkspaceEntity(
            id = "default", name = "Default", description = "",
            networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED",
            settingsJson = "{}", isActive = true,
            lastActiveProjectId = 1L,
            createdAtEpochMs = 0, lastAccessedEpochMs = 0
        )
        val projectDao = object : com.example.infrastructure.persistence.dao.ProjectDao {
            override fun getAllActiveProjects(): Flow<List<com.example.infrastructure.persistence.entities.ProjectEntity>> =
                MutableStateFlow(emptyList())
            override suspend fun getAllActiveProjectsList(): List<com.example.infrastructure.persistence.entities.ProjectEntity> = emptyList()
            override suspend fun getProjectById(id: Long): com.example.infrastructure.persistence.entities.ProjectEntity? = null
            override suspend fun insertProject(project: com.example.infrastructure.persistence.entities.ProjectEntity): Long = 77L
            override suspend fun updateProject(project: com.example.infrastructure.persistence.entities.ProjectEntity) {}
            override suspend fun archiveProject(id: Long) {}
        }
        val service = WorkspaceRuntimeService(
            workspaceDao = dao,
            projectDao = projectDao,
            coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
        Thread.sleep(50)

        val created = service.createWorkspace(name = "Isolated", description = "")

        val stored = dao.stored.values.first { it.name == "Isolated" }
        assertEquals(
            "P0-04: the new workspace owns its dedicated project (77L), not the shared 1L",
            77L,
            stored.lastActiveProjectId
        )
        assertEquals(77L, created.activeProjectId)
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
    fun `requireActiveWorkspaceId FAILS CLOSED when no workspace is active yet`() = runBlocking {
        // GAP-CLOSURE P0-03: previously this fail-OPENED to the literal
        // "default" (data written before bootstrap landed in a scope nobody
        // owns). The honest contract now: activeWorkspaceIdOrNull() = null,
        // requireActiveWorkspaceId() throws.
        // Directly verify the accessor contract on a service whose bootstrap
        // NEVER dispatches (a dispatcher that drops every task).
        val neverDispatches = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                // dropped — the init coroutine never runs
            }
        }
        val fresh = WorkspaceRuntimeService(
            workspaceDao = FakeWorkspaceDao(),
            coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + neverDispatches)
        )
        org.junit.Assert.assertNull(
            "P0-03: no active workspace must be HONESTLY null, not 'default'",
            fresh.activeWorkspaceIdOrNull()
        )
        var threw = false
        try {
            fresh.requireActiveWorkspaceId()
        } catch (_: com.example.application.workspace.NoActiveWorkspaceStateException) {
            threw = true
        }
        org.junit.Assert.assertTrue(
            "P0-03: requireActiveWorkspaceId must FAIL CLOSED (NoActiveWorkspaceStateException)",
            threw
        )
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
