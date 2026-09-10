package com.example.domain

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.context.PrincipalType
import com.example.domain.core.context.ResourceScope
import com.example.domain.core.context.ScopeGrant
import com.example.domain.core.context.ScopePermission
import com.example.domain.core.context.ScopeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * REPAIR ORDER §4/§6/§8 — SCOPE RULES UNIT TESTS (single semantics)
 * ============================================================================
 * Proves the three structural invariants:
 *   1. a CHILD scope may access permitted ANCESTOR-SHARED resources;
 *   2. a SIBLING scope may NOT access another sibling's private resources;
 *   3. a PARENT scope may NOT automatically access child-PRIVATE resources;
 * plus: no implicit cross-project fallback; explicit grants widen access.
 */
class ScopeRulesTest {

    private val wsA = "wsA"
    private val wsB = "wsB"

    private fun project(ws: String, id: Long, shared: Boolean = false) =
        ResourceScope.Project(ws, id, sharedUpward = shared)

    private fun session(ws: String, project: Long?) = ResourceScope.Session(ws, project, "sess_1")
    private fun task(ws: String, project: Long?) = ResourceScope.Task(ws, project, null, "task_1")

    // --- Invariant 1: child → ancestor-shared -------------------------

    @Test
    fun `project may access workspace-shared resources but not workspace-private`() {
        val sharedWorkspaceResource = ResourceScope.Workspace(wsA, sharedUpward = true)
        val privateWorkspaceResource = ResourceScope.Workspace(wsA, sharedUpward = false)
        val childProject = project(wsA, 1L)

        assertTrue(ScopeRules.canAccess(childProject, sharedWorkspaceResource))
        // A parent's PRIVATE resource is NOT automatically visible to children.
        assertFalse(ScopeRules.canAccess(childProject, privateWorkspaceResource))
    }

    @Test
    fun `task may access its own project-scope resources`() {
        val taskScope = task(wsA, 1L)
        val ownProject = project(wsA, 1L, shared = true)
        assertTrue(ScopeRules.canAccess(taskScope, ownProject))
    }

    @Test
    fun `application resources are ancestor-shared to every scope`() {
        val taskScope = task(wsB, 42L)
        assertTrue(ScopeRules.canAccess(taskScope, ResourceScope.Application))
    }

    // --- Invariant 2: sibling isolation ---------------------------------

    @Test
    fun `project A cannot access project B private resources - same workspace`() {
        val a = project(wsA, 1L)
        val b = project(wsA, 2L)
        assertFalse(ScopeRules.canAccess(a, b))
        assertTrue(ScopeRules.areSiblings(a, b))
    }

    @Test
    fun `project A cannot access project B even when B is workspace-shared`() {
        // Workspace-shared means shared with the WORKSPACE scope (ancestor),
        // NOT with sibling projects.
        val a = project(wsA, 1L)
        val b = project(wsA, 2L, shared = true)
        assertFalse(ScopeRules.canAccess(a, b))
    }

    @Test
    fun `session of project A cannot access project B private resources`() {
        val sessionA = session(wsA, 1L)
        val projectB = project(wsA, 2L)
        assertFalse(ScopeRules.canAccess(sessionA, projectB))
    }

    // --- Invariant 3: parent does not auto-access child-private ---------

    @Test
    fun `workspace cannot automatically access project-private resources`() {
        val workspaceScope = ResourceScope.Workspace(wsA)
        val projectPrivate = project(wsA, 1L, shared = false)
        assertFalse(ScopeRules.canAccess(workspaceScope, projectPrivate))
    }

    @Test
    fun `workspace CAN access project resources explicitly shared upward`() {
        val workspaceScope = ResourceScope.Workspace(wsA)
        val projectShared = project(wsA, 1L, shared = true)
        assertTrue(ScopeRules.canAccess(workspaceScope, projectShared))
    }

    // --- Cross-workspace isolation ---------------------------------------

