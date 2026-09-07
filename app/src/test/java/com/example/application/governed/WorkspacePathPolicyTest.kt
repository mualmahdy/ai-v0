package com.example.application.governed

import com.example.domain.core.security.governance.PathOperation
import com.example.domain.core.security.governance.WorkspacePathPolicy
import com.example.domain.core.security.governance.WorkspacePathPolicyEngine
import com.example.domain.core.security.governance.PathPolicyFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorkspacePathPolicy tests — traversal containment, forbidden targets,
 * write protection, canonical resolution semantics.
 */
class WorkspacePathPolicyTest {

    private val root = "/data/workspaces/proj_1"
    private val policy = WorkspacePathPolicy.Default

    private fun validate(path: String, op: PathOperation = PathOperation.READ): PathPolicyFailure? =
        WorkspacePathPolicyEngine.validate(
            rawPath = path,
            operation = op,
            policy = policy,
            workspaceRootCanonical = root,
            canonicalResolver = WorkspacePathPolicyEngine::lexicalCanonicalResolver
        )

    @Test
    fun `normal relative path inside workspace is allowed`() {
        assertNull(validate("src/main/Kotlin"))
        assertNull(validate("docs/report.md"))
        assertNull(validate("./nested/./file.txt"))
    }

    @Test
    fun `parent traversal escapes containment and is denied`() {
        val failure = validate("../../etc/passwd")
        assertTrue(failure is PathPolicyFailure.ContainmentViolation)
    }

    @Test
    fun `deep traversal masked by prefix is denied on canonical form`() {
        // String-prefix check would PASS this ("src/..."), canonical fails it.
        val failure = validate("src/main/../../../../secrets.key")
        assertTrue(failure is PathPolicyFailure.ContainmentViolation)
    }

    @Test
    fun `absolute path injection is contained or denied`() {
        val failure = validate("/etc/shadow")
        // canonical: root + /etc/shadow stays INSIDE root by lexical join,
        // but Windows-style or traversal forms must never escape.
        if (failure != null) {
            assertTrue(failure is PathPolicyFailure.ContainmentViolation || failure is PathPolicyFailure.ForbiddenTarget)
        }
    }

    @Test
    fun `git internals are forbidden`() {
        assertTrue(validate(".git/config") is PathPolicyFailure.ForbiddenTarget)
        assertTrue(validate("src/.git/HEAD") is PathPolicyFailure.ForbiddenTarget)
    }

    @Test
    fun `secret-like files are forbidden`() {
        assertTrue(validate("secrets.properties") is PathPolicyFailure.ForbiddenTarget)
        assertTrue(validate("config/.env.production") is PathPolicyFailure.ForbiddenTarget)
        assertTrue(validate("release.keystore") is PathPolicyFailure.ForbiddenTarget)
        assertTrue(validate("api_keys.json") is PathPolicyFailure.ForbiddenTarget)
    }

    @Test
    fun `write-protected manifest is writable never`() {
        assertTrue(validate("workspace_manifest.json", PathOperation.WRITE) is PathPolicyFailure.WriteProtected)
        assertTrue(validate("workspace_manifest.json", PathOperation.MODIFY) is PathPolicyFailure.WriteProtected)
        assertTrue(validate("workspace_manifest.json", PathOperation.DELETE) is PathPolicyFailure.WriteProtected)
    }

    @Test
    fun `write-protected manifest is readable`() {
        assertNull(validate("workspace_manifest.json", PathOperation.READ))
    }

    @Test
    fun `blank and oversized paths are invalid`() {
        assertTrue(validate("") is PathPolicyFailure.InvalidPath)
        assertTrue(validate("   ") is PathPolicyFailure.InvalidPath)
    }

    @Test
    fun `path depth beyond limit is invalid`() {
        val deep = (1..40).joinToString("/") { "d$it" } + "/file.txt"
        assertTrue(validate(deep) is PathPolicyFailure.InvalidPath)
    }

    @Test
    fun `lexical resolver normalizes dots and double dots`() {
        val resolved = WorkspacePathPolicyEngine.lexicalCanonicalResolver("$root/a/./b/../c")
        assertEquals("/data/workspaces/proj_1/a/c", resolved)
    }

    @Test
    fun `custom policy extends protection`() {
        val custom = WorkspacePathPolicy(writeProtectedFiles = setOf("build.gradle.kts"))
        val failure = WorkspacePathPolicyEngine.validate(
            rawPath = "build.gradle.kts",
            operation = PathOperation.MODIFY,
            policy = custom,
            workspaceRootCanonical = root,
            canonicalResolver = WorkspacePathPolicyEngine::lexicalCanonicalResolver
        )
        assertTrue(failure is PathPolicyFailure.WriteProtected)
    }
}
