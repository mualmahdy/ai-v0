package com.example.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.application.bootstrap.WorkspaceBootstrapOrchestrator
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.AutonomyPolicy
import com.example.infrastructure.persistence.AppDatabase
import com.example.presentation.viewmodel.SettingsViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ============================================================================
 * WorkspaceStartupMutationRaceTest — the STAGE-7 USER-BUILD FIX pin
 * ============================================================================
 *
 * The user's full-suite build failed with:
 *   SettingsCategoriesTest.the network category expands and a real policy
 *   write lands in the durable column — ComposeTimeoutException: Condition
 *   still not satisfied after 15000 ms.
 *
 * Root cause (reproduced deterministically by delaying ONLY the bootstrap):
 * a user-facing workspace mutation that lands while the init bootstrap is
 * still in flight used to be SILENTLY DROPPED by the `?: return` guard in
 * WorkspaceRuntimeService — the settings tap evaporated, the bootstrap then
 * surfaced the OLD policy from the durable column, and the UI wait timed
 * out. Under a single-task full-suite run (the user's `build`) the
 * bootstrap threads lose to the test-clock-driven UI path; under an
 * isolated run they win — which is why the suite was green in the delivery
 * round and red on the user's machine.
 *
 * This class pins the fix on the REAL stack (real in-memory Room + real
 * orchestrator + the SettingsViewModel surface — no fakes):
 *
 *  1. a NETWORK policy write during startup waits for the bootstrap and
 *     reaches the durable column;
 *  2. an AUTONOMY policy write (the second user-facing mutation the
 *     redesigned settings screen exposes) gets the identical guarantee;
 *  3. when the bootstrap NEVER lands, the write fails HONESTLY (the
 *     surfaced error contract) instead of being silently dropped — and
 *     nothing fabricated lands anywhere.
 *
 * The startup delay is injected through the PUBLIC projectRootResolver
 * seam (called inside the real bootstrap), never by patching production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WorkspaceStartupMutationRaceTest {

    private lateinit var db: AppDatabase
    private lateinit var baseDir: File
    private var serviceScope: CoroutineScope? = null
    private lateinit var service: com.example.application.workspace.WorkspaceRuntimeService

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        serviceScope?.cancel()
        if (this::db.isInitialized) db.close()
        if (this::baseDir.isInitialized) baseDir.deleteRecursively()
    }

    /**
     * Real stack over real in-memory Room. [bootstrapDelayMs] slows the REAL
     * bootstrap through the public projectRootResolver seam (it is called
     * while the bootstrap materializes the sandbox project root), widening
     * the startup window exactly the way a loaded machine does.
     * [serviceDispatcher] lets a test freeze the init entirely (a
     * StandardTestDispatcher nobody advances) — the honest-failure pin.
     */
    private fun newRealStack(
        bootstrapDelayMs: Long = 0L,
        serviceDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    ): SettingsViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = buildInMemoryDb()
        baseDir = File(context.filesDir, "test_startup_mutation_race").apply {
            deleteRecursively(); mkdirs()
        }
        val orchestrator = WorkspaceBootstrapOrchestrator(
            database = db,
            projectRootResolver = { id ->
                if (bootstrapDelayMs > 0) Thread.sleep(bootstrapDelayMs)
                File(baseDir, "proj_$id")
            }
        )
        val scope = CoroutineScope(SupervisorJob() + serviceDispatcher).also { serviceScope = it }
        service = com.example.application.workspace.WorkspaceRuntimeService(
            workspaceDao = db.workspaceDao(),
            projectDao = db.projectDao(),
            bootstrapOrchestrator = orchestrator,
            coroutineScope = scope
        )
        return SettingsViewModel(workspaceRuntimeService = service)
    }

    private fun buildInMemoryDb(): AppDatabase {
        return androidx.room.Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @Test
    fun `a network policy write that lands during startup waits for the bootstrap and reaches the durable column`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = newRealStack(bootstrapDelayMs = 400)

        // The mutation fires while the REAL bootstrap is still mid-flight
        // (the resolver seam is inside its sleep) — the exact window of the
        // user's failure. Pre-fix: silently dropped, the flow below never
        // satisfies, withTimeout(10s) fails the test.
        vm.updateWorkspaceNetworkPolicy(NetworkPolicy.OFFLINE)

        runBlocking {
            withTimeout(10_000) {
                vm.activeWorkspace.first { it?.networkPolicy == NetworkPolicy.OFFLINE }
            }
        }
        val durableColumn = runBlocking {
            db.workspaceDao().getWorkspaceById("default")?.networkPolicy
        }
        assertEquals(
            "the tap must reach the durable column — exactly what a process restart would read",
            NetworkPolicy.OFFLINE.name,
            durableColumn
        )
    }

    @Test
    fun `an autonomy policy write that lands during startup waits for the bootstrap and reaches the durable column`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = newRealStack(bootstrapDelayMs = 400)

        // The second user-facing mutation exposed by the redesigned settings
        // screen (the chat-AI autonomy rows) — same family, same guarantee.
        vm.setAutonomyPolicy(AutonomyPolicy.AUTONOMOUS)

        runBlocking {
            withTimeout(10_000) {
                vm.activeWorkspace.first {
                    it?.settings?.get("autonomyPolicy") == AutonomyPolicy.AUTONOMOUS.name
                }
            }
        }
        val durableColumn = runBlocking {
            db.workspaceDao().getWorkspaceById("default")?.autonomyPolicy
        }
        assertEquals(
            "the autonomy tap must reach the durable column",
            AutonomyPolicy.AUTONOMOUS.name,
            durableColumn
        )
    }

    @Test
    fun `a write fails honestly when the bootstrap never lands instead of being silently dropped`() {
        // REAL time on Main: the 200ms bound must actually expire. The
        // service scope is a StandardTestDispatcher nobody advances, so the
        // init launch is queued forever — the bootstrap never lands.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val vm = newRealStack(serviceDispatcher = StandardTestDispatcher())
        service.startupMutationWaitMs = 200

        vm.updateWorkspaceNetworkPolicy(NetworkPolicy.OFFLINE)

        // Bounded wait for the honest failure to surface on the VM's state.
        var surfaced: String? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val msg = vm.state.value.errorMessage
            if (msg != null) { surfaced = msg; break }
            Thread.sleep(20)
        }
        assertNotNull(
            "the dropped-tap contract must surface an honest error, never a silent success",
            surfaced
        )
        assertTrue(
            "the surfaced error must name the network-policy mutation",
            surfaced!!.contains("سياسة الشبكة")
        )
        // And nothing fabricated landed anywhere:
        assertNull("no workspace may be fabricated", vm.activeWorkspace.value)
        assertNull(
            "no durable row may exist — the bootstrap never ran",
            runBlocking { db.workspaceDao().getWorkspaceById("default") }
        )
    }
}