    @Test
    fun `cross-workspace access is always denied without a grant`() {
        val a = project(wsA, 1L)
        val b = project(wsB, 1L) // same project id, DIFFERENT workspace
        assertFalse(ScopeRules.canAccess(a, b))
        assertFalse(ScopeRules.areSiblings(a, b)) // not even siblings
    }

    @Test
    fun `same-scope access is allowed`() {
        val a = project(wsA, 1L)
        assertTrue(ScopeRules.canAccess(a, a))
    }

    // --- Explicit grants widen access (§8) -------------------------------

    @Test
    fun `explicit grant permits cross-sibling access for the granted permission`() {
        val a = project(wsA, 1L)
        val b = project(wsA, 2L)
        val grant = ScopeGrant(
            id = "g1",
            resourceScope = b,
            resourceType = "FILE",
            resourceId = "secret.md",
            permission = ScopePermission.READ,
            grantedToScope = a,
            grantedBy = "user",
            createdAtEpochMs = 1L
        )
        assertTrue(ScopeRules.canAccess(a, b, ScopePermission.READ, listOf(grant)))
        // A READ grant does NOT authorize READ_WRITE.
        assertFalse(ScopeRules.canAccess(a, b, ScopePermission.READ_WRITE, listOf(grant)))
    }

    @Test
    fun `expired grants do not authorize`() {
        val a = project(wsA, 1L)
        val b = project(wsA, 2L)
        val grant = ScopeGrant(
            id = "g2", resourceScope = b, resourceType = "FILE", resourceId = "f",
            permission = ScopePermission.READ, grantedToScope = a, grantedBy = "user",
            createdAtEpochMs = 1L, expiresAtEpochMs = System.currentTimeMillis() - 1_000
        )
        assertFalse(ScopeRules.canAccess(a, b, ScopePermission.READ, listOf(grant)))
    }

    @Test
    fun `READ_WRITE grant covers READ`() {
        val a = project(wsA, 1L)
        val b = project(wsA, 2L)
        val grant = ScopeGrant(
            id = "g3", resourceScope = b, resourceType = "FILE", resourceId = "f",
            permission = ScopePermission.READ_WRITE, grantedToScope = a, grantedBy = "user",
            createdAtEpochMs = 1L
        )
        assertTrue(ScopeRules.canAccess(a, b, ScopePermission.READ, listOf(grant)))
        assertTrue(ScopeRules.canAccess(a, b, ScopePermission.READ_WRITE, listOf(grant)))
    }

    // --- Ancestor resolution ----------------------------------------------

    @Test
    fun `ancestor resolution follows the hierarchy`() {
        val taskScope = task(wsA, 1L)
        assertTrue(ScopeRules.isAncestorOf(ResourceScope.Application, taskScope))
        assertTrue(ScopeRules.isAncestorOf(ResourceScope.Workspace(wsA), taskScope))
        assertTrue(ScopeRules.isAncestorOf(project(wsA, 1L), taskScope))
        assertFalse(ScopeRules.isAncestorOf(project(wsA, 2L), taskScope))
        assertFalse(ScopeRules.isAncestorOf(ResourceScope.Workspace(wsB), taskScope))
    }

    @Test
    fun `scopes describe themselves stably`() {
        assertEquals("PROJECT:wsA/1", project(wsA, 1L).describe())
        assertEquals("WORKSPACE:wsA", ResourceScope.Workspace(wsA).describe())
        assertEquals("APPLICATION", ResourceScope.Application.describe())
    }

    @Test
    fun `principal types are distinct`() {
        // PrincipalType exists and has exactly the governance-relevant values.
        assertEquals(3, PrincipalType.entries.size)
    }

    @Test
    fun `capability types used by governance filters exist`() {
        // Sanity: TOOL_EXECUTION (the §3B agent-capability binding key).
        assertTrue(CapabilityType.entries.contains(CapabilityType.TOOL_EXECUTION))
    }
}
