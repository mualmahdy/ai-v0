package com.example.gapclosure

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * GAP-16 (Design Closure 2026) — WorkspaceAtomicityTest
 * ============================================================================
 *
 * The audit finding: `createWorkspace` performed its multi-write sequence
 * (project insert → deactivateAll → workspace insert) as SEPARATE DAO calls
 * (a mid-sequence crash left a half-created state: a project with no
 * workspace, or a deactivated world with no new row); `switchWorkspace` was
 * non-transactional and NEVER reconciled the target's stale
 * lastActiveProjectId (the "no project associated" startup error repeated
 * until the next bootstrap); the active-workspace read had no ORDER BY
 * (multi-active corruption returned an arbitrary row).
 *
 * This test pins, against in-memory fakes + an injected transaction runner:
 *   1. an injected mid-transaction failure leaves NO partial state (no
 *      workspace row, no orphan project, nothing deactivated);
 *   2. the create/switch write sequences run INSIDE the transaction;
 *   3. switching to a workspace with a STALE pin reconciles it IMMEDIATELY
 *      (repaired to the newest ACTIVE owned project, or cleared);
 *   4. switching to a workspace with a VALID pin keeps it.
 */
class WorkspaceAtomicityTest {

    private class FakeWorkspaceDao : WorkspaceDao {
        val stored = mutableMapOf<String, WorkspaceEntity>()
        var failNextSetActive = false

        override fun observeAllWorkspaces(): Flow<List<WorkspaceEntity>> = MutableStateFlow(stored.values.toList())
        override suspend fun getAllWorkspaces(): List<WorkspaceEntity> = stored.values.toList()
        override suspend fun getWorkspaceById(id: String): WorkspaceEntity? = stored[id]
        override suspend fun getActiveWorkspace(): WorkspaceEntity? =
            stored.values.filter { it.isActive }.maxByOrNull { it.lastAccessedEpochMs }
        override fun observeActiveWorkspace(): Flow<WorkspaceEntity?> =
            MutableStateFlow(stored.values.filter { it.isActive }.maxByOrNull { it.lastAccessedEpochMs })

        override suspend fun insertOrUpdate(workspace: WorkspaceEntity) { stored[workspace.id] = workspace }
        override suspend fun update(workspace: WorkspaceEntity) { stored[workspace.id] = workspace }
        override suspend fun deactivateAll() {
            stored.forEach { (id, entity) -> stored[id] = entity.copy(isActive = false) }
        }
        override suspend fun setActive(id: String, now: Long) {
            if (failNextSetActive) throw IllegalStateException("INJECTED setActive FAILURE")
            stored[id]?.let { stored[id] = it.copy(isActive = true, lastAccessedEpochMs = now) }
        }
        override suspend fun setActiveProject(workspaceId: String, projectId: Long?, now: Long) {
            stored[workspaceId]?.let { stored[workspaceId] = it.copy(lastActiveProjectId = projectId, lastAccessedEpochMs = now) }
        }
        override suspend fun deleteById(id: String) { stored.remove(id) }
        override suspend fun updateAutonomyPolicy(workspaceId: String, policy: String, now: Long) {
            stored[workspaceId]?.let { stored[workspaceId] = it.copy(autonomyPolicy = policy) }
        }
        override suspend fun autonomyPolicyFor(workspaceId: String): String? = stored[workspaceId]?.autonomyPolicy
    }

    private class FakeProjectDao : ProjectDao {
        val stored = mutableMapOf<Long, ProjectEntity>()
        private var nextId = 100L

