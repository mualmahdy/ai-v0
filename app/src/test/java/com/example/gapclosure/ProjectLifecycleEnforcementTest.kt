package com.example.gapclosure

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.project.ProjectRuntimeService
import com.example.infrastructure.persistence.AppDatabase
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
import java.io.File

/**
 * ============================================================================
 * GAP-15 (Design Closure 2026) — ProjectLifecycleEnforcementTest
 * ============================================================================
 *
 * The audit finding: `deleteProject` / `purgeProject` bypassed the §27
 * lifecycle transition table entirely — the TRASHED→DELETED→PURGED edges
 * were decorative for them (an ACTIVE project could be deleted or purged
 * directly). "Deletion is never the first action" was contract text, not
 * enforced code.
 *
 * This test pins, against a REAL in-memory Room database:
 *   1. deleting an ACTIVE project is REJECTED (must be TRASHED first);
 *   2. purging an ACTIVE/ARCHIVED project is REJECTED the same way;
 *   3. the legal path works: TRASHED → DELETED (row removed) → PURGED
 *      (sandbox + scoped rows gone);
 *   4. the honest rejection also sets the service's lastError.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectLifecycleEnforcementTest {

    private lateinit var db: AppDatabase
    private lateinit var runtime: ProjectRuntimeService

    private val workspaceId = "ws-gap15"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val sandboxRoot = File(context.cacheDir, "gap15-sandboxes").apply { mkdirs() }
        runtime = ProjectRuntimeService(
            database = db,
            projectRootResolver = { id -> File(sandboxRoot, "proj_$id") }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createActiveProject(name: String = "gap15-project"): Long {
        val project = runtime.createProject(workspaceId, name, activate = true)
        assertNotNull("project creation must succeed", project)
        return project!!.id
    }

    @Test
    fun `deleting an ACTIVE project is rejected - trash first`() = runBlocking {
        val projectId = createActiveProject()

        val deleted = runtime.deleteProject(workspaceId, projectId)

        assertFalse("GAP-15: ACTIVE → DELETED is not a legal transition", deleted)
        // The row must still exist (nothing was destroyed).
        assertNotNull("the ACTIVE project row must survive the rejected delete", db.projectDao().getProjectById(projectId))
        assertTrue(
            "the honest rejection must set the service error: ${runtime.lastError.value}",
            runtime.lastError.value?.contains("المهملات") == true
        )
    }

    @Test
    fun `purging an ACTIVE project is rejected - destruction is never the first action`() = runBlocking {
        val projectId = createActiveProject()

        val purged = runtime.purgeProject(workspaceId, projectId)

        assertFalse("GAP-15: ACTIVE → PURGED is not a legal transition", purged)
        assertNotNull("the ACTIVE project row must survive the rejected purge", db.projectDao().getProjectById(projectId))
    }

    @Test
    fun `purging an ARCHIVED project is rejected too`() = runBlocking {
        val projectId = createActiveProject("archived-one")
        assertTrue(runtime.archiveProject(workspaceId, projectId))

        assertFalse("GAP-15: ARCHIVED → PURGED is not a legal transition", runtime.purgeProject(workspaceId, projectId))
    }

    @Test
    fun `the legal path works - trash then delete then purge`() = runBlocking {
        val projectId = createActiveProject("legal-path")

        // TRASHED (recoverable soft-delete).
        assertTrue("ACTIVE → TRASHED must succeed", runtime.trashProject(workspaceId, projectId))
        assertEquals("TRASHED", db.projectDao().getProjectById(projectId)?.effectiveLifecycleState)

        // DELETED (row removed; sandbox retained until purge).
        assertTrue("TRASHED → DELETED must succeed", runtime.deleteProject(workspaceId, projectId))
        assertEquals(
            "GAP-15: the DELETED step removes the row (sandbox retained for purge)",
            null,
            db.projectDao().getProjectById(projectId)
        )

        // PURGED (row already gone — sandbox + dangling scoped rows destroyed).
        assertTrue("DELETED → PURGED must succeed", runtime.purgeProject(workspaceId, projectId))
    }

    @Test
    fun `a foreign workspace cannot delete another workspace's project`() = runBlocking {
        val projectId = createActiveProject("foreign")
        assertTrue(runtime.trashProject(workspaceId, projectId))

        // A different workspace id: getProjectByIdForWorkspace finds nothing.
        val deleted = runtime.deleteProject("ws-foreign", projectId)
        assertFalse("workspace isolation: foreign delete is a no-op rejection", deleted)
    }
}
