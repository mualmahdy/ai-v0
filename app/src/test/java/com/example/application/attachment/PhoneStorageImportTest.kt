package com.example.application.attachment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.artifacts.ArtifactService
import com.example.application.transfer.FileTransferService
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.infrastructure.content.AndroidContentPort
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.storage.SandboxProjectFileStore
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * PhoneStorageImportTest — STAGE 7 user round 3 (the DocumentsUI-less
 * device's in-app storage browser, over the REAL content port)
 * ============================================================================
 *
 * The browser hands back `file://` URIs; these pins prove that the SAME
 * governed pipelines the SAF pickers feed work verbatim for that source —
 * through the REAL [AndroidContentPort] (no fake content port here):
 *
 *  - workspace import: file:// sources land under uploads/ with correct
 *    display names, through the real staging + atomic-promotion path;
 *  - folder attach: a plain phone DIRECTORY becomes a folder attachment
 *    through the production zipper routing (file:// → the directory→ZIP
 *    bridge), with the honest PHONE_STORAGE_DIR_ZIP provenance;
 *  - the directory bridge's entry/byte budgets surface as the honest
 *    null (never a truncated partial ZIP) — the test seam lets the pins
 *    run with production-shaped but tiny caps;
 *  - a missing file fails honestly per-file (never aborts the batch).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhoneStorageImportTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var fileStore: SandboxProjectFileStore
    private lateinit var workspaceService: WorkspaceRuntimeService
    private lateinit var artifactService: ArtifactService
    private lateinit var realPort: AndroidContentPort
    private lateinit var fileTransferService: FileTransferService
    private lateinit var coordinator: ChatAttachmentCoordinator
    private var serviceScope: CoroutineScope? = null

    /** Real on-disk phone files (Robolectric's environment). */
    private lateinit var phoneRoot: File

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val sandboxDir = File(context.getDir("test_phone_imports", Context.MODE_PRIVATE), "projects")
        fileStore = SandboxProjectFileStore(sandboxDir)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { serviceScope = it }
        workspaceService = WorkspaceRuntimeService(
            workspaceDao = com.example.presentation.viewmodel.FakeWorkspaceDaoForVm(),
            projectDao = com.example.presentation.viewmodel.FakeProjectDaoForVm(),
            coroutineScope = scope
        )
        Thread.sleep(100) // bootstrap settle
        workspaceService.createWorkspace("مساحة الهاتف", "اختبار الاستيراد من التخزين")
        artifactService = ArtifactService(database = database, fileStore = fileStore)
        realPort = AndroidContentPort(context)
        fileTransferService = FileTransferService(fileStore)
        coordinator = ChatAttachmentCoordinator(
            fileTransferService = fileTransferService,
            artifactService = artifactService,
            workspaceRuntimeService = workspaceService,
            fileStore = fileStore,
            contentPort = realPort,
            // THE PRODUCTION ROUTING (AppContainer): file:// goes to the
            // directory bridge, everything else to the SAF tree bridge.
            folderZipSource = { uri ->
                if (uri.startsWith("file:")) {
                    realPort.openDirectoryAsZipStream(uri)
                } else {
                    realPort.openTreeAsZipStream(uri)
                }
            }
        )
        phoneRoot = File(context.getDir("phone_storage", Context.MODE_PRIVATE), "sdcard").apply {
            mkdirs()
        }
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

    private fun phoneFile(relativePath: String, content: String): File {
        val file = File(phoneRoot, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun `workspace import from file uris lands under uploads with real display names`() = runBlocking {
        val report = phoneFile("Download/التقرير.pdf", "PDF-BYTES")
        val notes = phoneFile("Documents/notes.txt", "نص الملاحظات")

        val results = coordinator.importWorkspaceFiles(
            listOf(
                android.net.Uri.fromFile(report).toString(),
                android.net.Uri.fromFile(notes).toString()
            )
        )

        assertEquals(2, results.size)
        results.forEach { result ->
            assertTrue("import must succeed: ${result.errorMessage}", result.isSuccess)
            assertTrue(
                "the destination must be the uploads/ namespace, got ${result.importedRelativePath}",
                result.importedRelativePath!!.startsWith("uploads/")
            )
        }
        // The REAL port resolved honest display names for file:// sources
        // (the fallback of last resort — the file name itself).
        assertTrue(results.any { it.displayName == "التقرير.pdf" })
        assertTrue(results.any { it.displayName == "notes.txt" })

        // The real files landed in the ACTIVE project's sandbox.
        val projectId = workspaceService.activeProjectIdOrNull()!!
        results.forEach { result ->
            assertTrue(
                fileStore.stat(fileStore.projectRoot(projectId), result.importedRelativePath!!).exists
            )
        }
        // The artifact rows are registered.
        val artifacts = artifactService.forProject(projectId)
        assertTrue(artifacts.any { it.name == "التقرير.pdf" })
        assertTrue(artifacts.any { it.name == "notes.txt" })
    }

    @Test
    fun `one missing phone file fails honestly without aborting the batch`() = runBlocking {
        val ok = phoneFile("Download/سليم.txt", "محتوى")
        val missing = File(phoneRoot, "Download/مفقود.doc") // never created

        val results = coordinator.importWorkspaceFiles(
            listOf(
                android.net.Uri.fromFile(ok).toString(),
                android.net.Uri.fromFile(missing).toString()
            )
        )

        assertEquals(2, results.size)
        val okResult = results.first { it.displayName == "سليم.txt" }
        val missingResult = results.first { it.displayName == "مفقود.doc" }
        assertTrue(okResult.isSuccess)
        assertFalse(missingResult.isSuccess)
        assertTrue(
            "the real reason, verbatim: ${missingResult.errorMessage}",
            missingResult.errorMessage!!.contains("تعذر فتح الملف")
        )
    }

    @Test
    fun `a plain phone directory becomes a folder attachment through the production routing`() = runBlocking {
        val dir = File(phoneRoot, "Documents/مشروع").apply { mkdirs() }
        File(dir, "README.md").writeText("# المشروع")
        File(dir, "src").apply { mkdirs() }.let { File(it, "Main.kt").writeText("fun main()") }
        File(dir, "بيانات.txt").writeText("بيانات")

        val attachment = coordinator.importFolderAttachment(
            treeUri = android.net.Uri.fromFile(dir).toString()
        )

        // The honest provenance distinguishes a phone directory from a SAF
        // document TREE at a glance.
        assertEquals("PHONE_STORAGE_DIR_ZIP", attachment.provenance)
        assertEquals("inode/directory", attachment.mimeType)
        assertEquals("مشروع", attachment.name)
        // The Stat contract reports 0 for directories (a folder has no
        // single-file length) — the SAME honest semantics the SAF folder
        // path has always produced; the per-file truth lives in the
        // grounding report, never fabricated here.
        assertEquals(0L, attachment.sizeBytes)
        assertNotNull(attachment.artifactId)

        // The FOLDER artifact row is registered for the active project.
        val projectId = workspaceService.activeProjectIdOrNull()!!
        val artifacts = artifactService.forProject(projectId)
        assertTrue(
            "the folder artifact must be registered",
            artifacts.any { it.name == "مشروع" }
        )
    }

    @Test
    fun `the directory bridge serializes nested entries faithfully`() {
        val dir = File(phoneRoot, "Bridge/حزمة").apply { mkdirs() }
        File(dir, "a.txt").writeText("AAA")
        File(dir, "sub").apply { mkdirs() }.let { File(it, "b.txt").writeText("BBB") }

        val zip = realPort.serializeDirectoryToZip(dir)!!
        val names = zipEntries(zip)

        assertTrue(names.contains("a.txt"))
        assertTrue(names.contains("sub/"))
        assertTrue(names.contains("sub/b.txt"))
    }

    @Test
    fun `the directory bridge budget surfaces as the honest null - never a partial zip`() {
        val dir = File(phoneRoot, "Bridge/ضخم").apply { mkdirs() }
        File(dir, "1.txt").writeText("one")
        File(dir, "2.txt").writeText("two")
        File(dir, "3.txt").writeText("three")

        // The test seam runs the PRODUCTION traversal with production-shaped
        // but tiny caps: 2 entries / 4 bytes.
        val overEntries = realPort.serializeDirectoryToZip(dir, maxEntries = 2)
        val overBytes = realPort.serializeDirectoryToZip(dir, maxEntries = 10, maxTotalBytes = 4)

        assertEquals(null, overEntries)
        assertEquals(null, overBytes)
    }

    @Test
    fun `the directory bridge rejects an unreadable or missing directory honestly`() {
        assertEquals(null, realPort.openDirectoryAsZipStream("file:///no/such/dir"))
    }

    /**
     * The directory-entry fix (trailing-slash stripping) must NOT weaken
     * traversal protection: a DIRECTORY entry carrying a traversal path
     * («../evil/») is still rejected wholesale.
     */
    @Test
    fun `a directory entry with a traversal path is still rejected`() = runBlocking {
        val pid = workspaceService.activeProjectIdOrNull()!!
        val zipOut = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zipOut).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("good/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("../evil/"))
            zos.closeEntry()
        }
        val outcome = fileTransferService.importFolderZip(
            workspaceId = workspaceService.activeWorkspaceIdOrNull()!!,
            projectId = pid,
            zipStream = java.io.ByteArrayInputStream(zipOut.toByteArray()),
            targetSubDirectory = "attachments/zip_slip_probe"
        )
        assertTrue(
            "the traversal directory entry must fail the whole import",
            outcome is com.example.application.transfer.TransferOutcome.Failure
        )
        assertEquals(
            "PATH_TRAVERSAL_DETECTED",
            (outcome as com.example.application.transfer.TransferOutcome.Failure).code
        )
    }

    private fun zipEntries(stream: InputStream): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                zip.closeEntry()
            }
        }
        return names
    }
    // ------------------------------------------------------------------
    // STAGE 7 (user round 4) — the GROUNDED folder attach + the workspace
    // folder import (the two links the user's report proved missing).
    // ------------------------------------------------------------------

    @Test
    fun `a grounded folder attach rides the send digest with its real files`() = runBlocking {
        // The coordinator WITH the folder understanding service — the
        // production wiring (AppContainer wires it since round 3); the
        // plain coordinator in setUp stays reference-only (the honest
        // default of the API seam).
        val groundedCoordinator = ChatAttachmentCoordinator(
            fileTransferService = fileTransferService,
            artifactService = artifactService,
            workspaceRuntimeService = workspaceService,
            fileStore = fileStore,
            contentPort = realPort,
            folderZipSource = { uri ->
                if (uri.startsWith("file:")) realPort.openDirectoryAsZipStream(uri)
                else realPort.openTreeAsZipStream(uri)
            },
            folderUnderstanding = FolderUnderstandingService(fileStore)
        )
        val dir = File(phoneRoot, "Documents/نظام").apply { mkdirs() }
        File(dir, "README.md").writeText("# نظام CODP — نظرة عامة")
        File(dir, "src").apply { mkdirs() }.let { File(it, "main.py").writeText("def main(): pass") }
        File(dir, "ملاحظات.txt").writeText("ملاحظات المشروع")

        // THE FIX: groundFolder = true — the chat pickFolder call passes it
        // since round 4; before it the folder rode as reference-only and the
        // assistant could only reply that the files were «not uploaded».
        val scope = groundedCoordinator.captureActiveScope()
        val attachment = groundedCoordinator.importFolderAttachment(
            treeUri = android.net.Uri.fromFile(dir).toString(),
            scope = scope,
            groundFolder = true
        )

        assertEquals("PHONE_STORAGE_DIR_ZIP", attachment.provenance)
        assertEquals(
            "the folder must be GROUNDED when understanding is wired — got ${attachment.folderReportJson}",
            "GROUNDED",
            attachment.groundingState
        )
        val reportJson = attachment.folderReportJson
        assertNotNull(reportJson)
        val report = org.json.JSONObject(reportJson!!)
        assertTrue(
            "groundedFiles must be > 0, got ${report.optString("groundedFiles")}",
            report.optInt("groundedFiles") > 0
        )

        // THE USER-VISIBLE CONTRACT: the send digest (what the model
        // actually receives) contains the folder's real file contents.
        val outcome = groundedCoordinator.buildGroundingDigest(
            workspaceId = scope.workspaceId!!,
            projectId = scope.projectId!!,
            attachments = listOf(attachment)
        )
        assertTrue("digest failures must be empty: ${outcome.failures}", outcome.failures.isEmpty())
        assertTrue(
            "the digest must carry the <user_folder header",
            outcome.digest.contains("<user_folder")
        )
        assertTrue(
            "the digest must carry the actual README content",
            outcome.digest.contains("نظرة عامة")
        )
        assertTrue(
            "the digest must carry the actual source content",
            outcome.digest.contains("def main(): pass")
        )
    }

    @Test
    fun `a reference-only folder attach honestly says its content was not read`() = runBlocking {
        // The SAME seam, groundFolder = false (the API default): the honest
        // reference-only mode — the digest says so, never claims analysis.
        val dir = File(phoneRoot, "Documents/مرجع").apply { mkdirs() }
        File(dir, "a.txt").writeText("محتوى")

        val scope = coordinator.captureActiveScope()
        val attachment = coordinator.importFolderAttachment(
            treeUri = android.net.Uri.fromFile(dir).toString(),
            scope = scope
        )
        assertEquals("ATTACHMENT_ONLY", attachment.groundingState)

        val outcome = coordinator.buildGroundingDigest(
            workspaceId = scope.workspaceId!!,
            projectId = scope.projectId!!,
            attachments = listOf(attachment)
        )
        assertTrue(outcome.failures.isEmpty())
        assertTrue(
            "the digest must honestly say the content was not read",
            outcome.digest.contains("لم يُقرأ محتواه")
        )
    }

    @Test
    fun `workspace folder import lands a real browsable tree under uploads`() = runBlocking {
        val dir = File(phoneRoot, "Documents/حزمة_مشروع").apply { mkdirs() }
        File(dir, "README.md").writeText("# الحزمة")
        File(dir, "src").apply { mkdirs() }.let { File(it, "app.py").writeText("print(1)") }
        File(dir, "data.json").writeText("{}")

        val result = coordinator.importWorkspaceFolder(
            android.net.Uri.fromFile(dir).toString()
        )

        assertTrue("import must succeed: ${result.errorMessage}", result.isSuccess)
        assertTrue(
            "the destination must be the uploads/ namespace, got ${result.importedRelativePath}",
            result.importedRelativePath!!.startsWith("uploads/")
        )
        assertEquals("حزمة_مشروع", result.displayName)

        // The tree is REAL on disk in the ACTIVE project's sandbox — every
        // nested file exists at its own path (browsable by the explorer).
        val projectId = workspaceService.activeProjectIdOrNull()!!
        val target = result.importedRelativePath!!
        val root = fileStore.projectRoot(projectId)
        assertTrue(fileStore.stat(root, target).exists)
        assertTrue(fileStore.stat(root, "$target/README.md").exists)
        assertTrue(fileStore.stat(root, "$target/src/app.py").exists)
        assertTrue(fileStore.stat(root, "$target/data.json").exists)

        // The FOLDER artifact row is registered (honest type, real landing).
        val artifacts = artifactService.forProject(projectId)
        val folderArtifact = artifacts.firstOrNull { it.storageUri == target }
        assertNotNull("the folder artifact must be registered at $target", folderArtifact)
        assertEquals(
            com.example.domain.core.artifact.ArtifactType.FOLDER,
            folderArtifact!!.type
        )
    }

    @Test
    fun `workspace folder import fails honestly for a missing directory`() = runBlocking {
        val result = coordinator.importWorkspaceFolder("file:///no/such/folder")
        assertFalse(result.isSuccess)
        assertTrue(
            "the real reason, verbatim: ${result.errorMessage}",
            result.errorMessage!!.contains("تعذر قراءة المجلد")
        )
    }
}
