package com.example.application.project

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.context.ResourceHealthState
import com.example.domain.core.project.DependencyRequirement
import com.example.domain.core.project.DependencyStatus
import com.example.domain.core.project.ProjectDependencyType
import com.example.domain.core.project.ProjectReadinessState
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.KnowledgeDocumentEntity
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.WorkspaceEntity
import com.example.infrastructure.storage.SandboxProjectFileStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ============================================================================
 * ProjectReadinessServiceTest — §24/§25 PROJECT READINESS coverage
 * ============================================================================
 *
 * REPAIR ORDER §24/§25: readiness is DERIVED from authoritative runtime/
 * resource state — never manually asserted by UI. The derivation had ZERO
 * direct tests. This suite pins the state machine over the REAL in-memory
 * Room stack + a real sandbox file store:
 *
 *   - READY: healthy files root, no unresolved dependencies;
 *   - DEGRADED: an OPTIONAL dependency is missing (soft failure) or the
 *     knowledge component reports UNKNOWN (no documents yet);
 *   - BLOCKED: a REQUIRED dependency is missing (hard failure — the
 *     project cannot run);
 *   - knowledge documents move the KNOWLEDGE component to READY with the
 *     honest private/shared counts;
 *   - the persisted dependency rows are re-derived by assess() (the
 *     authority consumed by the dependency browser) and surfaced through
 *     dependenciesFor();
 *   - an unknown project honestly reports null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProjectReadinessServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var baseDir: File
    private lateinit var fileStore: SandboxProjectFileStore
    private lateinit var service: ProjectReadinessService

    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        baseDir = File(context.filesDir, "test_readiness").apply { deleteRecursively(); mkdirs() }
        fileStore = SandboxProjectFileStore(File(baseDir, "projects"))
        service = ProjectReadinessService(database = db, fileStore = fileStore)
    }

    @After
    fun tearDown() {
        db.close()
        baseDir.deleteRecursively()
    }

    private suspend fun seedWorkspaceAndProject(): Long {
        db.workspaceDao().insertOrUpdate(
            WorkspaceEntity(
                id = "default",
                name = "مساحة العمل",
                description = "",
                networkPolicy = "HYBRID",
                autonomyPolicy = "ASSISTED",
                settingsJson = "{}",
                isActive = true,
                lastActiveProjectId = null,
                createdAtEpochMs = now,
                lastAccessedEpochMs = now
            )
        )
        return db.projectDao().insertProject(
            ProjectEntity(
                name = "مشروع الجاهزية",
                description = null,
                rootPath = "/unused",
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                workspaceId = "default"
            )
        )
    }

    private suspend fun seedDocument(
        id: String,
        projectId: Long?,
        workspaceId: String = "default"
    ) {
        db.knowledgeDocumentDao().insertOrUpdate(
            KnowledgeDocumentEntity(
                id = id,
                workspaceId = workspaceId,
                title = "وثيقة $id",
                sourceUri = "file:///$id.md",
                content = "محتوى $id",
                tagsJson = "[]",
                totalChunks = 0,
                totalTokensEstimated = 0,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                projectId = projectId
            )
        )
    }

    @Test
    fun `a project with a healthy files root and no dependencies is READY`() = runBlocking {
        val pid = seedWorkspaceAndProject()

        val report = service.assess("default", pid)

        assertEquals(ProjectReadinessState.READY, report!!.state)
        val files = report.findings.first { it.component == "FILES" }
        assertEquals(ResourceHealthState.READY, files.state)
        // No documents at all → the knowledge component is honestly UNKNOWN.
        val knowledge = report.findings.first { it.component == "KNOWLEDGE" }
        assertEquals(ResourceHealthState.UNKNOWN, knowledge.state)
    }

    @Test
    fun `assess honestly reports null for an unknown project`() = runBlocking {
        seedWorkspaceAndProject()

        assertNull(service.assess("default", 999999L))
    }

    @Test
    fun `a missing REQUIRED model dependency BLOCKS the project`() = runBlocking {
        val pid = seedWorkspaceAndProject()
        service.declareDependency(
            projectId = pid,
            type = "MODEL",
            key = "model-unavailable",
            requirement = DependencyRequirement.REQUIRED
        )

        val report = service.assess("default", pid)!!

        assertEquals(ProjectReadinessState.BLOCKED, report.state)
        val modelFinding = report.findings.first { it.component == "MODEL" }
        // A REQUIRED missing dependency is UNAVAILABLE (hard), not DEGRADED.
        assertEquals(ResourceHealthState.UNAVAILABLE, modelFinding.state)
        assertTrue(
            "expected the honest MISSING status in the message, got: ${modelFinding.message}",
            modelFinding.message.contains("MODEL:model-unavailable")
        )
        // The re-derived status is persisted (the dependency browser's
        // authority) and surfaced through dependenciesFor().
        assertEquals(
            DependencyStatus.MISSING,
            service.dependenciesFor(pid).first { it.key == "model-unavailable" }.status
        )
    }

    @Test
    fun `a missing OPTIONAL tool dependency DEGRADES the project`() = runBlocking {
        val pid = seedWorkspaceAndProject()
        service.declareDependency(
            projectId = pid,
            type = "TOOL",
            key = "tool-not-registered",
            requirement = DependencyRequirement.OPTIONAL
        )

        val report = service.assess("default", pid)!!

        assertEquals(ProjectReadinessState.DEGRADED, report.state)
        val toolFinding = report.findings.first { it.component == "TOOL" }
        // An OPTIONAL missing dependency is DEGRADED (soft) — the project
        // still runs.
        assertEquals(ResourceHealthState.DEGRADED, toolFinding.state)
    }

    @Test
    fun `knowledge documents move the KNOWLEDGE component to READY with honest counts`() = runBlocking {
        val pid = seedWorkspaceAndProject()
        // One PROJECT-PRIVATE document + one WORKSPACE-SHARED document.
        seedDocument("doc-private", projectId = pid)
        seedDocument("doc-shared", projectId = null)

        val report = service.assess("default", pid)!!

        val knowledge = report.findings.first { it.component == "KNOWLEDGE" }
        assertEquals(ResourceHealthState.READY, knowledge.state)
        assertTrue(
            "expected the honest private+shared counts, got: ${knowledge.message}",
            knowledge.message.contains("1 مستنداً خاصاً") && knowledge.message.contains("1 مشتركاً")
        )
        // Knowledge alone never blocks or degrades readiness.
        assertEquals(ProjectReadinessState.READY, report.state)
    }

    @Test
    fun `declareDependency round-trips the declared contract`() = runBlocking {
        val pid = seedWorkspaceAndProject()
        service.declareDependency(
            projectId = pid,
            type = "TOOL",
            key = "shell_tool",
            requirement = DependencyRequirement.OPTIONAL
        )

        val declared = service.dependenciesFor(pid).single()

        assertEquals(ProjectDependencyType.TOOL, declared.type)
        assertEquals("shell_tool", declared.key)
        assertEquals(DependencyRequirement.OPTIONAL, declared.requirement)
        // A fresh declaration starts MISSING until assess() re-derives it.
        assertEquals(DependencyStatus.MISSING, declared.status)
    }
}
