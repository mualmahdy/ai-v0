package com.example.presentation.viewmodel

import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.AutonomyPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * SettingsViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage
 * for the SETTINGS feature ViewModel (ADR-6 slice 1)
 * ============================================================================
 *
 * Drives the REAL WorkspaceRuntimeService (fake DAOs, Unconfined dispatch)
 * and asserts the feature contract extracted from MainViewModel:
 *
 *  - the workspace-manager mutations route to the AUTHORITATIVE service
 *    (persisted columns change, the active/all flows update);
 *  - createWorkspace lands a new row AND switches to it (GAP-16 semantics
 *    live in the service);
 *  - the autonomy policy update persists through the DAO column that the
 *    execution pipeline consumes (REPAIR ORDER §20);
 *  - the network policy update persists + refreshes the domain model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var workspaceDao: FakeWorkspaceDaoForVm
    private lateinit var projectDao: FakeProjectDaoForVm
    private lateinit var service: WorkspaceRuntimeService
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        workspaceDao = FakeWorkspaceDaoForVm()
        projectDao = FakeProjectDaoForVm()
        service = WorkspaceRuntimeService(
            workspaceDao = workspaceDao,
            projectDao = projectDao,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        // Let the init bootstrap create the default workspace.
        Thread.sleep(100)
        viewModel = SettingsViewModel(workspaceRuntimeService = service)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `exposes the authoritative workspace flows (default bootstrap)`() {
        assertEquals(1, viewModel.allWorkspaces.value.size)
        assertEquals("default", viewModel.activeWorkspace.value?.id)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `createWorkspace persists a new workspace with its own project and switches to it`(): Unit = runBlocking {
        val before = workspaceDao.stored.values.toList()

        viewModel.createWorkspace(name = "مساحة التطوير", description = "وصف")

        Thread.sleep(100)
        val after = workspaceDao.stored.values.toList()
        assertTrue("A new workspace row must be persisted", after.size == before.size + 1)
        val created = after.first { it.name == "مساحة التطوير" }
        val boundProjectId = created.lastActiveProjectId
        assertTrue(
            "GAP-16: the new workspace must own a real project row",
            boundProjectId != null && boundProjectId > 0L
        )
        assertEquals(
            "Switching to the created workspace is part of the contract",
            created.id,
            viewModel.activeWorkspace.value?.id
        )
        assertNotNull(boundProjectId?.let { projectDao.stored[it] })
    }

    @Test
    fun `createWorkspace ignores blank names (no row, no error)`() {
        val before = workspaceDao.stored.size

        viewModel.createWorkspace(name = "   ", description = "")

        Thread.sleep(50)
        assertEquals(before, workspaceDao.stored.size)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `switchWorkspace reactivates the target row and deactivates the rest`() {
        viewModel.createWorkspace(name = "الثانية", description = "")
        Thread.sleep(100)
        val second = workspaceDao.stored.values.first { it.name == "الثانية" }

        // Switch back to the default workspace.
        viewModel.switchWorkspace("default")
        Thread.sleep(100)

        assertEquals("default", viewModel.activeWorkspace.value?.id)
        assertTrue(workspaceDao.deactivateAllCount > 0)
        assertEquals(
            "The target row is reactivated through setActive",
            "default" to workspaceDao.setActiveCalls.last().first,
            "default" to "default"
        )
        assertTrue(
            "The previously active row is deactivated",
            workspaceDao.stored[second.id]?.isActive == false
        )
    }

    @Test
    fun `updateWorkspaceNetworkPolicy persists the column and refreshes the domain model`() {
        viewModel.updateWorkspaceNetworkPolicy(NetworkPolicy.OFFLINE)
        Thread.sleep(100)

        assertEquals(
            "Persisted column must change",
            NetworkPolicy.OFFLINE.name,
            workspaceDao.stored["default"]?.networkPolicy
        )
        assertEquals(
            "Domain model flow must refresh",
            NetworkPolicy.OFFLINE,
            viewModel.activeWorkspace.value?.networkPolicy
        )
    }

    @Test
    fun `setAutonomyPolicy persists the authoritative autonomy column (REPAIR ORDER S20)`(): Unit = runBlocking {
        viewModel.setAutonomyPolicy(AutonomyPolicy.AUTONOMOUS)
        Thread.sleep(100)

        assertEquals(
            "The DAO column the execution pipeline reads must be updated",
            AutonomyPolicy.AUTONOMOUS.name,
            workspaceDao.stored["default"]?.autonomyPolicy
        )
        assertEquals(
            "The service's pinned lookup must resolve the new policy",
            AutonomyPolicy.AUTONOMOUS,
            service.autonomyPolicyForWorkspace("default")
        )
    }

    @Test
    fun `service failures surface an honest error message`() {
        // Corrupt the DAO so the switch throws (missing target row → the
        // service's transactional switch fails).
        workspaceDao.stored.clear()

        viewModel.switchWorkspace("ghost")
        Thread.sleep(100)

        assertNotNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.errorMessage!!.contains("تعذر"))

        // The error channel clears on dismissal.
        viewModel.dismissError()
        assertNull(viewModel.state.value.errorMessage)
    }
}
