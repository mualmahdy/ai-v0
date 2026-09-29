package com.example.convergence

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.application.artifacts.ArtifactService
import com.example.application.attachment.ChatAttachmentCoordinator
import com.example.application.rag.KnowledgePersistenceService
import com.example.application.transfer.FileTransferService
import com.example.application.transfer.ImportConflictPolicy
import com.example.application.transfer.ProjectPackageService
import com.example.application.transfer.TransferOutcome
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.execution.ScopeSnapshot
import com.example.domain.core.rag.DocumentChunk
import com.example.domain.core.rag.KnowledgeDocument
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.dao.DocumentChunkDao
import com.example.infrastructure.persistence.entities.ArtifactEntity
import com.example.infrastructure.persistence.entities.ChatTimelineEventEntity
import com.example.infrastructure.persistence.entities.ConversationSessionEntity
import com.example.infrastructure.persistence.entities.ConversationTurnEntity
import com.example.infrastructure.persistence.entities.IdMappingEntityType
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.TaskEntity
import com.example.infrastructure.persistence.entities.WorkspaceEntity
import com.example.infrastructure.storage.SandboxProjectFileStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.io.InputStream

/**
 * ============================================================================
 * TransferIdMappingTest — CLOSURE FINAL STAGE (§5/item 4 — B5 + B6)
 * ============================================================================
 *
 * The PROOF suite for the data-integrity half of the final closure stage:
 *
 *  B6 (central id mapping): an import's EVERY remapped id lands in the
 *      `id_mappings` ledger (inside the same Room transaction), and every
 *      cross-entity reference is REBOUND through it — artifact→session/task
 *      (previously artifact→session was ALWAYS dangling), task→parentTask,
 *      turn-attachment→artifact. References to never-traveling entities
 *      (approvals, execution logs) are dropped honestly and COUNTED.
 *
 *  B5 (RAG transaction boundary): a crash mid-persist (injected AFTER the
 *      chunk insert, INSIDE the transaction) leaves NO half-indexed document
 *      — the document row, the chunk wipe and the chunk inserts commit as
 *      ONE unit or not at all. The delete path gets the same boundary.
 *
 *  A3 (stream ownership): the chat attachment import CLOSES the
 *      ContentResolver stream on the SUCCESS path AND on the failure path
 *      (the read itself throwing) — previously every attachment leaked a
 *      file descriptor.
 */
@RunWith(RobolectricTestRunner::class)
class TransferIdMappingTest {

