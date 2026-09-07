package com.example.application.governed

import com.example.domain.core.tools.patch.FilePatchEngine
import com.example.domain.core.tools.patch.FilePatchRequest
import com.example.domain.core.tools.patch.PatchFailure
import com.example.domain.core.tools.patch.PatchHunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FilePatchEngine tests — atomic context-validated patching with
 * optimistic concurrency (the apply_patch primitive of Workstream F).
 */
class FilePatchEngineTest {

    @Test
    fun `single hunk applies and returns new hash`() {
        val content = "fun main() {\n    println(\"hello\")\n}\n"
        val result = FilePatchEngine.apply(
            FilePatchRequest(
                relativePath = "src/Main.kt",
                hunks = listOf(PatchHunk(expect = "println(\"hello\")", replaceWith = "println(\"world\")"))
            ),
            currentContent = content
        )
        assertTrue(result.isFullyApplied)
        assertEquals(1, result.appliedHunks)
        assertTrue(result.resultingContent.contains("println(\"world\")"))
        assertFalse(result.resultingContent.contains("println(\"hello\")"))
        assertEquals(FilePatchEngine.sha256Hex("fun main() {\n    println(\"world\")\n}\n"), result.resultingContentHash)
    }

    @Test
    fun `multiple hunks apply sequentially`() {
        val content = "alpha beta gamma"
        val result = FilePatchEngine.apply(
            FilePatchRequest(
                relativePath = "f.txt",
                hunks = listOf(
                    PatchHunk("alpha", "ALPHA"),
                    PatchHunk("gamma", "GAMMA")
                )
            ),
            currentContent = content
        )
        assertTrue(result.isFullyApplied)
        assertEquals("ALPHA beta GAMMA", result.resultingContent)
    }

    @Test
    fun `missing hunk rejects the WHOLE patch atomically`() {
        val content = "alpha beta gamma"
        val result = FilePatchEngine.apply(
            FilePatchRequest(
                relativePath = "f.txt",
                hunks = listOf(
                    PatchHunk("alpha", "ALPHA"),        // matches
                    PatchHunk("zeta", "ZETA")           // does NOT match
                )
            ),
            currentContent = content
        )
        assertFalse(result.isFullyApplied)
        assertEquals(0, result.appliedHunks)
        assertEquals(content, result.resultingContent) // unchanged — atomicity
        assertTrue(result.hunksRejected.any { it is PatchFailure.HunkNotFound })
    }

    @Test
    fun `ambiguous hunk is rejected not guessed`() {
        val content = "a a a"
        val result = FilePatchEngine.apply(
            FilePatchRequest("f.txt", hunks = listOf(PatchHunk("a", "b"))),
            currentContent = content
        )
        assertFalse(result.isFullyApplied)
        assertTrue(result.hunksRejected.any { it is PatchFailure.HunkAmbiguous && it.occurrences == 3 })
    }

    @Test
    fun `stale expected hash yields CONFLICT not silent overwrite`() {
        val content = "current content v2"
        val result = FilePatchEngine.apply(
            FilePatchRequest(
                relativePath = "f.txt",
                hunks = listOf(PatchHunk("current", "new")),
                expectedContentHash = FilePatchEngine.sha256Hex("current content v1") // stale
            ),
            currentContent = content
        )
        assertFalse(result.isFullyApplied)
        assertTrue(result.hunksRejected.any { it is PatchFailure.ContentHashConflict })
        assertEquals(content, result.resultingContent)
    }

    @Test
    fun `fresh expected hash allows the patch`() {
        val content = "current content v2"
        val result = FilePatchEngine.apply(
            FilePatchRequest(
                relativePath = "f.txt",
                hunks = listOf(PatchHunk("current", "new")),
                expectedContentHash = FilePatchEngine.sha256Hex(content)
            ),
            currentContent = content
        )
        assertTrue(result.isFullyApplied)
    }

    @Test
    fun `missing file without createIfMissing is FileMissing`() {
        val result = FilePatchEngine.apply(
            FilePatchRequest("f.txt", hunks = listOf(PatchHunk("x", "y"))),
            currentContent = null
        )
        assertFalse(result.isFullyApplied)
        assertTrue(result.hunksRejected.any { it is PatchFailure.FileMissing })
    }

    @Test
    fun `missing file with createIfMissing applies insertion hunk onto empty base`() {
        val result = FilePatchEngine.apply(
            FilePatchRequest("f.txt", hunks = listOf(PatchHunk("", "hello\n")), createIfMissing = true),
            currentContent = null
        )
        assertTrue(result.isFullyApplied)
        assertEquals("hello\n", result.resultingContent)
    }

    @Test
    fun `insertion hunk on NON-empty base is rejected`() {
        val result = FilePatchEngine.apply(
            FilePatchRequest("f.txt", hunks = listOf(PatchHunk("", "x")), createIfMissing = true),
            currentContent = "already has content"
        )
        assertFalse(result.isFullyApplied)
        assertTrue(result.hunksRejected.any { it is PatchFailure.HunkNotFound })
    }

    @Test
    fun `hash function is stable and case-insensitive comparable`() {
        assertEquals(
            FilePatchEngine.sha256Hex("abc"),
            FilePatchEngine.sha256Hex("abc")
        )
    }
}
