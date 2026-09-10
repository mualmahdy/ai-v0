package com.example.convergence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.audit.AuditTrailService
import com.example.application.transfer.FileTransferService
import com.example.application.transfer.ImportConflictPolicy
import com.example.application.transfer.ProjectPackageService
import com.example.application.transfer.TransferOutcome
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.ProjectEntity
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
import java.util.zip.ZipOutputStream

/**
 * ============================================================================
 * REPAIR ORDER §34 — IMPORT/EXPORT TESTS
 * ============================================================================
 * Project packages (.aiv0project), file/folder transfers, and their safety:
 *   - export → import round-trip with NEW identity (ID remapping, no reuse);
 *   - duplicate-name conflicts (ASK surfaces; RENAME de-conflicts);
 *   - malformed / corrupted packages are rejected (destination untouched);
 *   - path traversal entries are rejected BEFORE extraction;
 *   - secret-exclusion declaration is REQUIRED (a package without it is
 *     refused);
 *   - clone keeps the source intact;
 *   - move is verified before the source is removed;
 *   - Zip-Slip-protected folder imports with staging + atomic promotion.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectPackageTransferTest {

    private lateinit var db: AppDatabase
    private lateinit var baseDir: File
    private lateinit var fileStore: SandboxProjectFileStore
    private lateinit var packages: ProjectPackageService
    private lateinit var transfer: FileTransferService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        baseDir = File(context.filesDir, "test_transfer").apply { deleteRecursively(); mkdirs() }
        fileStore = SandboxProjectFileStore(baseDir)
        packages = ProjectPackageService(db, fileStore, AuditTrailService(db))
        transfer = FileTransferService(fileStore)
        // Seed workspace + project with files + knowledge.
        runBlocking {
            db.workspaceDao().insertOrUpdate(
                WorkspaceEntity(
                    id = "ws1", name = "WS1", description = "",
                    networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED", settingsJson = "{}",
                    isActive = true, lastActiveProjectId = null,
                    createdAtEpochMs = 1, lastAccessedEpochMs = 1
                )
            )
            val pid = db.projectDao().insertProject(
                ProjectEntity(
                    name = "Source", description = "src", rootPath = "",
                    createdAtEpochMs = 1, updatedAtEpochMs = 1,
                    workspaceId = "ws1", lifecycleState = "ACTIVE"
                )
            )
            db.projectDao().updateProject(
                db.projectDao().getProjectById(pid)!!.copy(rootPath = fileStore.projectRoot(pid).canonicalPath)
            )
            db.workspaceDao().setActiveProject("ws1", pid, System.currentTimeMillis())
            val root = fileStore.projectRoot(pid)
            fileStore.write(root, "docs/readme.md", "# Hello".toByteArray())
            fileStore.write(root, "code/main.kt", "fun main(){}".toByteArray())
            db.knowledgeDocumentDao().insertOrUpdate(
                com.example.infrastructure.persistence.entities.KnowledgeDocumentEntity(
                    id = "doc1", workspaceId = "ws1", title = "K1", sourceUri = "x",
                    content = "knowledge body", tagsJson = "[]", totalChunks = 1,
                    totalTokensEstimated = 10, createdAtEpochMs = 1, updatedAtEpochMs = 1,
                    projectId = pid
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        baseDir.deleteRecursively()
    }

    private suspend fun sourceProjectId(): Long = db.workspaceDao().getWorkspaceById("ws1")!!.lastActiveProjectId!!

    // ------------------------------------------------------------------
    // Round-trip
    // ------------------------------------------------------------------

    @Test
    fun `export-import round trip creates a NEW project identity with files and knowledge`() = runBlocking {
        val sourceId = sourceProjectId()
        val buffer = ByteArrayOutputStream()
        val exportResult = packages.exportProject("ws1", sourceId, buffer)
        assertTrue("export must succeed: $exportResult", exportResult is TransferOutcome.Success)

        val (newId, report) = packages.importProject(
            targetWorkspaceId = "ws1",
            packageStream = ByteArrayInputStream(buffer.toByteArray()),
            conflictPolicy = ImportConflictPolicy.RENAME
        )
        assertTrue("import must succeed: ${report.message}", report.ok)
        assertNotNull(newId)
        assertTrue(newId!! > 0)
        // NEW identity — never the same id.
        assertTrue(newId != sourceId)
        // Files landed in the new project's sandbox.
        val newRoot = fileStore.projectRoot(newId)
        assertTrue(File(newRoot, "docs/readme.md").exists())
        assertTrue(File(newRoot, "code/main.kt").exists())
        // Knowledge was remapped to the NEW project (id remapping, no reuse).
        val docs = db.knowledgeDocumentDao().getProjectPrivateDocuments(newId)
        assertEquals(1, docs.size)
        assertTrue(docs.first().id != "doc1")
        // Source is intact (import ≠ move).
        assertTrue(File(fileStore.projectRoot(sourceId), "docs/readme.md").exists())
    }

    @Test
    fun `duplicate project names - ASK policy surfaces the conflict without mutating`() = runBlocking {
        val sourceId = sourceProjectId()
        val buffer = ByteArrayOutputStream()
        packages.exportProject("ws1", sourceId, buffer)
        val (newId, report) = packages.importProject(
            "ws1", ByteArrayInputStream(buffer.toByteArray()), ImportConflictPolicy.ASK
        )
        assertNull("ASK must refuse on name conflict", newId)
        assertNotNull(report.conflictName)
        assertTrue(report.message.contains("CONFLICT"))
    }

    @Test
    fun `duplicate project names - RENAME policy de-conflicts automatically`() = runBlocking {
        val sourceId = sourceProjectId()
        val buffer = ByteArrayOutputStream()
        packages.exportProject("ws1", sourceId, buffer)
        val (newId, report) = packages.importProject(
            "ws1", ByteArrayInputStream(buffer.toByteArray()), ImportConflictPolicy.RENAME
        )
        assertTrue(report.ok)
        assertNotNull(newId)
        val newProject = db.projectDao().getProjectById(newId!!)!!
        assertTrue(newProject.name.contains("Source"))
        assertFalse(newProject.name == "Source") // suffixed
    }

    // ------------------------------------------------------------------
    // Safety
    // ------------------------------------------------------------------

    private fun packageWithoutManifest(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("random.txt")); zos.write("nope".toByteArray()); zos.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `malformed package (no manifest) is rejected with destination untouched`() = runBlocking {
        val before = db.projectDao().activeProjectsForWorkspaceList("ws1").size
        val (newId, report) = packages.importProject(
            "ws1", ByteArrayInputStream(packageWithoutManifest()), ImportConflictPolicy.RENAME
        )
        assertNull(newId)
        assertTrue(report.message.contains("MISSING_MANIFEST"))
        assertEquals(before, db.projectDao().activeProjectsForWorkspaceList("ws1").size)
    }

    @Test
    fun `path traversal entry in package files is rejected before extraction`() = runBlocking {
        // Build a valid manifest + a nested files.zip with an EVIL entry name.
        val out = ByteArrayOutputStream()
        val filesZip = ByteArrayOutputStream()
        ZipOutputStream(filesZip).use { inner ->
            inner.putNextEntry(ZipEntry("../../evil.sh")); inner.write("evil".toByteArray()); inner.closeEntry()
        }
        val manifest = org.json.JSONObject()
            .put("packageSchemaVersion", 1)
            .put("appId", com.example.application.transfer.ProjectPackageService.APP_ID)
            .put("packageId", "pkg_evil")
            .put("projectName", "Evil")
            .put("sourceWorkspaceId", "ws1")
            .put("exportedAtEpochMs", 1L)
            .put("secretsExcluded", true)
            .put("contentHashes", org.json.JSONObject())
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json")); zos.write(manifest.toString().toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("project.json"))
            zos.write(org.json.JSONObject().put("name", "Evil").toString().toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("files.zip")); zos.write(filesZip.toByteArray()); zos.closeEntry()
        }
        val (newId, report) = packages.importProject(
            "ws1", ByteArrayInputStream(out.toByteArray()), ImportConflictPolicy.RENAME
        )
        assertNull(newId)
        assertTrue(report.message.contains("PATH_TRAVERSAL"))
        // Nothing escaped the sandbox root.
        assertFalse(File(baseDir, "evil.sh").exists())
        assertFalse(File(baseDir.parentFile, "evil.sh").exists())
    }

    @Test
    fun `package without secret-exclusion declaration is refused`() = runBlocking {
        val out = ByteArrayOutputStream()
        val manifest = org.json.JSONObject()
            .put("packageSchemaVersion", 1)
            .put("appId", com.example.application.transfer.ProjectPackageService.APP_ID)
            .put("secretsExcluded", false) // NOT declared
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json")); zos.write(manifest.toString().toByteArray()); zos.closeEntry()
        }
        val (newId, report) = packages.importProject(
            "ws1", ByteArrayInputStream(out.toByteArray()), ImportConflictPolicy.RENAME
        )
        assertNull(newId)
        assertTrue(report.message.contains("SECRET_INCLUSION"))
    }

    @Test
    fun `corrupted stream (not a zip) is rejected honestly`() = runBlocking {
        val (newId, report) = packages.importProject(
            "ws1", ByteArrayInputStream("this is not a zip".toByteArray()), ImportConflictPolicy.RENAME
        )
        assertNull(newId)
        assertFalse(report.ok)
    }

    // ------------------------------------------------------------------
    // Clone / move
    // ------------------------------------------------------------------

    @Test
    fun `clone keeps the source project intact`() = runBlocking {
        val sourceId = sourceProjectId()
        val (cloneId, outcome) = packages.cloneProject("ws1", sourceId, "ws1", "Cloned Project")
        assertTrue("clone must succeed: $outcome", cloneId != null && outcome is TransferOutcome.Success)
        // Source still ACTIVE with its files.
        val source = db.projectDao().getProjectById(sourceId)!!
        assertEquals("ACTIVE", source.effectiveLifecycleState)
        assertTrue(File(fileStore.projectRoot(sourceId), "docs/readme.md").exists())
        // Clone exists with copied files.
        assertTrue(File(fileStore.projectRoot(cloneId!!), "docs/readme.md").exists())
    }

    @Test
    fun `move to another workspace verifies then removes the source`() = runBlocking {
        db.workspaceDao().insertOrUpdate(
            WorkspaceEntity(
                id = "ws2", name = "WS2", description = "",
                networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED", settingsJson = "{}",
                isActive = false, lastActiveProjectId = null,
                createdAtEpochMs = 1, lastAccessedEpochMs = 1
            )
        )
        val sourceId = sourceProjectId()
        val (movedId, outcome) = packages.moveProjectVerified("ws1", sourceId, "ws2")
        assertTrue("move must succeed: $outcome", movedId != null && outcome is TransferOutcome.Success)
        // The project row is now owned by ws2.
        val moved = db.projectDao().getProjectById(movedId!!)!!
        assertEquals("ws2", moved.workspaceId)
        // The SOURCE identity no longer exists in ws1.
        assertNull(db.projectDao().getProjectByIdForWorkspace(sourceId, "ws1"))
        // Files landed in the new root.
        assertTrue(File(fileStore.projectRoot(movedId), "docs/readme.md").exists())
    }

    // ------------------------------------------------------------------
    // File transfer safety (Zip Slip + staging + hash)
    // ------------------------------------------------------------------

    @Test
    fun `folder import with zip-slip entry is rejected`() = runBlocking {
        val pid = sourceProjectId()
        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zos ->
            zos.putNextEntry(ZipEntry("good.txt")); zos.write("ok".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("../escape.txt")); zos.write("evil".toByteArray()); zos.closeEntry()
        }
        val result = transfer.importFolderZip("ws1", pid, ByteArrayInputStream(zipOut.toByteArray()))
        assertTrue(result is TransferOutcome.Failure)
        assertTrue((result as TransferOutcome.Failure).code == "PATH_TRAVERSAL_DETECTED")
        assertFalse(File(baseDir, "escape.txt").exists())
    }

    @Test
    fun `file import with hash mismatch is rejected as corrupted transfer`() = runBlocking {
        val pid = sourceProjectId()
        val result = transfer.importFile(
            workspaceId = "ws1", projectId = pid,
            source = ByteArrayInputStream("content".toByteArray()),
            relativePath = "hashed.txt",
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
        )
        assertTrue(result is TransferOutcome.Failure)
        assertTrue((result as TransferOutcome.Failure).code == "HASH_MISMATCH")
        // Destination untouched.
        assertFalse(File(fileStore.projectRoot(pid), "hashed.txt").exists())
    }

    @Test
    fun `file import with matching hash succeeds atomically`() = runBlocking {
        val pid = sourceProjectId()
        val content = "hello world".toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it) }
        val result = transfer.importFile(
            "ws1", pid, ByteArrayInputStream(content), "hashed_ok.txt", sha
        )
        assertTrue(result is TransferOutcome.Success)
        assertTrue(File(fileStore.projectRoot(pid), "hashed_ok.txt").exists())
    }

    @Test
    fun `sandbox file store rejects path traversal on every operation`() {
        val pid = runBlocking { sourceProjectId() }
        val root = fileStore.projectRoot(pid)
        for (evil in listOf("../evil", "/absolute", "a/../../b", "a\\b")) {
            var rejected = false
            try { fileStore.resolveContained(root, evil) } catch (e: SecurityException) { rejected = true }
            assertTrue("path '$evil' must be rejected", rejected)
        }
    }
}
