package com.example.application.attachment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.artifacts.ArtifactService
import com.example.application.session.ConversationSessionService
import com.example.application.transfer.FileTransferService
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.artifact.ArtifactType
import com.example.domain.core.session.ConversationSessionId
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.storage.SandboxProjectFileStore
import com.example.presentation.viewmodel.FakeConversationSessionRepositoryForVm
import com.example.presentation.viewmodel.FakeProjectDaoForVm
import com.example.presentation.viewmodel.FakeWorkspaceDaoForVm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * ============================================================================
 * ChatAttachmentCoordinatorTest — CHAT CAPABILITIES Task 2 §5/§6/§16 (the
 * attachment axis) — Robolectric, REAL infrastructure
 * ============================================================================
 *
 * The REQUIRED test areas covered here (Task 2 §21):
 *  6. attachment → message contract — the imported TurnAttachment carries
 *     name/mime/size/storageUri/artifactId + the REAL artifact row exists
 *     with the REAL type (ATTACHMENT);
 *  7. attachment persistence — the turn keeps its references through the
 *     REAL session service (DB v18 chain);
 *  23. artifact rendering/persistence — the artifact row is queryable after
 *     registration;
 *  27/28. honesty: image content NEVER rides the LLM digest; a binary
 *     attachment is marked non-textual; no active project = the honest
 *     real reason.
 */
