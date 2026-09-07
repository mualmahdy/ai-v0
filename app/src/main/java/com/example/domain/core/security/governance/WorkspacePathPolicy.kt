package com.example.domain.core.security.governance

/**
 * ============================================================================
 * Workspace Path Policy — Phase 1 (Secure File System)
 * ============================================================================
 *
 * Pure domain logic governing every path a coding tool may touch.
 * NO tool may resolve a filesystem path without passing through this policy.
 *
 * Enforced invariants:
 *  1. CONTAINMENT: a resolved canonical path must stay inside the workspace
 *     root. `../` traversal, absolute-path injection, and encoded traversal
 *     sequences are denied.
 *  2. FORBIDDEN_TARGETS: `.git` internals, secrets stores and lock files are
 *     never writable or readable by agent tools.
 *  3. WRITE_PROTECTION: when the operation is a mutation, immutable entries
 *     (e.g. the workspace manifest) are refused.
 *  4. CANONICALITY: the decision is made ONLY on the canonical resolved path,
 *     never on the raw string — string-prefix checks are trivially bypassed.
 */

/** The kind of operation the path is about to be used for. */
enum class PathOperation(val code: String) {
    READ("READ"),
    WRITE("WRITE"),
    CREATE("CREATE"),
    MODIFY("MODIFY"),
    RENAME_SOURCE("RENAME_SOURCE"),
    RENAME_DESTINATION("RENAME_DESTINATION"),
    DELETE("DELETE"),
    LIST("LIST"),
    SEARCH("SEARCH")
}

/** Structured path policy failures (machine-consumable). */
sealed interface PathPolicyFailure {
    data class ContainmentViolation(val rawPath: String, val canonicalPath: String) : PathPolicyFailure
    data class ForbiddenTarget(val rawPath: String, val reason: String) : PathPolicyFailure
    data class WriteProtected(val rawPath: String) : PathPolicyFailure
    data class InvalidPath(val rawPath: String, val reason: String) : PathPolicyFailure
}

/** Policy configuration (immutable value object). */
data class WorkspacePathPolicy(
    val forbiddenDirectories: Set<String> = setOf(".git", ".ssh"),
    val forbiddenFileNamePatterns: List<Regex> = listOf(
        Regex("""(?i)^secrets?\.(properties|json|xml|txt|env|defaults\.properties)$"""),
        Regex("""(?i)^\.env(\..+)?$"""),
        Regex("""(?i)^.*\.keystore$"""),
        Regex("""(?i)^.*(password|credential|apikey|api_key|secret)[^/\\]*\.(json|txt|xml|properties)$""")
    ),
    val writeProtectedFiles: Set<String> = setOf("workspace_manifest.json"),
    val maxPathDepth: Int = 32,
    val maxPathComponentLength: Int = 255
) {
    companion object {
        val Default = WorkspacePathPolicy()
    }
}

/**
 * The policy decision engine. PURE — no I/O, deterministic, fully testable.
 * Callers must provide a canonical resolver function appropriate to their
 * platform (java.io.File.getCanonicalPath in production).
 */
object WorkspacePathPolicyEngine {

    /**
     * Validates [rawRelativePath] (or an absolute attacker-supplied path)
     * against [policy] relative to [workspaceRootCanonical].
     *
     * [canonicalResolver] MUST return the canonical absolute path for the
     * joined (root + candidate) path — production uses File.getCanonicalPath.
     */
    fun validate(
        rawPath: String,
        operation: PathOperation,
        policy: WorkspacePathPolicy = WorkspacePathPolicy.Default,
        workspaceRootCanonical: String,
        canonicalResolver: (String) -> String
    ): PathPolicyFailure? {
        // --- 0. Basic sanity ------------------------------------------------
        if (rawPath.isBlank()) {
            return PathPolicyFailure.InvalidPath(rawPath, "المسار فارغ.")
        }
        if (rawPath.length > 4096) {
            return PathPolicyFailure.InvalidPath(rawPath, "المسار يتجاوز الحد الأقصى للطول.")
        }

        val normalizedRaw = rawPath.replace('\\', '/')

        // --- 1. Forbidden targets FIRST (before any resolution) -------------
        val segments = normalizedRaw.split('/').filter { it.isNotBlank() && it != "." }
        if (segments.size > policy.maxPathDepth) {
            return PathPolicyFailure.InvalidPath(rawPath, "عمق المسار يتجاوز الحد المسموح (${policy.maxPathDepth}).")
        }
        for (segment in segments) {
            if (segment.length > policy.maxPathComponentLength) {
                return PathPolicyFailure.InvalidPath(rawPath, "مكوّن مسار يتجاوز الحد المسموح للطول.")
            }
            if (segment in policy.forbiddenDirectories) {
                return PathPolicyFailure.ForbiddenTarget(rawPath, "الوصول إلى دليل محظور أمنياً: $segment")
            }
            if (policy.forbiddenFileNamePatterns.any { it.matches(segment) }) {
                return PathPolicyFailure.ForbiddenTarget(rawPath, "الوصول إلى ملف محظور أمنياً: $segment")
            }
        }

        // --- 2. Canonical resolution — decision on the RESOLVED path -------
        val candidateRoot = workspaceRootCanonical.trimEnd('/') + "/"
        val joined = if (normalizedRaw.startsWith("/")) {
            // Attacker-supplied absolute path: treat as relative to root,
            // then let canonical resolution catch the escape.
            candidateRoot + normalizedRaw.trimStart('/')
        } else {
            candidateRoot + normalizedRaw
        }

        val canonical: String = try {
            canonicalResolver(joined)
        } catch (t: Throwable) {
            return PathPolicyFailure.InvalidPath(rawPath, "تعذر تحليل المسار قانونياً: ${t.message ?: "غير معروف"}")
        }

        // --- 3. Containment on canonical form ------------------------------
        if (!canonical.startsWith(candidateRoot)) {
            return PathPolicyFailure.ContainmentViolation(rawPath, canonical)
        }

        // --- 4. Write protection for mutations ------------------------------
        val fileName = segments.lastOrNull() ?: ""
        val relativeForProtection = segments.joinToString("/")
        if (operation != PathOperation.READ && operation != PathOperation.LIST && operation != PathOperation.SEARCH) {
            if (relativeForProtection in policy.writeProtectedFiles || fileName in policy.writeProtectedFiles) {
                return PathPolicyFailure.WriteProtected(rawPath)
            }
        }

        return null // allowed
    }

    /**
     * Convenient pure resolver for in-memory/test roots: normalizes the
     * joined path lexically WITHOUT touching the filesystem. Production
     * adapters pass a File-based canonical resolver instead.
     */
    fun lexicalCanonicalResolver(joinedPath: String): String {
        val parts = ArrayDeque<String>()
        for (segment in joinedPath.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> { /* skip */ }
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }
}
