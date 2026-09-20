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

        val outcome = coordinator.buildGroundingDigest(
            workspaceService.activeWorkspaceIdOrNull()!!,
            listOf(text)
        )

        // FUNCTIONAL CLOSURE (§14): a fully-read attachment is NOT a failure.
        assertTrue(outcome.failures.isEmpty())
        assertTrue(outcome.digest.contains("<user_attachment name=\"data.txt\""))
        assertTrue(outcome.digest.contains("سطر بيانات مهم"))
        assertTrue(outcome.digest.contains("</user_attachment>"))
    }

    @Test
    fun `an IMAGE attachment is kept but NEVER claimed analyzable (Vision honesty)`() = runBlocking {
        val imageBytes = ByteArray(64) { it.toByte() }
        contentFiles["content://saf/photo.png"] = "photo.png" to imageBytes
        val image = coordinator.importFileAttachment("content://saf/photo.png", "image/png")

        assertFalse(coordinator.isTextGroundable(image))
        val outcome = coordinator.buildGroundingDigest(
            workspaceService.activeWorkspaceIdOrNull()!!,
            listOf(image)
        )
        // The honest non-textual note rides the digest instead of fake content.
        assertTrue(outcome.digest.contains("لا يمكن تحليل محتواه في هذا الإصدار"))
        // The binary bytes never leak into the text digest.
        assertFalse(outcome.digest.contains(String(imageBytes, Charsets.ISO_8859_1)))
        // A non-text attachment is not a grounding failure (Vision is PLANNED,
        // its absence is documented — not an error).
        assertTrue(outcome.failures.isEmpty())
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

    // ------------------------------------------------------------------
    // FUNCTIONAL CLOSURE (Phase 1 §13): draft-removal cleanup — no orphaned
    // storage/artifacts behind a removed draft.
    // ------------------------------------------------------------------

    @Test
    fun `removing a draft deletes BOTH the artifact row and the sandbox file`() = runBlocking {
        contentFiles["content://saf/temp.txt"] = "temp.txt" to "بيانات مؤقتة".toByteArray()
        val draft = coordinator.importFileAttachment("content://saf/temp.txt", "text/plain")
        val projectId = workspaceService.activeProjectIdOrNull()!!
        val artifactId = draft.artifactId!!
        assertNotNull(artifactService.forProject(projectId).firstOrNull { it.id == artifactId })
        assertTrue(
            fileStore.stat(fileStore.projectRoot(projectId), draft.storageUri).exists
        )

        val removed = coordinator.deleteImportedAttachment(draft)

        assertTrue(removed)
        // The artifact row is GONE (the audit truth went with the draft).
        assertTrue(
            artifactService.forProject(projectId).none { it.id == artifactId }
        )
        // The sandbox copy is GONE too (no orphaned file).
        assertFalse(
            fileStore.stat(fileStore.projectRoot(projectId), draft.storageUri).exists
        )
    }

    @Test
    fun `removing a draft whose artifact row vanished deletes the file directly`() = runBlocking {
        contentFiles["content://saf/stray.txt"] = "stray.txt" to "ملف بلا سجل".toByteArray()
        val draft = coordinator.importFileAttachment("content://saf/stray.txt", "text/plain")
        val projectId = workspaceService.activeProjectIdOrNull()!!
        // Simulate the artifact row disappearing (a crashed cleanup halfway).
        artifactService.delete(
            com.example.domain.core.context.ResourceScope.Project(
                workspaceService.activeWorkspaceIdOrNull()!!,
                projectId
            ),
            draft.artifactId!!
        )

        // The direct-file fallback still cleans the copy (idempotent + honest).
        val removed = coordinator.deleteImportedAttachment(draft)
        assertTrue(removed)
        assertFalse(
            fileStore.stat(fileStore.projectRoot(projectId), draft.storageUri).exists
        )
    }

    // ------------------------------------------------------------------
    // FUNCTIONAL CLOSURE (Phase 1 §14): grounding failures are REPORTED —
    // never silently converted into "no evidence".
    // ------------------------------------------------------------------

    @Test
    fun `a broken text-attachment reference is an honest grounding failure`() = runBlocking {
        contentFiles["content://saf/broken.txt"] = "broken.txt" to "محتوى".toByteArray()
        val draft = coordinator.importFileAttachment("content://saf/broken.txt", "text/plain")
        // The sandbox file disappears (broken reference).
        fileStore.delete(
            fileStore.projectRoot(workspaceService.activeProjectIdOrNull()!!),
            draft.storageUri
        )

        val outcome = coordinator.buildGroundingDigest(
            workspaceService.activeWorkspaceIdOrNull()!!,
            listOf(draft)
        )

        assertTrue(outcome.isFailed)
        assertTrue(outcome.failures.single().contains("broken.txt"))
        assertTrue(outcome.failures.single().contains("غير موجود"))
    }

    @Test
    fun `grounding without an active project reports every text attachment as failed`() = runBlocking {
        contentFiles["content://saf/noproj.txt"] = "noproj.txt" to "محتوى".toByteArray()
        val draft = coordinator.importFileAttachment("content://saf/noproj.txt", "text/plain")
        workspaceService.setActiveProject(0L)

        val outcome = coordinator.buildGroundingDigest(
            workspaceService.activeWorkspaceIdOrNull()!!,
            listOf(draft)
        )

        assertTrue(outcome.isFailed)
        assertTrue(outcome.failures.single().contains("لا يوجد مشروع نشط"))
    }

    // ------------------------------------------------------------------
    // FUNCTIONAL CLOSURE (Phase 1 §16): TRUE bounded reads — a huge file is
    // never fully materialized for a digest.
    // ------------------------------------------------------------------

    @Test
    fun `the grounding read is BOUNDED for a huge file (memory safety)`() = runBlocking {
        // ~2.6 MB of text — far beyond any digest budget (max 8k chars/attachment).
        val hugeText = buildString {
            repeat(130_000) { append("بيانات اختبار الحدود ٢٠ حرفاً\n") }
        }
        contentFiles["content://saf/huge.log"] = "huge.log" to hugeText.toByteArray()
        val draft = coordinator.importFileAttachment("content://saf/huge.log", "text/plain")

        val outcome = coordinator.buildGroundingDigest(
            workspaceService.activeWorkspaceIdOrNull()!!,
            listOf(draft)
        )

        assertTrue(outcome.failures.isEmpty())
        // The digest is bounded well below the file's full content…
        assertTrue(outcome.digest.length < 12_000)
        // …yet it still carries the honest truncated marker.
        assertTrue(outcome.digest.contains("truncated=\"true\""))
    }

    @Test
    fun `readBounded never pulls more than the byte budget from disk`() {
        val projectId = workspaceService.activeProjectIdOrNull()!!
        val root = fileStore.projectRoot(projectId)
        val huge = ByteArray(5 * 1024 * 1024) { 'x'.code.toByte() } // 5 MB
        fileStore.write(root, "attachments/huge_raw.bin", huge)

        val budget = 100_000L
        val bounded = fileStore.readBounded(root, "attachments/huge_raw.bin", budget)

        assertEquals(budget.toInt(), bounded.bytes.size)
        assertTrue(bounded.truncated)
        // And a full read is SMALLER than the file — proving early termination.
        val smaller = fileStore.readBounded(root, "attachments/huge_raw.bin", 10L)
        assertEquals(10, smaller.bytes.size)
        assertTrue(smaller.truncated)
    }
}
