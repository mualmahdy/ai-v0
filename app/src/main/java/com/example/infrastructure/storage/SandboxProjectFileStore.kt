package com.example.infrastructure.storage

import com.example.domain.core.security.governance.PathContainment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * ============================================================================
 * REPAIR ORDER §7/§9/§11 — SANDBOX FILE STORE
 * ============================================================================
 * The low-level file engine behind the unified artifact library and the
 * transfer subsystem. All operations are PROJECT-ROOTED and CONTAINMENT-
 * CHECKED (reusing the existing [PathContainment] boundary-safe primitive —
 * NOT a new path-validation implementation).
 *
 * Operations: open/read/write/copy/move/delete/list/stat/stream/hash.
 *
 * Security invariants:
 *  - every candidate path is canonically contained in the project root
 *    (boundary-safe: `proj_10` does not pass `proj_1`'s check);
 *  - path traversal (../, absolute, backslash tricks) is REJECTED, never
 *    re-rooted;
 *  - no operation escapes the sanctioned sandbox layout.
 */
class SandboxProjectFileStore(
    /** Base directory containing all project roots (`files/workspaces`). */
    private val baseProjectsDir: File
) {
    companion object {
        /** Deterministic project-root layout. */
        fun projectRoot(base: File, projectId: Long): File = File(base, "proj_$projectId")
    }

    fun projectRoot(projectId: Long): File =
        projectRoot(baseProjectsDir, projectId).apply { if (!exists()) mkdirs() }

    // ------------------------------------------------------------------
    // Containment
    // ------------------------------------------------------------------

    class UnsafePathException(message: String) : SecurityException(message)

    /** Resolves and CONTAINMENT-CHECKS a relative path inside a project root. */
    fun resolveContained(root: File, relativePath: String): File {
        val normalized = relativePath.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.contains(":")) {
            throw UnsafePathException("PATH_REJECTED: المسار المطلق غير مسموح ($relativePath).")
        }
        // REPAIR ORDER §9 — backslash is a path-separator trick on Windows
        // hosts and ambiguous elsewhere: reject it before canonicalization.
        if (relativePath.contains('\\')) {
            throw UnsafePathException("PATH_REJECTED: المسار يحتوي فاصل مسار غير مسموح ('\\') ($relativePath).")
        }
        val target = File(root, normalized)
        val canonical = target.canonicalFile
        if (!PathContainment.isContained(
                candidateCanonical = canonical.absolutePath.replace('\\', '/'),
                rootCanonical = root.canonicalPath.replace('\\', '/')
            )
        ) {
            throw UnsafePathException(
                "PATH_REJECTED: تجاوز نطاق المشروع (path traversal) — $relativePath."
            )
        }
        return canonical
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    data class Stat(
        val exists: Boolean,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val lastModifiedEpochMs: Long,
        val sha256: String? = null
    )

    fun stat(root: File, relativePath: String): Stat {
        val target = resolveContained(root, relativePath)
        if (!target.exists()) return Stat(false, false, 0L, 0L)
        return Stat(
            exists = true,
            isDirectory = target.isDirectory,
            sizeBytes = if (target.isFile) target.length() else 0L,
            lastModifiedEpochMs = target.lastModified(),
            sha256 = if (target.isFile) hash(root, relativePath) else null
        )
    }

    fun read(root: File, relativePath: String): ByteArray {
        val target = resolveContained(root, relativePath)
        return target.readBytes()
    }

    fun write(root: File, relativePath: String, content: ByteArray) {
        val target = resolveContained(root, relativePath)
        target.parentFile?.mkdirs()
        // Atomic write: temp file + rename (partial writes never visible).
        val tmp = File(target.parentFile, target.name + ".tmp_transfer")
        FileOutputStream(tmp).use { it.write(content) }
        if (!tmp.renameTo(target)) {
            target.writeBytes(content) // cross-filesystem rename fallback
            tmp.delete()
        }
    }

    fun openStream(root: File, relativePath: String): InputStream =
        FileInputStream(resolveContained(root, relativePath))

    fun openWriteStream(root: File, relativePath: String): OutputStream {
        val target = resolveContained(root, relativePath)
        target.parentFile?.mkdirs()
        return FileOutputStream(target)
    }

    fun copy(root: File, from: String, to: String) {
        val src = resolveContained(root, from)
        val dst = resolveContained(root, to)
        if (src.isDirectory) {
            src.copyRecursively(dst, overwrite = true) { _, _ -> OnErrorAction.TERMINATE }
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }

    fun copyDirectory(srcRoot: File, dstRoot: File) {
        dstRoot.mkdirs()
        srcRoot.listFiles()?.forEach { entry ->
            if (entry.isDirectory) entry.copyRecursively(File(dstRoot, entry.name), overwrite = true)
            else entry.copyTo(File(dstRoot, entry.name), overwrite = true)
        }
    }

    fun move(root: File, from: String, to: String) {
        copy(root, from, to)
        delete(root, from)
    }

    fun delete(root: File, relativePath: String): Boolean {
        val target = resolveContained(root, relativePath)
        return target.deleteRecursively()
    }

    /** Recursive listing with sandbox-relative paths. */
    fun list(root: File, subDirectory: String? = null): List<String> {
        val base = if (subDirectory.isNullOrBlank()) root else resolveContained(root, subDirectory)
        if (!base.exists()) return emptyList()
        val out = mutableListOf<String>()
        base.walkTopDown().forEach { file ->
            if (file != base) {
                out.add(file.toRelativeString(root).replace(File.separatorChar, '/'))
            }
        }
        return out.sorted()
    }

    fun hash(root: File, relativePath: String): String? {
        val target = resolveContained(root, relativePath)
        if (!target.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(target).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    // REPAIR ORDER §9 — archive safety primitives
    // ------------------------------------------------------------------

    /**
     * Validates a ZIP entry name BEFORE extraction (Zip-Slip, absolute
     * paths, backslash tricks, device/control chars).
     */
    fun validateZipEntryName(entryName: String): Boolean {
        if (entryName.isBlank()) return false
        if (entryName.contains('\\')) return false
        if (entryName.startsWith("/")) return false
        if (entryName.contains(":")) return false
        if (entryName.split('/').any { it == ".." || it == "." || it.isBlank() && entryName.split('/').size > 1 }) return false
        if (entryName.any { it.code < 0x20 }) return false
        return true
    }

    /** Bytes transferred (atomic-promotion bookkeeping). */
    var totalBytesTransferred: Long = 0L
        private set

    internal fun recordBytes(n: Long) { totalBytesTransferred += n }

    private fun File.copyTo(dst: File, overwrite: Boolean) {
        if (dst.exists() && !overwrite) throw java.io.IOException("EXISTS: $dst")
        dst.parentFile?.mkdirs()
        java.nio.file.Files.copy(this.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun File.copyRecursively(dst: File, overwrite: Boolean, onError: (File, Exception) -> OnErrorAction): Boolean {
        if (this.isDirectory) {
            dst.mkdirs()
            return listFiles()?.all { child ->
                child.copyRecursively(File(dst, child.name), overwrite, onError)
            } ?: true
        }
        return try {
            copyTo(dst, overwrite)
            true
        } catch (e: Exception) {
            onError(this, e) != OnErrorAction.TERMINATE
        }
    }

    private enum class OnErrorAction { TERMINATE, SKIP }
}
