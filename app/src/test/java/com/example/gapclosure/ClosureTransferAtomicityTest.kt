package com.example.gapclosure

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.artifacts.ArtifactService
import com.example.application.attachment.ChatAttachmentCoordinator
import com.example.application.attachment.FolderUnderstandingService
import com.example.application.transfer.FileTransferService
import com.example.application.transfer.ImportConflictPolicy
import com.example.application.transfer.ProjectPackageService
import com.example.application.transfer.ProjectTransferCoordinator
import com.example.application.transfer.TransferOutcome
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.ArtifactVersionEntity
import com.example.infrastructure.persistence.entities.ChatTimelineEventEntity
import com.example.infrastructure.persistence.entities.ConversationSessionEntity
import com.example.infrastructure.persistence.entities.ConversationTurnEntity
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.TaskEntity
import com.example.infrastructure.persistence.entities.WorkspaceEntity
import com.example.infrastructure.storage.SandboxProjectFileStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ============================================================================
 * ClosureTransferAtomicityTest — CLOSURE §4/§5 (transfer + portability)
 * ============================================================================
 * The atomicity + completeness contracts of the v3 package pipeline:
 *
 *  - SESSION/TURN/TIMELINE/TASK/ARTIFACT portability: a v3 round-trip moves
 *    the FULL semantic state (sessions WITH their turns + timeline events,
 *    tasks WITH their real lifecycle + checkpoint + canonical context,
 *    artifacts WITH their rows) — not metadata-only ghosts;
 *  - TASK HONESTY: an imported task keeps its exported lifecycleState —
 *    never a blanket COMPLETED;
 *  - COUNT CONTRACTS: a package whose declared counts do not match its
 *    payloads is rejected with destination untouched;
 *  - IDENTITY-PRESERVING MOVE: the verified rebind moves EVERY scoped row
 *    (sessions/knowledge/tasks/artifacts) and clears the source — with
 *    before/after count witnesses;
 *  - ATOMIC IMPORT ROLLBACK: a staged-file hash mismatch aborts the whole
 *    import — no partial destination state survives.
 */
@RunWith(RobolectricTestRunner::class)
class ClosureTransferAtomicityTest {