        override fun getAllActiveProjects(): Flow<List<ProjectEntity>> =
            MutableStateFlow(stored.values.filter { !it.isArchived })
        override suspend fun getAllActiveProjectsList(): List<ProjectEntity> =
            stored.values.filter { !it.isArchived }
        override suspend fun getProjectById(id: Long): ProjectEntity? = stored[id]
        override fun getActiveProjectsForWorkspace(workspaceId: String): Flow<List<ProjectEntity>> =
            MutableStateFlow(stored.values.filter { !it.isArchived && it.workspaceId == workspaceId })
        override suspend fun getActiveProjectsForWorkspaceList(workspaceId: String): List<ProjectEntity> =
            stored.values.filter { !it.isArchived && it.workspaceId == workspaceId }
        override suspend fun getProjectByIdForWorkspace(id: Long, workspaceId: String): ProjectEntity? =
            stored[id]?.takeIf { it.workspaceId == workspaceId }
        override suspend fun archiveProjectForWorkspace(id: Long, workspaceId: String) {
            stored[id]?.let { if (it.workspaceId == workspaceId) stored[id] = it.copy(isArchived = true) }
        }
        override suspend fun archiveProject(id: Long) {
            stored[id]?.let { stored[id] = it.copy(isArchived = true) }
        }
        override suspend fun forWorkspaceInState(workspaceId: String, state: String): List<ProjectEntity> =
            stored.values.filter { it.workspaceId == workspaceId && it.effectiveLifecycleState == state }
        override suspend fun activeProjectsForWorkspaceList(workspaceId: String): List<ProjectEntity> =
            stored.values.filter { it.workspaceId == workspaceId && it.effectiveLifecycleState == "ACTIVE" }
        override suspend fun mostRecentActiveProjectForWorkspace(workspaceId: String): ProjectEntity? =
            stored.values
                .filter { it.workspaceId == workspaceId && it.effectiveLifecycleState == "ACTIVE" }
                .maxByOrNull { it.updatedAtEpochMs }
        override suspend fun resolvableProjectForWorkspace(id: Long, workspaceId: String): ProjectEntity? =
            stored[id]?.takeIf { it.workspaceId == workspaceId && it.effectiveLifecycleState == "ACTIVE" }
        override suspend fun setLifecycleState(
            id: Long, workspaceId: String, state: String, now: Long,
            archived: Boolean, archivedAt: Long?, trashedAt: Long?
        ) {
            stored[id]?.let {
                if (it.workspaceId == workspaceId) stored[id] = it.copy(isArchived = archived)
            }
        }
        override suspend fun insertProject(project: ProjectEntity): Long {
            val id = nextId++
            stored[id] = project.copy(id = id)
            return id
        }
        override suspend fun updateProject(project: ProjectEntity) { stored[project.id] = project }
        override suspend fun countByNameForWorkspace(workspaceId: String, name: String): Int =
            stored.values.count { it.workspaceId == workspaceId && it.name == name }
        override suspend fun renameProjectForWorkspace(
            id: Long, workspaceId: String, name: String, description: String?, now: Long
        ) {
            stored[id]?.let { if (it.workspaceId == workspaceId) stored[id] = it.copy(name = name, description = description) }
        }
        override suspend fun deleteProjectRow(projectId: Long) { stored.remove(projectId) }
        override suspend fun moveProjectToWorkspace(
            projectId: Long, sourceWorkspaceId: String, targetWorkspaceId: String, now: Long
        ): Int {
            val entity = stored[projectId]?.takeIf { it.workspaceId == sourceWorkspaceId } ?: return 0
            stored[projectId] = entity.copy(workspaceId = targetWorkspaceId)
            return 1
        }
    }

    private lateinit var workspaceDao: FakeWorkspaceDao
    private lateinit var projectDao: FakeProjectDao

    /** Records whether the runner is executing; can inject a mid-transaction failure. */
    private var inTransaction = false
    private var failInsideTransaction = false

    /**
     * Models Room's `withTransaction` semantics honestly: writes inside the
     * block are visible, but a mid-transaction exception ROLLS BACK to the
     * pre-transaction snapshot (state restored) before rethrowing.
     */
    private val recordingRunner: suspend (suspend () -> Unit) -> Unit = { block ->
        inTransaction = true
        val wsSnapshot = workspaceDao.stored.toMap()
        val projectSnapshot = projectDao.stored.toMap()
        try {
            if (failInsideTransaction) {
                // Run ONE write inside, then blow up mid-transaction.
                workspaceDao.deactivateAll()
                throw IllegalStateException("INJECTED TRANSACTION FAILURE")
            }
            block()
        } catch (t: Throwable) {
            // ROLLBACK — exactly what a real Room transaction guarantees.
            workspaceDao.stored.clear()
            workspaceDao.stored.putAll(wsSnapshot)
            projectDao.stored.clear()
            projectDao.stored.putAll(projectSnapshot)
            throw t
        } finally {
            inTransaction = false
        }
    }

    @Before
    fun setup() {
        workspaceDao = FakeWorkspaceDao()
        projectDao = FakeProjectDao()
    }

    private fun service(): WorkspaceRuntimeService = WorkspaceRuntimeService(
        workspaceDao = workspaceDao,
        projectDao = projectDao,
        projectRootPathResolver = { id -> "workspaces/proj_$id" },
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        transactionRunner = recordingRunner
    )

    private suspend fun seedWorkspace(id: String, active: Boolean, pinnedProject: Long?): WorkspaceEntity {
        val entity = WorkspaceEntity(
            id = id,
            name = "WS $id",
            description = "",
            networkPolicy = NetworkPolicy.HYBRID.name,
            autonomyPolicy = "SUPERVISED",
            settingsJson = "{}",
            isActive = active,
            lastActiveProjectId = pinnedProject,
            createdAtEpochMs = 1000L,
            lastAccessedEpochMs = if (active) 5000L else 1000L
        )
        workspaceDao.stored[id] = entity
        return entity
    }