    private lateinit var db: AppDatabase
    private lateinit var baseDir: File
    private lateinit var fileStore: SandboxProjectFileStore
    private lateinit var packages: ProjectPackageService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        baseDir = File(context.filesDir, "test_id_mapping").apply { deleteRecursively(); mkdirs() }
        fileStore = SandboxProjectFileStore(baseDir)
        packages = ProjectPackageService(db, fileStore, null)
        runBlocking {
            db.workspaceDao().insertOrUpdate(
                WorkspaceEntity(
                    id = "ws1", name = "WS1", description = "",
                    networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED", settingsJson = "{}",
                    isActive = true, lastActiveProjectId = null,
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

    /**
     * Seeds one project whose graph carries every reference class the import
     * must rebind: session + turn (with an embedded attachment→artifact ref),
     * parent task + child task, artifact (→ session + task + execution), and
     * a timeline event (→ approval + execution).
     */
    private suspend fun seedFullGraph(): Long {
        val pid = db.projectDao().insertProject(
            ProjectEntity(
                name = "GraphSource", description = null, rootPath = "",
                createdAtEpochMs = 1, updatedAtEpochMs = 1,
                workspaceId = "ws1", lifecycleState = "ACTIVE"
            )
        )
        db.projectDao().updateProject(
            db.projectDao().getProjectById(pid)!!.copy(rootPath = fileStore.projectRoot(pid).canonicalPath)
        )
        val root = fileStore.projectRoot(pid)
        fileStore.write(root, "docs/readme.md", "# Hello".toByteArray())

        // Session + turn whose attachment references artifact artA.
        db.conversationSessionDao().upsert(
            ConversationSessionEntity(
                sessionId = "sessA", workspaceId = "ws1", title = "S1", mode = "AGENT",
                turnCount = 1, totalTokensConsumed = 5,
                createdAtEpochMs = 1, lastActiveAtEpochMs = 1, projectId = pid
            )
        )
        db.conversationTurnDao().insert(
            ConversationTurnEntity(
                turnId = "turnA", sessionId = "sessA", prompt = "سؤال", answer = "جواب",
                tokensConsumed = 5, durationMs = 10, isSuccessful = true, eventCount = 3,
                createdAtEpochMs = 1,
                attachmentsJson = JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "attm_1")
                            .put("name", "evidence.txt")
                            .put("mimeType", "text/plain")
                            .put("sizeBytes", 4)
                            .put("storageUri", "attachments/evidence.txt")
                            .put("provenance", "SAF_FILE")
                            .put("groundingState", "GROUNDED")
                            .put("artifactId", "artA")
                    )
                    .toString(),
                sourcesJson = "[]"
            )
        )
        // Parent + child task (child references parent).
        db.taskDao().insertOrUpdateTask(
            TaskEntity(
                id = "taskA", assignedAgentId = "agent_general", rawPrompt = "root task",
                lifecycleState = "COMPLETED", autonomyPolicy = "SUPERVISED",
                resultSummary = "done",
                createdAtEpochMs = 1, updatedAtEpochMs = 1,
                goal = "root", workspaceId = "ws1", projectId = pid
            )
        )
        db.taskDao().insertOrUpdateTask(
            TaskEntity(
                id = "taskB", assignedAgentId = "agent_general", rawPrompt = "child task",
                lifecycleState = "COMPLETED", autonomyPolicy = "SUPERVISED",
                resultSummary = "done",
                parentTaskId = "taskA", delegationDepth = 1,
                createdAtEpochMs = 1, updatedAtEpochMs = 1,
                goal = "child", workspaceId = "ws1", projectId = pid
            )
        )
        // Artifact referencing session + task + a never-traveling execution.
        db.artifactDao().upsert(
            ArtifactEntity(
                id = "artA", workspaceId = "ws1", projectId = pid,
                sessionId = "sessA", taskId = "taskB", executionId = "exec_x",
                type = "FILE", name = "evidence.txt", mimeType = "text/plain",
                sizeBytes = 4, storageUri = "attachments/evidence.txt",
                createdAtEpochMs = 1, updatedAtEpochMs = 1
            )
        )
        // Timeline event referencing never-traveling approval + execution.
        db.chatTimelineEventDao().insert(
            ChatTimelineEventEntity(
                eventId = "evtA", sessionId = "sessA", kind = "CAPABILITY_RESULT",
                title = "نتيجة", summary = "ملخص",
                sourcesJson = "[]", isSuccessful = true, isDegraded = false,
                createdAtEpochMs = 1,
                approvalId = "appr_x", executionId = "exec_x"
            )
        )
        return pid
    }

    // ------------------------------------------------------------------
    // B6 — central id mapping + reference rebinding
    // ------------------------------------------------------------------

    @Test
    fun `import rebinds every cross-entity reference through the central mapping ledger`() = runBlocking {
        val sourceId = seedFullGraph()
        val buffer = ByteArrayOutputStream()
        assertTrue(packages.exportProject("ws1", sourceId, buffer) is TransferOutcome.Success)

        val (newId, report) = packages.importProject(
            targetWorkspaceId = "ws1",
            packageStream = ByteArrayInputStream(buffer.toByteArray()),
            conflictPolicy = ImportConflictPolicy.RENAME
        )
        assertNotNull("import must succeed: ${report.message}", newId)
        assertTrue(report.ok)

        // ---- session ALWAYS gets a new id ----
        val newSessions = db.conversationSessionDao().forProject(newId!!)
        assertEquals(1, newSessions.size)
        val newSessionId = newSessions.single().sessionId
        assertNotEquals("session ids are unconditionally regenerated", "sessA", newSessionId)

        // ---- tasks: BOTH collide with the source rows still in this DB →
        // both are remapped; the child's parent reference follows the map ----
        val newTasks = db.taskDao().getTasksForProject(newId)
        assertEquals(2, newTasks.size)
        val newParent = newTasks.first { it.rawPrompt == "root task" }
        val newChild = newTasks.first { it.rawPrompt == "child task" }
        assertNotEquals("taskA", newParent.id)
        assertNotEquals("taskB", newChild.id)
        assertEquals(
            "task→parentTask reference is REBOUND through the central map",
            newParent.id,
            newChild.parentTaskId
        )

        // ---- artifact: session/task references REBOUND (the audit's
        // ALWAYS-dangling artifact→session reference), execution dropped ----
        val newArtifacts = db.artifactDao().forProject(newId, limit = 100)
        assertEquals(1, newArtifacts.size)
        val newArtifact = newArtifacts.single()
        assertNotEquals("artA", newArtifact.id)
        assertEquals(
            "artifact→session is rebound to the NEW session id (previously ALWAYS dangling)",
            newSessionId,
            newArtifact.sessionId
        )
        assertEquals(
            "artifact→task is rebound to the NEW task id",
            newChild.id,
            newArtifact.taskId
        )
        assertNull(
            "artifact→execution (never-traveling entity) is dropped honestly",
            newArtifact.executionId
        )

        // ---- turn: the EMBEDDED attachment artifactId follows the map ----
        val newTurns = db.conversationTurnDao().forSessionsOnce(listOf(newSessionId))
        assertEquals(1, newTurns.size)
        val attachments = JSONArray(newTurns.single().attachmentsJson)
        assertEquals(1, attachments.length())
        assertEquals(
            "the turn's embedded attachment artifactId is remapped to the NEW artifact id",
            newArtifact.id,
            attachments.getJSONObject(0).optString("artifactId")
        )
        assertEquals(
            "the attachment's display fields survive the rewrite",
            "evidence.txt",
            attachments.getJSONObject(0).optString("name")
        )

        // ---- timeline: never-traveling references dropped ----
        val newEvents = db.chatTimelineEventDao().forSessionsOnce(listOf(newSessionId))
        assertEquals(1, newEvents.size)
        assertNull("timeline→approval is dropped honestly", newEvents.single().approvalId)
        assertNull("timeline→execution is dropped honestly", newEvents.single().executionId)

        // ---- the central ledger itself ----
        val sessionMapping = db.idMappingDao().latestForSource(IdMappingEntityType.SESSION.name, "sessA")
        assertNotNull("the session remapping is recorded in id_mappings", sessionMapping)
        assertEquals(newSessionId, sessionMapping!!.targetId)
        assertFalse(sessionMapping.unchanged)

        val taskMapping = db.idMappingDao().latestForSource(IdMappingEntityType.TASK.name, "taskA")
        assertNotNull(taskMapping)
        assertEquals(newParent.id, taskMapping!!.targetId)

        val artifactMapping = db.idMappingDao().latestForTarget(IdMappingEntityType.ARTIFACT.name, newArtifact.id)
        assertNotNull(artifactMapping)
        assertEquals("artA", artifactMapping!!.sourceId)

        // ---- the report's honest witnesses ----
        // PROJECT + SESSION + TURN + EVENT(kept or remapped) + TASK×2 + ARTIFACT + (no knowledge in this package)
        assertTrue(
            "remappedIds must count every ledger row (actual=${report.remappedIds})",
            report.remappedIds >= 7
        )
        assertTrue(
            "reboundReferences must count artifact→session, artifact→task and the attachment remap (actual=${report.reboundReferences})",
            report.reboundReferences >= 3
        )
        assertTrue(
            "droppedReferences must count the never-traveling drops: artifact execution + timeline approval + timeline execution (actual=${report.droppedReferences})",
            report.droppedReferences >= 3
        )
    }

    @Test
    fun `remapAttachmentArtifactIds - traveled artifactId remapped, absent dropped, display fields kept`() {
        val service = ProjectPackageService(db, fileStore, null)
        val source = JSONArray()
            .put(
                JSONObject()
                    .put("id", "attm_1")
                    .put("name", "a.txt")
                    .put("storageUri", "attachments/a.txt")
                    .put("artifactId", "artA")
            )
            .put(
                JSONObject()
                    .put("id", "attm_2")
                    .put("name", "b.txt")
                    .put("storageUri", "attachments/b.txt")
                    .put("artifactId", "art_NOT_IN_PACKAGE")
            )
            .put(
                JSONObject()
                    .put("id", "attm_3")
                    .put("name", "c.txt")
                    .put("storageUri", "attachments/c.txt")
            )
            .toString()

        val result = service.remapAttachmentArtifactIds(source, mapOf("artA" to "art_NEW"))

        val out = JSONArray(result.json)
        assertEquals(3, out.length())
        assertEquals("art_NEW", out.getJSONObject(0).optString("artifactId"))
        assertEquals(
            "a dead link to a non-traveling artifact is dropped (absent key)",
            "",
            out.getJSONObject(1).optString("artifactId")
        )
        assertEquals("c.txt", out.getJSONObject(2).optString("name"))
        assertEquals(1, result.remapped)
        assertEquals(1, result.dropped)
    }

    @Test
    fun `remapAttachmentArtifactIds - malformed payload passes through unchanged`() {
        val service = ProjectPackageService(db, fileStore, null)
        val malformed = "not-json-at-all"
        val result = service.remapAttachmentArtifactIds(malformed, mapOf("a" to "b"))
        assertEquals(malformed, result.json)
        assertEquals(0, result.remapped)
        assertEquals(0, result.dropped)
    }

    // ------------------------------------------------------------------
    // B5 — the RAG transaction boundary
    // ------------------------------------------------------------------

    /** Delegating chunk DAO whose insertAll succeeds THEN throws (crash AFTER the writes, INSIDE the tx). */
    private class PostInsertThrowingChunkDao(
        private val inner: DocumentChunkDao
    ) : DocumentChunkDao by inner {
        var thrown = false
        override suspend fun insertAll(chunks: List<com.example.infrastructure.persistence.entities.DocumentChunkEntity>) {
            inner.insertAll(chunks)
            thrown = true
            throw IllegalStateException("CRASH_MID_TRANSACTION")
        }
    }

    private fun document(id: String) = KnowledgeDocument(
        id = id, title = "T", sourceUri = "u", mimeType = "text/markdown",
        content = "content", tags = emptyList(), totalChunks = 2,
        createdAtTimestampMs = 1, projectId = null
    )

    private fun chunk(docId: String, index: Int) = DocumentChunk(
        id = "${docId}_c$index", documentId = docId, documentTitle = "T",
        chunkIndex = index, text = "chunk $index", vector = null,
        tokenCount = 1, metadata = emptyMap()
    )

    @Test
    fun `a crash mid-persist leaves NO half-indexed document - the three writes are ONE transaction`() = runBlocking {
        val throwingDao = PostInsertThrowingChunkDao(db.documentChunkDao())
        val service = KnowledgePersistenceService(
            documentDao = db.knowledgeDocumentDao(),
            chunkDao = throwingDao,
            transactionRunner = { block -> db.withTransaction { block() } }
        )

        val failure = runCatching {
            service.persistDocument("ws1", document("docB"), listOf(chunk("docB", 0), chunk("docB", 1)))
        }.exceptionOrNull()

        assertNotNull("the injected crash propagates", failure)
        assertTrue(throwingDao.thrown)
        // THE BOUNDARY: the document row was written FIRST inside the tx, the
        // crash came AFTER the chunk insert — with three independent writes
        // the committed document row would claim totalChunks=2 with zero
        // chunk rows (a half-indexed document). The transaction rolls ALL of
        // it back.
        assertEquals(
            "the document row must NOT survive a mid-transaction crash",
            0,
            db.knowledgeDocumentDao().getDocumentsForWorkspace("ws1").size
        )
        assertEquals(
            "the chunk rows must NOT survive a mid-transaction crash",
            0,
            db.documentChunkDao().getChunksForDocument("docB").size
        )
    }

    @Test
    fun `persistDocument happy path lands the document and all its chunks atomically`() = runBlocking {
        val service = KnowledgePersistenceService(
            documentDao = db.knowledgeDocumentDao(),
            chunkDao = db.documentChunkDao(),
            transactionRunner = { block -> db.withTransaction { block() } }
        )
        service.persistDocument("ws1", document("docH"), listOf(chunk("docH", 0), chunk("docH", 1)))

        val docs = db.knowledgeDocumentDao().getDocumentsForWorkspace("ws1")
        assertEquals(1, docs.size)
        assertEquals("docH", docs.single().id)
        assertEquals(2, docs.single().totalChunks)
        assertEquals(2, db.documentChunkDao().getChunksForDocument("docH").size)
    }

    // ------------------------------------------------------------------
    // A3 — explicit stream ownership on the chat attachment import
    // ------------------------------------------------------------------

    /** An InputStream that records its close() (the FD-leak witness). */
    private class RecordingInputStream(private val delegate: ByteArrayInputStream) : InputStream() {
        @Volatile var closed = false
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() {
            closed = true
            delegate.close()
        }
    }

    /** A stream that always fails on read (the failure-path witness). */
    private class ThrowingReadStream : InputStream() {
        @Volatile var closed = false
        override fun read(): Int = throw java.io.IOException("SAF pipe broke")
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `the chat attachment import closes the source stream on the success path`() = runBlocking {
        val artifactService = ArtifactService(db, fileStore, null)
        val transferService = FileTransferService(fileStore)
        val recorded = RecordingInputStream(ByteArrayInputStream("hello".toByteArray()))
        val port = object : FileTransferService.ContentPort {
            override fun openRead(uri: String): InputStream? = recorded
            override fun openWrite(uri: String): java.io.OutputStream? = null
            override fun queryDisplayName(uri: String): String? = "hello.txt"
            override fun querySize(uri: String): Long? = 5L
        }
        val workspaceService = WorkspaceRuntimeService(
            workspaceDao = com.example.presentation.viewmodel.FakeWorkspaceDaoForVm(),
            projectDao = com.example.presentation.viewmodel.FakeProjectDaoForVm(),
            coroutineScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined
            )
        )
        val coordinator = ChatAttachmentCoordinator(
            fileTransferService = transferService,
            artifactService = artifactService,
            workspaceRuntimeService = workspaceService,
            fileStore = fileStore,
            contentPort = port
        )

        val attachment = coordinator.importFileAttachment(
            uri = "content://fake/hello.txt",
            reportedMimeType = "text/plain",
            scope = ScopeSnapshot.capture(
                operationId = "attm_op_1",
                workspaceId = "ws1",
                projectId = 1L
            )
        )

        assertEquals("hello.txt", attachment.name)
        assertNotNull("the attachment references the registered artifact", attachment.artifactId)
        assertTrue(
            "A3: the ContentResolver stream is CLOSED after the successful import (previously leaked)",
            recorded.closed
        )
    }

    @Test
    fun `the chat attachment import closes the source stream on the failure path`() = runBlocking {
        val artifactService = ArtifactService(db, fileStore, null)
        val transferService = FileTransferService(fileStore)
        val failing = ThrowingReadStream()
        val port = object : FileTransferService.ContentPort {
            override fun openRead(uri: String): InputStream? = failing
            override fun openWrite(uri: String): java.io.OutputStream? = null
            override fun queryDisplayName(uri: String): String? = "broken.txt"
            override fun querySize(uri: String): Long? = 5L
        }
        val workspaceService = WorkspaceRuntimeService(
            workspaceDao = com.example.presentation.viewmodel.FakeWorkspaceDaoForVm(),
            projectDao = com.example.presentation.viewmodel.FakeProjectDaoForVm(),
            coroutineScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined
            )
        )
        val coordinator = ChatAttachmentCoordinator(
            fileTransferService = transferService,
            artifactService = artifactService,
            workspaceRuntimeService = workspaceService,
            fileStore = fileStore,
            contentPort = port
        )

        val failure = runCatching {
            coordinator.importFileAttachment(
                uri = "content://fake/broken.txt",
                reportedMimeType = "text/plain",
                scope = ScopeSnapshot.capture(
                    operationId = "attm_op_2",
                    workspaceId = "ws1",
                    projectId = 1L
                )
            )
        }.exceptionOrNull()

        assertNotNull("the broken read surfaces honestly", failure)
        assertTrue(
            "A3: the stream is CLOSED even when the import fails (the early-failure branches previously leaked it)",
            failing.closed
        )
    }
}
