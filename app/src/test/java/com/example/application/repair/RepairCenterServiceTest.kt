package com.example.application.repair

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.KnowledgeDocumentEntity
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.TaskEntity
import com.example.infrastructure.persistence.entities.WorkspaceEntity
import com.example.infrastructure.storage.SandboxProjectFileStore
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
import java.io.File

/**
 * ============================================================================
 * RepairCenterServiceTest — §28 REPAIR / RECONCILIATION CENTER coverage
 * ============================================================================
 *
 * The repair center is the audit-critical governance/recovery surface
 * (DETECT → EXPLAIN → REPAIR → VERIFY) and had ZERO direct tests: its
 * conditions were only exercised incidentally. This suite pins every
 * detection family over the REAL in-memory Room stack:
 *
 *   - a CONSISTENT workspace produces no conditions (the negative case —
 *     the detector must not fabricate work);
 *   - WORKSPACE_WITHOUT_VALID_PROJECT: a stale durable binding is detected
 *     and repaired by REBINDING to the most recent active project;
 *   - ORPHANED_PROJECT: a project pointing at a missing workspace is
 *     re-owned when ownership is unambiguous (exactly one workspace);
 *   - STALE_EXECUTION: a RUNNING task with no live execution (process-death
 *     leftover) is surfaced with resumability facts and reconciled to an
 *     honest FAILED row (degraded: PROCESS_DEATH);
 *   - INVALID_RESOURCE_REFERENCE: knowledge of a missing project is rebound
 *     to WORKSPACE-SHARED (recoverable — never deleted);
 *   - an unknown future condition code is refused honestly
 *     (NO_REPAIR_DEFINED, not a fabricated success).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepairCenterServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var baseDir: File
    private lateinit var fileStore: SandboxProjectFileStore
    private lateinit var service: RepairCenterService

    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        baseDir = File(context.filesDir, "test_repair_center").apply { deleteRecursively(); mkdirs() }
        fileStore = SandboxProjectFileStore(File(baseDir, "projects"))
        service = RepairCenterService(database = db, fileStore = fileStore)
    }

    @After
    fun tearDown() {
        db.close()
        baseDir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Seeding helpers (direct DAO writes — the corruption scenarios the
    // production paths can never produce, which is exactly the point).
    // ------------------------------------------------------------------

    private suspend fun seedWorkspace(
        id: String = "default",
        lastActiveProjectId: Long? = null
    ) {
        db.workspaceDao().insertOrUpdate(
            WorkspaceEntity(
                id = id,
                name = "مساحة $id",
                description = "",
                networkPolicy = "HYBRID",
                autonomyPolicy = "ASSISTED",
                settingsJson = "{}",
                isActive = id == "default",
                lastActiveProjectId = lastActiveProjectId,
                createdAtEpochMs = now,
                lastAccessedEpochMs = now
            )
        )
    }

    private suspend fun seedProject(
        name: String,
        workspaceId: String?,
        lifecycleState: String = "ACTIVE"
    ): Long = db.projectDao().insertProject(
        ProjectEntity(
            name = name,
            description = null,
            rootPath = "/unused",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            workspaceId = workspaceId,
            lifecycleState = lifecycleState
        )
    )

    @Test
    fun `a consistent workspace produces no conditions`() = runBlocking {
        seedWorkspace("default")
        val pid = seedProject("مشروع سليم", "default")
        db.workspaceDao().setActiveProject("default", pid, now)

        val conditions = service.detectAll()

        assertTrue(
            "expected no conditions, got: ${conditions.map { it.code }}",
            conditions.isEmpty()
        )
        assertTrue(service.interruptedExecutions().isEmpty())
    }

    @Test
    fun `a stale durable binding is detected and repaired by rebinding`() = runBlocking {
        // The workspace pins project 999 which never existed.
        seedWorkspace("default", lastActiveProjectId = 999L)
        val realProject = seedProject("المشروع الوحيد النشط", "default")

        val conditions = service.detectAll()
        val stale = conditions.firstOrNull { it.code == "WORKSPACE_WITHOUT_VALID_PROJECT" }
        assertNotNull("expected WORKSPACE_WITHOUT_VALID_PROJECT, got: ${conditions.map { it.code }}", stale)
        assertEquals(listOf("default"), stale!!.affectedIds)

        val report = service.repair(stale)

        // REPAIR: rebound to the most recent active project…
        assertTrue(report.repaired)
        assertTrue(report.action.startsWith("rebound_active_project"))
        // …and VERIFY: the condition is gone and the durable column moved.
        assertTrue(report.verifiedAfterRepair)
        assertEquals(realProject, db.workspaceDao().getWorkspaceById("default")!!.lastActiveProjectId)
        assertTrue(service.detectAll().none { it.code == "WORKSPACE_WITHOUT_VALID_PROJECT" })
    }

    @Test
    fun `an orphaned project is re-owned when exactly one workspace remains`() = runBlocking {
        seedWorkspace("default")
        val ownedProject = seedProject("مشروع مملوك", "default")
        db.workspaceDao().setActiveProject("default", ownedProject, now)
        // The orphan points at a workspace row that does not exist.
        val orphanId = seedProject("المشروع اليتيم", "ghost-workspace")

        val conditions = service.detectAll()
        val orphan = conditions.firstOrNull { it.code == "ORPHANED_PROJECT" }
        assertNotNull("expected ORPHANED_PROJECT, got: ${conditions.map { it.code }}", orphan)
        assertEquals(listOf(orphanId.toString()), orphan!!.affectedIds)

        val report = service.repair(orphan)

        // Unambiguous ownership (a single workspace) → REOWNED, never deleted.
        assertTrue(report.repaired)
        assertTrue(report.action.startsWith("reowned_orphaned_projects"))
        assertTrue(report.verifiedAfterRepair)
        assertEquals("default", db.projectDao().getProjectById(orphanId)!!.workspaceId)
    }

    @Test
    fun `a RUNNING task with no live execution is surfaced and reconciled to FAILED`() = runBlocking {
        seedWorkspace("default")
        val pid = seedProject("مشروع", "default")
        db.workspaceDao().setActiveProject("default", pid, now)
        db.taskDao().insertOrUpdateTask(
            TaskEntity(
                id = "task-stale-1",
                assignedAgentId = "default",
                rawPrompt = "لخص وثيقة المشروع",
                lifecycleState = "RUNNING",
                autonomyPolicy = "ASSISTED",
                resultSummary = null,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                workspaceId = "default",
                checkpointJson = null,
                executionContextJson = null
            )
        )

        // DETECT + the §11 interrupted-execution surface (resumability facts).
        val conditions = service.detectAll()
        val stale = conditions.firstOrNull { it.code == "STALE_EXECUTION" }
        assertNotNull("expected STALE_EXECUTION, got: ${conditions.map { it.code }}", stale)
        assertEquals(listOf("task-stale-1"), stale!!.affectedIds)

        val interrupted = service.interruptedExecutions()
        assertEquals(1, interrupted.size)
        assertEquals("task-stale-1", interrupted.first().taskId)
        assertEquals("لخص وثيقة المشروع", interrupted.first().rawPrompt)
        assertFalse(interrupted.first().isResumable)

        val report = service.repair(stale)

        assertTrue(report.repaired)
        assertTrue(report.action.startsWith("reconciled_stale_executions"))
        assertTrue(report.verifiedAfterRepair)
        val reconciled = db.taskDao().getAllTasks().first { it.id == "task-stale-1" }
        assertEquals("FAILED", reconciled.lifecycleState)
        assertTrue(reconciled.isDegraded)
        assertEquals("PROCESS_DEATH", reconciled.degradedReason)
    }

    @Test
    fun `knowledge referencing a missing project is rebound to workspace-shared`() = runBlocking {
        seedWorkspace("default")
        val pid = seedProject("مشروع", "default")
        db.workspaceDao().setActiveProject("default", pid, now)
        // The document claims a project that does not exist.
        db.knowledgeDocumentDao().insertOrUpdate(
            KnowledgeDocumentEntity(
                id = "doc-orphan",
                workspaceId = "default",
                title = "وثيقة يتيمة",
                sourceUri = "file:///doc.md",
                content = "محتوى",
                tagsJson = "[]",
                totalChunks = 0,
                totalTokensEstimated = 0,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                projectId = 4242L
            )
        )

        val conditions = service.detectAll()
        val invalidRef = conditions.firstOrNull { it.code == "INVALID_RESOURCE_REFERENCE" }
        assertNotNull("expected INVALID_RESOURCE_REFERENCE, got: ${conditions.map { it.code }}", invalidRef)
        assertEquals(listOf("doc-orphan"), invalidRef!!.affectedIds)

        val report = service.repair(invalidRef)

        // Recoverable: re-scoped to WORKSPACE-SHARED, never deleted.
        assertTrue(report.repaired)
        assertTrue(report.action.startsWith("rebound_knowledge_to_workspace"))
        assertTrue(report.verifiedAfterRepair)
        assertEquals(null, db.knowledgeDocumentDao().getDocumentById("doc-orphan")!!.projectId)
    }

    @Test
    fun `an unknown condition code is refused honestly`() = runBlocking {
        val unknown = RepairCenterService.Condition(
            code = "FUTURE_CONDITION",
            description = "شرط مستقبلي لم يُبرمج إصلاحه بعد",
            affectedIds = listOf("x")
        )

        val report = service.repair(unknown)

        // NO fabricated success: the surface says there is no repair.
        assertFalse(report.repaired)
        assertEquals("NO_REPAIR_DEFINED", report.action)
        // verifiedAfterRepair is VACUOUSLY true for an unknown code: VERIFY
        // re-detects and the code never appears in detection output. The
        // honest signal is `repaired=false` + the explicit NO_REPAIR_DEFINED
        // action — pinned above.
        assertTrue(report.verifiedAfterRepair)
    }
}
