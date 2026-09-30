package com.example.infrastructure.content

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.application.transfer.FileTransferService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ============================================================================
 * AndroidContentPort — the PRODUCTION implementation of the declared
 * [FileTransferService.ContentPort] seam (CHAT CAPABILITIES Task 2 §5)
 * ============================================================================
 *
 * The interface always said "production impl wraps ContentResolver/SAF;
 * tests use fakes" — this is that implementation: display names and sizes
 * come from the SAF provider (OpenableColumns), and reads open the
 * ContentResolver stream the picker granted.
 *
 * It ALSO provides the folder bridge the backend's folder-import contract
 * needs: a SAF document TREE (ACTION_OPEN_DOCUMENT_TREE) serialized into
 * the ZIP stream [FileTransferService.importFolderZip] consumes. The
 * traversal uses only framework DocumentsContract APIs (no new dependency)
 * and streams files entry-by-entry under the transfer limits' enforcement.
 */
class AndroidContentPort(
    private val context: Context
) : FileTransferService.ContentPort {

    override fun openRead(uri: String): InputStream? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))
    }.getOrNull()

    override fun openWrite(uri: String): OutputStream? = runCatching {
        context.contentResolver.openOutputStream(Uri.parse(uri))
    }.getOrNull()

    override fun queryDisplayName(uri: String): String? = runCatching {
        val parsed = Uri.parse(uri)
        // Document trees expose their display name via the document URI.
        if (DocumentsContract.isDocumentUri(context, parsed)) {
            queryDisplayNameOfDocument(parsed)
        } else {
            null
        }
    }.getOrNull()

    override fun querySize(uri: String): Long? = runCatching {
        val parsed = Uri.parse(uri)
        context.contentResolver.query(
            parsed,
            arrayOf(android.provider.OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            } else null
        }
    }.getOrNull()

    /** The SAF metadata a chat attachment chip needs at pick time. */
    fun queryMimeType(uri: String): String? = runCatching {
        context.contentResolver.getType(Uri.parse(uri))
    }.getOrNull()

    /**
     * Serializes a SAF document TREE into the ZIP stream the backend's
     * [FileTransferService.importFolderZip] contract consumes. Pure
     * framework APIs (DocumentsContract child queries) — no new dependency;
     * the transfer limits still bound the import downstream.
     *
     * HOTFIX (folder attach crash — unbounded in-memory serialization): the
     * whole tree used to be buffered into ONE ByteArrayOutputStream with no
     * cap of its own, so a folder larger than the heap killed the process
     * with OutOfMemoryError BEFORE the transfer layer's limits could ever
     * engage (they only see the stream afterwards). The traversal is now
     * bounded here: at most [MAX_TREE_ENTRIES] entries and
     * [MAX_TREE_TOTAL_BYTES] of file content. Exceeding either aborts the
     * serialization (null → the coordinator's honest failure) instead of
     * taking the whole app down. A streaming redesign (no in-memory buffer)
     * is the strategic fix — see the delivery report.
     */
    fun openTreeAsZipStream(treeUriString: String): InputStream? = runCatching {
        val treeUri = Uri.parse(treeUriString)
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val budget = TreeSerializationBudget()
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            writeTreeEntries(zip, rootDocUri, basePath = "", budget)
        }
        ByteArrayInputStream(buffer.toByteArray())
    }.getOrNull()

    /** Bounded traversal state for [openTreeAsZipStream]. */
    private class TreeSerializationBudget {
        var entries = 0
        var totalBytes = 0L
        val exceeded: Boolean get() = entries > MAX_TREE_ENTRIES || totalBytes > MAX_TREE_TOTAL_BYTES
    }

    private fun writeTreeEntries(
        zip: ZipOutputStream,
        documentUri: Uri,
        basePath: String,
        budget: TreeSerializationBudget
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            documentUri,
            DocumentsContract.getDocumentId(documentUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        context.contentResolver.query(
            childrenUri, projection, null, null, null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (budget.exceeded) throw TreeBudgetExceededException()
                val documentId = cursor.getString(idIndex) ?: continue
                val name = cursor.getString(nameIndex) ?: continue
                val mimeType = cursor.getString(mimeIndex) ?: ""
                val entryUri = DocumentsContract.buildDocumentUriUsingTree(documentUri, documentId)
                val entryPath = if (basePath.isBlank()) name else "$basePath/$name"
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    zip.putNextEntry(ZipEntry("$entryPath/"))
                    zip.closeEntry()
                    writeTreeEntries(zip, entryUri, entryPath, budget)
                } else {
                    budget.entries++
                    if (budget.entries > MAX_TREE_ENTRIES) throw TreeBudgetExceededException()
                    zip.putNextEntry(ZipEntry(entryPath))
                    context.contentResolver.openInputStream(entryUri)?.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (budget.totalBytes > MAX_TREE_TOTAL_BYTES) throw TreeBudgetExceededException()
                            val n = input.read(buf)
                            if (n < 0) break
                            budget.totalBytes += n
                            zip.write(buf, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private companion object {
        /**
         * HOTFIX caps for the folder bridge — generous enough for real
         * project folders, far below the heap ceiling of a foreground app.
         */
        const val MAX_TREE_ENTRIES = 5_000
        const val MAX_TREE_TOTAL_BYTES = 512L * 1024 * 1024
    }

    /**
     * Signals the tree serialization budget was exceeded — caught by
     * [openTreeAsZipStream]'s runCatching and surfaced as its honest null
     * (an unimportable folder) instead of a truncated/partial ZIP.
     */
    private class TreeBudgetExceededException : RuntimeException(
        "المجلد يتجاوز حدود الاستيراد (عدد المدخلات أو الحجم الكلي)."
    )

    private fun queryDisplayNameOfDocument(uri: Uri): String? {
        return context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        }
    }
}
