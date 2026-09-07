package com.example.domain.core.tools.patch

/**
 * ============================================================================
 * File Patch Models — Phase 1 (Coding Toolchain)
 * ============================================================================
 *
 * `apply_patch` is the PREFERRED modification primitive (over blind
 * write_file overwrite) because it validates context before mutating:
 *  - each hunk must match the CURRENT file content exactly once,
 *  - the whole patch applies atomically (all hunks or nothing),
 *  - an expected content hash guards against concurrent modification
 *    (optimistic concurrency — external changes yield CONFLICT, never a
 *    silent overwrite).
 *
 * This is a pure-domain, dependency-free model so it is fully unit-testable.
 */

/**
 * One replacement hunk: [expect] must occur exactly once in the target file.
 * An EMPTY [expect] is an INSERTION hunk — valid only when the base content
 * is empty (file creation); it is rejected otherwise.
 */
data class PatchHunk(
    val expect: String,
    val replaceWith: String
)

/** The patch request for a single file. */
data class FilePatchRequest(
    val relativePath: String,
    val hunks: List<PatchHunk>,
    /** SHA-256 (hex) of the expected current content; null = unconditional. */
    val expectedContentHash: String? = null,
    /** Create the file if it does not exist (with an empty base). */
    val createIfMissing: Boolean = false
) {
    init {
        require(hunks.isNotEmpty()) { "الرقعة يجب أن تحتوي على قِطعة واحدة على الأقل." }
    }
}

/** Machine-consumable patch failures. */
sealed interface PatchFailure {
    data class ContentHashConflict(val expected: String, val actual: String) : PatchFailure
    data class HunkNotFound(val hunkIndex: Int, val expectPreview: String) : PatchFailure
    data class HunkAmbiguous(val hunkIndex: Int, val occurrences: Int) : PatchFailure
    data class FileMissing(val path: String) : PatchFailure
}

/** Result of applying a patch. */
data class FilePatchResult(
    val path: String,
    val appliedHunks: Int,
    val resultingContent: String,
    val resultingContentHash: String,
    val hunksRejected: List<PatchFailure> = emptyList()
) {
    val isFullyApplied: Boolean get() = hunksRejected.isEmpty()
}

/**
 * Pure patch application engine. Applies hunks SEQUENTIALLY: each hunk
 * matches against the content as modified by the previous hunks —
 * the same semantics coding agents rely on for ordered edits.
 */
object FilePatchEngine {

    /**
     * Applies [request] against [currentContent].
     * All hunks are validated FIRST, then applied — a rejected hunk leaves
     * the content untouched (atomicity).
     */
    fun apply(request: FilePatchRequest, currentContent: String?): FilePatchResult {
        // --- Missing file handling -----------------------------------------
        if (currentContent == null && !request.createIfMissing) {
            return FilePatchResult(
                path = request.relativePath,
                appliedHunks = 0,
                resultingContent = "",
                resultingContentHash = "",
                hunksRejected = listOf(PatchFailure.FileMissing(request.relativePath))
            )
        }
        val content = currentContent ?: ""

        // --- Optimistic concurrency check ----------------------------------
        if (request.expectedContentHash != null) {
            val actual = sha256Hex(content)
            if (!actual.equals(request.expectedContentHash, ignoreCase = true)) {
                return FilePatchResult(
                    path = request.relativePath,
                    appliedHunks = 0,
                    resultingContent = content,
                    resultingContentHash = actual,
                    hunksRejected = listOf(
                        PatchFailure.ContentHashConflict(request.expectedContentHash, actual)
                    )
                )
            }
        }

        // --- Validate ALL hunks first (atomicity) --------------------------
        val rejections = mutableListOf<PatchFailure>()
        var probe = content
        request.hunks.forEachIndexed { index, hunk ->
            val occurrences = probe.count(hunk.expect)
            when {
                occurrences == 0 -> rejections += PatchFailure.HunkNotFound(
                    index,
                    hunk.expect.take(80)
                )
                occurrences > 1 -> rejections += PatchFailure.HunkAmbiguous(index, occurrences)
                else -> probe = replaceOnce(probe, hunk.expect, hunk.replaceWith)
            }
        }
        if (rejections.isNotEmpty()) {
            return FilePatchResult(
                path = request.relativePath,
                appliedHunks = 0,
                resultingContent = content,
                resultingContentHash = sha256Hex(content),
                hunksRejected = rejections
            )
        }

        // --- Apply for real -------------------------------------------------
        var result = content
        request.hunks.forEach { hunk ->
            result = replaceOnce(result, hunk.expect, hunk.replaceWith)
        }
        return FilePatchResult(
            path = request.relativePath,
            appliedHunks = request.hunks.size,
            resultingContent = result,
            resultingContentHash = sha256Hex(result)
        )
    }

    /** SHA-256 hex digest. */
    fun sha256Hex(content: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Single-occurrence replacement (Kotlin's replace() replaces ALL). */
    private fun replaceOnce(content: String, expect: String, replaceWith: String): String {
        if (expect.isEmpty()) {
            // Insertion hunk: only meaningful on an empty base.
            return if (content.isEmpty()) replaceWith else content
        }
        val index = content.indexOf(expect)
        if (index < 0) return content
        return content.substring(0, index) + replaceWith + content.substring(index + expect.length)
    }

    private fun String.count(needle: String): Int {
        if (needle.isEmpty()) return if (isEmpty()) 1 else 0
        var count = 0
        var index = 0
        while (true) {
            index = indexOf(needle, index)
            if (index < 0) break
            count++
            index += needle.length
        }
        return count
    }
}