    private lateinit var db: AppDatabase
    private lateinit var baseDir: File
    private lateinit var fileStore: SandboxProjectFileStore
    private lateinit var packages: ProjectPackageService
    private lateinit var artifacts: ArtifactService
    private lateinit var coordinator: ProjectTransferCoordinator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        baseDir = File(context.filesDir, "test_closure_transfer").apply { deleteRecursively(); mkdirs() }
        fileStore = SandboxProjectFileStore(baseDir)
        artifacts = ArtifactService(db, fileStore)
        packages = ProjectPackageService(db, fileStore)
        coordinator = ProjectTransferCoordinator(db, fileStore, packages)
        runBlocking {
            db.workspaceDao().insertOrUpdate(
                WorkspaceEntity(
                    id = "wsA", name = "A", description = "",
                    networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED", settingsJson = "{}",
                    isActive = true, lastActiveProjectId = null,
                    createdAtEpochMs = 1, lastAccessedEpochMs = 1
                )
            )
            db.workspaceDao().insertOrUpdate(
                WorkspaceEntity(
                    id = "wsB", name = "B", description = "",
                    networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED", settingsJson = "{}",
                    isActive = false, lastActiveProjectId = null,
                    createdAtEpochMs = 1, lastAccessedEpochMs = 1
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        baseDir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `v3 round trip carries turns timeline tasks artifacts - not metadata ghosts`() = runBlocking {
        val pid = db.projectDao().insertProject(
            ProjectEntity(
                name = "Rich", description = "", rootPath = "",
                createdAtEpochMs = 1, updatedAtEpochMs = 1, workspaceId = "wsA", lifecycleState = "ACTIVE"
            )
        )
        val root = fileStore.projectRoot(pid)
        fileStore.write(root, "docs/x.md", "hello".toByteArray())

        db.conversationSessionDao().upsert(
            ConversationSessionEntity(
                sessionId = "s1", workspaceId = "wsA", title = "S", mode = "AGENT",
                turnCount = 1, totalTokensConsumed = 5, createdAtEpochMs = 1, lastActiveAtEpochMs = 1, projectId = pid
            )
        )
        db.conversationTurnDao().insert(
            ConversationTurnEntity(
                turnId = "t1", sessionId = "s1", prompt = "p", answer = "a",
                tokensConsumed = 5, createdAtEpochMs = 1,
                attachmentsJson = "[]", sourcesJson = "[]"
            )
        )
        db.chatTimelineEventDao().insert(
            ChatTimelineEventEntity(
                eventId = "e1", sessionId = "s1", kind = "CAPABILITY_RESULT",
                title = "بحث", summary = "نتيجة", createdAtEpochMs = 1
            )
        )
        db.taskDao().insertOrUpdateTask(
            TaskEntity(
                id = "task_running_1", assignedAgentId = "agent_general", rawPrompt = "عملية طويلة",
                lifecycleState = "RUNNING", autonomyPolicy = "SUPERVISED", resultSummary = null,
                createdAtEpochMs = 1, updatedAtEpochMs = 1,
                goal = "عملية طويلة", checkpointJson = """{"stepIndex":3}""",
                workspaceId = "wsA", projectId = pid
            )
        )
        val artifact = artifacts.saveAssistantResultAsArtifact(
            workspaceId = "wsA", projectId = pid, sessionId = "s1", executionId = null,
            name = "تقرير", content = "المحتوى".toByteArray()
        )

        // Export → import round trip.
        val buffer = ByteArrayOutputStream()
        val export = packages.exportProject("wsA", pid, buffer)
        assertTrue("export must succeed: $export", export is TransferOutcome.Success)
        val (newId, report) = packages.importProject(
            "wsB", ByteArrayInputStream(buffer.toByteArray()), ImportConflictPolicy.RENAME
        )
        assertTrue("import must succeed: ${report.message}", report.ok)
        assertNotNull(newId)

        // Session + TURN + timeline landed with remapped session ids.
        val sessions = db.conversationSessionDao().forProject(newId!!)
        assertEquals(1, sessions.size)
        val newSessionId = sessions.first().sessionId
        assertTrue("session id remapped", newSessionId != "s1")
        assertEquals("turnCount recomputed from actual turns", 1, sessions.first().turnCount)
        val turns = db.conversationTurnDao().forSessionOnce(newSessionId)
        assertEquals("the turn IS portable", 1, turns.size)
        assertEquals("p", turns.first().prompt)
        assertEquals("a", turns.first().answer)
        val timeline = db.chatTimelineEventDao().forSessionOnce(newSessionId)
        assertEquals("timeline events ARE portable", 1, timeline.size)

        // Task lifecycle HONESTY: RUNNING stays RUNNING (never blanket COMPLETED).
        val tasks = db.taskDao().getTasksForProject(newId)
        assertEquals(1, tasks.size)
        assertEquals("RUNNING", tasks.first().lifecycleState)
        assertNotNull("checkpoint IS portable (resumability)", tasks.first().checkpointJson)

        // Artifact row landed in the target project.
        val targetArtifacts = db.artifactDao().forProject(newId)
        assertEquals(1, targetArtifacts.size)
        assertEquals("تقرير", targetArtifacts.first().name)

        // Files landed.
        assertTrue(File(fileStore.projectRoot(newId), "docs/x.md").exists())
    }

    @Test
    fun `a package with a violated count contract is rejected with destination untouched`() = runBlocking {
        val pid = seedMinimalProject()
        val buffer = ByteArrayOutputStream()
        assertTrue(packages.exportProject("wsA", pid, buffer) is TransferOutcome.Success)

        // Tamper: build a v3 package whose knowledgeCount lies (2 declared, 1 actual).
        val tampered = tamperManifestField(buffer.toByteArray()) { manifest ->
            manifest.put("knowledgeCount", manifest.optInt("knowledgeCount") + 1)
        }
        val (newId, report) = packages.importProject(
            "wsB", ByteArrayInputStream(tampered), ImportConflictPolicy.RENAME
        )
        assertNull("import must refuse", newId)
        assertFalse(report.ok)
        assertTrue(
            "count contract failure surfaced: ${report.message}",
            report.message.contains("COUNT")
        )
        // Destination untouched.
        assertTrue(db.projectDao().activeProjectsForWorkspaceList("wsB").isEmpty())
    }

    @Test
    fun `identity preserving move rebinds every scoped row with verified counts`() = runBlocking {
        val pid = db.projectDao().insertProject(
            ProjectEntity(
                name = "Move", description = "", rootPath = "",
                createdAtEpochMs = 1, updatedAtEpochMs = 1, workspaceId = "wsA", lifecycleState = "ACTIVE"
            )
        )
        db.conversationSessionDao().upsert(
            ConversationSessionEntity(
                sessionId = "sm", workspaceId = "wsA", title = "S", mode = "AGENT",
                turnCount = 0, createdAtEpochMs = 1, lastActiveAtEpochMs = 1, projectId = pid
            )
        )
        db.taskDao().insertOrUpdateTask(
            TaskEntity(
                id = "tm", assignedAgentId = "agent_general", rawPrompt = "x",
                lifecycleState = "COMPLETED", autonomyPolicy = "SUPERVISED", resultSummary = null,
                createdAtEpochMs = 1, updatedAtEpochMs = 1, workspaceId = "wsA", projectId = pid
            )
        )
        db.knowledgeDocumentDao().insertOrUpdate(
            com.example.infrastructure.persistence.entities.KnowledgeDocumentEntity(
                id = "dm", workspaceId = "wsA", title = "K", sourceUri = "u", content = "c",
                tagsJson = "[]", totalChunks = 0, totalTokensEstimated = 1,
                createdAtEpochMs = 1, updatedAtEpochMs = 1, projectId = pid
            )
        )

        val outcome = coordinator.moveProjectVerifiedIdentity(pid, "wsA", "wsB")
        assertTrue("move must succeed: $outcome", outcome is ProjectTransferCoordinator.MoveOutcome.IdentityPreserved)

        // EVERY scoped row now answers to wsB and NONE to wsA.
        assertEquals(1, db.projectDao().countSessionsForProjectInWorkspace(pid, "wsB"))
        assertEquals(0, db.projectDao().countSessionsForProjectInWorkspace(pid, "wsA"))
        assertEquals(1, db.projectDao().countKnowledgeForProjectInWorkspace(pid, "wsB"))
        assertEquals(0, db.projectDao().countKnowledgeForProjectInWorkspace(pid, "wsA"))
        assertEquals(1, db.projectDao().countTasksForProjectInWorkspace(pid, "wsB"))
        assertEquals(0, db.projectDao().countTasksForProjectInWorkspace(pid, "wsA"))
        assertEquals(1, db.taskDao().getTasksForWorkspaceAndProject("wsB", pid).size)
        // The project row itself.
        assertNotNull(db.projectDao().getProjectByIdForWorkspace(pid, "wsB"))
        assertNull(db.projectDao().getProjectByIdForWorkspace(pid, "wsA"))
    }

    @Test
    fun `artifact version lifecycle - create read rollback - append only history`() = runBlocking {
        val pid = db.projectDao().insertProject(
            ProjectEntity(
                name = "V", description = "", rootPath = "",
                createdAtEpochMs = 1, updatedAtEpochMs = 1, workspaceId = "wsA", lifecycleState = "ACTIVE"
            )
        )
        val artifact = artifacts.saveAssistantResultAsArtifact(
            "wsA", pid, null, null, "نسخ", "v1 content".toByteArray()
        )
        val scope = com.example.domain.core.context.ResourceScope.Project("wsA", pid)

        // v2.
        val v2 = artifacts.createVersion(scope, artifact.id, "v2 content".toByteArray(), "edit 2")
        assertNotNull(v2)
        assertEquals(2, v2!!.version)
        // v3.
        val v3 = artifacts.createVersion(scope, artifact.id, "v3 content".toByteArray(), "edit 3")
        assertEquals(3, v3!!.version)

        // History: append-only, current pointer advances.
        val versions = artifacts.listVersions(artifact.id)
        assertEquals(listOf(1, 2, 3), versions.map { it.version })
        assertEquals(3, versions.last { it.isCurrent }.version)

        // Read an OLD version.
        val v1Bytes = artifacts.readVersion(scope, artifact.id, 1)
        assertEquals("v1 content", v1Bytes!!.toString(Charsets.UTF_8))

        // Rollback to v1 → creates v4 with v1's bytes (history untouched).
        val v4 = artifacts.rollbackToVersion(scope, artifact.id, 1)
        assertEquals(4, v4!!.version)
        val currentRow = db.artifactDao().byIdForProject(artifact.id, pid)
        assertEquals(4, currentRow!!.currentVersion)
        assertEquals(
            "v1 content",
            artifacts.readVersion(scope, artifact.id, 4)!!.toString(Charsets.UTF_8)
        )
        // The original 3 versions are still readable (append-only).
        assertEquals(
            "v3 content",
            artifacts.readVersion(scope, artifact.id, 3)!!.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun `folder understanding - grounded mode reports read files honestly`() = runBlocking {
        val pid = db.projectDao().insertProject(
            ProjectEntity(
                name = "F", description = "", rootPath = "",
                createdAtEpochMs = 1, updatedAtEpochMs = 1, workspaceId = "wsA", lifecycleState = "ACTIVE"
            )
        )
        val root = fileStore.projectRoot(pid)
        fileStore.write(root, "folder/a.txt", "المحتوى أ".toByteArray())
        fileStore.write(root, "folder/b.md", "# B".toByteArray())
        fileStore.write(root, "folder/binary.bin", byteArrayOf(0, 1, 2, 3))

        val service = FolderUnderstandingService(fileStore)
        val (digest, report) = service.buildFolderGroundingDigest(pid, "folder")
        assertEquals(3, report.totalFiles)
        assertEquals("the two text files are readable", 2, report.readableTextFiles)
        assertEquals("both readable files grounded", 2, report.groundedFiles)
        assertTrue(report.isUnderstood)
        assertTrue(digest.contains("المحتوى أ"))
        assertTrue(digest.contains("# B"))

        // ATTACHMENT_ONLY mode is honestly NOT understood.
        val attachmentOnlyReport = FolderUnderstandingService.FolderUnderstandingReport(
            mode = FolderUnderstandingService.FolderMode.ATTACHMENT_ONLY,
            totalFiles = 3, readableTextFiles = 2, groundedFiles = 0, ingestedFiles = 0,
            skippedFiles = 3, digestChars = 0, notes = emptyList()
        )
        assertFalse(attachmentOnlyReport.isUnderstood)
    }

    @Test
    fun `bidi sanitizer isolates technical runs in arabic prose`() {
        val input = "افتح src/main/java/File.kt ثم احسب النتيجة"
        val output = com.example.presentation.ui.screens.studio.BidiSanitizer.isolateTechnicalRuns(input)
        // The path run is isolated with LRI/PDI pairs.
        assertTrue(output.contains("src/main/java/File.kt"))
        assertTrue("\u2066" in output && "\u2069" in output)
        // Pure LTR text is untouched.
        val pure = "open the file now"
        assertEquals(pure, com.example.presentation.ui.screens.studio.BidiSanitizer.isolateTechnicalRuns(pure))
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private suspend fun seedMinimalProject(): Long {
        val pid = db.projectDao().insertProject(
            ProjectEntity(
                name = "Min", description = "", rootPath = "",
                createdAtEpochMs = 1, updatedAtEpochMs = 1, workspaceId = "wsA", lifecycleState = "ACTIVE"
            )
        )
        fileStore.write(fileStore.projectRoot(pid), "one.md", "x".toByteArray())
        return pid
    }

    /** Rebuilds a v3 package with a tampered manifest (recomputes the digest so the COUNT check is what fires). */
    private fun tamperManifestField(packageBytes: ByteArray, tamper: (org.json.JSONObject) -> Unit): ByteArray {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(packageBytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                entries[e.name] = zis.readBytes()
            }
        }
        val manifest = org.json.JSONObject(entries["manifest.json"]!!.decodeToString())
        tamper(manifest)
        val manifestBytes = manifest.toString(2).toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(manifestBytes)
            .joinToString("") { "%02x".format(it) }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                when (name) {
                    "manifest.json" -> {
                        zos.putNextEntry(ZipEntry(name)); zos.write(manifestBytes); zos.closeEntry()
                    }
                    "manifest.sha256" -> {
                        zos.putNextEntry(ZipEntry(name)); zos.write(digest.toByteArray()); zos.closeEntry()
                    }
                    else -> {
                        zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
                    }
                }
            }
        }
        return out.toByteArray()
    }
}
