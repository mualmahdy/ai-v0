package com.example.application.attachment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.artifacts.ArtifactService
import com.example.application.transfer.FileTransferService
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.storage.SandboxProjectFileStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * WorkspaceImportTest — STAGE 7 (the file explorer's phone-storage upload)
 * ============================================================================
 *
 * The coordinator's [ChatAttachmentCoordinator.importWorkspaceFiles] arm —
 * the SAME governed transfer pipeline as chat attachments with a different
 * destination (`uploads/`, no turn binding):
 *
 *  - a multi-file import lands EVERY file under uploads/ through the real
 *    staging + atomic-promotion path, registers the artifact rows, and
 *    reports honest per-file results;
 *  - ONE file's failure never aborts the batch (the missing-source arm);
 *  - an un-attributable scope fails the WHOLE call honestly (nothing
 *    imported → nothing half-reported).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WorkspaceImportTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var fileStore: SandboxProjectFileStore
    private lateinit var workspaceService: WorkspaceRuntimeService
    private lateinit var artifactService: ArtifactService
    private lateinit var coordinator: ChatAttachmentCoordinator
    private var serviceScope: CoroutineScope? = null

    /** The fake phone-storage source (exactly where the ContentPort doc says fakes go). */
    private val contentFiles = mutableMapOf<String, Pair<String, ByteArray>>()

    private val fakeContentPort = object : FileTransferService.ContentPort {
        override fun openRead(uri: String): InputStream? =
            contentFiles[uri]?.second?.let { ByteArrayInputStream(it) }

        override fun openWrite(uri: String): java.io.OutputStream? = null

        override fun queryDisplayName(uri: String): String? = contentFiles[uri]?.first

        override fun querySize(uri: String): Long? = contentFiles[uri]?.second?.size?.toLong()
    }

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val sandboxDir = File(context.getDir("test_workspace_imports", Context.MODE_PRIVATE), "projects")
        fileStore = SandboxProjectFileStore(sandboxDir)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { serviceScope = it }
        workspaceService = WorkspaceRuntimeService(
            workspaceDao = com.example.presentation.viewmodel.FakeWorkspaceDaoForVm(),
            projectDao = com.example.presentation.viewmodel.FakeProjectDaoForVm(),
            coroutineScope = scope
        )
        Thread.sleep(100) // bootstrap settle
        workspaceService.createWorkspace("مساحة الاستيراد", "اختبار الاستيراد")
        artifactService = ArtifactService(database = database, fileStore = fileStore)
        coordinator = ChatAttachmentCoordinator(
            fileTransferService = FileTransferService(fileStore),
            artifactService = artifactService,
            workspaceRuntimeService = workspaceService,
            fileStore = fileStore,
            contentPort = fakeContentPort
        )
        assertNotNull(
            "the workspace must have an active project for imports",
            workspaceService.activeProjectIdOrNull()
        )
    }

    @After
    fun tearDown() {
        serviceScope?.cancel()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a multi-file import lands every file under uploads with honest results`() = runBlocking {
        contentFiles["content://phone/report.pdf"] = "تقرير.pdf" to byteArrayOf(1, 2, 3, 4)
        contentFiles["content://phone/notes.txt"] = "ملاحظات.txt" to "نص الملاحظات".toByteArray()

        val results = coordinator.importWorkspaceFiles(
            listOf("content://phone/report.pdf", "content://phone/notes.txt")
        )

        assertEquals(2, results.size)
        results.forEach { result ->
            assertTrue("import must succeed: ${result.errorMessage}", result.isSuccess)
            assertNotNull(result.importedRelativePath)
            assertTrue(
                "the destination must be the uploads/ namespace, got ${result.importedRelativePath}",
                result.importedRelativePath!!.startsWith("uploads/")
            )
        }

        // The real files landed in the ACTIVE project's sandbox.
        val projectId = workspaceService.activeProjectIdOrNull()!!
        results.forEach { result ->
            assertTrue(
                fileStore.stat(fileStore.projectRoot(projectId), result.importedRelativePath!!).exists
            )
        }

        // The artifact rows are registered (scope/audit truth).
        val artifacts = artifactService.forProject(projectId)
        assertTrue(artifacts.any { it.name == "تقرير.pdf" })
        assertTrue(artifacts.any { it.name == "ملاحظات.txt" })
    }

    @Test
    fun `one file failure never aborts the batch - the honest per-file result`() = runBlocking {
        contentFiles["content://phone/ok.txt"] = "سليم.txt" to "محتوى".toByteArray()
        // "broken.doc" is NOT in the fake source — openRead returns null.

        val results = coordinator.importWorkspaceFiles(
            listOf("content://phone/ok.txt", "content://phone/broken.doc")
        )

        assertEquals(2, results.size)
        val ok = results.first { it.displayName == "سليم.txt" }
        val broken = results.first { it.displayName == "broken.doc" }
        assertTrue(ok.isSuccess)
        assertFalse(broken.isSuccess)
        assertEquals(0L, broken.sizeBytes)
        assertNotNull(broken.errorMessage)
        // The real reason, verbatim for the UI banner.
        assertTrue(broken.errorMessage!!.contains("تعذر فتح الملف"))
    }

    @Test
    fun `an empty list is a clean no-op`() = runBlocking {
        val results = coordinator.importWorkspaceFiles(emptyList())
        assertTrue(results.isEmpty())
    }
}
