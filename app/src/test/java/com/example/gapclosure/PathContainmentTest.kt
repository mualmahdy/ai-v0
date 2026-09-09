package com.example.gapclosure

import com.example.domain.core.security.governance.PathContainment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * P0-2 (audit 2026 §6) — boundary-safe workspace filesystem containment.
 * ============================================================================
 *
 * The audit's central isolation finding: the legacy check
 * `canonicalPath.startsWith(projectDir.canonicalPath)` accepts a SIBLING
 * directory that merely shares a string prefix — a path under `proj_10`
 * passed the containment check of `proj_1`, breaking workspace isolation.
 * This test proves the boundary-safe [PathContainment] closes that hole.
 */
class PathContainmentTest {

    private val proj1 = "/data/workspaces/proj_1"
    private val proj10 = "/data/workspaces/proj_10"

    @Test
    fun `the sibling-prefix attack proj_1 vs proj_10 is REJECTED`() {
        // A file INSIDE proj_10 must NOT count as contained in proj_1.
        assertFalse(PathContainment.isContained("$proj10/notes.md", proj1))
        assertFalse(PathContainment.isContained(proj10, proj1))
        // And symmetrically.
        assertFalse(PathContainment.isContained("$proj1/notes.md", proj10))
    }

    @Test
    fun `paths strictly inside the root ARE contained`() {
        assertTrue(PathContainment.isContained("$proj1/notes.md", proj1))
        assertTrue(PathContainment.isContained("$proj1/a/b/c.txt", proj1))
    }

    @Test
    fun `the root itself is contained (directory operations)`() {
        assertTrue(PathContainment.isContained(proj1, proj1))
    }

    @Test
    fun `traversal escape is rejected`() {
        assertFalse(PathContainment.isContained("/data/workspaces/other/file", proj1))
        assertFalse(PathContainment.isContained("/etc/passwd", proj1))
        assertFalse(PathContainment.isContained("/data/workspaces", proj1))
    }

    @Test
    fun `trailing separators on the root do not change the verdict`() {
        assertTrue(PathContainment.isContained("$proj1/x", "$proj1/"))
        assertFalse(PathContainment.isContained("$proj10/x", "$proj1/"))
    }

    @Test
    fun `prefix-equal-but-different sibling names are all separated`() {
        // Exhaustive check over the proj_N family: only same-project paths pass.
        for (mine in listOf("proj_1", "proj_11", "proj_111", "proj_2", "proj_20")) {
            val root = "/data/workspaces/$mine"
            assertTrue(PathContainment.isContained("$root/file", root))
            for (other in listOf("proj_1", "proj_11", "proj_111", "proj_2", "proj_20")) {
                if (other == mine) continue
                val otherPath = "/data/workspaces/$other/file"
                val verdict = PathContainment.isContained(otherPath, root)
                assertEquals("path of $other must NOT be contained in $mine", false, verdict)
            }
        }
    }

    @Test
    fun `empty root never admits anything (fail-closed)`() {
        assertFalse(PathContainment.isContained("/anything", ""))
        assertFalse(PathContainment.isContained("/", ""))
    }
}
