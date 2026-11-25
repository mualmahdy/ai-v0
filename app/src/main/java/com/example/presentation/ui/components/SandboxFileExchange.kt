package com.example.presentation.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.domain.core.security.governance.PathContainment
import java.io.File

/**
 * ============================================================================
 * FIW Stage 1/2 — the REAL device-facing export/share surface for
 * project-sandbox files
 * ============================================================================
 * The audit finding: an attachment/artifact was a NAME in the conversation
 * with no way to open, export or share it — and workspace single-file
 * export did not exist (only whole-project packages did).
 *
 * This helper is the ONE bridge from a project-sandbox relative path to the
 * outside world, used by the artifact canvas, the conversation attachment
 * chips and the Files screen:
 *
 *  - [buildShareIntent]: ACTION_SEND with a REAL content URI through the
 *    FileProvider (`@xml/file_paths` exposes filesDir/workspaces/ ONLY);
 *  - [writeToSafTarget]: a CreateDocument SAF stream write (the "save to
 *    device" path — no storage permission needed).
 *
 * SECURITY CONTRACT: every path is containment-validated against the
 * project sandbox root BEFORE it touches a Uri (the same
 * [WorkspacePathPolicy.isContained] boundary check the storage adapter
 * uses) — a traversal attempt yields a null/failed result, never a handle
 * to a file outside the sandbox. The relative path also never escapes via
 * URI encoding: the File is constructed under the sandbox root only.
 */
object SandboxFileExchange {

    /** The sandbox root for a project (mirrors SandboxProjectFileStore). */
    private fun projectRoot(context: Context, projectId: Long): File =
        File(File(context.filesDir, "workspaces"), "proj_$projectId")

    /**
     * Resolves a containment-checked sandbox File (null = the path tried to
     * escape the project root, or the file simply does not exist).
     */
    fun resolveSandboxFile(context: Context, projectId: Long, relativePath: String): File? {
        val root = projectRoot(context, projectId)
        val target = File(root, relativePath)
        val candidateCanonical = target.canonicalPath.replace('\\', '/')
        val rootCanonical = root.canonicalPath.replace('\\', '/')
        if (!PathContainment.isContained(candidateCanonical, rootCanonical)) return null
        if (!target.exists() || !target.isFile) return null
        return target
    }

    /**
     * Builds an ACTION_SEND intent carrying the file as a REAL stream via
     * the FileProvider (read permission granted to the receiving app only).
     * Returns null when the path failed containment or the file is missing
     * (honest unavailable — the caller surfaces the reason, never a fake
     * share of an empty body).
     */
    fun buildShareIntent(
        context: Context,
        projectId: Long,
        relativePath: String,
        mimeType: String,
        title: String
    ): Intent? {
        val file = resolveSandboxFile(context, projectId, relativePath) ?: return null
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Writes the bytes into a CreateDocument SAF target uri (the user just
     * picked WHERE to save — this completes the real save-to-device cycle).
     * Returns true on a complete write; false surfaces the failure honestly.
     */
    fun writeToSafTarget(context: Context, uri: Uri, bytes: ByteArray): Boolean =
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                // "wt" = write + truncate: a re-save over an existing pick
                // never leaves stale trailing bytes from a longer previous
                // content.
                stream.write(bytes)
                stream.flush()
            } ?: return false
            true
        }.getOrDefault(false)
}