    private fun seedProject(id: Long, workspaceId: String, updatedAt: Long = 100L, state: String = "ACTIVE") {
        projectDao.stored[id] = ProjectEntity(
            id = id,
            name = "Project $id",
            description = null,
            rootPath = "workspaces/proj_$id",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = updatedAt,
            workspaceId = workspaceId,
            lifecycleState = state
        )
    }

    @Test
    fun `an injected mid-transaction failure leaves NO partial state`() = runBlocking {
        seedWorkspace("ws-existing", active = true, pinnedProject = null)
        failInsideTransaction = true

        val created = runCatching { service().createWorkspace("New", "desc") }
        assertTrue("the injected failure must propagate (fail-closed)", created.isFailure)

        // No orphan workspace row, no half-deactivated world:
        assertEquals(
            "GAP-16: no new workspace row may survive a failed transaction",
            setOf("ws-existing"),
            workspaceDao.stored.keys
        )
        assertTrue(
            "GAP-16: the pre-existing active workspace must still be active " +
                    "(a REAL Room transaction would roll the deactivate back)",
            workspaceDao.stored["ws-existing"]?.isActive == true
        )
    }

    @Test
    fun `createWorkspace runs its multi-writes inside ONE transaction`() = runBlocking {
        seedWorkspace("ws-existing", active = true, pinnedProject = null)
        val svc = service()

        var sawWriteInTransaction = false
        val spyDao = object : WorkspaceDao by workspaceDao {
            override suspend fun insertOrUpdate(workspace: WorkspaceEntity) {
                sawWriteInTransaction = sawWriteInTransaction || inTransaction
                workspaceDao.insertOrUpdate(workspace)
            }
        }

        val ws = WorkspaceRuntimeService(
            workspaceDao = spyDao,
            projectDao = projectDao,
            projectRootPathResolver = { id -> "workspaces/proj_$id" },
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            transactionRunner = recordingRunner
        )
        val created = ws.createWorkspace("Transactional", "desc")
        assertNotNull(created)
        assertTrue(
            "GAP-16: the workspace insert must happen INSIDE the transaction",
            sawWriteInTransaction
        )
        assertEquals(
            "the new workspace must be the only active one",
            created.id,
            workspaceDao.getActiveWorkspace()?.id
        )
        // Its OWN sandbox project must exist and be pinned.
        val pinned = workspaceDao.stored[created.id]?.lastActiveProjectId
        assertNotNull("GAP-16: the new workspace must pin its own project", pinned)
        assertNotNull(projectDao.stored[pinned])
        assertEquals(created.id, projectDao.stored[pinned]?.workspaceId)
    }

    @Test
    fun `switching to a workspace with a STALE pin reconciles immediately`() = runBlocking {
        // ws-target pins project 55 which does NOT exist (deleted).
        seedWorkspace("ws-a", active = true, pinnedProject = null)
        seedWorkspace("ws-target", active = false, pinnedProject = 55L)
        // ws-target's own ACTIVE project (the deterministic replacement).
        seedProject(77L, "ws-target", updatedAt = 200L)

        val svc = service()
        assertTrue(svc.switchWorkspace("ws-target"))

        val target = workspaceDao.stored["ws-target"]
        assertEquals(
            "GAP-16: the stale pin must be reconciled to the newest ACTIVE OWNED project immediately",
            77L,
            target?.lastActiveProjectId
        )
        assertTrue("the switched-to workspace must be active", target?.isActive == true)
        assertFalse("the previous workspace must be deactivated", workspaceDao.stored["ws-a"]?.isActive == true)
    }

    @Test
    fun `switching with no resolvable project clears the pin honestly`() = runBlocking {
        seedWorkspace("ws-a", active = true, pinnedProject = null)
        // ws-empty pins a project that is ARCHIVED (not resolvable) and has
        // NO active project of its own.
        seedWorkspace("ws-empty", active = false, pinnedProject = 88L)
        seedProject(88L, "ws-empty", state = "ARCHIVED")

        assertTrue(service().switchWorkspace("ws-empty"))
        assertNull(
            "GAP-16: with no resolvable project the pin is cleared (honest null), never kept stale",
            workspaceDao.stored["ws-empty"]?.lastActiveProjectId
        )
    }

    @Test
    fun `switching to a workspace with a VALID pin keeps it`() = runBlocking {
        seedWorkspace("ws-a", active = true, pinnedProject = null)
        seedWorkspace("ws-ok", active = false, pinnedProject = 99L)
        seedProject(99L, "ws-ok", state = "ACTIVE")

        assertTrue(service().switchWorkspace("ws-ok"))
        assertEquals(
            "GAP-16: a valid pin (exists + ACTIVE + owned) is preserved",
            99L,
            workspaceDao.stored["ws-ok"]?.lastActiveProjectId
        )
    }
}