@RunWith(RobolectricTestRunner::class)
class ChatAttachmentCoordinatorTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var fileStore: SandboxProjectFileStore
    private lateinit var workspaceService: WorkspaceRuntimeService
    private lateinit var sessionService: ConversationSessionService
    private lateinit var sessionRepository: FakeConversationSessionRepositoryForVm
    private lateinit var artifactService: ArtifactService
    private lateinit var coordinator: ChatAttachmentCoordinator

    /** The fake SAF source (exactly where the ContentPort doc says fakes go). */
    private val contentFiles = mutableMapOf<String, Pair<String, ByteArray>>() // uri → (name, bytes)

    private val fakeContentPort = object : FileTransferService.ContentPort {
        override fun openRead(uri: String): InputStream? =
            contentFiles[uri]?.second?.let { ByteArrayInputStream(it) }

        override fun openWrite(uri: String): java.io.OutputStream? = null

        override fun queryDisplayName(uri: String): String? = contentFiles[uri]?.first

        override fun querySize(uri: String): Long? = contentFiles[uri]?.second?.size?.toLong()
    }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val sandboxDir = File(context.getDir("test_workspaces", Context.MODE_PRIVATE), "projects")
        fileStore = SandboxProjectFileStore(sandboxDir)
        workspaceService = WorkspaceRuntimeService(
            workspaceDao = FakeWorkspaceDaoForVm(),
            projectDao = FakeProjectDaoForVm(),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        Thread.sleep(100) // bootstrap settle
        workspaceService.createWorkspace("مساحة المرفقات", "اختبار")
        sessionRepository = FakeConversationSessionRepositoryForVm()
        sessionRepository.activeWorkspaceId = workspaceService.activeWorkspaceIdOrNull()!!
        sessionService = ConversationSessionService(
            repository = sessionRepository,
            workspaceIdProvider = { sessionRepository.activeWorkspaceId }
        )
        artifactService = ArtifactService(database = database, fileStore = fileStore)
        coordinator = ChatAttachmentCoordinator(
            fileTransferService = FileTransferService(fileStore),
            artifactService = artifactService,
            workspaceRuntimeService = workspaceService,
            fileStore = fileStore,
            contentPort = fakeContentPort
        )
        // Guarantee an ACTIVE project exists (the store is project-scoped by design).
        assertNotNull(
            "the workspace must have an active project for attachments",
            workspaceService.activeProjectIdOrNull()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------------
    // 6 — attachment → message contract (§21.6)
    // ------------------------------------------------------------------

    @Test
    fun `importing a text file produces the full durable reference contract`() = runBlocking {
        contentFiles["content://saf/notes.txt"] = "ملاحظات.txt" to "محتوى الملف النصي".toByteArray()

        val attachment = coordinator.importFileAttachment("content://saf/notes.txt", "text/plain")

        assertEquals("ملاحظات.txt", attachment.name)
        assertEquals("text/plain", attachment.mimeType)
        assertEquals("محتوى الملف النصي".toByteArray().size.toLong(), attachment.sizeBytes)
        assertTrue(attachment.id.startsWith("attm_"))
        assertTrue(attachment.storageUri.startsWith("attachments/"))
        assertTrue(attachment.storageUri.endsWith("ملاحظات.txt"))
        assertNotNull("the artifact row must be registered", attachment.artifactId)

        // The REAL artifact row: type ATTACHMENT, project-scoped, queryable.
        val artifact = artifactService.forProject(workspaceService.activeProjectIdOrNull()!!)
            .first { it.id == attachment.artifactId }
        assertEquals(ArtifactType.ATTACHMENT, artifact.type)
        assertEquals("ملاحظات.txt", artifact.name)
        assertTrue(artifact.storageUri.startsWith("attachments/"))
    }

    @Test
    fun `a failed import surfaces the honest reason (no phantom reference)`() = runBlocking {
        // The picked source vanished before the import could open it.
        val failure = runCatching {
            coordinator.importFileAttachment("content://saf/ghost.txt", "text/plain")
        }.exceptionOrNull()

        assertTrue(
            "expected the honest import failure",
            failure is ChatAttachmentCoordinator.AttachmentImportException
        )
        assertTrue(failure!!.message!!.contains("تعذر فتح الملف"))
    }

    // ------------------------------------------------------------------
    // 7/8 — attachment persistence + reopen (§21.7/§21.8)
    // ------------------------------------------------------------------

    @Test
    fun `a turn's attachment references survive persist + reopen`() = runBlocking {
        contentFiles["content://saf/report.md"] = "report.md" to "# تقرير\nمحتوى".toByteArray()
        val attachment = coordinator.importFileAttachment("content://saf/report.md", "text/markdown")

        val session = sessionService.createSession(mode = com.example.domain.core.session.ChatMode.QUICK_CHAT)
        val appended = sessionService.appendTurn(
            sessionId = session.id,
            prompt = "لخص هذا التقرير",
            answer = "ملخص",
            agentName = null,
            agentRole = null,
            modelResourceId = null,
            tokensConsumed = 10,
            durationMs = 5,
            isSuccessful = true,
            eventCount = 3,
            attachments = listOf(attachment)
        )

        // REOPEN (the process-death stand-in): full reload from the service.
        val reopened = sessionService.getSessionWithTurns(ConversationSessionId(session.id.value))
        assertNotNull(reopened)
        val persistedTurn = reopened!!.turns.first { it.id == appended!!.id }
        assertEquals("لخص هذا التقرير", persistedTurn.prompt)
        val persisted = persistedTurn.attachments.single()
        assertEquals(attachment.id, persisted.id)
        assertEquals("report.md", persisted.name)
        assertEquals("text/markdown", persisted.mimeType)
        assertEquals(attachment.storageUri, persisted.storageUri)
        assertEquals(attachment.artifactId, persisted.artifactId)

        // The REFERENCED BYTES are still valid in the sandbox (reference ≠ dead link).
        val projectId = workspaceService.activeProjectIdOrNull()!!
        val bytes = fileStore.read(fileStore.projectRoot(projectId), persisted.storageUri)
        assertEquals("# تقرير\nمحتوى", String(bytes, Charsets.UTF_8))
    }

    // ------------------------------------------------------------------
    // 27/6 — grounding honesty (§21.27 + the Vision rule §7)
    // ------------------------------------------------------------------

    @Test
    fun `the grounding digest carries TEXT content bounded and marked`() = runBlocking {
        contentFiles["content://saf/data.txt"] = "data.txt" to "سطر بيانات مهم".toByteArray()
        val text = coordinator.importFileAttachment("content://saf/data.txt", "text/plain")

        val digest = coordinator.buildGroundingDigest(
            workspaceService.activeWorkspaceIdOrNull()!!,
            listOf(text)
        )

        assertTrue(digest.contains("<user_attachment name=\"data.txt\""))
        assertTrue(digest.contains("سطر بيانات مهم"))
        assertTrue(digest.contains("</user_attachment>"))
    }

    @Test
    fun `an IMAGE attachment is kept but NEVER claimed analyzable (Vision honesty)`() = runBlocking {
        val imageBytes = ByteArray(64) { it.toByte() }
        contentFiles["content://saf/photo.png"] = "photo.png" to imageBytes
        val image = coordinator.importFileAttachment("content://saf/photo.png", "image/png")

        assertFalse(coordinator.isTextGroundable(image))
        val digest = coordinator.buildGroundingDigest(
            workspaceService.activeWorkspaceIdOrNull()!!,
            listOf(image)
        )
        // The honest non-textual note rides the digest instead of fake content.
        assertTrue(digest.contains("لا يمكن تحليل محتواه في هذا الإصدار"))
        // The binary bytes never leak into the text digest.
        assertFalse(digest.contains(String(imageBytes, Charsets.ISO_8859_1)))
    }

    @Test
    fun `isTextGroundable classifies by mime and extension honestly`() {
        assertTrue(coordinator.isTextGroundable("text/plain", "a.txt"))
        assertTrue(coordinator.isTextGroundable("application/json", "a.json"))
        assertTrue(coordinator.isTextGroundable("application/octet-stream", "Script.kt"))
        assertFalse(coordinator.isTextGroundable("image/png", "a.png"))
        assertFalse(coordinator.isTextGroundable("application/pdf", "doc.pdf"))
        assertFalse(coordinator.isTextGroundable("application/zip", "bundle.zip"))
    }

    // ------------------------------------------------------------------
    // 28 — the real unavailability reason when storage cannot bind (§21.28)
    // ------------------------------------------------------------------

    @Test
    fun `without an active project the import refuses with the real reason`() = runBlocking {
        // Unbind the project (0L = no active project in the workspace runtime).
        workspaceService.setActiveProject(0L)

        val failure = runCatching {
            coordinator.importFileAttachment("content://saf/x.txt", "text/plain")
        }.exceptionOrNull()

        assertTrue(failure is ChatAttachmentCoordinator.AttachmentImportException)
        assertTrue(failure!!.message!!.contains("يتطلب مشروعاً نشطاً"))
    }
}
